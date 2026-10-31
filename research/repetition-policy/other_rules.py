# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see ../../LICENSE).

"""Bounded JDK-only comparisons of the remaining repetition-policy decisions.

Each intervention changes one rule in the existing independent interpreter.
These deliberately small ordered-path enumerations are research tools, not a
production matching algorithm. All subjects are ASCII, so model coordinates
equal the public String matcher's UTF-16 coordinates.
"""
import argparse
import base64
from collections import Counter, defaultdict
import itertools
import json
from pathlib import Path
import subprocess
import sys

from model import A, C, E, G, Interpreter, L, R, S, W, Z, numbered, render


def group_ids(n):
    if n[0] == 'cap':
        return {n[1]} | group_ids(n[2])
    if n[0] in ('seq', 'alt'):
        return set().union(*(group_ids(x) for x in n[1:]))
    if n[0] in ('rep', 'wrap'):
        return group_ids(n[1])
    return set()


class Alternative(Interpreter):
    def __init__(self, ast, text, groups, intervention='selected'):
        super().__init__(ast, text, groups, 'stop')
        self.intervention = intervention

    def walk(self, n, p, c):
        if self.intervention == 'first_capture' and n[0] == 'cap':
            self.tick()
            for q, d in self.walk(n[2], p, c):
                ds = list(d)
                ds[n[1] - 1] = c[n[1] - 1] if c[n[1] - 1] is not None else (p, q)
                yield q, tuple(ds)
        else:
            yield from super().walk(n, p, c)

    def repeat(self, n, p, c, count):
        self.tick()
        _, child, lo, hi, greedy = n
        if self.intervention == 'flat_optionals' and hi is not None:
            # Numbered child nodes are intentionally shared: same source groups.
            copies = [child] * lo + [('rep', child, 0, 1, greedy)] * (hi - lo)
            # Each optional needs the selected interpreter, not this intervention
            # again. Otherwise a single optional would recursively expand itself.
            ordinary = Alternative(('seq', *copies), self.text, self.groups)
            yield from ordinary.walk(ordinary.ast, p, c)
            self.steps += ordinary.steps
            return
        if self.intervention == 'fresh_star' and hi is None and lo == 1:
            # Invoke the required copy first, then a genuinely fresh star.
            for q, d in self.walk(child, p, c):
                yield from self.repeat(('rep', child, 0, None, greedy), q, d, 0)
            return
        if hi is not None and count == hi:
            yield p, c
            return
        if count >= lo and not greedy:
            yield p, c
        incoming = c
        if self.intervention == 'clear_omitted':
            ds = list(c)
            for g in group_ids(child):
                ds[g - 1] = None
            incoming = tuple(ds)
        for q, d in self.walk(child, p, incoming):
            if q == p and self.intervention == 'reject_optional_empty' and count >= lo:
                continue
            stop = q == p and (
                count >= lo if self.intervention == 'optional_phase' else count + 1 >= lo
            )
            if stop:
                yield q, d
            else:
                yield from self.repeat(n, q, d, count + 1)
        if count >= lo and greedy:
            yield p, c

    def at(self, start, full=False, nonempty=False):
        best = None
        for end, captures in self.walk(self.ast, start, (None,) * self.groups):
            if full and end != len(self.text) or nonempty and end == start:
                continue
            result = [[start, end], *[list(g) if g is not None else None for g in captures]]
            if self.intervention != 'longest':
                return result
            if best is None or end > best[0][1]:
                best = result
        return best

    def operations(self):
        if self.intervention != 'retry_nonempty':
            return super().operations()
        history = []
        start = 0
        while start <= len(self.text):
            found = self.search(start)
            if found is None:
                break
            history.append(found)
            a, b = found[0]
            if a == b:
                consuming = self.at(a, nonempty=True)
                if consuming is not None:
                    history.append(consuming)
                    start = consuming[0][1]
                    continue
            start = b + (a == b)
        return {'first': history[0] if history else None,
                'lookingAt': self.at(0), 'matches': self.at(0, True), 'history': history}


def evaluate(raw, text, intervention):
    ast, count = numbered(raw)
    return Alternative(ast, text, count(), intervention).operations()


def self_check():
    a, b = L('a'), L('b')
    checks = [
        (A(a, L('aa')), 'aa', 'longest', [[0, 1]], [[0, 2]]),
        (S(R(A(R(b), a), 1, 2), b), 'abb', 'optional_phase', [[0, 3]], [[0, 2]]),
        (R(G(E), 0, None), '', 'reject_optional_empty', [[0, 0], [0, 0]], [[0, 0], None]),
        (S(R(G(A(a, L('aa'))), 0, 2, False), b), 'aab', 'flat_optionals',
         [[0, 3], [1, 2]], [[0, 3], [0, 2]]),
        (S(R(A(S(Z('begin'), G(E)), a), 1, None), b), 'ab', 'fresh_star',
         [[0, 2], None], [[0, 2], [0, 0]]),
        (R(S(a, R(G(b))), 1, None), 'aba', 'clear_omitted',
         [[0, 3], [1, 2]], [[0, 3], None]),
        (R(G(a), 1, None), 'aa', 'first_capture', [[0, 2], [1, 2]], [[0, 2], [0, 1]]),
    ]
    for raw, text, intervention, chosen, other in checks:
        assert evaluate(raw, text, 'selected')['first'] == chosen, intervention
        assert evaluate(raw, text, intervention)['first'] == other, intervention
    raw = A(E, a)
    assert evaluate(raw, 'a', 'selected')['history'] == [[[0, 0]], [[1, 1]]]
    assert evaluate(raw, 'a', 'retry_nonempty')['history'] == [[[0, 0]], [[0, 1]], [[1, 1]]]
    assert evaluate(A(S(G(a), b), L('ac')), 'ac', 'selected')['first'] == [[0, 2], None]
    assert evaluate(R(G(a), 0, 0), 'a', 'selected')['first'] == [[0, 0], None]


def corpus():
    a, b = L('a'), L('b')
    subjects = sorted({''.join(x) for n in range(5) for x in itertools.product('ab', repeat=n)}
                      | {'c', 'ac', 'bc', 'abc', 'aabc', 'abbc', 'bac', 'aabab', 'aaaaaa',
                         'aabaab', 'aabbaab', 'babaab', 'x', 'xa', 'xab', 'xaab', 'xabb',
                         ' ', ' a', ' ab', ' a ', 'a b', ' y', '  y', ' y y', '12', 'a1b'})
    contexts = {
        'bare': lambda x: x,
        'literal_suffix': lambda x: S(x, b),
        'ordered_suffix': lambda x: S(x, A(L('ab'), b)),
        'end_assertion': lambda x: S(x, Z('end')),
        'ambiguous_prefix': lambda x: S(A(L('ab'), a), x, b),
        'literal_prefix': lambda x: S(L('x'), x),
    }
    nullable = {
        'optional_atom': G(R(a)),
        'empty_first': A(G(E), a),
        'optional_first': A(R(b), G(a)),
        'nullable_concat': G(S(R(a), R(b))),
        'overlap': A(G(S(a, R(b))), R(b)),
        'reluctant_operand': G(S(R(a, 0, 1, False), R(b))),
        'assertion': A(S(Z('begin'), G(E)), a),
        'nested_finite': R(G(a), 0, 2),
        'nested_unbounded': R(G(a), 0, None),
        'zero_width': G(E),
        'boundary': A(G(Z('word')), C('ab')),
    }
    consuming = {
        'literal': G(a),
        'word': G(L('ab')),
        'overlap_short_first': G(A(a, L('aa'))),
        'overlap_long_first': G(A(L('aa'), a)),
        'optional_tail': G(S(a, R(b))),
        'reluctant_tail': G(S(a, R(b, 0, 1, False))),
        'split_groups': A(G(a), G(L('aa'))),
        'nested_count': R(G(a), 1, 2),
    }
    captures = {
        'optional_descendant': G(S(a, R(G(b)))),
        'branch_omission': A(G(a), G(b)),
        'optional_prefix': S(R(G(b)), a),
        'nullable_descendant': G(S(a, R(G(R(b))))),
        'nested_finite_zero_min': R(G(a), 0, 2),
        'nested_finite_positive_min': S(R(G(a), 1, 2), b),
        'nested_reluctant': S(R(G(a), 0, 2, False), b),
        'nested_star': R(G(a), 0, None),
        'empty_or_word': A(G(E), G(a)),
        'captured_optional': G(R(a)),
    }
    entries = []
    seen = set()

    def add(study, family, raw, intervention, context, primary='first', paired=None):
        ast, count = numbered(raw)
        pattern = render(ast)
        pair = (paired if isinstance(paired, str) else render(numbered(paired)[0])) if paired is not None else None
        key = (study, pattern, pair)
        if key in seen:
            return
        seen.add(key)
        entries.append({'id': len(entries), 'study': study, 'family': family,
                        'context': context, 'regex': pattern, 'paired': pair,
                        'ast': ast, 'groups': count(), 'alternative': intervention,
                        'primary': primary})

    for family, body in {**consuming, **{'nullable_' + k: v for k, v in nullable.items()}}.items():
        # Local alternatives, greediness, and reluctance compete with global length.
        for greedy in [True, False]:
            for context, make in contexts.items():
                add('ordered_first', family, make(R(body, 0, 2, greedy)), 'longest', context)
    for study, intervention, bodies, bounds in [
        ('minimum_boundary', 'optional_phase', nullable, [(1, 2), (2, 3), (1, None), (2, None)]),
        ('terminal_empty', 'reject_optional_empty', nullable,
         [(0, 1), (0, 2), (1, 2), (0, None), (1, None), (2, None)]),
        ('optional_order', 'flat_optionals', consuming, [(0, 2), (1, 3), (0, 3), (2, 3)]),
        ('plus_invocation', 'fresh_star', nullable, [(1, None)]),
        ('omitted_capture', 'clear_omitted', captures,
         [(0, 2), (1, 2), (2, 2), (0, None), (1, None)]),
        ('latest_capture', 'first_capture', {**captures, **consuming},
         [(0, 2), (1, 2), (2, 2), (0, None), (1, None)]),
    ]:
        for family, body in bodies.items():
            for lo, hi in bounds:
                for greedy in [True, False]:
                    for context, make in contexts.items():
                        add(study, family, make(R(body, lo, hi, greedy)), intervention, context)
    for family, body in nullable.items():
        for context, make in contexts.items():
            add('empty_search_cursor', family, make(R(body, 0, 2)), 'retry_nonempty',
                context, 'history')
    # Compare pairs, rather than treating exact emulation of JDK as a policy model.
    for family, body in {**nullable, **consuming}.items():
        for lo, hi in [(0, 2), (1, 2), (2, 2), (0, None), (1, None)]:
            for greedy in [True, False]:
                for context, make in contexts.items():
                    raw = make(R(body, lo, hi, greedy))
                    pair = make(R(W(body), lo, hi, greedy))
                    add('wrapper_transparency', family, raw, 'selected', context, paired=pair)
    zero_width = {
        'empty_capture': G(E),
        'two_captures': S(G(E), G(E)),
        'anchor_then_empty': A(G(Z('begin')), G(E)),
        'boundary': G(Z('word')),
        'nested_count': R(G(E), 1, 2),
        'two_anchors': A(G(Z('begin')), G(Z('end'))),
    }
    for family, body in zero_width.items():
        for lo, hi in [(0, 2), (1, 2), (2, 2), (0, None), (1, None)]:
            core = R(body, lo, hi)
            core_regex = render(numbered(core)[0])
            for context, make in contexts.items():
                raw = make(core)
                paired = render(numbered(raw)[0]).replace(core_regex, core_regex + '+', 1)
                add('zero_width_possessive', family, raw, 'selected', context, paired=paired)
    invariant_bodies = {
        'rollback_branch': A(S(G(a), b), L('ac')),
        'rollback_nested': A(S(G(S(a, G(b))), L('c')), L('aba')),
        'rollback_repeat': S(R(G(a), 0, None), a),
        'rollback_alternative': S(A(G(L('ab')), G(a)), b),
        'new_start': A(S(G(a), L('c')), b),
        'new_start_counted_capture': S(R(W(G(a)), 0, 2), b),
        'new_start_counted_word': S(R(W(G(L('ab'))), 0, 2), b),
        'zero_count': S(R(G(a), 0, 0), R(b)),
        'empty_vs_unmatched': A(G(E), G(a)),
        'group_zero': R(G(a), 1, None),
        'required_empty': R(G(E), 2, 2),
        'inner_empty_outer_consumes': R(S(a, R(G(E), 0, None)), 0, None),
        'fresh_nested_invocation': R(S(a, R(G(A(E, b)), 0, None)), 1, None),
        'full_acceptance': A(a, L('aa')),
        'continuation': S(A(G(a), G(L('aa'))), b),
        'reluctance_preserves_branch_order': R(G(A(a, L('aa'))), 1, 2, False),
    }
    for family, body in invariant_bodies.items():
        for context, make in contexts.items():
            add('invariants', family, make(body), 'selected', context)
    return subjects, entries


def category(chosen, other, actual):
    if chosen == other:
        return 'both' if actual == chosen else 'shared_miss'
    return 'selected' if actual == chosen else 'alternative' if actual == other else 'neither'


def first_repeat(node):
    if node[0] == 'rep':
        return node
    if node[0] in ('cap', 'wrap'):
        return first_repeat(node[-1])
    if node[0] in ('seq', 'alt'):
        for child in node[1:]:
            found = first_repeat(child)
            if found is not None:
                return found
    return None


def summarize(root, entries, subjects):
    totals = defaultdict(Counter)
    families = defaultdict(lambda: defaultdict(Counter))
    strata = defaultdict(lambda: defaultdict(Counter))
    examples = defaultdict(lambda: defaultdict(list))
    invariants = Counter()
    by_study = Counter()
    rows = 0
    expected_ids = itertools.product(range(len(entries)), range(len(subjects)))
    with (root / 'jdk.jsonl').open() as actual_file, (root / 'comparisons.jsonl').open('w') as out:
        for line, expected in zip(actual_file, expected_ids, strict=True):
            row = json.loads(line)
            assert (row['pattern'], row['input']) == expected
            entry = entries[row['pattern']]
            text = subjects[row['input']]
            args = entry['ast'], text, entry['groups']
            selected_engine = Alternative(*args)
            selected = selected_engine.operations()
            other = Alternative(*args, entry['alternative']).operations()
            actual = row['actual']
            study = entry['study']
            primary = entry['primary']
            outcome = category(selected[primary], other[primary], actual[primary])
            if study in ('wrapper_transparency', 'zero_width_possessive'):
                paired = row['paired']
                outcome = 'equal' if actual[primary] == paired[primary] else 'different'
                equality = category(selected[primary], selected[primary], paired[primary])
                totals[study]['paired_' + equality] += 1
                whole = lambda vector: None if vector is None else vector[0]
                totals[study]['pair_bounds_differences'] += int(
                    whole(actual[primary]) != whole(paired[primary]))
            # Interventions that only change captures cannot change the selected
            # whole match. Retrying after empty cannot change the first match or
            # anchored operations. Longest selection keeps the earliest start.
            bounds_only = lambda value: None if value is None else value[0]
            for op in ['first', 'lookingAt', 'matches']:
                if entry['alternative'] in ('clear_omitted', 'first_capture'):
                    assert bounds_only(selected[op]) == bounds_only(other[op])
                if entry['alternative'] == 'retry_nonempty':
                    assert selected[op] == other[op]
            if entry['alternative'] == 'longest':
                assert selected['matches'] == other['matches']
                if selected['first'] is not None:
                    assert selected['first'][0][0] == other['first'][0][0]
                    assert selected['first'][0][1] <= other['first'][0][1]
            totals[study][outcome] += 1
            families[study][entry['family']][outcome] += 1
            repeat = first_repeat(entry['ast'])
            if repeat is not None:
                regime = ('unbounded' if repeat[3] is None else 'finite')
                regime += '_greedy' if repeat[4] else '_reluctant'
                strata[study][regime][outcome] += 1
            by_study[study] += 1
            for op in ['first', 'lookingAt', 'matches', 'history']:
                totals[study][op + ':' + category(selected[op], other[op], actual[op])] += 1
            # These checks derive from JDK's actual matches, so policy discrepancies
            # cannot masquerade as replacement/split or observation failures.
            invariants.update(row['checks'])
            saved = {'pattern': row['pattern'], 'input': row['input'], 'outcome': outcome,
                     'selected': selected, 'alternative': other, 'jdk': actual,
                     'paired': row['paired']}
            out.write(json.dumps(saved, separators=(',', ':')) + '\n')
            if len(examples[study][outcome]) < 12:
                examples[study][outcome].append({
                    'family': entry['family'], 'context': entry['context'],
                    'regex': entry['regex'], 'text': text,
                    'selected': selected[primary], 'alternative': other[primary],
                    'jdk': actual[primary], 'paired': None if row['paired'] is None
                    else row['paired'][primary],
                })
            rows += 1
            if rows % 20000 == 0:
                print('Compared', rows, 'pattern/subject pairs', flush=True)
    result = {'runtime': (root / 'java-runtime.txt').read_text().strip(),
              'cases': rows, 'byStudy': by_study, 'summary': dict(totals),
              'families': {k: dict(v) for k, v in families.items()},
              'strata': {k: dict(v) for k, v in strata.items()},
              'apiInvariantFailures': invariants,
              'auxiliary': json.loads((root / 'auxiliary.json').read_text()),
              'examples': dict(examples)}
    (root / 'results.json').write_text(json.dumps(result, indent=2) + '\n')
    observed = {k: v for k, v in result.items() if k != 'examples'}
    (root / 'observed.json').write_text(json.dumps(observed, indent=2) + '\n')
    assert rows == len(entries) * len(subjects)
    assert not any(invariants.values()), invariants
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--java', default='java')
    parser.add_argument('--check-recorded', action='store_true')
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    root = args.output.resolve()
    repository = source.parents[1]
    if root == repository or repository in root.parents:
        parser.error('keep generated evidence outside the checkout')
    root.mkdir(parents=True, exist_ok=True)
    self_check()
    subjects, entries = corpus()
    plan = {'caseCount': len(entries) * len(subjects), 'subjects': subjects,
            'patterns': len(entries), 'studyPatterns': dict(Counter(e['study'] for e in entries)),
            'primary': 'Full first-find capture vector; full find history for empty_search_cursor.',
            'deduplication': 'One unique source pattern and exact subject per study; no added wrapper variants except the dedicated pair study.',
            'interventions': {
                'longest': 'Longest whole match at the earliest start, ordered priority for equal lengths; not a full POSIX submatch model.',
                'optional_phase': 'Empty-stop only after an optional iteration; required copies complete before a fresh optional phase.',
                'reject_optional_empty': 'Discard optional empty outcomes; retain all other selected rules, including required-empty stopping.',
                'flat_optionals': 'Required copies followed by independent optional copies, sharing capture IDs; only nonnullable operands in this study.',
                'fresh_star': 'Required operand followed by fresh star, sharing capture IDs.',
                'clear_omitted': 'Clear descendant capture IDs before each iteration, keeping rollback.',
                'first_capture': 'Keep the first completed accepted participation instead of the latest.',
                'retry_nonempty': 'After an empty published result try a nonempty outcome at the same start, before advancing.',
            },
            'limitations': ['Constructed small ASCII examples, not workload frequencies or universal engine equivalence.',
                            'Alternative interpretations modify a single policy dimension, not complete foreign engine semantics.',
                            'First, anchored, full and history observations reuse cases and must not be added as independent evidence.']}
    (root / 'plan.json').write_text(json.dumps(plan, indent=2) + '\n')
    (root / 'patterns.json').write_text(json.dumps(entries, indent=2) + '\n')
    enc = lambda value: base64.b64encode(value.encode()).decode()
    (root / 'patterns.tsv').write_text(''.join(
        f"{e['id']}\t{enc(e['regex'])}\t{enc(e['paired']) if e['paired'] else ''}\n"
        for e in entries))
    (root / 'inputs.tsv').write_text(''.join(enc(x) + '\n' for x in subjects))
    print('Declared', plan['caseCount'], 'pairs:', plan['studyPatterns'], flush=True)
    subprocess.run([args.java, str(source / 'OtherRulesProbe.java'), str(root)], check=True)
    result = summarize(root, entries, subjects)
    subprocess.run([sys.executable, str(source / 'validate_other_models.py'),
                    '--study', str(root), '--java', args.java], check=True)
    result['explicitCopyValidation'] = json.loads((root / 'model-validation.json').read_text())
    (root / 'results.json').write_text(json.dumps(result, indent=2) + '\n')
    (root / 'observed.json').write_text(json.dumps(
        {k: v for k, v in result.items() if k != 'examples'}, indent=2) + '\n')
    if args.check_recorded:
        recorded = json.loads((source / 'other-rules-observed-jdk26.json').read_text())
        assert json.loads((root / 'observed.json').read_text()) == recorded
    print(json.dumps({'runtime': result['runtime'], 'cases': result['cases'],
                      'summary': result['summary'], 'apiInvariantFailures': result['apiInvariantFailures']}, indent=2))


if __name__ == '__main__':
    main()
