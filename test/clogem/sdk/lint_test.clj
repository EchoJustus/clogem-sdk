;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.sdk.lint-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.sdk.lint :as lint]))

(def epl (:epl-2.0 lint/header-lines))
(def apache (:apache-2.0 lint/header-lines))
(def custom ["© 2026 Example Owner" "Custom header line two" "SPDX-License-Identifier: LicenseRef-Example"])

(deftest render-header-matches-the-published-templates
  (is (= [";; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)"
          ";;"
          ";; This program and the accompanying materials are made available under the"
          ";; terms of the Eclipse Public License 2.0 which is available at"
          ";; https://www.eclipse.org/legal/epl-2.0"
          ";;"
          ";; SPDX-License-Identifier: EPL-2.0"]
         (lint/render-header epl ";;")))
  (is (= [";; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)"
          ";; Licensed under the Apache License, Version 2.0; see the LICENSE file."
          ";; SPDX-License-Identifier: Apache-2.0"]
         (lint/render-header apache ";;")))
  (is (= ["// © 2026 Example Owner"
          "// Custom header line two"
          "// SPDX-License-Identifier: LicenseRef-Example"]
         (lint/render-header custom "//"))))

(defn- with-header [lines marker body]
  (str (str/join "\n" (lint/render-header lines marker)) "\n\n" body))

(deftest header-problem-accepts-correct-headers
  (is (nil? (lint/header-problem apache "src/x.clj" (with-header apache ";;" "(ns x)"))))
  (is (nil? (lint/header-problem custom "lib/a.dart" (with-header custom "//" "void main() {}"))))
  (is (nil? (lint/header-problem epl "ci.yml" (with-header epl "#" "on: push"))))
  (is (nil? (lint/header-problem apache "src/X.CLJ" (with-header apache ";;" "(ns x)")))
      "extensions are matched case-insensitively")
  (testing "a shebang line may precede the header"
    (is (nil? (lint/header-problem epl "bin/tool"
                                   (str "#!/usr/bin/env bb\n" (with-header epl ";;" "(println 1)")))))
    (is (nil? (lint/header-problem epl "bin/tool2"
                                   (str "#!/usr/local/bin/bb\n" (with-header epl ";;" "(println 1)")))))
    (is (nil? (lint/header-problem epl "bin/run.sh"
                                   (str "#!/bin/sh\n" (with-header epl "#" "echo")))))
    (is (nil? (lint/header-problem epl "bin/py"
                                   (str "#!/opt/bb-tools/python3\n" (with-header epl "#" "print(1)"))))
        "the interpreter name decides, not any path component")))

(deftest header-problem-rejects-missing-or-wrong-headers
  (is (string? (lint/header-problem apache "src/x.clj" "(ns x)")) "no header")
  (is (string? (lint/header-problem epl "src/x.clj" (with-header apache ";;" "(ns x)"))) "wrong license")
  (is (string? (lint/header-problem custom "src/x.clj" (with-header custom "//" "(ns x)"))) "wrong marker")
  (is (string? (lint/header-problem apache "src/X.CLJ" "(ns x)")) "upper-case extension still checked")
  (is (string? (lint/header-problem apache "src/x.clj" "")) "empty file"))

(deftest unchecked-files-are-skipped
  (is (nil? (lint/header-problem apache "README.md" "# hi")))
  (is (nil? (lint/header-problem apache "data.json" "{}")))
  (is (nil? (lint/header-problem apache "bin/blob" "no shebang here"))))

(deftest check-headers-walks-a-tree
  (let [dir (fs/create-temp-dir {:prefix "clogem-lint"})]
    (try
      (fs/create-dirs (fs/path dir "src" "a"))
      (fs/create-dirs (fs/path dir "build"))
      (spit (str (fs/path dir "src" "a" "good.clj")) (with-header apache ";;" "(ns a.good)"))
      (spit (str (fs/path dir "src" "a" "bad.clj")) "(ns a.bad)")
      (spit (str (fs/path dir "src" "a" "Loud.CLJ")) "(ns a.loud)")
      (spit (str (fs/path dir "build" "gen.clj")) "(ns gen)")
      (spit (str (fs/path dir "notes.md")) "# notes")
      (let [{:keys [checked violations]} (lint/check-headers {:root (str dir) :license :apache-2.0})]
        (is (= 3 checked) "build/ is excluded and notes.md is not checkable")
        (is (= ["src/a/Loud.CLJ" "src/a/bad.clj"] (map :file violations))))
      (testing "an explicit :header replaces the license template"
        (spit (str (fs/path dir "src" "a" "good.clj")) (with-header custom ";;" "(ns a.good)"))
        (is (= ["src/a/Loud.CLJ" "src/a/bad.clj"]
               (map :file (:violations (lint/check-headers {:root (str dir) :header custom}))))))
      (finally (fs/delete-tree dir)))))

(deftest bad-configuration-is-an-error
  (is (thrown? clojure.lang.ExceptionInfo (lint/check-headers {:root "." :license :mit})))
  (is (thrown? clojure.lang.ExceptionInfo (lint/check-headers {:root "does-not-exist" :license :apache-2.0}))
      "a missing root must not lint as clean"))

(deftest this-repository-is-clean
  (is (empty? (:violations (lint/check-headers {:root "." :license :apache-2.0})))))
