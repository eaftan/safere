# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see ../../LICENSE).

import os
from pathlib import Path
import json, itertools, collections
p = Path(os.environ['REPETITION_POLICY_STUDY_DIR'])
plan = json.loads((p / 'plan.json').read_text())
patterns = json.loads((p / 'patterns.json').read_text())
seen = {}

def canonical(n):
    k = n[0]
    if k == 'cap':
        return canonical(n[2])
    if k == 'wrap':
        return canonical(n[1])
    if k == 'seq':
        xs = []
        for y in n[1:]:
            v = canonical(y)
            if v == ('empty',):
                continue
            if v[0] == 'seq':
                xs.extend(v[1:])
            else:
                xs.append(v)
        return ('empty',) if not xs else xs[0] if len(xs) == 1 else ('seq', *xs)
    if k == 'alt':
        return (k, *(canonical(y) for y in n[1:]))
    if k == 'rep':
        return (k, canonical(n[1]), *n[2:])
    return tuple(n)
with (p / 'models.jsonl').open() as a, (p / 'jdk.jsonl').open() as b:
    for ml, jl in zip(a, b):
        m = json.loads(ml)
        j = json.loads(jl)
        v = patterns[m['pattern']]
        i = m['input']
        key = (canonical(v['ast']), i)
        bounds = lambda r: r['first'][0] if r['first'] is not None else None
        e, s, d = (bounds(m['expansion']), bounds(m['stop']), bounds(j['actual']))
        if e == s:
            continue
        outcome = 'expansion' if d == e else 'stop' if d == s else 'neither'
        if key not in seen:
            seen[key] = {
                'family': set(),
                'variants': set(),
                'outcomes': set(),
                'expansion': e,
                'stop': s,
                'examples': [],
            }
        x = seen[key]
        assert (x['expansion'], x['stop']) == (e, s)
        x['family'].add(v['family'])
        x['variants'].add(v['variant'])
        x['outcomes'].add(outcome)
        if len(x['examples']) < 3:
            x['examples'].append({'regex': v['regex'], 'input': plan['inputs'][i], 'jdk': d, 'outcome': outcome})
counts = collections.Counter(
    (next(iter(x['outcomes'])) if len(x['outcomes']) == 1 else 'syntax_dependent' for x in seen.values()),
)
variants = sorted(set().union(*(x['variants'] for x in seen.values())))
family_counts = collections.defaultdict(collections.Counter)
priority = [
    'optional_first_alternation',
    'overlapping_alternation',
    'reluctant_operand',
    'assertions',
    'nested_finite',
    'captured_nullable_operand',
]
for x in seen.values():
    family = next((f for f in priority if f in x['family']), sorted(x['family'])[0])
    outcome = next(iter(x['outcomes'])) if len(x['outcomes']) == 1 else 'syntax_dependent'
    family_counts[family][outcome] += 1
result = {
    'familyCounts': {f: dict(v) for f, v in family_counts.items()},
    'criterion': 'Canonical AST plus exact subject, stripping captures and redundant wrappers and flattening concatenation only. Retain repeat bounds, greediness, alternative order and literal values. Collapse duplicates only for whole-match comparison; source-dependent JDK outcomes are recorded separately.',
    'distinguishingCases': len(seen),
    'outcomes': dict(counts),
    'operandVariants': variants,
    'syntaxDependentGroups': [{'families': sorted(x['family']), 'variants': sorted(x['variants']), 'expansion': x['expansion'], 'stop': x['stop'], 'outcomes': sorted(x['outcomes']), 'examples': x['examples']} for x in seen.values() if len(x['outcomes']) > 1],
}
(p / 'deduplicated-results.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
