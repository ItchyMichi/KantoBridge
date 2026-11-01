"""Battle Palace arena: continuous source-painted floor, not vertical relief UVs."""
import argparse
import json
from pathlib import Path

import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import Model
from tools.shape_fit.emerald_pass.review import BASELINE, ROOT, entry_hash, read

REGISTRY = Path(__file__).with_name('arena_floor_registry.json')
MAP = 'EM_BATTLE_FRONTIER_BATTLE_PALACE_BATTLE_ROOM'
OUT = ROOT / 'local/model-gallery/emerald-pass-study/arena'


def generate(table):
    updates = {}
    for key, spec in read(REGISTRY).items():
        old = table['cells'][key]
        if entry_hash(old) != spec['baseline_entry_sha256']:
            raise ValueError('Arena source changed: ' + key)
        model = Model()
        # Retain the existing half-block platform thickness. The drawing is a
        # floor plan, so each native texel addresses x/z rather than x/height.
        model.box((0, 16), (0, 4), (0, 16))
        _, z, x = np.indices(model.occ.shape)
        model.paint = (z * 16 + x).astype(np.uint8)
        updates[key] = model.entry(old, source='hand-emerald-palace-arena')
    return updates


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--baseline', type=Path, default=BASELINE)
    ap.add_argument('--out', type=Path, default=OUT)
    args = ap.parse_args()
    table, registry = read(args.baseline), read(REGISTRY)
    art = EmeraldArt(ROOT / 'local/minecraft/kantobridge/export/emerald')
    patches = generate(table)
    grid = art.map_cells(art.maps[MAP])
    layout = [row[4:11] for row in grid[3:8]]
    if {k for row in layout for k in row} != set(registry):
        raise ValueError('Arena assembly no longer matches the inspected map rectangle')
    entity = {'id': 'emerald_battle_palace_arena_floor', 'kind': 'object (model as one)',
              'note': 'Battle Palace full 7 by 5 arena floor mosaic', 'layout': layout}
    rows = []
    for key, spec in registry.items():
        rows.append(dict(spec, key=key, kind='cell', decision='changed',
            reason='Repainted the existing grounded half-block platform across x/z. The arena circle and border now join across the full 7 by 5 floor instead of becoming vertical stripes on top.',
            occurrences=art.locations[key], assembly_identity=entity['id'],
            evidence=['source-map.png', 'whole-source.png', 'assembly.json']))
    args.out.mkdir(parents=True, exist_ok=True)
    art.map_image(MAP).save(args.out / 'source-map.png')
    art.drawing(layout, 'BAKED').save(args.out / 'whole-source.png')
    write_json(args.out / 'updates.json', {'cells': patches, 'buildings': {}})
    write_json(args.out / 'ledger.json', rows)
    write_json(args.out / 'entities.json', [entity])
    write_json(args.out / 'assembly.json', {'map': MAP, 'x': 4, 'y': 3, 'width': 7, 'depth': 5, 'entity': entity})
    print(f'{len(patches)} arena tiles; one complete 35-cell source-verified floor')


if __name__ == '__main__':
    main()
