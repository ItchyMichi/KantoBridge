"""Source study for Crystal's fitted cells (MODEL_AUTHORING_WORKFLOW section 2).

`families` assigns every fitted key to the tileset family it occurs in most (a key with no
static placement goes to `unplaced`) and writes families.json. `group` renders one family
(or explicit keys): each key's sprite beside an angled render of its current model, its
metadata, the exported collision code at every placement, and each map with the studied
cells numbered. Local evidence only; it makes no decisions.
"""
from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

from tools.model_gallery import CrystalArt, write_json
from tools.shape_fit.blue_pass.common import decode

ROOT = Path(__file__).resolve().parents[3]
BUNDLE = ROOT / "local/minecraft/kantobridge/export/crystal"
BASELINE = ROOT / "local/model-gallery/crystal-full-pass-baseline.json"
STUDY = ROOT / "local/model-gallery/crystal-pass-study"


def collision(art, name):
    """The exported collision code of a map cell (quarter of a 2x2 block)."""
    source = art.maps[name]
    tiles = art.tilesets[source.get("variant", source["tileset"])]
    import base64
    blocks = base64.b64decode(source["blocks"])
    w = source["w"]

    def at(x, y):
        b = blocks[(y // 2) * w + x // 2]
        if b >= len(tiles["coll"]):
            b = source["border"]
        return tiles["coll"][b][(y % 2) * 2 + x % 2]
    return at


def family_of(art, key):
    places = art.locations.get(key, [])
    if not places:
        return "unplaced"
    count = Counter()
    for p in places:
        s = art.maps[p["map"]]
        count[s.get("variant", s["tileset"])] += 1
    return count.most_common(1)[0][0]


def render(entry, art, key, size=110):
    from tools.shape_fit.hand_check import picture
    occ, paint = decode(entry)
    if not occ.any():
        return Image.new("RGB", (size, size), (90, 40, 40))
    px = np.asarray(art.cell(key, "BAKED").convert("RGB"))
    import os
    tmp = STUDY / f"_render-{os.getpid()}.png"
    tmp.parent.mkdir(parents=True, exist_ok=True)
    picture(occ, px[paint // 16, paint % 16], tmp, 3)
    im = Image.open(tmp).convert("RGB")
    im.thumbnail((size, size))
    return im


def families(art, table):
    out = defaultdict(list)
    for key in sorted(table["cells"]):
        out[family_of(art, key)].append(key)
    return dict(sorted(out.items()))


def group(art, table, keys, out: Path, maps=True):
    out.mkdir(parents=True, exist_ok=True)
    from tools.shape_fit.crystal_pass import census as census_mod
    census = census_mod.load()["keys"] if census_mod.CENSUS.exists() else None
    clabel = census_mod.label
    index = {k: i for i, k in enumerate(keys)}
    by_map = defaultdict(set)
    for k in keys:
        for p in art.locations.get(k, []):
            by_map[p["map"]].add((p["x"], p["y"], k))
    records = defaultdict(list)
    for name, places in sorted(by_map.items()):
        at = collision(art, name)
        for x, y, k in places:
            records[k].append({"map": name, "x": x, "y": y, "collision": at(x, y)})
        if not maps:
            continue
        img = art.map_image(name).convert("RGB")
        s = 3
        img = img.resize((img.width * s, img.height * s), Image.Resampling.NEAREST)
        d = ImageDraw.Draw(img)
        for gx in range(0, img.width, 16 * s):
            d.line([(gx, 0), (gx, img.height)], fill=(255, 0, 255))
        for gy in range(0, img.height, 16 * s):
            d.line([(0, gy), (img.width, gy)], fill=(255, 0, 255))
        for x, y, k in places:
            d.rectangle([x * 16 * s + 1, y * 16 * s + 1, (x + 1) * 16 * s - 2, (y + 1) * 16 * s - 2], outline=(255, 255, 0), width=2)
            d.text((x * 16 * s + 3, y * 16 * s + 2), str(index[k]), fill=(255, 255, 0))
        img.save(out / f"map-{name}.png")
    cols, cw, ch = 6, 240, 160
    pages = (len(keys) + 35) // 36
    meta = {}
    for page in range(pages):
        chunk = keys[page * 36:(page + 1) * 36]
        rows = (len(chunk) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * cw, rows * ch), "white")
        d = ImageDraw.Draw(sheet)
        for j, k in enumerate(chunk):
            i = index[k]
            x0, y0 = j % cols * cw, j // cols * ch
            sheet.paste(art.cell(k, "BAKED").convert("RGB").resize((96, 96), Image.Resampling.NEAREST), (x0 + 4, y0 + 18))
            entry = table["cells"][k]
            sheet.paste(render(entry, art, k), (x0 + 112, y0 + 16))
            m = {a: b for a, b in entry.items() if a not in ("v", "t", "up")}
            occ, _ = decode(entry)
            ys = np.nonzero(occ.any(axis=(1, 2)))[0]
            meta[k] = {"index": i, "metadata": m, "layers": 1 + len(entry.get("up", [])),
                       "top": int(ys.max()) + 1 if len(ys) else 0, "voxels": int(occ.sum()),
                       "placements": records.get(k, [])}
            flags = ",".join(f for f in ("walk", "seat") if m.get(f)) + (" on" if m.get("on") else "")
            col = sorted(Counter(p["collision"] for p in records.get(k, [])).items())
            d.text((x0 + 4, y0 + 2), f"{i} {flags} h={meta[k]['top']} c={col}"[:44], fill="black")
            d.text((x0 + 4, y0 + 118), k[4:], fill=(80, 80, 80))
            mp = Counter(p["map"] for p in records.get(k, []))
            d.text((x0 + 4, y0 + 132), f"{len(records.get(k, []))}x {', '.join(n[3:] for n, _ in mp.most_common(2))}"[:44], fill=(80, 80, 80))
            if census and k in census:
                meta[k]["census"] = census[k]
                d.text((x0 + 4, y0 + 146), clabel(census[k])[:44], fill=(160, 0, 0) if not census[k]["drawn"] else (0, 110, 0))
        sheet.save(out / f"cells-{page + 1}.png")
    write_json(out / "cells.json", meta)
    return meta


def pixel_map(art, key):
    """A cell's drawing as text: one symbol a pixel, with the colour legend (most frequent first)."""
    px = np.asarray(art.cell(key, "BAKED").convert("RGB")).astype(int)
    values, counts = np.unique(px.reshape(-1, 3), axis=0, return_counts=True)
    order = [tuple(int(c) for c in values[i]) for i in np.argsort(-counts)]
    symbols = ".#o+x=*%@abcdefghijklmnopqrstuvwxyz"
    sym = {c: symbols[i] for i, c in enumerate(order)}
    lines = [key + "  " + "  ".join(f"{sym[c]}={c}" for c in order),
             "   " + "".join(str(u % 10) for u in range(16))]
    for v in range(16):
        lines.append(f"{v:2d} " + "".join(sym[tuple(px[v, u])] for u in range(16)))
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("families")
    px = sub.add_parser("pixels", help="print cells' drawings as text with their exact colours")
    px.add_argument("keys", nargs="+")
    g = sub.add_parser("group")
    g.add_argument("--family")
    g.add_argument("--keys", nargs="*")
    g.add_argument("--out", type=Path)
    g.add_argument("--no-maps", action="store_true")
    args = ap.parse_args()
    art = CrystalArt(BUNDLE)
    table = json.loads(BASELINE.read_text(encoding="utf-8"))
    if args.cmd == "pixels":
        for key in args.keys:
            print(pixel_map(art, key if key.startswith("g3q/") else "g3q/" + key))
            print()
        return
    if args.cmd == "families":
        fam = families(art, table)
        write_json(STUDY / "families.json", fam)
        for name, keys in fam.items():
            print(len(keys), name)
        return
    fam = json.loads((STUDY / "families.json").read_text(encoding="utf-8"))
    keys = args.keys or fam[args.family]
    out = args.out or STUDY / "groups" / args.family
    meta = group(art, table, keys, out, maps=not args.no_maps)
    print(f"{len(meta)} keys -> {out}")


if __name__ == "__main__":
    main()
