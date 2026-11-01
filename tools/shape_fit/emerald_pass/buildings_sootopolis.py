"""Ten exact missing Sootopolis cone-tower source patterns, local candidates."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path

from tools.shape_fit.emerald import building_trials as p
from tools.shape_fit.emerald_pass.buildings import BASELINE, BUNDLE, ROOT, digest
from tools.shape_fit.emerald_pass.buildings_west import box, material

OUT = ROOT / "local/model-gallery/emerald-pass-study/buildings-sootopolis"
MAP = "EM_SOOTOPOLIS_CITY"
# number, exact source rectangle origin, observed facade, RGB source hash.
PATTERNS = (
    (113, 27, 0, 'diamond', '26b47bf878e14fa2c881b1d345121f0bcb6ca0e5bf7d89e196a8803d3285ab6d'),
    (114, 51, 0, 'diamond', '6b0cb6343e9523a8ed6bfffa5331f2c15bff12e8f6b80ebe329ad796fc35494c'),
    (115, 22, 1, 'pointed', '252681d7f5e057b13dbd56b74a96b5ad6bc8d7654485446773476ca6e81d8370'),
    (116, 54, 1, 'pointed', 'c0addbc3176a9966eb2ac672247c9a40ccd32f3a3f4b69c3b7714f1b68bd04c2'),
    (117, 8, 2, 'door', '793273ef10a2d5fee5f5e465c5244726359c50dabf3e10c63e026393f1c9a3f0'),
    (118, 8, 13, 'door', '0bde7c9ff4039fa6ee4be0ecb405ffb786aae6c87cbb2354baac3d270e4a0a5d'),
    (119, 43, 13, 'door', 'd751cf739c9d892194268db14e93d2ce169555e8a5a12d73284743546adbbc90'),
    (120, 53, 15, 'diamond', '9870fdda24af1efcb02f262d97effba250c8d36606713a10ef5e35ccdef541f3'),
    (121, 0, 16, 'diamond', '567cd0da220152e611dc76e514a479461c38e0955e25a66d42112f79d1695bd9'),
    (122, 53, 33, 'diamond', '6f5375cf623fcfaeff82e56e314ed7ab6c0ef3481d0a422eb9a082c487006e01'),
)


def tower(facade):
    """Rounded white tower, one continuous cone, native metal roof rings."""
    assert facade in ('door', 'diamond', 'pointed')
    # This cream texel is verified in door and both window variants. The door
    # drawing's x12,y58 is a gray jamb, not the wall material used by annexes.
    cream = material((15, 56, 16, 57))
    slate = material((24, 20, 25, 21))
    pale_slate = material((24, 43, 25, 44))
    seam = material((8, 34, 9, 35))
    parts = [
        p.round_part('cylinder', [24, 56], [22, 22], [0, 30], cream),
        p.round_part('cylinder', [24, 56], [22, 22], [0, 1], seam),
        p.round_part('cylinder', [24, 56], [23, 23], [29, 32], cream),
        p.round_part('cone', [24, 56], [24, 24], [32, 72], slate),
        # The source has three horizontal slate seams; these are rings on one
        # cone, not three disconnected pointed roofs.
        p.round_part('cylinder', [24, 56], [24, 24], [32, 34], seam),
        p.round_part('cylinder', [24, 56], [23, 23], [34, 36], pale_slate),
        p.round_part('cylinder', [24, 56], [17, 17], [44, 46], seam),
        p.round_part('cylinder', [24, 56], [16, 16], [46, 48], pale_slate),
        p.round_part('cylinder', [24, 56], [10, 10], [56, 58], seam),
        p.round_part('cylinder', [24, 56], [9, 9], [58, 60], pale_slate),
    ]
    if facade == 'door':
        parts.extend([
            box((9, 13), (70, 75), (0, 27), p.paint([8, 56, 14, 79], side=[15, 56, 16, 57])),
            box((35, 39), (70, 75), (0, 27), p.paint([34, 56, 40, 79], side=[15, 56, 16, 57])),
            box((14, 34), (73, 78), (0, 29), p.paint([14, 56, 34, 80], side=[15, 56, 16, 57])),
            box((16, 32), (70, 80), (0, 25), carve=True),
            box((16, 32), (70, 71), (0, 25), p.paint([16, 63, 32, 80], side=[16, 63, 17, 80])),
            box((14, 34), (71, 79), (0, 2), material((17, 78, 19, 80))),
            box((16, 32), (72, 76), (25, 29), p.paint([16, 58, 32, 63], side=[16, 58, 17, 63])),
        ])
    else:
        # Isolate the actual window on the cream wall. Projecting the whole
        # source facade around a cylinder repeated its drawn roof crescent and
        # slanted outer edge as vertical streaks on the curved side.
        parts.append(box((16, 32), (76, 78), (7, 26),
                         p.paint([16, 58, 32, 77], side=[15, 56, 16, 57])))
    return {'height': 76, 'parts': parts}


def quarters(layout):
    result = []
    for row in layout:
        cells = [list(map(int, key.split('/')[1].split('-'))) for key in row]
        result.extend([[n for cell in cells for n in cell[:2]], [n for cell in cells for n in cell[2:]]])
    return result


def source_cells(art, x, y):
    source = art.maps[MAP]; raw = base64.b64decode(source['cells']); grid = art.map_cells(source)
    result = []
    for dy in range(5):
        for dx in range(3):
            i = ((y + dy) * source['w'] + x + dx) * 4
            result.append({'x': x + dx, 'y': y + dy, 'local': [dx, dy], 'key': grid[y + dy][x + dx],
                           'metatile': int.from_bytes(raw[i:i + 2], 'little'),
                           'collision_byte': raw[i + 2], 'elevation': raw[i + 3]})
    return result


def generate(art=None):
    from tools.model_gallery import EmeraldArt
    art = art or EmeraldArt(BUNDLE); grid = art.map_cells(art.maps[MAP]); result = {}
    for number, x, y, facade, expected in PATTERNS:
        layout = [row[x:x + 3] for row in grid[y:y + 5]]
        drawing = art.drawing(layout, 'BAKED').convert('RGB')
        assert hashlib.sha256(drawing.tobytes()).hexdigest() == expected
        cells = source_cells(art, x, y)
        # Top row includes real cliff/ground, not a physical roof carrier.
        # WALK roof projections remain terrain too. All authored parts sit in
        # the common lower three rows, avoiding all such columns before clipping.
        terrain = [[dx, 0] for dx in range(3)]
        terrain.extend(c['local'] for c in cells if c['local'][1] > 0 and c['collision_byte'] == 0)
        name = f'emerald_b{number:03}_{MAP}'
        result[name] = {'id': name, 'atlas': 'g3q', 'tiles': quarters(layout), 'terrain': terrain, **tower(facade)}
    return result


def main():
    from PIL import Image, ImageDraw
    import numpy as np
    from tools import model_gallery
    from tools.shape_fit.emerald import parts_model
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=OUT)
    parser.add_argument('--previews', action='store_true')
    args = parser.parse_args()
    art = model_gallery.EmeraldArt(BUNDLE); baseline = json.loads(BASELINE.read_text()); additions = generate(art)
    assert not {b['id'] for b in baseline['buildings']} & set(additions)
    assert all(new['tiles'] != old['tiles'] for new in additions.values() for old in baseline['buildings'])
    catalog = model_gallery.building_catalog({'game': 'emerald', 'cells': {}, 'buildings': list(additions.values())}, art)
    assert sum(len(item['occurrences']) for item in catalog) == 11
    args.out.mkdir(parents=True, exist_ok=True); ledger = []; placements = []
    map_image = art.map_image(MAP).convert('RGB'); map_image.save(args.out / 'whole-map.png')
    for item, (_, x, y, facade, expected) in zip(catalog, PATTERNS):
        name = item['id']; entry = additions[name]; target = args.out / name; target.mkdir(exist_ok=True)
        source = p.drawing(entry, BUNDLE); assert hashlib.sha256(source.tobytes()).hexdigest() == expected
        source.save(target / 'source.png')
        source.resize((288, 480), Image.Resampling.NEAREST).save(target / 'source-enlarged.png')
        # Black above/left the map is only context, never a source/paint pixel.
        context = map_image.crop((x * 16 - 16, y * 16 - 16, x * 16 + 64, y * 16 + 96))
        ImageDraw.Draw(context).rectangle((16, 16, 63, 95), outline='red')
        context.resize((320, 448), Image.Resampling.NEAREST).save(target / 'context.png')
        occurrences = []
        for place in item['occurrences']:
            warps = [warp for warp in art.maps[MAP].get('warps', []) if place['x'] <= warp['x'] < place['x'] + 3 and place['y'] <= warp['y'] < place['y'] + 5]
            occurrences.append({**place, 'width': 3, 'depth': 5, 'cells': source_cells(art, place['x'], place['y']), 'warps': warps})
        assert bool(occurrences[0]['warps']) == (facade == 'door')
        placements.append({'id': name, 'occurrences': occurrences, 'terrain': entry['terrain']})
        if args.previews:
            occ, color = parts_model.build(entry, np.asarray(source))
            parts_model.picture(occ, color, target / 'view.png', 4)
            parts_model.front_view(occ, color, target / 'front.png', 5)
            parts_model.front_view(np.transpose(occ, (0, 2, 1))[:, :, ::-1], np.transpose(color, (0, 2, 1, 3))[:, :, ::-1], target / 'side.png', 5)
        relative = str(target.relative_to(ROOT)).replace('\\', '/')
        ledger.append({'key': name, 'kind': 'building', 'decision': 'added', 'baseline_absent': True,
                       'baseline_entry_sha256': None, 'proposed_entry_sha256': digest(entry),
                       'label': f'Sootopolis slate cone tower {facade} {name[8:12]}', 'family': 'sootopolis-cone-tower',
                       'source_pixel_sha256': expected, 'source_pixel_mode': 'RGB', 'occurrences': occurrences,
                       'reason': 'Exact complete 3x5 tower source is absent from all112 original building templates. Author the rounded cream wall and continuous slate cone with three native-colored roof rings, keeping this exact cliff/ground/window/door source variant. Only source warp variants receive a recessed doorway; window annexes have none. Preserve background cliff row and WALK projection cells as terrain, and keep all geometry inside stable lower SOLID/door rows.',
                       'evidence': [{'source': f'{relative}/source.png', 'context': f'{relative}/context.png'},
                                    {'source_group': 'general__sootopolis', 'full_map': str((args.out / 'whole-map.png').relative_to(ROOT))},
                                    {'python_front': f'{relative}/front.png', 'python_side': f'{relative}/side.png', 'python_angled': f'{relative}/view.png'}],
                       'visual_status': 'source/map inspection complete; candidate visual/runtime QA pending', 'user_approved': False})
    (args.out / 'updates.json').write_text(json.dumps({'cells': {}, 'buildings': additions}, indent=2) + '\n')
    (args.out / 'added-ledger.json').write_text(json.dumps(ledger, indent=2) + '\n')
    (args.out / 'occurrences.json').write_text(json.dumps(placements, indent=2) + '\n')
    print(json.dumps({'added': len(additions), 'occurrences': sum(len(p['occurrences']) for p in placements), 'out': str(args.out)}))


if __name__ == '__main__':
    main()
