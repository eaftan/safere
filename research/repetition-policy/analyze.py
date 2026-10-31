# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see ../../LICENSE).

import os
from pathlib import Path
import json, collections, itertools
p = Path(os.environ['REPETITION_POLICY_STUDY_DIR'])
plan = json.loads((p / 'plan.json').read_text())
patterns = json.loads((p / 'patterns.json').read_text())
inputs = plan['inputs']
counters = collections.defaultdict(collections.Counter)
examples = collections.defaultdict(list)
validation = []
seen = set()
groups = collections.defaultdict(collections.Counter)

def metric(r, key):
    if key == 'first_bounds':
        return r['first'][0] if r['first'] is not None else None
    if key == 'first_captures':
        return r['first']
    if key == 'history_bounds':
        return [m[0] for m in r['history']]
    if key == 'history_captures':
        return r['history']
    if key == 'lookingAt_bounds':
        return r['lookingAt'][0] if r['lookingAt'] is not None else None
    if key == 'matches_bounds':
        return r['matches'][0] if r['matches'] is not None else None
    raise AssertionError(key)
metrics = ['first_bounds', 'first_captures', 'history_bounds', 'history_captures', 'lookingAt_bounds', 'matches_bounds']
with (p / 'models.jsonl').open() as a, (p / 'jdk.jsonl').open() as b:
    for mline, jline in itertools.zip_longest(a, b):
        assert mline is not None and jline is not None, 'incomplete arm'
        m = json.loads(mline)
        j = json.loads(jline)
        pid = m['pattern']
        idx = m['input']
        assert (pid, idx) == (j['pattern'], j['input'])
        assert (pid, idx) not in seen
        seen.add((pid, idx))
        v = patterns[pid]
        if j['expandedValidation'] is not None:
            for key in ['first_bounds', 'history_bounds', 'lookingAt_bounds', 'matches_bounds']:
                if metric(j['expandedValidation'], key) != metric(m['expansion'], key):
                    validation.append(
                        {
                            'pattern': pid,
                            'input': idx,
                            'metric': key,
                            'model': m['expansion'],
                            'explicit': j['expandedValidation'],
                        },
                    )
        for key in metrics:
            e, s, actual = (metric(m['expansion'], key), metric(m['stop'], key), metric(j['actual'], key))
            outcome = ('all_agree' if actual == e else 'common_disagreement') if e == s else 'expansion' if actual == e else 'stop' if actual == s else 'neither'
            for family in ['ALL', v['family']]:
                counters[family, key][outcome] += 1
            sub = ('finite_only' if v['finiteOnly'] else 'contains_unbounded', key)
            groups[sub][outcome] += 1
            groups['minimum_' + str(v['min']), key][outcome] += 1
            groups['greedy' if v['greedy'] else 'reluctant', key][outcome] += 1
            if outcome in ['expansion', 'stop', 'neither']:
                item = {
                    'pattern': pid,
                    'inputIndex': idx,
                    'family': v['family'],
                    'variant': v['variant'],
                    'context': v['context'],
                    'regex': v['regex'],
                    'input': inputs[idx],
                    'metric': key,
                    'outcome': outcome,
                    'expansion': e,
                    'stop': s,
                    'jdk': actual,
                    'finiteOnly': v['finiteOnly'],
                }
                target = examples[v['family'], key, outcome]
                target.append(item)
                target.sort(key=lambda x: (len(x['input']), len(x['regex']), x['regex'], x['input']))
                del target[3:]
assert len(seen) == plan['caseCount'], (len(seen), plan['caseCount'])
(p / 'expanded-validation-differences.json').write_text(json.dumps(validation, indent=2) + '\n')
result = {
    'cases': len(seen),
    'runtime': plan['runtime'],
    'expandedValidationMismatchCount': len(validation),
    'summary': {family: {key: dict(counters[family, key]) for key in metrics} for family in ['ALL', *plan['families']]},
    'subgroups': {label: {key: dict(groups[label, key]) for key in metrics} for label in sorted({x[0] for x in groups})},
    'examples': [x for xs in examples.values() for x in xs],
}
(p / 'results.json').write_text(json.dumps(result, indent=2) + '\n')
print('Literal-expansion validation mismatches:', len(validation))
print('ALL', result['summary']['ALL'])
for family in plan['families']:
    print(
        family,
        'first bounds',
        result['summary'][family]['first_bounds'],
        'first captures',
        result['summary'][family]['first_captures'],
    )
print('First-bound expansion examples:')
for e in result['examples']:
    if e['metric'] == 'first_bounds' and e['outcome'] == 'expansion':
        print(e)
