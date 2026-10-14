#!/usr/bin/env python3
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
"""Validate the upstream fuzz-target manifest and select launchable targets."""

import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
KINDS = {'broad', 'strict', 'property', 'robustness'}


def read_targets(root=ROOT):
    targets = json.loads((root / 'targets.json').read_text())
    names = set()
    classes = set()
    for target in targets:
        if set(target) - {'profile'} != {'class', 'kind', 'oss_fuzz', 'seeds', 'methods'}:
            raise ValueError(f'invalid manifest fields: {target}')
        if target.get('profile') not in (None, 'work-counters'):
            raise ValueError('invalid build profile')
        if target.get('profile') and target['oss_fuzz']:
            raise ValueError('instrumented target requires a separate hosted build')
        name = target['class']
        if not re.fullmatch(r'org\.safere\.(?:fuzz\.)?[A-Za-z][A-Za-z0-9]*Fuzzer', name):
            raise ValueError(f'invalid target class: {name}')
        short = name.rsplit('.', 1)[-1]
        if short in names or name in classes:
            raise ValueError(f'duplicate target: {name}')
        names.add(short)
        classes.add(name)
        if target['kind'] not in KINDS or type(target['oss_fuzz']) is not bool:
            raise ValueError(f'invalid target policy: {name}')
        if target['oss_fuzz'] and target['kind'] == 'broad':
            raise ValueError(f'broad differential target cannot enroll: {name}')
        source = root / 'src/test/java' / (name.replace('.', '/') + '.java')
        if not source.is_file():
            raise ValueError(f'missing target source: {name}')
        if target['oss_fuzz'] and 'public static void fuzzerTestOneInput(' not in source.read_text():
            raise ValueError(f'missing static entry point: {name}')
        methods = re.findall(r'@FuzzTest\([^)]*\)\s+void\s+(\w+)\(', source.read_text())
        if not methods or target['methods'] != methods:
            raise ValueError(f'fuzz method inventory differs from source: {name}')
        seeds = target['seeds']
        if seeds is not None:
            expected = 'src/test/resources/' + name.replace('.', '/') + 'Inputs'
            if seeds != expected or not (root / seeds).is_dir():
                raise ValueError(f'invalid seed path: {name}')
    discovered = {
        str(p.relative_to(root / 'src/test/java')).removesuffix('.java').replace('/', '.')
        for p in (root / 'src/test/java').rglob('*Fuzzer.java')
    }
    if discovered != classes:
        raise ValueError(f'uninventoried targets: {sorted(discovered ^ classes)}')
    return targets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', choices=['all', 'oss-fuzz', *sorted(KINDS)], default='all')
    parser.add_argument('--format', choices=['names', 'selectors', 'tsv', 'profile'], default='names')
    parser.add_argument('--select', help='Resolve a class or class#method to Maven selectors')
    args = parser.parse_args()
    try:
        targets = read_targets()
    except (ValueError, OSError) as error:
        parser.error(str(error))
    if args.select:
        short, separator, method = args.select.partition('#')
        selected = [t for t in targets if t['class'].rsplit('.', 1)[-1] == short]
        if not selected or (separator and method not in selected[0]['methods']):
            parser.error('unknown fuzz target: ' + args.select)
        if args.format == 'profile':
            print(selected[0].get('profile', ''))
            return
        for item in selected:
            for name in ([method] if separator else item['methods']):
                print(short + '#' + name)
        return
    for target in targets:
        if args.suite == 'oss-fuzz' and not target['oss_fuzz']:
            continue
        if args.suite in KINDS and target['kind'] != args.suite:
            continue
        if args.format == 'selectors':
            for method in target['methods']:
                print(target['class'].rsplit('.', 1)[-1] + '#' + method)
        elif args.format == 'names':
            print(target['class'].rsplit('.', 1)[-1])
        else:
            print(target['class'] + '\t' + (target['seeds'] or '-'))


if __name__ == '__main__':
    main()
