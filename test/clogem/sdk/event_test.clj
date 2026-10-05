;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.sdk.event-test
  (:require [clojure.test :refer [deftest is]]
            [clogem.sdk.event :as event]))

(deftest constructed-events-are-valid
  (let [e (event/event :echo :echo/said {:text "hi"})]
    (is (event/valid? e))
    (is (nil? (event/explain e)))
    (is (uuid? (:event/id e)))
    (is (inst? (:event/ts e)))
    (is (re-matches event/traceparent-re (:event/trace e)))
    (is (= {:text "hi"} (:event/payload e)))
    (is (not= (:event/id e) (:event/id (event/event :echo :echo/said {}))))))

(deftest a-trace-can-be-continued
  (let [parent (event/new-traceparent)
        e (event/event :echo :echo/said {} parent)]
    (is (= parent (:event/trace e)))))

(deftest envelope-rules
  (let [e (event/event :echo :echo/said {})]
    (is (some? (event/explain (assoc e :event/type :unqualified))) "type must be qualified")
    (is (some? (event/explain (assoc e :event/trace "nope"))) "trace must be a traceparent")
    (is (some? (event/explain (assoc e :event/payload [1 2]))) "payload must be a map")
    (is (some? (event/explain (assoc e :extra 1))) "no extra keys")
    (is (some? (event/explain (dissoc e :event/source))) "source is required")))
