# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see ../../LICENSE).

import os
from pathlib import Path
from model import Interpreter
import json, time, collections
p = Path(os.environ['REPETITION_POLICY_STUDY_DIR'])
plan = json.loads((p / 'plan.json').read_text())
patterns = json.loads((p / 'patterns.json').read_text())
inputs = plan['inputs']
begin = time.monotonic()
errors = []
maxsteps = 0
with (p / 'models.jsonl').open('w') as out:
    for v in patterns:
        for i, text in enumerate(inputs):
            results = {}
            steps = {}
            for mode in ['expansion', 'stop']:
                interpreter = Interpreter(v['ast'], text, v['groups'], mode)
                try:
                    results[mode] = interpreter.operations()
                except Exception as error:
                    errors.append({'pattern': v['id'], 'input': i, 'mode': mode, 'error': str(error)})
                    raise
                steps[mode] = interpreter.steps
                maxsteps = max(maxsteps, interpreter.steps)
            out.write(
                json.dumps({'pattern': v['id'], 'input': i, **results, 'steps': steps}, separators=(',', ':')) + '\n',
            )
        if v['id'] % 100 == 0:
            print(
                'model pattern',
                v['id'],
                'elapsed',
                round(time.monotonic() - begin, 1),
                'max steps',
                maxsteps,
                flush=True,
            )
(p / 'model-completion.json').write_text(
    json.dumps({'completed': True, 'patterns': len(patterns), 'subjects': len(inputs), 'errors': errors, 'maxInterpreterSteps': maxsteps, 'elapsedSeconds': time.monotonic() - begin}, indent=2) + '\n',
)
print('Models completed', round(time.monotonic() - begin, 1), 'seconds', flush=True)
