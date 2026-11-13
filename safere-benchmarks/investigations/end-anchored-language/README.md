# End-anchored language investigation

Controls for issue #1185: universal and restricted prefixes, nonliteral terminal
expressions, assertions, trailing terminators, Unicode, Boolean search, bounds,
and capture consumption. Uses the shared generic runners; no specialized harness
is needed. The original greedy search workload and its input definitions are
copied unchanged from the development corpus.

```bash
./run-java-benchmarks.sh --fastbuild --investigation end-anchored-language \
  CrossEngineScalingBenchmark.run -- \
  -p crossEngineScalingTrial=EndLanguage.greedy.1048576@safere-string
```

The universal-prefix cases test whether the reverse DFA can stop reading a prefix
after proving every continuation accepts. Class and alternation suffixes check
that this depends on the automaton rather than a literal-tail template. Restricted
prefixes and assertions check exclusions; terminal failures and short suffixes
check added dispatch overhead. Bounds and capture consumption check callers that
require more than a Boolean. The UTF-8 matcher case uses `findAllCount` to retain
the matcher API and its complete search sequence. Fresh compilation plus search
controls measure per-pattern state-analysis setup costs.

Use identical definitions on the baseline and candidate. Run measurements
sequentially with the default scan provider; use `--long` for confirmation.
Keep raw measurements and profiles local.
