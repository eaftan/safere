# Copyright (c) 2026 Eddie Aftandilian. Licensed under the BSD 3-Clause License.
# See LICENSE file in the project root for details.

import json
import math
from pathlib import Path
root = Path(__file__).parent
policies = ['baseline', 'dfa1073', 'local4', 'hybrid4', 'hybrid16', 'localSticky']

def load(p, suffix=''):
    try:
        return {next(iter(r['params'].values())): r for r in json.loads((root / (p + suffix + '.json')).read_text())}
    except (FileNotFoundError, json.JSONDecodeError):
        return {}

def score(r):
    return r['primaryMetric']['score']

def gm(xs):
    return math.exp(sum((math.log(x) for x in xs)) / len(xs))
data = {p: load(p) for p in policies}
base = data['baseline']
out = []

def emit(x=''):
    out.append(x)
    print(x)
groups = {}
for rate in ['all', 'rare']:
    for mode in ['text1', 'text4', 'phase1']:
        groups['128 retained ' + rate + ' ' + mode] = [k for k in base if '.128.' in k and '.' + rate + '.' + mode + '.retained@' in k]
for rate in ['all', 'rare']:
    groups['128 fresh ASCII ' + rate + ' text1'] = [k for k in base if '.ascii.128.' + rate + '.text1.newPerInvocation@' in k]
for mode in ['offset1', 'boolean1']:
    groups['ASCII ' + mode + ' controls'] = [k for k in base if '.' + mode + '.' in k]
groups['Boundary controls'] = [k for k in base if '.128.' not in k]
emit('Ratios are candidate time / baseline time, geometric means with equal weight per exact row.')
emit('Dash = not measured; n marks actual pairwise membership. No partial set is an overall result.')
emit('| Category | Baseline rows | ' + ' | '.join(policies[1:]) + ' |')
emit('|---|---:|' + '---:|' * 5)
for name, ks in groups.items():
    cells = []
    for p in policies[1:]:
        common = [k for k in ks if k in data[p]]
        cells.append(f'{gm([score(data[p][k]) / score(base[k]) for k in common]):.3f} (n={len(common)})' if common else '–')
    emit('| ' + name + ' | ' + str(len(ks)) + ' | ' + ' | '.join(cells) + ' |')
emit('\nRepresentative retained ASCII 128 cases, ns/document (one timed cycle = 1536 documents):')
for k in base:
    if '.ascii.128.' in k and '.text1.retained@' in k:
        emit(k + ' ' + ' '.join((f"{p}={score(d[k]) / 1536:.1f}±{d[k]['primaryMetric']['scoreError'] / 1536:.1f}" for p, d in data.items() if k in d)))
emit('\nConcurrent shared Pattern, four threads: two text consumers and two boolean consumers.')
shared = {p: load(p, '-shared') for p in policies}
sb = shared['baseline']
for k in sb:
    emit(k + ' ' + ' '.join((f'{p}={score(d[k]) / 1536:.1f}ns/doc ({score(d[k]) / score(sb[k]):.3f}x)' for p, d in shared.items() if k in d)))
emit('\nLong-mode confirmation (raw ns/op; findUsage divided by 1536 for ns/document):')
longs = {p: load(p, '-long') for p in ['baseline', 'dfa1073', 'local4']}
for k in longs['baseline']:
    factor = 1536 if k.startswith('CapturePolicyBenchmark.') else 1
    emit(k + ' ' + ' '.join((f"{p}={score(d[k]) / factor:.1f}±{d[k]['primaryMetric']['scoreError'] / factor:.1f}" for p, d in longs.items() if k in d)))
emit('\nExisting scheme document extraction, raw us/op:')
docs = {p: load(p, '-document') for p in ['baseline', 'dfa1073', 'local4']}
for k in docs['baseline']:
    emit(k + ' ' + ' '.join((f"{p}={score(d[k]):.4f}±{d[k]['primaryMetric']['scoreError']:.4f}" for p, d in docs.items() if k in d)))
(root / 'summary.txt').write_text('\n'.join(out) + '\n')
