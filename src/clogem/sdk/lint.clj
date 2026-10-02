;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.sdk.lint
  "Fitness checks shared by every Clogem repository.

   S00 ships the license-header check. S01 adds namespace ownership, module
   isolation, pod placement, print discipline, tool-name and schema rules.
   Each repository configures the checks from its own bb.edn `lint` task;
   this namespace carries no repository-specific names."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def copyright-line
  "© 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)")

(def header-lines
  "Header text per license, before the comment marker is applied.
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
    "SPDX-License-Identifier: Apache-2.0"]
   :proprietary
   [(str copyright-line ". All rights reserved.")
    "PROPRIETARY AND CONFIDENTIAL. Unauthorized copying, distribution, or use"
    "of this file, via any medium, is strictly prohibited."
    "SPDX-License-Identifier: LicenseRef-Clogem-Proprietary"]})

(def comment-marker
  "Line-comment marker by file extension. Files with any other extension are
   not checked: Markdown, JSON, plain text and binaries carry no header."
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
   line comments."
  [license marker]
  (mapv (fn [line] (if (str/blank? line) marker (str marker " " line)))
        (header-lines license)))

(defn- shebang-marker [first-line]
  (when (str/starts-with? first-line "#!")
    (if (re-find #"\b(bb|clojure|clj)\b" first-line) ";;" "#")))

(defn marker-for
  "Comment marker for `path`: from its extension, or from the shebang line
   of an extension-less script. nil means the file is not checked."
  [path first-line]
  (let [ext (fs/extension path)]
    (if ext
      (get comment-marker ext)
      (shebang-marker first-line))))

(defn header-problem
  "nil when `content` starts with the header for `license` (after an optional
   shebang line); otherwise a human-readable reason."
  [license path content]
  (let [lines (str/split-lines content)
        first-line (or (first lines) "")
        marker (marker-for path first-line)]
    (when marker
      (let [expected (render-header license marker)
            body (if (str/starts-with? first-line "#!") (rest lines) lines)]
        (when-not (= expected (vec (take (count expected) body)))
          (str "missing or malformed " (name license)
               " header; expected line 1: " (first expected)))))))

(defn- excluded? [exclude-dirs rel-path]
  (some exclude-dirs (map str (fs/components rel-path))))

(defn source-files
  "Regular files under `root` whose extension (or shebang) is checkable,
   skipping excluded directory names. Symbolic links are not followed, so a
   workspace that links sibling repositories in place lints only its own
   tree. Returns [[absolute-path relative-path-string] ...]."
  [root exclude-dirs]
  (let [root (fs/canonicalize root)]
    (->> (fs/glob root "**" {:hidden true :follow-links false})
         (filter fs/regular-file?)
         (map (fn [f] [f (fs/relativize root f)]))
         (remove (fn [[_ rel]] (excluded? exclude-dirs rel)))
         (filter (fn [[f _]] (let [ext (fs/extension f)]
                               (or (nil? ext) (contains? comment-marker ext)))))
         (map (fn [[f rel]] [f (str rel)]))
         (sort-by second))))

(defn check-headers
  "Verify that every authored source file under :root carries the :license
   header. Returns {:checked n :violations [{:file \"…\" :reason \"…\"}]}."
  [{:keys [root license exclude-dirs] :or {root "." exclude-dirs #{}}}]
  (when-not (contains? header-lines license)
    (throw (ex-info (str "unknown license " (pr-str license))
                    {:known (vec (keys header-lines))})))
  (let [results (for [[f rel] (source-files root (into default-exclude-dirs exclude-dirs))
                      :let [content (slurp (str f))
                            first-line (or (first (str/split-lines content)) "")]
                      :when (marker-for f first-line)]
                  {:file rel :reason (header-problem license f content)})]
    {:checked (count results)
     :violations (vec (filter :reason results))}))

(defn lint!
  "Run the repository's lint checks and print a report. Returns true when
   clean. Options: :root (default \".\"), :license (one of :epl-2.0,
   :apache-2.0, :proprietary), :exclude-dirs (extra directory names to skip)."
  [opts]
  (let [{:keys [checked violations]} (check-headers opts)]
    (doseq [{:keys [file reason]} violations]
      (println (str "lint: " file ": " reason)))
    (println (str "lint: " checked " file(s) checked, "
                  (count violations) " violation(s)"))
    (empty? violations)))
