;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.sdk.testkit
  "A fake `clogem.api/Runtime` for module tests: no hub, no bus, no
   database, no process, no model. Everything a module asks of its host is
   recorded in atoms and answered from the options given to `runtime`.

     (def rt (testkit/runtime {:queries {[\"SELECT 1\"] [{:one 1}]}}))
     (def ctx (testkit/ctx rt :echo))
     (tools/say ctx {:text \"hi\"})
     (testkit/published rt)   ; => [{:event/type :echo/said …} …]

   Options of `runtime` (all optional):
   :replies   command keyword → reply map, or (fn [command] reply); a map
              reply is returned as is; the whole option may also be one fn.
              Default reply {:ok false :error {:type :unknown-command …}}.
   :tx-reply  the reply of every submit-tx! (default {:ok true}), or
              (fn [tx] reply).
   :queries   query → rows: a map keyed by the query value, or (fn [q] rows);
              default [].
   :process   (fn [argv opts] reply); the reply is merged over the default
              {:exit 0 :out \"\" :err \"\" :duration-ms 0 :timed-out? false
              :cancelled? false :truncated? false :argv argv}, so a fake may
              answer only the keys it cares about. argv is checked as the
              contract says (a non-empty vector of strings) and
              {:type :process/invalid-argv} is thrown otherwise.
   :llm       (fn [request] reply); merged over the default {:content \"\"
              :json nil :model <request's or \"fake\"> :provider :fake
              :done-reason \"stop\" :duration-ms 0 :raw {}}. The fake never
              parses :content: a test that wants :json supplies it. The
              request is checked as the hub checks it (:messages non-empty
              with roles system|user|assistant and string contents, :format
              nil | \"json\" | a map, :temperature a number, :options a map)
              and {:type :llm/invalid-request} is thrown otherwise; :model
              and :timeout-ms are not checked, the host defaults them.
   :config    the module's own configuration section; `config` walks `path`
              in it with get-in.
   :jobs      seed jobs, a vector of job maps carrying a string :id (default
              []); the other keys default to a queued job created now, and a
              seed without :module belongs to whichever module touches it.
              Jobs are kept in memory with the hub's lifecycle and reply
              shape (clogem.api): :create → queued, with the op map's :id (a
              non-blank string of at most 128 characters) or the next free
              counter value as a string (\"1\", \"2\", \"3\" …); :progress →
              running; :complete → done; :fail → failed; :cancel → cancelled
              (these three set :finished-at); :get reads. Replies are
              {:ok true :result job}, job being {:id :module :kind :status
              :progress :message :input :result :error :cancel-requested?
              :created-at :updated-at :finished-at}, or {:ok false :error
              {:type :not-found | :job-finished | :not-owner | :invalid-job
              | :unknown-op …}}.

   `publish!` requires a qualified keyword and otherwise validates nothing;
   it records the envelope (built with `clogem.sdk.event/event`, the module
   as source) and does not call the kit's own subscribers: a test delivers
   incoming events with `deliver!`. `subscribe!` stores the handler and
   returns an unsubscribe fn. `log` records {:level :event} and prints
   nothing. The recorders (`published`, `subscriptions`, `requests`, `txs`,
   `queries`, `processes`, `chats`, `jobs`, `job-ops`, `logged`) return
   vectors in call order."
  (:require [clojure.string :as str]
            [clogem.api :as api]
            [clogem.sdk.event :as event]))

;; ---------------------------------------------------------------------------
;; Defaults

(def default-reply
  "What `request!` answers for a command without a configured reply."
  {:ok false :error {:type :unknown-command}})

(def default-tx-reply
  "What `submit-tx!` answers unless :tx-reply is given."
  {:ok true})

(defn default-process-reply
  "The reply of `run-process!` for `argv` unless a :process fn adds to it."
  [argv]
  {:exit 0 :out "" :err "" :duration-ms 0
   :timed-out? false :cancelled? false :truncated? false :argv argv})

(defn default-llm-reply
  "The reply of `llm-chat!` for `request` unless an :llm fn adds to it."
  [request]
  {:content "" :json nil :model (or (:model request) "fake") :provider :fake
   :done-reason "stop" :duration-ms 0 :raw {}})

(def ops
  "Job operations the kit understands."
  #{:create :progress :complete :fail :cancel :get})

(def ^:private open-statuses #{:queued :running})

(def ^:private roles #{"system" "user" "assistant"})

;; ---------------------------------------------------------------------------
;; Contract checks (what the hub refuses, the kit refuses too)

(defn- check-argv! [argv]
  (when-not (and (vector? argv) (seq argv) (every? string? argv))
    (throw (ex-info "argv must be a non-empty vector of strings (never a shell string)"
                    {:type :process/invalid-argv :argv argv}))))

(defn- message-problems [i m]
  (cond-> []
    (not (map? m)) (conj (str "messages[" i "] is not a map"))
    (and (map? m) (not (contains? roles (:role m))))
    (conj (str "messages[" i "] :role must be one of system, user, assistant"))
    (and (map? m) (not (string? (:content m))))
    (conj (str "messages[" i "] :content must be a string"))
    (and (map? m) (contains? m :images)
         (not (and (sequential? (:images m)) (every? string? (:images m)))))
    (conj (str "messages[" i "] :images must be a sequence of base64 strings"))))

(defn- check-request!
  "The hub's checks of a chat request, except :model and :timeout-ms, which
   the host defaults."
  [request]
  (let [{:keys [messages format options]} request
        temperature (or (:temperature request) (:temperature options) 0)
        problems (cond-> (if (and (sequential? messages) (seq messages))
                           (vec (mapcat (fn [i m] (message-problems i m)) (range) messages))
                           [":messages must be a non-empty sequence"])
                   (not (or (nil? format) (= "json" format) (map? format)))
                   (conj ":format must be nil, \"json\" or a JSON Schema map")
                   (not (number? temperature))
                   (conj ":temperature must be a number")
                   (not (or (nil? options) (map? options)))
                   (conj ":options must be a map"))]
    (when (seq problems)
      (throw (ex-info (str "invalid llm request: " (str/join "; " problems))
                      {:type :llm/invalid-request :problems problems})))))

(defn- check-event-type! [event-type]
  (when-not (qualified-keyword? event-type)
    (throw (ex-info (str "event type must be a qualified keyword, got " (pr-str event-type))
                    {:type :invalid-event-type :event/type event-type}))))

;; ---------------------------------------------------------------------------
;; Jobs (in memory)

(defn- invalid-job [& problems]
  {:ok false :error {:type :invalid-job :problems (vec problems)}})

(defn- job-reply [job] {:ok true :result job})

(defn- valid-id?
  "A job id as the hub stores it: a non-blank string of at most 128 characters."
  [id]
  (and (string? id) (not (str/blank? id)) (<= (count id) 128)))

(defn- owner?
  "A module may touch its own jobs; a seed job without :module belongs to
   whichever module touches it."
  [job module]
  (or (nil? (:module job)) (= module (:module job))))

(defn- explain-no-change [jobs module id]
  (let [job (get jobs id)]
    (cond
      (nil? job) {:ok false :error {:type :not-found :id id}}
      (not (owner? job module)) {:ok false :error {:type :not-owner :id id :owner (:module job)}}
      :else {:ok false :error {:type :job-finished :id id :status (:status job)}})))

(defn- change-job!
  "Apply `(f job now)` to the open job `id` owned by `module`, stamp
   :updated-at and reply with the job. As in the hub, the id must be a
   non-blank string."
  [state module id f]
  (if-not (and (string? id) (not (str/blank? id)))
    (invalid-job ":id must be a non-blank string")
    (let [result (atom nil)]
      (swap! state
             (fn [{:keys [jobs] :as s}]
               (let [job (get jobs id)]
                 (if (and job (owner? job module) (contains? open-statuses (:status job)))
                   (let [now (System/currentTimeMillis)
                         job' (assoc (f job now) :updated-at now)]
                     (reset! result (job-reply job'))
                     (assoc-in s [:jobs id] job'))
                   (do (reset! result (explain-no-change jobs module id))
                       s)))))
      @result)))

(defn- free-id
  "The first counter value from `n` whose string is not a job id yet."
  [jobs n]
  (if (contains? jobs (str n)) (recur jobs (inc n)) n))

(defn- create-job!
  "Insert a queued job with every key the contract documents: the op map's
   :id, else the next free counter value as a string."
  [state module {:keys [id kind input]}]
  (cond
    (not (and (string? kind) (not (str/blank? kind))))
    (invalid-job ":kind must be a non-blank string")
    (and (some? id) (not (valid-id? id)))
    (invalid-job ":id must be a non-blank string of at most 128 characters")
    :else
    (let [result (atom nil)]
      (swap! state
             (fn [{:keys [next-job-id jobs] :as s}]
               (let [n (when (nil? id) (free-id jobs next-job-id))
                     job-id (or id (str n))]
                 (if (contains? jobs job-id)
                   (do (reset! result (invalid-job (str "a job with id " (pr-str job-id) " exists")))
                       s)
                   (let [now (System/currentTimeMillis)
                         job {:id job-id :module module :kind kind :status :queued :progress 0.0
                              :message nil :input input :result nil :error nil
                              :cancel-requested? false :created-at now :updated-at now
                              :finished-at nil}]
                     (reset! result (job-reply job))
                     (-> s
                         (assoc-in [:jobs job-id] job)
                         (update :job-order conj job-id)
                         (cond-> n (assoc :next-job-id (inc n)))))))))
      @result)))

(defn- handle-job! [state module op {:keys [id] :as job}]
  (case op
    :create (create-job! state module job)
    :progress (let [{:keys [progress message]} job]
                (cond
                  (not (and (number? progress) (<= 0 progress 1)))
                  (invalid-job ":progress must be a number between 0 and 1")
                  (and (some? message) (not (string? message)))
                  (invalid-job ":message must be a string")
                  :else (change-job! state module id
                                     (fn [j _] (assoc j :status :running
                                                      :progress (double progress) :message message)))))
    :complete (change-job! state module id
                           (fn [j now] (assoc j :status :done :progress 1.0
                                              :result (:result job) :finished-at now)))
    :fail (change-job! state module id
                       (fn [j now] (assoc j :status :failed :error (:error job) :finished-at now)))
    :cancel (change-job! state module id
                         (fn [j now] (assoc j :status :cancelled :cancel-requested? true :finished-at now)))
    :get (let [found (when (string? id) (get-in @state [:jobs id]))]
           (cond
             (nil? found) {:ok false :error {:type :not-found :id id}}
             (not (owner? found module)) {:ok false :error {:type :not-owner :id id :owner (:module found)}}
             :else (job-reply found)))
    {:ok false :error {:type :unknown-op :op op :ops (vec (sort ops))}}))

;; ---------------------------------------------------------------------------
;; The runtime

(defn- record! [state k v]
  (swap! state update k conj v))

(defn- answer-request [replies command]
  (let [reply (cond
                (fn? replies) (replies command)
                (map? replies) (let [r (get replies (:command command))]
                                 (if (fn? r) (r command) r))
                :else nil)]
    (or reply (assoc-in default-reply [:error :command] (:command command)))))

(defn- answer-query [queries q]
  (cond
    (fn? queries) (or (queries q) [])
    (map? queries) (get queries q [])
    :else []))

(defrecord FakeRuntime [opts state]
  api/Runtime
  (-publish! [_ ctx event-type payload]
    (check-event-type! event-type)
    (let [e (event/event (:module/id ctx) event-type payload)]
      (record! state :published e)
      e))
  (-subscribe! [_ ctx event-type handler]
    ;; the id is allocated inside the swap; the state after it holds the next one
    (let [id (dec (:next-sub-id (swap! state
                                       (fn [{:keys [next-sub-id] :as s}]
                                         (-> s
                                             (update :subscriptions conj
                                                     {:id next-sub-id :module/id (:module/id ctx)
                                                      :event/type event-type :handler handler})
                                             (update :next-sub-id inc))))))]
      (fn unsubscribe []
        (swap! state update :subscriptions #(filterv (fn [sub] (not= id (:id sub))) %))
        nil)))
  (-request! [_ _ctx command]
    (record! state :requests command)
    (answer-request (:replies opts) command))
  (-submit-tx! [_ _ctx tx]
    (record! state :txs tx)
    (let [reply (:tx-reply opts)]
      (cond (fn? reply) (reply tx)
            (some? reply) reply
            :else default-tx-reply)))
  (-query [_ _ctx q]
    (record! state :queries q)
    (answer-query (:queries opts) q))
  (-run-process! [_ _ctx argv opts']
    (check-argv! argv)
    (let [opts' (or opts' {})]
      (record! state :processes {:argv argv :opts opts'})
      (merge (default-process-reply argv)
             (when-let [f (:process opts)] (f argv opts')))))
  (-llm-chat! [_ _ctx request]
    (check-request! request)
    (record! state :chats request)
    (merge (default-llm-reply request)
           (when-let [f (:llm opts)] (f request))))
  (-job! [_ ctx op job]
    (record! state :job-ops {:op op :job job})
    (handle-job! state (:module/id ctx) op (or job {})))
  (-config [_ _ctx path]
    (get-in (:config opts) path))
  (-log [_ _ctx level e]
    (record! state :logged {:level level :event e})
    nil))

(def ^:private seed-defaults
  "What a seed job gets for the keys it leaves out: a queued job that has not
   finished; :created-at and :updated-at are stamped when the runtime is built."
  {:status :queued :progress 0.0 :message nil :input nil :result nil :error nil
   :cancel-requested? false :finished-at nil})

(defn- seed-jobs [jobs]
  (reduce (fn [s {:keys [id] :as job}]
            (when-not (valid-id? id)
              (throw (ex-info "a seed job needs a non-blank string :id of at most 128 characters"
                              {:type :invalid-job :job job})))
            (let [now (System/currentTimeMillis)]
              (-> s
                  (assoc-in [:jobs id] (merge seed-defaults {:created-at now :updated-at now} job))
                  (update :job-order conj id))))
          {:jobs {} :job-order [] :next-job-id 1}
          jobs))

(defn runtime
  "A fake runtime answering from `opts` (see the namespace doc)."
  ([] (runtime {}))
  ([opts]
   (->FakeRuntime opts
                  (atom (merge {:published [] :subscriptions [] :requests [] :txs []
                                :queries [] :processes [] :chats [] :job-ops [] :logged []
                                :next-sub-id 1}
                               (seed-jobs (:jobs opts)))))))

(defn ctx
  "The ctx a module function receives: {:clogem/runtime rt :module/id id}
   plus :module/manifest when a manifest is given."
  ([rt module-id] (ctx rt module-id nil))
  ([rt module-id manifest]
   (cond-> {:clogem/runtime rt :module/id module-id}
     (some? manifest) (assoc :module/manifest manifest))))

;; ---------------------------------------------------------------------------
;; Recorders

(defn- state-of [^FakeRuntime rt] @(:state rt))

(defn published
  "Every envelope published through `rt`, oldest first."
  [rt]
  (:published (state-of rt)))

(defn subscriptions
  "The active subscriptions: [{:event/type t :module/id id :handler f} …]
   in subscription order; unsubscribed ones are gone."
  [rt]
  (mapv #(dissoc % :id) (:subscriptions (state-of rt))))

(defn requests
  "Every command sent through `request!`, oldest first."
  [rt]
  (:requests (state-of rt)))

(defn txs
  "Every transaction submitted through `submit-tx!`, oldest first."
  [rt]
  (:txs (state-of rt)))

(defn queries
  "Every query run through `query`, oldest first."
  [rt]
  (:queries (state-of rt)))

(defn processes
  "Every process run: [{:argv argv :opts opts} …], oldest first."
  [rt]
  (:processes (state-of rt)))

(defn chats
  "Every request sent through `llm-chat!`, oldest first."
  [rt]
  (:chats (state-of rt)))

(defn jobs
  "The current job maps, in creation order."
  [rt]
  (let [{:keys [jobs job-order]} (state-of rt)]
    (mapv jobs job-order)))

(defn job-ops
  "Every job operation: [{:op op :job map} …], oldest first."
  [rt]
  (:job-ops (state-of rt)))

(defn logged
  "Every log call: [{:level level :event map} …], oldest first."
  [rt]
  (:logged (state-of rt)))

;; ---------------------------------------------------------------------------
;; Driving a subscribing module

(defn deliver!
  "Call every handler subscribed to `event-type` synchronously, in
   subscription order, with an envelope for `payload` (source `source`,
   default :test). A handler's exception propagates to the caller. Returns
   the envelope."
  ([rt event-type payload] (deliver! rt event-type payload :test))
  ([rt event-type payload source]
   (check-event-type! event-type)
   (let [e (event/event source event-type payload)]
     (doseq [{:keys [handler] :as sub} (:subscriptions (state-of rt))
             :when (= event-type (:event/type sub))]
       (handler e))
     e)))
