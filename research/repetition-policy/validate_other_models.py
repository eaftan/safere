# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see ../../LICENSE).

"""Independent explicit-copy control for nested versus flat optional ordering."""
import argparse
import base64
import itertools
import json
from pathlib import Path
import subprocess

from model import A, E, R, S, render
from other_rules import Alternative


def expand(node):
    kind = node[0]
    if kind == 'rep':
        _, child, lo, hi, greedy = node
        assert hi is not None
        child = expand(child)
        tail = E
        for _ in range(hi - lo):
            tail = A(S(child, tail), E) if greedy else A(E, S(child, tail))
        return S(*[child] * lo, tail)
    if kind in ('seq', 'alt'):
        return (kind, *(expand(child) for child in node[1:]))
    if kind == 'cap':
        return kind, node[1], expand(node[2])
    if kind == 'wrap':
        return kind, expand(node[1])
    return node


def flatten_outer(node):
    if node[0] == 'rep':
        _, child, lo, hi, greedy = node
        return S(*[child] * lo, *[R(child, 0, 1, greedy)] * (hi - lo))
    if node[0] in ('seq', 'alt'):
        return node[0], *(flatten_outer(child) for child in node[1:])
    if node[0] == 'cap':
        return node[0], node[1], flatten_outer(node[2])
    if node[0] == 'wrap':
        return node[0], flatten_outer(node[1])
    return node


def capture_ids(node):
    if node[0] == 'cap':
        return [node[1], *capture_ids(node[2])]
    if node[0] in ('seq', 'alt'):
        return list(itertools.chain.from_iterable(capture_ids(c) for c in node[1:]))
    if node[0] == 'wrap':
        return capture_ids(node[1])
    return []


def collapse(vector, identities, groups):
    if vector is None:
        return None
    result = [vector[0], *[None] * groups]
    assert len(vector) == len(identities) + 1
    for source_id, span in zip(identities, vector[1:], strict=True):
        if span is not None:
            # This control corpus has only consuming captures. Distinct writes
            # to the same source identity are disjoint and chronologically ordered
            # by start position, even though source expansion duplicates group IDs.
            assert span[0] < span[1]
            if result[source_id] is None or span[0] > result[source_id][0]:
                result[source_id] = span
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study', type=Path, required=True)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    args.study = args.study.resolve()
    repository = source.parents[1]
    if args.study == repository or repository in args.study.parents:
        parser.error('keep generated evidence outside the checkout')
    root = args.study / 'explicit-copy-validation'
    root.mkdir(exist_ok=True)
    entries = json.loads((args.study / 'patterns.json').read_text())
    subjects = json.loads((args.study / 'plan.json').read_text())['subjects']
    checks = []
    enc = lambda s: base64.b64encode(s.encode()).decode()
    patterns = []
    for entry in entries:
        if entry['study'] != 'optional_order':
            continue
        for mode in ['selected', 'flat_optionals']:
            ast = expand(entry['ast'] if mode == 'selected' else flatten_outer(entry['ast']))
            checks.append((entry, mode, capture_ids(ast)))
            patterns.append(f'{len(patterns)}\t{enc(render(ast))}\t\t0\n')
    (root / 'patterns.tsv').write_text(''.join(patterns))
    (root / 'inputs.tsv').write_text(''.join(enc(text) + '\n' for text in subjects))
    subprocess.run([args.java, str(source / 'StudyProbe.java'), str(root)], check=True)
    assert (root / 'java-runtime.txt').read_text() == (args.study / 'java-runtime.txt').read_text()
    count = 0
    with (root / 'jdk.jsonl').open() as stream:
        for line, expected_id in zip(stream, itertools.product(range(len(checks)),
                                                              range(len(subjects))), strict=True):
            row = json.loads(line)
            assert (row['pattern'], row['input']) == expected_id
            entry, mode, identities = checks[row['pattern']]
            expected = Alternative(entry['ast'], subjects[row['input']], entry['groups'], mode).operations()
            actual = row['actual']
            merged = {op: collapse(actual[op], identities, entry['groups'])
                      for op in ['first', 'lookingAt', 'matches']}
            merged['history'] = [collapse(v, identities, entry['groups']) for v in actual['history']]
            assert merged == expected, (entry['regex'], subjects[row['input']], mode, merged, expected)
            count += 1
    assert count == len(checks) * len(subjects)
    result = {'cases': count, 'mismatches': 0,
              'scope': 'Nested and flat optional ordering, fully expanded finite patterns; source capture identities reconstructed from consuming writes.'}
    (args.study / 'model-validation.json').write_text(json.dumps(result, indent=2) + '\n')
    print('Explicit-copy model validation passed:', count, 'pattern/subject cases', flush=True)


if __name__ == '__main__':
    main()
