# Writing a Clogem module

A module is a directory with a `manifest.edn` and Babashka code under `clogem.module.<id>.*`.
The hub loads it in-process, validates the manifest with this SDK, starts it, and exposes its
tools, resources and prompts over MCP. The fictional `echo` module in `examples/echo/` is the
smallest complete example; this guide walks through it.

## 1. The manifest is the contract

`resources/clogem/module/<id>/manifest.edn` is read with `clojure.edn` (no code is evaluated)
and is the single source of truth for everything the hub knows about the module:

```clojure
{:manifest/version 1
 :module/id :echo                                  ; ^[a-z][a-z0-9-]*$
 :module/version "0.1.0"                           ; semantic version
 :module/license :apache-2.0                       ; :epl-2.0 | :apache-2.0 | :proprietary
 :module/description "Fictional example module that echoes a message back."
 :module/entry clogem.module.echo.core/module      ; {:start (fn [ctx] state) :stop (fn [state]) :health (fn [state])}
 :module/requires {:clogem/api "1" :bins [] :env #{}}
 :bus {:publishes {:echo/said [:map [:text :string]]
                   :resource/updated [:map {:closed true} [:uri :string]]}   ; see §3
       :subscribes #{}}
 :db {:migrations nil}
 :mcp {:tools [{:name "echo_say"
                :title "Echo"
                :description "Echo a message back, optionally upper-cased. Use it to …"
                :handler clogem.module.echo.tools/say
                :input [:map {:closed true} [:text [:string {:min 1 :max 1000}]] [:upcase {:optional true} :boolean]]
                :output [:map [:text :string]]
                :annotations {:readOnlyHint true :idempotentHint true}
                :meta {}
                :profiles #{:gui :llm-small :llm-large :admin}}]
       :resources [{:uri "clogem://echo/last/said" :name "last-said" :mime-type "text/plain"
                    :handler clogem.module.echo.resources/last-said
                    :profiles #{:gui :llm-small :llm-large :admin}}]
       :resource-templates [{:uri-template "clogem://echo/history/{n}" :name "history" :mime-type "text/plain"
                             :handler clogem.module.echo.resources/history
                             :profiles #{:gui :llm-large :admin}}]
       :prompts []}
 :ui {:card {:title "Echo" :icon "chat" :quick-actions ["echo_say"]} :routes []}}
```

Rules the checker enforces (`bb manifest:check <path>` exits 1 and prints each problem):

- Tool names match `^[a-z][a-z0-9_]{0,63}$` and start with the module id plus `_`
  (dashes become underscores: module `my-mod` → `my_mod_`).
- `:module/entry` and every `:handler` are qualified symbols under `clogem.module.<id>.`.
- `:input` is a malli schema whose JSON Schema root is an object with no root-level
  `anyOf/oneOf/allOf`; property names match `[A-Za-z0-9_.-]{1,64}`. Hosts drop or flatten
  anything else.
- Descriptions are 1 to 1,000 characters.
- Resource URIs are `clogem://<id>/<kind>/<thing>`; templates use `{var}`.
- `:ui/card :quick-actions` name tools of this module.
- `:clogem/api` is the major version of `clogem.api` this SDK provides (`1`).
- Across the manifests given to one `bb manifest:check` call, and across the hub's registry,
  module ids, tool names and resource URIs are unique.

Conventions the checker cannot verify (reviewed by hand):

- Descriptions lead with what the tool does and when to use it.
- Set `destructiveHint` and `idempotentHint` honestly. A tool that must be confirmed by a human
  on every call sets `:meta {"anthropic/requiresUserInteraction" true}` (a Claude Code convention).

## 2. The entry point

```clojure
(ns clogem.module.echo.core
  (:require [clogem.api :as api]))

(def module
  {:start (fn [ctx] (api/log ctx :info {:msg "echo module started"}) {:started-at (System/currentTimeMillis)})
   :stop (fn [state] nil)
   :health (fn [state] {:status :ok})})
```

`:start` receives the module `ctx` and returns the module state; `:health` returns
`{:status :ok}` or `{:status :degraded …}` (the module keeps serving, `system_health` reports
it); `:stop` releases resources.

## 3. Handlers and the API

A tool handler takes the module `ctx` and the validated arguments (keyword keys) and returns
the structured result, which the hub validates against `:output` when declared:

```clojure
(ns clogem.module.echo.tools
  (:require [clojure.string :as str] [clogem.api :as api]))

(defn say [ctx {:keys [text upcase]}]
  (let [out (if upcase (str/upper-case text) text)]
    (api/publish! ctx :echo/said {:text out})
    {:text out}))
```

Everything a module needs from its host goes through `clogem.api`, `ctx` first:
`publish!`, `subscribe!`, `request!`, `submit-tx!`, `query`, `run-process!`, `llm-chat!`,
`job!`, `config`, `log`. The runtime behind them lives in `(:clogem/runtime ctx)`; the hub
supplies the real one and `clogem.sdk.testkit` a fake one for tests (§5), so module tests never
need a running hub. Throwing from a handler yields an MCP result with `isError: true`; it is
never a protocol error.

A resource handler takes the module `ctx` and `{:uri uri :params {var value}}` (template
variables arrive as strings) and returns `{:text s}` or `{:blob base64}` with an optional
`:mime-type`, or `nil` when nothing exists at that URI (the hub answers `-32602 Resource not
found`). When the content behind a resource changes, publish `clogem.sdk.event/resource-updated`
(`:resource/updated`, payload `{:uri uri}`; declare it in `:bus/publishes`): the hub turns it into
`notifications/resources/updated` for clients that subscribed to that URI. Resources and templates
carry `:profiles` like tools; a client sees and reads only what its profile exposes.

```clojure
(ns clogem.module.echo.resources)

(defonce history-ref (atom []))

(defn last-said [ctx request]
  {:mime-type "text/plain" :text (or (peek @history-ref) "")})

(defn history [ctx {:keys [params]}]
  (let [n (parse-long (str (:n params)))]
    (when (and n (< -1 n (count @history-ref)))
      {:mime-type "text/plain" :text (nth @history-ref n)})))
```

## 4. What a module may require

Only `clogem.api`, its own namespaces and the allowlisted libraries (`clojure.*` except
`clojure.java.shell` and `clojure.core.async`, `malli.*`, `cheshire.core`, `babashka.fs`), plus
`clojure.test` and `clogem.sdk.testkit` in tests. Never `clogem.hub.*`, another module,
`babashka.process`, `babashka.pods`, `org.httpkit.*` or `babashka.http-client`: processes, the
database, the LLM and the network are reached through the API so the hub can enforce timeouts,
local-first egress and media safety. Module code does not print; it logs through `api/log`.
`bb lint` checks all of this (`clogem.sdk.lint`).

## 5. Testing a module with the test kit

`clogem.sdk.testkit` is a fake `clogem.api/Runtime`: no hub, no bus, no database, no process,
no model. A test builds a runtime from options, wraps it in a ctx and calls the module's handlers
directly; everything the module asked of its host is recorded and can be asserted. Module tests
therefore never need a running hub, ffmpeg or a model, and pass on CI runners without them.

```clojure
(ns clogem.module.echo.tools-test
  (:require [clojure.test :refer [deftest is]]
            [clogem.sdk.testkit :as tk]
            [clogem.module.echo.tools :as tools]))

(deftest say-echoes-and-publishes
  (let [rt (tk/runtime)
        ctx (tk/ctx rt :echo)]                        ; (tk/ctx rt :echo manifest) adds :module/manifest
    (is (= {:text "HI"} (tools/say ctx {:text "hi" :upcase true})))
    (is (= [[:echo/said {:text "HI"}] [:resource/updated {:uri "clogem://echo/last/said"}]]
           (mapv (juxt :event/type :event/payload) (tk/published rt))))))
```

Behaviour comes from the options of `tk/runtime` (all optional):

| Option | Answers | Default |
|---|---|---|
| `:replies` | `request!`: command keyword → reply map or `(fn [command] reply)`; or one fn for every command | `{:ok false :error {:type :unknown-command}}` |
| `:tx-reply` | `submit-tx!`: a reply map or `(fn [tx] reply)` | `{:ok true}` |
| `:queries` | `query`: a map from the query value to rows, or `(fn [q] rows)` | `[]` |
| `:process` | `run-process!`: `(fn [argv opts] reply)`, merged over the contract's default reply, so a fake answers only the keys it cares about | `{:exit 0 :out "" :err "" :duration-ms 0 :timed-out? false :cancelled? false :truncated? false :argv argv}` |
| `:llm` | `llm-chat!`: `(fn [request] reply)`, merged over the default | `{:content "" :json nil :model "fake" :provider :fake :done-reason "stop" :duration-ms 0 :raw {}}` |
| `:config` | `config`: the module's own configuration map, walked with the path | nothing (`nil` for every path) |
| `:jobs` | `job!`: seed jobs (maps with a string `:id`, usually a `:module`; the other keys default to a queued job); jobs then live in memory with the host's lifecycle, queued → running → done / failed / cancelled, and `:get` reads. `:create` takes the op map's `:id` or allocates `"1"`, `"2"`, `"3"` …; replies are `{:ok true :result job}` with the job map `clogem.api` documents, or `{:ok false :error {:type …}}` | none |

The recorders return vectors in call order: `(tk/published rt)` (envelopes built with
`clogem.sdk.event/event`, the module as source), `(tk/subscriptions rt)`, `(tk/requests rt)`,
`(tk/txs rt)`, `(tk/queries rt)`, `(tk/processes rt)` (`{:argv :opts}`), `(tk/chats rt)`,
`(tk/jobs rt)` (the current job maps), `(tk/job-ops rt)` and `(tk/logged rt)` (`{:level :event}`;
`api/log` prints nothing).

A module that subscribes is driven with `(tk/deliver! rt :media/registered {:id "m-1"})`: every
handler it registered through `subscribe!` for that type runs synchronously, in subscription order,
with a proper envelope (source `:test`, or the keyword given as a fourth argument). `publish!` only
records; it never calls the kit's own subscribers.

Faking a process and a model:

```clojure
(tk/runtime {:process (fn [argv _opts]
                        (if (= "ffprobe" (first argv))
                          {:out (slurp "test/fixtures/probe.json")}
                          {:exit 1 :err "unexpected program"}))
             :llm (fn [_request]
                    {:content "{\"title\":\"x\"}" :json {:title "x"}})})
```

The fake never parses `:content`: a test that wants `:json` supplies it, which is also how a test
feeds a module the malformed model output it must survive. The kit refuses what the host refuses:
an argv that is not a non-empty vector of strings (`:process/invalid-argv`), a request without
valid messages or with a `:format`, `:temperature` or `:options` the host would refuse
(`:llm/invalid-request`), an event type that is not a qualified keyword. It does not
check that a published event is declared in the manifest or matches its schema; the hub enforces
that at runtime, so assert it in the test when it matters:

```clojure
(let [declared (get-in manifest [:bus :publishes])]
  (doseq [{:event/keys [type payload]} (tk/published rt)]
    (is (contains? declared type))
    (is (malli.core/validate (get declared type) payload))))
```

`clogem.sdk.testkit` is allowed in test namespaces only (`bb lint` refuses it in module code).

## 6. Checklist

1. `bb manifest:check resources/clogem/module/<id>/manifest.edn` is clean.
2. `bb lint` and `bb test` are green in the module directory; the tests run against
   `clogem.sdk.testkit`, never a running hub (§5).
3. The module's directory is listed in `modules.edn` in the hub's config directory
   (`%LOCALAPPDATA%\clogem\` on Windows, `~/.config/clogem/` on POSIX; S04) and `system_list_modules`
   shows the module as `ready`.
