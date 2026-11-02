"""Crystal checkpoint 33: the landmarks (Goldenrod's Department Store and Radio Tower, Olivine's Lighthouse and terrace
building), and the earlier parts specs kept (the Battle Tower, the National Park's gate and flower planter, the S.S. Aqua).

Source reading (building_kit.read; the pixel maps of b022, b023, b029):

- b032, the Department Store: a Johto building with a tall facade (rows 32-127: window rows, the door and the Mart's
  standing sign): building_kit.johto.
- b022 (Olivine): a Johto house (its blue roof rows 16-47, the walls rows 48-79 with two windows, no door this side) with a
  roof terrace drawn behind it (rows 0-15: a pink tiled deck between grey railings). The house from the drawing's lower part
  (building_kit.johto on rows 16-79, shifted back into place), the terrace a deck on a back extension at the wall head, its
  railings low blocks.
- b023, the Lighthouse (64x224): a speckled roof cap (rows 0-23), a gallery band (rows 24-35), the brick tower (rows 36-223:
  window pairs, the door at the base). A square tower on the footprint's south (as Codex's b088 stands its storeys), 1:1
  (188 voxels), the windows proud, a gallery ring near the top, a hip cap in the drawn roof, the door recessed.
- b029, the Radio Tower (64x192): a lower block (rows 112-191, all four cells wide), the tall tower over its west half
  (columns 0-31, rows 0-111 drawn upward), a shorter tower over its east half (columns 32-63, rows 96-111), a small
  yellow-roofed hut at the footprint's north (columns 32-63, rows 0-31) and paved ground between: the lower block on the
  south, the two towers rising from it at their drawn heights, the hut at the north.
- Kept: b096 (the Battle Tower: a facade block with its glass column and entrance canopy; the drawing's doors are not the
  standard colours the reader finds), b099 (the National Park's gate: brick walls under a pink gable, no door this side),
  b100 (the park's flower planter), b101 (the S.S. Aqua's hull and superstructure): their earlier specs read well.
"""
from __future__ import annotations

import copy

import numpy as np

from tools.shape_fit.crystal_pass import building_kit as BK
from tools.shape_fit.crystal_pass.building_parts import box, drawing, paint
from tools.shape_fit.crystal_pass.buildings_johto_a import readings_sheet
from tools.shape_fit.crystal_pass.common import Pass


def shifted(spec, dz):
    """A spec built on a drawing's lower part (rows dz..), moved back into the whole drawing's coordinates."""
    out = copy.deepcopy(spec)
    for p in out["parts"]:
        if "z" in p:
            p["z"] = [p["z"][0] + dz, p["z"][1] + dz]
        for name, rect in p.get("paint", {}).items():
            if name != "wrap":
                p["paint"][name] = [rect[0], rect[1] + dz, rect[2], rect[3] + dz]
    return out


def terrace_house(a):
    """b022: the Johto house from rows 16-79, a terrace deck (rows 0-15) on a back extension at the wall head."""
    sub = a[16:]
    f = BK.read(sub)
    spec = shifted(BK.johto(f), 16)
    W = a.shape[1]
    walls = next(p for p in spec["parts"] if p["shape"] == "box" and p["z"][0] == 19)
    yt = walls["y"][1]
    side = walls["paint"]["side"]
    spec["parts"] += [
        box((3, W - 3), (2, 19), (2, yt), paint(side, side=side, back=side)),
        box((1, W - 1), (1, 18), (yt, yt + 2), paint([16, 14, 48, 16], top=[16, 0, 48, 16], side=[16, 14, 20, 16], back=[16, 14, 48, 16])),
        box((1, 15), (2, 16), (yt + 2, yt + 7), paint([0, 8, 16, 16], top=[0, 0, 16, 8], side=[0, 8, 6, 16])),
        box((W - 15, W - 1), (2, 16), (yt + 2, yt + 7), paint([48, 8, 64, 16], top=[48, 0, 64, 8], side=[48, 8, 54, 16])),
    ]
    spec["height"] = max(spec["height"], yt + 8)
    return spec


def lighthouse(a):
    """b023: a square tower on the south (x 4..60, z 162..221), 1:1 from the facade (rows 36-223), the windows proud, a
    gallery ring near the top (rows 24-35), a hip cap in the drawn roof (rows 0-23), the door recessed."""
    D, W = a.shape[:2]
    f = BK.read(a)
    top, F = f.top, D - f.top
    y0, zf, zb = 2, D - 3, D - 62
    wh = F
    yt = y0 + wh

    def y_of(row):
        return y0 + wh * (D - 1 - row) / (F - 1)
    side = [8, 120, 16, 132]
    parts = [box((2, W - 2), (zb - 2, D), (0, y0), paint([2, D - 1, W - 2, D], top=[2, D - 1, W - 2, D], side=[2, D - 1, 4, D])),
             box((4, W - 4), (zb, zf), (y0, yt), paint([4, top, W - 4, D - 1], side=side, back=side))]
    for px in ((1, 4), (W - 4, W - 1)):
        for pz in ((zf - 4, zf), (zb, zb + 4)):
            parts.append(box(px, pz, (y0, yt), paint([1, top, 4, D - 1], side=[1, top, 4, D - 1])))
    parts.append(box((1, W - 1), (zb - 3, zf + 3), (yt - 14, yt - 11), paint([0, 29, W, 35], top=[0, 25, W, 28], side=[0, 29, 8, 35])))
    parts.append(box((2, W - 2), (zb - 2, zf + 2), (yt, yt + 3), paint([0, 24, W, 29], top=[8, 2, W - 8, 23], side=[0, 24, 8, 29])))
    parts.append({"shape": "hip", "x": [2, W - 2], "z": [zb - 2, zf + 2], "y": [yt + 3, yt + 22], "paint": paint([0, 20, W, 24], top=[8, 2, W - 8, 23], side=[0, 20, 8, 24])})
    if f.door:
        x0, z0, x1, z1 = f.door
        dh = round(y_of(z0))
        parts.append(box((x0 + 1, x1 - 1), (zf - 4, zf + 1), (y0, dh), carve=True))
        parts.append(box((x0 + 1, x1 - 1), (zf - 4, zf - 3), (y0, dh), paint([x0 + 1, z0 + 1, x1 - 1, z1], side=[x0 + 1, z0 + 1, x0 + 2, z1])))
    for x0, z0, x1, z1 in f.windows:
        parts.append(box((x0, x1), (zf, zf + 1), (round(y_of(z1)), round(y_of(z0))), paint([x0, z0, x1, z1], side=[x0, z0, x0 + 1, z1])))
    return {"height": yt + 23, "parts": parts}


def radio_tower(a):
    """b029: the lower block (rows 112-191) on the south, the tall tower (columns 0-31, rows 0-111) and the shorter tower
    (columns 32-63, rows 96-111) rising from it at their drawn heights, the hut (columns 32-63, rows 0-31) at the north."""
    D, W = a.shape[:2]
    y0 = 2
    base_h = 80                       # rows 112-191, 1:1
    yb = y0 + base_h
    grey = [8, 120, 16, 128]
    parts = [box((0, W), (126, D), (0, y0), paint([0, D - 2, W, D], top=[16, D - 2, 32, D - 1], side=[0, D - 2, 4, D])),
             box((1, W - 1), (128, D - 1), (y0, yb), paint([0, 112, W, D - 1], top=[8, 168, 56, 176], side=grey, back=grey)),
             box((2, 30), (140, 180), (yb, yb + 98), paint([0, 14, 32, 112], top=[8, 2, 24, 10], side=[2, 32, 30, 48], back=[2, 32, 30, 48])),
             box((6, 26), (150, 170), (yb + 98, yb + 110), paint([0, 0, 32, 14], top=[12, 2, 20, 6], side=[0, 0, 32, 14])),
             box((34, 62), (140, 180), (yb, yb + 16), paint([32, 96, 64, 112], top=[40, 98, 56, 104], side=[34, 112, 62, 128])),
             box((34, 62), (2, 30), (y0, y0 + 16), paint([32, 14, 64, 32], top=[34, 2, 62, 12], side=[34, 16, 40, 30], back=[34, 16, 40, 30])),
             box((32, 64), (0, 32), (y0 + 16, y0 + 20), paint([32, 10, 64, 14], top=[33, 2, 63, 12], side=[32, 10, 36, 14]))]
    f = BK.read(a, top=168)
    if f.door:
        x0, z0, x1, z1 = f.door
        parts.append(box((x0 + 1, x1 - 1), (D - 6, D - 1), (y0, y0 + 18), carve=True))
        parts.append(box((x0 + 1, x1 - 1), (D - 6, D - 5), (y0, y0 + 18), paint([x0 + 1, z0 + 1, x1 - 1, z1], side=[x0 + 1, z0 + 1, x0 + 2, z1])))
    return {"height": yb + 111, "parts": parts}


BUILT = {
    "crystal_b032_G2_GOLDENROD_CITY": ("Goldenrod Department Store", lambda a: BK.johto(BK.read(a)),
                                       "The Johto archetype on a tall facade (1:1): the window rows, the door recessed, the Mart's sign on its legs."),
    "crystal_b022_G2_OLIVINE_CITY": ("Olivine house with a roof terrace", terrace_house,
                                     "The Johto house from the drawing's lower part, the drawn roof terrace a tiled deck on a back extension with its railings."),
    "crystal_b023_G2_OLIVINE_CITY": ("Olivine Lighthouse", lighthouse,
                                     "A square brick tower on the footprint's south, 1:1 (188 voxels), windows proud, a gallery ring, a hip cap, the door recessed."),
    "crystal_b029_G2_GOLDENROD_CITY": ("Goldenrod Radio Tower", radio_tower,
                                       "The lower block on the south, the tall and the short tower rising from it at their drawn heights, the hut at the north."),
}
RETAINED = {
    "crystal_b096_G2_BATTLE_TOWER_OUTSIDE": "Battle Tower",
    "crystal_b099_G2_NATIONAL_PARK": "National Park gate",
    "crystal_b100_G2_NATIONAL_PARK": "National Park flower planter",
    "crystal_b101_G2_OLIVINE_PORT": "S.S. Aqua",
}


def main():
    out = Pass("buildings-landmarks")
    table = {b["id"]: b for b in out.table["buildings"]}
    items = []
    for bid, (label, build, why) in BUILT.items():
        img = drawing(table[bid])
        items.append((bid, img.resize((img.width * 2, img.height * 2))))
        out.building(bid, build(np.asarray(img)), label=label, family="bl-landmarks", reason=why)
    for bid, label in RETAINED.items():
        out.keep_building(bid, label=label, family="bl-retained",
                          reason="Its earlier parts spec reads as its drawing (checked in the local preview); kept.")
    out.write(owned=[*BUILT, *RETAINED])
    readings_sheet(out.out, items)


if __name__ == "__main__":
    main()
