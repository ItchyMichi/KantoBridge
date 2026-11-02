"""Crystal checkpoint 23: the Kanto outdoor tilesets (TILESET_KANTO_1 to TILESET_KANTO_13).

Source reading (study/groups/TILESET_KANTO_*; the census per key and the route maps):

- The Kanto tree (3,077 placements): one cell, a rounded crown on a short trunk (README's outdoor rule; Kanto's trees
  are one square in the source, Johto's two).
- The round bollards lining Kanto's paths and bridges (1,988): a post with its light top and grey band.
- The town signs (75: a wide board with its text on two legs) and the wooden post pairs of Route 13 and Fuchsia (461).
- The ledges (Route 1's and their kind: the path or grass drawn on top over a short rock face): a low step, as Johto's.
- The cliff faces and mountain rock drawn as units where no template claims them (Routes 22-24, Silver Cave's outside):
  full blocks showing their rock.
- The gatehouses cut by a map seam (Routes 15, 17, 18, Fuchsia's edge, Routes 6, 16): their template cannot claim the
  cells on this side, so each slice is drawn as a unit; they become full blocks (the gate's wall with its drawing on its
  face, its roof seen from above), so the slice reads as the building's side instead of uneven pieces.
- Retained: keys never drawn (templates, Tier A folds, water, walk) and the sea drawn as a unit at map edges (its plate).
"""
from __future__ import annotations

import json
from collections import Counter

import numpy as np

from tools.shape_fit.crystal_pass import furniture as F
from tools.shape_fit.crystal_pass.common import K, STUDY, Pass
from tools.shape_fit.crystal_pass.furniture import Build, Cell, box, front_rect, side_rect, top_rect

S = (16, 16, 16)
TOPDRAW = top_rect((0, 0, 16, 16), (0, 16), (0, 16))
FULL = front_rect((0, 0, 16, 16), (0, 16), (0, 16))
SIDEDRAW = side_rect((0, 0, 16, 16), (0, 16), (0, 16))
DARK = (58, 58, 58)
FAMILIES = [f"TILESET_KANTO_{n}" for n in (1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13)]

TREE = K("1243-1244-1245-1246")
BOLLARD = K("1252-1253-1254-1255")
SIGN = K("1234-1235-1236-1237")
POSTS = K("1256-1256-1257-1257")
LEDGES = [K(k) for k in ("1233-1233-1232-1232", "361-361-1232-1232", "361-361-1272-1232", "1233-1233-1232-1274", "1271-1233-1272-1232")]
CLIFF_EDGE_W = K("1271-1233-1271-1233")
CLIFF = [K(k) for k in ("1273-1273-1273-1273", "1232-1232-1232-1232", "1271-1271-1271-1271", "1279-387-1273-1279", "387-1280-1280-1271")]
GATE_ROOF = [K(k) for k in ("1260-1261-1262-1263", "1261-1261-1263-1263", "1261-1266-1263-1262", "1262-1263-1264-1265",
                            "1263-1262-1265-1267", "1263-1263-1265-1265", "1212-1212-1215-1215")]
GATE_WALL = [K(k) for k in ("1228-1228-570-570", "1238-1228-570-570", "1238-1251-570-1251", "1247-1238-1247-570",
                            "1247-570-1220-1221", "570-1251-1221-1231", "570-570-1221-1221", "1238-570-570-570")]
SEA = K("442-442-442-442")


def own(key, n=4):
    c = Cell(key)
    return [col for col, _ in Counter(tuple(int(a) for a in px) for px in c.pixels.reshape(-1, 3)).most_common(n)]


def tree():
    """The Kanto tree: a rounded crown (drawn rows 0-13) over a short trunk (the dark rows 13-15, columns 6-9)."""
    c = Cell(TREE)
    b = Build(c)
    crown = F.ellipsoid(S, (8, 9.6, 8), (7.7, 6.6, 7.5)) & (np.indices(S)[0] >= 3)
    b.add(crown, top=top_rect((0, 0, 16, 9), (0.3, 15.7), (0.5, 15.5)), front=front_rect((0, 0, 16, 14), (0.3, 15.7), (3, 16)),
          side=side_rect((0, 0, 16, 14), (0.5, 15.5), (3, 16)))
    b.add(box(S, (7, 9), (0, 4), (7, 9)), top=own(TREE), front=front_rect((6, 13, 10, 16), (7, 9), (0, 4)),
          side=front_rect((6, 13, 10, 16), (7, 9), (0, 4)))
    return b.done()


def bollard():
    """A round bollard: its light top with the grey rim (drawn rows 0-6) on a grey post (rows 6-15), drawn columns 1-13."""
    c = Cell(BOLLARD)
    b = Build(c)
    b.add(F.cylinder(S, (7.5, 8), (6.5, 6), (0, 12)), top=top_rect((1, 0, 14, 7), (1, 14), (2, 14)),
          front=front_rect((1, 6, 14, 16), (1, 14), (0, 12)), side=side_rect((1, 6, 14, 16), (2, 14), (0, 12)))
    return b.done()


def sign():
    """A town sign: the wide framed board with its text (drawn rows 1-11) on two legs (columns 2-5 and 10-13)."""
    c = Cell(SIGN)
    b = Build(c)
    m = own(SIGN)
    b.add(box(S, (0, 16), (5, 15), (7, 9)), top=m, front=front_rect((0, 1, 16, 12), (0, 16), (5, 15)), side=m)
    for x in ((2, 6), (10, 14)):
        b.add(box(S, x, (0, 5), (7, 9)), top=m, front=front_rect((x[0], 12, x[1], 15), x, (0, 5)), side=m)
    return b.done()


def posts():
    """A pair of wooden posts (drawn columns 1-6 and 9-14): square posts with rounded caps, their wood drawn on the faces."""
    c = Cell(POSTS)
    b = Build(c)
    for x0 in (1, 9):
        x = (x0 + 1, x0 + 5)
        face = front_rect((x0, 5, x0 + 6, 15), x, (0, 11))
        b.add(box(S, x, (0, 11), (6, 10)), top=top_rect((x0, 2, x0 + 6, 6), x, (6, 10)), front=face,
              side=side_rect((x0, 5, x0 + 6, 15), (6, 10), (0, 11)))
        b.add(box(S, (x[0] + 1, x[1] - 1), (11, 13), (7, 9)), top=top_rect((x0, 2, x0 + 6, 5), (x[0] + 1, x[1] - 1), (7, 9)),
              front=front_rect((x0 + 1, 2, x0 + 5, 5), (x[0] + 1, x[1] - 1), (11, 13)), side=own(POSTS))
    return b.done()


def ledge(key):
    """A ledge: the path or grass drawn on top of a low step, its short rock face (drawn rows 8-15) on the south and its
    rock (the drawing's lower-left quarter, rock in every ledge key) on the ends."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 5), (0, 16)), top=TOPDRAW, front=front_rect((0, 8, 16, 16), (0, 16), (0, 5)),
          side=side_rect((0, 8, 8, 16), (0, 16), (0, 5)))
    return b.done()


def cliff_edge_w():
    """A cliff's edge running north-south: the rock (drawn columns 0-7) a full block on the west, the grass east on the
    ground (a one-voxel plate)."""
    c = Cell(CLIFF_EDGE_W)
    b = Build(c)
    b.add(box(S, (0, 8), (0, 16), (0, 16)), top=top_rect((0, 0, 8, 16), (0, 8), (0, 16)), front=front_rect((0, 0, 8, 16), (0, 8), (0, 16)),
          side=side_rect((0, 0, 8, 16), (0, 16), (0, 16)))
    b.add(box(S, (8, 16), (0, 1), (0, 16)), top=top_rect((8, 0, 16, 16), (8, 16), (0, 16)), front=own(CLIFF_EDGE_W), side=own(CLIFF_EDGE_W))
    return b.done()


def block(key, *, top):
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (0, 16)), top=top, front=FULL, side=SIDEDRAW)
    return b.done()


def wall_block(key):
    """A gatehouse wall slice: a full block, the facade drawn on its face and sides, the dark roof edge on top."""
    c = Cell(key)
    b = Build(c)
    b.add(box(S, (0, 16), (0, 16), (0, 16)), top=[DARK] + own(key), front=FULL, side=SIDEDRAW)
    return b.done()


def main():
    out = Pass("kanto-outdoor")
    census = json.loads((STUDY / "census" / "census.json").read_text(encoding="utf-8"))["keys"]
    out.cell(TREE, *tree(), label="Kanto tree", family="ka-trees",
             reason="One cell: a rounded crown on a short trunk (3,077 placements; README's outdoor rule).")
    out.cell(BOLLARD, *bollard(), label="bollard", family="ka-props", reason="A round post with its light top and grey band (1,988).")
    out.cell(SIGN, *sign(), label="town sign", family="ka-props", reason="The wide board with its text on two legs (75).")
    out.cell(POSTS, *posts(), label="wooden posts (pair)", family="ka-props", reason="Two square wooden posts with rounded caps (461).")
    for key in LEDGES:
        out.cell(key, *ledge(key), label="ledge", family="ka-ledges", reason="The path or grass on top of a low step, the rock face south.")
    out.cell(CLIFF_EDGE_W, *cliff_edge_w(), label="cliff edge (west)", family="ka-cliffs",
             reason="The rock drawn on the cell's west half a full block, the grass beside it on the ground.")
    for key in CLIFF:
        out.cell(key, *block(key, top=TOPDRAW), label="cliff rock", family="ka-cliffs",
                 reason="Cliff face or mountain rock drawn as a unit where no template claims it: a full block showing its rock.")
    for key in GATE_ROOF:
        out.cell(key, *block(key, top=TOPDRAW), label="gatehouse slice (roof)", family="ka-gate-slices",
                 reason=f"A gatehouse cut by a map seam, drawn as a unit in {census[key]['drawn']} places: a full block, its roof "
                        f"seen from above, so the slice reads as the building's side.")
    for key in GATE_WALL:
        out.cell(key, *wall_block(key), label="gatehouse slice (wall)", family="ka-gate-slices",
                 reason=f"A gatehouse cut by a map seam, drawn as a unit in {census[key]['drawn']} places: a full block with "
                        f"its facade on the face (the old model stood 11-14 high, some with stray rods on top).")
    out.keep(SEA, label="sea (kept)", family="ka-retained",
             reason=f"The sea drawn as a unit at map edges in {census[SEA]['drawn']} of {census[SEA]['placements']} placements: "
                    f"its water plate is right; kept.")
    modelled = {TREE, BOLLARD, SIGN, POSTS, CLIFF_EDGE_W, SEA, *LEDGES, *CLIFF, *GATE_ROOF, *GATE_WALL}
    fam = json.loads((STUDY / "families.json").read_text(encoding="utf-8"))
    for F_ in FAMILIES:
        for key in fam[F_]:
            if key in modelled:
                continue
            info = census.get(key, {})
            if info.get("drawn", 0):
                raise SystemExit(f"{key} is drawn ({info['drawn']}) but has no decision")
            out.keep(key, label="never drawn", family="ka-retained",
                     reason=f"Never drawn by its entry (census: {', '.join(sorted(info.get('reasons') or {})) or 'unplaced'}); kept.")
    out.write(families=FAMILIES)


if __name__ == "__main__":
    main()
