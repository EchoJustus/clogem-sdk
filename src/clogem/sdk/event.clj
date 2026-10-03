;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.sdk.event
  "The event envelope every fact on the bus is wrapped in.

   {:event/id uuid :event/type :ns/name :event/source :module-id
    :event/ts inst :event/trace \"traceparent\" :event/payload {…}}

   Modules never build envelopes themselves: the runtime wraps the payload
   given to `clogem.api/publish!`. This namespace is the published contract
   and the constructor the runtime uses."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def traceparent-re
  "W3C Trace Context `traceparent`: version-traceid-spanid-flags."
  #"^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")

(def Event
  "Malli schema of the envelope. Closed: no extra keys."
  [:map {:closed true}
   [:event/id :uuid]
   [:event/type :qualified-keyword]
   [:event/source :keyword]
   [:event/ts inst?]
   [:event/trace [:re traceparent-re]]
   [:event/payload :map]])

(defn- hex [^java.security.SecureRandom rnd bytes]
  (let [buf (byte-array bytes)]
    (.nextBytes rnd buf)
    (let [s (apply str (map #(format "%02x" (bit-and % 0xff)) buf))]
      ;; all-zero ids are invalid in Trace Context
      (if (every? #{\0} s) (str (subs s 1) "1") s))))

(def ^:private secure-random (java.security.SecureRandom.))

(defn new-traceparent
  "A fresh root `traceparent` (version 00, sampled)."
  []
  (str "00-" (hex secure-random 16) "-" (hex secure-random 8) "-01"))

(defn event
  "Build an envelope for `payload` published by module `source` under
   `event-type`. `trace` continues an existing traceparent; nil starts one."
  ([source event-type payload] (event source event-type payload nil))
  ([source event-type payload trace]
   {:event/id (random-uuid)
    :event/type event-type
    :event/source source
    :event/ts (java.util.Date.)
    :event/trace (or trace (new-traceparent))
    :event/payload payload}))

(defn valid?
  "True when `e` is a well-formed envelope."
  [e]
  (m/validate Event e))

(defn explain
  "Humanized validation errors for `e`, or nil when valid."
  [e]
  (some-> (m/explain Event e) me/humanize))
