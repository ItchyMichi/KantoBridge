"""Crystal checkpoint 22: the Johto outdoor tilesets (TILESET_JOHTO_1/2/3/5/6/8/9/10/11, TILESET_JOHTO_MODERN_1/2).

Source reading (study/groups/TILESET_JOHTO_*; the census per key):

- Trees (README: one cell's crown and trunk, a second cell's crown above, up to ~32 high): the standard Johto tree
  (2,278 / 2,280 placements) and its bushier variant (226) are drawn two cells tall and already stack (the crown top on
  the base): one rounded crown over both cells on a short trunk, each cell painted from its own drawing by height. The
  one-cell tree (654): a compact crown on a stub trunk.
- The 2x2 rock formations (122 each): one rounded mound over the four cells, painted from its composite.
- Fences: east-west runs (two capped posts, two rails), north-south runs (a post column on the drawn side, two rails),
  their ends; the sign boards (101: board on its base); the sea-rock field (747: a water plate and its four drawn rocks);
  the ledges (the ground's top over the dirt face, a low step); Goldenrod's flower bed and its rails.
- Retained: keys never drawn (building templates claim them, Tier A folds them, water, walkable), and the building wall
  and cliff blocks drawn as units in a few places outside the templates (their full-block models already read as the
  block).
"""
from __future__ import annotations

import json
from collections import Counter

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, STUDY, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, top_rect

S = (16, 16, 16)
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
FAMILIES = ["TILESET_JOHTO_1", "TILESET_JOHTO_2", "TILESET_JOHTO_3", "TILESET_JOHTO_5", "TILESET_JOHTO_6", "TILESET_JOHTO_8",
            "TILESET_JOHTO_9", "TILESET_JOHTO_10", "TILESET_JOHTO_11", "TILESET_JOHTO_MODERN_1", "TILESET_JOHTO_MODERN_2"]

TREES = [(K("367-368-751-752"), K("751-752-371-372")), (K("367-368-369-370"), K("369-370-371-372"))]
SMALL_TREE = K("367-368-371-372")
ROCK = [[K("383-384-386-387"), K("384-385-387-388")], [K("386-387-389-390"), K("387-388-390-391")]]
FENCE_EW = {K("393-393-394-394"): None, K("393-393-449-394"): "e", K("393-393-394-449"): "w"}
FENCE_NS = {K("361-449-361-449"): "e", K("449-361-449-361"): "w"}
SIGN = K("445-446-447-448")
SEA_ROCKS = K("438-438-438-438")
LEDGES = [K(k) for k in ("361-361-390-390", "362-362-390-390", "361-388-390-391", "386-361-389-390", "386-362-386-362")]
FLOWER_BED = K("468-469-471-472")
RAILS = [K("477-477-361-361"), K("477-477-377-377")]


def own(key, n=4):
    c = Cell(key)
    return [col for col, _ in Counter(tuple(int(a) for a in px) for px in c.pixels.reshape(-1, 3)).most_common(n)]


def tree(top, base):
    """One tree over its two stacked cells (world y 0-31): a trunk (x 7-8, y 0-6) under a rounded crown (y 5-30). Each
    cell paints from its own drawing: the crown's faces by height over the two drawings' crown rows (top rows 0-15, base
    rows 0-7), its upper surface from the top cell's drawing, the trunk from the base's trunk rows (9-14)."""
    yy, zz, xx = np.indices((32, 16, 16))
    crown = ((xx + .5 - 8) / 7.6) ** 2 + ((yy + .5 - 17.5) / 12.5) ** 2 + ((zz + .5 - 8) / 7.4) ** 2 <= 1
    trunk = (xx >= 7) & (xx < 9) & (zz >= 7) & (zz < 9) & (yy < 7)
    occ = crown | trunk
    e = F.exposed(occ)
    out = {}
    for key, y0 in ((base, 0), (top, 16)):
        c = Cell(key)
        part = occ[y0:y0 + 16]
        paint = np.zeros(part.shape, np.uint8)
        greens = [col for col in own(key, 6)]
        for y, z, x in np.argwhere(part):
            yw = y + y0
            if trunk[yw, z, x] and not crown[yw, z, x]:
                u, v = x + .5, 9 + (6 - yw) * 5 / 7 + .5
            elif e["up"][yw, z, x] and yw >= 16:
                u, v = x + .5, z * 0.55 + .5           # the crown's upper surface: the top drawing's upper rows
            else:
                g = (29.5 - yw) / 25 * 24             # height to the composite's crown rows (top 0-15, base 16-23)
                face_u = x + .5 if (e["south"][yw, z, x] or e["north"][yw, z, x]) else z + .5
                u, v = face_u, g - y0 * 0 if y0 == 16 else g - 16
                if y0 == 16:
                    v = min(15.5, max(.5, g))
                else:
                    v = min(7.5, max(.5, g - 16))
            paint[y, z, x] = int(min(15, max(0, v))) * 16 + int(min(15, max(0, u)))
        out[key] = (part.copy(), paint)
    return out


def small_tree():
    """The one-cell tree: a compact crown (drawn rows 0-10) on a stub trunk (rows 11-15)."""
    c = Cell(SMALL_TREE)
    b = Build(c)
    crown = F.ellipsoid(S, (8, 9.5, 8), (7.2, 6.5, 7))
    b.add(crown, top=top_rect((0, 0, 16, 9), (0.8, 15.2), (1, 15)), front=front_rect((0, 0, 16, 11), (0.8, 15.2), (3, 16)), side=own(SMALL_TREE))
    b.add(box(S, (7, 9), (0, 4), (7, 9)), top=own(SMALL_TREE), front=front_rect((6, 11, 10, 16), (7, 9), (0, 4)), side=own(SMALL_TREE))
    return b.done()


def union(keys):
    out = []
    for k in keys:
        for col in own(k, 8):
            if col not in out:
                out.append(col)
    return out


def rock():
    yy, zz, xx = np.indices((16, 32, 32))
    occ = F.ellipsoid((16, 32, 32), (16, 1, 16), (15.5, 14, 15.5)) & (yy >= 0)

    def colour_at(y, gz, gx, face):
        return (gx + .5, gz + .5) if face == "up" else (gx + .5, min(31.5, gz + .5 + (14 - y) * .4))
    return I.paint_global(occ, ROCK, colour_at, union([k for row in ROCK for k in row]))


def fence_ew(key, end):
    """An east-west fence run: two capped posts (drawn columns 1-5 and 9-13) and two rails (rows 6-7 and 14-15)."""
    c = Cell(key)
    b = Build(c)
    m = own(key)
    for x in ((1, 6), (9, 14)):
        b.add(box(S, x, (0, 12), (6, 10)), top=top_rect((x[0], 0, x[1], 5), x, (6, 10)), front=front_rect((x[0], 0, x[1], 14), x, (0, 12)), side=m)
    x0, x1 = (1 if end == "w" else 0), (14 if end == "e" else 16)
    for y in ((7, 9), (1, 3)):
        b.add(box(S, (x0, x1), y, (7, 9)), top=m, front=front_rect((0, 6, 16, 8), (0, 16), y), side=m)
    return b.done()


def fence_ns(key, side):
    """A north-south fence run along the drawn side: posts (two per cell) and two rails along the run."""
    c = Cell(key)
    b = Build(c)
    m = own(key)
    x = (10, 14) if side == "e" else (2, 6)
    for z in ((1, 5), (9, 13)):
        b.add(box(S, x, (0, 12), z), top=top_rect((x[0], z[0], x[1], z[1]), x, z), front=m, side=m)
    xr = (x[0] + 1, x[1] - 1)
    for y in ((7, 9), (1, 3)):
        b.add(box(S, xr, y, (0, 16)), top=m, front=m, side=m)
    return b.done()


def sign():
    """A sign board: the framed board with its text (drawn rows 1-10) above its base (rows 10-14)."""
    c = Cell(SIGN)
    b = Build(c)
    m = own(SIGN)
    b.add(box(S, (1, 15), (6, 15), (7, 9)), top=m, front=front_rect((1, 1, 15, 10), (1, 15), (6, 15)), side=m)
    b.add(box(S, (2, 14), (0, 6), (6, 10)), top=m, front=front_rect((2, 10, 14, 15), (2, 14), (0, 6)), side=m)
    return b.done()


def sea_rocks():
    """The rocky shallows: a one-voxel water plate and the cell's four drawn rocks (centres at drawn columns 5 and 13,
    rows 4 and 12) rising as small rounded stones, all painted from the drawing."""
    c = Cell(SEA_ROCKS)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 1), (0, 16)), top=TOPDRAW, front=own(SEA_ROCKS), side=own(SEA_ROCKS))
    for cx in (5, 13):
        for cz in (4, 12):
            b.add(F.ellipsoid(S, (cx, 1, cz), (3, 3.5, 3)) & (np.indices(S)[0] >= 1), top=TOPDRAW, front=TOPDRAW, side=TOPDRAW)
    return b.done()


def slab(key, h, *, front=None, side=None, z=(0, 16)):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, h), z), top=TOPDRAW, front=front or own(key), side=side or own(key))
    return b.done()


def ledge(key):
    """A ledge: the ground's drawn top over a low step, its dirt face (drawn rows 8-15) on the south and its rock (the
    drawing's lower-left quarter, rock in every ledge key) on the ends."""
    return slab(key, 5, front=front_rect((0, 8, 16, 16), (0, 16), (0, 5)), side=F.side_rect((0, 8, 8, 16), (0, 16), (0, 5)))


def flower_bed():
    """Goldenrod's flower bed: a hedge box (its front drawn rows 8-15) with the red flowers on top (rows 0-7)."""
    c = Cell(FLOWER_BED)
    b = Build(c)
    b.add(box(S, (1, 15), (0, 8), (2, 14)), top=top_rect((0, 0, 16, 8), (1, 15), (2, 14)), front=front_rect((0, 8, 16, 16), (1, 15), (0, 8)),
          side=F.side_rect((0, 8, 16, 16), (2, 14), (0, 8)))
    return b.done()


def rails(key):
    """The grey rails in the cell's north half (drawn rows 0-7), three voxels high."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 3), (0, 8)), top=top_rect((0, 0, 16, 8), (0, 16), (0, 8)), front=own(key), side=own(key))
    return b.done()


def main():
    out = Pass("johto-outdoor")
    census = json.loads((STUDY / "census" / "census.json").read_text(encoding="utf-8"))["keys"]
    modelled = set()
    for top, base in TREES:
        for key, (occ, paint) in tree(top, base).items():
            out.cell(key, occ, paint, label="tree " + ("crown (stacked)" if key == top else "base and trunk"), family="jo-trees",
                     reason="One tree over its two stacked cells: a rounded crown on a short trunk, about 30 voxels, each cell "
                            "painted from its own drawing by height (README: crown, trunk, no shadow slab).")
            modelled.add(key)
        name = "standard" if top == TREES[0][0] else "bushy"
        out.entity(f"crystal_jo_tree_{name}", [[top], [base]], f"Johto tree, {name} (stack)", kind="stack (model as one)")
    out.cell(SMALL_TREE, *small_tree(), label="one-cell tree", family="jo-trees", reason="A compact crown on a stub trunk (654).")
    modelled.add(SMALL_TREE)
    for key, (occ, paint) in rock().items():
        out.cell(key, occ, paint, label="rock formation (2x2)", family="jo-rocks",
                 reason="One rounded mound designed over its 2x2 cells, painted from its composite drawing (122 each).")
        modelled.add(key)
    out.entity("crystal_jo_rock", ROCK, "Johto rock formation")
    for key, end in FENCE_EW.items():
        out.cell(key, *fence_ew(key, end), label="fence (east-west)", family="jo-fences", reason="Two capped posts and two rails.")
        modelled.add(key)
    for key, side in FENCE_NS.items():
        out.cell(key, *fence_ns(key, side), label="fence (north-south)", family="jo-fences",
                 reason="Two posts along the drawn side and two rails along the run.")
        modelled.add(key)
    out.entity("crystal_jo_fence", [[K("393-393-394-449"), K("393-393-394-394"), K("393-393-449-394")]], "Johto fence run")
    out.cell(SIGN, *sign(), label="sign board", family="jo-props", reason="The framed board with its text on its base (101).")
    out.cell(SEA_ROCKS, *sea_rocks(), label="sea-rock field", family="jo-props",
             reason="The rocky shallows (747): a water plate with the four drawn rocks rising from it.")
    for key in LEDGES:
        out.cell(key, *ledge(key), label="ledge", family="jo-ledges", reason="The ground's drawn top over a low step, the dirt face south.")
    out.cell(FLOWER_BED, *flower_bed(), label="flower bed", family="jo-props", reason="A hedge box with the red flowers on top.")
    for key in RAILS:
        out.cell(key, *rails(key), label="rails", family="jo-props", reason="The grey rails in the cell's north half.")
    modelled |= {SIGN, SEA_ROCKS, FLOWER_BED, *LEDGES, *RAILS}
    fam = json.loads((STUDY / "families.json").read_text(encoding="utf-8"))
    for F_ in FAMILIES:
        for key in fam[F_]:
            if key in modelled:
                continue
            info = census.get(key, {})
            reasons = info.get("reasons") or {}
            if info.get("drawn", 0):
                out.keep(key, label="building wall / cliff block (kept)", family="jo-retained",
                         reason=f"Drawn as a unit in {info['drawn']} of {info['placements']} placements, outside the building "
                                f"templates ({', '.join(sorted(reasons))}); its full-block model already reads as the wall or "
                                f"cliff block, so it is kept.")
            else:
                out.keep(key, label="never drawn", family="jo-retained",
                         reason=f"Never drawn by its entry (census: {', '.join(sorted(reasons)) or 'unplaced'}); kept.")
    out.write(families=FAMILIES)


if __name__ == "__main__":
    main()
