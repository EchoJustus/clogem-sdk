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
 :bus {:publishes {:echo/said [:map [:text :string]]} :subscribes #{}}
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
       :resources [] :resource-templates [] :prompts []}
 :ui {:card {:title "Echo" :icon "chat" :quick-actions ["echo_say"]} :routes []}}
```

Rules the checker enforces (`bb manifest:check <path>` exits 1 and prints each problem):

- Tool names match `^[a-z][a-z0-9_]{0,63}$` and start with the module id plus `_`
  (dashes become underscores: module `my-mod` → `my_mod_`).
- `:module/entry` and every `:handler` are qualified symbols under `clogem.module.<id>.`.
- `:input` is a malli schema whose JSON Schema root is an object with no root-level
  `anyOf/oneOf/allOf`; property names match `[A-Za-z0-9_.-]{1,64}`. Hosts drop or flatten
  anything else.
- Descriptions lead with what the tool does and when to use it, at most 1,000 characters.
- Set `destructiveHint` and `idempotentHint` honestly. A tool that must be confirmed by a human
  on every call sets `:meta {"anthropic/requiresUserInteraction" true}`.
- Resource URIs are `clogem://<id>/<kind>/<thing>`; templates use `{var}`.
- `:ui/card :quick-actions` name tools of this module.
- `:clogem/api` is the major version of `clogem.api` this SDK provides (`1`).
- Across the registry, module ids, tool names and resource URIs are unique.

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
supplies the real one and `clogem.sdk.testkit` (S04) a fake one for tests, so module tests never
need a running hub. Throwing from a handler yields an MCP result with `isError: true`; it is
never a protocol error.

## 4. What a module may require

Only `clogem.api`, its own namespaces and the allowlisted libraries (`clojure.*` except
`clojure.java.shell` and `clojure.core.async`, `malli.*`, `cheshire.core`, `babashka.fs`), plus
`clojure.test` and `clogem.sdk.testkit` in tests. Never `clogem.hub.*`, another module,
`babashka.process`, `babashka.pods`, `org.httpkit.*` or `babashka.http-client`: processes, the
database, the LLM and the network are reached through the API so the hub can enforce timeouts,
local-first egress and media safety. Module code does not print; it logs through `api/log`.
`bb lint` checks all of this (`clogem.sdk.lint`).

## 5. Checklist

1. `bb manifest:check resources/clogem/module/<id>/manifest.edn` is clean.
2. `bb lint` and `bb test` are green in the module directory.
3. The manifest is listed in `~/.config/clogem/modules.edn` (S04) and `system_list_modules`
   shows the module as `ready`.
