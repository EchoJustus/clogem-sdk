;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.sdk.manifest
  "The module manifest contract: `manifest.edn` is a module's single source
   of truth for its tools, resources, prompts, bus events, migrations, UI
   card and URI routes (PD-7).

   This namespace reads a manifest with `clojure.edn` (no eval), validates
   its shape against the meta-schema, applies the semantic rules the shape
   cannot express, converts malli input schemas to JSON Schema for the MCP
   edge, and backs `bb manifest:check`."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as mjs]
            [clogem.api :as api]))

;; ---------------------------------------------------------------------------
;; Lexical rules

(def module-id-re #"^[a-z][a-z0-9-]*$")
(def tool-name-re #"^[a-z][a-z0-9_]{0,63}$")
(def property-name-re #"^[A-Za-z0-9_.-]{1,64}$")
(def action-re #"^[a-z][a-z0-9-]*$")
(def semver-re
  #"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$")
(def resource-uri-re
  "clogem://<module>/<kind>/<id>"
  #"^clogem://[a-z][a-z0-9-]*/[A-Za-z0-9_.-]+/[A-Za-z0-9_.~%-]+$")
(def resource-template-re
  "clogem://<module>/<kind>/{var}, RFC 6570 level 1 variables."
  #"^clogem://[a-z][a-z0-9-]*/[A-Za-z0-9_.-]+/(\{[A-Za-z_][A-Za-z0-9_]*\}|[A-Za-z0-9_.~%-]+)(/(\{[A-Za-z_][A-Za-z0-9_]*\}|[A-Za-z0-9_.~%-]+))*$")

(def profiles
  "Client profiles a tool, resource or prompt can be exposed to."
  #{:gui :llm-small :llm-large :admin})

;; ---------------------------------------------------------------------------
;; Meta-schema (manifest version 1)

(def ModuleId
  [:and :keyword
   [:fn {:error/message "must be an unqualified keyword matching ^[a-z][a-z0-9-]*$"}
    (fn [k] (and (keyword? k) (nil? (namespace k)) (boolean (re-matches module-id-re (name k)))))]])

(def SemVer [:re {:error/message "must be a semantic version such as 0.1.0"} semver-re])
(def ToolName [:re {:error/message "tool names match ^[a-z][a-z0-9_]{0,63}$"} tool-name-re])
(def Profiles [:set {:min 1} (into [:enum] (sort profiles))])

(def Annotations
  [:map {:closed true}
   [:title {:optional true} :string]
   [:readOnlyHint {:optional true} :boolean]
   [:destructiveHint {:optional true} :boolean]
   [:idempotentHint {:optional true} :boolean]
   [:openWorldHint {:optional true} :boolean]])

(def Tool
  [:map {:closed true}
   [:name ToolName]
   [:title {:optional true} :string]
   [:description [:string {:min 1 :max 1000}]]
   [:handler :qualified-symbol]
   [:input :any]
   [:output {:optional true} :any]
   [:annotations {:optional true} Annotations]
   [:meta {:optional true} [:map-of :string :any]]
   [:profiles Profiles]])

(def Resource
  [:map {:closed true}
   [:uri [:re {:error/message "resource URIs look like clogem://<module>/<kind>/<id>"} resource-uri-re]]
   [:name :string]
   [:title {:optional true} :string]
   [:description {:optional true} :string]
   [:mime-type {:optional true} :string]
   [:handler :qualified-symbol]
   [:profiles Profiles]])

(def ResourceTemplate
  [:map {:closed true}
   [:uri-template [:re {:error/message "templates look like clogem://<module>/<kind>/{id}"} resource-template-re]]
   [:name :string]
   [:title {:optional true} :string]
   [:description {:optional true} :string]
   [:mime-type {:optional true} :string]
   [:handler :qualified-symbol]
   [:profiles Profiles]])

(def PromptArgument
  [:map {:closed true}
   [:name :string]
   [:description {:optional true} :string]
   [:required {:optional true} :boolean]])

(def Prompt
  [:map {:closed true}
   [:name :string]
   [:title {:optional true} :string]
   [:description {:optional true} :string]
   [:arguments {:optional true} [:vector PromptArgument]]
   [:handler :qualified-symbol]
   [:profiles Profiles]])

(def Route
  "A `clogem://<module>/<action>?…` link route."
  [:map {:closed true}
   [:action [:re {:error/message "actions match ^[a-z][a-z0-9-]*$"} action-re]]
   [:handler :qualified-symbol]
   [:params {:optional true} :any]])

(def Card
  [:map {:closed true}
   [:title :string]
   [:icon :string]
   [:quick-actions [:vector ToolName]]])

(def Manifest
  "Meta-schema of `manifest.edn`, version 1."
  [:map {:closed true}
   [:manifest/version [:= 1]]
   [:module/id ModuleId]
   [:module/version SemVer]
   [:module/license [:enum :epl-2.0 :apache-2.0 :proprietary]]
   [:module/description [:string {:min 1 :max 200}]]
   [:module/entry :qualified-symbol]
   [:module/requires
    [:map {:closed true}
     [:clogem/api [:re {:error/message "the clogem.api major version, e.g. \"1\""} #"^\d+$"]]
     [:bins [:vector :string]]
     [:env [:set :string]]]]
   [:bus
    [:map {:closed true}
     [:publishes [:map-of :qualified-keyword :any]]
     [:subscribes [:set :qualified-keyword]]]]
   [:db [:map {:closed true} [:migrations [:maybe :string]]]]
   [:mcp
    [:map {:closed true}
     [:tools [:vector Tool]]
     [:resources [:vector Resource]]
     [:resource-templates [:vector ResourceTemplate]]
     [:prompts [:vector Prompt]]]]
   [:ui
    [:map {:closed true}
     [:card Card]
     [:routes [:vector Route]]]]])

;; ---------------------------------------------------------------------------
;; Reading

(defn read-manifest
  "Read a manifest file with `clojure.edn` (no reader eval, no custom
   readers). Throws ex-info when the file is not a single EDN map."
  [path]
  (let [value (edn/read-string {:eof ::eof} (slurp (str path)))]
    (when-not (map? value)
      (throw (ex-info (str "manifest is not an EDN map: " path) {:path (str path)})))
    value))

;; ---------------------------------------------------------------------------
;; Shape errors, humanized

(defn- flatten-errors
  "[\"path: message\" ...] from a malli.error/humanize tree."
  ([tree] (flatten-errors [] tree))
  ([path tree]
   (cond
     (map? tree) (mapcat (fn [[k v]] (flatten-errors (conj path k) v)) tree)
     (and (coll? tree) (every? string? tree))
     [(str (if (seq path)
             (str/join "." (map #(if (keyword? %) (subs (str %) 1) (str %)) path))
             "manifest")
           ": " (str/join "; " (sort tree)))]
     (sequential? tree) (mapcat (fn [[i v]] (flatten-errors (conj path i) v)) (map-indexed vector tree))
     (set? tree) (mapcat #(flatten-errors path %) tree)
     (string? tree) [(str (str/join "." (map str path)) ": " tree)]
     :else [])))

(defn shape-problems
  "Humanized meta-schema violations of `manifest`, or an empty vector."
  [manifest]
  (if-let [ex (m/explain Manifest manifest)]
    (vec (flatten-errors (me/humanize ex)))
    []))

;; ---------------------------------------------------------------------------
;; JSON Schema at the MCP edge

(defn malli-schema
  "The malli schema object for `data`, or nil when `data` is not a valid schema."
  [data]
  (try (m/schema data) (catch Exception _ nil)))

(defn json-schema
  "JSON Schema (as a Clojure map with keyword keys) for a malli schema.
   Closed maps become `additionalProperties false`; keywords, uuids and
   enums become strings, which is what hosts expect."
  [schema]
  (mjs/transform (m/schema schema)))

(defn input-schema-problems
  "Rules a tool's `:input` must satisfy to be a usable MCP input schema:
   a valid malli schema that converts to a JSON Schema whose root is an
   object without root-level anyOf/oneOf/allOf, with property names
   matching [A-Za-z0-9_.-]{1,64}."
  [input]
  (if-let [schema (malli-schema input)]
    (let [js (mjs/transform schema)
          props (keys (:properties js))]
      (-> []
          (cond-> (not= "object" (:type js))
            (conj (str "input schema root must be an object, got " (pr-str (:type js)))))
          (cond-> (some #(contains? js %) [:anyOf :oneOf :allOf])
            (conj "input schema root must not use anyOf/oneOf/allOf"))
          (into (for [p props
                      :let [n (if (keyword? p) (subs (str p) 1) (str p))]
                      :when (not (re-matches property-name-re n))]
                  (str "input property name " (pr-str n) " must match [A-Za-z0-9_.-]{1,64}")))))
    ["input is not a valid malli schema"]))

;; ---------------------------------------------------------------------------
;; Semantic rules

(defn tool-prefix
  "The prefix every tool of module `id` must carry: id with dashes as
   underscores, plus `_`."
  [id]
  (str (str/replace (name id) "-" "_") "_"))

(defn module-ns-prefix
  "Namespace prefix a module's code lives under."
  [id]
  (str "clogem.module." (name id)))

(defn under-module-ns?
  "True when qualified symbol `sym` resolves inside the module's namespace prefix."
  [id sym]
  (let [prefix (module-ns-prefix id)
        ns (namespace sym)]
    (boolean (and ns (or (= ns prefix) (str/starts-with? ns (str prefix ".")))))))

(defn api-compatible?
  "True when the manifest requires this SDK's clogem.api major version."
  [manifest]
  (= (str api/major) (get-in manifest [:module/requires :clogem/api])))

(defn- duplicates [xs]
  (->> xs frequencies (filter (fn [[_ n]] (> n 1))) (map first) sort))

(defn semantic-problems
  "Rules beyond the shape, for a manifest that already passed `shape-problems`."
  [{:module/keys [id entry] :as manifest}]
  (let [prefix (tool-prefix id)
        tools (get-in manifest [:mcp :tools])
        tool-names (set (map :name tools))
        uris (concat (map :uri (get-in manifest [:mcp :resources]))
                     (map :uri-template (get-in manifest [:mcp :resource-templates])))
        handlers (concat (map :handler tools)
                         (map :handler (get-in manifest [:mcp :resources]))
                         (map :handler (get-in manifest [:mcp :resource-templates]))
                         (map :handler (get-in manifest [:mcp :prompts]))
                         (map :handler (get-in manifest [:ui :routes])))
        own-uri-prefix (str "clogem://" (name id) "/")]
    (-> []
        (cond-> (not (api-compatible? manifest))
          (conj (str "module/requires: clogem/api must be \"" api/major "\" for this runtime, got "
                     (pr-str (get-in manifest [:module/requires :clogem/api])))))
        (cond-> (not (under-module-ns? id entry))
          (conj (str "module/entry " entry " must resolve under " (module-ns-prefix id) ".")))
        (into (for [h handlers :when (not (under-module-ns? id h))]
                (str "handler " h " must resolve under " (module-ns-prefix id) ".")))
        (into (for [{:keys [name]} tools :when (not (str/starts-with? name prefix))]
                (str "tool " name " must be prefixed " prefix)))
        (into (for [d (duplicates (map :name tools))] (str "duplicate tool name " d)))
        (into (for [d (duplicates uris)] (str "duplicate resource URI " d)))
        (into (for [u uris :when (not (str/starts-with? u own-uri-prefix))]
                (str "resource URI " u " must start with " own-uri-prefix)))
        (into (for [{:keys [name input]} tools
                    p (input-schema-problems input)]
                (str "tool " name ": " p)))
        (into (for [{:keys [name output]} tools
                    :when (and (some? output) (nil? (malli-schema output)))]
                (str "tool " name ": output is not a valid malli schema")))
        (into (for [[type schema] (get-in manifest [:bus :publishes])
                    :when (nil? (malli-schema schema))]
                (str "bus/publishes " type ": not a valid malli schema")))
        (into (for [{:keys [params action]} (get-in manifest [:ui :routes])
                    :when (and (some? params) (nil? (malli-schema params)))]
                (str "ui/routes " action ": params is not a valid malli schema")))
        (into (for [qa (get-in manifest [:ui :card :quick-actions])
                    :when (not (contains? tool-names qa))]
                (str "ui/card quick-action " qa " is not a tool of this module"))))))

(defn check
  "Validate a manifest value. Returns {:ok? boolean :problems [string ...]}.
   Semantic rules run only once the shape is valid."
  [manifest]
  (let [shape (shape-problems manifest)
        problems (if (seq shape) shape (semantic-problems manifest))]
    {:ok? (empty? problems) :problems (vec problems)}))

(defn check-file
  "Read and validate a manifest file. Returns {:ok? :problems :manifest :path}."
  [path]
  (try
    (let [manifest (read-manifest path)]
      (assoc (check manifest) :manifest manifest :path (str path)))
    (catch Exception e
      {:ok? false :problems [(str "cannot read manifest: " (ex-message e))] :path (str path)})))

(defn check-registry
  "Cross-manifest rules for a set of manifests loaded together: module ids,
   tool names and resource URIs must be unique across the registry.
   Returns a vector of problems."
  [manifests]
  (-> []
      (into (for [d (duplicates (map :module/id manifests))] (str "duplicate module id " d)))
      (into (for [d (duplicates (mapcat #(map :name (get-in % [:mcp :tools])) manifests))]
              (str "duplicate tool name across modules: " d)))
      (into (for [d (duplicates (mapcat #(concat (map :uri (get-in % [:mcp :resources]))
                                                 (map :uri-template (get-in % [:mcp :resource-templates])))
                                        manifests))]
              (str "duplicate resource URI across modules: " d)))))

;; ---------------------------------------------------------------------------
;; bb manifest:check

(defn -main
  "Validate every manifest path given, then the registry-wide uniqueness
   rules across them. Prints one line per problem and exits 1 on any
   problem, 2 when no path is given."
  [& paths]
  (if (empty? paths)
    (do (println "usage: bb manifest:check <manifest.edn> ...")
        (System/exit 2))
    (let [results (mapv check-file paths)
          cross (when (every? :ok? results) (check-registry (map :manifest results)))]
      (doseq [{:keys [ok? problems path]} results]
        (doseq [p problems] (println (str path ": " p)))
        (println (str path ": " (if ok? "OK" (str (count problems) " problem(s)")))))
      (doseq [p cross] (println (str "across manifests: " p)))
      (System/exit (if (and (every? :ok? results) (empty? cross)) 0 1)))))
