# ADR-0001: Schemas are malli data in the manifest; JSON Schema is derived at the MCP edge

Status: Accepted (S01, 2026-10-03)

## Context

A module's `manifest.edn` declares its tools, resources, prompts, bus events and UI card. Hosts
need JSON Schema (draft 2020-12) for tool inputs; the hub needs to validate tool arguments and
bus payloads at runtime; module authors want one notation. Manifests are read from disk and must
never be evaluated.

## Decision

- **Source of truth:** schemas are written as malli vector syntax inside `manifest.edn`
  (`[:map {:closed true} [:text [:string {:min 1}]]]`). The manifest is read with `clojure.edn`
  only (no reader eval, no custom readers), validated against the meta-schema in
  `clogem.sdk.manifest/Manifest`, and then checked semantically (tool-name prefix, handler and
  entry symbols under `clogem.module.<id>.`, input schema root is an object without root-level
  `anyOf/oneOf/allOf`, property names match `[A-Za-z0-9_.-]{1,64}`, uniqueness, quick actions
  name real tools, `clogem.api` major version).
- **Derivation:** `clogem.sdk.manifest/json-schema` converts a malli schema with
  `malli.json-schema/transform`. Closed maps become `additionalProperties false`; keywords, uuids
  and enums become strings. The hub emits that as each tool's `inputSchema` (and `outputSchema`
  when `:output` is declared) and validates `tools/call` arguments with the same malli schema.
- **Errors are humanized:** `malli.error/humanize` output is flattened to
  `path: message` lines, which `bb manifest:check <path>` prints before exiting 1.
- **Version pin:** `metosin/malli` 0.20.2, the latest release on Clojars when S01 ran
  (2026-10-03).

## Smoke test (Babashka 1.13.225)

```
bb -Sdeps '{:deps {metosin/malli {:mvn/version "0.20.2"}}}' -e "(require '[malli.core :as m] '[malli.error :as me] '[malli.json-schema :as mjs] '[malli.util :as mu]) …"
:ok true {:x ["should be an integer"], :y ["disallowed key"]}
{:type "object", :properties {:media {:type "string", :minLength 1}, :a {:type "number", :minimum 0},
 :tags {:type "array", :items {:type "string"}}, :k {:type "string", :enum ["a" "b"]},
 :kw {:type "string"}, :u {:type "string", :format "uuid"}}, :required [:media :tags :k :kw :u],
 :additionalProperties false}
```

`malli.core`, `malli.error`, `malli.json-schema` and `malli.util` all load; the fallback the
roadmap named for a failed smoke test (JSON Schema as EDN plus a minimal validator) was not
needed.

## Consequences

- One notation for authors; the hub and the SDK share one validator.
- Any schema malli cannot express in JSON Schema (functions, custom predicates) is rejected by
  `manifest:check` for tool inputs, by design: hosts could not use it.
- Changing the meta-schema is a published-contract change (version bump and ADR, Ask-first).

## Revisit trigger

A host requiring a JSON Schema construct malli cannot emit, or a malli release changing its
JSON Schema output (re-run `bb test`, which pins the derived shapes).
