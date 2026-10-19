#!/usr/bin/env python3
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
"""Report observed finding evidence independently of a fuzz process's exit status."""

import argparse
import json
from pathlib import Path
import re
import sys


FINDING_MARKERS = re.compile(
    r'AssertionError|FuzzerSecurityIssue|DEDUP_TOKEN|== Java Exception:'
    r'|(?:ERROR|SUMMARY): (?:libFuzzer|AddressSanitizer|UndefinedBehaviorSanitizer)')


def summary(result):
    return (f"{result['target']}: process={result['process']} (exit {result['exit_code']}); "
            f"findings={result['findings']}; reporting={result['reporting']}")


def report(log_dir, target, exit_code):
    log_dir = Path(log_dir)
    # Stream logs: coverage-guided runs can produce large output. Do not scan old Surefire reports
    # or persistent corpus directories, which can contain evidence from unrelated runs.
    with (log_dir / f'{target}.log').open(encoding='utf-8', errors='replace') as log:
        log_evidence = any(FINDING_MARKERS.search(line) for line in log)
    findings_dir = log_dir / f'{target}-findings'
    json_files = []
    if findings_dir.exists():
        json_files = sorted(p.name for p in findings_dir.iterdir()
                            if p.is_file() and p.suffix == '.json')
    reproducer_files = sorted(p.name for p in (log_dir / f'{target}-reproducers').iterdir()
                              if p.is_file())
    findings = 'UNTRIAGED' if log_evidence or json_files or reproducer_files else 'NONE'
    process = 'SUCCEEDED' if exit_code == 0 else 'FAILED'
    result = dict(target=target, exit_code=exit_code, process=process, findings=findings,
                  reporting='OK', log_evidence=log_evidence, finding_files=json_files,
                  reproducer_files=reproducer_files)
    try:
        with (log_dir / f'{target}.log').open('a', encoding='utf-8') as log:
            log.write(f'=== Completed {summary(result)} ===\n')
    except OSError as error:
        # Keep the observed findings and process status even if the log cannot be appended.
        result['reporting'] = 'FAILED'
        print(f'error: could not append target summary: {error}', file=sys.stderr)
    (log_dir / f'{target}-result.json').write_text(json.dumps(result, indent=2) + '\n')
    return summary(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('log_dir', type=Path)
    parser.add_argument('target')
    parser.add_argument('exit_code', type=int)
    args = parser.parse_args()
    print(report(args.log_dir, args.target, args.exit_code))


if __name__ == '__main__':
    main()
