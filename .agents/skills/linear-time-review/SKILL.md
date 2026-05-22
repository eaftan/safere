---
name: linear-time-review
description: Review SafeRE source files to assess whether the linear-time regex matching guarantee still holds. Use when asked to audit the whole project, a module, or a change for risks such as backtracking, repeated rescans, unbounded recursion, cache-state blowups, or parser acceptance of non-linear regex features.
---

# Linear-Time Review

## Goal

Audit SafeRE for ways a pattern/input pair could violate the intended linear-time matching
guarantee. The output is a prioritized list of potential problems, with file references and the
reason each item may threaten linear time. Do not fix issues unless the user explicitly asks.

## Workflow

1. Establish the work surface.
   - Check `git status --short`.
   - List all main source files with:
     `find safere/src/main/java -type f | sort`
   - Treat that list as the review checklist.
   - If reviewing a branch or PR, also inspect changed files, but do not limit the audit to only
     changed files unless the user asked for a scoped review.

2. Classify files before deep review.
   - Review every file that can affect parsing, simplification, compilation, engine selection,
     execution, capture extraction, caches, search bounds, Unicode/class membership, or public API
     paths that call matching.
   - You may skip source files that are irrelevant to linear time, such as pure enums, constants,
     module descriptors, or static data tables with no algorithmic behavior.
   - Record every skipped file with a one-line reason. If unsure whether a file matters, review it.

3. Review relevant files one by one.
   - Read each file directly; do not rely only on names or memory.
   - Maintain a checklist with statuses: pending, reviewing, reviewed, skipped.
   - For each reviewed file, note either "no linear-time concern found" or the specific concern.
   - Follow call paths far enough to decide whether an apparent risk is real, but keep the audit
     organized by the original file list.

4. Look for linear-time failure modes.
   - Backtracking or path enumeration over alternatives, repetitions, or captures.
   - Nested loops where one dimension is input length and another can repeatedly revisit the same
     pattern states or input positions.
   - Retry loops that restart matching from many candidate positions without monotonic progress or
     prefix acceleration.
   - Cache keys that omit dimensions affecting DFA/NFA behavior, causing recomputation or wrong
     reuse across modes.
   - Cache eviction or fallback behavior that can make the same input/state work repeat
     superlinearly.
   - Recursive regex-tree or program traversal that can overflow or revisit subtrees
     superlinearly; SafeRE should prefer iterative walkers for deep regexes.
   - Parser acceptance of regex features that require backtracking or non-regular semantics:
     backreferences, lookahead/lookbehind, possessive quantifiers, and unsupported constructs.
   - Unicode and character-class operations that scan large tables per input character when a
     cheaper bounded or logarithmic structure is expected.
   - Capture extraction paths that rerun full searches, repeatedly replay prefixes, or perform
     per-capture/per-position matching in ways that scale beyond linear in input for fixed pattern.
   - Public API methods such as `find`, `matches`, replacement, split, regions, and pattern sets
     that may call the engine repeatedly without consuming input or proving progress.
   - JDK compatibility repairs that happen after engine execution by repeatedly invoking matchers,
     replaying prefixes, rescanning unbounded context from many positions, or otherwise nesting
     input-position work inside retry loops.

5. Review JDK-compatibility pressure points explicitly.
   - UTF-16 search positions: JDK `Matcher` positions are `char` indices, so compatibility may
     expose starts inside surrogate pairs. Verify these positions are represented in engine state
     or handled by a bounded linear pass, not by retrying a matcher from every candidate index.
   - Grapheme constructs: `\X` and `\b{g}` require Unicode boundary context. Verify regional
     indicator parity, emoji/ZWJ context, and other boundary state are computed incrementally or
     cached per input instead of scanning backward from each boundary check.
   - Region and bounds semantics: `region()`, transparent bounds, anchoring bounds, `^`, `$`,
     `\A`, `\G`, word boundaries, `hitEnd()`, and `requireEnd()` depend on context around the
     current search range. Verify that context is an explicit input to empty-width checks and
     engine state rather than a post-hoc correction.
   - Capture compatibility: JDK-compatible captures, especially quantified captures and
     alternations, must not be recovered by per-capture or per-position matcher reruns. A bounded
     extraction pass is acceptable; retry loops over captures or positions are not.
   - Fallback engine selection: OnePass, DFA, BitState, and NFA fallbacks should compose as a
     bounded number of passes over the relevant range. Treat repeated fallback invocations over
     growing or overlapping ranges as high risk.
   - Compatibility boundary: if observed JDK behavior appears to require nested matching over input
     positions, report it as a compatibility boundary. Either identify an engine-native linear
     formulation or recommend documenting an intentional divergence under SafeRE's linear-time
     guarantee.

6. Calibrate findings.
   - Distinguish proven issues, plausible risks, and false alarms.
   - For potential problems, describe the adversarial pattern/input shape that would expose the
     risk.
   - If a local micro-test, unit test, or benchmark would cheaply validate a concern, mention it.
     Do not add tests or edit code unless asked.
   - Do not mark an intentional bounded cost as a problem. Examples: work proportional to compiled
     program size, Unicode table setup at compile time, or a single forward scan of input.

7. Report results.
   - Start with findings ordered by severity. Use file and line references.
   - For each finding include: risk, why it may break linear time, suspected trigger, and confidence
     level.
   - Include a "Skipped Files" section with reasons.
   - Include a "Reviewed Files With No Findings" section, grouped compactly.
   - If no concerns are found, say so clearly and mention residual risk, such as paths not exercised
     by tests or areas that need benchmarks to confirm constants rather than asymptotics.

## SafeRE-Specific Hot Spots

Prioritize these classes when the full list is large:

- `Parser`, `Regexp`, `Simplifier`, `Compiler`, `Prog`, and `Inst`
- `Matcher`, `Pattern`, and `PatternSet`
- `Dfa`, `Nfa`, `BitState`, and `OnePass`
- `CharClass`, `CharClassBuilder`, `UnicodeGroups`, `UnicodeProperties`,
  `UnicodeTables`, and `JavaCharacterClasses`
- Engine-selection and safety classes such as `EnginePath*`, `ResultAuthority`, and
  `SemanticGuard`

## Review Discipline

- Preserve unrelated user changes.
- Use `rg` first for searches.
- Prefer exact code references over broad claims.
- Keep the review grounded in algorithmic behavior, not style.
- Do not describe SafeRE as a clean-room port.
