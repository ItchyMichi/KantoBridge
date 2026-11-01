"""Reconstruct the entire Emerald scenery audit inventory without reviewing it.

Grouping records source tileset membership and exact geometry identities only.
No label, retention decision or complete-object claim is inferred from grouping.
Local orthographic previews are audit aids, not Minecraft capture evidence.
"""
from __future__ import annotations

import argparse
import base64
from collections import Counter, defaultdict
import hashlib
import html
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from tools.model_gallery import EmeraldArt, safe_id, write_json
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass.review import BASELINE, TABLE, REPORT, entry_hash, read
from tools.shape_fit.emerald_pass.scenery import BUNDLE, ROOT

OUT = ROOT / "local/model-gallery/emerald-pass-study/inventory"


def membership(art):
    result = defaultdict(list)
    for pair, quarters in art.quarters.items():
        for mid, values in quarters.items():
            result["g3q/" + "-".join(map(str, values))].append({"tileset": pair, "metatile": mid})
    return result


def source_group(entries):
    pairs = sorted({e["tileset"] for e in entries})
    if not pairs:
        return "unlisted"
    if len(pairs) == 1:
        return pairs[0]
    return "shared-" + pairs[0].split("__")[0]


def building_membership(table):
    result = defaultdict(list)
    for building in table["buildings"]:
        rows = building["tiles"]
        keys = {"g3q/" + "-".join(map(str, (rows[y][x], rows[y][x+1], rows[y+1][x], rows[y+1][x+1])))
                for y in range(0, len(rows), 2) for x in range(0, len(rows[0]), 2)}
        for key in keys:
            result[key].append(building["id"])
    return result


def orthographic(entry, sprite):
    """Source-painted front/east/top silhouettes of occupied voxels."""
    occ, paint = decode(entry)
    art = np.asarray(sprite.convert("RGB"))
    colors = art[paint//16, paint%16]
    def projection(axis, reverse_y=False):
        visible = occ.any(axis=axis)
        indices = occ.shape[axis] - 1 - np.argmax(np.flip(occ, axis=axis), axis=axis)
        coords = np.indices(visible.shape)
        lookup = []
        j = 0
        for dimension in range(3):
            if dimension == axis:
                lookup.append(indices)
            else:
                lookup.append(coords[j]); j += 1
        projected = np.zeros((*visible.shape, 4), dtype=np.uint8)
        projected[..., :3] = colors[tuple(lookup)]
        projected[..., 3] = visible.astype(np.uint8)*255
        if reverse_y:
            projected = projected[::-1]
        return Image.fromarray(projected)
    return projection(1, True), projection(2, True), projection(0)


def metadata(table, baseline, art, review):
    members, buildings = membership(art), building_membership(table)
    reviewed = {(r["kind"], r["key"]): r for r in review.get("entries", [])}
    geometry, items, groups = defaultdict(list), [], defaultdict(list)
    supports = defaultdict(list)
    for key, entry in table["cells"].items():
        if entry.get("on"):
            supports[entry["on"]].append(key)
    for number, (key, entry) in enumerate(sorted(table["cells"].items())):
        if entry.get("class") != "voxels":
            continue
        layers = [entry, *entry.get("up", [])]
        raw = b"".join(base64.b64decode(layer["v"]) for layer in layers)
        geometry_hash = hashlib.sha256(raw).hexdigest()
        places = art.locations.get(key, [])
        source = art.cell(key, "BAKED")
        group = source_group(members[key])
        bounds = np.argwhere(decode(entry)[0])
        item = {"key": key, "number": number, "group": group,
                "review_status": reviewed.get(("cell", key), {}).get("decision", "pending"),
                "class": entry["class"], "from": entry.get("from"),
                "baseline_entry_sha256": entry_hash(baseline["cells"][key]),
                "current_entry_sha256": entry_hash(entry), "geometry_sha256": geometry_hash,
                "sprite_sha256": hashlib.sha256(source.tobytes()).hexdigest(),
                "voxels": sum(int(v).bit_count() for v in raw), "layers": len(layers),
                "occupied_bounds_yzx": [bounds.min(axis=0).tolist(), (bounds.max(axis=0)+1).tolist()] if bounds.size else None,
                "on": entry.get("on"), "supports": sorted(supports[key]),
                "tileset_membership": members[key], "building_source_membership": buildings[key],
                "occurrences": places, "maps": sorted({p["map"] for p in places}),
                "semantic_review": "pending unless separately recorded in cumulative review; grouping is not a review decision"}
        geometry[geometry_hash].append(key)
        items.append(item); groups[group].append(item)
    for rows in groups.values():
        rows.sort(key=lambda r: (min(x["metatile"] for x in r["tileset_membership"]) if r["tileset_membership"] else 99999, r["key"]))
    return items, groups, geometry


def contact_pages(group, rows, table, art, out):
    directory = out / group
    directory.mkdir(parents=True, exist_ok=True)
    font_path = Path("C:/Windows/Fonts/consola.ttf")
    font = ImageFont.truetype(str(font_path), 12) if font_path.exists() else ImageFont.load_default()
    links = []
    for start in range(0, len(rows), 32):
        chunk = rows[start:start+32]
        sheet = Image.new("RGB", (1680, 60 + ((len(chunk)+3)//4)*192), "#e8ecef")
        draw = ImageDraw.Draw(sheet)
        draw.text((12, 10), f"Emerald audit candidates / {group} / {start+1}-{start+len(chunk)} of {len(rows)}", fill="black", font=font)
        draw.text((12, 29), "Source | local geometry FRONT | EAST | TOP. Inspection aids only; no automatic semantic decisions.", fill="black", font=font)
        for i, row in enumerate(chunk):
            x, y = i%4*420, 60+i//4*192
            draw.rectangle((x+2, y+2, x+416, y+188), fill="#f9fafb", outline="#c6cdd2")
            key = row["key"]
            draw.text((x+8, y+7), f"{start+i+1:03d} {key}", fill="black", font=font)
            sprite = art.cell(key, "BAKED")
            previews = [sprite, *orthographic(table["cells"][key], sprite)]
            for j, image in enumerate(previews):
                scale = max(1, min(88//image.width, 119//image.height))
                image = image.resize((image.width*scale, image.height*scale), Image.Resampling.NEAREST)
                sheet.paste(image, (x+11+j*101+(88-image.width)//2, y+29+(119-image.height)//2), image if image.mode == "RGBA" else None)
            displayed = table['cells'][key]
            displayed_voxels = int(decode(displayed)[0].sum())
            label = f"{displayed.get('from')} | {displayed_voxels} vox | {len(row['occurrences'])} places"
            if displayed.get('on'): label += " | ON"
            if any(e.get('on')==key for e in table['cells'].values()): label += " | SUPPORT"
            if row["building_source_membership"]: label += " | BLDG"
            draw.text((x+8, y+151), label, fill="#28434a", font=font)
            draw.text((x+8, y+168), (row["maps"][0] if row["maps"] else "No exported placement")[:55], fill="#28434a", font=font)
            row["contact_page"] = f"{group}/page-{start//32+1:03d}.png"
            row["contact_position"] = start+i+1
            row["displayed_entry_sha256"] = entry_hash(displayed)
        path = directory / f"page-{start//32+1:03d}.png"
        sheet.save(path)
        links.append(path.name)
    write_json(directory / "entries.json", rows)
    (directory / "index.html").write_text("<!doctype html><meta charset='utf-8'><title>Emerald audit " + html.escape(group) + "</title><h1>" + html.escape(group) + "</h1><p>Pending audit candidates; source and local orthographic previews are not completed Minecraft evidence.</p>" + "".join(f"<p><a href='{name}'><img style='max-width:100%' src='{name}'></a></p>" for name in links), encoding="utf-8")
    print(group, len(rows), "cells,", len(links), "contact pages", flush=True)


def compact_context_pages(rows, art, out):
    """One indexed, centered map neighborhood per key for a manual audit.

    The red square identifies the source cell. This is evidence generation,
    never an automatic review or an assertion that all occurrences were viewed.
    """
    out.mkdir(parents=True, exist_ok=True)
    grids, index = {}, []
    for start in range(0, len(rows), 16):
        chunk = rows[start:start+16]
        page = Image.new('RGB', (1600, ((len(chunk)+3)//4)*250), '#eeeeee')
        draw = ImageDraw.Draw(page)
        for i, row in enumerate(chunk):
            left, top = (i%4)*400, (i//4)*250
            key = row['key']; places = row.get('occurrences', [])
            name = f'page-{start//16+1:03d}.png'
            draw.text((left+4,top+4), f"{row.get('contact_position',start+i+1)} {key}", fill='black')
            page.paste(art.cell(key,'BAKED').resize((64,64),Image.Resampling.NEAREST),(left+4,top+34))
            record = {'key':key, 'group':row['group'], 'contact_position':row.get('contact_position'), 'page':name}
            if places:
                place = places[0]; map_id = place['map']
                if map_id not in grids:grids[map_id]=art.map_cells(art.maps[map_id])
                grid=grids[map_id]; x,y=place['x'],place['y']
                crop=[[grid[cy][cx] if 0<=cy<len(grid) and 0<=cx<len(grid[cy]) else None
                       for cx in range(x-2,x+3)] for cy in range(y-2,y+3)]
                picture=art.drawing(crop,'BAKED').resize((200,200),Image.Resampling.NEAREST)
                ImageDraw.Draw(picture).rectangle((80,80,119,119),outline='#ff3030',width=2)
                page.paste(picture,(left+88,top+30))
                draw.text((left+4,top+232),f'{map_id} ({x},{y})',fill='black')
                record['occurrence']=place
            else:draw.text((left+75,top+50),'No static map occurrence',fill='black')
            index.append(record)
        page.save(out/name)
    write_json(out/'index.json',index)


def context_pages(rows, art, out):
    """Focused source-neighborhood evidence, avoiding whole-map image rendering."""
    out.mkdir(parents=True, exist_ok=True)
    grids = {}
    for start in range(0, len(rows), 8):
        chunk = rows[start:start+8]
        page = Image.new('RGB', (1400, len(chunk)*225), '#eeeeee')
        draw = ImageDraw.Draw(page)
        for i, row in enumerate(chunk):
            key, top = row['key'], i*225
            draw.text((5,top+5), f"{row.get('contact_position', start+i+1)} {key}", fill='black')
            page.paste(art.cell(key,'BAKED').resize((160,160),Image.Resampling.NEAREST),(5,top+35))
            seen=set()
            for place in row.get('occurrences',[]):
                name=place['map']
                if name in seen:continue
                column=len(seen);seen.add(name)
                if name not in grids:grids[name]=art.map_cells(art.maps[name])
                grid=grids[name];x,y=place['x'],place['y']
                crop=[r[max(0,x-2):x+4] for r in grid[max(0,y-2):y+4]]
                picture=art.drawing(crop,'BAKED').resize((216,216),Image.Resampling.NEAREST)
                page.paste(picture,(190+column*290,top))
                draw.text((190+column*290,top+5),f'{name} ({x},{y})',fill='red')
                if len(seen)==4:break
        page.save(out/f'page-{start//8+1:02d}.png')


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--baseline", type=Path, default=BASELINE)
    ap.add_argument("--table", type=Path, default=TABLE)
    ap.add_argument("--review", type=Path, default=REPORT)
    ap.add_argument("--bundle", type=Path, default=BUNDLE)
    ap.add_argument("--out", type=Path, default=OUT)
    ap.add_argument("--only-group", action="append")
    ap.add_argument("--contexts", action="store_true", help="also render source neighborhoods for selected groups")
    args = ap.parse_args()
    table, baseline = read(args.table), read(args.baseline)
    art = EmeraldArt(args.bundle)
    review = read(args.review) if args.review.exists() else {}
    items, groups, geometry = metadata(table, baseline, art, review)
    args.out.mkdir(parents=True, exist_ok=True)
    distribution = [{"group": name, "cells": len(rows), "pending": sum(r["review_status"] == "pending" for r in rows),
                     "building_source_cells": sum(bool(r["building_source_membership"]) for r in rows),
                     "empty_cells": sum(not r["voxels"] for r in rows), "keys": [r["key"] for r in rows]}
                    for name, rows in sorted(groups.items(), key=lambda p: (-len(p[1]), p[0]))]
    write_json(args.out / "distribution.json", distribution)
    write_json(args.out / "geometry-groups.json", dict(geometry))
    write_json(args.out / "inventory.json", {"schema": 1, "source_table_sha256": hashlib.sha256(args.table.read_bytes()).hexdigest(),
               "baseline_sha256": hashlib.sha256(args.baseline.read_bytes()).hexdigest(), "cells": len(items),
               "geometry_groups": len(geometry), "groups": len(groups), "entries": items})
    # Exact manual ownership selected after source/map/geometry inspection.
    # This is assignment, not an automatic retention or approval decision.
    from tools.shape_fit.emerald_pass import vegetation as vegetation_spec, projected_terrain as terrain_spec
    vegetation_keys = (set(vegetation_spec.BROADLEAF) | set(vegetation_spec.CROWNS) | set(vegetation_spec.BASES)
        | {vegetation_spec.MIDDLE, vegetation_spec.FLOWER, vegetation_spec.GRASS}
        | set(vegetation_spec.PLANTERS) | set(vegetation_spec.SHRUBS)
        | {key for row in vegetation_spec.ORNAMENTAL for key in row}
        | set(terrain_spec.TUFTS) | set(terrain_spec.SURFACES) | set(terrain_spec.MATCHES))
    owned = [row for row in groups['shared-general'] if row['key'] in vegetation_keys]
    if {row['key'] for row in owned} != vegetation_keys:
        raise ValueError('declared vegetation ownership differs from source inventory')
    write_json(args.out / 'vegetation-ownership.json', {'owner':'emerald_scenery', 'group':'shared-general',
        'basis':'Exact manual assignment after inspecting source, geometry and reconstructed map contexts; no review implied by ownership.',
        'keys':[row['key'] for row in owned], 'entries':owned})
    (args.out / "index.html").write_text("<!doctype html><meta charset='utf-8'><title>Emerald scenery audit inventory</title><h1>Emerald scenery audit inventory</h1><p>Groups are source facts, not semantic reviews or complete-object claims. Each card shows source, local front, east and top geometry.</p><ul>" + "".join(f"<li><a href='{row['group']}/index.html'>{row['group']}</a>: {row['cells']} cells, {row['pending']} pending, {row['building_source_cells']} occur in building template art</li>" for row in distribution) + "</ul>", encoding="utf-8")
    for group, rows in sorted(groups.items(), key=lambda pair: (pair[0] != "shared-building", pair[0] != "shared-general", pair[0])):
        if not args.only_group or group in args.only_group:
            contact_pages(group, rows, table, art, args.out)
            if args.contexts:
                context_pages(rows, art, args.out / group / 'contexts')
    print(json.dumps({"cells": len(items), "pending": sum(r['review_status']=='pending' for r in items), "groups": len(groups), "geometry_groups": len(geometry)}))


if __name__ == "__main__":
    main()
