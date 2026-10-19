#!/usr/bin/env python3
"""Crystal's copy (tools/shape_fit/crystal/README.md). Buildings as a box with 3D decorations (docs/specs/HAND_MODELS.md section 7): a prototype from one building's drawing.

    python building_model.py <map art.png> <map info.json> <x0> <y0> <w> <h> <out dir> [--roof-rows N] [--wall V]

The building is cells (x0, y0)..(x0 + w - 1, y0 + h - 1) of the map, drawn top-down in three-quarter view: the upper
rows are the roof seen from above, the lower rows the facade. The roof's end, its style and any porch bands are
detected from the drawing's dark outline (detect()); --roof-rows, --roof and --bands override them. Voxels are 16 a cell (8 a block); x east, y up, z south.

    cube      a decorated box as wide as the facade's drawn walls, the facade's rows times --wall voxels high; facade
              rows named in --bands (a porch roof) stand out BAND voxels
    front     the facade drawing stretched over the wall's height on the south face; the door (a walkable cell of the
              footprint's last row) recessed DOOR voxels; the facade's other details (pixels not its main colour)
              standing out 1 voxel
    roof      a separate topper on the cube: a stepped hip RH voxels high (STEP-voxel steps, each STEP in from every side),
              as wide as the drawn roof (the eaves overhang the walls) and reaching EAVE past the front; its top shows
              the roof drawing from above (its rows across the depth); --roof flat: a slab FLAT voxels thick with a
              PARAPET-voxel rim, its front the roof's edge rows (a department store's roof garden)
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
FLAT, PARAPET = 4, 2  # a flat roof's slab and its parapet (voxels)


def outline(draw: np.ndarray) -> np.ndarray:
    """The pixels in the outline's colour: the darkest colour within 30, pure black (missing art in the mosaic) left out."""
    lum = draw.astype(int).sum(-1)
    real = lum[lum > 0]
    return (lum <= (real.min() if len(real) else 0) + 30) & (lum > 0)


def detect(draw: np.ndarray) -> dict:
    """The building's parts from its drawing, by its dark outline (Gen 1's four colours share the ground's white, so the
    outline is the one reliable signal):

        roof_rows  the roof ends at its first double line (two rows mostly dark, one row apart) below row 4 (a run
                   of near-full ones two rows apart, a striped tile roof, counting as one: the roof ends after it,
                   within 70% of the rows), else at its
                   first single line (75% dark) below row 8, else half way for a building up to two cells deep, else
                   0 (no roof drawn: all wall); within 60% of the rows
        bands      a later double line (or run: a storey's eave) closes a porch roof: from the dark row above it (a
                   third dark or more) to it, at most 12 rows, in facade rows
        roof       hip when the roof's outline moves sideways down its rows (a sloped side), else flat
    """
    dark = outline(draw)
    frac = dark.mean(1)
    n = len(frac)
    doubles = [r for r in range(4, n - 2) if frac[r] >= 0.5 and frac[r + 2] >= 0.5 and frac[r + 1] < 0.3]
    # a run of double lines two rows apart is one line (a striped tile roof, a storey's eave): (first, last) per run
    runs = []
    full = lambda r: frac[r] >= 0.8 and frac[r + 2] >= 0.8  # noqa: E731  (a tile stripe is near-full; a window row isn't)
    for r in doubles:
        if runs and r - runs[-1][1] <= 2 and full(r) and full(runs[-1][1]):
            runs[-1][1] = r
        else:
            runs.append([r, r])
    singles = [r for r in range(8, n) if frac[r] >= 0.75]  # fuller than a row of window frames
    if runs and runs[0][1] + 3 <= int((0.7 if runs[0][1] > runs[0][0] else 0.6) * n):
        roof_end = runs[0][1] + 3
    elif singles and singles[0] + 1 <= int(0.6 * n):
        roof_end = singles[0] + 1  # a single line closes the roof (a wide hall's)
    elif n <= 2 * W:
        roof_end = n // 2  # a low building (a gate) with no line: half roof
    else:
        roof_end = 0  # no roof line: the roof is off the map (a tower's lower floors), all wall
    bands = []
    for first, last in runs:
        if first <= roof_end + 2 or last + 2 >= n - 6:
            continue
        top = first
        for q in range(first - 1, roof_end + 1, -1):
            if frac[q] >= 0.3:
                top = q
                break
        if last + 2 - top <= 12:  # a porch roof or a storey's eave is short; taller spans are storeys
            bands.append((top - roof_end, last + 2 - roof_end))
    # hip or flat: where the first dark pixel of each roof row sits (from the left); a sloped side moves it
    starts = [int(np.argmax(dark[r])) for r in range(2, max(3, roof_end - 4)) if dark[r].any()]
    spread = (max(starts) - min(starts)) if starts else 0
    return {"roof_rows": roof_end, "bands": bands, "roof": "hip" if spread >= 6 else "flat", "spread": spread}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("art")
    ap.add_argument("info")
    ap.add_argument("x0", type=int)
    ap.add_argument("y0", type=int)
    ap.add_argument("w", type=int)
    ap.add_argument("h", type=int)
    ap.add_argument("out")
    ap.add_argument("--roof-rows", type=int, default=-1, help="drawing rows of roof (default: detected)")
    ap.add_argument("--wall", type=float, default=1.5, help="wall voxels a facade pixel (1.5: a door about two blocks)")
    ap.add_argument("--bands", default="", help="facade rows (from the facade's top) that stand out as an overhang: a-b,c-d")
    ap.add_argument("--name", default="")
    ap.add_argument("--roof", choices=("auto", "hip", "flat"), default="auto", help="the topper: a stepped hip, or a flat roof with a parapet")
    args = ap.parse_args()
    x0, y0, w, h = args.x0, args.y0, args.w, args.h
    info = json.loads(Path(args.info).read_text(encoding="utf-8"))
    art = Image.open(args.art).convert("RGB")
    draw = np.asarray(art.crop((x0 * ART, y0 * ART, (x0 + w) * ART, (y0 + h) * ART)).resize((w * W, h * W), Image.NEAREST))
    found = detect(draw)
    roof_rows = args.roof_rows if args.roof_rows >= 0 else found["roof_rows"]
    if args.roof == "auto":
        args.roof = found["roof"]
    if not args.bands and args.roof_rows < 0:
        args.bands = ",".join(f"{a}-{b}" for a, b in found["bands"])
    print("detected:", found, "using roof rows", roof_rows, "roof", args.roof, "bands", args.bands or "-")
    facade = draw[roof_rows:]  # rows of the front wall
    roof = draw[:roof_rows]
    wall = int(round(args.wall * facade.shape[0]))
    bands = [tuple(int(v) for v in b.split("-")) for b in args.bands.split(",") if b]
    X, Z = w * W, h * W  # the footprint in voxels
    # the ground around the building (the drawing's corner) is not the building
    bg = draw[0, 0].copy()
    ground = lambda img: np.abs(img.astype(int) - bg.astype(int)).sum(axis=-1) < 12  # noqa: E731
    # the cube: as wide as the facade's drawn walls (its columns that are not ground), so its pixels reach its edges
    # the walls end at their outline: the outermost columns drawn in the darkest colour down most of the facade (a pale
    # shadow strip on the ground beside the outline is not wall)
    dark = outline(facade)
    fcols = np.nonzero(dark.mean(axis=0) >= 0.5)[0]
    if not len(fcols):
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
    if not len(roof):
        # no roof drawn (it is off the map): a plain roof in the wall's colour
        roof = np.tile(main.astype(np.uint8), (FLAT, X, 1))
        rmain = main
    door = [cx for cx in range(w) if info["kind"][(y0 + h - 1) * info["w"] + x0 + cx] == "W"]

    # the grid: the footprint, the roof's overhang past it on the sides and the front, the roof's height above the walls
    pad = max(0, 1 - rl, rr_ + 1 - (X - 1))
    GX, GZ, GY = X + 2 * pad, Z + EAVE + max(BAND, 1), wall + RH
    ox = pad
    occ = np.zeros((GY, GZ, GX), bool)
    col = np.zeros((GY, GZ, GX, 3), np.uint8)

    # features that stand out: groups of 10+ touching pixels unlike the wall with some outline in them, under 60%
    # of the wall's width (windows, doors, signs), not bricks or mortar
    unlike = (np.abs(facade.astype(int) - main.astype(int)).sum(axis=-1) > 30) & ~ground(facade)
    feature = np.zeros_like(unlike)
    fdark = outline(facade)
    seen = np.zeros_like(unlike)
    FH, FW = unlike.shape
    for sy in range(FH):
        for sx in range(FW):
            if unlike[sy, sx] and not seen[sy, sx]:
                comp, todo = [], [(sy, sx)]
                seen[sy, sx] = True
                while todo:
                    cy_, cx_ = todo.pop()
                    comp.append((cy_, cx_))
                    for ny, nx in ((cy_ + 1, cx_), (cy_ - 1, cx_), (cy_, cx_ + 1), (cy_, cx_ - 1)):
                        if 0 <= ny < FH and 0 <= nx < FW and unlike[ny, nx] and not seen[ny, nx]:
                            seen[ny, nx] = True
                            todo.append((ny, nx))
                # a window, a door, a sign: outlined in black, not a pattern spanning the wall (bricks, mortar)
                outlined = any(fdark[cy_, cx_] for cy_, cx_ in comp)
                span = max(c for _, c in comp) - min(c for _, c in comp) + 1
                if len(comp) >= 10 and outlined and span * 10 < (right - left + 1) * 6:
                    for cy_, cx_ in comp:
                        feature[cy_, cx_] = True
    # the sides: the wall's colour, but a row one colour fills (70% or more: a trim band, the base) keeps it
    row_main = []
    for r in range(FH):
        px = facade[r, left + 2:right - 1]
        px = px[~ground(px)]
        band_colour = main
        if len(px):
            u, c = np.unique(px, axis=0, return_counts=True)
            if c.max() >= 0.7 * len(px):
                band_colour = u[int(np.argmax(c))]
        row_main.append(band_colour)

    def facade_row(y):
        return facade.shape[0] - 1 - int(y * facade.shape[0] / wall)

    # the decorated cube: the facade on its front, its edge columns on the sides, the roof's back row on the back
    for y in range(wall):
        r = facade_row(y)
        for x in range(left, right + 1):
            px = facade[r, x]
            is_door = (x // W) in door and 3 <= x % W <= 12 and r >= facade.shape[0] - 11
            band = any(a <= r <= b for a, b in bands)
            detail = not is_door and bool(feature[r, x])
            front = Z - 1 - (DOOR if is_door else 0) + (BAND if band else 1 if detail else 0)
            for z in range(front + 1):
                occ[y, z, ox + x] = True
                col[y, z, ox + x] = px if z >= front - 1 else facade[r, left] if x < (left + right) // 2 else facade[r, right]
            if ground(px):
                col[y, front, ox + x] = main
        for z in range(Z - 1):
            # the sides wear the wall just inside the outline (the outline itself stays on the front edge)
            col[y, z, ox + left] = row_main[r]
            col[y, z, ox + right] = row_main[r]
        for x in range(left, right + 1):
            col[y, 0, ox + x] = rmain if rmain is not None else main
    # the roof topper: a stepped hip on the cube, as wide as the drawn roof and reaching EAVE past the front; each step
    # STEP voxels high and STEP in from every side; its top shows the roof drawing from above (rows across the depth)
    depth = Z + EAVE
    rows = max(1, len(roof))
    if args.roof == "flat":
        # a flat roof: a slab FLAT voxels thick as wide as the drawn roof (overhanging), its front face the roof
        # drawing's last rows (the roof's edge), its top the roof drawing from above; a parapet PARAPET high round it
        x_lo, x_hi = rl - 1, rr_ + 1
        edge = roof[-FLAT:] if len(roof) >= FLAT else roof
        # the top: the roof's back rows at the back, its front rows at the front, its middle repeated at its own scale
        # (a roof garden's lattice stays square)
        top_rows, front_rows = min(2, rows), min(FLAT, rows)
        mid = max(1, rows - top_rows - front_rows)

        def top_row(z):
            if z < top_rows:
                return z
            if z >= depth - front_rows:
                return rows - (depth - z)
            return top_rows + (z - top_rows) % mid

        for y in range(wall, wall + FLAT + PARAPET):
            for z in range(depth):
                r = top_row(z)
                for x in range(x_lo, x_hi + 1):
                    rim = x in (x_lo, x_hi) or z in (0, depth - 1)
                    if y >= wall + FLAT and not rim:
                        continue
                    occ[y, z, ox + x] = True
                    xx = min(X - 1, max(0, x))
                    if z == depth - 1 and y < wall + FLAT:
                        col[y, z, ox + xx] = edge[len(edge) - 1 - (y - wall), xx]
                    else:
                        col[y, z, ox + x] = roof[r, xx]
    for k in range(RH // STEP if args.roof == "hip" else 0):
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
