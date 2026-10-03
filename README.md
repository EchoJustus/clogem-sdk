# clogem-sdk

Plugin contract for Clogem modules: the `clogem.api` facade, `manifest.edn` schema, event formats, and a test kit for building modules without a running hub.

Status: S01. The contract is implemented: `clogem.api` (1.0.0-alpha.1, frozen as 1.0.0 in S04), `clogem.sdk.manifest` (meta-schema, semantic rules, `bb manifest:check`) and `clogem.sdk.event`; `clogem.sdk.testkit` follows in S04. This repository also hosts the development tooling shared by every Clogem repository: `clogem.sdk.lint` (fitness checks) and `clogem.sdk.guard` (public-repository leak guard).

## Commands

```sh
bb test            # clojure.test over test/**/*_test.clj
bb lint            # fitness checks: headers, namespace ownership, module isolation, pods, prints, manifests, home paths
bb manifest:check <path>...  # validate manifest.edn files; exits 1 on any problem
bb guard:message <file>      # leak guard over a commit message (commit-msg hook)
bb guard:public    # leak guard over the tree and git history
bb hooks:install   # pre-commit (guard:public --staged) and commit-msg (guard:message) hooks
```

Requires Babashka 1.13.225 or newer. Consumers depend on this repository as a sibling checkout: `{:local/root "../clogem-sdk"}`.

## License

© 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com). Licensed under the Apache License, Version 2.0; see LICENSE.
