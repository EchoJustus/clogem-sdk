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

`deps.edn` carries no dependencies; it only declares `:paths` so that sibling checkouts can
depend on this repository with `{:local/root "../clogem-sdk"}`.
