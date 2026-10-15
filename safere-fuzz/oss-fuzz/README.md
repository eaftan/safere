# OSS-Fuzz build adapter

The [target manifest](../targets.json) owns enrollment. The OSS-Fuzz project
build script delegates to this adapter:

```bash
#!/bin/bash -eu
cd "$SRC/safere"
exec bash safere-fuzz/oss-fuzz/build.sh
```

The [OSS-Fuzz integration](https://github.com/google/oss-fuzz/blob/master/projects/safere/build.sh)
always invokes this adapter. SafeRE's `targets.json` controls target enrollment,
and a missing adapter fails the build.

Keep the container's JDK setup in OSS-Fuzz. The container also needs Python 3,
Maven, rsync, and the Jazzer JVM tooling already used by the integration.

The adapter packages the library and test dependencies, copies the runtime JDK,
and emits only explicitly enrolled launchers. `safere-targets.txt` records that
selection. Broad differential classes may exist in the jar but receive no
launcher. Missing Jazzer dependencies fail the build instead of being ignored.

Use a fresh OSS-Fuzz output directory for every build. No regex dictionary is
attached to the structured generators: their bytes encode choices, not raw regex
tokens. Raw-syntax robustness mutation remains available.

## Container validation

Use a current OSS-Fuzz checkout with Docker running and accessible to your account.
From that checkout, build the local SafeRE source and check all emitted launchers:

```bash
cd /path/to/oss-fuzz
python3 infra/helper.py build_fuzzers --clean safere /path/to/safere
python3 infra/helper.py check_build safere
```

The source path selects the SafeRE checkout under review rather than the revision
cloned into the builder image. Maven writes build outputs into that checkout; use
a separate copy or worktree if also running host builds. `--clean` removes prior
OSS-Fuzz outputs and work files for this project.

`check_build` checks discovery and startup, including launchers relocated outside
`/out`, but skips seed-corpus replay. Replay seeds separately for every enrolled
target:

```bash
while IFS= read -r target; do
  python3 infra/helper.py run_fuzzer safere "$target" -- -runs=0 || exit 1
done < build/out/safere/safere-targets.txt
```

The runner extracts each target's seed archive, including nested method
directories, and executes the seeds without further mutation. Check that its log
reports the packaged corpus files. Local manifest/exporter tests validate
selection and archive names, but do not substitute for these container checks.

Review input decoding and target identity before migrating hosted corpora. A new
target or changed input format requires review of whether existing bytes retain
their meaning. The ordinary adapter does not enroll `WorkBoundFuzzer`; hosting it
requires a separate instrumented build.
