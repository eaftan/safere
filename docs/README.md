# SafeRE documentation

Start with the [project README](../README.md) for installation and a usage example.
Commands in these guides run from the repository root unless stated otherwise.

## Using SafeRE

| Guide | Contents |
|---|---|
| [Installation](INSTALLATION.md) | Releases, snapshots, and source builds |
| [Syntax and compatibility](SYNTAX.md) | Regex syntax, flags, Unicode, and unsupported features |
| [Migration](MIGRATION.md) | Moving from `java.util.regex` and validating behavior |
| [Intentional divergences](../INTENTIONAL_DIVERGENCES.md) | Accepted compatibility boundaries and rationale |
| [UTF-8 matching](UTF8.md) | Input ownership, byte coordinates, replacement, and optional Vector scanning |
| [Multi-pattern matching](PATTERN_SET.md) | Compiling and querying a set of patterns |
| [Diagnostics](DIAGNOSTICS.md) | Static analysis, runtime events, and aggregation |
| [Benchmark report](../BENCHMARKS.md) | Measurements, methodology, and published artifacts |

## Contributing and maintaining

| Guide | Contents |
|---|---|
| [Contributing](../CONTRIBUTING.md) | Getting started and preparing a pull request |
| [Development](DEVELOPMENT.md) | Builds, optional modules, packaging, and external validation |
| [Testing](TESTING.md) | Test organization, oracles, fuzzing, and validation commands |
| [Architecture](ARCHITECTURE.md) | Compilation, execution, input representations, and design rationale |
| [Semantic invariants](INVARIANTS.md) | Capture, engine, parser, and matcher state contracts |
| [Design decisions](DESIGN_DECISIONS.md) | Settled decisions and the alternatives already rejected |
| [Graphemes and regions](GRAPHEME_REGIONS.md) | Consumption, boundary visibility, and Unicode coordinates |
| [Releasing](RELEASING.md) | Maven Central releases and snapshot publication |
| [Performance work](../safere-benchmarks/PERFORMANCE_GUIDE.md) | Profiling, optimization, measurement, and reporting rules |
| [Running benchmarks](../safere-benchmarks/README.md) | Collection, targeted comparisons, and cross-runtime runners |

Module-specific workflows live with their code:
[crosscheck](../safere-crosscheck/README.md),
[fuzzing](../safere-fuzz/README.md),
[exhaustive sweeps](../safere-exhaustive/README.md),
[Unicode generation](../safere-unicode/README.md), and
[Vector benchmarks](../safere-vector-benchmarks/README.md).

## Recorded evidence

Historical measurements describe the revisions and environments they record:

- [Published benchmark artifacts](../benchmark-results/published/)
- [Rebar comparison](../benchmark-results/rebar-2026-09-19/README.md)
- [Benchmark configuration evaluation](../safere-benchmarks/CONFIGURATION_EVALUATION.md)
- [Diagnostics overhead](benchmarks/DIAGNOSTICS.md)
- [Trino UTF-8 integration measurements](benchmarks/TRINO_UTF8.md)
- [Unicode case-equivalence audit](../audits/unicode-case-equivalence/README.md)

## Design history

The design documents that preceded these guides were removed when the
documentation was reorganized. Their last versions record the original
analysis, evidence, and alternatives behind
[design decisions](DESIGN_DECISIONS.md):

- [DESIGN.md](https://github.com/eaftan/safere/blob/30ae754981ab2b3529e8b5214da628230218a6c4/DESIGN.md): pipeline, engine selection, and key decisions
- [Semantic invariants](https://github.com/eaftan/safere/blob/30ae754981ab2b3529e8b5214da628230218a6c4/design/SEMANTIC_INVARIANTS.md), with focused designs for
  [capture semantics](https://github.com/eaftan/safere/blob/30ae754981ab2b3529e8b5214da628230218a6c4/design/CAPTURE_SEMANTICS.md),
  [engine-path equivalence](https://github.com/eaftan/safere/blob/30ae754981ab2b3529e8b5214da628230218a6c4/design/ENGINE_PATH_EQUIVALENCE.md),
  [the matcher state machine](https://github.com/eaftan/safere/blob/30ae754981ab2b3529e8b5214da628230218a6c4/design/MATCHER_STATE_MACHINE.md),
  [parser dialect compatibility](https://github.com/eaftan/safere/blob/30ae754981ab2b3529e8b5214da628230218a6c4/design/PARSER_DIALECT_COMPATIBILITY.md),
  [the crosscheck test oracle](https://github.com/eaftan/safere/blob/30ae754981ab2b3529e8b5214da628230218a6c4/design/CROSSCHECK_TEST_ORACLE.md), and
  [grapheme and region matching](https://github.com/eaftan/safere/blob/30ae754981ab2b3529e8b5214da628230218a6c4/design/GRAPHEME_REGION_MATCHING.md)
- [UTF-8 byte input](https://github.com/eaftan/safere/blob/30ae754981ab2b3529e8b5214da628230218a6c4/design/UTF8_BYTE_INPUT.md)

Keep guides focused on supported behavior and repeatable workflows. Put
implementation details near the code, track proposed work in issues, and keep
dated measurements with their evidence. Update existing guidance when behavior
changes instead of retaining conflicting implementation plans.
