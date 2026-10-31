# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see ../../LICENSE).

import base64
import itertools
import json
import os
from pathlib import Path

from model import A, C, E, G, L, R, S, Z, contains_unbounded, numbered, render

p = Path(os.environ['REPETITION_POLICY_STUDY_DIR'])
a = L('a')
b = L('b')
c = L('c')
families = {
    'optional_atoms': [('optional_literal', R(a)), ('optional_class', R(C('ab'))), ('nullable_run', R(a, 0, None))],
    'empty_first_alternation': [
        ('empty_or_literal', A(E, a)),
        ('empty_capture_or_literal', A(G(E), a)),
        ('empty_or_word', A(E, L('ab'))),
    ],
    'optional_first_alternation': [
        ('optional_or_literal', A(R(a), b)),
        ('optional_class_or_literal', A(R(C('ab')), c)),
        ('optional_word_or_literal', A(R(L('ab')), b)),
    ],
    'nullable_concatenation': [
        ('two_optionals', S(R(a), R(b))),
        ('class_then_optional', S(R(C('ab')), R(c))),
        ('two_nullable_choices', S(A(E, a), A(E, b))),
    ],
    'overlapping_alternation': [
        ('overlapping_optional_tails', A(S(a, R(b)), R(b))),
        ('nullable_run_or_word', A(R(a, 0, None), L('ab'))),
        ('optional_word_or_sequence', A(R(L('ab')), S(R(a), b))),
    ],
    'reluctant_operand': [
        ('reluctant_optional_or_literal', A(R(a, 0, 1, False), b)),
        ('reluctant_run_or_literal', A(R(a, 0, None, False), b)),
        ('reluctant_then_optional', S(R(a, 0, 1, False), R(b))),
    ],
    'captured_nullable_operand': [
        ('capture_optional', G(R(a))),
        ('empty_capture_or_nonword', A(G(E), R(C('nonword'), 1, None))),
        ('captured_choice', A(G(R(a)), G(b))),
    ],
    'assertions': [
        ('begin_or_literal', A(Z('begin'), a)),
        ('boundary_or_class', A(Z('word'), C('ab'))),
        ('nullable_then_boundary', S(A(E, a), Z('word'))),
        ('end_or_literal', A(Z('end'), a)),
    ],
    'nested_finite': [
        ('captured_inner_count', R(G(a), 0, 2)),
        ('inner_count_or_optional', A(R(a, 0, 2), R(b))),
        ('required_nullable_inner_count', R(A(E, L('ab')), 1, 2)),
    ],
    'nested_unbounded': [
        ('captured_inner_star', R(G(a), 0, None)),
        ('run_or_empty_capture', A(R(a, 1, None), G(E))),
        ('star_then_optional', S(R(a, 0, None), R(b))),
    ],
    'pure_zero_width': [
        ('empty_capture', G(E)),
        ('capture_then_boundary', S(G(E), Z('word'))),
        ('captured_anchor_choice', A(G(Z('begin')), G(Z('end')))),
    ],
}
bounds = [(0, 1), (0, 2), (0, 3), (1, 2), (2, 2), (2, 3)]
inputs = sorted(
    {''.join(x) for n in range(3) for x in itertools.product('abc', repeat=n)} | {''.join(x) for n in range(5) for x in itertools.product('ab', repeat=n)} | {'aabbc', 'abbc', 'abc', 'acb', 'bac', 'babc', 'x', 'xab', 'xabb', 'xxabb', ' y', '  y', 'ay', 'ayy', ' y y', '12', 'a1b'},
)
contexts = {
    'suffix_a': lambda x: S(x, a),
    'suffix_b': lambda x: S(x, b),
    'ordered_suffix': lambda x: S(x, A(L('ab'), b)),
    'consuming_suffix': lambda x: S(x, R(C('ab'), 1, None), c),
    'end_assertion': lambda x: S(x, Z('end')),
    'captured_context': lambda x: S(G(x), G(A(L('ab'), b))),
    'literal_prefix': lambda x: S(L('x'), x, b),
    'ambiguous_prefix': lambda x: S(A(L('ab'), a), x, b),
    'anchored_context': lambda x: S(Z('begin'), x, b, Z('end')),
}
patterns = []
for family, operands in families.items():
    for variant, body in operands:
        for lo, hi in bounds:
            for greedy in [True, False]:
                for context, make in contexts.items():
                    raw = make(R(body, lo, hi, greedy))
                    ast, count = numbered(raw)
                    patterns.append(
                        {
                            'id': len(patterns),
                            'family': family,
                            'variant': variant,
                            'min': lo,
                            'max': hi,
                            'greedy': greedy,
                            'context': context,
                            'groups': count(),
                            'ast': ast,
                            'regex': render(ast),
                            'expanded': render(ast, True, True),
                            'finiteOnly': not contains_unbounded(ast),
                        },
                    )
meta = {
    'runtime': 'recorded after the JDK probe in java-runtime.txt',
    'families': {k: [v[0] for v in vs] for k, vs in families.items()},
    'bounds': bounds,
    'outerGreediness': [True, False],
    'contexts': list(contexts),
    'inputs': inputs,
    'patternCount': len(patterns),
    'caseCount': len(patterns) * len(inputs),
    'primaryUnit': 'One pattern/input pair, first unanchored match bounds; summarize each behavioral family separately.',
    'secondaryUnits': [
        'Full capture vector of the first match',
        'Repeated find history',
        'lookingAt and matches results',
    ],
    'models': {
        'expansion': 'Finite required copies followed by nested optional suffix; empty does not stop finite repeats.',
        'stop': 'Accept empty and stop the invocation once this iteration has met the minimum.',
        'shared': 'Unbounded repeats in both models accept a terminal empty iteration once the minimum is met; immutable accepted-path captures, retention and rollback. All positions use UTF-16 units (subjects are ASCII).',
    },
    'limitations': [
        'Purposely constructed semantic cases; no estimate of production workload frequency.',
        'Small fixed pattern/input bounds permit exhaustive ordered-path enumeration; this interpreter is not a production engine.',
        'Common disagreements of both models with JDK are recorded separately from cases distinguishing the finite-repeat policies.',
    ],
}
(p / 'plan.json').write_text(json.dumps(meta, indent=2) + '\n')
(p / 'patterns.json').write_text(json.dumps(patterns, indent=2) + '\n')
enc = lambda s: base64.b64encode(s.encode()).decode()
(p / 'patterns.tsv').write_text(
    ''.join(
        (f"{v['id']}\t{enc(v['regex'])}\t{enc(v['expanded'])}\t{int(v['finiteOnly'])}\n" for v in patterns),
    ),
)
(p / 'inputs.tsv').write_text(''.join((enc(x) + '\n' for x in inputs)))
print(
    'Declared',
    len(families),
    'families,',
    len(patterns),
    'patterns,',
    len(inputs),
    'subjects,',
    meta['caseCount'],
    'pattern/input cases',
    flush=True,
)
