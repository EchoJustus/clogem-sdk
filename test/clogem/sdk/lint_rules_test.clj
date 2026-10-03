;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.sdk.lint-rules-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.sdk.lint :as lint]))

(def apache (:apache-2.0 lint/header-lines))

(defn- headed [body]
  (str (str/join "\n" (lint/render-header apache ";;")) "\n\n" body))

(defn- write! [dir rel content]
  (let [p (fs/path dir rel)]
    (fs/create-dirs (fs/parent p))
    (spit (str p) content)))

(defn- rules-for [violations file]
  (set (map :rule (filter #(= file (:file %)) violations))))

(deftest ns-parsing
  (is (= {:name "a.b" :requires ["clojure.string" "c.d" "e.f"]}
         (lint/ns-decl (lint/read-forms "(ns a.b \"doc\" (:require [clojure.string :as str] c.d #?(:clj [e.f] :cljs [g.h])))"))))
  (is (nil? (lint/ns-decl (lint/read-forms "(println 1)"))))
  (is (= ["(ns x)" ":user/k" ":al/k"] (map str (lint/read-forms "(ns x) ::k ::al/k"))) "auto-resolved keywords read")
  (is (= "echo" (lint/module-id-of "clogem.module.echo.tools")))
  (is (= "echo" (lint/module-id-of "clogem.module.echo")))
  (is (nil? (lint/module-id-of "clogem.hub.registry")))
  (is (lint/prefix-of? "clogem.hub" "clogem.hub.db.writer"))
  (is (not (lint/prefix-of? "clogem.hub" "clogem.hubris"))))

(deftest every-rule-fires-on-a-synthetic-tree
  (let [dir (fs/create-temp-dir {:prefix "clogem-lint-rules"})
        opts {:root (str dir) :license :apache-2.0
              :namespaces ["clogem.hub" "clogem.module.system"]
              :pod-namespaces ["clogem.hub.db"]
              :log-namespace "clogem.hub.log"
              :no-print-prefixes ["clogem.hub" "clogem.module"]}]
    (try
      (write! dir "src/clogem/hub/log.clj" (headed "(ns clogem.hub.log) (defn log! [m] (binding [*out* *err*] (prn m)))"))
      (write! dir "src/clogem/hub/ok.clj" (headed "(ns clogem.hub.ok (:require [clogem.hub.log :as log])) (defn f [] (log/log! {}))"))
      (write! dir "src/clogem/hub/noisy.clj" (headed "(ns clogem.hub.noisy) (defn f [] (println \"hi\") (clojure.core/prn 1))"))
      (write! dir "src/clogem/hub/db/core.clj" (headed "(ns clogem.hub.db.core (:require [babashka.pods :as pods])) (pods/load-pod 'org.babashka/go-sqlite3 \"0.1.0\")"))
      (write! dir "src/clogem/hub/sneaky.clj" (headed "(ns clogem.hub.sneaky) (babashka.pods/load-pod 'x \"1\")"))
      (write! dir "src/clogem/other/ns.clj" (headed "(ns clogem.other.ns)"))
      (write! dir "src/clogem/module/system/good.clj" (headed "(ns clogem.module.system.good (:require [clojure.string :as str] [clogem.api :as api] [clogem.module.system.util :as u]))"))
      (write! dir "src/clogem/module/system/bad.clj" (headed "(ns clogem.module.system.bad (:require [clogem.hub.registry :as r] [clogem.module.media.core :as m] [babashka.process :as p] [clojure.java.shell :as sh] [clogem.sdk.manifest :as mf]))"))
      (write! dir "src/clogem/module/system/loud.clj" (headed "(ns clogem.module.system.loud) (defn f [] (print 1))"))
      (write! dir "test/clogem/module/system/good_test.clj" (headed "(ns clogem.module.system.good-test (:require [clojure.test :refer [deftest]] [clogem.sdk.testkit :as tk])) (println \"tests may print\")"))
      (write! dir "test/clogem/module/system/bad_test.clj" (headed "(ns clogem.module.system.bad-test (:require [clogem.sdk.lint :as l]))"))
      (write! dir "test/clogem/hub_test.clj" (headed "(ns clogem.hub-test)"))
      (write! dir "test/clogem/elsewhere_test.clj" (headed "(ns clogem.elsewhere-test)"))
      (write! dir "src/clogem/hub/broken.clj" (headed "(ns clogem.hub.broken) (defn f [] (]"))
      (write! dir "src/clogem/hub/paths.clj" (headed (str "(ns clogem.hub.paths) (def p \"/home/" "alice/x\")")))
      (write! dir "resources/clogem/module/system/manifest.edn" (headed "{:manifest/version 1 :module/id :system}"))
      (write! dir "src/clogem/hub/unheaded.clj" "(ns clogem.hub.unheaded)")
      (let [{:keys [violations]} (lint/check opts)
            by-file (fn [f] (rules-for violations f))]
        (is (= #{} (by-file "src/clogem/hub/log.clj")) "the log namespace may print")
        (is (= #{} (by-file "src/clogem/hub/ok.clj")))
        (is (= #{:no-print} (by-file "src/clogem/hub/noisy.clj")))
        (is (= 2 (count (filter #(= "src/clogem/hub/noisy.clj" (:file %)) violations))) "both print forms")
        (is (= #{} (by-file "src/clogem/hub/db/core.clj")) "pods allowed under clogem.hub.db")
        (is (= #{:pods} (by-file "src/clogem/hub/sneaky.clj")))
        (is (= #{:namespace-ownership} (by-file "src/clogem/other/ns.clj")))
        (is (= #{} (by-file "src/clogem/module/system/good.clj")))
        (is (= #{:module-isolation} (by-file "src/clogem/module/system/bad.clj")))
        (is (= 5 (count (filter #(= "src/clogem/module/system/bad.clj" (:file %)) violations)))
            "hub, other module, process, shell and clogem.sdk.* are each reported")
        (is (= #{:no-print} (by-file "src/clogem/module/system/loud.clj")))
        (is (= #{} (by-file "test/clogem/module/system/good_test.clj")) "tests may print and use the test kit")
        (is (= #{:module-isolation} (by-file "test/clogem/module/system/bad_test.clj")) "clogem.sdk.lint is not the test kit")
        (is (= #{} (by-file "test/clogem/hub_test.clj")) "<prefix>-test belongs to the prefix")
        (is (= #{:namespace-ownership} (by-file "test/clogem/elsewhere_test.clj")))
        (is (= #{:unreadable} (by-file "src/clogem/hub/broken.clj")))
        (is (= #{:home-path} (by-file "src/clogem/hub/paths.clj")))
        (is (= #{:manifest} (by-file "resources/clogem/module/system/manifest.edn")))
        (is (= #{:header} (by-file "src/clogem/hub/unheaded.clj"))))
      (testing "rules are skipped when unconfigured"
        (let [{:keys [violations]} (lint/check {:root (str dir) :license :apache-2.0 :manifests? false})]
          (is (empty? (filter #(contains? #{:namespace-ownership :no-print :manifest} (:rule %)) violations)))
          (is (seq (filter #(= :pods (:rule %)) violations)) "pods are forbidden everywhere by default")
          (is (seq (filter #(= :module-isolation (:rule %)) violations)) "isolation always applies to module code")))
      (finally (fs/delete-tree dir)))))
