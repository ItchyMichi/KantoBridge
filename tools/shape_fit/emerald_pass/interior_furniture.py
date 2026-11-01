"""Map-audited chairs, benches, statue plinths, consoles and stage stairs."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import Model, decode, encode, render
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.review import BASELINE, ROOT, entry_hash, read
from tools.shape_fit.emerald_pass.scenery import BUNDLE

REGISTRY = Path(__file__).with_name('interior_furniture_registry.json')
OUT = ROOT / 'local/model-gallery/emerald-pass-study/interior-furniture'


def chair(sprite, role, section, mirror):
    depth = 32 if role == 'armchair' else 48
    m = Model(depth=depth)
    if role == 'sofa':
        cushion = material(sprite, (85, 90, 120), family='navy')
        edge = material(sprite, (45, 50, 80), family='navy')
        start, end = 9, 39
    else:
        cushion = material(sprite, (235, 125, 90), family='orange')
        edge = material(sprite, (185, 85, 60), family='orange')
        start, end = (6, 29) if role == 'armchair' else (3, 46)
    # Some middle upholstery cells contain only orange cloth and blue carpet.
    # Their feet use the local dark upholstery/wood shade, never carpet blue.
    leg = edge
    # All three furniture types face east; mirrored variants face west.
    m.box((3, 14), (5, 8), (start, end), cushion)
    m.box((1, 4), (5, 14), (start, end), edge)
    m.box((2, 4), (7, 13), (start+1, end-1), cushion)
    for z in ((start, start+3), (end-3, end)):
        m.box((3, 14), (8, 11), z, edge)
        for x in ((2, 4), (11, 13)):
            m.box(x, (0, 5), z, leg)
    # Long benches carry a central pair of feet. They remain independent of
    # any source-carpet color in the surrounding cell.
    if role == 'bench':
        for x in ((2, 4), (11, 13)):
            m.box(x, (0, 5), (23, 25), leg)
    if mirror:
        m.occ = m.occ[:, :, ::-1].copy()
        m.paint = m.paint[:, :, ::-1].copy()
    return m.occ[:, section*16:(section+1)*16], m.paint[:, section*16:(section+1)*16]


def statue(sprite, role):
    m = Model()
    if role == 'statue_crown':
        gold = material(sprite, (245, 215, 115), (2, 7, 14, 16))
        edge = material(sprite, (170, 145, 80), (2, 7, 14, 16))
        m.cylinder((8, 8), 3.4, (0, 2), edge)
        m.ellipsoid((8, 6, 8), (5.8, 6, 5.8), gold)
    else:
        body = material(sprite, (185, 175, 190), (3, 3, 13, 14))
        rim = material(sprite, (225, 220, 230), (3, 3, 13, 14))
        dark = material(sprite, (90, 90, 115), (3, 3, 13, 14))
        blue = material(sprite, (90, 220, 225), (3, 0, 13, 5))
        m.cylinder((8, 8), 5.6, (0, 3), dark)
        m.cylinder((8, 8), 4.6, (3, 12), body)
        m.cylinder((8, 8), 5.5, (12, 14), rim)
        m.cylinder((8, 8), 4.8, (14, 16), dark)
        m.box((4, 12), (13, 14), (12, 13), blue)
    return m.occ, m.paint


def console(sprite, role):
    m = Model()
    dark = material(sprite, (70, 75, 100), (2, 0, 14, 8), 'neutral')
    if role == 'console_upper':
        m.box((1, 15), (0, 16), (4, 13)).project(side=dark, top=dark)
    else:
        m.box((1, 15), (7, 16), (4, 13)).project((0, 0, 16, 8), side=dark, top=dark)
        for x in ((2, 4), (12, 14)):
            for z in ((5, 7), (10, 12)):
                m.box(x, (0, 7), z, dark)
    return m.occ, m.paint


def generate(table, art):
    updates = {}
    for key, spec in read(REGISTRY).items():
        old = table['cells'][key]
        if entry_hash(old) != spec['baseline_entry_sha256']:
            raise ValueError('Reviewed furniture changed: ' + key)
        role, sprite = spec['role'], art.cell(key, 'BAKED')
        if role in ('armchair', 'sofa', 'bench'):
            occ, paint = chair(sprite, role, spec['section'], spec['mirror'])
        elif role.startswith('statue_'):
            occ, paint = statue(sprite, role)
        elif role.startswith('console_'):
            occ, paint = console(sprite, role)
        elif role == 'stairs':
            y, z, x = np.indices((16, 16, 16))
            whole_z = z + spec['section'] * 16
            # The native two-row drawing contains an upper landing followed
            # by three broad tread bands, not eight thin extrusion steps.
            height = np.where(whole_z < 16, 12, np.where(whole_z < 24, 8, 4))
            occ = y < height
            paint = (z*16 + x).astype(np.uint8)
            front = occ & ~np.concatenate((occ[:, 1:, :], np.zeros_like(occ[:, :1, :])), axis=1)
            riser_row = np.where(spec['section'] == 0, 12, np.where(z < 8, 4, 12))
            riser = front & (y < height - 1)
            paint[riser] = (riser_row*16 + x)[riser]
        elif role == 'floor':
            occ, _ = decode(old)
            _, z, x = np.indices(occ.shape)
            paint = (z*16 + x).astype(np.uint8)
        else:
            raise ValueError(role)
        entry = encode(occ, paint, old, source='hand-emerald-interior-furniture')
        if spec.get('remove_on'):
            assert entry.get('on') == spec['remove_on']
            del entry['on']
        updates[key] = entry
    return updates


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out', type=Path, default=OUT)
    args = ap.parse_args()
    table, registry, art = read(BASELINE), read(REGISTRY), EmeraldArt(BUNDLE)
    updates = generate(table, art)
    rows = []
    for key, spec in registry.items():
        rows.append(dict(spec, key=key, kind='cell', decision='changed', occurrences=art.locations[key]))
        render(updates[key], art.cell(key, 'BAKED'), args.out/'models'/key.split('/')[1])
    write_json(args.out/'updates.json', {'cells': updates, 'buildings': {}})
    write_json(args.out/'ledger.json', rows)
    assemblies = read(REGISTRY.with_name('interior_furniture_assemblies.json'))
    for assembly in assemblies:
        layout = assembly['entity']['layout']
        for place in assembly['occurrences']:
            grid = art.map_cells(art.maps[place['map']])
            if any(grid[place['y']+z][place['x']:place['x']+len(row)] != row for z, row in enumerate(layout)):
                raise ValueError('Source assembly changed: ' + assembly['entity']['id'])
        art.drawing(layout, 'BAKED').save(args.out/(assembly['entity']['id']+'-source.png'))
        from tools.shape_fit import hand_check
        stack = assembly['entity']['kind'].startswith('stack')
        height, depth, width = (len(layout)*16 if stack else 16), (16 if stack else len(layout)*16), len(layout[0])*16
        occ = np.zeros((height, depth, width), bool)
        colors = np.zeros((*occ.shape, 3), np.uint8)
        for row, values in enumerate(layout):
            for col, key in enumerate(values):
                cell, paint = decode(updates[key])
                sprite = np.asarray(art.cell(key, 'BAKED').convert('RGB'))
                y, z = ((len(layout)-row-1)*16, 0) if stack else (0, row*16)
                occ[y:y+16, z:z+16, col*16:col*16+16] = cell
                colors[y:y+16, z:z+16, col*16:col*16+16] = sprite[paint//16, paint%16]
        target = args.out/'assemblies'/assembly['entity']['id']
        target.mkdir(parents=True, exist_ok=True)
        hand_check.picture(occ, colors, target/'angled.png', 7)
        hand_check.front(occ, colors, target/'front.png', 8)
        hand_check.front(occ.transpose(0, 2, 1), colors.transpose(0, 2, 1, 3), target/'side.png', 8)
    write_json(args.out/'entities.json', [a['entity'] for a in assemblies])
    write_json(args.out/'assembly-placements.json', assemblies)
    print(f'{len(updates)} explicit furniture, stair and floor sections')


if __name__ == '__main__':
    main()
