#!/usr/bin/env python3
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
"""Recalculate this investigation's comparisons from the archived normalized rows."""
import collections
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent
manifest = json.loads((ROOT / 'resolved-manifest.json').read_text())
workloads = {e['workloadId']: e for e in manifest['executionPlan']['entries']
             if e['engineId'] == 'safere-string'}
data = collections.defaultdict(dict)
identities = set()
for line in (ROOT / 'raw/normalized-results.jsonl').read_text().splitlines():
    row = json.loads(line)
    identity = (row['engine'], row['benchmark'])
    assert identity not in identities, identity
    identities.add(identity)
    factor = {'ns/op': 1, 'us/op': 1000, 'ms/op': 1000000}.get(row['unit'])
    if factor is not None and row['score'] > 0:
        data[row['benchmark']][row['engine']] = row['score'] * factor

categories = {
    'Real-world find': lambda k: k.startswith('RealWorldRegexBenchmark.') and workloads[k]['operation'] == 'find',
    'Real-world find: match': lambda k: k.startswith('RealWorldRegexBenchmark.') and workloads[k]['operation'] == 'find' and '.match.' in k,
    'Real-world find: no match': lambda k: k.startswith('RealWorldRegexBenchmark.') and workloads[k]['operation'] == 'find' and '.noMatch.' in k,
    'Real-world replaceAll': lambda k: k.startswith('RealWorldRegexBenchmark.') and workloads[k]['operation'] == 'replaceAll',
    'All declared find': lambda k: workloads[k]['operation'] == 'find',
    'Steady-state compile': lambda k: workloads[k]['operation'] == 'compile' and workloads[k]['measurement']['mode'] != 'singleShotColdStart',
    'First compile': lambda k: workloads[k]['operation'] == 'compile' and workloads[k]['measurement']['mode'] == 'singleShotColdStart',
    'Real-world find excluding mapFieldPath': lambda k: k.startswith('RealWorldRegexBenchmark.') and workloads[k]['operation'] == 'find' and '.mapFieldPath.' not in k,
}
aggregates = []
for category, include in categories.items():
    for a, b in [('safere', 're2_cpp'), ('safere', 'rust'), ('safere_utf8', 're2_cpp'),
                 ('safere_utf8', 'rust'), ('safere_utf8', 'safere')]:
        members = sorted(k for k, v in data.items() if include(k) and a in v and b in v)
        if members:
            ratio = math.exp(sum(math.log(data[k][a] / data[k][b]) for k in members) / len(members))
            aggregates.append(dict(category=category, numerator=a, denominator=b,
                                   ratio=ratio, rows=len(members), members=members))
(ROOT / 'aggregates.json').write_text(json.dumps(aggregates, indent=2) + '\n')
text = ['| Category | String / RE2 | String / Rust | UTF-8 / RE2 | UTF-8 / Rust |',
        '|---|---:|---:|---:|---:|']
for category in categories:
    cells = []
    for a, b in [('safere', 're2_cpp'), ('safere', 'rust'), ('safere_utf8', 're2_cpp'), ('safere_utf8', 'rust')]:
        row = next((r for r in aggregates if r['category'] == category and r['numerator'] == a and r['denominator'] == b), None)
        cells.append(f'{row["ratio"]:.3f} ({row["rows"]})' if row else '—')
    text.append('| ' + category + ' | ' + ' | '.join(cells) + ' |')
(ROOT / 'comparison-tables.md').write_text('\n'.join(text) + '\n')

candidates = [
    ('String end-anchored greedy search', 'SearchScalingBenchmark.endAnchoredGreedySuccess.1048576'),
    ('End-anchored early literal search', 'SearchScalingBenchmark.endAnchoredEarlyLiteralDot.1048576'),
    ('Field-path Boolean search', 'RealWorldRegexBenchmark.runBenchmark.mapFieldPath.match.100000'),
    ('Protocol-link replacement', 'RealWorldRegexBenchmark.runBenchmark.customProtocolLinkReplace.falseCandidates.100000'),
    ('Multi-literal alternation', 'SearchScalingBenchmark.multiLiteral6Success.1048576'),
    ('Fixed-offset candidate filtering', 'FixedOffsetAccelerationBenchmark.findMultiOffsetColonNoise'),
    ('UTF-8 literal scanning', 'Utf8MatchingBenchmark.literalScan.longWord'),
    ('UTF-8 small ASCII class scanning', 'SmallAsciiClassBenchmark.triple.mixedLate.1048576'),
    ('Capture fallback for Boolean searches', 'CaptureFallbackBenchmark.boolean.1024'),
    ('First-compile initialization', 'UnicodeFirstCompileBenchmark.firstCompile.word.0'),
]
rows = []
for title, key in candidates:
    assert key in data, key
    rows.append(dict(title=title, workload=key, operation=workloads[key]['operation'],
                     patterns=workloads[key].get('patterns'), times_ns=data[key]))
(ROOT / 'opportunities.json').write_text(json.dumps(rows, indent=2) + '\n')
print((ROOT / 'comparison-tables.md').read_text())
for r in rows:
    print(r['title'], r['workload'], r['times_ns'])
