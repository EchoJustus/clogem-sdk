# ADR-0002: `clogem.api` 1.0.0 is frozen

Status: Accepted (S04, 2026-10-12)

## Context

`clogem.api` has been the facade every module codes against since S01 (v0): ten ctx-first
functions over the `Runtime` protocol — `publish!`, `subscribe!`, `request!`, `submit-tx!`,
`query`, `run-process!`, `llm-chat!`, `job!`, `config`, `log` — each dispatching to the runtime
found in `(:clogem/runtime ctx)`, with no global state. Through S01–S03 the host grew the bus,
the single-writer store, jobs and the MCP facade behind that surface without changing it, but
two primitives, `run-process!` and `llm-chat!`, had signatures and no documented request or reply
shapes. S04 gives the host a process runner and an LLM adapter and gives this SDK a test kit
(`clogem.sdk.testkit`); the host, the kit and every module, including proprietary modules loaded
at runtime, must agree on what each call takes and returns before modules are built on them.

## Decision

- `clogem.api` is version **1.0.0** and frozen. `clogem.api/version` is `"1.0.0"` and
  `clogem.api/major` is `1`; a manifest declares `:module/requires {:clogem/api "1"}` and the
  manifest checker accepts only that major.
- The published contract is the ten functions with their arities, the `Runtime` protocol, and
  the request and reply shapes documented in the namespace docstring:
  - `run-process!`: `argv` is a non-empty vector of strings whose first element the host
    resolves (its configured `:bins` map, else PATH, `.exe` on Windows), never a shell string.
    `opts` are `:timeout-ms`, `:cwd`, `:env`, `:stdin`, `:on-progress` (one call per
    `-progress pipe:1` block, keyword → string; stdout is then not accumulated), `:cancel` (an
    atom; a hard kill) and `:max-output-bytes`. The reply is
    `{:exit :out :err :duration-ms :timed-out? :cancelled? :truncated? :argv}`; errors are
    `ex-info` with `:type :process/binary-missing` or `:process/invalid-argv`.
  - `llm-chat!`: the request carries `:messages` (`:role` system | user | assistant, a string
    `:content`, optional base64 `:images`), `:model`, `:format` (nil, `"json"` or a JSON Schema
    map), `:temperature` (default 0), `:timeout-ms` and `:options`. The reply is
    `{:content :json :model :provider :done-reason :duration-ms :raw}`, `:json` parsed only when
    `:format` was given and the content is valid JSON. Errors are `ex-info` with
    `:type :llm/unreachable | :llm/timeout | :llm/provider-error | :llm/invalid-request`. Egress
    goes only to the host's configured loopback or LAN endpoint.
  - `job!`: `op` is `:create`, `:progress`, `:complete`, `:fail`, `:cancel` or `:get` and `job`
    the operation's map: `:create {:kind string :input any [:id string]}`,
    `:progress {:id :progress 0..1 [:message]}`, `:complete {:id :result}`, `:fail {:id :error}`,
    `:cancel {:id}`, `:get {:id}`. The reply is `{:ok true :result job}` where `job` is
    `{:id string :module keyword :kind string :status :queued | :running | :done | :failed |
    :cancelled :progress double :message :input :result :error :cancel-requested? bool
    :created-at ms :updated-at ms :finished-at ms-or-nil}`, or
    `{:ok false :error {:type :invalid-job | :not-found | :not-owner | :job-finished |
    :unknown-op …}}`. A module sees only its own jobs.
  - `config`: a vector of keys under the module's own section of the host configuration; nil
    when absent.
- Integer milliseconds everywhere (`:timeout-ms`, `:duration-ms`); seconds appear only in the
  model-facing JSON a module builds itself.
- A change to a signature, an arity, a documented request or reply key, or an error type is a
  breaking change: it needs a major version bump (`clogem.api/major` 2, manifests declaring
  `"2"`) and an ADR in this repository, and it is a decision for the owner. Adding a documented
  optional key or a new error type is a minor change that still needs an ADR and a version note.
- `clogem.sdk.testkit` implements the same protocol with the contract's reply shapes as its
  defaults and refuses what the host refuses (`:process/invalid-argv`, `:llm/invalid-request`),
  so a module tested against the kit is tested against the contract.

## Consequences

- Module authors, the host and the test kit share one written contract; the kit's defaults are
  the contract's shapes, so a module's tests break when the contract would.
- Hosts may add behaviour behind the facade (timeouts, output caps, audit, egress checks, the
  module's manifest checked against what it publishes) without a version change, as long as the
  documented shapes hold.
- The API cannot grow a convenience function without an ADR; helper code belongs in a module or
  in a separate SDK namespace.
- Proprietary modules loaded at runtime depend on a stable `"1"`; the host refuses a manifest
  that requires another major.

## Revisit trigger

A primitive the facade cannot express without a new function or key (streaming model output, a
second process mode, asynchronous replies to `request!`), a host that must change a documented
reply shape, or a second host implementing `Runtime` that cannot honour one of the contracts.
Any of these opens a new ADR and a major bump.
