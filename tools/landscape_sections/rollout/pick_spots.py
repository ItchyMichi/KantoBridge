"""Picture spots for the rollout's sheets (docs/specs/LANDSCAPE_ROLLOUT_PLAN.md, phase 7): per game the N biggest modelled
units (one a map), each seen from a walkable cell south of it, facing north.

    python3 pick_spots.py <N> <out dir>

Reads rollout/spots_units.json, models/<unit>/specs (a unit counts only with specs), the final dumps (levels_r7/levels_r7pin
for Hoenn and Johto, levels_r9/levels_r9pin for Kanto). Writes <out dir>/cells_<game>.txt and spots_<game>.json
(context_shots.py's: {name: {map, x, y, facing, ux, uy}}).
"""
import json
import os
import sys

W = '/home/claude/kb-hub/work/landscape'
DUMPS = {'emerald': f'{W}/levels_r7/levels_r7pin', 'crystal': f'{W}/levels_r7/levels_r7pin', 'blue': f'{W}/levels_r9/levels_r9pin'}


def game(m):
    return 'emerald' if m.startswith('EM_') else 'crystal' if m.startswith('G2_') else 'blue'


def main(argv):
    n, out = int(argv[1]), argv[2]
    os.makedirs(out, exist_ok=True)
    spots = json.load(open(f'{W}/rollout/spots_units.json'))
    picked = {'emerald': {}, 'crystal': {}, 'blue': {}}
    ranked = sorted(spots, key=lambda u: -(spots[u]['window'][2] * spots[u]['window'][3]))
    for u in ranked:
        s = spots[u]
        g, mid = game(s['map']), s['map']
        specs = f'{W}/models/{u}/specs'
        if len(picked[g]) >= n or any(v['map'] == mid for v in picked[g].values()):
            continue
        if not os.path.isdir(specs) or not any(f.endswith('.json') for f in os.listdir(specs)):
            continue
        d = json.load(open(f'{DUMPS[g]}/{mid}.json'))
        w, h, kinds = d['w'], d['h'], d['kinds']
        x0, y0, ww, wh = s['window']
        cx, bottom = x0 + ww // 2, y0 + wh
        stand = None
        for dy in range(0, 7):
            for dx in (0, -1, 1, -2, 2, -3, 3, -4, 4):
                x, y = cx + dx, bottom + dy
                if 0 <= x < w and 0 <= y < h and kinds[y * w + x] == '.':
                    stand = (x, y)
                    break
            if stand:
                break
        if stand:
            picked[g][f'{mid}_ROCK'] = {'map': mid, 'x': stand[0], 'y': stand[1], 'facing': 'up', 'ux': cx, 'uy': y0 + wh // 2}
    for g, chosen in picked.items():
        with open(f'{out}/cells_{g}.txt', 'w') as fh:
            fh.write(''.join(name + '\n' for name in chosen))
        json.dump(chosen, open(f'{out}/spots_{g}.json', 'w'))
        print(g, len(chosen), list(chosen))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
