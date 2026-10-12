;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;; Licensed under the Apache License, Version 2.0; see the LICENSE file.
;; SPDX-License-Identifier: Apache-2.0

(ns clogem.api
  "The facade every Clogem module codes against.

   Every function takes `ctx` first and dispatches to the runtime found in
   `(:clogem/runtime ctx)`, which implements the `Runtime` protocol. The hub
   supplies the real runtime; `clogem.sdk.testkit` (S04) supplies a fake one
   for module tests. There is no global state: a module never reaches the
   hub, the bus, the database or a process except through this namespace.

   STATUS: 1.0.0, frozen (ADR-0002 of this repository). The ten functions
   below and the `Runtime` protocol are the published contract: a change
   to a signature or to a documented request or reply shape needs a major
   version bump and an ADR. Manifests declare `:module/requires
   {:clogem/api \"1\"}`.

   Contracts of the primitives the hub provides (S04):

   run-process!  `argv` is a non-empty vector of strings; its first element
     is a program name the host resolves (its configured `:bins` map, else
     PATH; `.exe` on Windows), never a shell string. `opts`:
       :timeout-ms       kill after this long (host default 600000)
       :cwd              working directory
       :env              extra environment variables (map of strings)
       :stdin            a string written to the process, then closed
       :on-progress      (fn [block]) called for every `-progress pipe:1`
                         block ffmpeg writes to stdout, a map of keyword →
                         string (`:frame`, `:out_time_us`, `:speed`,
                         `:progress` \"continue\" | \"end\" …); when given,
                         stdout is not accumulated into :out
       :cancel           an atom; reset it to true to kill the process (a
                         hard kill on Windows, so partial outputs must be
                         temp files the caller removes)
       :max-output-bytes cap on :out and :err (host default 16 MiB); the
                         rest is dropped and :truncated? is true
     Reply: {:exit int-or-nil :out string :err string :duration-ms int
             :timed-out? bool :cancelled? bool :truncated? bool :argv [...]}
     Throws ex-info {:type :process/binary-missing :binary name} and
     {:type :process/invalid-argv}.

   llm-chat!  `request`:
       :messages    [{:role \"system\"|\"user\"|\"assistant\" :content string
                      :images [base64-string …]}] (images optional)
       :model       provider model name (host default)
       :format      nil, \"json\", or a JSON Schema map (structured output)
       :temperature number (default 0)
       :timeout-ms  int (host default)
       :options     provider-specific options map
     Reply: {:content string :json value-or-nil (parsed when :format was
             given and the content is valid JSON) :model string :provider
             keyword :done-reason string-or-nil :duration-ms int :raw map}
     Throws ex-info {:type :llm/unreachable | :llm/timeout |
     :llm/provider-error | :llm/invalid-request}. Egress goes only to the
     host's configured loopback or LAN endpoint (PD-9).

   job!  `op` is :create, :progress, :complete, :fail or :cancel; `job`
     the operation's map (S02). Reply {:ok true :job {…}} or
     {:ok false :error {:type …}}.

   config  `path` is a vector of keys under the module's own section of
     the host configuration (`:modules <id> …`); nil when absent.")

(def version
  "Version of this API. Frozen in S04."
  "1.0.0")

(def major
  "Major version a manifest's `:clogem/api` requirement must equal."
  1)

(defprotocol Runtime
  "What a module may ask of its host. Implemented by the hub's runtime and
   by the SDK test kit. Module code never calls these methods directly; it
   uses the ctx-first functions below."
  (-publish! [rt ctx event-type payload]
    "Broadcast a fact. The runtime wraps it in the event envelope
     (clogem.sdk.event) with the module as source. Returns the event.")
  (-subscribe! [rt ctx event-type handler]
    "Call `(handler event)` for every event of `event-type`. Returns an
     unsubscribe function.")
  (-request! [rt ctx command]
    "Send a command map to its single owner and return the reply map.
     v0: synchronous; S02 adds reply channels and timeouts.")
  (-submit-tx! [rt ctx tx]
    "Submit a `:db/tx` to the single writer and return its reply (S02).")
  (-query [rt ctx q]
    "Run a read-only query and return its rows (S02).")
  (-run-process! [rt ctx argv opts]
    "Run an external program given an argv vector, never a shell string,
     with timeout, progress and cancellation; see the namespace doc.")
  (-llm-chat! [rt ctx request]
    "Synchronous chat completion through the host's local LLM adapter;
     see the namespace doc.")
  (-job! [rt ctx op job]
    "Create, update, progress or cancel a job (S02).")
  (-config [rt ctx path]
    "Read the module's configuration at `path` (a vector of keys).")
  (-log [rt ctx level event]
    "Log one EDN map at `level` (:debug :info :warn :error) to the hub's log."))

(defn runtime
  "The runtime behind `ctx`. Throws when the context carries none."
  [ctx]
  (or (:clogem/runtime ctx)
      (throw (ex-info "ctx has no :clogem/runtime" {:ctx-keys (keys ctx)}))))

(defn module-id
  "The id of the module `ctx` belongs to."
  [ctx]
  (:module/id ctx))

(defn publish!
  "Broadcast the fact `payload` as an event of `event-type` (a qualified
   keyword declared in the manifest's `:bus/publishes`)."
  [ctx event-type payload]
  (-publish! (runtime ctx) ctx event-type payload))

(defn subscribe!
  "Subscribe `handler` to `event-type`. Returns an unsubscribe function."
  [ctx event-type handler]
  (-subscribe! (runtime ctx) ctx event-type handler))

(defn request!
  "Send `command` (a map with a `:command` keyword) to its owner; returns the reply."
  [ctx command]
  (-request! (runtime ctx) ctx command))

(defn submit-tx!
  "Submit a database transaction to the single writer; returns the reply."
  [ctx tx]
  (-submit-tx! (runtime ctx) ctx tx))

(defn query
  "Run a read-only query; returns the rows."
  [ctx q]
  (-query (runtime ctx) ctx q))

(defn run-process!
  "Run `argv` (a vector of strings, program name first) as an external
   process with `opts` (:timeout-ms :cwd :env :stdin :on-progress :cancel
   :max-output-bytes); the reply map is documented in the namespace doc."
  ([ctx argv] (run-process! ctx argv {}))
  ([ctx argv opts] (-run-process! (runtime ctx) ctx argv opts)))

(defn llm-chat!
  "Chat completion through the host's LLM adapter; `request` holds
   :messages (with optional per-message :images), :model, :format,
   :temperature, :timeout-ms, :options; the reply map is documented in the
   namespace doc."
  [ctx request]
  (-llm-chat! (runtime ctx) ctx request))

(defn job!
  "Job operations: `op` is :create, :progress, :complete, :fail or :cancel."
  [ctx op job]
  (-job! (runtime ctx) ctx op job))

(defn config
  "The module's configuration value at `path`."
  [ctx path]
  (-config (runtime ctx) ctx path))

(defn log
  "Log `event` (a map) at `level` through the hub."
  [ctx level event]
  (-log (runtime ctx) ctx level event))
