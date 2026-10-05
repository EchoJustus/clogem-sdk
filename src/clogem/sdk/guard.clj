;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.sdk.guard
  "Leak guard for Clogem's public repositories.

   Scans the files git would commit (working tree, or the index with
   :staged? true), their names, symbolic-link targets and, with :history?
   true, every commit on every ref (messages, author and committer identity,
   patches, touched paths) and ref names for: entries of a denylist that
   lives outside the public repositories (../.clogem/public-denylist.txt in
   the workspace), absolute home paths, token-like strings and files that
   must stay private.

   Findings name the rule and a redacted location but never echo the
   matched text, so the report itself cannot leak when it runs in a public
   CI log."
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
    :re #"(?<![\w.-])/home/[A-Za-z0-9._-]+(?![\w.-])"}
   {:id :home-path :why "absolute macOS home path"
    :re #"(?<![\w.-])/Users/[A-Za-z0-9._-]+(?![\w.-])"}
   {:id :home-path :why "absolute Windows profile path"
    :re #"(?i)(?:\b[a-z]:[\\/]{1,2}|/mnt/[a-z]/)users[\\/]{1,2}[a-z0-9._-]+"}
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
   literal unless it starts with `re:`, which introduces a case-insensitive
   regular expression. Findings cite the entry number, never the entry."
  [text]
  (->> (str/split-lines text)
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       (map-indexed
        (fn [i line]
          (let [why (str "denylist entry #" (inc i))]
            (if (str/starts-with? line "re:")
              {:id :denylist :why why
               :re (re-pattern (str "(?i)" (str/trim (subs line 3))))}
              {:id :denylist :why why
               :re (re-pattern (str "(?i)" (java.util.regex.Pattern/quote line)))}))))
       vec))

(defn- redact [line]
  (reduce (fn [l allowed] (str/replace l allowed "")) line allowlist))

(defn- redact-matches
  "Replace every rule match in `s` with ***, so a location can be printed."
  [rules s]
  (reduce (fn [acc {:keys [re]}] (str/replace acc re "***")) s rules))

(defn scan-text
  "Findings for every line of `text` that matches a rule. `source` is the
   location prefix; it is redacted with the same rules before use."
  [rules source text]
  (let [source (redact-matches rules source)]
    (into []
          (comp (map-indexed vector)
                (mapcat (fn [[i line]]
                          (let [line (redact line)]
                            (keep (fn [{:keys [id why re]}]
                                    (when (re-find re line)
                                      {:id id :where (str source ":" (inc i)) :why why}))
                                  rules)))))
          (str/split-lines text))))

(defn scan-name
  "Findings for a repository-relative file name: denylist and built-in rules
   plus the private-path rules. The reported location is redacted."
  [rules rel-path]
  (let [name (redact rel-path)
        shown (redact-matches rules rel-path)]
    (into []
          (keep (fn [{:keys [id why re]}]
                  (when (re-find re name)
                    {:id id :where shown :why (str why " (file name)")})))
          (concat rules private-path-rules))))

(defn- git [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string :continue true}
         "git" args))

(defn- git-lines [root & args]
  (let [{:keys [exit out]} (apply git root args)]
    (if (zero? exit) (remove str/blank? (str/split-lines out)) [])))

(defn- nul-split [s]
  (remove str/blank? (str/split s #"\u0000")))

(defn repo-files
  "Repository-relative paths git tracks or would add (untracked but not
   ignored); with staged? only the index. Falls back to walking the tree
   outside a git checkout."
  ([root] (repo-files root false))
  ([root staged?]
   (let [{:keys [exit out]} (if staged?
                              (git root "ls-files" "--cached" "-z")
                              (git root "ls-files" "--cached" "--others"
                                   "--exclude-standard" "-z"))]
     (if (zero? exit)
       (sort (nul-split out))
       (let [root (fs/canonicalize root)]
         (->> (fs/glob root "**" {:hidden true :follow-links false})
              (filter fs/regular-file?)
              (map #(str (fs/relativize root %)))
              (remove #(str/starts-with? % ".git/"))
              sort))))))

(defn- bytes->text
  "Decode bytes as UTF-8 and drop NUL bytes, so UTF-16 and NUL-prefixed files
   are still scanned instead of being skipped as binary."
  [^bytes bs]
  (str/replace (String. bs "UTF-8") "\u0000" ""))

(defn- worktree-content [root rel]
  (let [path (fs/path root rel)]
    (cond
      (fs/sym-link? path) (str (fs/read-link path))
      (fs/regular-file? path) (bytes->text (fs/read-all-bytes path))
      :else nil)))

(defn- staged-content [root rel]
  (let [{:keys [exit out]} (git root "show" (str ":" rel))]
    (when (zero? exit) (bytes->text (.getBytes ^String out "UTF-8")))))

(defn tree-findings
  "Scan file names and contents of the files git would commit under `root`:
   the working tree, or the index when staged? is true. A symbolic link's
   target is scanned as its content, since that is what git commits."
  ([rules root] (tree-findings rules root false))
  ([rules root staged?]
   (into []
         (mapcat (fn [rel]
                   (concat (scan-name rules rel)
                           (when-let [text (if staged?
                                             (staged-content root rel)
                                             (worktree-content root rel))]
                             (scan-text rules rel text)))))
         (repo-files root staged?))))

(defn message-findings
  "Scan a commit message (text) with the rules."
  [rules text]
  (scan-text rules "commit message" text))

(defn history-findings
  "Scan every commit on every ref: message, author and committer identity,
   patch, touched paths and ref names. Commits are separated by NUL so a
   message line cannot forge the separator. Findings cite the abbreviated
   commit hash and the line within that commit's entry."
  [rules root]
  (let [{:keys [exit out]} (git root "log" "--all" "-p" "--no-color"
                                "--format=%x00%H%n%an <%ae>%n%cn <%ce>%n%B")
        entries (if (zero? exit) (nul-split out) [])
        touched (git-lines root "log" "--all" "--name-only" "--format=")
        refs (git-lines root "for-each-ref" "--format=%(refname)")]
    (-> []
        (into (mapcat (fn [entry]
                        (let [sha (subs entry 0 (min 7 (count entry)))
                              body (subs entry (min (count entry) 41))]
                          (scan-text rules (str "history@" sha) body)))
                      entries))
        (into (mapcat #(scan-name rules %) (distinct touched)))
        (into (scan-text rules "refs" (str/join "\n" refs))))))

(defn- denylist-path [root denylist]
  (or denylist
      (System/getenv "CLOGEM_DENYLIST")
      (str (fs/path root ".." ".clogem" "public-denylist.txt"))))

(defn- ci? []
  (contains? #{"true" "1" "yes"} (str/lower-case (or (System/getenv "CI") ""))))

(defn- load-rules
  "Built-in rules plus the denylist, or nil when the denylist is required but
   missing."
  [root denylist]
  (let [path (denylist-path root denylist)
        entries (when (fs/exists? path) (parse-denylist (slurp path)))]
    (cond
      entries (into (vec builtin-rules) entries)
      (ci?) (do (println "guard: CI detected and no denylist available; built-in rules only")
                (vec builtin-rules))
      :else (do (println (str "guard: denylist not found; expected ../.clogem/public-denylist.txt"
                              " beside the repository, or set CLOGEM_DENYLIST"))
                nil))))

(defn- report! [findings summary]
  (doseq [{:keys [where why]} findings]
    (println (str "guard: " where ": " why)))
  (println (str "guard: " summary "; " (count findings) " finding(s)"))
  (empty? findings))

(defn guard!
  "Scan a public repository and print a report. Returns true when clean.

   Options: :root (default \".\"); :denylist (path; defaults to
   $CLOGEM_DENYLIST, then ../.clogem/public-denylist.txt); :staged? (scan the
   index instead of the working tree, for the pre-commit hook); :history?
   (also scan git history and ref names). A missing denylist is an error,
   except under CI (environment variable `CI` is true), where the public
   checkout has no access to it and only the built-in rules run."
  [{:keys [root denylist staged? history?]
    :or {root "." staged? false history? false}}]
  (if-let [rules (load-rules root denylist)]
    (report! (-> []
                 (into (tree-findings rules root staged?))
                 (into (when history? (history-findings rules root))))
             (str (count (repo-files root staged?)) " file(s)"
                  (when staged? " (index)") ", " (count rules) " rule(s)"
                  (when history? ", history scanned")))
    false))

(defn guard-message!
  "Scan a commit message file (the commit-msg hook). Returns true when clean."
  [{:keys [root denylist file] :or {root "."}}]
  (if-let [rules (load-rules root denylist)]
    (report! (message-findings rules (slurp file))
             (str "commit message, " (count rules) " rule(s)"))
    false))
