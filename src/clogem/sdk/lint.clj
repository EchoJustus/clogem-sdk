;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.sdk.lint
  "Fitness checks shared by every Clogem repository (PD-12: guardrails are
   executable). Each repository configures them from its own bb.edn `lint`
   task; this namespace carries no repository-specific names and no
   non-public license text.

   Rules:
   - :header            every authored source file starts with the license header
   - :namespace-ownership  a repo defines namespaces only under its own prefixes
   - :module-isolation  module code (clogem.module.<id>.*) requires only
                        clogem.api, its own namespaces and allowlisted libraries
                        (clogem.sdk.testkit in tests only); never clogem.hub.*,
                        another module, processes, pods or HTTP clients
   - :pods              babashka.pods only in the configured namespaces
   - :no-print          no print/println/prn/pr/printf in daemon or stdio
                        namespaces outside the log namespace
   - :manifest          every manifest.edn passes clogem.sdk.manifest/check
   - :home-path         no absolute home paths in source files

   Options (all optional except :root and :license or :header):
   :root, :license (:epl-2.0 | :apache-2.0) or :header [lines], :exclude-dirs,
   :source-dirs (default [\"src\"]), :test-dirs (default [\"test\"]),
   :namespaces (allowed prefixes; nil skips the rule), :module-allowed-requires,
   :pod-namespaces (default none), :log-namespace, :no-print-prefixes,
   :manifests? (default true)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [edamame.core :as edamame]
            [clogem.sdk.guard :as guard]
            [clogem.sdk.manifest :as manifest]))

;; ---------------------------------------------------------------------------
;; License headers

(def copyright-line
  "© 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)")

(def header-lines
  "Header text per public license, before the comment marker is applied.
   An empty string renders as a bare comment-marker line."
  {:epl-2.0
   [copyright-line
    ""
    "This program and the accompanying materials are made available under the"
    "terms of the Eclipse Public License 2.0 which is available at"
    "https://www.eclipse.org/legal/epl-2.0"
    ""
    "SPDX-License-Identifier: EPL-2.0"]
   :apache-2.0
   [copyright-line
    "Licensed under the Apache License, Version 2.0; see the LICENSE file."
    "SPDX-License-Identifier: Apache-2.0"]})

(def comment-marker
  "Line-comment marker by lower-cased file extension. Files with any other
   extension are not checked: Markdown, JSON, plain text and binaries carry
   no header."
  {"clj" ";;" "cljc" ";;" "cljs" ";;" "cljd" ";;" "bb" ";;" "edn" ";;"
   "dart" "//"
   "sh" "#" "bash" "#" "yaml" "#" "yml" "#"})

(def default-exclude-dirs
  "Directory names skipped at any depth: version control, caches, build
   output, generated trees and test fixtures (data, not authored source)."
  #{".git" ".cpcache" ".clj-kondo" ".lsp" ".dart_tool" "build" "node_modules"
    ".idea" ".vscode" "fixtures"})

(defn render-header
  "The exact lines expected at the top of a file that uses `marker` for
   line comments. `lines` is a vector of header lines (see header-lines)."
  [lines marker]
  (mapv (fn [line] (if (str/blank? line) marker (str marker " " line))) lines))

(defn- extension [path]
  (some-> (fs/extension path) str/lower-case))

(defn- shebang-marker
  "Marker for an extension-less script, from the interpreter named on its
   shebang line (the last path segment of the last word)."
  [first-line]
  (when (str/starts-with? first-line "#!")
    (let [interpreter (-> first-line (subs 2) str/trim (str/split #"\s+") last
                          (or "") (str/split #"/") last)]
      (if (contains? #{"bb" "clojure" "clj"} interpreter) ";;" "#"))))

(defn marker-for
  "Comment marker for `path`: from its extension, or from the shebang line
   of an extension-less script. nil means the file is not checked."
  [path first-line]
  (if-let [ext (extension path)]
    (get comment-marker ext)
    (shebang-marker first-line)))

(defn header-problem
  "nil when `content` starts with the header `lines` (after an optional
   shebang line); otherwise a human-readable reason."
  [lines path content]
  (let [content-lines (str/split-lines content)
        first-line (or (first content-lines) "")
        marker (marker-for path first-line)]
    (when marker
      (let [expected (render-header lines marker)
            body (if (str/starts-with? first-line "#!") (rest content-lines) content-lines)]
        (when-not (= expected (vec (take (count expected) body)))
          (str "missing or malformed license header; expected line 1: "
               (first expected)))))))

(defn- excluded? [exclude-dirs rel-path]
  (some exclude-dirs (map str (fs/components rel-path))))

(defn- require-directory! [root]
  (when-not (fs/directory? root)
    (throw (ex-info (str "lint root is not a directory: " root) {:root (str root)}))))

(defn source-files
  "Regular files under `root` whose extension (or shebang) is checkable,
   skipping excluded directory names. Symbolic links are not followed, so a
   workspace that links sibling repositories in place lints only its own
   tree. Returns [[absolute-path relative-path-string] ...]."
  [root exclude-dirs]
  (require-directory! root)
  (let [root (fs/canonicalize root)]
    (->> (fs/glob root "**" {:hidden true :follow-links false})
         (filter fs/regular-file?)
         (map (fn [f] [f (fs/relativize root f)]))
         (remove (fn [[_ rel]] (excluded? exclude-dirs rel)))
         (filter (fn [[f _]] (let [ext (extension f)]
                               (or (nil? ext) (contains? comment-marker ext)))))
         (map (fn [[f rel]] [f (str rel)]))
         (sort-by second))))

(defn resolve-header
  "The header lines for an options map: :header (explicit lines) wins over
   :license (a key of header-lines)."
  [{:keys [header license]}]
  (or header
      (get header-lines license)
      (throw (ex-info (str "unknown license " (pr-str license) " and no :header given")
                      {:known (vec (keys header-lines))}))))

(defn check-headers
  "Verify that every authored source file under :root carries the header
   (:header lines, or the :license template). Returns
   {:checked n :violations [{:file \"…\" :rule :header :reason \"…\"}]}."
  [{:keys [root exclude-dirs] :or {root "." exclude-dirs #{}} :as opts}]
  (let [lines (resolve-header opts)
        results (for [[f rel] (source-files root (into default-exclude-dirs exclude-dirs))
                      :let [content (slurp (str f))
                            first-line (or (first (str/split-lines content)) "")]
                      :when (marker-for f first-line)]
                  {:file rel :rule :header :reason (header-problem lines f content)})]
    {:checked (count results)
     :violations (vec (filter :reason results))}))

;; ---------------------------------------------------------------------------
;; Code rules: parsing

(def code-extensions #{"clj" "cljc" "cljs" "bb"})

(defn read-forms
  "Every top-level form of a Clojure source string, read without evaluation.
   Reader conditionals take the :clj branch; unknown tagged literals and
   auto-resolved keywords are tolerated."
  [content]
  (edamame/parse-string-all
   content
   {:all true
    :read-cond :allow
    :features #{:clj :bb}
    :auto-resolve (fn [alias] (if (= alias :current) 'user alias))
    :readers (fn [_tag] identity)}))

(defn- libspec-namespaces [clause]
  (for [spec (rest clause)
        :let [s (cond (symbol? spec) spec
                      (vector? spec) (first spec)
                      (seq? spec) (first spec))]
        :when (symbol? s)]
    (str s)))

(defn ns-decl
  "{:name \"a.b\" :requires [\"c.d\" ...]} from the first (ns ...) form, or nil."
  [forms]
  (when-let [f (first (filter #(and (seq? %) (= 'ns (first %)) (symbol? (second %))) forms))]
    {:name (str (second f))
     :requires (vec (mapcat libspec-namespaces
                            (filter #(and (seq? %) (contains? #{:require :use :require-macros} (first %)))
                                    f)))}))

(defn- code-files
  "[{:abs :rel :test?} ...] for Clojure files under the given dirs."
  [root exclude dirs test?]
  (let [root (fs/canonicalize root)]
    (for [d dirs
          :let [dir (fs/path root d)]
          :when (fs/directory? dir)
          f (fs/glob dir "**" {:hidden true :follow-links false})
          :when (and (fs/regular-file? f) (contains? code-extensions (extension f)))
          :let [rel (fs/relativize root f)]
          :when (not (excluded? exclude rel))]
      {:abs f :rel (str rel) :test? test?})))

(defn- parse-files
  "Attach :forms and :ns to each file, or :unreadable with the reader error."
  [files]
  (for [{:keys [abs] :as file} files]
    (try
      (let [forms (read-forms (slurp (str abs)))]
        (assoc file :forms forms :ns (ns-decl forms)))
      (catch Exception e
        (assoc file :unreadable (ex-message e))))))

;; ---------------------------------------------------------------------------
;; Code rules

(defn prefix-of?
  "True when `ns-name` equals `prefix` or lives below it."
  [prefix ns-name]
  (or (= ns-name prefix) (str/starts-with? ns-name (str prefix "."))))

(defn- under-any? [prefixes ns-name]
  (boolean (some #(prefix-of? % ns-name) prefixes)))

(def module-allowed-prefixes
  "Libraries any module may require besides clogem.api and itself."
  ["clojure" "clogem.api" "malli" "cheshire.core" "babashka.fs"])

(def module-forbidden-prefixes
  "Namespaces a module may never require even though a broader prefix is
   allowed: processes, shells and HTTP go through clogem.api."
  ["clojure.java.shell" "clojure.core.async"])

(def module-test-allowed-prefixes
  ["clojure.test" "clogem.sdk.testkit"])

(defn module-id-of
  "The module id when `ns-name` is module code (clogem.module.<id>[.…]), else nil."
  [ns-name]
  (second (re-matches #"clogem\.module\.([a-z][a-z0-9-]*)(?:\..*)?" ns-name)))

(defn- unreadable-violations [files]
  (for [{:keys [rel unreadable]} files :when unreadable]
    {:file rel :rule :unreadable :reason (str "cannot read source: " unreadable)}))

(defn- owned-name
  "The namespace name an ownership check applies to: test namespaces drop
   the conventional -test suffix (clogem.api-test belongs to clogem.api)."
  [ns-name test?]
  (if test? (str/replace ns-name #"-test$" "") ns-name))

(defn- ownership-violations [{:keys [namespaces]} files]
  (when (seq namespaces)
    (for [{:keys [rel ns test?]} files
          :when (and ns (not (under-any? namespaces (owned-name (:name ns) test?))))]
      {:file rel :rule :namespace-ownership
       :reason (str "namespace " (:name ns) " is outside this repository's prefixes "
                    (str/join ", " namespaces))})))

(defn- isolation-violations [{:keys [module-allowed-requires]} files]
  (for [{:keys [rel ns test?]} files
        :let [id (some-> ns :name module-id-of)]
        :when id
        req (:requires ns)
        :let [allowed (concat module-allowed-prefixes module-allowed-requires
                              [(str "clogem.module." id)]
                              (when test? module-test-allowed-prefixes))]
        :when (or (under-any? module-forbidden-prefixes req)
                  (not (under-any? allowed req)))]
    {:file rel :rule :module-isolation
     :reason (str "module " id " requires " req
                  "; modules may require only clogem.api, their own namespaces and allowlisted libraries")}))

(defn- uses-pods? [{:keys [ns forms]}]
  (or (some #(prefix-of? "babashka.pods" %) (:requires ns))
      (some #(and (symbol? %) (= "load-pod" (name %)) (contains? #{"babashka.pods" "pods"} (namespace %)))
            (tree-seq coll? seq forms))))

(defn- pod-violations [{:keys [pod-namespaces]} files]
  (for [{:keys [rel ns] :as file} files
        :when (and ns (uses-pods? file) (not (under-any? pod-namespaces (:name ns))))]
    {:file rel :rule :pods
     :reason (str "babashka.pods may be loaded only from "
                  (if (seq pod-namespaces) (str/join ", " pod-namespaces) "no namespace of this repository"))}))

(def print-symbols
  #{'print 'println 'prn 'pr 'printf
    'clojure.core/print 'clojure.core/println 'clojure.core/prn 'clojure.core/pr 'clojure.core/printf})

(defn- print-violations [{:keys [no-print-prefixes log-namespace]} files]
  (for [{:keys [rel ns forms test?]} files
        :when (and ns (not test?)
                   (under-any? no-print-prefixes (:name ns))
                   (not= (:name ns) log-namespace))
        form (tree-seq coll? seq forms)
        :when (and (seq? form) (contains? print-symbols (first form)))]
    {:file rel :rule :no-print
     :reason (str (first form) " is allowed only in " (or log-namespace "the log namespace")
                  "; daemon and stdio paths log through it")}))

(defn- manifest-violations [{:keys [root exclude-dirs manifests?] :or {manifests? true}}]
  (when manifests?
    (let [root (fs/canonicalize root)
          exclude (into default-exclude-dirs exclude-dirs)]
      (for [f (fs/glob root "**/manifest.edn" {:hidden true :follow-links false})
            :let [rel (fs/relativize root f)]
            :when (not (excluded? exclude rel))
            p (:problems (manifest/check-file f))]
        {:file (str rel) :rule :manifest :reason p}))))

(defn- home-path-violations [source-files]
  (let [rules (filterv #(= :home-path (:id %)) guard/builtin-rules)]
    (for [[f rel] source-files
          {:keys [where why]} (guard/scan-text rules rel (slurp (str f)))]
      {:file rel :rule :home-path :reason (str why " at " where)})))

;; ---------------------------------------------------------------------------
;; Entry points

(defn check
  "Run every rule. Returns {:checked n :violations [{:file :rule :reason} ...]}
   where :checked counts header-checked files."
  [{:keys [root exclude-dirs source-dirs test-dirs]
    :or {root "." exclude-dirs #{} source-dirs ["src"] test-dirs ["test"]}
    :as opts}]
  (require-directory! root)
  (let [opts (assoc opts :root root)
        exclude (into default-exclude-dirs exclude-dirs)
        headers (check-headers opts)
        srcs (source-files root exclude)
        files (vec (parse-files (concat (code-files root exclude source-dirs false)
                                        (code-files root exclude test-dirs true))))
        readable (remove :unreadable files)]
    {:checked (:checked headers)
     :violations (-> (vec (:violations headers))
                     (into (unreadable-violations files))
                     (into (ownership-violations opts readable))
                     (into (isolation-violations opts readable))
                     (into (pod-violations opts readable))
                     (into (print-violations opts readable))
                     (into (manifest-violations opts))
                     (into (home-path-violations srcs)))}))

(defn lint!
  "Run the repository's lint checks and print a report. Returns true when
   clean. See the namespace docstring for the options."
  [opts]
  (let [{:keys [checked violations]} (check opts)]
    (doseq [{:keys [file rule reason]} (sort-by (juxt :file :rule) violations)]
      (println (str "lint: " file ": [" (name rule) "] " reason)))
    (println (str "lint: " checked " file(s) checked, "
                  (count violations) " violation(s)"))
    (empty? violations)))
