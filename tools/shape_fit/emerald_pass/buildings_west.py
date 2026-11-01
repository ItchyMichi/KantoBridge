"""Source-reviewed western Emerald architecture; candidate patches only.

The register is deliberately explicit: related facade drawings share construction,
while landmarks keep their own geometry. No live resource table is written.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path

from tools.shape_fit.emerald import building_trials as p
from tools.shape_fit.emerald_pass.buildings import BASELINE, BUNDLE, ROOT, digest

OUT = ROOT / "local/model-gallery/emerald-pass-study/buildings-west"
NUMBERS = (5, 7, 8, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22,
           23, 24, 25, 26, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 39,
           40, 41, 42, 43, 46, 47, 50, 51, 52, 53, 54, 55, 56, 57, 58)
GYMS = (5, 8, 19, 39, 50)
TERRACES = tuple(range(11, 19))
TERRACE_DOORS = (12, 15, 16, 18)
# width, entrance, windows, plain wall source rectangle. Each is inspected art.
HOUSES = {
    21: (80, (16, 32), ((48, 64),), (42, 43, 46, 60)),
    22: (64, (16, 32), ((32, 48),), (3, 44, 7, 60)),
    23: (80, (16, 32), ((48, 64),), (35, 42, 42, 60)),
    28: (64, (16, 32), ((40, 56),), (3, 42, 7, 60)),
    29: (64, (16, 32), ((40, 56),), (3, 42, 7, 60)),
    30: (64, (16, 32), ((40, 56),), (3, 42, 7, 60)),
    31: (80, (32, 48), ((8, 24), (56, 72)), (27, 42, 30, 60)),
    32: (64, (16, 32), ((40, 56),), (3, 42, 7, 60)),
    36: (80, (32, 48), ((48, 64),), (16, 42, 24, 60)),
    37: (64, (16, 32), ((32, 48),), (5, 42, 9, 60)),
    46: (64, (16, 32), ((32, 48),), (48, 42, 52, 60)),
    58: (64, (16, 32), ((32, 48),), (4, 42, 8, 60)),
}
RETAIN = {
    7: ("Pacifidlog thatched pavilion", "round-thatch-pavilion", "The offshore platform context confirms a circular timber hut. Its existing round wall, tapered woven dome, finial and separated timber uprights follow that silhouette; no cube replacement is warranted."),
    20: ("Mauville Game Corner", "game-corner", "The pink barrel roof, paired glazed bays and illuminated projecting entrance are already separate coherent volumes. The repeated neon framing belongs to this casino facade and the open entrance reads correctly in both previews."),
    24: ("Mauville rear brick annex", "flat-roof-annex", "The source and adjoining-house context show a doorless yellow-brick annex with two arched windows. Its shallow inset roof and raised stone parapet already preserve that distinction and use matching side brickwork."),
    25: ("Lilycove art museum", "museum", "The stepped museum has a lower gallery, centered upper pavilion, forward vestibule and actual stair flight with separate rails and finials. Front and elevated previews preserve all four masses and the patterned flat roofs; no geometry defect was identified."),
    26: ("Lilycove Department Store", "department-store", "The tall store retains its three window tiers, two ground-floor display pairs, projecting canopy and roof rim. Its long side uses the same blue cladding without duplicating the entrance or badge; the tall proportions match the source's distinct multi-storey frontage."),
    33: ("Lilycove fan club", "club-flat-roof", "The low red hall has a clipped-corner pale studded roof and separate cornice over the decorated facade. Both previews keep the blue double entrance and repeated red cladding clear, with no invented upper floor."),
    34: ("Lilycove gold-roof residence", "layered-gold-house", "The drawing's broad lower gold roof and narrower raised central roof are already distinct gabled volumes. The left door and two small right windows remain legible, and the side uses matching timber rather than copied doors."),
    35: ("Lilycove harbor terminal", "harbor-terminal", "The waterside terminal already has a continuous orange barrel roof, paired tall front windows, recessed open entry and repeated side windows. The nautical doorway ornament stays localized and the shallow horizontal wall bands agree with the source."),
    52: ("Lavaridge hot spring surround", "hot-spring", "The low rocky rim surrounds a genuinely empty interior, leaving the exported water to terrain. Its wooden access steps are distinct at the northeast corner. The wall height and irregular stone profile match the spring context."),
    53: ("Mossdeep coastal round-window house", "layered-coastal-house", "The compact house already separates the lower roof and raised center, round dark window, orange door and low stone plinth. Its clean pale side wall avoids duplicating the door or front masonry details."),
    54: ("Mossdeep island round-window house", "layered-coastal-house", "The island context uses the same compact coastal architecture with its own source tiles. Existing stepped roofs, circular window and low plinth remain coherent; terrain around the building is excluded from its side wall."),
    55: ("Mossdeep twin-window coastal house", "layered-coastal-house", "This wider facade has two arched windows and ornamental pale roof ends, unlike the small houses. Existing separate roof tiers preserve those features and the broad stone facade; the sandy source margin does not become a building volume."),
}


def box(x, z, y, paint=None, carve=False):
    return p.box(list(x), list(z), list(y), paint, carve)


def material(rect):
    return p.paint(list(rect), list(rect), list(rect), list(rect))


def entry_recess(parts, x, front, back, height, source):
    """A door slab behind actual air, with a grounded threshold in front."""
    parts.append(box(x, (back, front), (0, height), carve=True))
    parts.append(box(x, (back, back + 1), (0, height), p.paint(list(source), side=list(source))))
    parts.append(box((x[0] - 2, x[1] + 2), (back + 1, front), (0, 2), material((source[0], source[3] - 2, source[2], source[3]))))


def house(number):
    width, door, windows, side = HOUSES[number]
    wall = p.paint([2, 38, width - 2, 64], [2, 38, width - 2, 41], list(side))
    parts = [box((2, width - 2), (8, 61), (0, 28), wall),
             box((0, width), (5, 63), (28, 31), p.paint([2, 34, width - 2, 38], [2, 32, width - 2, 36], [4, 34, 8, 38])),
             {"shape": "gable", "x": [0, width], "z": [5, 63], "y": [31, 51], "ridge": "x",
              "paint": p.paint([2, 32, width - 2, 36], [2, 9, width - 2, 35], [4, 14, 8, 34])},
             box((0, width), (32, 36), (50, 53), p.paint([2, 4, width - 2, 9], [2, 2, width - 2, 6], [4, 2, 8, 8]))]
    entry_recess(parts, door, 63, 56, 23, (*door[:1], 43, door[1], 64))
    for lo, hi in windows:
        parts.append(box((lo, hi), (61, 62), (7, 25), p.paint([lo, 40, hi, 58], side=list(side))))
    # One side light is intentional architecture, not a repeated facade tile.
    lo, hi = windows[0]
    parts.append(box((width - 2, width - 1), (28, 44), (9, 25), p.paint(list(side), side=[lo, 40, hi, 58])))
    return {"height": 56, "parts": parts}


def terrace(number):
    stone = material((24, 34, 25, 35))
    trim = material((6, 4, 7, 5))
    parts = [box((2, 46), (4, 61), (0, 32), p.paint([2, 33, 46, 64], [3, 2, 45, 30], [24, 34, 25, 35])),
             box((0, 48), (2, 62), (32, 35), trim),
             box((3, 45), (6, 58), (35, 36), p.paint([3, 28, 45, 31], [3, 2, 45, 30], [3, 28, 6, 31]))]
    for x, z in [((0, 3), (2, 62)), ((45, 48), (2, 62)), ((3, 45), (2, 5)), ((3, 45), (59, 62))]:
        parts.append(box(x, z, (35, 38), trim))
    if number in TERRACE_DOORS:
        parts.extend([box((9, 39), (60, 63), (0, 24), p.paint([9, 41, 39, 64], side=[9, 42, 12, 61])),
                      box((16, 32), (60, 63), (24, 29), p.paint([16, 36, 32, 41], side=[16, 36, 18, 41]))])
        entry_recess(parts, (17, 31), 64, 57, 22, (17, 43, 31, 64))
    else:
        # These four source variants are windows with no warp or door in maps.
        source = (16, 43, 32, 58) if number in (11, 14) else (17, 43, 31, 57)
        parts.append(box((source[0], source[2]), (61, 62), (7, 22), p.paint(list(source), side=stone["side"])))
    return {"height": 40, "parts": parts}


def gym(number):
    side = [78, 58, 81, 66]
    parts = [box((2, 94), (6, 72), (0, 30), p.paint([3, 46, 94, 70], [3, 6, 93, 44], side)),
             box((1, 95), (2, 73), (30, 35), p.paint([2, 42, 94, 47], [2, 3, 94, 45], [8, 42, 12, 47])),
             box((41, 71), (56, 78), (0, 30), p.paint([41, 52, 71, 70], [41, 44, 71, 52], [43, 61, 44, 65])),
             box((40, 72), (54, 79), (30, 35), p.paint([40, 50, 72, 55], [40, 44, 72, 51], [44, 50, 46, 55]))]
    entry_recess(parts, (48, 64), 80, 74, 24, (48, 60, 64, 79))
    # The freestanding Lavaridge GYM sign belongs only to this source variant.
    if number == 50:
        parts.append(box((82, 94), (75, 78), (0, 24), p.paint([82, 56, 94, 76], side=[82, 56, 84, 76])))
    return {"height": 38, "parts": parts}


def chimney_house(old):
    result = {"height": 80, "parts": copy.deepcopy(old["parts"][:2])}
    parts = result["parts"]
    parts.append(box((33, 56), (49, 64), (48, 55), p.paint([33, 25, 56, 35], [33, 23, 56, 29], [33, 25, 36, 35])))
    # Two independent hollow flues, replacing the old solid painted chimney box.
    for lo in (35, 49):
        parts.append(box((lo, lo + 7), (51, 60), (54, 73), material((36, 8, 40, 17))))
        parts.append(box((lo - 1, lo + 8), (50, 61), (71, 74), material((35, 4, 42, 7))))
        parts.append(box((lo + 2, lo + 5), (53, 58), (69, 75), carve=True))
    entry_recess(parts, (16, 32), 79, 71, 29, (16, 59, 32, 80))
    return result


def league(old):
    result = {"height": old["height"], "parts": copy.deepcopy(old["parts"][:3])}
    parts = result["parts"]
    parts[1]["paint"]["front"] = [16, 0, 20, 88]
    parts[2]["paint"]["front"] = [156, 0, 160, 88]
    parts[1]["z"][1] = parts[2]["z"][1] = 96
    # The original body UV contains the painted canopy. Replace that lower wall
    # with actual orange panels, otherwise a second canopy floats behind ours.
    for x in range(20, 156, 17):
        parts.append(box((x, min(x + 17, 156)), (95, 96), (0, 72), p.paint([24, 48, 40, 96], side=[24, 48, 28, 96])))
    # The original foreground terrain cell (5,6) is the walkable approach.
    # Inset the portico into a real alcove so no canopy is cut by that column.
    parts.append(box((48, 128), (80, 96), (0, 45), carve=True))
    parts.append(p.round_part("cylinder", [88, 80], [40, 14], [25, 43], p.paint([60, 58, 76, 72], [60, 58, 116, 63], [55, 59, 61, 66])))
    for old_part in old["parts"][5:9]:
        column = copy.deepcopy(old_part)
        column["center"][1] -= 16
        parts.append(column)
    parts.append(box((80, 96), (93, 95), (26, 42), p.paint([80, 64, 96, 80], side=[70, 62, 74, 66])))
    parts.append(box((76, 100), (79, 80), (0, 24), p.paint([76, 81, 100, 96], side=[76, 81, 78, 96])))
    parts.append(box((74, 102), (80, 95), (0, 2), material((80, 96, 84, 100))))
    return result


def gateway(width):
    """The visible 3-row roof sits behind the 2-row ground-level approach."""
    parts=[]
    for lo in (2, width-14):
        rect=[0, 47, 14, 78] if lo==2 else [width-14, 47, width, 78]
        parts.append(box((lo,lo+12),(30,47),(0,46),p.paint(rect,side=[2,48,6,77])))
        parts.append(box((lo-1,lo+13),(29,48),(0,4),material((3,73,7,77))))
    parts.append(box((0,width),(4,48),(44,48),p.paint([0,43,width,48],[0,39,width,44],[4,43,8,48])))
    parts.append({"shape":"profile","x":[0,width],"z":[2,48],"y":[48,71],"along":"x", "profile":[3,12,20,23,20,12,3],"cross":[8,20,23,23,23,20,8],
                  "paint":p.paint([2,36,width-2,45],[16,2,width-16,43],[8,16,12,39])})
    return {"height":76,"parts":parts}


def contest():
    parts = [p.round_part("cylinder", [40, 45], [37, 28], [0, 28], p.paint([3, 47, 77, 73], side=[4, 48, 10, 69])),
             p.round_part("dome", [40, 41], [38, 33], [26, 52], p.paint([4, 24, 20, 47], [10, 8, 70, 31], [3, 21, 10, 31])),
             box((20, 60), (57, 77), (0, 29), p.paint([20, 54, 60, 80], [20, 51, 60, 55], [20, 58, 24, 77])),
             box((27, 53), (72, 78), (26, 30), material((19, 51, 23, 54))),
             box((26, 54), (76, 79), (29, 53), p.paint([26, 32, 54, 56], side=[27, 47, 29, 50]))]
    entry_recess(parts, (30, 50), 80, 72, 24, (30, 56, 50, 80))
    return {"height": 56, "parts": parts}


def space_center(old):
    result = {"height": old["height"], "parts": copy.deepcopy(old["parts"])}
    result["parts"].pop(8)  # the source dish is concave, not a second solid dome
    result["parts"].append(box((84, 129), (4, 78), (96, 97), material((80, 3, 84, 7))))
    metal = material((92, 31, 97, 35))
    bowl = [p.round_part("cylinder", [106, 46], [10, 14], [104, 108], metal)]
    # Stepped concave dish, with a visible inner bowl and narrow raised rim.
    for inner, outer, low, high in [(0, 10, 106, 108), (8, 14, 108, 111), (12, 17, 111, 114), (15, 19, 114, 117)]:
        bowl.append(p.round_part("cylinder", [106, 46], [outer, outer * 26 / 19], [low, high], metal))
        if inner:
            bowl.append(p.round_part("cylinder", [106, 46], [inner, inner * 26 / 19], [low, high], carve=True))
    bowl.append(p.round_part("cylinder", [106, 46], 2, [107, 120], material((104, 16, 108, 24))))
    result["parts"].extend(bowl)
    return result


def launch(old):
    result = {"height": old["height"], "parts": copy.deepcopy(old["parts"][3:])}
    parts = result["parts"]
    steel = material((1, 20, 2, 21))
    for part in parts:
        if part["shape"] == "cylinder" and part["y"] == [0, 14]:
            part["paint"]["front"] = [40, 85, 56, 96]
            part["paint"]["top"] = [40, 85, 56, 88]
    # Four corner posts on each gantry, open bays and actual crossed braces.
    for left in (0, 80):
        for x in (left, left + 13):
            for z in (80, 93):
                parts.append(box((x, x + 3), (z, z + 3), (0, 120), steel))
        for y in range(0, 121, 20):
            parts.append(box((left, left + 16), (80, 96), (y, min(y + 3, 123)), steel))
            if y < 120:
                for step in range(10):
                    for x in (left + 2 + step, left + 11 - step):
                        for z in (80, 94):
                            parts.append(box((x, x + 2), (z, z + 2), (y + 2 * step, y + 2 * step + 3), steel))
        parts.append(box((left, left + 16), (80, 96), (120, 124), p.paint([left, 0, left + 16, 16], [left, 0, left + 16, 16], [left + 2, 3, left + 14, 10])))
    # The small service gantry uses the same open construction and its own cap.
    for x in (17, 28):
        for z in (82, 93):
            parts.append(box((x, x + 3), (z, z + 3), (0, 24), steel))
    for y in (0, 21):
        parts.append(box((17, 31), (82, 96), (y, y + 3), steel))
    for step in range(7):
        for x in (19 + step, 27 - step):
            for z in (82, 94):
                parts.append(box((x, x + 2), (z, z + 2), (3 + step * 2, 6 + step * 2), steel))
    parts.append(box((17, 31), (82, 96), (24, 28), p.paint([17, 64, 31, 80], [17, 64, 31, 80], [19, 65, 29, 72])))
    return result


def specifications(table):
    entries = {int(e["id"].split("_")[1][1:]): e for e in table["buildings"]}
    result = {n: house(n) for n in HOUSES}
    result.update({n: terrace(n) for n in TERRACES})
    result.update({n: gym(n) for n in GYMS})
    result.update({40: league(entries[40]), 41: gateway(112), 42: gateway(80), 43: contest(), 47: chimney_house(entries[47]),
                   56: space_center(entries[56]), 57: launch(entries[57])})
    result[51] = {"height": entries[51]["height"], "parts": copy.deepcopy(entries[51]["parts"])}
    entry_recess(result[51]["parts"], (16, 32), 63, 55, 26, (16, 42, 32, 64))
    return result


def generate(table=None):
    table = table if table is not None else json.loads(BASELINE.read_text())
    by_number = {int(e["id"].split("_")[1][1:]): e["id"] for e in table["buildings"]}
    return {by_number[n]: spec for n, spec in specifications(table).items()}


def decision(number):
    if number in RETAIN:
        return (*RETAIN[number], "retained")
    if number in HOUSES:
        city = "Mauville" if number < 24 else "Lilycove" if number < 33 else "Dewford" if number < 38 else "Fallarbor" if number == 46 else "Oldale"
        return (f"{city} tiled residence {number:03}", "pitched-tile-house", "Author the single-storey wall and complete pitched roof from this facade's exact coordinates; add a separate ridge, correctly positioned recessed door and threshold, front window panels and one deliberate side window. Clean source wall swatches replace stretched doorway or fascia strips.", "changed")
    if number in TERRACES:
        doorway = "a recessed front door and stepped portal" if number in TERRACE_DOORS else "the source's doorless decorative window; no invented entry"
        return (f"Sootopolis terrace {number:03}", "sootopolis-roof-terrace", f"Replace inconsistent wall extrusion with a shallow inset tiled terrace and continuous raised perimeter rim, clean white side walls and {doorway}. Exact map warps distinguish door and window variants.", "changed")
    if number in GYMS:
        return (f"Gym {number:03}", "gold-flat-roof-gym", "Keep the source's broad flat gold roof and projecting central vestibule; reduce stretched facade height, give the entrance its own shallow cap and actual recessed door, and use clean panel material on the sides. Preserve the separate Lavaridge sign only where present.", "changed")
    return {
        40: ("Ever Grande Pokemon League", "league-hall", "Preserve the tall hall and four columns; lower the oversized entrance canopy to its source band, isolate the badge on a square plate and add a clear recessed double doorway.", "changed"),
        41: ("Ever Grande broad ceremonial gateway", "ceremonial-gateway", "Author one connected orange canopy and two grounded piers entirely in the roof's rear source footprint. The old canopy crossed excluded foreground passage columns and lost its front-center roof, leaving unintended vertical cut faces at runtime; preserve the original open approach metadata.", "changed"),
        42: ("Ever Grande narrow ceremonial gateway", "ceremonial-gateway", "Use this gateway's narrower source width with a connected curved canopy and two grounded piers, keeping all roof geometry behind the excluded foreground passage columns. Source path and interaction metadata remain unchanged.", "changed"),
        43: ("Fallarbor Contest Hall", "contest-dome", "Use one continuous dome with the roof-only source region; isolate the contest emblem on a square front plate so it is not stretched over the roof, and author a distinct entry porch and recessed double door.", "changed"),
        47: ("Fallarbor twin-flue house", "chimney-house", "Preserve this house's shifted footprint and orange gable, but replace the single solid chimney graphic with two separate hollow flues and add a recessed front door.", "changed"),
        51: ("Lavaridge stepped red-roof house", "stepped-red-house", "Keep the source-specific two-level curved red roof and gold walls, adding the missing physical entrance recess, back-set door slab and grounded threshold.", "changed"),
        56: ("Mossdeep Space Center", "space-center", "Keep the glazed central spine, lower wings and observatory dome. Replace the right convex dome with a genuinely concave stepped satellite dish and central receiver, as identified in the source and space-center map context.", "changed"),
        57: ("Mossdeep launch gantries and rocket", "rocket-launch", "Preserve the multi-stage rocket, short service tower and access arms, replacing two solid facade-textured gantries with four-post open steel frames, horizontal members and crossed braces. Water-blue source gaps no longer become solid tower surfaces.", "changed"),
    }[number]


def main():
    from tools import model_gallery
    from tools.shape_fit.emerald import parts_model
    from PIL import ImageDraw
    import numpy as np
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=BASELINE)
    parser.add_argument("--bundle", type=Path, default=BUNDLE)
    parser.add_argument("--out", type=Path, default=OUT)
    parser.add_argument("--previews", action="store_true")
    args = parser.parse_args()
    raw = args.baseline.read_bytes()
    table = json.loads(raw)
    art = model_gallery.EmeraldArt(args.bundle)
    catalog = {e["id"]: e for e in model_gallery.building_catalog(table, art)}
    specs = generate(table)
    patch, ledger = {}, []
    args.out.mkdir(parents=True, exist_ok=True)
    for old in table["buildings"]:
        number = int(old["id"].split("_")[1][1:])
        if number not in NUMBERS:
            continue
        name = old["id"]
        target = args.out / name
        target.mkdir(parents=True, exist_ok=True)
        drawing = p.drawing(old, args.bundle)
        drawing.save(target / "source.png")
        label, family, reason, status = decision(number)
        entry = {**old, **specs[name]} if name in specs else copy.deepcopy(old)
        if name in specs:
            patch[name] = entry
        (target / "spec.json").write_text(json.dumps(entry, indent=2) + "\n")
        occurrences = catalog[name]["occurrences"]
        if occurrences and not (target / "context.png").exists():
            place = occurrences[0]
            source_map = art.map_image(place["map"]).convert("RGB")
            x, y = place["x"] * 16, place["y"] * 16
            bounds = (max(0, x - 32), max(0, y - 32), min(source_map.width, x + drawing.width + 32), min(source_map.height, y + drawing.height + 32))
            context = source_map.crop(bounds)
            ImageDraw.Draw(context).rectangle((x - bounds[0], y - bounds[1], x + drawing.width - bounds[0] - 1, y + drawing.height - bounds[1] - 1), outline="red")
            context.save(target / "context.png")
        if args.previews and not all((target / f).exists() for f in ("before-front.png", "before-view.png")):
            occ, colors = parts_model.build(old, np.asarray(drawing))
            parts_model.picture(occ, colors, target / "before-view.png", 2)
            parts_model.front_view(occ, colors, target / "before-front.png", 3)
        evidence = [{"source": str((target / "source.png").relative_to(ROOT)), "source_pixels_sha256": hashlib.sha256(drawing.tobytes()).hexdigest(), "size": list(drawing.size), "identification": label},
                    {"map_occurrences": occurrences},
                    {"before_front": str((target / "before-front.png").relative_to(ROOT)), "before_elevated": str((target / "before-view.png").relative_to(ROOT)), "source_and_existing_geometry_reviewed": True}]
        if (target / "context.png").exists():
            evidence.append({"map_context": str((target / "context.png").relative_to(ROOT)), "placement": occurrences[0]})
        if number == 8:
            evidence.append({"near_match_only": {"map": "EM_SOOTOPOLIS_CITY", "x": 28, "y": 28, "matching_cells": 24, "total_cells": 30}, "analysis": "local/model-gallery/emerald-pass-study/buildings/unmatched-context-analysis.json", "limitation": "No exact template occurrence: six top-row upper quarter pairs differ. Template metadata is deliberately unchanged."})
        if args.previews and name in specs:
            occ, colors = parts_model.build(entry, np.asarray(drawing))
            parts_model.picture(occ, colors, target / "view.png", 2)
            parts_model.front_view(occ, colors, target / "front.png", 3)
        if name in specs and (target / "front.png").exists() and (target / "view.png").exists():
            evidence.append({"python_front": str((target / "front.png").relative_to(ROOT)), "python_elevated": str((target / "view.png").relative_to(ROOT))})
        ledger.append(dict(key=name, kind="building", label=label, family=family, decision=status, reason=reason, baseline_entry_sha256=digest(old), evidence=evidence, visual_status="source and existing geometry reviewed; candidate Minecraft comparison pending"))
        print(name, status, flush=True)
    (args.out / "updates.json").write_text(json.dumps({"cells": {}, "buildings": patch}, indent=2) + "\n")
    (args.out / "ledger.json").write_text(json.dumps(ledger, indent=2) + "\n")
    (args.out / "provenance.json").write_text(json.dumps({"baseline_table_sha256": hashlib.sha256(raw).hexdigest(), "generator": str(Path(__file__).relative_to(ROOT)), "reviewed": len(ledger), "changed": len(patch), "retained": len(ledger) - len(patch), "status": "candidate; agent source/geometry review, not user approval"}, indent=2) + "\n")


if __name__ == "__main__":
    main()
