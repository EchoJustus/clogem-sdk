# clogem-sdk

Plugin contract for Clogem modules: the `clogem.api` facade, `manifest.edn` schema, event formats, and a test kit for building modules without a running hub.

Status: bootstrapped (session S00). The contract itself (`clogem.api`, `clogem.sdk.manifest`, `clogem.sdk.event`) lands from S01. This repository also hosts the development tooling shared by every Clogem repository: `clogem.sdk.lint` (fitness checks) and `clogem.sdk.guard` (public-repository leak guard).

## Commands

```sh
bb test            # clojure.test over test/**/*_test.clj
bb lint            # fitness checks (license headers now; more rules from S01)
bb manifest:check  # validate a manifest.edn (arrives in S01)
bb guard:public    # leak guard over the tree and git history
bb hooks:install   # pre-commit hook that runs guard:public
```

Requires Babashka 1.13.225 or newer. Consumers depend on this repository as a sibling checkout: `{:local/root "../clogem-sdk"}`.

## License

© 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com). Licensed under the Apache License, Version 2.0; see LICENSE.
