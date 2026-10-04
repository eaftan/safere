# OSS-Fuzz build adapter

The upstream manifest, `../targets.json`, owns enrollment. The OSS-Fuzz project
build script should delegate to this adapter:

```bash
#!/bin/bash -eu
cd "$SRC/safere"
bash safere-fuzz/oss-fuzz/build.sh
```

This replaces the source-file glob in the proposed
[OSS-Fuzz integration](https://github.com/google/oss-fuzz/pull/16164).
[integration.patch](integration.patch) applies this delegation to the proposed
build script at PR head `70760e290e80525b2eff588ae9049bb77a8d86cd`. Apply it in the
OSS-Fuzz checkout after this upstream adapter is available to its clone. The
patch is prepared locally; it has not been posted or pushed.

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
