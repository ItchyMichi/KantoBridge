"""Reading a Crystal building drawing's facade, and the shared archetype builders (docs/MODEL_AUTHORING_WORKFLOW.md section 5).

Gen 2's town buildings are made of standard tiles, so their features share colours across towns: the lit windows' panes and
the door's frame are yellow (255,255,58), the door's lintel orange (255,132,8), the sign bars pink (255,156,197), outlines
dark (58,58,58). `read()` finds, in a drawing (drawing pixels; x across, z down the rows): the row where the facade starts
(under the roof's last full dark eave line), the door, the windows and the signs. Every builder takes the reading and
returns a parts spec (building_parts' boxes and profiles); the module that uses it checks every reading against the
drawing (an overlay sheet) before any spec is written.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

from tools.shape_fit.crystal_pass.building_parts import box, paint, profile

YELLOW, ORANGE, PINK, DARK = (255, 255, 58), (255, 132, 8), (255, 156, 197), (58, 58, 58)


@dataclass
class Facade:
    w: int
    d: int
    top: int                       # the facade's first row (under the roof's eave lines)
    door: tuple | None = None      # [x0, z0, x1, z1)
    windows: list = field(default_factory=list)
    signs: list = field(default_factory=list)    # (rect, kind) kind "stand" (on legs, beside the door) or "wall"


def _mask(a, col):
    return np.all(a == np.array(col), axis=2)


def _components(m):
    """4-connected components of a mask: their bounding boxes [x0, z0, x1, z1)."""
    seen = np.zeros_like(m)
    out = []
    for z, x in zip(*np.nonzero(m)):
        if seen[z, x]:
            continue
        stack, xs, zs = [(z, x)], [], []
        seen[z, x] = True
        while stack:
            cz, cx = stack.pop()
            xs.append(cx)
            zs.append(cz)
            for dz, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nz, nx = cz + dz, cx + dx
                if 0 <= nz < m.shape[0] and 0 <= nx < m.shape[1] and m[nz, nx] and not seen[nz, nx]:
                    seen[nz, nx] = True
                    stack.append((nz, nx))
        out.append([min(xs), min(zs), max(xs) + 1, max(zs) + 1])
    return out


def facade_top(a, upto=0.75):
    """The facade's first row: under the last row (in the upper `upto` of the drawing) that is at least 90% the darkest
    colour (the roof's eave line)."""
    dark = _mask(a, DARK)
    rows = [z for z in range(int(a.shape[0] * upto)) if dark[z].mean() >= .9]
    return (max(rows) + 1) if rows else None


def read(a, top=None):
    a = np.asarray(a).astype(int)
    d, w = a.shape[:2]
    top = facade_top(a) if top is None else top
    f = Facade(w, d, top)
    if top is None:
        return f
    sub = a[top:]
    orange = _mask(sub, ORANGE)
    yellow = _mask(sub, YELLOW)
    pink = _mask(sub, PINK)
    # the door: the topmost orange row whose yellow/orange run spans a whole frame (12 px or more: the lintel row; the
    # door's inner bars are shorter or boxed by the outline); that run is the frame's width; down to the drawing's base
    for z0 in range(sub.shape[0]):
        if not orange[z0].any():
            continue
        x_l = int(np.nonzero(orange[z0])[0][0])
        x0 = x_l
        while x0 - 1 >= 0 and (orange[z0, x0 - 1] or yellow[z0, x0 - 1]):
            x0 -= 1
        x1 = x_l
        while x1 + 1 < w and (orange[z0, x1 + 1] or yellow[z0, x1 + 1]):
            x1 += 1
        if x1 + 1 - x0 >= 12:
            f.door = (int(x0), int(top + z0), int(x1 + 1), int(d - 1))
            break
    # signs: pink bars; at the facade's top a wall sign, else a standing sign beside the door
    for x0, z0, x1, z1 in _components(pink):
        if x1 - x0 < 12:   # a sign's bar spans a whole tile pair; the pink bricks of some towns are shorter runs
            continue
        kind = "wall" if z0 <= 2 else "stand"
        f.signs.append(([int(x0), int(top + z0), int(x1), int(top + z0)], kind))
    # windows: yellow components outside the door and signs, merged where their 1-px frames touch
    boxes = []
    for x0, z0, x1, z1 in _components(yellow):
        r = [x0 - 1, top + z0 - 1, x1 + 1, top + z1 + 1]
        if f.door and r[0] < f.door[2] and r[2] > f.door[0] and r[3] > f.door[1]:
            continue
        if (x1 - x0) * (z1 - z0) < 4:
            continue
        boxes.append(r)
    merged = True
    while merged:
        merged = False
        for i in range(len(boxes)):
            for j in range(i + 1, len(boxes)):
                p, q = boxes[i], boxes[j]
                if p[0] <= q[2] and q[0] <= p[2] and p[1] <= q[3] and q[1] <= p[3]:
                    boxes[i] = [min(p[0], q[0]), min(p[1], q[1]), max(p[2], q[2]), max(p[3], q[3])]
                    boxes.pop(j)
                    merged = True
                    break
            if merged:
                break
    f.windows = [tuple(int(v) for v in b) for b in sorted(boxes, key=lambda b: (b[1], b[0]))]
    return f


def overlay(img, f: Facade, scale=4):
    """The drawing with the reading drawn on it (facade line cyan, door red, windows blue, signs magenta)."""
    from PIL import ImageDraw
    from PIL import Image
    im = img.resize((img.width * scale, img.height * scale), Image.Resampling.NEAREST)
    dr = ImageDraw.Draw(im)
    if f.top is not None:
        dr.line([(0, f.top * scale), (im.width, f.top * scale)], fill=(0, 220, 255), width=2)
    if f.door:
        x0, z0, x1, z1 = f.door
        dr.rectangle([x0 * scale, z0 * scale, x1 * scale - 1, z1 * scale - 1], outline=(255, 0, 0), width=2)
    for x0, z0, x1, z1 in f.windows:
        dr.rectangle([x0 * scale, z0 * scale, x1 * scale - 1, z1 * scale - 1], outline=(0, 60, 255), width=2)
    for (x0, z0, x1, _), kind in f.signs:
        dr.rectangle([x0 * scale, z0 * scale, x1 * scale - 1, (z0 + 1) * scale], outline=(255, 0, 255), width=3)
    return im


def free_swatch(f: Facade, z0, z1, width=8):
    """A swatch of plain wall: `width` columns of the facade between the posts that no door, window or sign crosses."""
    busy = np.zeros(f.w, bool)
    busy[:7] = busy[f.w - 7:] = True
    for r in ([f.door] if f.door else []) + list(f.windows) + [s[0] for s in f.signs]:
        busy[max(0, r[0] - 1):min(f.w, r[2] + 1)] = True
    run = 0
    for x in range(f.w):
        run = run + 1 if not busy[x] else 0
        if run == width:
            return [x - width + 1, z0, x + 1, z1]
    return [7, z0, 7 + width, z1]


def gable(span, rise, lip=1.5, n=None):
    """Heights across a plain gable of `span` voxels: a lip at both eaves, a straight rise to the ridge at the middle."""
    n = n or span // 2 + 1
    out = []
    for i in range(n):
        t = i / (n - 1)
        out.append(lip + (rise - lip) * (1 - abs(2 * t - 1)))
    return out


def slope(span, a, b, n=None):
    """Heights falling (or rising) in a straight line from `a` to `b` across `span` voxels."""
    n = n or max(2, span // 2 + 1)
    return [a + (b - a) * i / (n - 1) for i in range(n)]


def johto(f: Facade, *, scale=None, rise=None, sign_rows=8, roof=True, top_y=None):
    """A Johto town building from its reading: a plinth, walls behind ochre corner posts (the facade's art stretched over
    the front, a plain swatch along the sides), a plain gable roof over the whole footprint with its eaves overhanging (the
    roof's field laid along the slope, the eave bands on its edges), a real door recess with the door at its back, the
    windows and wall signs standing a voxel proud, a standing sign on its two legs in front."""
    W, D, top = f.w, f.d, f.top
    F = D - top
    wh = round(max(22, F * (scale or (1.0 if F >= 28 else 1.375))))   # Codex's b010: 14 facade rows, 20 high
    if top_y is not None:   # a fixed wall top (a walls-only template meeting what stands behind it)
        wh = top_y - 2
    zf, zb = D - 3, 3
    y0 = 2
    yt = y0 + wh

    def y_of(row):   # a facade row's height on the wall (the drawing's base row at the plinth)
        return y0 + wh * (D - 1 - row) / (F - 1)

    swatch = free_swatch(f, top + 1, D - 2)
    wall_top = [3, top, W - 3, top + 1] if not roof else None
    parts = [
        box((1, W - 1), (zb - 1, D), (0, y0), paint([1, D - 1, W - 1, D], top=[2, D - 1, W - 2, D], side=[1, D - 1, 3, D])),
        box((3, W - 3), (zb if roof else 0, zf), (y0, yt), paint([3, top, W - 3, D - 1], top=wall_top, side=swatch, back=swatch)),
    ]
    post = [1, top, 3, D - 1]
    for px in ((1, 3), (W - 3, W - 1)):
        for pz in ((zf - 3, zf), (zb, zb + 3)):
            parts.append(box(px, pz, (y0, yt), paint(post, top=post, side=post)))
    r = rise or round(max(8, top * 0.6))
    band = [0, max(0, top - 4), W, top]
    field = [2, 1, W - 2, max(2, top - 4)]
    if not roof:
        return _finish(f, parts, y0, yt, zf, zb, wh, D, F, sign_rows, y_of, swatch)
    # two slopes meeting at the ridge (z = D/2): the front slope carries the drawn roof from the ridge (its first rows)
    # down to the eave (its last), as the drawing shows it; the back slope the same art, rarely seen
    mid = D // 2
    parts.append(profile((0, W), (0, mid), (yt - 2, yt - 1 + r), "x", slope(mid, 1.5, r + 1),
                         paint(band, top=field, side=swatch, back=band)))
    parts.append(profile((0, W), (mid, D), (yt - 2, yt - 1 + r), "x", slope(D - mid, r + 1, 1.5),
                         paint(band, top=field, side=swatch, back=band)))
    return _finish(f, parts, y0, yt, zf, zb, wh, D, F, sign_rows, y_of, swatch)


def _finish(f, parts, y0, yt, zf, zb, wh, D, F, sign_rows, y_of, swatch):
    """The door recess, windows and signs over a facade at z = zf (shared by the archetypes)."""
    if f.door:
        x0, z0, x1, z1 = f.door
        dh = y_of(z0)
        parts.append(box((x0 + 1, x1 - 1), (zf - 4, zf + 1), (y0, round(dh)), carve=True))
        parts.append(box((x0 + 1, x1 - 1), (zf - 4, zf - 3), (y0, round(dh)), paint([x0 + 1, z0 + 1, x1 - 1, z1], side=[x0 + 1, z0 + 1, x0 + 2, z1])))
    for x0, z0, x1, z1 in f.windows:
        parts.append(box((x0, x1), (zf, zf + 1), (round(y_of(z1)), round(y_of(z0))), paint([x0, z0, x1, z1], side=[x0, z0, x0 + 1, z1])))
    for (x0, z0, x1, _), kind in f.signs:
        board = [x0, z0, x1, min(D - 1, z0 + sign_rows)]
        ytop, ybot = round(y_of(z0)), round(y_of(board[3]))
        if kind == "wall":
            parts.append(box((x0, x1), (zf, zf + 1), (ybot, ytop), paint(board, side=[x0, z0, x0 + 1, board[3]])))
        else:
            parts.append(box((x0, x1), (zf + 1, zf + 3), (ybot, ytop), paint(board, top=[x0, z0, x1, z0 + 1], side=[x0, z0, x0 + 1, board[3]])))
            for lx in ((x0 + 2, x0 + 4), (x1 - 4, x1 - 2)):
                leg = [lx[0], board[3], lx[1], D - 2]
                parts.append(box(lx, (zf + 1, zf + 3), (y0, ybot), paint(leg, side=leg)))
    height = max(p["y"][1] for p in parts) + 1
    return {"height": int(round(height)), "parts": parts}


# ---- Kanto: hip roofs (Viridian, Pewter, Cerulean, Vermilion, Lavender, Celadon, Fuchsia, Saffron, Pallet) ----

@dataclass
class KantoFacade:
    w: int
    d: int
    eave: int                 # the roof's full dark eave line over the window band (the band starts the row after)
    awning: tuple | None      # [z0, z1) rows of a roof band between the window band and the ground floor
    door: tuple | None


def kanto_read(a):
    """A Kanto hip-roof drawing: the eave line (under the roof field: see below), the
    window band under it (eight rows), an awning band below that (rows whose middle half is mostly the roof field's two
    main colours), and the door (as `read`)."""
    a = np.asarray(a).astype(int)
    d, w = a.shape[:2]
    dark = _mask(a, DARK)
    mid = slice(w // 4, 3 * w // 4)
    # the eave: the first row under the roof field (below the top outline) whose middle half is all dark; where the triple
    # eave line follows (dark, a light course, dark) the band starts under its second dark row
    eave = next(z for z in range(2, int(d * .7)) if dark[z, mid].all())
    if eave + 2 < d and dark[eave + 2, mid].all():
        eave += 2
    from collections import Counter
    field = Counter(tuple(c) for c in a[2:max(3, eave - 2), w // 4:3 * w // 4].reshape(-1, 3)).most_common(2)
    fcols = [np.array(c) for c, _ in field]
    is_field = np.zeros((d, w), bool)
    for c in fcols:
        is_field |= np.all(a == c, axis=2)
    rows = [z for z in range(eave + 9, d) if is_field[z, mid].mean() > .6]
    awning = None
    if rows:
        z0 = rows[0]
        z1 = z0
        while z1 + 1 < d and (z1 + 1 in rows or dark[z1 + 1, mid].mean() > .6):
            z1 += 1
        if z1 - z0 >= 3:
            awning = (z0, z1 + 1)
    door = read(a, top=eave + 1).door
    return KantoFacade(w, d, eave, awning, door)


def kanto(k: KantoFacade, *, scale=None):
    """A Kanto town building from its reading: a plinth; the lower walls between grey stone pillars (the facade below the
    window band over the front); a hip roof over the footprint from the band's bottom (45 degrees, the drawn hatch); the
    window band as a wide dormer standing out of the roof's front slope (x 16..W-16), roofed by the drawn front field that
    slopes back into the hip; an awning band as a lean-to over the ground floor; the door recessed."""
    W, D, E = k.w, k.d, k.eave
    F = D - (E + 1)
    wh = round(max(22, F * (scale or (1.0 if F >= 28 else 1.375))))
    y0, zf, zb = 2, D - 3, 3

    def y_of(row):
        return y0 + wh * (D - 1 - row) / (F - 1)

    hatch = [1, 8, 13, min(E, 20)]
    yl = round(y_of(E + 9))                      # the band's bottom: the hip's eaves at the sides
    yb = round(y_of(E + 1)) + 1                  # the band's top
    lower = [4, E + 9, W - 4, D - 1]
    swatch = free_swatch(Facade(W, D, E + 9, k.door), E + 10, D - 2)
    parts = [box((1, W - 1), (zb - 1, D), (0, y0), paint([1, D - 1, W - 1, D], top=[2, D - 1, W - 2, D], side=[1, D - 1, 3, D]))]
    parts.append(box((4, W - 4), (zb, zf), (y0, yl), paint(lower, side=swatch, back=swatch)))
    pillar = [1, E + 9, 4, D - 1]
    for px in ((1, 4), (W - 4, W - 1)):
        for pz in ((zf - 3, zf), (zb, zb + 3)):
            parts.append(box(px, pz, (y0, yl), paint(pillar, top=pillar, side=pillar)))
    hx, hz = W / 2, D / 2
    R = round(min(hx, hz))
    # the slopes in the drawn front field (the roof's own courses; the 12-px hatch stretched over the footprint would
    # break into large patches), the hatch on the eave edges where it repeats at its own scale
    parts.append({"shape": "hip", "x": [0, W], "z": [0, D], "y": [yl - 1, yl - 1 + R],
                  "paint": paint([0, E + 5, 16, E + 9], top=[16, 1, W - 16, E - 1], side=hatch, back=hatch)})
    zd = D // 2
    band = [16, E + 1, W - 16, E + 9]
    parts.append(box((16, W - 16), (zd, zf), (yl, yb), paint(band, top=[17, E + 2, 22, E + 3], side=[16, E + 1, 18, E + 9])))
    # the drawn front field: from the band's top at the front up into the hip, whose height over z = zd - 2 is at most R
    back = zd - 2
    hip_at = min(R, R * (D - back) / min(hx, hz))
    rise = max(4, round(yl - 1 + hip_at - yb + 2))
    field = [16, 1, W - 16, E - 1]
    parts.append(profile((14, W - 14), (back, zf + 2), (yb - 1, yb + rise), "x", slope(zf + 2 - back, rise, 1.5),
                         paint([14, E - 2, W - 14, E + 1], top=field, side=hatch, back=hatch)))
    if k.awning:
        a0, a1 = k.awning
        ya = round(y_of(a1 - 1))
        parts.append(profile((4, W - 4), (zf - 4, D), (ya - 1, ya + 4), "x", slope(D - (zf - 4), 5, 1.5),
                             paint([4, a1 - 3, W - 4, a1], top=[4, a0, W - 4, a1], side=[4, a0, 6, a1])))
    if k.door:
        x0, z0, x1, z1 = k.door
        dh = round(y_of(z0))
        parts.append(box((x0 + 1, x1 - 1), (zf - 4, zf + 1), (y0, dh), carve=True))
        parts.append(box((x0 + 1, x1 - 1), (zf - 4, zf - 3), (y0, dh), paint([x0 + 1, z0 + 1, x1 - 1, z1], side=[x0 + 1, z0 + 1, x0 + 2, z1])))
    height = max(p["y"][1] for p in parts) + 1
    return {"height": int(round(height)), "parts": parts}


# ---- Kanto: flat roofs with a parapet (Celadon, Saffron, Cerulean's Mart, Lavender, Fuchsia, Pewter, Indigo) ----

def flat_eave(a):
    """The cornice's last row: as kanto_read's eave (the first all-dark middle row under the roof, stepping to the
    triple line's second dark row); None where the drawing has no roof (a facade alone)."""
    a = np.asarray(a).astype(int)
    d, w = a.shape[:2]
    dark = _mask(a, DARK)
    mid = slice(w // 4, 3 * w // 4)
    rows = [z for z in range(2, int(d * .7)) if dark[z, mid].all()]
    if not rows:
        return None
    eave = rows[0]
    if eave + 2 < d and dark[eave + 2, mid].all():
        eave += 2
    return eave


def kanto_flat(a, *, eave=None, scale=None, roof_only=False, walls_only=False, base=30):
    """A Kanto flat-roofed building: a plinth; walls between the grey stone pillars (the facade under the cornice over the
    front, 1:1 for tall facades); a flat roof slab over the footprint, the drawn roof field on top and the cornice on its
    edges; a parapet three voxels high round it; the door recessed. `roof_only`: a drawing that is all roof (the walls
    belong to another claim): the slab and parapet on a low base."""
    a = np.asarray(a).astype(int)
    D, W = a.shape[:2]
    E = -1 if walls_only else (flat_eave(a) if eave is None else eave)
    y0, zf, zb = 2, D - 3, 3
    field = [8, 2, W - 8, max(3, E - 3)]
    cornice = [3, max(0, E - 2), W - 3, E + 1]
    parts = [box((1, W - 1), (zb - 1, D), (0, y0), paint([1, D - 1, W - 1, D], top=[2, D - 1, W - 2, D], side=[1, D - 1, 3, D]))]
    if roof_only:   # its walls are another claim's (or Tier A's): the roof at the height the runtime gave the claim
        yt = base
        parts.append(box((3, W - 3), (zb, zf), (y0, yt), paint(field, side=field, back=field)))
        door = None
        F = 1
    else:
        F = D - (E + 1)
        wh = round(max(22, F * (scale or (1.0 if F >= 28 else 1.375))))
        yt = y0 + wh
        f = Facade(W, D, E + 1)
        door = read(a, top=E + 1).door
        f.door = door
        swatch = free_swatch(f, E + 9, D - 6)
        parts.append(box((5, W - 5), (zb, zf), (y0, yt), paint([5, E + 1, W - 5, D - 1], side=swatch, back=swatch)))
        pillar = [1, E + 1, 5, D - 1]
        for px in ((1, 5), (W - 5, W - 1)):
            for pz in ((zf - 4, zf), (zb, zb + 4)):
                parts.append(box(px, pz, (y0, yt), paint(pillar, top=pillar, side=pillar)))
    if walls_only:   # the roof is not drawn (beyond the map's edge): a cap in the wall's plain plaster (a 2x2 patch)
        light = np.all(a == np.array((222, 255, 222)), axis=2)
        z, x = next((z, x) for z in range(8, D - 8) for x in range(8, W - 8) if light[z:z + 2, x:x + 2].all())
        cap = [int(x), int(z), int(x) + 2, int(z) + 2]
        parts.append(box((3, W - 3), (zb - 1, zf + 1), (yt, yt + 2), paint(cap, top=cap, side=cap, back=cap)))
    else:
        parts.append(box((1, W - 1), (zb - 2, zf + 2), (yt, yt + 3), paint(cornice, top=field, side=cornice, back=cornice)))
    for x, z in ([] if walls_only else (((1, W - 1), (zb - 2, zb)), ((1, W - 1), (zf, zf + 2)), ((1, 3), (zb - 2, zf + 2)), ((W - 3, W - 1), (zb - 2, zf + 2)))):
        parts.append(box(x, z, (yt + 3, yt + 6), paint(cornice, top=[3, 0, W - 3, 2], side=cornice, back=cornice)))
    if door:
        x0, z0, x1, z1 = door

        def y_of(row):
            return y0 + (yt - y0) * (D - 1 - row) / (F - 1)
        dh = round(y_of(z0))
        parts.append(box((x0 + 1, x1 - 1), (zf - 4, zf + 1), (y0, dh), carve=True))
        parts.append(box((x0 + 1, x1 - 1), (zf - 4, zf - 3), (y0, dh), paint([x0 + 1, z0 + 1, x1 - 1, z1], side=[x0 + 1, z0 + 1, x0 + 2, z1])))
    height = max(p["y"][1] for p in parts) + 1
    return {"height": int(round(height)), "parts": parts}
