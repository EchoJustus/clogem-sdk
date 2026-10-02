;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.sdk.lint-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.sdk.lint :as lint]))

(deftest render-header-matches-the-published-templates
  (is (= [";; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)"
          ";;"
          ";; This program and the accompanying materials are made available under the"
          ";; terms of the Eclipse Public License 2.0 which is available at"
          ";; https://www.eclipse.org/legal/epl-2.0"
          ";;"
          ";; SPDX-License-Identifier: EPL-2.0"]
         (lint/render-header :epl-2.0 ";;")))
  (is (= [";; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)"
          ";; Licensed under the Apache License, Version 2.0; see the LICENSE file."
          ";; SPDX-License-Identifier: Apache-2.0"]
         (lint/render-header :apache-2.0 ";;")))
  (is (= ["// © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com). All rights reserved."
          "// PROPRIETARY AND CONFIDENTIAL. Unauthorized copying, distribution, or use"
          "// of this file, via any medium, is strictly prohibited."
          "// SPDX-License-Identifier: LicenseRef-Clogem-Proprietary"]
         (lint/render-header :proprietary "//"))))

(defn- with-header [license marker body]
  (str (str/join "\n" (lint/render-header license marker)) "\n\n" body))

(deftest header-problem-accepts-correct-headers
  (is (nil? (lint/header-problem :apache-2.0 "src/x.clj"
                                 (with-header :apache-2.0 ";;" "(ns x)"))))
  (is (nil? (lint/header-problem :proprietary "lib/a.dart"
                                 (with-header :proprietary "//" "void main() {}"))))
  (is (nil? (lint/header-problem :epl-2.0 "ci.yml"
                                 (with-header :epl-2.0 "#" "on: push"))))
  (testing "a shebang line may precede the header"
    (is (nil? (lint/header-problem :epl-2.0 "bin/tool"
                                   (str "#!/usr/bin/env bb\n"
                                        (with-header :epl-2.0 ";;" "(println 1)")))))
    (is (nil? (lint/header-problem :epl-2.0 "bin/run.sh"
                                   (str "#!/bin/sh\n"
                                        (with-header :epl-2.0 "#" "echo")))))))

(deftest header-problem-rejects-missing-or-wrong-headers
  (is (string? (lint/header-problem :apache-2.0 "src/x.clj" "(ns x)")) "no header")
  (is (string? (lint/header-problem :epl-2.0 "src/x.clj"
                                    (with-header :apache-2.0 ";;" "(ns x)")))
      "wrong license")
  (is (string? (lint/header-problem :proprietary "src/x.clj"
                                    (with-header :proprietary "//" "(ns x)")))
      "wrong comment marker")
  (is (string? (lint/header-problem :apache-2.0 "src/x.clj" "")) "empty file"))

(deftest unchecked-files-are-skipped
  (is (nil? (lint/header-problem :apache-2.0 "README.md" "# hi")))
  (is (nil? (lint/header-problem :apache-2.0 "data.json" "{}")))
  (is (nil? (lint/header-problem :apache-2.0 "bin/blob" "no shebang here"))))

(deftest check-headers-walks-a-tree
  (let [dir (fs/create-temp-dir {:prefix "clogem-lint"})]
    (try
      (fs/create-dirs (fs/path dir "src" "a"))
      (fs/create-dirs (fs/path dir "build"))
      (spit (str (fs/path dir "src" "a" "good.clj"))
            (with-header :apache-2.0 ";;" "(ns a.good)"))
      (spit (str (fs/path dir "src" "a" "bad.clj")) "(ns a.bad)")
      (spit (str (fs/path dir "build" "gen.clj")) "(ns gen)")
      (spit (str (fs/path dir "notes.md")) "# notes")
      (let [{:keys [checked violations]}
            (lint/check-headers {:root (str dir) :license :apache-2.0})]
        (is (= 2 checked) "build/ is excluded and notes.md is not checkable")
        (is (= ["src/a/bad.clj"] (map :file violations))))
      (finally (fs/delete-tree dir)))))

(deftest unknown-license-is-an-error
  (is (thrown? clojure.lang.ExceptionInfo
               (lint/check-headers {:root "." :license :mit}))))

(deftest this-repository-is-clean
  (is (empty? (:violations (lint/check-headers {:root "." :license :apache-2.0})))))
