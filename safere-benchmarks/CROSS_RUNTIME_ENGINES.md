# Cross-runtime benchmark engines

The cross-runtime matrix contains native C++ RE2, PCRE2 JIT, Go `regexp`, Rust
`regex`, and .NET non-backtracking. All runners read materialized inputs,
Java-canonical patterns and replacement templates, and stable workload
identities derived from `benchmark-data.json`. Every runner consumes its rows
from the same versioned materialized execution plan.

## Workload coverage

C++ RE2 and PCRE2 JIT compile the same C++ harness. Unless filters are
supplied, each executable runs every native workload it can support. PCRE2
does not run `PathologicalBenchmark` workloads because their declarations
require `linearTime`, which a backtracking engine cannot provide. It also
omits timed replacement workloads because PCRE2's substitution API performs
interpreted matching rather than JIT matching; reporting those measurements
under the `pcre2_jit` engine ID would be misleading. Filters are forwarded
unchanged to the selected engines.

Go `regexp`, Rust `regex`, and .NET non-backtracking run every entry declared
runnable for that engine. Every harness can list the plan's explicit
exclusions with `--list-exclusions`. Engine-specific syntax and compatibility
are resolved during materialization; unsupported syntax is never inferred or
rewritten by a runner.

These measurements are cross-runtime context rather than controlled same-JVM
comparisons. C++, Go, and Rust consume the materialized UTF-8 bytes directly;
.NET decodes them to strings before measurement.

## Shared prerequisites

Every native runner first invokes the Java benchmark-input materializer.
Install the following:

| Tool | Requirement | Installation |
|---|---|---|
| JDK | JDK 26, matching `.sdkmanrc` | [Eclipse Temurin installation guide](https://adoptium.net/installation/) |
| Maven | Maven available as `mvn` | [Apache Maven installation guide](https://maven.apache.org/install.html) |
| Bash | Required by the repository runner scripts | Install [GNU Bash](https://www.gnu.org/software/bash/) from the operating system's package source |

The first build requires network access because CMake, Go, and Cargo download
pinned source or module dependencies. No runner installs system packages.

## C++ RE2

The RE2 executable uses the repository's pinned
[RE2](https://github.com/google/re2) and Abseil revisions. CMake fetches and
builds both, so a separate RE2 installation is neither required nor used.

Additional requirements:

| Tool | Requirement | Installation |
|---|---|---|
| CMake | 3.15 or newer | [CMake installation guide](https://cmake.org/install/) |
| C++ compiler | C++17 support | [GCC binary installation options](https://gcc.gnu.org/install/binaries.html) or [Apple Xcode and command-line tools](https://developer.apple.com/xcode/resources/) |

Run every native workload supported by the RE2 harness:

```bash
./run-cpp-benchmarks.sh --engine re2
```

## PCRE2 JIT

The PCRE2 executable uses pinned
[PCRE2 10.47](https://github.com/PCRE2Project/pcre2/releases/tag/pcre2-10.47)
in 8-bit UTF mode. CMake fetches and builds PCRE2 with JIT enabled, so a
separate PCRE2 installation is neither required nor used. The CMake and C++17
requirements are the same as for C++ RE2.

The host must permit allocation of executable memory for JIT code. The runner
rejects any selected pattern that does not produce JIT code; see the
[PCRE2 JIT documentation](https://pcre2project.github.io/pcre2/doc/pcre2jit/)
for platform constraints and behavior.

Run every native workload supported by the PCRE2 adapter:

```bash
./run-cpp-benchmarks.sh --engine pcre2-jit
```

The `linearTime` pathological workload family is intentionally absent from
PCRE2 output. Those patterns can exhaust PCRE2's match limit and do not measure
the declared linear-time behavior. Replacement workloads are also absent
because PCRE2 does not expose a JIT substitution operation. The engine
self-test still checks substitution correctness outside benchmark measurement.

C++ retained-memory rows are explicitly excluded because no portable,
comparable retained-heap measurement is available across supported hosts.

## Go `regexp`

The Go runner uses the standard-library
[`regexp`](https://pkg.go.dev/regexp) engine.

Additional requirement:

| Tool | Requirement | Installation |
|---|---|---|
| Go | 1.21 or newer | [Go installation guide](https://go.dev/doc/install) |

Run every workload supported by the Go harness:

```bash
./run-go-benchmarks.sh
```

## Rust `regex`

The Rust runner uses the [`regex`](https://crates.io/crates/regex) crate and
the exact dependency versions pinned in `safere-benchmarks/rust/Cargo.lock`.

Additional requirement:

| Tool | Requirement | Installation |
|---|---|---|
| Rust | Rust 1.85 with Cargo | [Rust installation guide](https://www.rust-lang.org/tools/install) |

Run every workload supported by the Rust harness:

```bash
./run-rust-benchmarks.sh
```

## .NET non-backtracking

The .NET runner uses
[`RegexOptions.NonBacktracking`](https://learn.microsoft.com/dotnet/api/system.text.regularexpressions.regexoptions)
with `RegexOptions.CultureInvariant`.

Additional requirement:

| Tool | Requirement | Installation |
|---|---|---|
| .NET SDK | .NET SDK 8 or newer | [.NET installation guide](https://learn.microsoft.com/dotnet/core/install/) |

The runtime-only package is insufficient because the wrapper builds the
benchmark harness.

Run every workload supported by the .NET adapter, or inspect its exclusions:

```bash
./run-dotnet-benchmarks.sh
./run-dotnet-benchmarks.sh --list-exclusions
```

## Smoke tests

CI runs matching and, where supported, memory smoke workloads for every
engine. Fast local engine self-tests verify compilation, captures, replacement,
and PCRE2 JIT generation:

```bash
./materialize-benchmark-inputs.sh
cmake -S safere-benchmarks/cpp -B safere-benchmarks/cpp/build \
  -DCMAKE_BUILD_TYPE=Release -Wno-dev
cmake --build safere-benchmarks/cpp/build --parallel
ctest --test-dir safere-benchmarks/cpp/build --output-on-failure

cd safere-benchmarks/go
go test ./...

cd ../rust
cargo test --locked --all-features
```

To smoke-test the actual timed workload path for one engine, supply a narrow
filter:

```bash
./run-cpp-benchmarks.sh --engine re2 RegexBenchmark.literalMatch
./run-cpp-benchmarks.sh --engine pcre2-jit RegexBenchmark.literalMatch
./run-go-benchmarks.sh RegexBenchmark.literalMatch
./run-rust-benchmarks.sh RegexBenchmark.literalMatch
./run-dotnet-benchmarks.sh --smoke
```
