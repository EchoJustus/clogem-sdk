;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.module.echo.resources
  "Resource handlers of the fictional `echo` module. A resource handler takes
   the module ctx and {:uri uri :params {var value}} (template variables are
   strings) and returns {:text s} or {:blob base64} with an optional
   :mime-type, or nil when there is nothing at that URI.")

(def last-said-uri "clogem://echo/last/said")

(defonce ^{:doc "Every text echoed so far, oldest first (process-local example state)."}
  history-ref (atom []))

(defn remember!
  "Append `text` to the history."
  [text]
  (swap! history-ref conj text))

(defn reset-history!
  "Forget everything (tests)."
  []
  (reset! history-ref []))

(defn last-said
  "clogem://echo/last/said: the most recent text, or an empty string."
  [_ctx _request]
  {:mime-type "text/plain" :text (or (peek @history-ref) "")})

(defn history
  "clogem://echo/history/{n}: the n-th text, or nil when n is out of range."
  [_ctx {:keys [params]}]
  (let [n (try (Long/parseLong (str (:n params))) (catch Exception _ nil))
        xs @history-ref]
    (when (and n (<= 0 n) (< n (count xs)))
      {:mime-type "text/plain" :text (nth xs n)})))
