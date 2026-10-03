;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.module.echo.tools
  "Tool handlers of the fictional `echo` module. A handler takes the module
   ctx and the validated arguments map and returns the structured result."
  (:require [clojure.string :as str]
            [clogem.api :as api]))

(defn say
  "echo_say: return the text, upper-cased on request, and publish :echo/said."
  [ctx {:keys [text upcase]}]
  (let [out (if upcase (str/upper-case text) text)]
    (api/publish! ctx :echo/said {:text out})
    {:text out}))
