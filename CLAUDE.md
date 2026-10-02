# clogem-sdk — repository guardrails

Public repository, Apache-2.0. Everything committed here is visible to the world.
The workspace `CLAUDE.md` (one directory up) holds the prime directives; this file adds
what is specific to the SDK.

## Purpose

The plugin contract for Clogem modules:
- `clogem.api` — the ctx-first facade every module codes against (v0 in S01, frozen as v1 in S04)
- `clogem.sdk.manifest` — the `manifest.edn` meta-schema, a `clojure.edn` reader, humanized errors, `bb manifest:check`
- `clogem.sdk.event` — the event envelope schema
- `clogem.sdk.testkit` — a fake runtime for module tests (S04)
- `clogem.sdk.lint` and `clogem.sdk.guard` — dev tooling shared by every Clogem repository (S00)
- `examples/echo/` — a fictional module used by tests and docs
- `docs/plugin-guide.md`, `docs/deps.md`, `docs/adr/`

## Commands

```sh
bb test            # clojure.test over test/**/*_test.clj
bb lint            # fitness checks; must be green before every commit
bb manifest:check <path>   # exits 1 on any manifest error (S01)
bb guard:public    # leak guard: denylist + home paths + tokens + private files, tree and history
bb hooks:install   # installs guard:public as .git/hooks/pre-commit
```

`bb guard:public` reads `../.clogem/public-denylist.txt` from the private workspace (or
`$CLOGEM_DENYLIST`). Outside the workspace it fails, except under CI where only the built-in
rules run. The denylist is never copied into this repository.

## Layout

```text
LICENSE  README.md  CLAUDE.md  bb.edn  deps.edn  .gitignore
src/clogem/api.clj                 src/clogem/sdk/{manifest,event,testkit,lint,guard}.clj
test/clogem/…                      examples/echo/
docs/adr/  docs/plugin-guide.md  docs/deps.md
```

`bb.edn` is the source of truth for tasks and the dev classpath. `deps.edn` exists only so
that consumers' `{:local/root "../clogem-sdk"}` resolves (tools.deps reads deps.edn, not bb.edn);
keep its `:paths` in sync with the library paths in `bb.edn`.

## Rules

- **Namespaces:** this repo defines only `clogem.api` and `clogem.sdk.*`.
- **Direction of dependencies:** the hub and every module depend on this repo. This repo never
  depends on the hub or on any module, and never references them by name.
- **Public-safe wording (PD-8):** no proprietary code, private repository or module names,
  prompts, secrets, tokens, absolute personal paths, or session logs. Examples use the fictional
  `echo` module. Say "proprietary modules may be loaded at runtime", nothing more specific.
- **Headers:** every authored source file starts with the Apache-2.0 header; the exact text is
  `clogem.sdk.lint/header-lines`. Never write "All rights reserved" in this repo.
- **Contracts are data (PD-7):** the manifest meta-schema, the event envelope and `clogem.api`
  are published contracts. Changing one needs a version bump and an ADR, and is Ask-first.
- **Plugin API shape:** every `clogem.api` function takes `ctx` first and dispatches to the
  runtime in `(:clogem/runtime ctx)`. No global state.
- **Schemas:** malli in manifests, converted to JSON Schema 2020-12 at the MCP edge. Tool names
  match `^[a-z][a-z0-9_]{0,63}$` and are prefixed `<module-id>_`; input schema roots are objects
  without root-level `anyOf/oneOf/allOf`; property names match `[A-Za-z0-9_.-]{1,64}`.
- **Input handling:** never `eval`, `read-string` or `load-string` external input; `clojure.edn` only.
- **Dependencies (§2.5):** exact pins only; prefer bb built-ins; allowlisted additions are
  `metosin/malli` (from S01). No pods in this repo. Smoke-test and record new deps in
  `docs/deps.md`. `:min-bb-version` is the installed bb (1.13.225; floor 1.12.208).
- **Guardrails are executable (PD-12):** never weaken, skip or delete a lint, guard or test to
  get to green.
- **ADRs:** `docs/adr/NNNN-slug.md` with Context · Decision · Consequences · Revisit trigger,
  in public-safe wording. Session logs never live here.
