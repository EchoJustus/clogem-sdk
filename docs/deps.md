# Dependencies

`bb.edn` is the source of truth. Exact pins only: no ranges, SNAPSHOT or LATEST.
Before adopting a dependency, smoke-test it in the installed Babashka
(`bb -Sdeps '{:deps {…}}' -e "(require '…)"`) and record it here.

Minimum Babashka: 1.13.225 (installed when the repository was bootstrapped). The floor is
1.12.208, the release from which bb waits for non-daemon threads (CHANGELOG entry for
babashka/babashka#1843, 2025-09-04).

## Allowlist

- Babashka built-ins: `babashka.fs`, `babashka.process`, `babashka.cli`, `babashka.http-client`,
  `cheshire`, `org.httpkit.server`, `clojure.core.async`, `clojure.edn`, `clojure.test`
- `metosin/malli` (from S01, after a smoke test)
- No pods in this repository.

Anything else is Ask-first.

## Record

| Session | Dependency | Version | Purpose | Smoke test |
|---|---|---|---|---|
| S00 | (none beyond built-ins) | — | `babashka.fs`, `babashka.process`, `clojure.test` for lint, guard and tests | bb 1.13.225 loads them |
| S01 | `metosin/malli` | 0.20.2 (latest on Clojars, 2026-10-03) | manifest meta-schema, event envelope, humanized errors, JSON Schema at the MCP edge | `bb -Sdeps '{:deps {metosin/malli {:mvn/version "0.20.2"}}}'`: `malli.core`, `malli.error`, `malli.json-schema`, `malli.util` load; validate, humanize and transform work (closed map → `additionalProperties false`) |
| S01 | `edamame` (bundled in bb) | bb 1.13.225 | reading source forms for the lint rules without evaluation (reader conditionals, auto-resolved keywords) | `edamame.core/parse-string-all` with `:read-cond :allow` works |

`deps.edn` declares the same `:deps` as `bb.edn` plus the library `:paths`, so that sibling
checkouts depending on this repository with `{:local/root "../clogem-sdk"}` get malli transitively.
