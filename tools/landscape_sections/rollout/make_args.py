"""The rollout workflows' args (docs/specs/LANDSCAPE_ROLLOUT_PLAN.md), split into <runs> files balanced by work.

    python3 make_args.py mark <runs>      maps in rollout/to_mark.json -> rollout/mark_run<N>_args.json
    python3 make_args.py model <runs>     units in rollout/spots_units.json -> rollout/model_run<N>_args.json (a batch: a map's
                                          units, at most 6 of them)
<runs> "auto": as many runs as make about 10 items each (the workflow-size guideline).
"""
import json
import math
import os
import sys

W = '/home/claude/kb-hub/work/landscape'
R = f'{W}/rollout'


def game(m):
    return 'emerald' if m.startswith('EM_') else 'crystal' if m.startswith('G2_') else 'blue'


def split(rows, runs, weight, prefix):
    if runs == 'auto':
        runs = max(1, math.ceil(len(rows) / 10))
    runs = int(runs)
    rows.sort(key=lambda r: -weight(r))
    buckets, loads = [[] for _ in range(runs)], [0] * runs
    for r in rows:
        i = loads.index(min(loads))
        buckets[i].append(r)
        loads[i] += weight(r)
    for i, b in enumerate(buckets):
        json.dump(b, open(f'{R}/{prefix}_run{i + 1}_args.json', 'w'), indent=1)
        print(f'{prefix}_run{i + 1}', len(b), 'items', round(loads[i]))


def main(argv):
    mode, runs = argv[1], argv[2]
    if mode == 'mark':
        rows = []
        for m in json.load(open(f'{R}/to_mark.json')):
            reg = json.load(open(f'{W}/regions/{m}.json'))
            chunks = json.load(open(f'{W}/chunks/{m}_chunks.json'))['chunks']
            rows.append({'map': m, 'game': game(m), 'w': reg['w'], 'h': reg['h'], 'regions': len(reg['regions']), 'chunks': len(chunks)})
        split(rows, runs, lambda r: r['chunks'] + r['regions'] / 20, 'mark')
    elif mode == 'model':
        spots = json.load(open(f'{R}/spots_units.json'))
        per = {}
        for name in sorted(spots, key=lambda n: (spots[n]['map'], int(n.split('__u')[1]))):
            per.setdefault(spots[name]['map'], []).append(name)
        rows = []
        for m, names in sorted(per.items()):
            n = math.ceil(len(names) / 6)
            for k in range(n):
                batch = names[k::n]
                rows.append({'map': m, 'game': game(m), 'units': batch, 'model': {u: spots[u]['model'] for u in batch}})
        split(rows, runs, lambda r: sum(len(v) for v in r['model'].values()), 'model')
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
