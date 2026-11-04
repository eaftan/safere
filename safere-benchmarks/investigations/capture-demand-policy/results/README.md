# Capture-demand policy investigation: results and assessment

The measurements support retaining the production policy for now. They expose a
large cost from sharing capture history with matchers that reject their inputs,
but every alternative has material regressions in successful extraction or shared
use. Matcher-local sticky history obtains nearly the same rejection benefit as
DFA-first without aging, so this study does not establish a useful forgetting
window. It identifies history ownership as a promising question for further work.

This is a focused investigation for [#1075](https://github.com/eaftan/safere/issues/1075).
Its results are excluded from release collections and recurring benchmark reports.
The plain-text [summary.txt](summary.txt) remains available; this document explains the
policies, comparisons, and limits of the evidence.

## Question and baseline

SafeRE can find match boundaries with a DFA and defer extracting inner captures
until a caller requests them. For short inputs, it can instead use a
capture-capable engine first, avoiding a separate extraction pass when a search
succeeds and the caller reads captures. That choice can cost more when a candidate
fails and the DFA could reject it cheaply.

The measured `main` baseline records a sticky capture-use hint on the compiled
`Pattern`. Once one matcher reads an inner capture, other matchers sharing that
Pattern inherit the hint across inputs and threads. The same history has two
uses: collecting captures during an exact fallback that is already necessary,
and selecting a capture-capable producer before DFA rejection. This study varies
the second use while preserving the first.

Here, **main means the measured baseline revision**
[`524eaa4cfe09a4f40302333c436d4c19e2d8f0e8`](https://github.com/eaftan/safere/commit/524eaa4cfe09a4f40302333c436d4c19e2d8f0e8),
whose commit time is `2026-11-03T03:54:42Z`. The comparisons do not measure later
changes to main. The original benchmark overlay is preserved at
[`2c562a0a6c369ce69f07893624c4d18b5e53b3d6`](https://github.com/eaftan/safere/commit/2c562a0a6c369ce69f07893624c4d18b5e53b3d6)
and in [historical-harness.patch](historical-harness.patch).
No experimental producer policy is applied by the investigation runner or adopted
by this PR.

## Policies tested

| Policy / artifact name | Who owns the producer hint? | Learning, forgetting, and recovery |
|---|---|---|
| **main** / `baseline` | Shared Pattern | An inner-capture read sets a sticky bit. Other matchers inherit it; unused results and failed searches do not clear it. |
| **DFA-first** / `dfa1073` | Shared history remains available to exact fallback | Remove the history-driven pre-DFA capture attempt for unanchored `find()`, following the captured #1073 gate. Preserve the anchored preference and other fast paths. |
| **Local sticky** / `localSticky` | Individual Matcher | Start cold; an inner-capture read on that matcher sets its sticky producer hint. No Pattern seeding and no aging. |
| **Local lease** / `local4` | Individual Matcher | Start at zero. A capture read renews a lease to four. Retiring a successful result whose inner captures were unused decrements it once; zero disables the preference until another read. |
| **Seeded lease, four** / `hybrid4` | Individual Matcher, initially seeded from Pattern | At the first producer decision, seed four if the shared hint is set, otherwise zero. Thereafter use the local lease rules. |
| **Seeded lease, sixteen** / `hybrid16` | Individual Matcher, initially seeded from Pattern | Same as `hybrid4`, with a lease of sixteen. |

The [prototype patches](../policies/README.md) specify the implementations. For the
local policies, ordinary reset, input changes, and region changes retain local
history, subject to retiring the current result; changing Pattern restarts it,
including reseeding for the hybrid variants.
A lease counts a result as unused only when that successful result is retired by
a later attempt or clearing operation, rather than when it is first produced.
Reading captures renews the lease immediately. Failed searches do not consume it.
Consequently a seeded matcher that only rejects inputs can retain its inherited
preference indefinitely.

All variants continue recording shared Pattern demand for already-required exact
fallbacks. They keep operation-scoped replacement requirements and current-result
capture availability separate from producer history. None changes the existing
512-native-unit eligibility limit, BitState capacity checks, or search budget.
These are diagnostic prototypes; complete public-API transition coverage was not
established for each one.

## Workloads and measurement

The matrix is constructed data using the regexes from
`ApplicationBenchmark.stackTraceExtract` and
`Issue481ScalingBenchmark.schemeExtract.128`. The fixtures contain newly constructed
valid/invalid stack frames and custom-scheme links, padded with ASCII prose or
multibyte text. They are not sampled application traffic. The existing four-frame
stack-trace document
and scheme-extraction document provide additional successful-extraction controls.

| Dimension | Protocol |
|---|---|
| Document hit rate | All 16 documents match, or only the first of 16 matches. Each document is scanned through repeated `find()` calls to exhaustion. |
| Capture consumption | `text1`: hash capture text on every hit; `text4`: on every fourth hit; `offset1`: consume native capture bounds; `boolean1`: consume only match existence. |
| Usage changes | `phase1`: consume capture text in phases one and three, with Boolean-only use in phase two. Other modes repeat the same consumption in all phases. |
| Invocation | Three phases × 32 rounds × 16 documents = 1,536 documents. Capture sampling is conditional on a hit, independent of document hit rate. |
| Matcher lifetime | `retained`: one cursor per document slot, reused across invocations; `newPerInvocation`: a new cursor for each document scan, including allocation in timing. One compiled Pattern is shared by the cursors in a case. |
| Representation | String and pre-existing UTF-8 bytes. Input preparation and compilation are outside timing; capture-text materialization, including UTF-8 decoding, is inside timing. Offset-only reads use native offsets. |
| Shared Pattern | Four workers share a Pattern; two consume capture text and two use Boolean-only results, with private retained cursors. |
| Input sizes | Definitions declare 128, 511, 512, 513, and 1,024 UTF-16 units. Standard measurements cover the 128 matrix plus ASCII scheme controls at 511/512/513. Long confirmations add retained multibyte stack/scheme cases at 511/513. The 1,024 definitions were not measured. |

All constructed multibyte text uses BMP characters. Its UTF-8 byte length exceeds
its UTF-16 length: for example, the 511-unit stack and scheme hits occupy 1,148
and 1,159 bytes. The 512-native-unit cutoff therefore applies at different points
for String and UTF-8. Multibyte boundary comparisons cannot be treated as identical
eligibility experiments for the two representations.

The environment is OpenJDK 26.0.2, JMH 1.37, the default scan provider, and a
virtualized i7-11700K host with 16 logical CPUs. Exact details are in
[cpu.txt](cpu.txt) and [java-version.txt](java-version.txt).

| Mode | Forks | Warmup per fork | Measurement per fork | Workers |
|---|---:|---|---|---:|
| Standard | 2 | 2 × 500 ms | 5 × 500 ms | 1 |
| Long confirmation | 2 | 3 × 1 s | 5 × 1 s | 1 |
| Shared Pattern, standard | 2 | 2 × 500 ms | 5 × 500 ms | 4 |

Runs were sequential under the global JMH lock. Only corrected measurements with
matching validation and measurement cursor lifetimes are included. Diagnostic
profiles are separate from unprofiled timing results. In timing tables, `±` is the
JMH score error for its reported 99.9% confidence interval, scaled with the score.
Ratios below one mean less time than main; ratios above one mean more time.

## Comparison across all policies

The following standard-mode aggregates use **candidate time / main time**,
computed as geometric means with equal weight per exact row. Each 128-unit
retained row has eight cases: two regex families × ASCII/multibyte × String/UTF-8.
Fresh ASCII rows have four cases; offset and Boolean controls each have four
retained ASCII cases. Boundary controls are the six retained ASCII scheme cases
at 511/512/513 units across both representations. Capture modes are kept separate.

The baseline, DFA-first, local4, and hybrid4 standard sets each have 70 rows;
hybrid16 has 32, and localSticky has 40. `n` is the actual pairwise membership;
a dash means unmeasured. No partial set is an overall result, and these
standard-mode estimates are not pooled with long confirmations.

| Category | Baseline rows | dfa1073 | local4 | hybrid4 | hybrid16 | localSticky |
|---|---:|---:|---:|---:|---:|---:|
| 128 retained all text1 | 8 | 1.422 (n=8) | 1.348 (n=8) | 1.075 (n=8) | 1.001 (n=8) | 1.218 (n=8) |
| 128 retained all text4 | 8 | 1.257 (n=8) | 1.379 (n=8) | 1.234 (n=8) | – | – |
| 128 retained all phase1 | 8 | 1.348 (n=8) | 1.335 (n=8) | 1.248 (n=8) | 1.213 (n=8) | 1.220 (n=8) |
| 128 retained rare text1 | 8 | 0.073 (n=8) | 0.080 (n=8) | 1.063 (n=8) | 0.970 (n=8) | 0.073 (n=8) |
| 128 retained rare text4 | 8 | 0.073 (n=8) | 0.079 (n=8) | 1.064 (n=8) | – | – |
| 128 retained rare phase1 | 8 | 0.069 (n=8) | 0.082 (n=8) | 1.186 (n=8) | 1.143 (n=8) | 0.069 (n=8) |
| 128 fresh ASCII all text1 | 4 | 1.255 (n=4) | 1.445 (n=4) | 1.142 (n=4) | – | 1.357 (n=4) |
| 128 fresh ASCII rare text1 | 4 | 0.078 (n=4) | 0.081 (n=4) | 1.103 (n=4) | – | 0.072 (n=4) |
| ASCII offset1 controls | 4 | 1.492 (n=4) | 1.298 (n=4) | 1.083 (n=4) | – | – |
| ASCII boolean1 controls | 4 | 0.996 (n=4) | 1.057 (n=4) | 1.093 (n=4) | – | – |
| Boundary controls | 6 | 1.388 (n=6) | 1.492 (n=6) | 0.981 (n=6) | – | – |

Three comparisons matter:

1. **Ownership accounts for much of the rejection gain.** On retained rare-hit
   `text1` cases, localSticky and DFA-first both have a 0.073 ratio to main;
   local4 is 0.080. Because localSticky never forgets, aging is not required for
   this gain. Each rejecting cursor stays cold instead of inheriting the
   successful cursor's capture history.
2. **Successful extraction still pays for alternatives.** On retained all-hit
   `text1`, localSticky takes 1.218× main's time, compared with 1.348× for local4
   and 1.422× for DFA-first. On these same eight rows, localSticky's point estimate
   uses about 10% less time than local4 and 14% less than DFA-first. This is a
   standard-mode comparison; localSticky was not confirmed in long mode.
3. **Pattern seeding preserves the rejection problem.** Hybrid4 and hybrid16
   have rare-hit `text1` ratios of 1.063 and 0.970. Their all-hit ratios are 1.075
   and 1.001, respectively. The longer lease preserves successful-extraction
   performance here, but neither seeded variant obtains the unseeded policies'
   rejection gains. Small differences near one do not establish a winner.

The `phase1` cycle includes both the Boolean interval and recovery to capture
reads. Its aggregate does not expose time per phase or the speed of recovery.
Local4 also costs 30% more in offset controls and has a 1.057 point estimate in
Boolean-only controls, where no reads should teach capture demand. These costs
qualify any claim that adding a counter is a broadly beneficial adaptation.

## Long confirmation: successful extraction versus rejection

Times below are **µs/document**, including the document scans that produce no
match. All synthetic rows use 128 ASCII units, retained cursors, and `text1`.
Baseline, DFA-first, and local4 were measured in long mode on identical membership.

| Workload | main | dfa1073 | local4 |
|---|---:|---:|---:|
| stack, all match, String | 0.7277 ± 0.1106 | 1.3758 ± 0.1940 | 0.6514 ± 0.0170 |
| stack, all match, UTF-8 | 2.8400 ± 0.0991 | 1.4351 ± 0.1432 | 2.7446 ± 0.0382 |
| stack, 1/16 match, String | 1.5665 ± 0.0391 | 0.4575 ± 0.0180 | 0.4433 ± 0.0443 |
| stack, 1/16 match, UTF-8 | 4.4694 ± 0.0226 | 0.4667 ± 0.0381 | 0.6057 ± 0.1129 |
| scheme, all match, String | 0.3712 ± 0.0113 | 0.6462 ± 0.0240 | 0.5947 ± 0.0289 |
| scheme, all match, UTF-8 | 0.3897 ± 0.0043 | 0.7185 ± 0.0630 | 0.5546 ± 0.0092 |
| scheme, 1/16 match, String | 5.7401 ± 0.5865 | 0.1357 ± 0.0041 | 0.1327 ± 0.0100 |
| scheme, 1/16 match, UTF-8 | 6.3260 ± 1.1213 | 0.1498 ± 0.0046 | 0.1435 ± 0.0080 |

For String scheme extraction, DFA-first reduces rare-hit time from 5.7401 to
0.1357 µs/document, about **42× faster**, while increasing all-hit extraction
from 0.3712 to 0.6462 µs/document, **74% more time**. Local4 has a similar rejection
benefit, but still takes **60% more time** than main for all-hit extraction.

The same direction holds for UTF-8 scheme extraction: both alternatives gain
about 42–44× on rare hits while costing 84% more time for DFA-first and 42% more
for local4 on all-hit extraction. String stack extraction regresses under
DFA-first, while UTF-8 stack extraction improves. Local4's small apparent
advantage on all-hit String stack input has overlapping confidence intervals
with main and does not establish an improvement there.

The existing application-document controls show that the successful-extraction
cost is also present outside the newly constructed matrix. These times are
**µs/document**; the stack rows use long mode, and the scheme rows use standard
mode. They are separate comparisons, not one combined aggregate.

| Existing document | main | dfa1073 | local4 |
|---|---:|---:|---:|
| Four-frame stack trace (long), String | 2.5310 ± 0.1306 | 4.1818 ± 0.0624 | 2.9355 ± 0.0936 |
| Four-frame stack trace (long), UTF-8 | 5.7981 ± 0.1169 | 4.3212 ± 0.1096 | 4.9112 ± 0.2202 |
| Scheme document (standard), String | 0.7049 ± 0.0493 | 1.3522 ± 0.1473 | 1.2287 ± 0.0650 |
| Scheme document (standard), UTF-8 | 0.7214 ± 0.0530 | 1.3703 ± 0.1017 | 1.2503 ± 0.0790 |

DFA-first takes **65% more time** for the String stack document and **25% less**
for UTF-8. Local4 takes 16% more and 15% less, respectively. On the existing scheme
document, DFA-first takes roughly 90–92% more time and local4 73–74% more.
The representation-dependent stack result prevents a general claim that
capture-first or DFA-first is always preferable for successful extraction.

Fresh-cursor and boundary confirmations are preserved in [summary.txt](summary.txt)
and the raw long-mode files. They do not provide evidence for a new threshold.
In particular, String scheme results at 513 units largely converge once the
existing small-input preference is ineligible. UTF-8 multibyte 511/513-unit inputs
are already above the byte cutoff. The study does not isolate the best cutoff or
justify moving it.

## Shared Pattern across workers

These standard-mode ratios are **candidate time / main time**. Each row uses
128 ASCII units, all-hit documents, retained cursors, and four workers: two
capture-text readers and two Boolean-only consumers. JMH reports a worker
average; these numbers do not separate reader and Boolean-role latency.

| Workload | dfa1073 | local4 | hybrid4 | hybrid16 | localSticky |
|---|---:|---:|---:|---:|---:|
| stack, String | 1.537 | 1.103 | 1.027 | 0.983 | 1.054 |
| stack, UTF-8 | 0.402 | 1.241 | 0.674 | 0.670 | 0.690 |
| scheme, String | 1.561 | 1.820 | 1.395 | 1.364 | 1.391 |
| scheme, UTF-8 | 1.584 | 1.770 | 1.343 | 1.374 | 1.440 |

No alternative improves every row. UTF-8 stack input benefits under several
policies, while String stack is close to main for the seeded leases and most
alternatives regress both scheme rows. Local4 has the largest scheme cost here,
at 77–82% more time than main. Matcher-local history therefore does not by itself
establish a concurrency benefit. All prototypes still record shared fallback
history, so this experiment also does not isolate the cost of the shared volatile
hint or prove that removing shared bookkeeping would help.

## Interpretation and assessment

The source changes and [sampled profiles](profile-summary.txt) support a specific
mechanism: the baseline rare-hit String scheme workload spends most sampled
execution in BitState, while DFA-first shifts the dominant work into the DFA.
This is consistent with avoiding a costly capture-capable attempt on rejecting
candidates. The localSticky control strengthens the ownership explanation:
rejecting per-input matchers never acquire a producer hint, even though their
Pattern records capture use by another matcher.

The local4 prototype renews its lease on capture reads, yet retains substantial
successful-extraction costs in some cases. The evidence does not explain the
entire residual cost. It would be premature to attribute it all to decrementing
a counter, cache changes, or JIT decisions. Likewise, the small hybrid4/hybrid16
differences do not identify an optimal lease length.

**Recommendation: retain the existing production policy and preserve these
experiments as investigation evidence.** The practical finding is that a shared
capture-use hint can be useful for an exact fallback without being a reliable
predictor of whether speculative capture-first search will pay off. Local
ownership is worth further study, but the measured extraction and shared-use
regressions rule out selecting any tested prototype as a demonstrated general
replacement.

The main limits and next questions are:

- The input mix is synthetic and deliberately structured. In rare-hit cases the
  successful document is first, warming shared history before the rejecting
  cursors run. Results describe that protocol, not a production workload
  distribution. The all-hit/rare-hit gains cannot be averaged into a deployment
  benefit without a representative mix.
- A retained cursor is tied to one document slot. A single matcher rotating
  through successful and failed inputs via `reset(newInput)` could retain local
  history across both; this study does not measure that ownership pattern.
- Lease aging counts successful unused results, so repeated rejections do not
  make an inherited preference expire. Other forgetting rules or observed-cost
  backoff remain open questions; speculative-window retry/backoff is also
  tracked separately in [#966](https://github.com/eaftan/safere/issues/966).
- Neither per-phase latency nor all public API transitions were measured.
  Delayed reads, snapshots, replacements, bounds, regions, and Pattern changes
  need invariant and work-counter coverage before adopting a production policy.
- Hybrid16 and localSticky have partial standard-mode coverage and no long-mode
  confirmation. Close point estimates and noisy rows should not select a policy.
  Broad application and external-suite comparisons were not collected here.

## Evidence and reproduction

Raw JMH JSON and logs are authoritative. The files preserve these comparisons:

| Files | Content |
|---|---|
| `<policy>.json` / `.log` | Standard matrix; exact selections are in `<policy>-trials.txt`. |
| `<policy>-shared.json` / `.log` | Four shared-Pattern scenarios for each policy. |
| `<policy>-long.json` / `.log` | Long confirmations for baseline, DFA-first, and local4; selections in [confirmation-trials.txt](confirmation-trials.txt). |
| `<policy>-document.json` / `.log` | Existing scheme-document controls, in µs/document. |
| [summary.txt](summary.txt), [summarize.py](summarize.py) | Derived comparisons and the script that regenerates them. |
| [profile-summary.txt](profile-summary.txt) | Diagnostic sampled-method summaries; full JFR captures remain local. |
| [historical-commands.txt](historical-commands.txt) | Exact original selections, wrapper settings, and diagnostic commands. |
| [provenance.json](provenance.json), [SHA256SUMS](SHA256SUMS) | Original source identities and checksums of preserved evidence. |

The sibling [policies](../policies/README.md) patches reconstruct the alternatives
against the pinned main baseline. `provenance.json` records full-file hashes of
those Matcher sources. Its other hashes identify the original local harness
capture; the complete tracked overlay is in `historical-harness.patch`. The
`dfa` provenance entry is an earlier diagnostic prototype excluded from the
comparisons above; the measured DFA-first policy is `dfa1073`.

To reproduce the historical source, start from the pinned main baseline, apply
`historical-harness.patch`, then one policy patch. Use separate clean snapshots
with identical harnesses and the recorded wrapper selections. The command
transcript retains the original temporary paths and local source-selection
controller. Adjust paths for the new checkout and fresh output directories;
that controller is not a replay tool. The preserved patches supply the policy
implementations.

The current [opt-in harness](../README.md) reorganizes the original protocol and
uses a separate consumption parameter. For example, historical ID
`CapturePolicyBenchmark.stack.ascii.128.all.text1.retained@safere-string`
corresponds to `captureTrial=CapturePolicyBenchmark.stack.ascii.128.all.retained@safere-string`
and `consumption=text1`. Migration checks preserve cycle checksums, but the
layout and call structure changed. **The historical timings above were measured
with the original harness, before that reorganization.**
