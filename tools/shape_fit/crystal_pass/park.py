"""Crystal checkpoint 25: TILESET_PARK_1 (the National Park and its Bug-Catching Contest copy; Safari Zone Beta's boulder).

Source reading (study/groups/TILESET_PARK_1; the National Park map):

- The big trees ringing the park (386 each, drawn over 2x2 cells): one rounded crown over the four cells on a broad
  trunk (its roots fill the south cells), 32 voxels (two layers), painted from the composite; the shadow is not modelled.
  Ilex Forest's big tree, in this tileset's art.
- The ornamental fence round the lawn. East-west runs: two wooden blocks (drawn rows 9-14) as a low wall with the hoop
  arch on top (rows 0-8). North-south runs: the wooden block column (tile 3859) is the wall; the narrow oval beside it is
  the same hoop seen edge-on (tiles 3863, 3866, 3867: its north end, middle and south end over three quarter-tiles), with
  a cap block (3860) between hoops: here the hoops stand on the wall in line with the run. Each cell is built from its own
  quarter tiles, so every phase of the run fits. The run ends (3861): a free-standing hoop.
- The benches (two cells: a slatted backrest over a slatted seat, on legs), the round bins (an open top), the boulder.
- The fountain's stone rim, built per quarter tile: a stone (3843) a block six high with its drawing, water (442) a
  one-voxel plate (the unit's floor wears the ground, not water), ground (360) left to the floor. The old models raised
  some ground and water quarters to the stones' height.
- Retained: the keys never drawn (the gate buildings' templates, the flower beds, walkable grass and paving).
"""
from __future__ import annotations

import json
from collections import Counter

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass import interiors as I
from tools.shape_fit.crystal_pass.common import K, STUDY, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, side_rect, top_rect

S = (16, 16, 16)
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
FAMILIES = ["TILESET_PARK_1"]
TAN, BROWN, DARK = (197, 148, 58), (165, 123, 25), (58, 58, 58)

BIG_TREE = [[K("3824-3825-3828-3829"), K("3826-3827-3830-3831")], [K("3832-3833-3836-3837"), K("3834-3835-3838-3839")]]
FENCE_EW = K("3872-3873-3874-3875")
FENCE_END = K("3861-3862-3864-3865")
FENCE_NS = [K(k) for k in ("3859-3860-3859-3863", "3859-3863-3859-3866", "3859-3866-3859-3867", "3859-3867-3859-3860",
                           "3860-3859-3863-3859", "3863-3859-3866-3859", "3866-3859-3867-3859", "3867-3859-3860-3859")]
BENCH = [[K("3844-3845-3848-3849"), K("3846-3847-3850-3851")]]
BIN = K("3855-3856-3857-3858")
BOULDER = K("3894-3895-3897-3898")
FOUNTAIN_RIM = [K(k) for k in ("360-3843-3843-3843", "3843-360-3843-3843", "3843-3843-360-3843", "3843-3843-3843-360",
                               "3843-3843-3843-442", "3843-3843-442-3843", "3843-3843-442-442", "3843-442-3843-3843",
                               "3843-442-3843-442", "3843-442-442-442", "442-3843-3843-3843", "442-3843-442-3843",
                               "442-3843-442-442", "442-442-3843-3843", "442-442-3843-442", "442-442-442-3843")]


def own(key, n=4):
    c = Cell(key)
    return [col for col, _ in Counter(tuple(int(a) for a in px) for px in c.pixels.reshape(-1, 3)).most_common(n)]


def union(keys):
    out = []
    for k in keys:
        for col in own(k, 8):
            if col not in out:
                out.append(col)
    return out


def big_tree():
    """The 2x2 park tree (global y 0-31, z and x 0-31): a rounded crown (y 9-31) on a broad trunk (x 11-20, z 16-23,
    y 0-12). Up faces show the crown's drawn rows 1-17 from back to front, side faces by height over the same rows (the
    crown kept to the drawn columns 3-28); the trunk the drawn trunk and roots (rows 17-27, columns 9-22); the underside
    the shadow."""
    yy, zz, xx = np.indices((32, 32, 32))
    crown = ((xx + .5 - 16) / 15.6) ** 2 + ((yy + .5 - 21) / 11) ** 2 + ((zz + .5 - 15) / 14.6) ** 2 <= 1
    trunk = (((xx + .5 - 15.5) / 4.6) ** 2 + ((zz + .5 - 19.5) / 3.6) ** 2 <= 1) & (yy < 13)
    occ = crown | trunk

    def colour_at(y, gz, gx, face):
        if trunk[y, gz, gx] and not crown[y, gz, gx]:
            return 9 + (gx + .5 - 10.9) / 9.2 * 13, 17 + (12.5 - y) / 12.5 * 10
        cu = 3 + (gx + .5) / 32 * 26
        if face == "up":
            return cu, 1 + (gz + .5 - .4) / 29.2 * 16
        if face == "down":
            return 15.5, 30.5
        u = cu if face in ("south", "north") else 3 + (gz + .5) / 32 * 26
        return u, 1 + (31.5 - y) / 22.5 * 16
    return I.paint_global(occ, BIG_TREE, colour_at, union([k for row in BIG_TREE for k in row]))


def ring(shape, plane, centre, outer, inner, y0):
    """A hoop: the band between two half-ellipses standing from y0, in the x-y plane (`plane`="xy", centre/outer/inner
    along x) or the y-z plane (along z)."""
    yy, zz, xx = np.indices(shape)
    a = (xx if plane == "xy" else zz) + .5
    h = yy + .5 - y0
    out = ((a - centre) / outer[0]) ** 2 + (h / outer[1]) ** 2 <= 1
    inn = ((a - centre) / inner[0]) ** 2 + (h / inner[1]) ** 2 <= 1
    return out & ~inn & (h >= 0)


def fence_ew():
    """An east-west run: the two wooden blocks (drawn rows 9-14) as a wall seven high, the hoop (rows 0-8) on top."""
    c = Cell(FENCE_EW)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 7), (6, 10)), top=[TAN], front=front_rect((0, 9, 16, 15), (0, 16), (0, 7)), side=[TAN, BROWN])
    hoop = ring(S, "xy", 8, (6.2, 9), (3.3, 6.2), 7) & box(S, (0, 16), (7, 16), (7, 9))
    b.add(hoop, top=[BROWN, TAN], front=front_rect((0, 0, 16, 9), (0, 16), (7, 16)), side=[TAN])
    return b.done()


def fence_end():
    """A run's end: a free-standing hoop from the ground (its legs drawn rows 6-14, its crown bar rows 1-6)."""
    c = Cell(FENCE_END)
    b = Build(c)
    hoop = ring(S, "xy", 8, (7.4, 15), (4.4, 11.5), 0) & box(S, (0, 16), (0, 16), (6, 10))
    b.add(hoop, top=[TAN, BROWN], front=front_rect((0, 1, 16, 15), (0, 16), (0, 15)), side=[TAN, BROWN])
    return b.done()


# the edge-on hoop over three quarter-tiles: (start of this quarter along the hoop, in voxels)
HOOP_PART = {3863: 0, 3866: 8, 3867: 16}


def fence_ns(key):
    """A north-south run: the wooden block column (tile 3859) is a wall seven high; on it, in line with the run, the
    hoop seen edge-on in the drawing (tiles 3863/3866/3867: a 24-long arch across three quarters) or a cap block (3860)."""
    tl, tr, bl, br = (int(t) for t in key.split("/")[1].split("-"))
    west = tl == 3859
    c = Cell(key)
    b = Build(c)
    wx = (1, 7) if west else (9, 15)
    u0 = wx[0]
    b.add(box(S, wx, (0, 7), (0, 16)), top=top_rect((wx[0], 0, wx[1], 16), wx, (0, 16)),
          front=front_rect((wx[0], 8, wx[1], 16), wx, (0, 7)),
          side=lambda y, z, x: (u0 + 1 + (y + .5) / 7 * 4, z + .5))
    hx = (wx[0] + 2, wx[1] - 2)
    for tile, z0 in ((tr if west else tl, 0), (br if west else bl, 8)):
        if tile == 3860:
            b.add(box(S, (wx[0], wx[1]), (7, 10), (z0 + 1, z0 + 7)), top=[TAN], front=[DARK, BROWN], side=[BROWN, TAN])
            continue
        start = HOOP_PART[tile]
        arch = ring((16, 16 + 24, 16), "yz", 12, (12, 8.5), (9.5, 6), 7)[:, start:start + 8, :]
        part = np.zeros(S, bool)
        part[:, z0:z0 + 8, :] = arch & box((16, 8, 16), hx, (0, 16), (0, 8))
        b.add(part, top=[BROWN, TAN], front=[DARK], side=[TAN, BROWN])
    return b.done()


def bench():
    """The bench over its two cells (global x 0-31): a slatted seat (drawn rows 8-15 on top) and backrest (rows 0-6 on
    its face), on grey legs and back supports (the row 7 grey)."""
    yy, zz, xx = np.indices((16, 16, 32))
    seat = (yy >= 5) & (yy < 7) & (zz >= 7) & (zz < 15) & (xx >= 1) & (xx < 31)
    back = (yy >= 7) & (yy < 15) & (zz >= 4) & (zz < 6) & (xx >= 1) & (xx < 31)
    legs = np.zeros_like(seat)
    for x0 in (2, 28):
        legs |= (xx >= x0) & (xx < x0 + 2) & (yy < 5) & (((zz >= 8) & (zz < 10)) | ((zz >= 12) & (zz < 14)))
        legs |= (xx >= x0) & (xx < x0 + 2) & (yy < 7) & (zz >= 4) & (zz < 7)
    occ = seat | back | legs

    def colour_at(y, gz, gx, face):
        if legs[y, gz, gx]:
            return (3.5 if gx < 16 else 28.5), 7.5
        if back[y, gz, gx]:
            return gx + .5, min(6.5, (15 - y - .5) / 8 * 7)
        if face == "up":
            return gx + .5, 8 + (gz + .5 - 7) / 8 * 8
        return gx + .5, 15.5
    return I.paint_global(occ, BENCH, colour_at, union(BENCH[0]))


def bin_():
    """A round bin: its rim and dark opening (drawn rows 1-9) over its grey body (rows 8-15)."""
    c = Cell(BIN)
    b = Build(c)
    body = F.cylinder(S, (7.5, 8), (6, 5.5), (0, 10))
    b.add(body, top=top_rect((1, 1, 14, 9), (1.5, 13.5), (2.5, 13.5)), front=front_rect((1, 8, 14, 16), (1.5, 13.5), (0, 10)),
          side=side_rect((1, 8, 14, 16), (2.5, 13.5), (0, 10)))
    b.cut(F.cylinder(S, (7.5, 8), (3.2, 2.8), (7, 10)))  # the opening: its floor shows the drawn dark centre
    return b.done()


def rim(key):
    """The fountain's rim, quarter by quarter (its tiles TL, TR, BL, BR): stones six high, water a plate, ground the floor."""
    c = Cell(key)
    b = Build(c)
    tiles = [int(t) for t in key.split("/")[1].split("-")]
    for tile, (qx, qz) in zip(tiles, ((0, 0), (8, 0), (0, 8), (8, 8))):
        x, z = (qx, qx + 8), (qz, qz + 8)
        if tile == 3843:
            b.add(box(S, x, (0, 6), z), top=top_rect((qx, qz, qx + 8, qz + 8), x, z),
                  front=front_rect((qx, qz + 2, qx + 8, qz + 8), x, (0, 6)), side=side_rect((qx, qz + 2, qx + 8, qz + 8), z, (0, 6)))
        elif tile == 442:
            b.add(box(S, x, (0, 1), z), top=top_rect((qx, qz, qx + 8, qz + 8), x, z), front=[(107, 99, 255)], side=[(107, 99, 255)])
    return b.done()


def boulder():
    c = Cell(BOULDER)
    b = Build(c)
    occ = F.ellipsoid(S, (7.2, 4, 8), (6.8, 8.5, 6.8)) & (np.indices(S)[0] < 12)
    b.add(occ, top=TOPDRAW, front=FULL, side=own(BOULDER))
    return b.done()


def main():
    out = Pass("park")
    census = json.loads((STUDY / "census" / "census.json").read_text(encoding="utf-8"))["keys"]
    for key, (occ, paint) in big_tree().items():
        out.cell(key, occ, paint, label="park big tree (2x2)", family="pk-trees",
                 reason="One rounded crown over its 2x2 cells on a broad trunk, 32 voxels (two layers), painted from its "
                        "composite drawing (386 each; Ilex Forest's big tree in this art).")
    out.entity("crystal_pk_big_tree", BIG_TREE, "National Park big tree")
    out.cell(FENCE_EW, *fence_ew(), label="park fence (east-west)", family="pk-fence",
             reason="Two wooden blocks as a low wall with the hoop arch on top (84).")
    out.cell(FENCE_END, *fence_end(), label="park fence end (hoop)", family="pk-fence", reason="A free-standing hoop at a run's end (76).")
    for key in FENCE_NS:
        out.cell(key, *fence_ns(key), label="park fence (north-south)", family="pk-fence",
                 reason="The wooden block column as the wall; the hoop drawn edge-on beside it stands on the wall in line "
                        "with the run (or the cap block between hoops), built from this cell's own quarter tiles.")
    out.entity("crystal_pk_fence_ns", [[FENCE_NS[1]], [FENCE_NS[3]], [FENCE_NS[0]], [FENCE_NS[2]]], "Park fence, a north-south run")
    out.entity("crystal_pk_fence_ew", [[FENCE_END, FENCE_EW, FENCE_EW, FENCE_EW]], "Park fence, an east-west run and its end")
    for key, (occ, paint) in bench().items():
        out.cell(key, occ, paint, label="park bench", family="pk-props",
                 reason="The bench over its two cells: slatted seat and backrest on legs (10 each).")
    out.entity("crystal_pk_bench", BENCH, "National Park bench")
    out.cell(BIN, *bin_(), label="bin", family="pk-props", reason="A round bin with its dark opening.")
    out.cell(BOULDER, *boulder(), label="boulder", family="pk-props", reason="A rounded grey rock, its drawing on top.")
    for key in FOUNTAIN_RIM:
        out.cell(key, *rim(key), label="fountain rim", family="pk-fountain",
                 reason="Per quarter: stones a block six high, water a one-voxel plate, ground left to the floor (the old "
                        "model raised some ground and water quarters to the stones' height).")
    modelled = {FENCE_EW, FENCE_END, BIN, BOULDER, *FENCE_NS, *FOUNTAIN_RIM, *(k for r in BIG_TREE for k in r), *BENCH[0]}
    fam = json.loads((STUDY / "families.json").read_text(encoding="utf-8"))
    for key in fam["TILESET_PARK_1"]:
        if key in modelled:
            continue
        info = census.get(key, {})
        if info.get("drawn", 0):
            raise SystemExit(f"{key} is drawn ({info['drawn']}) but has no decision")
        out.keep(key, label="never drawn", family="pk-retained",
                 reason=f"Never drawn by its entry (census: {', '.join(sorted(info.get('reasons') or {})) or 'unplaced'}); kept.")
    out.write(families=FAMILIES)


if __name__ == "__main__":
    main()
