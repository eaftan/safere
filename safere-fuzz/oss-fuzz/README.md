# OSS-Fuzz build adapter

The upstream manifest, `../targets.json`, owns enrollment. The OSS-Fuzz project
build script should delegate to this adapter:

```bash
#!/bin/bash -eu
cd "$SRC/safere"
bash safere-fuzz/oss-fuzz/build.sh
```

The initial [OSS-Fuzz integration](https://github.com/google/oss-fuzz/pull/16164)
has landed. The [adapter follow-up](https://github.com/google/oss-fuzz/pull/16238)
uses this adapter when it is present and retains an explicit target allowlist for
older SafeRE checkouts. Use the current upstream integration rather than applying
a patch against the original proposal.

Keep the container's JDK setup in OSS-Fuzz. The container also needs Python 3,
Maven, rsync, and the Jazzer JVM tooling already used by the integration.

The adapter packages the library and test dependencies, copies the runtime JDK,
and emits only explicitly enrolled launchers. `safere-targets.txt` records that
selection. Broad differential classes may exist in the jar but receive no
launcher. Missing Jazzer dependencies fail the build instead of being ignored.

Use a fresh OSS-Fuzz output directory for every build. No regex dictionary is
attached to the initial structured generators: their bytes encode choices, not
raw regex tokens. Broad raw-syntax robustness mutation remains available.

Validate with the OSS-Fuzz helper's build and check commands in an OSS-Fuzz
checkout with Docker available. Local manifest/exporter tests validate selection
and archive names, but do not substitute for that container check.
