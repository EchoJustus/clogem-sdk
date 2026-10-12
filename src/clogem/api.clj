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

   STATUS: pre-1.0 (\"v0\" in the roadmap). Signatures may still change until
   the S04 freeze, which publishes 1.0.0. The major version is already 1 so
   that manifests can declare `:module/requires {:clogem/api \"1\"}`.")

(def version
  "Version of this API. Pre-release until the S04 freeze."
  "1.0.0-alpha.1")

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
     with timeout and cancellation (S04).")
  (-llm-chat! [rt ctx request]
    "Synchronous chat completion through the hub's local LLM adapter (S04).")
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
  "Run `argv` (a vector of strings) as an external process with `opts`
   (:timeout-ms, :cwd, :on-progress)."
  ([ctx argv] (run-process! ctx argv {}))
  ([ctx argv opts] (-run-process! (runtime ctx) ctx argv opts)))

(defn llm-chat!
  "Chat completion through the hub's LLM adapter; `request` holds
   :messages, optional :format (JSON Schema), :images, :timeout-ms."
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
