#!/usr/bin/env python3
"""Buildings as a box with 3D decorations (docs/specs/HAND_MODELS.md section 7): a prototype from one building's drawing.

    python building_model.py <map art.png> <map info.json> <x0> <y0> <w> <h> <out dir> [--roof-rows N] [--wall V]

The building is cells (x0, y0)..(x0 + w - 1, y0 + h - 1) of the map, drawn top-down in three-quarter view: the upper
rows are the roof seen from above, the lower `--roof-rows`-less rows the facade (default: all but the last cell row are
roof). Voxels are 16 a cell (8 a block); x east, y up, z south.

    box       a solid box over the footprint, the facade's rows times --wall voxels high, a ROOF-voxel slab on top; facade
              rows named in --bands (a porch roof) stand out BAND voxels
    front     the facade drawing stretched over the wall's height on the south face; the door (a walkable cell of the
              footprint's last row) recessed DOOR voxels; the facade's other details (pixels not its main colour)
              standing out 1 voxel
    roof      the top shows the roof drawing from above (its rows across the depth); its last rows, the eave, stand out
              EAVE voxels past the south face and SIDE voxels past the east and west faces, a slab under the roof line
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
EAVE, SIDE, ROOF, DOOR, BAND = 4, 2, 4, 3, 3


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
    # the model's grid: the footprint plus the eaves around it
    GX, GZ, GY = X + 2 * SIDE, Z + EAVE, wall + ROOF
    occ = np.zeros((GY, GZ, GX), bool)
    col = np.zeros((GY, GZ, GX, 3), np.uint8)
    ox = SIDE  # the footprint's x offset in the grid

    # the ground around the building (the drawing's corner) is not the building: the roof's corners take the roof's main
    # colour, the sides the facade's outermost column that is not ground
    bg = draw[0, 0].copy()
    is_bg = lambda c: np.abs(c.astype(int) - bg.astype(int)).sum() < 12  # noqa: E731
    if len(roof):
        rflat = roof.reshape(-1, 3)
        rflat = rflat[np.abs(rflat.astype(int) - bg.astype(int)).sum(axis=1) >= 12]
        if len(rflat):
            ru, rc = np.unique(rflat, axis=0, return_counts=True)
            rmain = ru[int(np.argmax(rc))]
            roof = np.where((np.abs(roof.astype(int) - bg.astype(int)).sum(axis=2) < 12)[..., None], rmain, roof).astype(np.uint8)
    cols = [x for x in range(X) if not all(is_bg(facade[r, x]) for r in range(facade.shape[0]))]
    left, right = (cols[0], cols[-1]) if cols else (0, X - 1)
    # the facade's main colour (its wall) and its door columns
    flat = facade.reshape(-1, 3)
    uniq, counts = np.unique(flat, axis=0, return_counts=True)
    main = uniq[int(np.argmax(counts))]
    door = [cx for cx in range(w) if info["kind"][(y0 + h - 1) * info["w"] + x0 + cx] == "W"]

    def facade_px(x, y):
        """The facade drawing's pixel for wall column x (0..X-1) at height y (0..wall-1), stretched over the wall."""
        r = facade.shape[0] - 1 - int(y * facade.shape[0] / wall)
        return facade[r, x]

    # the box: walls up to `wall`, the roof slab above; the front face's relief
    for y in range(wall):
        for x in range(X):
            px = facade_px(x, y)
            r = facade.shape[0] - 1 - int(y * facade.shape[0] / wall)
            is_door = (x // W) in door and 3 <= x % W <= 12 and r >= facade.shape[0] - 11
            band = any(a <= r <= b for a, b in bands)
            detail = not is_door and np.abs(px.astype(int) - main.astype(int)).sum() > 30
            front = Z - 1 - (DOOR if is_door else 0) + (BAND if band else 1 if detail else 0)
            for z in range(0, min(front + 1, GZ)):
                occ[y, z, ox + x] = True
                col[y, z, ox + x] = px if z >= front - 1 else facade[0, x] if z > Z - 3 else facade[facade.shape[0] // 2, 0 if x < X // 2 else -1]
    # the sides show the facade's edge columns, the back the roof's top row
    for y in range(wall):
        for z in range(Z):
            col[y, z, ox] = facade_px(left, y)
            col[y, z, ox + X - 1] = facade_px(right, y)
        for x in range(X):
            col[y, 0, ox + x] = roof[0, x] if len(roof) else facade_px(x, y)
    # the roof slab: the roof drawing from above, rows across the depth; the eave past the front and sides
    rr = max(1, len(roof))
    for y in range(wall, wall + ROOF):
        for z in range(GZ):
            r = min(rr - 1, int(z * rr / Z)) if z < Z else rr - 1
            for gx in range(GX):
                x = min(X - 1, max(0, gx - ox))
                occ[y, z, gx] = True
                col[y, z, gx] = roof[r, x] if len(roof) else main
    # under the eave: one voxel thick at the roof line (the overhang's underside), so it reads as protruding
    for z in range(Z, GZ):
        for gx in range(GX):
            occ[wall - 1, z, gx] = True
            col[wall - 1, z, gx] = roof[rr - 1, min(X - 1, max(0, gx - ox))] if len(roof) else main

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
