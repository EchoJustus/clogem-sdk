;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.api-test
  (:require [clojure.test :refer [deftest is]]
            [clogem.api :as api]))

(defn- recording-runtime
  "A minimal Runtime that records every call. The S04 test kit generalizes this."
  [calls]
  (reify api/Runtime
    (-publish! [_ ctx t p] (swap! calls conj [:publish! (:module/id ctx) t p]) {:event/type t})
    (-subscribe! [_ _ t _] (swap! calls conj [:subscribe! t]) (fn [] :unsubscribed))
    (-request! [_ _ c] (swap! calls conj [:request! c]) {:ok true})
    (-submit-tx! [_ _ tx] (swap! calls conj [:submit-tx! tx]) {:ok true})
    (-query [_ _ q] (swap! calls conj [:query q]) [])
    (-run-process! [_ _ argv opts] (swap! calls conj [:run-process! argv opts]) {:exit 0})
    (-llm-chat! [_ _ r] (swap! calls conj [:llm-chat! r]) {:content ""})
    (-job! [_ _ op j] (swap! calls conj [:job! op j]) {:job/id 1})
    (-config [_ _ path] (swap! calls conj [:config path]) :value)
    (-log [_ _ level e] (swap! calls conj [:log level e]) nil)))

(deftest every-api-function-dispatches-to-the-runtime-in-ctx
  (let [calls (atom [])
        ctx {:clogem/runtime (recording-runtime calls) :module/id :echo}]
    (is (= :echo (api/module-id ctx)))
    (api/publish! ctx :echo/said {:text "x"})
    (api/subscribe! ctx :media/registered identity)
    (api/request! ctx {:command :system/modules})
    (api/submit-tx! ctx {:db/tx []})
    (api/query ctx {:select 1})
    (api/run-process! ctx ["ffprobe" "-v" "error"])
    (api/run-process! ctx ["ffmpeg"] {:timeout-ms 10})
    (api/llm-chat! ctx {:messages []})
    (api/job! ctx :create {:name "x"})
    (api/config ctx [:port])
    (api/log ctx :info {:msg "hi"})
    (is (= [[:publish! :echo :echo/said {:text "x"}]
            [:subscribe! :media/registered]
            [:request! {:command :system/modules}]
            [:submit-tx! {:db/tx []}]
            [:query {:select 1}]
            [:run-process! ["ffprobe" "-v" "error"] {}]
            [:run-process! ["ffmpeg"] {:timeout-ms 10}]
            [:llm-chat! {:messages []}]
            [:job! :create {:name "x"}]
            [:config [:port]]
            [:log :info {:msg "hi"}]]
           @calls))))

(deftest a-ctx-without-runtime-fails-loudly
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :clogem/runtime"
                        (api/publish! {:module/id :echo} :echo/said {}))))

(deftest version-metadata
  (is (= 1 api/major))
  (is (re-matches #"1\.0\.0.*" api/version)))
