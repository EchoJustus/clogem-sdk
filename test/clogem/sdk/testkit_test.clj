;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.sdk.testkit-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [clogem.api :as api]
            [clogem.sdk.event :as event]
            [clogem.sdk.manifest :as manifest]
            [clogem.sdk.testkit :as tk]
            [clogem.module.echo.core :as echo-core]
            [clogem.module.echo.resources :as echo-resources]
            [clogem.module.echo.tools :as echo-tools]))

(def echo-manifest-path "examples/echo/resources/clogem/module/echo/manifest.edn")

(def echo-manifest (manifest/read-manifest echo-manifest-path))

(defn- ex-type
  "The :type of the ex-info `thunk` throws, or nil when it returns."
  [thunk]
  (try (thunk) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

;; ---------------------------------------------------------------------------
;; runtime and ctx

(deftest runtime-and-ctx-shape
  (let [rt (tk/runtime)]
    (is (satisfies? api/Runtime rt))
    (is (= {:clogem/runtime rt :module/id :echo} (tk/ctx rt :echo)))
    (is (= {:clogem/runtime rt :module/id :echo :module/manifest echo-manifest}
           (tk/ctx rt :echo echo-manifest)))
    (is (= :echo (api/module-id (tk/ctx rt :echo))))
    (is (= rt (api/runtime (tk/ctx rt :echo))))
    (testing "every recorder starts empty"
      (is (= [] (tk/published rt) (tk/subscriptions rt) (tk/requests rt) (tk/txs rt)
             (tk/queries rt) (tk/processes rt) (tk/chats rt) (tk/jobs rt) (tk/job-ops rt)
             (tk/logged rt))))
    (testing "two runtimes do not share state"
      (api/publish! (tk/ctx rt :echo) :echo/said {:text "a"})
      (is (= [] (tk/published (tk/runtime)))))))

;; ---------------------------------------------------------------------------
;; publish!

(deftest publish-records-valid-envelopes-with-the-module-as-source
  (let [rt (tk/runtime)
        ctx (tk/ctx rt :echo)
        e (api/publish! ctx :echo/said {:text "hi"})]
    (is (event/valid? e))
    (is (= :echo (:event/source e)))
    (is (= :echo/said (:event/type e)))
    (is (= {:text "hi"} (:event/payload e)))
    (api/publish! ctx :resource/updated {:uri "clogem://echo/last/said"})
    (is (= [:echo/said :resource/updated] (mapv :event/type (tk/published rt))))
    (is (= e (first (tk/published rt))) "publish! returns the envelope it recorded")
    (testing "only a qualified keyword is required; declarations and payloads are not checked"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"qualified keyword"
                            (api/publish! ctx :unqualified {})))
      (is (= :invalid-event-type (ex-type #(api/publish! ctx "echo/said" {}))))
      (is (= 2 (count (tk/published rt))) "a refused publish is not recorded")
      (is (event/valid? (api/publish! (tk/ctx rt :echo echo-manifest) :not/declared {:anything 1}))))))

;; ---------------------------------------------------------------------------
;; subscribe! and deliver!

(deftest subscribe-stores-handlers-and-deliver-calls-them-synchronously
  (let [rt (tk/runtime)
        ctx (tk/ctx rt :echo)
        seen (atom [])
        unsub-a (api/subscribe! ctx :media/registered #(swap! seen conj [:a %]))
        unsub-b (api/subscribe! ctx :media/registered #(swap! seen conj [:b %]))
        _ (api/subscribe! ctx :other/event #(swap! seen conj [:other %]))]
    (is (fn? unsub-a))
    (is (= [{:event/type :media/registered :module/id :echo}
            {:event/type :media/registered :module/id :echo}
            {:event/type :other/event :module/id :echo}]
           (mapv #(dissoc % :handler) (tk/subscriptions rt))))
    (is (every? fn? (map :handler (tk/subscriptions rt))))
    (let [e (tk/deliver! rt :media/registered {:id "m-1" :path "a.mp4"})]
      (is (event/valid? e))
      (is (= :test (:event/source e)) "the default source")
      (is (= {:id "m-1" :path "a.mp4"} (:event/payload e)))
      (is (= [[:a e] [:b e]] @seen) "handlers run in subscription order, before deliver! returns"))
    (testing "the source can be named"
      (is (= :media (:event/source (tk/deliver! rt :media/registered {:id "m-2"} :media)))))
    (testing "unsubscribe removes exactly that handler and is idempotent"
      (is (nil? (unsub-a)))
      (reset! seen [])
      (tk/deliver! rt :media/registered {:id "m-3"})
      (is (= [:b] (mapv first @seen)))
      (is (= 2 (count (tk/subscriptions rt))))
      (unsub-a)
      (is (= 2 (count (tk/subscriptions rt))))
      (unsub-b)
      (is (= [:other/event] (mapv :event/type (tk/subscriptions rt)))))
    (testing "deliver! with nobody subscribed still returns an envelope"
      (is (event/valid? (tk/deliver! rt :nobody/listens {}))))
    (testing "deliver! requires a qualified keyword, like publish!"
      (is (= :invalid-event-type (ex-type #(tk/deliver! rt :plain {})))))
    (testing "a handler's exception reaches the test"
      (api/subscribe! ctx :boom/event (fn [_] (throw (ex-info "boom" {:type :boom}))))
      (is (= :boom (ex-type #(tk/deliver! rt :boom/event {})))))
    (testing "publish! does not call the kit's own subscribers"
      (reset! seen [])
      (api/publish! ctx :media/registered {:id "m-4"})
      (is (= [] @seen)))))

;; ---------------------------------------------------------------------------
;; request!

(deftest request-answers-from-replies
  (testing "the default reply names the command"
    (let [rt (tk/runtime)
          ctx (tk/ctx rt :echo)
          reply (api/request! ctx {:command :system/modules})]
      (is (= {:ok false :error {:type :unknown-command}} tk/default-reply))
      (is (false? (:ok reply)))
      (is (= :unknown-command (get-in reply [:error :type])))
      (is (= :system/modules (get-in reply [:error :command])))
      (is (= [{:command :system/modules}] (tk/requests rt)))))
  (testing "a map of replies: maps and fns, keyed by command"
    (let [rt (tk/runtime {:replies {:system/health {:ok true :result {:status :ok}}
                                    :echo/count (fn [command] {:ok true :result (count (:items command))})}})
          ctx (tk/ctx rt :echo)]
      (is (= {:ok true :result {:status :ok}} (api/request! ctx {:command :system/health})))
      (is (= {:ok true :result 3} (api/request! ctx {:command :echo/count :items [1 2 3]})))
      (is (= :unknown-command (get-in (api/request! ctx {:command :nope/nope}) [:error :type])))
      (is (= [:system/health :echo/count :nope/nope] (mapv :command (tk/requests rt))))))
  (testing "one fn for every command"
    (let [rt (tk/runtime {:replies (fn [command] {:ok true :echo command})})
          ctx (tk/ctx rt :echo)]
      (is (= {:ok true :echo {:command :any/thing}} (api/request! ctx {:command :any/thing}))))))

;; ---------------------------------------------------------------------------
;; submit-tx!

(deftest submit-tx-records-and-answers
  (let [rt (tk/runtime)
        ctx (tk/ctx rt :echo)
        tx [["INSERT INTO echo_notes (text) VALUES (?)" "hi"]]]
    (is (= {:ok true} tk/default-tx-reply (api/submit-tx! ctx tx)))
    (is (= [tx] (tk/txs rt))))
  (let [rt (tk/runtime {:tx-reply {:ok false :error {:type :db/busy}}})
        ctx (tk/ctx rt :echo)]
    (is (= {:ok false :error {:type :db/busy}} (api/submit-tx! ctx {:statements []}))))
  (let [rt (tk/runtime {:tx-reply (fn [tx] {:ok true :result {:rows-affected (count tx)}})})
        ctx (tk/ctx rt :echo)]
    (is (= {:ok true :result {:rows-affected 2}} (api/submit-tx! ctx [["a"] ["b"]])))))

;; ---------------------------------------------------------------------------
;; query

(deftest query-answers-from-queries
  (let [rt (tk/runtime)
        ctx (tk/ctx rt :echo)]
    (is (= [] (api/query ctx ["SELECT 1"])))
    (is (= [["SELECT 1"]] (tk/queries rt))))
  (let [rows [{:id "m-1" :path "a.mp4"}]
        rt (tk/runtime {:queries {["SELECT * FROM media_items WHERE id = ?" "m-1"] rows}})
        ctx (tk/ctx rt :echo)]
    (is (= rows (api/query ctx ["SELECT * FROM media_items WHERE id = ?" "m-1"])))
    (is (= [] (api/query ctx ["SELECT * FROM media_items WHERE id = ?" "m-2"])))
    (is (= 2 (count (tk/queries rt)))))
  (let [rt (tk/runtime {:queries (fn [q] (when (= "SELECT 2" (first q)) [{:two 2}]))})
        ctx (tk/ctx rt :echo)]
    (is (= [{:two 2}] (api/query ctx ["SELECT 2"])))
    (is (= [] (api/query ctx ["SELECT 3"])) "nil from the fn is an empty result")))

;; ---------------------------------------------------------------------------
;; run-process!

(deftest run-process-records-and-answers-per-the-contract
  (testing "the default reply"
    (let [rt (tk/runtime)
          ctx (tk/ctx rt :echo)
          argv ["ffprobe" "-v" "error" "in.mp4"]]
      (is (= {:exit 0 :out "" :err "" :duration-ms 0
              :timed-out? false :cancelled? false :truncated? false :argv argv}
             (tk/default-process-reply argv)
             (api/run-process! ctx argv)))
      (is (= [{:argv argv :opts {}}] (tk/processes rt)))
      (api/run-process! ctx argv {:timeout-ms 10})
      (is (= {:timeout-ms 10} (:opts (second (tk/processes rt)))))))
  (testing "a :process fn answers only the keys it cares about"
    (let [rt (tk/runtime {:process (fn [argv opts] {:exit 1 :err (str "failed: " (first argv) " in " (:cwd opts))})})
          ctx (tk/ctx rt :echo)
          reply (api/run-process! ctx ["ffmpeg" "-i" "x"] {:cwd "work"})]
      (is (= 1 (:exit reply)))
      (is (= "failed: ffmpeg in work" (:err reply)))
      (is (= "" (:out reply)))
      (is (= ["ffmpeg" "-i" "x"] (:argv reply)))
      (is (false? (:timed-out? reply)))))
  (testing "a fake may drive :on-progress the way ffmpeg does"
    (let [blocks (atom [])
          rt (tk/runtime {:process (fn [_ {:keys [on-progress]}]
                                     (doseq [b [{:frame "1" :out_time_us "40000" :progress "continue"}
                                                {:frame "2" :out_time_us "80000" :progress "end"}]]
                                       (on-progress b))
                                     {:exit 0})})
          ctx (tk/ctx rt :echo)]
      (api/run-process! ctx ["ffmpeg" "-progress" "pipe:1"] {:on-progress #(swap! blocks conj %)})
      (is (= ["continue" "end"] (mapv :progress @blocks)))))
  (testing "argv must be a non-empty vector of strings, never a shell string"
    (let [rt (tk/runtime)
          ctx (tk/ctx rt :echo)]
      (doseq [bad ["ffmpeg -i x" [] ["ffmpeg" 1] '("ffmpeg") nil]]
        (is (= :process/invalid-argv (ex-type #(api/run-process! ctx bad))) (pr-str bad)))
      (is (= [] (tk/processes rt)) "refused calls are not recorded"))))

;; ---------------------------------------------------------------------------
;; llm-chat!

(deftest llm-chat-records-and-answers-per-the-contract
  (let [request {:messages [{:role "user" :content "hi"}]}]
    (testing "the default reply"
      (let [rt (tk/runtime)
            ctx (tk/ctx rt :echo)]
        (is (= {:content "" :json nil :model "fake" :provider :fake
                :done-reason "stop" :duration-ms 0 :raw {}}
               (tk/default-llm-reply request)
               (api/llm-chat! ctx request)))
        (is (= "vision-7b" (:model (api/llm-chat! ctx (assoc request :model "vision-7b"))))
            "a requested model is echoed, as the provider does")
        (is (= [request (assoc request :model "vision-7b")] (tk/chats rt)))))
    (testing "an :llm fn supplies :content and :json; the kit never parses"
      (let [rt (tk/runtime {:llm (fn [req] {:content "{\"title\":\"x\"}"
                                            :json (when (:format req) {:title "x"})})})
            ctx (tk/ctx rt :echo)]
        (is (= {:title "x"} (:json (api/llm-chat! ctx (assoc request :format {:type "object"})))))
        (is (nil? (:json (api/llm-chat! ctx request))))
        (is (= "{\"title\":\"x\"}" (:content (api/llm-chat! ctx request))))
        (is (= :fake (:provider (api/llm-chat! ctx request))))))
    (testing "images ride on messages"
      (let [rt (tk/runtime)
            ctx (tk/ctx rt :echo)
            with-image (assoc request :messages [{:role "user" :content "what is this?" :images ["aGVsbG8="]}])]
        (is (map? (api/llm-chat! ctx with-image)))
        (is (= ["aGVsbG8="] (get-in (first (tk/chats rt)) [:messages 0 :images])))))
    (testing "invalid requests are refused as the hub refuses them"
      (let [rt (tk/runtime)
            ctx (tk/ctx rt :echo)]
        (doseq [bad [{} {:messages []} {:messages [{:role "robot" :content "x"}]}
                     {:messages [{:role "user" :content 42}]}
                     {:messages [{:role "user" :content "x" :images "not-a-list"}]}
                     {:messages ["user: hi"]}
                     (assoc request :format :json)
                     (assoc request :format "yaml")
                     (assoc request :temperature "0")
                     (assoc request :options [])]]
          (is (= :llm/invalid-request (ex-type #(api/llm-chat! ctx bad))) (pr-str bad)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"messages\[0\] :role"
                              (api/llm-chat! ctx {:messages [{:role "robot" :content "x"}]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":format must be nil"
                              (api/llm-chat! ctx (assoc request :format :json))))
        (is (= [] (tk/chats rt)) "refused calls are not recorded")
        (testing "what the hub accepts, the kit accepts: both :format spellings, a number, an options map"
          (doseq [good [(assoc request :format "json") (assoc request :format {:type "object"})
                        (assoc request :temperature 0.2) (assoc request :options {:num_ctx 4096})
                        (assoc request :model "m" :timeout-ms 5)]]
            (is (map? (api/llm-chat! ctx good)) (pr-str good)))
          (is (= 5 (count (tk/chats rt)))))))))

;; ---------------------------------------------------------------------------
;; job!

(def job-keys
  "The keys of every job map, as clogem.api documents them."
  #{:id :module :kind :status :progress :message :input :result :error
    :cancel-requested? :created-at :updated-at :finished-at})

(deftest jobs-follow-the-hub-lifecycle-in-memory
  (let [rt (tk/runtime)
        ctx (tk/ctx rt :echo)
        before (System/currentTimeMillis)]
    (testing "create → queued: {:ok true :result job} with the contract's keys and a string id"
      (let [{:keys [ok result] :as reply} (api/job! ctx :create {:kind "index" :input {:media "m-1"}})]
        (is (true? ok))
        (is (= #{:ok :result} (set (keys reply))) "the reply key is :result, as in the hub")
        (is (= job-keys (set (keys result))))
        (is (= {:id "1" :module :echo :kind "index" :status :queued :progress 0.0
                :message nil :input {:media "m-1"} :result nil :error nil
                :cancel-requested? false :finished-at nil}
               (dissoc result :created-at :updated-at)))
        (is (and (int? (:created-at result)) (<= before (:created-at result))))
        (is (= (:created-at result) (:updated-at result))))
      (is (= "2" (get-in (api/job! ctx :create {:kind "other"}) [:result :id])) "ids \"1\", \"2\", \"3\" …")
      (is (= ["1" "2"] (mapv :id (tk/jobs rt)))))
    (testing "progress → running, stamping :updated-at"
      (let [{:keys [result]} (api/job! ctx :progress {:id "1" :progress 0.5 :message "half"})]
        (is (= [:running 0.5 "half" nil] ((juxt :status :progress :message :finished-at) result)))
        (is (and (int? (:updated-at result)) (<= (:created-at result) (:updated-at result))))
        (is (= :running (:status (first (tk/jobs rt)))) "the recorder shows the current state")))
    (testing "complete → done with the result and :finished-at"
      (let [{:keys [result]} (api/job! ctx :complete {:id "1" :result {:segments 3}})]
        (is (= [:done 1.0 {:segments 3}] ((juxt :status :progress :result) result)))
        (is (and (int? (:finished-at result)) (<= (:created-at result) (:finished-at result))))
        (is (= job-keys (set (keys result))))))
    (testing "a finished job refuses further changes"
      (is (= {:ok false :error {:type :job-finished :id "1" :status :done}}
             (api/job! ctx :progress {:id "1" :progress 0.9}))))
    (testing "fail and cancel finish the job too"
      (let [{:keys [result]} (api/job! ctx :fail {:id "2" :error {:type :boom}})]
        (is (= [:failed {:type :boom} 0.0] ((juxt :status :error :progress) result)))
        (is (int? (:finished-at result))))
      (let [{:keys [result]} (api/job! ctx :create {:kind "c"})
            cancelled (:result (api/job! ctx :cancel {:id (:id result)}))]
        (is (= "3" (:id result)))
        (is (= [:cancelled true] ((juxt :status :cancel-requested?) cancelled)))
        (is (int? (:finished-at cancelled)))
        (is (= :job-finished (get-in (api/job! ctx :complete {:id (:id result)}) [:error :type])))))
    (testing ":get reads"
      (let [{:keys [result]} (api/job! ctx :get {:id "1"})]
        (is (= :done (:status result)))
        (is (= job-keys (set (keys result))))))
    (testing "an unknown id → :not-found"
      (is (= {:ok false :error {:type :not-found :id "99"}} (api/job! ctx :progress {:id "99" :progress 0.1})))
      (is (= :not-found (get-in (api/job! ctx :get {:id "99"}) [:error :type])))
      (is (= :not-found (get-in (api/job! ctx :cancel {:id "99"}) [:error :type]))))
    (testing "another module's job → :not-owner"
      (let [other (tk/ctx rt :other)]
        (is (= {:ok false :error {:type :not-owner :id "1" :owner :echo}} (api/job! other :get {:id "1"})))
        (is (= :not-owner (get-in (api/job! other :cancel {:id "2"}) [:error :type])))))
    (testing "invalid ops and maps, refused as the hub refuses them"
      (is (= :invalid-job (get-in (api/job! ctx :create {:kind ""}) [:error :type])))
      (is (= :invalid-job (get-in (api/job! ctx :create {}) [:error :type])))
      (is (= :invalid-job (get-in (api/job! ctx :progress {:id "1" :progress 2}) [:error :type])))
      (is (= :invalid-job (get-in (api/job! ctx :progress {:id "1" :progress 0.1 :message 1}) [:error :type])))
      (doseq [bad-id [1 nil "" :kw]]
        (is (= :invalid-job (get-in (api/job! ctx :progress {:id bad-id :progress 0.1}) [:error :type]))
            (str "a change needs a string id: " (pr-str bad-id)))
        (is (= :invalid-job (get-in (api/job! ctx :cancel {:id bad-id}) [:error :type])) (pr-str bad-id))
        (is (= :not-found (get-in (api/job! ctx :get {:id bad-id}) [:error :type]))
            (str ":get answers :not-found for " (pr-str bad-id))))
      (is (= :unknown-op (get-in (api/job! ctx :retry {:id "1"}) [:error :type])))
      (is (= (vec (sort tk/ops)) (get-in (api/job! ctx :retry {:id "1"}) [:error :ops])))
      (is (= #{:create :progress :complete :fail :cancel :get} tk/ops)))
    (testing "every operation is recorded, refused ones included"
      (let [ops (tk/job-ops rt)]
        (is (= {:op :create :job {:kind "index" :input {:media "m-1"}}} (first ops)))
        (is (= {:op :retry :job {:id "1"}} (last ops)))
        (is (every? #(and (keyword? (:op %)) (map? (:job %))) ops))))))

(deftest jobs-can-be-seeded-and-given-ids
  (let [rt (tk/runtime {:jobs [{:id "1" :module :echo :kind "seeded" :status :running :progress 0.2}
                               {:id "ext" :kind "anyone"}]})
        ctx (tk/ctx rt :echo)
        long-id (apply str (repeat 128 "x"))]
    (is (= ["1" "ext"] (mapv :id (tk/jobs rt))))
    (testing "a seed is completed to the contract's shape"
      (let [{:keys [result]} (api/job! ctx :get {:id "1"})]
        (is (= job-keys (set (keys result))))
        (is (= [:running 0.2 :echo "seeded" nil nil nil nil false nil]
               ((juxt :status :progress :module :kind :message :input :result :error
                      :cancel-requested? :finished-at) result)))
        (is (and (int? (:created-at result)) (int? (:updated-at result))))))
    (is (= "2" (get-in (api/job! ctx :create {:kind "next"}) [:result :id])) "the counter skips taken ids")
    (is (= :queued (get-in (api/job! ctx :get {:id "ext"}) [:result :status])) "seed defaults")
    (is (= :done (get-in (api/job! (tk/ctx rt :other) :complete {:id "ext"}) [:result :status]))
        "a seed without :module belongs to whoever touches it")
    (is (= "custom" (get-in (api/job! ctx :create {:id "custom" :kind "k"}) [:result :id])))
    (is (= :invalid-job (get-in (api/job! ctx :create {:id "custom" :kind "k"}) [:error :type]))
        "duplicate id")
    (testing "an :id must be a non-blank string of at most 128 characters, as the hub requires"
      (doseq [bad [10 :kw "" "  " (str long-id "x")]]
        (let [reply (api/job! ctx :create {:id bad :kind "k"})]
          (is (= :invalid-job (get-in reply [:error :type])) (pr-str bad))
          (is (= [":id must be a non-blank string of at most 128 characters"]
                 (get-in reply [:error :problems])))))
      (is (= long-id (get-in (api/job! ctx :create {:id long-id :kind "k"}) [:result :id]))))
    (is (= "3" (get-in (api/job! ctx :create {:id "3" :kind "k"}) [:result :id])))
    (is (= "4" (get-in (api/job! ctx :create {:kind "k"}) [:result :id]))
        "… and past an explicit numeric-looking id")
    (is (= ["1" "ext" "2" "custom" long-id "3" "4"] (mapv :id (tk/jobs rt))) "creation order")
    (is (= :invalid-job (ex-type #(tk/runtime {:jobs [{:kind "no id"}]}))))
    (is (= :invalid-job (ex-type #(tk/runtime {:jobs [{:id 7 :kind "an integer id"}]}))))))

;; ---------------------------------------------------------------------------
;; config and log

(deftest config-walks-the-module-section
  (let [rt (tk/runtime {:config {:endpoint {:url "http://127.0.0.1:1234"} :window-ms 15000}})
        ctx (tk/ctx rt :echo)]
    (is (= "http://127.0.0.1:1234" (api/config ctx [:endpoint :url])))
    (is (= 15000 (api/config ctx [:window-ms])))
    (is (= {:url "http://127.0.0.1:1234"} (api/config ctx [:endpoint])))
    (is (nil? (api/config ctx [:missing])))
    (is (nil? (api/config ctx [:endpoint :missing])))
    (is (nil? (api/config (tk/ctx (tk/runtime) :echo) [:anything])))))

(deftest log-records-and-prints-nothing
  (let [rt (tk/runtime)
        ctx (tk/ctx rt :echo)
        returned (atom ::unset)
        out (with-out-str
              (binding [*err* *out*]
                (reset! returned (api/log ctx :info {:msg "started"}))
                (api/log ctx :warn {:msg "careful" :n 1})))]
    (is (nil? @returned))
    (is (= "" out))
    (is (= [{:level :info :event {:msg "started"}}
            {:level :warn :event {:msg "careful" :n 1}}]
           (tk/logged rt)))))

;; ---------------------------------------------------------------------------
;; The echo example module, driven through the kit the way the hub drives it

(defn- echo-handler
  "The handler var the echo manifest names for the `kind` entry at `idx`."
  [kind idx]
  (requiring-resolve (get-in echo-manifest [:mcp kind idx :handler])))

(deftest the-echo-module-runs-against-the-kit
  (echo-resources/reset-history!)
  (try
    (let [rt (tk/runtime)
          ctx (tk/ctx rt :echo echo-manifest)]
      (testing "the entry point"
        (let [state ((:start echo-core/module) ctx)]
          (is (int? (:started-at state)))
          (is (= {:status :ok} ((:health echo-core/module) state)))
          (is (nil? ((:stop echo-core/module) state)))
          (is (= [{:level :info :event {:msg "echo module started"}}] (tk/logged rt)))))
      (testing "the tool handler the manifest names"
        (let [say (echo-handler :tools 0)]
          (is (= #'echo-tools/say say))
          (is (= {:text "hi"} (say ctx {:text "hi"})))
          (is (= {:text "LOUD"} (say ctx {:text "loud" :upcase true})))
          (is (= [[:echo/said {:text "hi"}] [:resource/updated {:uri "clogem://echo/last/said"}]
                  [:echo/said {:text "LOUD"}] [:resource/updated {:uri "clogem://echo/last/said"}]]
                 (mapv (juxt :event/type :event/payload) (tk/published rt))))
          (is (every? #(= :echo (:event/source %)) (tk/published rt)))
          (is (every? event/valid? (tk/published rt)))))
      (testing "what it published is declared in its manifest and valid against the declared schemas"
        (let [declared (get-in echo-manifest [:bus :publishes])]
          (doseq [{:event/keys [type payload]} (tk/published rt)]
            (is (contains? declared type) (str type))
            (is (m/validate (get declared type) payload) (str type " " payload)))))
      (testing "the resource handlers the manifest names"
        (let [last-said (echo-handler :resources 0)
              history (echo-handler :resource-templates 0)]
          (is (= #'echo-resources/last-said last-said))
          (is (= {:mime-type "text/plain" :text "LOUD"} (last-said ctx {:uri "clogem://echo/last/said"})))
          (is (= {:mime-type "text/plain" :text "hi"}
                 (history ctx {:uri "clogem://echo/history/0" :params {:n "0"}})))
          (is (= "LOUD" (:text (history ctx {:uri "clogem://echo/history/1" :params {:n "1"}}))))
          (is (nil? (history ctx {:uri "clogem://echo/history/2" :params {:n "2"}})) "out of range")
          (is (nil? (history ctx {:uri "clogem://echo/history/x" :params {:n "x"}})) "not a number")))
      (testing "the module never asked for anything else"
        (is (= [] (tk/requests rt) (tk/txs rt) (tk/queries rt) (tk/processes rt) (tk/chats rt)
               (tk/jobs rt) (tk/subscriptions rt)))))
    (finally (echo-resources/reset-history!))))
