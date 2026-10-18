#!/usr/bin/env python3
"""Fitted shapes: entities from the user's map review (docs/specs/FITTED_SHAPES.md section 15) modelled as one object.

    python entity_depth.py <game> <map> <flags_<game>.json> <map art.png> <out dir> [--weights /root/esrgan]

An entity is a set of cells the user selected on kb-hub's maps page (one object drawn over several cells: a sideways
pair, a tall object, a group), with optional background cells (the floor it is drawn on). Entities with the same keys
in the same layout are one model. Per model:

    drawing   the cells cut from the map's picture (the hidden game's colours), 16 px a cell
    mask      a pixel is the object's when it differs from the background at the same place in a cell and its colour
              is none of the background's (holes the floor shows through): the background cells the user picked, else
              the walkable cells around the entity; small specks dropped
    depth     Depth Anything V2 Small on the drawing as it is (nearest, padded to a square) and through Real-ESRGAN twice
              at x4 (anime and general models, its own colours); each map averaged per drawn pixel, normalised over the
              mask, the maps averaged (0 the farthest drawn pixel, 1 the nearest)
    relief    each drawn pixel a column centred in the cell's depth, TMIN + depth * (span - TMIN) voxels, span the
              mask's widest row (as deep as it is wide) capped at 16; a sideways pair (seen side-on) at most SIDE_SPAN
    cells     the relief cut into each cell's 16 x 16 x 16 voxels, the cell's own drawing rows in front (the planner's
              front projection), so each cell's table entry holds its part of the object

Writes <out>/<model id>/{drawing.png, mask.png, depth.png, viewer.json} and <out>/shapes.json ({cell key: voxel bits},
for merge_voxels.py --source entity) and <out>/entities.json (each model's keys, size, placements, kind and note).
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

W = 16
TMIN = 2
SIDE_SPAN = 6  # a sideways pair is seen side-on: at most this deep
ART = 48  # map art pixels per cell (kb-hub's maps pictures are 3x)


def bits(arr: np.ndarray) -> str:
    return base64.b64encode(np.packbits(arr.astype(np.uint8).ravel(), bitorder="little").tobytes()).decode()


def native(art, x0: int, y0: int, w: int, h: int) -> np.ndarray:
    """The cells (x0, y0) .. (x0 + w, y0 + h) of the map picture at 16 px a cell, RGB."""
    a = np.asarray(art.convert("RGB"))
    s = ART // W
    return a[y0 * ART + s // 2:(y0 + h) * ART:s, x0 * ART + s // 2:(x0 + w) * ART:s].copy()


def mask_of(draw: np.ndarray, cells: list, box, backs: list[np.ndarray]) -> np.ndarray:
    x0, y0, w, h = box
    fg = np.zeros(draw.shape[:2], bool)
    for cx, cy in cells:
        px = draw[(cy - y0) * W:(cy - y0 + 1) * W, (cx - x0) * W:(cx - x0 + 1) * W].astype(np.int16)
        if backs:
            # floor where it matches a background cell at the same place, or where its colour is one of the floor's
            # colours (holes the floor shows through: a wheel's middle on a checkered floor)
            dist = np.min([np.abs(px - b.astype(np.int16)).sum(axis=2) for b in backs], axis=0)
            colours = np.unique(np.concatenate([b.reshape(-1, 3) for b in backs]).astype(np.int16), axis=0)
            near = np.min(np.abs(px.reshape(-1, 1, 3) - colours[None]).sum(axis=2), axis=1).reshape(W, W)
            on = (dist > 40) & (near > 24)
        else:
            on = np.ones((W, W), bool)
        fg[(cy - y0) * W:(cy - y0 + 1) * W, (cx - x0) * W:(cx - x0 + 1) * W] = on
    # drop specks: 4-connected pieces under 4 pixels
    seen = np.zeros_like(fg)
    for sy in range(fg.shape[0]):
        for sx in range(fg.shape[1]):
            if fg[sy, sx] and not seen[sy, sx]:
                comp, todo = [], [(sy, sx)]
                seen[sy, sx] = True
                while todo:
                    y, x = todo.pop()
                    comp.append((y, x))
                    for ny, nx in ((y + 1, x), (y - 1, x), (y, x + 1), (y, x - 1)):
                        if 0 <= ny < fg.shape[0] and 0 <= nx < fg.shape[1] and fg[ny, nx] and not seen[ny, nx]:
                            seen[ny, nx] = True
                            todo.append((ny, nx))
                if len(comp) < 4:
                    for y, x in comp:
                        fg[y, x] = False
    return fg


def square(img_rgba: np.ndarray):
    """The drawing on a transparent square canvas (centred), and where it sits: (canvas, ox, oy, scale)."""
    from PIL import Image

    h, w = img_rgba.shape[:2]
    side = max(w, h)
    scale = 512 // side
    big = Image.fromarray(img_rgba, "RGBA").resize((w * scale, h * scale), Image.NEAREST)
    canvas = Image.new("RGBA", (768, 768), (0, 0, 0, 0))
    ox, oy = (768 - w * scale) // 2, (768 - h * scale) // 2
    canvas.paste(big, (ox, oy))
    return canvas, ox, oy, scale


def depth_of(depth, canvas, ox, oy, scale, shape, fg) -> np.ndarray | None:
    from PIL import Image

    rgb = Image.new("RGB", canvas.size, (0, 0, 0))
    rgb.paste(canvas, mask=canvas.split()[3])
    d = np.array(depth(rgb)["predicted_depth"], dtype=np.float32).squeeze()
    d = np.array(Image.fromarray(d).resize(canvas.size, Image.BILINEAR))
    h, w = shape
    d = d[oy:oy + h * scale, ox:ox + w * scale].reshape(h, scale, w, scale).mean(axis=(1, 3))
    vals = d[fg]
    if vals.size == 0 or vals.max() - vals.min() < 1e-6:
        return None
    return (d - vals.min()) / (vals.max() - vals.min())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("game")
    ap.add_argument("map")
    ap.add_argument("flags")
    ap.add_argument("art")
    ap.add_argument("out")
    ap.add_argument("--weights", default="/root/esrgan")
    ap.add_argument("--info", default="", help="the map's info json (kb-hub): walkable cells for the fallback background")
    args = ap.parse_args()
    from PIL import Image

    flags = json.load(open(args.flags))
    ents = flags.get("_entities", {}).get(args.map, [])
    art = Image.open(args.art)
    info = json.load(open(args.info)) if args.info else None
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    models: dict[str, dict] = {}
    for e in ents:
        if e["kind"] in ("keep as blocks", "make by hand"):
            continue
        x0, y0, w, h = e["box"]
        layout = [[None] * w for _ in range(h)]
        for (cx, cy), k in zip(e["cells"], e["keys"]):
            layout[cy - y0][cx - x0] = k
        sig = json.dumps(layout)
        m = models.setdefault(sig, {"layout": layout, "box": [w, h], "placements": [], "kind": e["kind"], "note": e.get("note", ""),
                                    "first": e, "backs": []})
        m["placements"].append([args.map, x0, y0])
        for b in e.get("background", []):
            m["backs"].append(b["cell"])
        if e.get("note"):
            m["note"] = e["note"]
    # one background list for the whole map when an entity has none: the user's picks elsewhere on the map, else the
    # walkable cells around the entity
    all_backs = sorted({tuple(c) for m in models.values() for c in m["backs"]})
    import torch
    from spandrel import ModelLoader
    from transformers import pipeline

    import upscale_test as up

    cuda = torch.cuda.is_available()
    upscalers = {k: ModelLoader().load_from_file(os.path.join(args.weights, f)).eval() for k, f in up.MODELS.items()}
    if cuda:
        upscalers = {k: v.cuda() for k, v in upscalers.items()}
    depth = pipeline("depth-estimation", model="depth-anything/Depth-Anything-V2-Small-hf", device=0 if cuda else -1)
    shapes, listing = {}, []
    for n, (sig, m) in enumerate(models.items()):
        e = m["first"]
        x0, y0, w, h = e["box"]
        draw = native(art, x0, y0, w, h)
        backs = m["backs"] or list(all_backs)
        if not backs and info:
            for cx in range(x0 - 1, x0 + w + 1):
                for cy in range(y0 - 1, y0 + h + 1):
                    if 0 <= cx < info["w"] and 0 <= cy < info["h"] and info["kind"][cy * info["w"] + cx] == "W" \
                            and [cx, cy] not in e["cells"]:
                        backs.append([cx, cy])
        back_px = [native(art, bx, by, 1, 1) for bx, by in backs]
        fg = mask_of(draw, e["cells"], e["box"], back_px)
        rgba = np.dstack([draw, np.where(fg, 255, 0).astype(np.uint8)])
        sources = []
        canvas, ox, oy, scale = square(rgba)
        sources.append((canvas, ox, oy, scale))
        for model in upscalers.values():
            big = up.upscale(model, rgba)  # x16
            img = Image.fromarray(big, "RGBA")
            side = max(img.size)
            sc = 512 / side
            img = img.resize((int(img.width * sc), int(img.height * sc)), Image.LANCZOS)
            a = np.array(img)
            a[..., 3] = np.where(a[..., 3] >= 128, 255, 0)
            img = Image.fromarray(a, "RGBA")
            cv = Image.new("RGBA", (768, 768), (0, 0, 0, 0))
            ox2, oy2 = (768 - img.width) // 2, (768 - img.height) // 2
            cv.paste(img, (ox2, oy2))
            # map back to native pixels: resample the canvas region to the drawing's size
            sources.append((cv, ox2, oy2, img.width / draw.shape[1]))
        maps = []
        for cv, ox_, oy_, sc_ in sources:
            if isinstance(sc_, int):
                dm = depth_of(depth, cv, ox_, oy_, sc_, draw.shape[:2], fg)
            else:
                d = np.array(depth(Image.composite(cv.convert("RGB"), Image.new("RGB", cv.size), cv.split()[3]))["predicted_depth"],
                             dtype=np.float32).squeeze()
                d = np.array(Image.fromarray(d).resize(cv.size, Image.BILINEAR))
                reg = d[oy_:oy_ + int(round(draw.shape[0] * sc_)), ox_:ox_ + int(round(draw.shape[1] * sc_))]
                reg = np.array(Image.fromarray(reg).resize((draw.shape[1], draw.shape[0]), Image.BILINEAR))
                vals = reg[fg]
                dm = None if vals.size == 0 or vals.max() - vals.min() < 1e-6 else (reg - vals.min()) / (vals.max() - vals.min())
            if dm is not None:
                maps.append(dm)
        dn = np.mean(maps, axis=0) if maps else np.full(draw.shape[:2], 0.5, np.float32)
        span = min(W, max(int(fg[r].sum()) for r in range(fg.shape[0])) or W)
        if m["kind"] == "sideways pair":
            span = min(span, SIDE_SPAN)  # seen side-on (a bike, a bench): thin
        mid = f"{args.game}_{args.map}_{n:02d}"
        d = out / mid
        d.mkdir(exist_ok=True)
        Image.fromarray(draw).resize((draw.shape[1] * 8, draw.shape[0] * 8), Image.NEAREST).save(d / "drawing.png")
        Image.fromarray((fg * 255).astype(np.uint8)).resize((draw.shape[1] * 8, draw.shape[0] * 8), Image.NEAREST).save(d / "mask.png")
        Image.fromarray((dn * fg * 255).astype(np.uint8)).resize((draw.shape[1] * 8, draw.shape[0] * 8), Image.NEAREST).save(d / "depth.png")
        whole = np.zeros((h * W, W, w * W), bool)  # [y][z][x], rows stacked as drawn
        for (cx, cy), k in zip(e["cells"], e["keys"]):
            cell = np.zeros((W, W, W), bool)
            for r in range(W):
                for c in range(W):
                    gy, gx = (cy - y0) * W + r, (cx - x0) * W + c
                    if fg[gy, gx]:
                        t = int(round(TMIN + dn[gy, gx] * (span - TMIN)))
                        z0 = (W - t) // 2
                        cell[W - 1 - r, z0:z0 + t, c] = True
            shapes[k] = bits(cell)
            whole[(h - 1 - (cy - y0)) * W:(h - (cy - y0)) * W, :, (cx - x0) * W:(cx - x0 + 1) * W] = cell
        # the viewer: the whole object as drawn (rows stacked), its own colours
        q = Image.fromarray(draw).quantize(colors=64, method=Image.Quantize.MEDIANCUT)
        pal = ((q.getpalette() or []) + [0] * 192)[:192]
        idx = np.asarray(q)
        shade = np.full(whole.shape, 255, np.uint8)
        for y in range(whole.shape[0]):
            row = h * W - 1 - y
            shade[y] = np.where(whole[y], idx[row][None, :], 255)
        view = {"title": f"entity: {m['kind']}", "dims": [w * W, h * W, W], "voxels": bits(whole),
                "shades": base64.b64encode(shade.tobytes()).decode(),
                "palette": ["#%02x%02x%02x" % tuple(pal[i * 3:i * 3 + 3]) for i in range(64)], "voxelsPerBlock": 8}
        (d / "viewer.json").write_text(json.dumps(view), encoding="utf-8")
        listing.append({"id": mid, "kind": m["kind"], "note": m["note"], "size": [w, h], "layout": m["layout"],
                        "placements": m["placements"], "background": backs, "span": span, "maps": len(maps)})
        print(mid, m["kind"], f"{w}x{h}", "placements", len(m["placements"]), "drawn px", int(fg.sum()), "depth maps", len(maps))
    (out / "shapes.json").write_text(json.dumps(shapes), encoding="utf-8")
    (out / "entities.json").write_text(json.dumps(listing, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
