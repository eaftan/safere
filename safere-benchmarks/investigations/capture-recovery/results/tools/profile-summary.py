#!/usr/bin/env python3
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the BSD 3-Clause License.
# See LICENSE file in the project root for details.

"""Summarize retained JFR execution samples; no timing attribution is implied."""

import collections, json, pathlib, subprocess, sys
out = pathlib.Path(__file__).resolve().parents[1]
paths = [out / 'baseline-jfr'] + [out / p / 'jfr' for p in sys.argv[1:]]
summaries = []
for folder in paths:
    for source in sorted(folder.rglob('*.jfr')):
        result = json.loads(subprocess.check_output(['jfr', 'print', '--json', '--events', 'jdk.ExecutionSample', str(source)], text=True))
        stages = collections.Counter()
        top = collections.Counter()
        total = 0
        for event in result['recording']['events']:
            frames = (event.get('values', {}).get('stackTrace') or {}).get('frames', [])
            names = [f['method']['type']['name'].replace('/', '.') + '.' + f['method']['name'] for f in frames]
            if not names:
                continue
            total += 1
            top[names[0]] += 1
            if any(('Matcher.resolveCaptures' in n for n in names)):
                stage = 'capture replay'
            elif any(('Matcher.searchReverseDfa' in n or 'Dfa.doSearchReverse' in n for n in names)):
                stage = 'reverse DFA'
            elif any(('Matcher.searchForwardDfa' in n for n in names)):
                stage = 'forward DFA'
            elif any(('Matcher.searchWithBitStateOrNfa' in n for n in names)):
                stage = 'exact boundary/fallback'
            elif any(('StartAccelerator' in n or 'StartCandidate' in n for n in names)):
                stage = 'start acceleration'
            else:
                stage = 'other'
            stages[stage] += 1
        summaries.append({'file': str(source.relative_to(out)), 'executionSamples': total, 'stackClassification': dict(stages), 'topFrames': top.most_common(12)})
(out / 'profiles-derived.json').write_text(json.dumps(summaries, indent=2) + '\n')
print('Summarized', len(summaries), 'retained JFR recordings; classification is approximate, not wall-time attribution.')
