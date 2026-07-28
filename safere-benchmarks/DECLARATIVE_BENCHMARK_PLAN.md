# Declarative Benchmark Plan

SafeRE's normalized benchmark plan is a strict, versioned object within
`benchmark-data.json`. The initial schema version is `1`. Normalized
declarations are authoritative for materialized inputs and ordinary/scaling
cross-engine workloads, specialized modes, collection, and reporting. The
schema preserves the established benchmark timing boundaries.

The normalized plan has this shape:

```json
{
  "schemaVersion": 1,
  "inputs": [
    {
      "id": "search.random.{size}",
      "axes": {"size": [1024, 10240]},
      "recipe": {
        "kind": "randomChars",
        "alphabet": "abcdefghijklmnopqrstuvwxyz",
        "length": "{size}",
        "seed": 42
      }
    }
  ],
  "workloads": [
    {
      "id": "SearchBenchmark.find.{size}",
      "operation": "find",
      "patterns": ["needle"],
      "inputs": ["search.random.{size}"],
      "axes": {"size": [1024, 10240]},
      "flags": [],
      "requirements": ["find"],
      "inputRepresentations": [
        "javaString",
        "preexistingUtf8",
        "javaStringWithTimedUtf8Conversion"
      ],
      "resultConsumption": "boolean",
      "expected": {"type": "boolean", "value": false},
      "measurement": {
        "mode": "averageTime",
        "timingUnit": "microseconds",
        "constraints": ["prematerializedInput"]
      }
    }
  ]
}
```

Unknown fields and values are errors. Schema changes that are not backward
compatible require a new `schemaVersion`; loaders must reject versions they do
not understand. New enum values may be added within a version only before that
version is used by a released benchmark plan.

## Stable identities and expansion

Input and workload `axes` are ordered maps. Expansion is a deterministic
Cartesian product in declaration order. Axis values are strings, integers,
booleans, or labeled `{"id": "...", "value": ...}` scalars. A labeled value
uses `id` in the stable workload identity and `value` in the expanded pattern.
Every workload axis must appear in the workload ID. This makes the expanded ID
an unambiguous historical result identity.

Axis references use `{axisName}` in IDs, input references, patterns, string
recipe arguments, and scalar integer recipe arguments. Expanded workload IDs
must be unique and must not contain `@`. A Java trial appends the independently
declared engine variant:

```text
<expanded-workload-id>@<engine-variant-id>
```

A materially changed operation, input, result-consumption rule, lifecycle, or
timing boundary requires a new workload ID. Reordering JSON or changing a
display label does not.

## Pattern profiles

All workload patterns use Java regex syntax as their canonical representation.
When another regex dialect needs different syntax for the same semantics,
the pattern definition declares an exact alternate inline:

```json
{
  "patterns": [
    {
      "java": "\\p{script=Latin}+",
      "alternates": {
        "re2": {
          "pattern": "\\p{Latin}+",
          "reason": "RE2 uses bare Unicode script names"
        }
      }
    }
  ]
}
```

An engine adapter selects one syntax-family profile. SafeRE and JDK select
`java`; RE2/J, RE2-FFM, native C++ RE2, and Go select `re2`. If the selected
profile has no entry for a Java pattern, the adapter uses the Java pattern
unchanged.

Alternates are exact reviewed strings. Runners must not rewrite regex syntax
automatically. The required nonblank `reason` records why the alternate is
necessary. The materializer replaces inline definitions with their Java strings
and emits a resolved profile lookup in the generated manifest. Conflicting
alternates for the same Java pattern and profile, malformed profile IDs, blank
fields, and unknown fields are rejected during materialization.
Pattern selection and compilation happen outside timed matching operations;
compile benchmarks select the alternate before starting the timed compilation.

## Bounded input recipes

Recipes describe data and deterministic generation; they cannot invoke Java
classes, scripts, or workload-family code. Version 1 defines:

| Recipe | Purpose |
|---|---|
| `literal` | Exact text |
| `repeat` | Repeat text a fixed count |
| `repeatToLength` | Repeat and truncate to a target UTF-16 length |
| `repeatAtLeastLength` | Repeat through the first unit boundary at or beyond a minimum length |
| `delimitedRepeatToLength` | Repeat text with a deterministic delimiter sequence and truncate |
| `appendInput` | Append a suffix to another declared materialized input |
| `randomChars` | Seeded selection from a UTF-16 alphabet |
| `randomCodePoints` | Seeded selection from declared Unicode code points |
| `surroundToLength` | Prefix, repeated body, and suffix at a target length |
| `suffixToLength` | Repeated prefix followed by a fixed suffix |
| `prefixedRepeatToLength` | Fixed prefix followed by delimited repeated text |
| `sparseMatchToLength` | Periodic match among nonmatching units |
| `centerInSpaces` | Fixed body centered in spaces |
| `scaledCenterInSpaces` | Scaled body centered in spaces |
| `lazyAlternationToLength` | Prefix, central match, and suffix units |
| `periodicAlternationToLength` | Hit and miss units at a fixed interval |
| `optionalRequiredRepeatPattern` | Pathological optional/required literal pattern |

Recipe fields are fixed for each kind. A new shape requires one generic recipe
kind and validation, not a family-specific branch. The central materializer
evaluates these recipes and rejects unknown dependencies and dependency
cycles.

## Workload requirements

A workload describes behavior without naming engines:

- `operation` selects generic semantics.
- `patterns` supports one pattern or a collection for PatternSet-like
  operations.
- `inputs` references materialized input IDs. Compile and analysis operations
  may not require inputs.
- `flags` declares regex compilation flags.
- `requirements` declares engine-neutral API features such as capture text,
  named groups, replacement, matcher state, regions, PatternSet, UTF-8 input,
  or diagnostics.
- `inputRepresentations` declares acceptable timing boundaries, not engines.
- `resultConsumption` controls how the result enters the blackhole.
- `expected` is a typed optional correctness value.

Version 1 operations are grouped below. Names are exact JSON values.

| Group | Operations |
|---|---|
| Matching | `matches`, `find`, `lookingAt`, `findAllCount`, `findAllLengthSum`, `findAllGroupLengthSum`, `matchesCorpus`, `matchesGroupLengthSum`, `findGroupPresent`, `findGroup`, `captureGroups` |
| Replacement and splitting | `replaceFirst`, `replaceAll`, `replaceAllLengthSum`, `appendReplacement`, `manualReplaceAll`, `split`, `splitLengthSum` |
| Compilation and matcher state | `compile`, `compileAndFind`, `findRotatingUtf16`, `compileAndFindRotatingUtf16`, `matcherConstruction`, `matcherResetFind`, `matcherRegionFind`, `findInWindow` |
| Pattern collections | `patternSetCompile`, `patternSetFind`, `patternSetMatches` |
| UTF-8 | `utf8CaptureBounds`, `utf8DecodeFind`, `utf8Replacement` |
| Diagnostics and memory | `analyzePattern`, `cachedAnalysis`, `compileAndAnalyze`, `dfaCacheGrowth`, `diagnosticsFind` |

Operation-specific data uses a strict `arguments` object rather than
workload-family fields. Group-consuming operations use `group` or `groups`;
replacement operations require `replacement`; split operations accept `limit`;
PatternSet operations require `anchor` with `anchored` or `unanchored` and may
declare `patternCount`; flagged compilation uses `flagSet`; rotating UTF-16
operations use `seed` and `count`. Arguments may reference declared axes.

Flags are `caseInsensitive`, `multiline`, `dotAll`, `unicodeCase`,
`comments`, `literal`, and `unicodeCharacterClass`. Engine adapters declare
the exact flag set they support; an unsupported flag is distinct from an
unsupported API feature.

Engine-neutral requirements are `find`, `matches`, `lookingAt`,
`captureText`, `namedGroups`, `replace`, `numberedReplacement`,
`namedReplacement`, `appendReplacement`, `functionalReplacement`, `split`,
`matcherState`, `regions`, `bounds`, `patternSet`, `utf8Input`,
`utf8Replacement`, `diagnostics`, `dfaCache`, `flaggedCompile`,
`javaCharacterClass`, `linearTime`, and `retainedHeap`. Operations and
lifecycle steps derive their intrinsic requirements automatically.
Declarations add requirements only for semantic details that cannot be
inferred, such as the replacement-reference form.

Input representations are `javaString`, `preexistingUtf8`, and
`javaStringWithTimedUtf8Conversion`. Result consumption is `boolean`,
`integer`, `string`, `stringList`, `compiledObject`, or `blackholeObject`.
Typed expectations support the first four forms. A scalar expectation may
reference one axis as its complete value, for example
`{"type": "boolean", "axis": "match"}`.

Engine adapters independently declare one representation and a feature set.
The planner joins the two axes. It records unsupported flag, feature, or
representation pairs separately from missing engine adapters and missing
generic operation implementations. Every workload/engine pair is therefore
either scheduled or has a machine-readable exclusion.

A workload that intentionally has no trials must provide a nonblank
`disabledReason`; otherwise an empty trial matrix is an error.

## Matcher lifecycle

Stateful operations declare whether a matcher is created for every invocation
or retained:

```json
{
  "matcher": "retained",
  "steps": [
    {"kind": "reset"},
    {"kind": "region", "start": 7, "end": 22},
    {"kind": "transparentBounds", "enabled": true}
  ]
}
```

Supported steps are `reset`, `region`, `transparentBounds`, and
`anchoringBounds`. Lifecycle is part of workload identity and determines
whether construction and mutation happen inside or outside the timed task.

## Measurement policy

The version 1 modes are:

| Mode | Purpose |
|---|---|
| `averageTime` | Ordinary and scaling time measurements |
| `compileOnly` | Compilation is the timed operation |
| `singleShotColdStart` | Fresh-process single-shot measurements |
| `retainedMemory` | Retained-size measurement |
| `subprocessMemory` | Process-level memory measurement |

Generic runner selection and the boundary of each unusual mode are documented in
[`SPECIALIZED_MEASUREMENT_MODES.md`](SPECIALIZED_MEASUREMENT_MODES.md).

Timing units are `nanoseconds`, `microseconds`, `milliseconds`, or `bytes`.
Constraints are `noFork`, `freshProcessPerInvocation`, `retainState`,
`prematerializedInput`, and `allocationProfile`. Incompatible combinations,
such as `noFork` with a fresh process per invocation, are rejected.

Pathological workloads use `averageTime` plus `noFork`; the scheduler must not
infer that policy from a class or workload name.

## Coverage of the existing suite

The schema uses generic concepts for every current family:

| Existing benchmarks | Schema representation |
|---|---|
| Regex, Application, RealWorld, HTTP, search scaling, fanout | Ordinary operations, axes, recipes, and average-time modes |
| Capture scaling | Capture operations and `captureText` requirement |
| Compile and Unicode compile | `compile` plus compile-only or cold-start mode |
| Replace, Issue488, scrubber | Replace operations, flags, recipes, and expected strings |
| Issue481 | Find, split, replace, and scaling axes |
| Matcher API | Looking-at/region/reset operations with declared lifecycle |
| Pathological | Generated pattern/text recipes and `noFork` |
| PatternSet | Pattern collections and `patternSet` requirement |
| UTF-8 matching/replacement | UTF-8 operations and explicit representations |
| Diagnostics and analysis | Diagnostics/analysis operations and requirements |
| Memory and cold start | Retained-memory, subprocess-memory, and cold-start modes |

The generic runners execute these declarations. Workload-specific Java
benchmark classes and data adapters have been removed.
