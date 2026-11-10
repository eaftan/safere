# Historical producer-selection prototypes

These patches reconstruct the measured experimental `Matcher.java` implementations
from library base `524eaa4cfe09a4f40302333c436d4c19e2d8f0e8`.
Use a separate clean checkout for each comparison, with identical benchmark
sources and data. Apply one patch with `git apply`; the investigation runner never
changes library sources automatically.

| Patch | Producer-history owner and behavior |
|---|---|
| `dfa1073.patch` | Preserve anchored preference; unanchored `find()` uses DFA first, following the captured #1073 gate. |
| `localSticky.patch` | Own inner-capture reads set a Matcher-local sticky preference; no Pattern seeding or aging. |
| `local4.patch` | Own reads renew a local lease to four; retiring a successful unused result decrements it once. |
| `hybrid4.patch` | Seed once from Pattern at the first producer decision, then use a local lease of four. |
| `hybrid16.patch` | Same seeding and aging with a lease of sixteen. |

Every variant preserves shared Pattern demand recording for already-required
exact fallback passes. Operation-scoped replacement requirements and result capture
availability remain separate. Failed searches do not age a successful-result
lease; consequently a seeded rejection-only cursor can retain its preference.
Local history survives reset and input/region changes and starts again on pattern
change. Reads renew it immediately. The experiment does not choose a new production
threshold or change the existing 512-native-unit eligibility limit or search budget.

These are investigation prototypes, not production implementations with complete
transition coverage. The historical data exercises `find()`, consumption changes,
fresh/retained cursors, and shared Patterns. Replacements, delayed snapshots, and
all region/bounds/pattern transitions were not measured for every prototype. The
separate speculative-window retry/backoff work in #966 is untouched.
