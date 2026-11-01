"""Explicit shared interior bins, stools, plants and supported television.

The registry is selected by source/map inspection, never by image extrusion.
Every material remains an address in the corresponding native source cell.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import Model, encode, render, uv
from tools.shape_fit.emerald_pass.review import BASELINE, ROOT, entry_hash, read
from tools.shape_fit.emerald_pass.scenery import BUNDLE

OUT = ROOT / 'local/model-gallery/emerald-pass-study/interior-props'
REGISTRY = Path(__file__).with_name('interior_props_registry.json')


def material(sprite, rgb, rect=(0, 0, 16, 16), family=None):
    pixels = np.asarray(sprite.convert('RGB')).astype(int)
    x0, z0, x1, z1 = rect
    distances = ((pixels[z0:z1, x0:x1] - rgb) ** 2).sum(axis=2)
    region = pixels[z0:z1, x0:x1]
    if family == 'green':
        allowed = (region[..., 1] > region[..., 0] + 5) & (region[..., 1] > region[..., 2] + 5)
    elif family == 'neutral':
        allowed = (abs(region[..., 0] - region[..., 1]) < 20) & (region[..., 2] >= region[..., 0] - 15)
    elif family == 'orange':
        allowed = (region[..., 0] > region[..., 1] + 40) & (region[..., 1] > region[..., 2] + 10)
    elif family == 'navy':
        allowed = (region[..., 2] > region[..., 0]) & (region[..., 1] < 130) & (region[..., 2] < 185)
    else:
        allowed = np.ones(distances.shape, bool)
    if not allowed.any():
        raise ValueError('Source region has no requested material: ' + str(family))
    distances = np.where(allowed, distances, 10**9)
    z, x = np.unravel_index(distances.argmin(), distances.shape)
    return uv(x + x0, z + z0)


def bin_model(sprite):
    m = Model()
    dark = material(sprite, (70, 75, 100), (4, 3, 12, 12))
    body = material(sprite, (160, 170, 185), (3, 7, 13, 14))
    light = material(sprite, (245, 245, 255), (3, 1, 13, 8))
    m.cylinder((8, 8), 4, (0, 2), dark)
    m.cylinder((8, 8), 5, (2, 12), body)
    m.cylinder((8, 8), 5.6, (12, 14), light)
    m.cylinder((8, 8), 3, (12, 16), cut=True)
    m.cylinder((8, 8), 3, (10, 12), dark)
    return m


def stool_model(sprite):
    m = Model()
    metal = material(sprite, (75, 80, 110), (4, 10, 12, 16), 'neutral')
    highlight = material(sprite, (175, 180, 195), (4, 10, 12, 16), 'neutral')
    cushion = material(sprite, (205, 125, 110), (3, 2, 13, 9))
    rim = material(sprite, (180, 100, 80), (3, 7, 13, 11))
    m.cylinder((8, 8), 4.5, (0, 2), metal)
    m.cylinder((8, 8), 2, (2, 7), highlight)
    m.cylinder((8, 8), 5.5, (7, 9), rim)
    m.ellipsoid((8, 9, 8), (5.7, 2.3, 5.7), cushion)
    return m


def plant_model(sprite, role):
    m = Model()
    stem = material(sprite, (90, 120, 55), (4, 0, 12, 12), 'green')
    if role in ('plant_crown', 'pedestal_crown'):
        # Full plant spans two vertical cells. Its upper stem joins the lower
        # stem at y=16; asymmetric leaf lobes make an open crown, not a card.
        tones = [material(sprite, c, (2, 2, 14, 14), 'green') for c in
                 ((55, 105, 50), (110, 165, 70), (155, 200, 90))]
        m.box((7, 9), (0, 10), (7, 9), stem)
        for x, y, z, rx, ry, rz, tone in (
            (5, 5, 8, 4.5, 3, 3, 0), (11, 6, 8, 4, 3, 3, 1),
            (8, 7, 4, 3, 4, 3.5, 1), (8, 7, 12, 3, 4, 3.5, 0),
            (6, 10, 6, 3, 4.5, 3, 2), (10, 10, 10, 3, 4, 3, 1),
            (8, 12, 8, 2.5, 3.5, 2.5, 2)):
            m.ellipsoid((x, y, z), (rx, ry, rz), tones[tone])
        return m
    soil = material(sprite, (70, 70, 85), (4, 3, 12, 10))
    body = material(sprite, (165, 155, 165), (4, 9, 12, 14))
    rim = material(sprite, (240, 240, 245), (2, 3, 14, 11))
    if role == 'pedestal_base':
        # The Contest planter has a brass bowl above a grey stem/white foot.
        # Its top source rows carry the bowl; lower rows carry only the stand.
        brass = material(sprite, (200, 155, 105), (0, 0, 16, 7), 'orange')
        brass_rim = material(sprite, (250, 205, 145), (0, 0, 16, 7), 'orange')
        m.cylinder((8, 8), 4.8, (0, 2), rim)
        m.cylinder((8, 8), 2, (2, 10), body)
        m.cylinder((8, 8), 5.7, (10, 13), brass)
        m.cylinder((8, 8), 6.4, (13, 15), brass_rim)
        m.cylinder((8, 8), 4.8, (14, 15), soil)
        m.box((7, 9), (14, 16), (7, 9), stem)
    else:
        m.cylinder((8, 8), 3.5, (0, 2), body)
        m.cylinder((8, 8), 4.5, (2, 9), body)
        m.cylinder((8, 8), 5.4, (9, 12), rim)
        m.cylinder((8, 8), 4.1, (11, 12), soil)
        m.box((7, 9), (12, 16), (7, 9), stem)
    return m


def television(sprite, role):
    m = Model()
    metal = material(sprite, (85, 85, 110))
    if role == 'television':
        m.box((1, 15), (1, 15), (4, 12)).project(side=metal, top=material(sprite, (190, 190, 180)))
        m.box((4, 12), (0, 1), (5, 11), metal)
    else:
        m.box((1, 15), (7, 16), (3, 13)).project((0, 0, 16, 8), side=metal, top=metal)
        for x in ((2, 4), (12, 14)):
            for z in ((4, 6), (10, 12)):
                m.box(x, (0, 7), z, metal)
    return m


def generate(table, art):
    updates = {}
    for key, spec in read(REGISTRY).items():
        old = table['cells'][key]
        if entry_hash(old) != spec['baseline_entry_sha256']:
            raise ValueError('Interior source changed: ' + key)
        sprite, role = art.cell(key, 'BAKED'), spec['role']
        model = bin_model(sprite) if role == 'bin' else stool_model(sprite) if role == 'stool' else (
            television(sprite, role) if role in ('television', 'tv_cabinet') else plant_model(sprite, role))
        updates[key] = model.entry(old, source='hand-emerald-interior-props')
    return updates


def assemblies(art, registry):
    pairs = {}
    crowns = {k for k, v in registry.items() if v['role'] in ('television', 'plant_crown', 'pedestal_crown')}
    bases = {k for k, v in registry.items() if v['role'] in ('tv_cabinet', 'plant_base', 'pedestal_base')}
    for map_id, data in art.maps.items():
        grid = art.map_cells(data)
        for y in range(len(grid) - 1):
            for x, key in enumerate(grid[y]):
                lower = grid[y+1][x]
                if key in crowns and lower in bases:
                    pairs.setdefault((key, lower), []).append({'map': map_id, 'x': x, 'y': y})
    entities, places = [], []
    for (top, bottom), locations in sorted(pairs.items()):
        signature = json.dumps([[[top], [bottom]], 'stack'], separators=(',', ':'))
        identity = 'emerald_compound_' + hashlib.sha256(signature.encode()).hexdigest()[:12]
        # Vertical stack matches the preserved on relationship; the source
        # drawing still shows the two neighboring north/south cells.
        entity = {'id': identity, 'kind': 'stack (model as one)',
                  'note': registry[top]['label'] + ' with complete support', 'layout': [[top], [bottom]]}
        entities.append(entity)
        places.append({'id': identity, 'layout': entity['layout'], 'occurrences': locations})
    return entities, places


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out', type=Path, default=OUT)
    args = ap.parse_args()
    table, registry, art = read(BASELINE), read(REGISTRY), EmeraldArt(BUNDLE)
    updates = generate(table, art)
    rows = []
    for key, spec in registry.items():
        rows.append(dict(spec, key=key, kind='cell', decision='changed', occurrences=art.locations[key],
                         evidence=['interiors/props-source.png', 'inventory/shared-building/' + spec['contact_page']]))
        render(updates[key], art.cell(key, 'BAKED'), args.out / 'models' / key.split('/')[1])
    entities, placements = assemblies(art, registry)
    write_json(args.out / 'updates.json', {'cells': updates, 'buildings': {}})
    write_json(args.out / 'ledger.json', rows)
    write_json(args.out / 'entities.json', entities)
    write_json(args.out / 'assembly-placements.json', placements)
    print(f'{len(updates)} interior props and {len(entities)} whole assemblies')


if __name__ == '__main__':
    main()
