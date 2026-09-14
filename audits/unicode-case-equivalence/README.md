# Unicode case-equivalence audit for #866

The audit defines families as connected components of Unicode 17 default simple
case-fold links (statuses C and S) and JDK 26.0.2 `Character.toUpperCase`,
`toLowerCase`, and `toTitleCase` single-code-point links. It treats every link
as bidirectional. This is the chosen SafeRE matching rule under
`CASE_INSENSITIVE | UNICODE_CASE`, not a claim about the JDK's implementation.
The [Unicode data file](https://www.unicode.org/Public/17.0.0/ucd/CaseFolding.txt)
has SHA-256 `ff8d8fefbf123574205085d6714c36149eb946d717a0c585c27f0f4ef58c4183`.

Run the independent [Java audit](CaseEquivalenceAudit.java) on the same JDK with
SafeRE compiled in `safere/target/classes`:

```sh
curl -fsSL https://www.unicode.org/Public/17.0.0/ucd/CaseFolding.txt -o /tmp/safere-casefold-17.txt
java -Xmx512m --class-path safere/target/classes \
  audits/unicode-case-equivalence/CaseEquivalenceAudit.java \
  /tmp/safere-casefold-17.txt audits/unicode-case-equivalence/recheck
```

The audit examines every ordered pair among all 2,996 code points that
participate in the chosen mappings. For each pair, it tests a literal, singleton
class, singleton range, and their two negated class forms using full matching.
That is 44,880,080 checks per engine. The comparison uses the equivalence
components computed independently from the Unicode file and Java `Character`
API. It covers all mapping participants, while the JUnit suite also covers
other flags, wider ranges, intersections, captures, and search paths. Inputs
outside the mapping participants are not exhaustively paired in this audit.

[Before](before/summary.txt), SafeRE disagreed with the rule on 30 probes,
all in the dotted/dotless I family. [After](after/summary.txt), it disagreed on
none. Across the same probes, the JDK disagreed on 36. The [difference inventory](after/differences.tsv)
shows exactly which source, target, and syntax differed; syntax codes are
`0` literal, `1` singleton class, `2` singleton range, `3` negated singleton
class, and `4` negated singleton range. The [family inventory](after/families.txt)
records all 1,482 nontrivial families. Only one family is broader than Unicode
default simple folding alone: `I`, `i`, U+0130, and U+0131.

The JDK differences include the previously documented range cases (I, Kelvin,
Ohm, Angstrom, and theta) and reciprocal literal differences for U+0390/U+1FD3,
U+03B0/U+1FE3, and U+FB05/U+FB06. The latter pairs have Unicode simple-fold
links; they are observed JDK behavior, not a reason to make SafeRE's matching
relation directional. JDK 26 `Pattern` documents Unicode-aware folding and
[UTS #18 §1.5](https://www.unicode.org/reports/tr18/#Simple_Loose_Matches)
describes simple-fold literal matching and optional, declared class closure.
