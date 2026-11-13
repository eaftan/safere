# Nullable search windows

Controls for issue #1185 opportunity 4. These controls isolate sizing of a
BitState window from a DFA end hint and lazy-loop capture extraction outside
the protocol-link pattern family.
Inputs are materialized before timing. Boolean results are validated by the
runner; `NullableWindowProtocolTest` validates capture-substitution and measured
protocol-link replacement output against the JDK for String and UTF-8 adapters.

`short` and `tiny` cover unchanged setup paths; `hint` and `unicode` enter the
enlarged window; `cap` exceeds the bitmap cap; `priority` exercises competing
alternatives; `miss` exercises rejection; `fallback` has no nullable loop and stays on the ordinary engine path. `lazyCaptures` exercises
the lazy-loop fusion during capture substitution, while `greedyCaptures` covers
the neighboring greedy-loop path. Run both representations
on baseline and candidate with the same declarations and default scan provider:

```sh
./run-java-benchmarks.sh --fastbuild --investigation nullable-search-window \
  CrossEngineBenchmark.run -- \
  -p crossEngineTrial=NullableWindowBenchmark.hint@safere-string
```

Use comma-separated trial IDs to select controls and `--long` for confirmation.
Keep raw focused measurements local.

Run the protocol checks with:

```sh
mvn -pl safere-benchmarks -am -Dbenchmark.investigation=nullable-search-window \
  -Dexec.skip=true -Dtest=NullableWindowProtocolTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
