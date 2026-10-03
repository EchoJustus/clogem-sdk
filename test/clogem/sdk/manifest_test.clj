;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.sdk.manifest-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.sdk.manifest :as manifest]))

(def echo-path "examples/echo/resources/clogem/module/echo/manifest.edn")

(def echo (manifest/read-manifest echo-path))

(defn- problems [m] (:problems (manifest/check m)))

(defn- problem-matching [m re]
  (some #(re-find re %) (problems m)))

(deftest the-echo-example-is-valid
  (is (= {:ok? true :problems []} (manifest/check echo)))
  (is (:ok? (manifest/check-file echo-path))))

(deftest read-manifest-uses-edn-only
  (let [dir (fs/create-temp-dir {:prefix "clogem-manifest"})]
    (try
      (spit (str (fs/path dir "evil.edn")) "#=(+ 1 2)")
      (is (thrown? Exception (manifest/read-manifest (fs/path dir "evil.edn"))) "no reader eval")
      (spit (str (fs/path dir "vec.edn")) "[1 2 3]")
      (is (thrown? clojure.lang.ExceptionInfo (manifest/read-manifest (fs/path dir "vec.edn"))) "must be a map")
      (is (false? (:ok? (manifest/check-file (fs/path dir "missing.edn")))))
      (finally (fs/delete-tree dir)))))

(deftest shape-rules-are-enforced-with-readable-messages
  (testing "missing and unknown keys"
    (is (problem-matching (dissoc echo :module/version) #"module/version"))
    (is (problem-matching (assoc echo :extra 1) #"extra.*disallowed")))
  (testing "lexical rules"
    (is (problem-matching (assoc echo :module/id :Echo) #"module/id"))
    (is (problem-matching (assoc echo :module/id :ns/echo) #"module/id"))
    (is (problem-matching (assoc echo :module/version "1.0") #"module/version.*semantic"))
    (is (problem-matching (assoc echo :module/license :mit) #"module/license"))
    (is (problem-matching (assoc echo :module/entry 'unqualified) #"module/entry"))
    (is (problem-matching (assoc-in echo [:mcp :tools 0 :name] "Echo-Say") #"tool names match"))
    (is (problem-matching (assoc-in echo [:mcp :tools 0 :profiles] #{}) #"profiles"))
    (is (problem-matching (assoc-in echo [:mcp :tools 0 :profiles] #{:root}) #"profiles"))
    (is (problem-matching (assoc-in echo [:mcp :tools 0 :description] "") #"description"))
    (is (problem-matching (assoc-in echo [:mcp :tools 0 :annotations :readOnlyHint] "yes") #"readOnlyHint"))
    (is (problem-matching (assoc-in echo [:module/requires :clogem/api] "1.0") #"clogem/api")))
  (testing "shape problems stop semantic checks"
    (is (= 1 (count (problems (dissoc echo :ui)))))))

(deftest semantic-rules-are-enforced
  (testing "tool prefix is the module id"
    (is (problem-matching (assoc-in echo [:mcp :tools 0 :name] "say") #"must be prefixed echo_"))
    (is (= "my_mod_" (manifest/tool-prefix :my-mod))))
  (testing "entry and handlers resolve under clogem.module.<id>."
    (is (problem-matching (assoc echo :module/entry 'clogem.hub.core/module) #"module/entry .* must resolve under clogem.module.echo"))
    (is (problem-matching (assoc-in echo [:mcp :tools 0 :handler] 'clogem.module.other.tools/say) #"handler .* must resolve under"))
    (is (manifest/under-module-ns? :echo 'clogem.module.echo/x))
    (is (manifest/under-module-ns? :echo 'clogem.module.echo.deep.ns/x))
    (is (not (manifest/under-module-ns? :echo 'clogem.module.echoes/x))))
  (testing "input schema must be a root object without root combinators and with safe property names"
    (is (problem-matching (assoc-in echo [:mcp :tools 0 :input] :string) #"root must be an object"))
    (is (problem-matching (assoc-in echo [:mcp :tools 0 :input] [:or [:map [:a :int]] [:map [:b :int]]]) #"anyOf/oneOf/allOf"))
    (is (problem-matching (assoc-in echo [:mcp :tools 0 :input] [:map [:bad/key :int]]) #"property name"))
    (is (problem-matching (assoc-in echo [:mcp :tools 0 :input] [:map [:x :nope]]) #"not a valid malli schema"))
    (is (problem-matching (assoc-in echo [:mcp :tools 0 :output] [:map [:x :nope]]) #"output is not a valid malli schema")))
  (testing "uniqueness, URIs, quick actions, bus schemas, api version"
    (let [two-tools (update-in echo [:mcp :tools] #(conj % (first %)))]
      (is (problem-matching two-tools #"duplicate tool name echo_say")))
    (is (problem-matching (assoc-in echo [:ui :card :quick-actions] ["echo_other"]) #"quick-action echo_other"))
    (is (problem-matching (assoc-in echo [:bus :publishes :echo/said] [:map [:x :nope]]) #"bus/publishes"))
    (is (problem-matching (assoc-in echo [:module/requires :clogem/api] "2") #"clogem/api must be \"1\""))
    (let [with-resource (assoc-in echo [:mcp :resources]
                                  [{:uri "clogem://other/kind/id" :name "x"
                                    :handler 'clogem.module.echo.tools/say :profiles #{:admin}}])]
      (is (problem-matching with-resource #"must start with clogem://echo/")))))

(deftest json-schema-conversion
  (let [js (manifest/json-schema (get-in echo [:mcp :tools 0 :input]))]
    (is (= "object" (:type js)))
    (is (= false (:additionalProperties js)))
    (is (= [:text] (:required js)))
    (is (= {:type "string" :minLength 1 :maxLength 1000} (get-in js [:properties :text])))
    (is (= {:type "boolean"} (get-in js [:properties :upcase])))))

(deftest registry-wide-uniqueness
  (let [other (-> echo
                  (assoc :module/id :other :module/entry 'clogem.module.other.core/module)
                  (assoc-in [:mcp :tools 0 :handler] 'clogem.module.other.tools/say)
                  (assoc-in [:mcp :tools 0 :name] "other_say")
                  (assoc-in [:ui :card :quick-actions] ["other_say"]))]
    (is (empty? (manifest/check-registry [echo other])))
    (is (= ["duplicate module id :echo" "duplicate tool name across modules: echo_say"]
           (manifest/check-registry [echo echo])))
    (is (some #(str/includes? % "duplicate tool name across modules")
              (manifest/check-registry [echo (assoc-in other [:mcp :tools 0 :name] "echo_say")])))))
