#!/usr/bin/env python3
"""Buildings as a box with 3D decorations (docs/specs/HAND_MODELS.md section 7): a prototype from one building's drawing.

    python building_model.py <map art.png> <map info.json> <x0> <y0> <w> <h> <out dir> [--roof-rows N] [--wall V]

The building is cells (x0, y0)..(x0 + w - 1, y0 + h - 1) of the map, drawn top-down in three-quarter view: the upper
rows are the roof seen from above, the lower `--roof-rows`-less rows the facade (default: all but the last cell row are
roof). Voxels are 16 a cell (8 a block); x east, y up, z south.

    cube      a decorated box as wide as the facade's drawn walls, the facade's rows times --wall voxels high; facade
              rows named in --bands (a porch roof) stand out BAND voxels
    front     the facade drawing stretched over the wall's height on the south face; the door (a walkable cell of the
              footprint's last row) recessed DOOR voxels; the facade's other details (pixels not its main colour)
              standing out 1 voxel
    roof      a separate topper on the cube: a stepped hip RH voxels high (STEP-voxel steps, each STEP in from every side),
              as wide as the drawn roof (the eaves overhang the walls) and reaching EAVE past the front; its top shows
              the roof drawing from above (its rows across the depth)
    sides     the facade's edge columns; the back the roof's top row

Writes <out>/<name>.json (kb-hub's viewer: dims, voxels, shades, palette) and <out>/<name>_view.png (a 3D picture).
"""
from __future__ import annotations

import argparse
import base64
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

W = 16  # voxels (and drawing pixels) a cell
ART = 48  # the map picture's pixels a cell
EAVE, SIDE, DOOR, BAND = 4, 2, 3, 3
RH, STEP = 16, 2  # the roof topper's height and its steps (voxels)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("art")
    ap.add_argument("info")
    ap.add_argument("x0", type=int)
    ap.add_argument("y0", type=int)
    ap.add_argument("w", type=int)
    ap.add_argument("h", type=int)
    ap.add_argument("out")
    ap.add_argument("--roof-rows", type=int, default=-1, help="drawing rows of roof (default: all but the last cell)")
    ap.add_argument("--wall", type=float, default=1.5, help="wall voxels a facade pixel (1.5: a door about two blocks)")
    ap.add_argument("--bands", default="", help="facade rows (from the facade's top) that stand out as an overhang: a-b,c-d")
    ap.add_argument("--name", default="")
    args = ap.parse_args()
    x0, y0, w, h = args.x0, args.y0, args.w, args.h
    info = json.loads(Path(args.info).read_text(encoding="utf-8"))
    art = Image.open(args.art).convert("RGB")
    draw = np.asarray(art.crop((x0 * ART, y0 * ART, (x0 + w) * ART, (y0 + h) * ART)).resize((w * W, h * W), Image.NEAREST))
    roof_rows = args.roof_rows if args.roof_rows >= 0 else (h - 1) * W
    facade = draw[roof_rows:]  # rows of the front wall
    roof = draw[:roof_rows]
    wall = int(round(args.wall * facade.shape[0]))
    bands = [tuple(int(v) for v in b.split("-")) for b in args.bands.split(",") if b]
    X, Z = w * W, h * W  # the footprint in voxels
    # the ground around the building (the drawing's corner) is not the building
    bg = draw[0, 0].copy()
    ground = lambda img: np.abs(img.astype(int) - bg.astype(int)).sum(axis=-1) < 12  # noqa: E731
    # the cube: as wide as the facade's drawn walls (its columns that are not ground), so its pixels reach its edges
    fcols = np.nonzero(~ground(facade).all(axis=0))[0]
    left, right = (int(fcols[0]), int(fcols[-1])) if len(fcols) else (0, X - 1)
    # the roof topper: as wide as the drawn roof (wider than the walls: the eaves overhang)
    rcols = np.nonzero(~ground(roof).all(axis=0))[0] if len(roof) else np.array([], int)
    rl, rr_ = (int(rcols[0]), int(rcols[-1])) if len(rcols) else (left - SIDE, right + SIDE)
    rmain = None
    if len(roof):
        rpx = roof[~ground(roof)]
        if len(rpx):
            ru, rc = np.unique(rpx, axis=0, return_counts=True)
            rmain = ru[int(np.argmax(rc))]
            roof = np.where(ground(roof)[..., None], rmain, roof).astype(np.uint8)
    fpx = facade[~ground(facade)]
    uniq, counts = np.unique(fpx if len(fpx) else facade.reshape(-1, 3), axis=0, return_counts=True)
    main = uniq[int(np.argmax(counts))]
    door = [cx for cx in range(w) if info["kind"][(y0 + h - 1) * info["w"] + x0 + cx] == "W"]

    # the grid: the footprint, the roof's overhang past it on the sides and the front, the roof's height above the walls
    pad = max(0, 1 - rl, rr_ + 1 - (X - 1))
    GX, GZ, GY = X + 2 * pad, Z + EAVE + max(BAND, 1), wall + RH
    ox = pad
    occ = np.zeros((GY, GZ, GX), bool)
    col = np.zeros((GY, GZ, GX, 3), np.uint8)

    def facade_row(y):
        return facade.shape[0] - 1 - int(y * facade.shape[0] / wall)

    # the decorated cube: the facade on its front, its edge columns on the sides, the roof's back row on the back
    for y in range(wall):
        r = facade_row(y)
        for x in range(left, right + 1):
            px = facade[r, x]
            is_door = (x // W) in door and 3 <= x % W <= 12 and r >= facade.shape[0] - 11
            band = any(a <= r <= b for a, b in bands)
            detail = not is_door and not ground(px) and np.abs(px.astype(int) - main.astype(int)).sum() > 30
            front = Z - 1 - (DOOR if is_door else 0) + (BAND if band else 1 if detail else 0)
            for z in range(front + 1):
                occ[y, z, ox + x] = True
                col[y, z, ox + x] = px if z >= front - 1 else facade[r, left] if x < (left + right) // 2 else facade[r, right]
            if ground(px):
                col[y, front, ox + x] = main
        for z in range(Z):
            col[y, z, ox + left] = facade[r, left]
            col[y, z, ox + right] = facade[r, right]
        for x in range(left, right + 1):
            col[y, 0, ox + x] = rmain if rmain is not None else main
    # the roof topper: a stepped hip on the cube, as wide as the drawn roof and reaching EAVE past the front; each step
    # STEP voxels high and STEP in from every side; its top shows the roof drawing from above (rows across the depth)
    depth = Z + EAVE
    rows = max(1, len(roof))
    for k in range(RH // STEP):
        inset = k * STEP
        x_lo, x_hi = rl - 1 + inset, rr_ + 1 - inset
        z_lo, z_hi = inset, depth - 1 - inset
        if x_lo >= x_hi or z_lo >= z_hi:
            break
        for y in range(wall + k * STEP, wall + (k + 1) * STEP):
            for z in range(z_lo, z_hi + 1):
                r = min(rows - 1, int(z * rows / depth))
                for x in range(x_lo, x_hi + 1):
                    occ[y, z, ox + x] = True
                    col[y, z, ox + x] = roof[r, min(X - 1, max(0, x))] if len(roof) else main

    name = args.name or f"building_{x0}_{y0}"
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    q = Image.fromarray(col.reshape(-1, 1, 3)).quantize(colors=64, method=Image.Quantize.MEDIANCUT)
    pal = ((q.getpalette() or []) + [0] * 192)[:192]
    shade = np.where(occ.reshape(-1), np.asarray(q).reshape(-1), 255).astype(np.uint8)
    view = {"title": f"building prototype: {name}", "dims": [GX, GY, GZ],
            "voxels": base64.b64encode(np.packbits(occ.astype(np.uint8).ravel(), bitorder="little").tobytes()).decode(),
            "shades": base64.b64encode(shade.tobytes()).decode(),
            "palette": ["#%02x%02x%02x" % tuple(pal[i * 3:i * 3 + 3]) for i in range(64)], "voxelsPerBlock": 8}
    (out / f"{name}.json").write_text(json.dumps(view), encoding="utf-8")
    picture(occ, col, out / f"{name}_view.png")
    print(name, f"{GX}x{GY}x{GZ} voxels", int(occ.sum()), "solid; door cells", door)
    return 0


def picture(occ: np.ndarray, col: np.ndarray, path: Path, s: int = 5) -> None:
    """A 3D picture from the south-east, above: each visible voxel face a shaded parallelogram (painter's order)."""
    GY, GZ, GX = occ.shape

    def p(x, y, z):  # isometric: x to the right and down, z to the left and down, y up
        return ((x - z) * s, (x + z) * s / 2 - y * s * 1.15)

    xs = [p(x, y, z)[0] for x in (0, GX) for y in (0, GY) for z in (0, GZ)]
    ys = [p(x, y, z)[1] for x in (0, GX) for y in (0, GY) for z in (0, GZ)]
    ox, oy = -min(xs) + 20, -min(ys) + 20
    img = Image.new("RGB", (int(max(xs) - min(xs)) + 40, int(max(ys) - min(ys)) + 40), (40, 44, 52))
    d = ImageDraw.Draw(img)
    q = lambda x, y, z: (p(x, y, z)[0] + ox, p(x, y, z)[1] + oy)  # noqa: E731
    for y in range(GY):
        for z in range(GZ):
            for x in range(GX):
                if not occ[y, z, x]:
                    continue
                c = col[y, z, x].astype(int)
                if y + 1 >= GY or not occ[y + 1, z, x]:
                    d.polygon([q(x, y + 1, z), q(x + 1, y + 1, z), q(x + 1, y + 1, z + 1), q(x, y + 1, z + 1)], fill=tuple(c))
                if z + 1 >= GZ or not occ[y, z + 1, x]:
                    d.polygon([q(x, y, z + 1), q(x + 1, y, z + 1), q(x + 1, y + 1, z + 1), q(x, y + 1, z + 1)],
                              fill=tuple((c * 0.86).astype(int)))
                if x + 1 >= GX or not occ[y, z, x + 1]:
                    d.polygon([q(x + 1, y, z), q(x + 1, y, z + 1), q(x + 1, y + 1, z + 1), q(x + 1, y + 1, z)],
                              fill=tuple((c * 0.7).astype(int)))
    img.save(path)


if __name__ == "__main__":
    raise SystemExit(main())
