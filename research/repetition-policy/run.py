# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see ../../LICENSE).

"""Reproduce the finite-repetition policy comparison using Python and public JDK APIs."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java', default='java', help='JDK source-file launcher')
    parser.add_argument('--check-recorded', action='store_true')
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    output = args.output.resolve()
    repository = source.parents[1]
    if output == repository or repository in output.parents:
        parser.error('keep generated evidence outside the checkout')
    output.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env['REPETITION_POLICY_STUDY_DIR'] = str(output)
    runtime = subprocess.run([args.java, '-version'], check=True, capture_output=True, text=True)
    (output / 'java-version.txt').write_text(runtime.stdout + runtime.stderr)
    for filename in ['model.py', 'generate.py', 'run_models.py']:
        subprocess.run([sys.executable, str(source / filename)], check=True, env=env)
    subprocess.run([args.java, str(source / 'StudyProbe.java'), str(output)], check=True, env=env)
    plan_path = output / 'plan.json'
    plan = json.loads(plan_path.read_text())
    plan['runtime'] = (output / 'java-runtime.txt').read_text().strip()
    plan_path.write_text(json.dumps(plan, indent=2) + '\n')
    for filename in ['analyze.py', 'deduplicate.py']:
        subprocess.run([sys.executable, str(source / filename)], check=True, env=env)
    results = json.loads((output / 'results.json').read_text())
    deduplicated = json.loads((output / 'deduplicated-results.json').read_text())
    if results['expandedValidationMismatchCount']:
        raise SystemExit('literal-expansion validation failed; inspect generated differences')
    observed = {
        'runtime': plan['runtime'],
        'deduplicatedFirstMatch': {
            'familyCounts': deduplicated['familyCounts'],
            'distinguishingCases': deduplicated['distinguishingCases'],
            'outcomes': deduplicated['outcomes'],
        },
        'allCases': results['cases'],
        'literalExpansionValidationMismatches': results['expandedValidationMismatchCount'],
        'firstMatchBounds': results['summary']['ALL']['first_bounds'],
        'firstMatchCaptures': results['summary']['ALL']['first_captures'],
        'findHistoryCaptures': results['summary']['ALL']['history_captures'],
    }
    (output / 'observed.json').write_text(json.dumps(observed, indent=2) + '\n')
    if args.check_recorded:
        recorded = json.loads((source / 'observed-jdk26.json').read_text())
        expected_runtime = recorded['runtime']
        if observed != recorded:
            raise SystemExit('results differ from the recorded ' + expected_runtime + ' observations')
    print('Reproduction complete; evidence directory:', output)
if __name__ == '__main__':
    main()
