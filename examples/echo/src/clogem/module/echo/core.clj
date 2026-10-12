;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.module.echo.core
  "Entry point of the fictional `echo` module: what `:module/entry` in
   manifest.edn resolves to. A module entry is a map of three functions."
  (:require [clogem.api :as api]))

(def module
  {:start (fn [ctx]
            (api/log ctx :info {:msg "echo module started"})
            {:started-at (System/currentTimeMillis)})
   :stop (fn [_state] nil)
   :health (fn [_state] {:status :ok})})
