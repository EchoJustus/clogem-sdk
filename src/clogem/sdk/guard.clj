;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.sdk.guard
  "Leak guard for Clogem's public repositories.

   Scans the files git would commit, their names and (optionally) the whole
   git history for: entries of a denylist that lives outside the public
   repositories (../.clogem/public-denylist.txt in the workspace), absolute
   home paths, token-like strings and files that must stay private.

   Findings name the rule and the location but never echo the matched text,
   so the report itself cannot leak when it runs in a public CI log."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def allowlist
  "Substrings removed from a line before scanning. The copyright contact is
   public by design."
  #{"EchoJustus.Studio@outlook.com"})

(def builtin-rules
  "Rules every public repository is checked against, with or without a
   denylist."
  [{:id :home-path :why "absolute Linux home path"
    :re #"(?<![\w./-])/home/[A-Za-z0-9._-]+/"}
   {:id :home-path :why "absolute macOS home path"
    :re #"(?<![\w./-])/Users/[A-Za-z0-9._-]+/"}
   {:id :home-path :why "absolute Windows profile path"
    :re #"(?i)[A-Z]:\\Users\\"}
   {:id :token :why "GitHub token"
    :re #"\bgh[pousr]_[A-Za-z0-9]{20,}"}
   {:id :token :why "GitHub fine-grained token"
    :re #"\bgithub_pat_[A-Za-z0-9_]{20,}"}
   {:id :token :why "API secret key"
    :re #"\bsk-[A-Za-z0-9_-]{20,}"}
   {:id :token :why "Slack token"
    :re #"\bxox[abprs]-[A-Za-z0-9-]{10,}"}
   {:id :token :why "AWS access key"
    :re #"\bAKIA[0-9A-Z]{16}\b"}
   {:id :token :why "private key block"
    :re #"-----BEGIN [A-Z ]*PRIVATE KEY-----"}
   {:id :token :why "JSON Web Token"
    :re #"\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{10,}"}])

(def private-path-rules
  "Paths that never belong in a public repository, matched against the
   repository-relative file name."
  [{:id :private-file :why "session logs stay in the private workspace"
    :re #"(^|/)docs/sessions/"}
   {:id :private-file :why "local-only Claude Code memory"
    :re #"(^|/)CLAUDE\.local\.md$"}
   {:id :private-file :why "local-only Claude Code settings"
    :re #"(^|/)settings\.local\.json$"}
   {:id :private-file :why "environment file"
    :re #"(^|/)\.env(\.[A-Za-z0-9_-]+)?$"}
   {:id :private-file :why "database file"
    :re #"\.db(-wal|-shm|-journal)?$"}
   {:id :private-file :why "client token store"
    :re #"(^|/)clients\.edn$"}])

(defn parse-denylist
  "One entry per line; `#` starts a comment. An entry is a case-insensitive
   literal unless it starts with `re:`, which introduces a regular expression.
   Findings cite the entry number, never the entry."
  [text]
  (->> (str/split-lines text)
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       (map-indexed
        (fn [i line]
          (let [why (str "denylist entry #" (inc i))]
            (if (str/starts-with? line "re:")
              {:id :denylist :why why :re (re-pattern (str/trim (subs line 3)))}
              {:id :denylist :why why
               :re (re-pattern (str "(?i)" (java.util.regex.Pattern/quote line)))}))))
       vec))

(defn- redact [line]
  (reduce (fn [l allowed] (str/replace l allowed "")) line allowlist))

(defn scan-text
  "Findings for every line of `text` that matches a rule."
  [rules source text]
  (into []
        (comp (map-indexed vector)
              (mapcat (fn [[i line]]
                        (let [line (redact line)]
                          (keep (fn [{:keys [id why re]}]
                                  (when (re-find re line)
                                    {:id id :where (str source ":" (inc i)) :why why}))
                                rules)))))
        (str/split-lines text)))

(defn scan-name
  "Findings for a repository-relative file name: denylist and built-in rules
   plus the private-path rules."
  [rules rel-path]
  (let [name (redact rel-path)]
    (into []
          (keep (fn [{:keys [id why re]}]
                  (when (re-find re name)
                    {:id id :where rel-path :why (str why " (file name)")})))
          (concat rules private-path-rules))))

(defn- git [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string :continue true}
         "git" args))

(defn repo-files
  "Repository-relative paths git tracks or would add (untracked but not
   ignored). Falls back to walking the tree outside a git checkout."
  [root]
  (let [{:keys [exit out]} (git root "ls-files" "--cached" "--others"
                                "--exclude-standard" "-z")]
    (if (zero? exit)
      (->> (str/split out #"\u0000") (remove str/blank?) sort)
      (let [root (fs/canonicalize root)]
        (->> (fs/glob root "**" {:hidden true :follow-links false})
             (filter fs/regular-file?)
             (map #(str (fs/relativize root %)))
             (remove #(str/starts-with? % ".git/"))
             sort)))))

(defn- text-file? [path]
  (let [bytes (fs/read-all-bytes path)
        probe (take 8000 bytes)]
    (not-any? zero? probe)))

(defn tree-findings
  "Scan file names and contents of the files git would commit under `root`."
  [rules root]
  (into []
        (mapcat (fn [rel]
                  (let [path (fs/path root rel)]
                    (concat (scan-name rules rel)
                            (when (and (fs/regular-file? path) (text-file? path))
                              (scan-text rules rel (slurp (str path))))))))
        (repo-files root)))

(defn history-findings
  "Scan every commit on every ref: messages, patches and ref names. Findings
   cite the abbreviated commit hash and the line within that commit's entry."
  [rules root]
  (let [{:keys [exit out]} (git root "log" "--all" "-p" "--no-color"
                                "--format=@@commit %H%n%B")
        log (if (zero? exit) out "")
        refs (or (:out (git root "for-each-ref" "--format=%(refname)")) "")]
    (loop [lines (seq (str/split-lines log)) commit "?" n 0 acc []]
      (if-let [line (first lines)]
        (if (str/starts-with? line "@@commit ")
          (recur (next lines) (subs line 9 (min (count line) 16)) 0 acc)
          (recur (next lines) commit (inc n)
                 (into acc (map #(assoc % :where (str "history@" commit ":" (inc n)))
                                (scan-text rules "history" line)))))
        (into acc (scan-text rules "refs" refs))))))

(defn- denylist-path [root denylist]
  (or denylist
      (System/getenv "CLOGEM_DENYLIST")
      (str (fs/path root ".." ".clogem" "public-denylist.txt"))))

(defn guard!
  "Scan a public repository and print a report. Returns true when clean.

   Options: :root (default \".\"); :denylist (path; defaults to
   $CLOGEM_DENYLIST, then ../.clogem/public-denylist.txt); :history? (also scan
   git history and ref names). A missing denylist is an error, except under
   CI (environment variable `CI` set), where the public checkout has no access
   to it and only the built-in rules run."
  [{:keys [root denylist history?] :or {root "." history? false}}]
  (let [path (denylist-path root denylist)
        ci? (some? (System/getenv "CI"))
        entries (when (fs/exists? path) (parse-denylist (slurp path)))]
    (cond
      (and (nil? entries) (not ci?))
      (do (println (str "guard: denylist not found at " path
                        " (run inside the workspace layout or set CLOGEM_DENYLIST)"))
          false)

      :else
      (let [_ (when (nil? entries)
                (println "guard: CI detected and no denylist available; built-in rules only"))
            rules (into (vec builtin-rules) entries)
            findings (-> []
                         (into (tree-findings rules root))
                         (into (when history? (history-findings rules root))))]
        (doseq [{:keys [where why]} findings]
          (println (str "guard: " where ": " why)))
        (println (str "guard: " (count (repo-files root)) " file(s), "
                      (count rules) " rule(s)"
                      (when history? ", history scanned")
                      "; " (count findings) " finding(s)"))
        (empty? findings)))))
