#!/usr/bin/env python3
# Copyright (c) 2026 Eddie Aftandilian. Licensed under the BSD 3-Clause License.
# See LICENSE file in the project root for details.

"""Regenerate or check all comparison tables from preserved raw JMH results."""

import argparse

CHECK = False

def emit(path, contents):
    if CHECK:
        if path.read_text() != contents:
            raise ValueError("Derived artifact differs: " + str(path))
    else:
        path.write_text(contents)

import json, math, pathlib, statistics
out = pathlib.Path(__file__).resolve().parents[1]

def read_recovery(name):
    assert (out / (name + '.exit')).read_text().strip() == '0', name
    rows = json.loads((out / (name + '.json')).read_text())
    by = {tuple((r['params'][k] for k in ['representation', 'scenario'])): r for r in rows}
    assert len(by) == len(rows) == 16, name
    assert all((math.isfinite(r['primaryMetric']['score']) and r['primaryMetric']['score'] > 0 for r in rows))
    return by

def gm(values):
    return math.exp(statistics.mean(map(math.log, values)))

def recovery():
    derived = []
    lines = ['| Mode | Input | Scenario | Main ns/cycle ± JMH error | Candidate ns/cycle ± JMH error | Ratio |', '|---|---|---|---:|---:|---:|']
    summary = []
    for mode, base, new in [('standard', 'base-screen', 'greedy-screen'), ('long', 'base-long', 'greedy-long')]:
        before = read_recovery(base)
        after = read_recovery(new)
        assert before.keys() == after.keys()
        for key in sorted(before):
            a = before[key]['primaryMetric']
            b = after[key]['primaryMetric']
            ratio = b['score'] / a['score']
            row = {'mode': mode, 'representation': key[0], 'scenario': key[1], 'main': a, 'candidate': b, 'ratio': ratio}
            derived.append(row)
            lines.append(f"| {mode} | {key[0]} | {key[1]} | {a['score']:.1f} ± {a['scoreError']:.1f} | {b['score']:.1f} ± {b['scoreError']:.1f} | {ratio:.3f}× |")
        for representation in ['string', 'utf8', 'both']:
            subset = [r for r in derived if r['mode'] == mode and (representation == 'both' or r['representation'] == representation)]
            ratios = sorted((r['ratio'] for r in subset))
            summary.append({'mode': mode, 'representation': representation, 'rows': len(ratios), 'geometricMean': gm(ratios), 'p90NearestRank': ratios[math.ceil(0.9 * len(ratios)) - 1], 'worst': max(ratios)})
    emit(out / 'recovery-rows.json', json.dumps(derived, indent=2) + '\n')
    emit(out / 'recovery-aggregates.json', json.dumps(summary, indent=2) + '\n')
    emit(out / 'recovery-table.md', '\n'.join(lines) + '\n')
    a = json.loads((out / 'base-long-command.json').read_text())['sourceSha256']
    b = json.loads((out / 'greedy-long-command.json').read_text())['sourceSha256']
    changed = {k for k in a.keys() | b.keys() if a.get(k) != b.get(k)}
    assert changed == {'safere/src/main/java/org/safere/BitState.java', 'safere/src/main/java/org/safere/Prog.java'}, changed
    print(json.dumps(summary, indent=2))

def cutoff_read(name):
    path = out / name
    assert (path / 'exit.txt').read_text().strip() == '0', name
    assert (path / 'paths-exit.txt').read_text().strip() == '0', name
    manifest = json.loads((path / 'command.json').read_text())
    rows = json.loads((path / 'results.json').read_text())
    by = {tuple((r['params'][k] for k in ['cutoffTrial', 'operation', 'profile'])): r for r in rows}
    assert len(by) == len(rows) == manifest['expectedRows']
    audit = json.loads((path / 'paths.json').read_text())
    assert len(audit) == len(by)
    assert {(r['trial'], r['operation'], r['profile']) for r in audit} == set(by)
    assert all((p['reverseDfa'] == 0 for r in audit for p in r['paths'])), name
    return (manifest, by)

def cutoffs():
    derived = []
    for family in ['stack', 'onePass']:
        ma, a = cutoff_read(f'cutoff-0-{family}-screen')
        mb, b = cutoff_read(f'cutoff-2048-{family}-screen')
        assert a.keys() == b.keys()
        changed = {k for k in ma['sourceSha256'].keys() | mb['sourceSha256'].keys() if ma['sourceSha256'].get(k) != mb['sourceSha256'].get(k)}
        assert changed == {'safere/src/main/java/org/safere/Matcher.java'}, changed
        for key in sorted(a):
            x = a[key]['primaryMetric']
            y = b[key]['primaryMetric']
            derived.append({'family': family, 'trial': key[0], 'operation': key[1], 'profile': key[2], 'disabled': x, 'extended': y, 'ratio': y['score'] / x['score']})
    emit(out / 'cutoff-rows.json', json.dumps(derived, indent=2) + '\n')
    lines = []
    for family, profiles in [('stack', ['text', 'mixed', 'rare', 'reject']), ('onePass', ['text', 'reject'])]:
        lines += [f'### {family}', '', '| Input | Length | Operation | ' + ' | '.join((p + ' ratio' for p in profiles)) + ' |', '|---|---:|---|' + '---:|' * len(profiles)]
        subset = [r for r in derived if r['family'] == family]
        for engine in ['safere-string', 'safere-utf8']:
            for size in [512, 1024]:
                for op in ['matches', 'lookingAt', 'find']:
                    cells = [next((r for r in subset if r['trial'] == f'CaptureCutoffBenchmark.{family}.{size}@{engine}' and r['operation'] == op and (r['profile'] == p))) for p in profiles]
                    lines.append(f'| {engine} | {size} | {op} | ' + ' | '.join((f"{r['ratio']:.3f}×" for r in cells)) + ' |')
        lines.append('')
    _, boundary = cutoff_read('cutoff-512-boundary-screen')
    lines += ['### Existing 512 boundary, candidate implementation', '', '| Input | Operation | Profile | 511 ns/doc | 512 ns/doc | 513 ns/doc |', '|---|---|---|---:|---:|---:|']
    for engine in ['safere-string', 'safere-utf8']:
        for op in ['matches', 'lookingAt', 'find']:
            for profile in ['text', 'reject']:
                vals = [boundary[f'CaptureCutoffBenchmark.stack.{size}@{engine}', op, profile]['primaryMetric']['score'] / 16 for size in [511, 512, 513]]
                lines.append(f'| {engine} | {op} | {profile} | ' + ' | '.join((f'{v:.1f}' for v in vals)) + ' |')
    emit(out / 'cutoff-table.md', '\n'.join(lines) + '\n')
    print('Cutoff pairs:', len(derived), 'boundary rows:', len(boundary))

def recovery_confirm():
    names = ['base-confirm', 'greedy-confirm']
    groups = []
    for name in names:
        assert (out / (name + '.exit')).read_text().strip() == '0'
        raw = json.loads((out / (name + '.json')).read_text())
        by = {tuple((r['params'][k] for k in ['representation', 'scenario'])): r for r in raw}
        assert len(by) == len(raw) == 6
        groups.append(by)
    assert groups[0].keys() == groups[1].keys()
    rows = []
    for key in sorted(groups[0]):
        a = groups[0][key]['primaryMetric']
        b = groups[1][key]['primaryMetric']
        rows.append({'representation': key[0], 'scenario': key[1], 'main': a, 'candidate': b, 'ratio': b['score'] / a['score']})
    emit(out / 'recovery-confirm-rows.json', json.dumps(rows, indent=2) + '\n')
    print('Reversed-order recovery pairs:', len(rows))

def cutoff_long():
    before_manifest, before = cutoff_read("cutoff-0-long-512")
    after_manifest, after = cutoff_read("cutoff-2048-long-512")
    if before.keys() != after.keys() or len(before) != 18:
        raise ValueError("Long cutoff membership differs")
    changes = {name for name in before_manifest["sourceSha256"].keys()
               | after_manifest["sourceSha256"].keys()
               if before_manifest["sourceSha256"].get(name)
               != after_manifest["sourceSha256"].get(name)}
    if changes != {"safere/src/main/java/org/safere/Matcher.java"}:
        raise ValueError("Unexpected long-comparison source differences")
    rows = []
    for key in sorted(before):
        a = before[key]["primaryMetric"]
        b = after[key]["primaryMetric"]
        rows.append({"trial": key[0], "operation": key[1], "profile": key[2],
                     "disabled": a, "extended": b, "ratio": b["score"] / a["score"]})
    emit(out / "cutoff-long-rows.json", json.dumps(rows, indent=2) + "\n")
    lines = ["| Input | Operation | Every-hit text | One-in-four text | Rejection |",
             "|---|---|---:|---:|---:|"]
    for engine in ["safere-string", "safere-utf8"]:
        for operation in ["matches", "lookingAt", "find"]:
            ratios = [next(row["ratio"] for row in rows
                           if row["trial"] == f"CaptureCutoffBenchmark.stack.512@{engine}"
                           and row["operation"] == operation and row["profile"] == profile)
                      for profile in ["text", "mixed", "reject"]]
            lines.append(f"| {engine} | {operation} | "
                         + " | ".join(f"{ratio:.3f}×" for ratio in ratios) + " |")
    emit(out / "cutoff-long-table.md", "\n".join(lines) + "\n")


def main():
    global CHECK
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="require byte-identical derived output")
    CHECK = parser.parse_args().check
    recovery()
    recovery_confirm()
    cutoffs()
    cutoff_long()
    print("All derived comparison artifacts verified." if CHECK else "Regenerated comparison artifacts.")


if __name__ == "__main__":
    main()
