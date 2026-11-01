"""Individually inspected building-interior batches, with map-bound assemblies.

The registry is authored after looking at source, model projections and maps.
Source grouping never assigns a decision. Outputs are local patches, not writes
to the fitted resource. Orthographic renders are authoring aids, not runtime QA.
"""
from __future__ import annotations

import argparse
import copy
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

from tools.model_gallery import EmeraldArt, safe_id, write_json
from tools.shape_fit.blue_pass.common import Model, decode, encode
from tools.shape_fit.emerald_pass.inventory import contact_pages
from tools.shape_fit.emerald_pass.review import BASELINE, ROOT, entry_hash, read

REGISTRY = Path(__file__).with_name('building_interior_registry.json')
OUT = ROOT / 'local/model-gallery/emerald-pass-study/building-interiors'
BUNDLE = ROOT / 'local/minecraft/kantobridge/export/emerald'


def material(sprite, rgb):
    """Choose a native source address for an explicitly named part material."""
    pixels = np.asarray(sprite.convert('RGB')).astype(float)
    delta = ((pixels - rgb) ** 2).sum(axis=2)
    return int(np.argmin(delta))


def weather_terminal_cap(old):
    """Keep the authored curved PC rim; omit the separate room cornice above it."""
    occ,paint=decode(old)
    # Exact inspected source component: the rim occupies y0..7. The white
    # room-wall cornice at y11..15 is disconnected across three empty layers.
    assert occ.shape==(16,16,16) and not occ[8:11].any()
    occ=occ.copy();occ[8:]=False
    return occ,paint


def weather_terminal_floor(sprite):
    """Native gray floor below the PC, excluding its projected vent/cabinet."""
    model=Model();model.box((0,16),(0,1),(0,16))
    y,z,x=np.indices(model.occ.shape)
    return model.occ,((8+z//2)*16+x).astype(np.uint8)


def chair(orientation, sprite):
    model = Model()
    metal = material(sprite, (55, 61, 65))
    green = material(sprite, (110, 210, 83))
    edge = material(sprite, (58, 133, 54))
    for x in (3, 11):
        for z in (4, 11):
            model.box((x, x+2), (0, 7), (z, z+2), metal)
    model.box((2, 14), (6, 8), (3, 14), edge)
    model.box((3, 13), (8, 9), (4, 13), green)
    model.box((2, 4), (8, 15), (3, 5), metal)
    model.box((12, 14), (8, 15), (3, 5), metal)
    model.box((4, 12), (9, 15), (3, 5), green)
    turns = {'north': 0, 'east': 3, 'west': 1}[orientation]
    model.occ = np.rot90(model.occ, turns, axes=(1, 2)).copy()
    model.paint = np.rot90(model.paint, turns, axes=(1, 2)).copy()
    return model


def plant():
    """One grounded pot with nine separate arching fronds, before slicing."""
    model = Model(height=32)
    model.cylinder((8, 8), 3.6, (0, 2), 0)
    model.cylinder((8, 8), 4.4, (2, 6), 0)
    model.cylinder((8, 8), 5, (5, 7), 1)
    model.cylinder((8, 8), 3.5, (6, 7), 2)
    model.cylinder((8, 8), 1.2, (7, 14), 3)
    tips=[(1,13,8),(15,13,8),(8,13,1),(8,13,15),
          (3,17,3),(13,17,3),(3,17,13),(13,17,13),(8,23,8)]
    for index,tip in enumerate(tips):
        for t in np.linspace(0,1,15):
            point=np.array((8,10,8))*(1-t)+np.array(tip)*t
            point[1]+=3*np.sin(np.pi*t)
            width=.55+1.05*np.sin(np.pi*t)
            model.ellipsoid(point,(width,.8,width),3 if t<.65 else 4)
    return model


def plant_cell(recipe, sprite):
    full = plant()
    start = 16 if recipe == 'plant_cap' else 0
    colors = [material(sprite, c) for c in [(173, 66, 58), (230, 214, 74),
                                           (115, 99, 41), (82, 181, 66), (140, 230, 115)]]
    return full.occ[start:start+16], np.asarray(colors, np.uint8)[full.paint[start:start+16]]


def bed():
    """Weather Institute 2-by-2 bed, one horizontal frame with air under it."""
    model = Model(width=32, depth=32)
    for x in (5, 25):
        for z in (3, 28):
            model.box((x, x+2), (0, 5), (z, z+2), 0)
    model.box((4, 28), (4, 7), (2, 31), 0)
    model.box((5, 27), (7, 10), (4, 29), 1)
    model.box((4, 28), (7, 15), (2, 4), 0)
    model.box((6, 26), (10, 11), (14, 29), 2)
    for x in (7, 17):
        model.box((x, x+8), (10, 12), (6, 12), 1)
    model.box((4, 28), (7, 11), (29, 31), 0)
    return model


def bookcase(wall=False, box_offset=None):
    """One 32-wide cabinet, with open shelves and separate spines, before slicing.

    Wall shelves use two source rows; the separate crate/floor below are not
    part of their stack. Free shelves include a third projected cap/parcel row.
    """
    model=Model(height=32 if wall else 48,width=32)
    roof=26 if wall else 34
    model.box((1,31),(0,2),(2,14),0)
    model.box((1,3),(0,roof+2),(2,14),0)
    model.box((29,31),(0,roof+2),(2,14),0)
    model.box((3,29),(1,roof),(2,4),0)
    model.box((15,17),(2,roof),(4,14),0)
    model.box((1,31),(roof,roof+2),(2,14),1)
    model.box((3,29),(18 if wall else 26,roof),(12,14),2)
    shelves=(4,10,16) if wall else (8,18,26)
    for y in shelves:model.box((3,29),(y,y+2),(4,14),1)
    model.box((3,29),(2,4 if wall else 8),(11,14),8 if wall else 1)
    for level,y in enumerate(shelves[:-1]):
        for n,x in enumerate((4,7,10,12,18,21,24,27)):
            high=(5+(n+level)%3) if wall else (6+(n+level)%3)
            model.box((x,min(x+2,29)),(y+2,min(y+2+high,shelves[level+1])),(8,13),3+n%3)
    if box_offset is not None:
        model.box((box_offset,box_offset+8),(36,44),(5,12),6)
        model.box((box_offset+3,box_offset+4),(36,44),(5,12),7)
    return model


def bookcase_cell(recipe,sprite):
    _,family,x,layer=recipe.split('_')
    wall=family=='wall';x,layer=int(x)*16,int(layer)
    offset=3 if family=='boxleft' else 7 if family=='boxcenter' else None
    full=bookcase(wall,offset)
    colors=[material(sprite,c) for c in [(66,66,82),(140,140,148),(255,255,255),
        (66,123,181),(173,66,58),(82,181,66),(239,230,123),(173,148,41),(140,140,148)]]
    occ=full.occ[layer*16:layer*16+16,:,x:x+16]
    paint=np.asarray(colors,np.uint8)[full.paint[layer*16:layer*16+16,:,x:x+16]]
    # Native fronts retain frame/door details; explicit book spines use their
    # own native colours so projected white header pixels cannot stain them.
    y,z,xx=np.indices(occ.shape)
    semantic=full.paint[layer*16:layer*16+16,:,x:x+16]
    front=(z>=12)&np.isin(semantic,[0,1])
    paint[front]=((15-y)*16+xx)[front]
    return occ,paint


def crate(sprite):
    model=Model()
    yellow=material(sprite,(239,230,123));edge=material(sprite,(173,148,41))
    model.box((2,14),(0,13),(3,14),yellow)
    model.box((2,14),(12,14),(3,14),edge)
    model.box((7,9),(0,14),(2,15),edge)
    return model


def generate(table, art, registry=None):
    registry = registry or read(REGISTRY)
    updates = {}
    bed_model = bed()
    for row in registry['entries']:
        key, recipe = row['key'], row.get('recipe')
        old = table['cells'][key]
        if entry_hash(old) != row['baseline_entry_sha256']:
            raise ValueError('Inspected source entry changed: ' + key)
        if not recipe:
            continue
        sprite = art.cell(key, 'BAKED')
        if recipe.startswith('chair_'):
            model = chair(recipe.split('_', 1)[1], sprite)
            occ, paint = model.occ, model.paint
        elif recipe.startswith('plant_'):
            occ, paint = plant_cell(recipe, sprite)
        elif recipe.startswith('bed_'):
            _, x, z = recipe.split('_')
            x, z = int(x)*16, int(z)*16
            occ = bed_model.occ[:, z:z+16, x:x+16]
            addresses = [material(sprite, c) for c in [(87, 92, 104), (247, 247, 237), (255, 189, 66)]]
            paint = np.asarray(addresses, np.uint8)[bed_model.paint[:, z:z+16, x:x+16]]
        elif recipe.startswith('shelf_'):
            occ,paint=bookcase_cell(recipe,sprite)
        elif recipe=='wall_crate':
            model=crate(sprite);occ,paint=model.occ,model.paint
        elif recipe=='floor_projection':
            model=Model();model.box((0,16),(0,1),(0,16))
            occ=model.occ;y,z,x=np.indices(occ.shape);paint=((8+z//2)*16+x).astype(np.uint8)
        elif recipe=='weather_terminal_cap':
            occ,paint=weather_terminal_cap(old)
        elif recipe=='weather_terminal_floor':
            occ,paint=weather_terminal_floor(sprite)
        else:
            raise ValueError('Unknown explicit interior recipe: ' + recipe)
        updates[key] = encode(occ, paint, old, source='hand-emerald-interior-review', trim=True)
        if row.get('added_on'):updates[key]['on']=row['added_on']
        if row.get('added_walk'):updates[key]['walk']=True
    return updates


def assembly_views(layout, table, art, vertical=False, placements=None):
    heights = [decode(table['cells'][k])[0].shape[0] for row in layout for k in row if k]
    h = max(heights) + (len(layout)-1)*16 if vertical else max(heights)
    d, w = (16 if vertical else len(layout)*16), len(layout[0])*16
    custom={tuple(p['source']):tuple(v*16 for v in p['offset']) for p in placements} if placements is not None else None
    if custom is not None:
        w=max(p[0]+16 for p in custom.values());d=max(p[2]+16 for p in custom.values())
        h=max(p[1]+decode(table['cells'][layout[z][x]])[0].shape[0] for (x,z),p in custom.items())
    occ, colors = np.zeros((h, d, w), bool), np.zeros((h, d, w, 3), np.uint8)
    for z, row in enumerate(layout):
        for x, key in enumerate(row):
            if not key:
                continue
            o, p = decode(table['cells'][key])
            y0, z0 = ((len(layout)-1-z)*16, 0) if vertical else (0, z*16)
            x0=x*16
            if custom is not None:x0,y0,z0=custom[(x,z)]
            sl = np.s_[y0:y0+len(o), z0:z0+16, x0:x0+16]
            occ[sl] = o
            colors[sl] = np.asarray(art.cell(key, 'BAKED').convert('RGB'))[p//16, p%16]
    result = []
    for axis in (1, 2, 0):
        visible = occ.any(axis=axis)
        ix = occ.shape[axis]-1-np.argmax(np.flip(occ, axis), axis=axis)
        coords = iter(np.indices(visible.shape))
        lookup = [ix if a == axis else next(coords) for a in range(3)]
        rgb = colors[tuple(lookup)]
        rgba = np.concatenate((rgb, (visible*255).astype(np.uint8)[..., None]), axis=2)
        if axis != 0:
            rgba = rgba[::-1]
        result.append(Image.fromarray(rgba))
    return result


def assembly_evidence(spec, table, art, directory):
    grid = art.map_cells(art.maps[spec['map']])
    x, y, w, d = spec['rect']
    layout = [row[x:x+w] for row in grid[y:y+d]]
    if layout != spec['source_layout']:
        raise ValueError('Assembly source adjacency changed: ' + spec['id'])
    # A stack uses the same source drawing, with its upper image row elevated.
    vertical = spec.get('kind', '').startswith('stack')
    entity = {k: copy.deepcopy(spec[k]) for k in ('id', 'kind', 'note')}
    model_layout=spec.get('model_layout',layout)
    entity['layout'] = model_layout
    if model_layout!=layout:entity['reference_layout']=layout
    if 'placements' in spec:entity['placements']=copy.deepcopy(spec['placements'])
    for field in ('baseline_layout','baseline_placements'):
        if field in spec:entity[field]=copy.deepcopy(spec[field])
    source = art.drawing(layout, 'BAKED')
    views = assembly_views(model_layout, table, art, vertical, spec.get('placements'))
    width = 900
    sheet = Image.new('RGB', (width, 290), '#e8ecef')
    draw = ImageDraw.Draw(sheet)
    draw.text((10, 8), spec['id'], fill='black')
    draw.text((10, 25), f"{spec['map']} ({x},{y}) {w}x{d}; SOURCE | FRONT | EAST | TOP", fill='black')
    for i, im in enumerate([source, *views]):
        scale = min(210/im.width, 225/im.height)
        im = im.resize((int(im.width*scale), int(im.height*scale)), Image.Resampling.NEAREST)
        sheet.paste(im, (i*225+(225-im.width)//2, 55+(225-im.height)//2), im if im.mode == 'RGBA' else None)
    sheet.save(directory / (spec['id']+'.png'))
    source.save(directory / (spec['id']+'-source.png'))
    return entity


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--baseline', type=Path, default=BASELINE)
    ap.add_argument('--out', type=Path, default=OUT)
    args = ap.parse_args()
    table, registry = read(args.baseline), read(REGISTRY)
    art = EmeraldArt(BUNDLE)
    updates = generate(table, art, registry)
    current = copy.deepcopy(table)
    current['cells'].update(updates)
    args.out.mkdir(parents=True, exist_ok=True)
    rows = []
    for item in registry['entries']:
        key = item['key']
        row = dict(item, kind='cell', decision='changed' if key in updates else 'retained',
                   occurrences=art.locations.get(key, []),
                   visual_status='Source/map and local projections inspected; Minecraft comparison pending')
        row['evidence'] = [item['source_contact'], *['assemblies/'+a+'.png' for a in item.get('assemblies', [])]]
        rows.append(row)
    write_json(args.out/'updates.json', {'cells': updates, 'buildings': {}})
    write_json(args.out/'ledger.json', rows)
    assembly_dir = args.out/'assemblies'
    assembly_dir.mkdir(exist_ok=True)
    entities = [assembly_evidence(s, current, art, assembly_dir) for s in registry['assemblies']]
    write_json(args.out/'entities.json', entities)
    write_json(args.out/'assemblies.json', registry['assemblies'])
    groups = sorted({r['group'] for r in registry['entries']})
    for group in groups:
        inventory = read(ROOT/'local/model-gallery/emerald-pass-study/inventory'/group/'entries.json')
        keys = {r['key'] for r in rows}
        inventory = [r for r in inventory if r['key'] in keys]
        for r in inventory:
            r['from'] = current['cells'][r['key']].get('from')
            r['voxels'] = int(decode(current['cells'][r['key']])[0].sum())
        contact_pages(group, inventory, current, art, args.out/'current-contacts')
    print(f'{len(rows)} decisions; {len(updates)} changes; {len(entities)} map-bound whole assemblies')


if __name__ == '__main__':
    main()
