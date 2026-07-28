# Cross-runtime benchmark toolchains

The benchmark wrappers build their harnesses from source and materialize the
shared corpus automatically. Run them from the repository root. Benchmark
dependencies are downloaded into normal build caches; no C++, Go, or .NET
regex dependency needs to be installed separately.

## Java engines

SafeRE, `java.util.regex`, RE2/J, and RE2-FFM run in the JMH harness.

- JDK 21 or newer. SafeRE targets Java 21 and is routinely tested with newer
  OpenJDK releases. Install an OpenJDK build using the
  [OpenJDK installation guide](https://openjdk.org/install/).
- Apache Maven 3.9 or newer. See
  [Installing Apache Maven](https://maven.apache.org/install.html).
- RE2-FFM additionally needs a C++17 compiler because Maven builds its native
  RE2 bridge. GCC and Clang installation resources are listed in the C++ RE2
  section below.

Verify the tools with:

```bash
java -version
mvn -version
```

Run the complete declared Java plan or its smoke test with:

```bash
./run-java-benchmarks.sh --declared
./run-java-benchmarks.sh --smoke --declared
```

## C++ RE2

The native RE2 harness requires:

- [CMake 3.14 or newer](https://cmake.org/download/).
- A C++17 compiler, such as [GCC](https://gcc.gnu.org/install/) or
  [Clang](https://clang.llvm.org/get_started.html).
- Git and network access on the first build. CMake fetches the pinned RE2,
  Abseil, and nlohmann/json sources.

Verify the tools with:

```bash
cmake --version
c++ --version
git --version
```

Run the normal suite or smoke test with:

```bash
./run-cpp-benchmarks.sh
./run-cpp-benchmarks.sh --smoke
```

## Go `regexp`

The Go harness requires
[Go 1.21 or newer](https://go.dev/doc/install). It uses only the standard
library and the checked-in module.

Verify and run it with:

```bash
go version
./run-go-benchmarks.sh
./run-go-benchmarks.sh --smoke
```

## .NET non-backtracking

The .NET harness targets `net8.0` and requires the
[.NET SDK 8 or newer](https://learn.microsoft.com/dotnet/core/install/).
The runtime-only package is insufficient because the wrapper builds the
harness. It uses `System.Text.RegularExpressions` from the standard library
with `RegexOptions.NonBacktracking`.

Verify and run it with:

```bash
dotnet --info
./run-dotnet-benchmarks.sh
./run-dotnet-benchmarks.sh --smoke
```

The .NET runner consumes every resolved workload and dynamically checks
whether its patterns compile in non-backtracking mode. To inspect the exact
capability boundary:

```bash
./run-dotnet-benchmarks.sh --list
./run-dotnet-benchmarks.sh --list-exclusions
```

The exclusion stream is JSON Lines. It distinguishes missing .NET APIs (for
example SafeRE diagnostics or retained mutable matcher state), byte-oriented
UTF-8 workloads, unsupported measurement modes, and regex-dialect gaps.

## Collection smoke test

The collection-level smoke test runs a representative workload through every
selected Java engine. Add `--cross-language` to include the three native
harnesses:

```bash
./collect-benchmark-results.sh --smoke
./collect-benchmark-results.sh --smoke --cross-language
```

The harness-level `--smoke` commands above are broader: each exercises every
workload currently selected by that harness once, without producing
statistically meaningful benchmark measurements.
