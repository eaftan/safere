# Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see ../../LICENSE).

"""Small independent policy interpreter. Research-only bounded patterns/subjects."""
import re
E = ('empty',)

def L(s):
    return ('lit', s)

def C(s):
    return ('class', s)

def S(*xs):
    return ('seq', *xs)

def A(*xs):
    return ('alt', *xs)

def G(x):
    return ('cap', x)

def R(x, lo=0, hi=1, greedy=True):
    return ('rep', x, lo, hi, greedy)

def Z(s):
    return ('assert', s)

def W(x):
    return ('wrap', x)

def numbered(n):
    count = 0

    def visit(x):
        nonlocal count
        k = x[0]
        if k == 'cap':
            count += 1
            g = count
            return (k, g, visit(x[1]))
        if k in ('seq', 'alt'):
            return (k, *(visit(y) for y in x[1:]))
        if k == 'rep':
            return (k, visit(x[1]), *x[2:])
        if k == 'wrap':
            return (k, visit(x[1]))
        return x
    return (visit(n), lambda: count)

def render(n, expand=False, strip=False):
    k = n[0]
    if k == 'empty':
        return ''
    if k == 'lit':
        return re.escape(n[1])
    if k == 'class':
        return {'word': '\\w', 'space': '\\s', 'nonword': '\\W', 'digit': '\\d'}.get(n[1], '[' + n[1] + ']')
    if k == 'assert':
        return {'begin': '\\A', 'end': '\\z', 'word': '\\b'}[n[1]]
    if k == 'cap':
        return ('(?:' if strip else '(') + render(n[2], expand, strip) + ')'
    if k == 'wrap':
        return '(?:' + render(n[1], expand, strip) + ')'
    if k == 'seq':
        return ''.join((render(x, expand, strip) for x in n[1:]))
    if k == 'alt':
        return '(?:' + '|'.join((render(x, expand, strip) for x in n[1:])) + ')'
    if k == 'rep':
        _, child, lo, hi, greedy = n
        if expand and hi is not None:
            tail = E
            for _ in range(hi - lo):
                tail = A(S(child, tail), E) if greedy else A(E, S(child, tail))
            return render(S(*[child] * lo, tail), expand, strip)
        body = render(child, expand, strip)
        if child[0] not in ('cap', 'wrap', 'alt', 'class') and (not (child[0] == 'lit' and len(child[1]) == 1)):
            body = '(?:' + body + ')'
        q = '*' if lo == 0 and hi is None else '+' if lo == 1 and hi is None else '?' if (lo, hi) == (0, 1) else '{' + str(lo) + ',' + ('' if hi is None else str(hi)) + '}' if hi != lo else '{' + str(lo) + '}'
        return body + q + ('' if greedy else '?')
    raise AssertionError(n)

def contains_unbounded(n):
    if n[0] == 'rep':
        return n[3] is None or contains_unbounded(n[1])
    if n[0] in ('alt', 'seq'):
        return any(map(contains_unbounded, n[1:]))
    if n[0] == 'cap':
        return contains_unbounded(n[2])
    if n[0] == 'wrap':
        return contains_unbounded(n[1])
    return False

class Interpreter:

    def __init__(self, ast, text, groups, mode):
        self.ast = ast
        self.text = text
        self.groups = groups
        self.mode = mode
        self.steps = 0

    def tick(self):
        self.steps += 1
        if self.steps > 20000000:
            raise RuntimeError('research enumeration budget exceeded')

    def walk(self, n, p, c):
        self.tick()
        k = n[0]
        t = self.text
        if k == 'empty':
            yield (p, c)
        elif k == 'lit':
            if t.startswith(n[1], p):
                yield (p + len(n[1]), c)
        elif k == 'class':
            if p < len(t):
                a = t[p]
                s = n[1]
                word = a in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_'
                ok = word if s == 'word' else not word if s == 'nonword' else a in ' \t\n\r\x0c\x0b' if s == 'space' else a in '0123456789' if s == 'digit' else a in s
                if ok:
                    yield (p + 1, c)
        elif k == 'assert':
            word = lambda i: 0 <= i < len(t) and t[i] in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_'
            if n[1] == 'begin' and p == 0 or (n[1] == 'end' and p == len(t)) or (n[1] == 'word' and word(p - 1) != word(p)):
                yield (p, c)
        elif k == 'cap':
            for q, d in self.walk(n[2], p, c):
                ds = list(d)
                ds[n[1] - 1] = (p, q)
                yield (q, tuple(ds))
        elif k == 'wrap':
            yield from self.walk(n[1], p, c)
        elif k == 'alt':
            for child in n[1:]:
                yield from self.walk(child, p, c)
        elif k == 'seq':
            yield from self.seq(n[1:], 0, p, c)
        elif k == 'rep':
            yield from self.repeat(n, p, c, 0)
        else:
            raise AssertionError(n)

    def seq(self, n, i, p, c):
        self.tick()
        if i == len(n):
            yield (p, c)
        else:
            for q, d in self.walk(n[i], p, c):
                yield from self.seq(n, i + 1, q, d)

    def repeat(self, n, p, c, count):
        self.tick()
        _, child, lo, hi, greedy = n
        if hi is not None and count == hi:
            yield (p, c)
            return
        if count >= lo and (not greedy):
            yield (p, c)
        for q, d in self.walk(child, p, c):
            stop = q == p and ((hi is None or self.mode == 'stop') and count + 1 >= lo or (hi is not None and self.mode == 'optional' and (count >= lo)))
            if stop:
                yield (q, d)
            else:
                yield from self.repeat(n, q, d, count + 1)
        if count >= lo and greedy:
            yield (p, c)

    def at(self, start, full=False):
        for end, c in self.walk(self.ast, start, (None,) * self.groups):
            if not full or end == len(self.text):
                return [[start, end], *[list(g) if g is not None else None for g in c]]
        return None

    def search(self, start):
        for p in range(start, len(self.text) + 1):
            r = self.at(p)
            if r is not None:
                return r
        return None

    def operations(self):
        at = self.at(0)
        full = self.at(0, True)
        history = []
        start = 0
        while start <= len(self.text):
            r = self.search(start)
            if r is None:
                break
            history.append(r)
            a, b = r[0]
            start = b + (a == b)
        return {'first': history[0] if history else None, 'lookingAt': at, 'matches': full, 'history': history}

def run(ast, text, mode):
    n, count = numbered(ast)
    return Interpreter(n, text, count(), mode).operations()
if __name__ == '__main__':
    a = L('a')
    b = L('b')
    operand = A(R(b), a)
    assert run(S(R(operand, 0, 2), b), 'abb', 'expansion')['first'] == [[0, 2]]
    assert run(S(R(operand, 0, 2), b), 'abb', 'stop')['first'] == [[0, 3]]
    assert run(S(R(operand, 2, 2), b), 'abb', 'stop')['first'] == [[0, 2]]
    assert run(S(R(operand, 1, 2), b), 'abb', 'optional')['first'] == [[0, 2]]
    assert run(S(R(operand, 1, 2), b), 'abb', 'stop')['first'] == [[0, 3]]
    cap = A(G(E), R(C('nonword'), 1, None))
    pat = S(R(cap, 0, 2), L('y'))
    assert run(pat, ' y', 'expansion')['first'] == [[0, 2], [0, 0]]
    assert run(pat, ' y', 'stop')['first'] == [[0, 2], [1, 1]]
    assert run(A(S(G(a), b), L('ac')), 'ac', 'expansion')['first'] == [[0, 2], None]
    assert run(R(S(a, R(G(b))), 1, None), 'aba', 'stop')['first'] == [[0, 3], [1, 2]]
    assert run(R(G(E), 0, 2), '', 'stop')['first'] == [[0, 0], [0, 0]]
    assert run(S(R(R(G(a), 0, 2), 0, None), L('b')), 'aaaab', 'expansion')['first'] == [[0, 5], [3, 4]]
    assert run(R(A(L('a'), L('aa')), 1, 2), 'aa', 'expansion')['first'][0] == [0, 2]
    print('Interpreter path, minimum, capture retention and rollback self-checks passed')
