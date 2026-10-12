;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.module.echo.tools
  "Tool handlers of the fictional `echo` module. A handler takes the module
   ctx and the validated arguments map and returns the structured result."
  (:require [clojure.string :as str]
            [clogem.api :as api]
            [clogem.module.echo.resources :as resources]))

(defn say
  "echo_say: return the text, upper-cased on request, remember it for the
   module's resources, publish :echo/said and tell the hub that the
   `clogem://echo/last/said` resource changed."
  [ctx {:keys [text upcase]}]
  (let [out (if upcase (str/upper-case text) text)]
    (resources/remember! out)
    (api/publish! ctx :echo/said {:text out})
    (api/publish! ctx :resource/updated {:uri resources/last-said-uri})
    {:text out}))
