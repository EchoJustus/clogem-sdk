;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.sdk.guard-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.sdk.guard :as guard]))

;; Sample secrets and paths are assembled at runtime so that this file never
;; contains one and the guard stays clean on its own tests.
(defn- fake [prefix n] (str prefix (apply str (repeat n "a"))))

(defn- hits [rules s] (map :why (guard/scan-text rules "t" s)))

(deftest builtin-rules-catch-home-paths-and-tokens
  (let [b guard/builtin-rules]
    (is (= ["absolute Linux home path"] (hits b (str "/home/" "alice/project"))))
    (is (= ["absolute Linux home path"] (hits b (str "cd /home/" "alice"))) "no trailing slash")
    (is (= ["absolute Linux home path"] (hits b (str "file:///home/" "alice/x"))) "inside a URL")
    (is (= ["absolute macOS home path"] (hits b (str "/Users/" "alice/project"))))
    (is (= ["absolute Windows profile path"] (hits b (str "C:\\" "Users\\alice"))))
    (is (= ["absolute Windows profile path"] (hits b (str "\"C:\\\\" "Users\\\\alice\""))) "escaped backslashes")
    (is (= ["absolute Windows profile path"] (hits b (str "/mnt/c/" "Users/alice/x"))) "WSL mount")
    (is (= ["GitHub token"] (hits b (fake "ghp_" 36))))
    (is (= ["GitHub fine-grained token"] (hits b (fake "github_pat_" 40))))
    (is (= ["API secret key"] (hits b (fake "sk-" 40))))
    (is (= ["Slack token"] (hits b (fake "xoxb-" 24))))
    (is (= ["AWS access key"] (hits b (str "AKIA" "ABCDEFGHIJKLMNOP"))))
    (is (= ["private key block"] (hits b (str "-----BEGIN " "RSA PRIVATE KEY-----"))))
    (is (= ["JSON Web Token"] (hits b (str (fake "eyJ" 20) "." (fake "x" 20) "." (fake "y" 12)))))))

(deftest builtin-rules-ignore-portable-paths-and-urls
  (let [b guard/builtin-rules]
    (is (empty? (hits b "~/.config/clogem/config.edn and $XDG_RUNTIME_DIR/clogem")))
    (is (empty? (hits b "https://www.eclipse.org/legal/epl-2.0")))
    (is (empty? (hits b "/home/<user>/.local/share/clogem")) "placeholders are not names")
    (is (empty? (hits b "the Users table")))
    (is (empty? (hits b "sk-short")))))

(deftest the-copyright-contact-is-allowlisted
  (let [rules (guard/parse-denylist "outlook.com")]
    (is (empty? (guard/scan-text rules "t" "© 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)")))
    (is (seq (guard/scan-text rules "t" "someone-else@outlook.com")))))

(deftest denylist-entries-are-literal-or-regex-and-case-insensitive
  (let [rules (guard/parse-denylist "# comment\n\nSecret-Name\nre: \\bfoo\\d+\\b\n")]
    (is (= 2 (count rules)))
    (is (= ["denylist entry #1"] (hits rules "a secret-name here")))
    (is (= ["denylist entry #1"] (hits rules "SECRET-NAME")))
    (is (= ["denylist entry #2"] (hits rules "foo42")))
    (is (= ["denylist entry #2"] (hits rules "FOO42")) "regex entries are case-insensitive too")
    (is (empty? (hits rules "foo")))))

(deftest private-paths-are-flagged-by-name
  (is (seq (guard/scan-name [] "docs/sessions/S01.md")))
  (is (seq (guard/scan-name [] "CLAUDE.local.md")))
  (is (seq (guard/scan-name [] ".claude/settings.local.json")))
  (is (seq (guard/scan-name [] "data/clogem.db")))
  (is (seq (guard/scan-name [] "data/clogem.db-wal")))
  (is (seq (guard/scan-name [] ".env")))
  (is (empty? (guard/scan-name [] "docs/adr/0001-example.md")))
  (is (empty? (guard/scan-name [] "src/clogem/sdk/lint.clj"))))

(deftest findings-never-echo-the-match
  (let [token (fake "ghp_" 36)
        out (pr-str (guard/scan-text guard/builtin-rules "t" token))]
    (is (not (str/includes? out token)))
    (is (str/includes? out "t:1")))
  (testing "file names and sources are redacted too"
    (let [rules (guard/parse-denylist "secret-name")
          by-name (guard/scan-name rules "docs/secret-name-notes.md")
          by-content (guard/scan-text rules "docs/secret-name-notes.md" "mentions secret-name")]
      (is (= ["denylist entry #1 (file name)"] (map :why by-name)))
      (is (= "docs/***-notes.md" (:where (first by-name))))
      (is (= "docs/***-notes.md:1" (:where (first by-content)))))))

(defn- sh [dir & args]
  (apply p/shell {:dir (str dir) :out :string :err :string} args))

(deftest tree-and-history-scans-cover-staged-content-links-and-identity
  (let [dir (fs/create-temp-dir {:prefix "clogem-guard"})
        rules (into (vec guard/builtin-rules) (guard/parse-denylist "secret-name"))
        token (fake "ghp_" 36)]
    (try
      (sh dir "git" "init" "-q" "-b" "main")
      (sh dir "git" "config" "user.email" "t@example.test")
      (sh dir "git" "config" "user.name" "secret-name bot")
      (testing "UTF-16 and NUL-prefixed files are scanned, not skipped"
        (spit (str (fs/path dir "u16.txt")) "mentions secret-name" :encoding "UTF-16LE")
        (fs/write-bytes (fs/path dir "nul.txt") (.getBytes (str "\u0000" token) "UTF-8"))
        (is (= #{"u16.txt:1" "nul.txt:1"} (set (map :where (guard/tree-findings rules dir))))))
      (fs/delete (fs/path dir "u16.txt"))
      (fs/delete (fs/path dir "nul.txt"))
      (testing "staged content is scanned even when the working tree is clean"
        (spit (str (fs/path dir "cfg.txt")) (str "token=" token))
        (sh dir "git" "add" "cfg.txt")
        (spit (str (fs/path dir "cfg.txt")) "clean")
        (is (empty? (guard/tree-findings rules dir false)))
        (is (= ["GitHub token"] (map :why (guard/tree-findings rules dir true)))))
      (testing "a symbolic link's target is scanned"
        (spit (str (fs/path dir "cfg.txt")) "clean")
        (sh dir "git" "add" "cfg.txt")
        (fs/create-sym-link (fs/path dir "leak-link") (str "/home/" "alice/secret.txt"))
        (sh dir "git" "add" "leak-link")
        (is (= ["absolute Linux home path"] (map :why (guard/tree-findings rules dir true))))
        (sh dir "git" "rm" "-q" "--cached" "leak-link")
        (fs/delete (fs/path dir "leak-link")))
      (testing "history: identity, deleted private files and forged separators"
        (sh dir "git" "commit" "-q" "-m" "first")
        (fs/create-dirs (fs/path dir "docs" "sessions"))
        (spit (str (fs/path dir "docs" "sessions" "S01.md")) "log")
        (sh dir "git" "add" "docs")
        (sh dir "git" "commit" "-q" "-m" (str "add log\n" "@@commit deadbeef\n" "x"))
        (sh dir "git" "rm" "-q" "-r" "docs")
        (sh dir "git" "commit" "-q" "-m" "remove log")
        (let [findings (guard/history-findings rules dir)
              whys (set (map :why findings))]
          (is (contains? whys "denylist entry #1") "author name")
          (is (contains? whys "session logs stay in the private workspace (file name)") "deleted private file")
          (is (every? #(re-find #"^(history@[0-9a-f]{7}:\d+|docs/.*|refs:\d+)$" (:where %)) findings)
              (pr-str (map :where findings))))
        (is (seq (guard/message-findings rules "sync with secret-name"))))
      (finally (fs/delete-tree dir)))))

(deftest this-repository-is-clean-under-the-builtin-rules
  ;; The workspace denylist is private and may be absent in CI, so this test
  ;; covers only the built-in rules. `bb guard:public` adds the denylist.
  (is (empty? (guard/tree-findings guard/builtin-rules ".")))
  (is (empty? (guard/history-findings guard/builtin-rules "."))))
