;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.sdk.guard-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clogem.sdk.guard :as guard]))

;; Sample secrets and paths are assembled at runtime so that this file never
;; contains one and the guard stays clean on its own tests.
(defn- fake [prefix n] (str prefix (apply str (repeat n "a"))))

(defn- hits [rules s] (map :why (guard/scan-text rules "t" s)))

(deftest builtin-rules-catch-home-paths-and-tokens
  (let [b guard/builtin-rules]
    (is (= ["absolute Linux home path"] (hits b (str "/home/" "alice/project"))))
    (is (= ["absolute macOS home path"] (hits b (str "/Users/" "alice/project"))))
    (is (= ["absolute Windows profile path"] (hits b (str "C:\\" "Users\\alice"))))
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
    (is (empty? (hits b "/home/<user>/.local/share/clogem")))
    (is (empty? (hits b "sk-short")))))

(deftest the-copyright-contact-is-allowlisted
  (let [rules (guard/parse-denylist "outlook.com")]
    (is (empty? (guard/scan-text rules "t"
                                 "© 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)")))
    (is (seq (guard/scan-text rules "t" "someone-else@outlook.com")))))

(deftest denylist-entries-are-literal-case-insensitive-or-regex
  (let [rules (guard/parse-denylist "# comment\n\nSecret-Name\nre: foo\\d+\n")]
    (is (= 2 (count rules)))
    (is (= ["denylist entry #1"] (hits rules "a secret-name here")))
    (is (= ["denylist entry #1"] (hits rules "SECRET-NAME")))
    (is (= ["denylist entry #2"] (hits rules "foo42")))
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

(deftest denylisted-names-are-flagged-in-file-names-too
  (let [rules (guard/parse-denylist "secret-name")]
    (is (= ["denylist entry #1 (file name)"]
           (map :why (guard/scan-name rules "docs/secret-name-notes.md"))))))

(deftest findings-never-echo-the-match
  (let [token (fake "ghp_" 36)
        out (pr-str (guard/scan-text guard/builtin-rules "t" token))]
    (is (not (str/includes? out token)))
    (is (str/includes? out "t:1"))))

(deftest this-repository-is-clean-under-the-builtin-rules
  ;; The workspace denylist is private and may be absent in CI, so this test
  ;; covers only the built-in rules. `bb guard:public` adds the denylist.
  (is (empty? (guard/tree-findings guard/builtin-rules ".")))
  (is (empty? (guard/history-findings guard/builtin-rules "."))))
