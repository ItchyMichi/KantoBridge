"""Source study for a group of pending Emerald entries (MODEL_AUTHORING_WORKFLOW section 2).

For each map the keys occur in: the reconstructed map with a grid and every studied
cell numbered. For each key: its sprite beside a render of its current fitted model,
with metadata, exported collision/elevation at every placement and the map positions.
Writes local evidence only; makes no decisions.
"""
from __future__ import annotations

import argparse
import base64
import json
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import decode

ROOT = Path(__file__).resolve().parents[3]
BUNDLE = ROOT / "local/minecraft/kantobridge/export/emerald"
TABLE = ROOT / "fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"
REPORT = ROOT / "local/model-gallery/emerald-pass-study/review.json"
GROUPS = ROOT / "local/model-gallery/emerald-pass-study/remaining-building-groups.json"


def attributes(art, name):
    source = art.maps[name]
    data = base64.b64decode(source["cells"])
    w = source["w"]
    return lambda x, y: (data[(y * w + x) * 4 + 2], data[(y * w + x) * 4 + 3])


def model_render(entry, art, key, size=96):
    """Small angled render of a fitted model with its own paint (local preview only)."""
    from tools.shape_fit.hand_check import picture
    occ, paint = decode(entry)
    if not occ.any():
        return Image.new("RGB", (size, size), (90, 40, 40))
    px = np.asarray(art.cell(key, "BAKED").convert("RGB"))
    tmp = ROOT / "local/model-gallery/claude-trial/_render.png"
    tmp.parent.mkdir(parents=True, exist_ok=True)
    picture(occ, px[paint // 16, paint % 16], tmp, 3)
    im = Image.open(tmp).convert("RGB")
    im.thumbnail((size, size))
    return im


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--group", required=True, help="remaining-building-groups name, or a map name for ungrouped keys")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--keys", nargs="*", help="explicit keys instead of the group's pending ones")
    args = ap.parse_args()
    art = EmeraldArt(BUNDLE)
    table = json.loads(TABLE.read_text(encoding="utf-8"))
    pending = {p["key"] for p in json.loads(REPORT.read_text(encoding="utf-8"))["pending"]}
    if args.keys:
        keys = args.keys
    else:
        groups = {g["group"]: g["keys"] for g in json.loads(GROUPS.read_text(encoding="utf-8"))}
        keys = [k for k in groups[args.group] if k in pending] if args.group in groups else \
            sorted(k for k in pending if any(p["map"] == args.group for p in art.locations.get(k, [])))
    index = {k: i for i, k in enumerate(keys)}
    out = args.out
    out.mkdir(parents=True, exist_ok=True)
    by_map = defaultdict(set)
    for k in keys:
        for p in art.locations.get(k, []):
            by_map[p["map"]].add((p["x"], p["y"], k))
    records = {}
    for name, places in sorted(by_map.items()):
        img = art.map_image(name).convert("RGB")
        s = 4
        img = img.resize((img.width * s, img.height * s), Image.Resampling.NEAREST)
        d = ImageDraw.Draw(img)
        for gx in range(0, img.width, 16 * s):
            d.line([(gx, 0), (gx, img.height)], fill=(255, 0, 255))
        for gy in range(0, img.height, 16 * s):
            d.line([(0, gy), (img.width, gy)], fill=(255, 0, 255))
        attr = attributes(art, name)
        for x, y, k in places:
            d.rectangle([x * 16 * s + 1, y * 16 * s + 1, (x + 1) * 16 * s - 2, (y + 1) * 16 * s - 2], outline=(255, 255, 0), width=2)
            d.text((x * 16 * s + 4, y * 16 * s + 3), str(index[k]), fill=(255, 255, 0))
            records.setdefault(k, []).append({"map": name, "x": x, "y": y, "collision": attr(x, y)[0], "elevation": attr(x, y)[1]})
        img.save(out / f"map-{name}.png")
    cols, cw, chh = 6, 220, 150
    rows = (len(keys) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * cw, rows * chh), "white")
    d = ImageDraw.Draw(sheet)
    meta = {}
    for i, k in enumerate(keys):
        x0, y0 = i % cols * cw, i // cols * chh
        sheet.paste(art.cell(k, "BAKED").convert("RGB").resize((96, 96), Image.Resampling.NEAREST), (x0 + 4, y0 + 18))
        entry = table["cells"][k]
        sheet.paste(model_render(entry, art, k), (x0 + 110, y0 + 18))
        m = {a: b for a, b in entry.items() if a not in ("v", "t", "up")}
        meta[k] = {"index": i, "metadata": m, "layers": 1 + len(entry.get("up", [])), "placements": records.get(k, [])}
        flags = ",".join(f for f in ("walk", "seat") if m.get(f)) + (" on" if m.get("on") else "")
        col = sorted({(p["collision"], p["elevation"]) for p in records.get(k, [])})
        d.text((x0 + 4, y0 + 2), f"{i} {flags} c/e={col}"[:40], fill="black")
        d.text((x0 + 4, y0 + 118), k[4:], fill=(80, 80, 80))
        d.text((x0 + 4, y0 + 132), f"{len(records.get(k, []))} placements", fill=(80, 80, 80))
    sheet.save(out / "cells.png")
    write_json(out / "cells.json", meta)
    print(f"{len(keys)} keys, {len(by_map)} maps -> {out}")


if __name__ == "__main__":
    main()
