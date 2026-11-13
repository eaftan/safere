# Boolean existence searches

Controls for issue #1185, opportunity 3. Patterns with greedy tails exercise early
acceptance, late acceptance, rejection, line boundaries, Unicode, and a second
field-delimiter family. Bounds, capture, and replacement consumers measure the
cost of resolving a deferred match. Tiny inputs check dispatch overhead.

Run with the default provider and standard settings:

```bash
./run-java-benchmarks.sh --fastbuild --investigation boolean-existence \
  CrossEngineBenchmark.run -- \
  -p crossEngineTrial=BooleanExistence.early.find@safere-string
```

Apply identical declarations to the baseline before comparing revisions. Raw
focused measurements stay local.
