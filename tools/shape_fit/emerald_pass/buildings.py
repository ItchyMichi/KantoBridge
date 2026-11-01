"""Explicit Emerald building families, emitted as guarded candidate patches.

No live resource writes. Art, maps, patches and previews stay under local/.
The first batch transfers the accepted Center construction to matching source
drawings, authors the lower Mart roof, and mirrors the second Littleroot house.
Minecraft comparison and acceptance remain separate from generation.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path

from tools.shape_fit.emerald import building_trials as pilots

ROOT = Path(__file__).resolve().parents[3]
BASELINE = ROOT / "local/model-gallery/emerald-full-pass-baseline.json"
BUNDLE = ROOT / "local/minecraft/kantobridge/export/emerald"
OUT = ROOT / "local/model-gallery/emerald-pass-study/buildings"
CENTER_IDS = (
    "emerald_b006_EM_PACIFIDLOG_TOWN", "emerald_b010_EM_SOOTOPOLIS_CITY",
    "emerald_b027_EM_LILYCOVE_CITY", "emerald_b038_EM_DEWFORD_TOWN",
    "emerald_b044_EM_FALLARBOR_TOWN", "emerald_b048_EM_LAVARIDGE_TOWN",
    "emerald_b059_EM_OLDALE_TOWN", "emerald_b064_EM_RUSTBORO_CITY",
    "emerald_b086_EM_VERDANTURF_TOWN",
)
MART_IDS = (
    "emerald_b004_EM_FORTREE_CITY", "emerald_b009_EM_SOOTOPOLIS_CITY",
    "emerald_b045_EM_FALLARBOR_TOWN", "emerald_b049_EM_LAVARIDGE_TOWN",
    "emerald_b065_EM_RUSTBORO_CITY", "emerald_b085_EM_VERDANTURF_TOWN",
)
HOUSE_ID = "emerald_b001_EM_LITTLEROOT_TOWN"


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def mart():
    """Blue roof with a broad crown and its undistorted, independent badge."""
    result = copy.deepcopy(pilots.center())
    roof = result["parts"][1]
    roof["y"] = [28, 51]
    roof["profile"] = [8, 15, 21, 23, 23, 23, 21, 15, 8]
    roof["cross"] = [7, 17, 23, 23, 23, 20, 10]
    roof["paint"] = pilots.paint([8, 28, 12, 31], [8, 9, 56, 24], [8, 28, 12, 31])
    result["height"] = 54
    return result


def second_house():
    """Left-hand entrance and right-hand window from the second house drawing."""
    result = copy.deepcopy(pilots.house())
    for part in result["parts"]:
        if "x" in part:
            lo, hi = part["x"]
            part["x"] = [80 - hi, 80 - lo]
        for name, rect in part.get("paint", {}).items():
            if name != "wrap":
                lo, top, hi, bottom = rect
                part["paint"][name] = [80 - hi, top, 80 - lo, bottom]
    return result


def generate():
    result = {name: copy.deepcopy(pilots.center()) for name in CENTER_IDS}
    result.update({name: mart() for name in MART_IDS})
    result[HOUSE_ID] = second_house()
    return result


def source_contacts(table, bundle, out):
    from PIL import Image, ImageDraw
    for page in range((len(table["buildings"]) + 27) // 28):
        canvas = Image.new("RGB", (1200, 1855), (32, 35, 40))
        pen = ImageDraw.Draw(canvas)
        for index, entry in enumerate(table["buildings"][page * 28:(page + 1) * 28]):
            target = out / entry["id"]
            target.mkdir(parents=True, exist_ok=True)
            art = pilots.drawing(entry, bundle)
            art.save(target / "drawing.png")
            scale = min(280 / art.width, 230 / art.height)
            thumb = art.resize((int(art.width * scale), int(art.height * scale)), Image.Resampling.NEAREST)
            x, y = index % 4 * 300, index // 4 * 265
            canvas.paste(thumb, (x + (300 - thumb.width) // 2, y + 25))
            pen.text((x + 5, y + 3), entry["id"].removeprefix("emerald_"), fill="white")
        canvas.save(out / f"contact-{page}.png")


def main():
    from PIL import Image, ImageDraw
    from tools import model_gallery
    from tools.shape_fit.emerald import parts_model
    import numpy as np

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=BASELINE)
    parser.add_argument("--bundle", type=Path, default=BUNDLE)
    parser.add_argument("--out", type=Path, default=OUT)
    parser.add_argument("--previews", action="store_true")
    parser.add_argument("--contacts", action="store_true")
    args = parser.parse_args()
    raw = args.baseline.read_bytes()
    table = json.loads(raw)
    entries = {entry["id"]: entry for entry in table["buildings"]}
    art = model_gallery.EmeraldArt(args.bundle)
    catalog = {entry["id"]: entry for entry in model_gallery.building_catalog(table, art)}
    args.out.mkdir(parents=True, exist_ok=True)
    if args.contacts:
        source_contacts(table, args.bundle, args.out)
    patch, ledger = {}, []
    for name, changes in generate().items():
        old = entries[name]
        entry = {**old, **changes}
        patch[name] = entry
        family = "pokemon-center" if name in CENTER_IDS else "poke-mart" if name in MART_IDS else "littleroot-house"
        reason = {
            "pokemon-center": "Replace overlapping roof masses with the accepted continuous curved roof, independent square badge, clean wall swatches, separate side window and recessed entrance; same 64x64 facade and badge source coordinates.",
            "poke-mart": "Replace the overlapping blue roofs with one broad low curved roof; isolate the badge from roof stretching and the MART sign from repeated wall paint; add a grounded threshold and open entry recess.",
            "littleroot-house": "Apply the accepted single-storey layered house construction with the source drawing's left entrance and right window; preserve the shared roof proportions and exclude corner grass from wall materials.",
        }[family]
        target = args.out / name
        target.mkdir(parents=True, exist_ok=True)
        drawing = pilots.drawing(entry, args.bundle)
        drawing.save(target / "drawing.png")
        drawing.resize((drawing.width * 5, drawing.height * 5), Image.Resampling.NEAREST).save(target / "source.png")
        (target / "spec.json").write_text(json.dumps(entry, indent=2) + "\n")
        occurrence = catalog[name]["occurrences"]
        evidence = [{"source": str((target / "drawing.png").relative_to(ROOT)),
                     "source_pixels_sha256": hashlib.sha256(drawing.tobytes()).hexdigest(),
                     "size": list(drawing.size), "identification": family},
                    {"map_occurrences": occurrence}]
        if occurrence:
            place = occurrence[0]
            source_map = art.map_image(place["map"]).convert("RGB")
            x, y = place["x"] * 16, place["y"] * 16
            bounds = (max(0, x - 32), max(0, y - 32), min(source_map.width, x + drawing.width + 32), min(source_map.height, y + drawing.height + 32))
            crop = source_map.crop(bounds)
            pen = ImageDraw.Draw(crop)
            pen.rectangle((x - bounds[0], y - bounds[1], x - bounds[0] + drawing.width - 1, y - bounds[1] + drawing.height - 1), outline="red")
            crop.resize((crop.width * 3, crop.height * 3), Image.Resampling.NEAREST).save(target / "context.png")
            evidence.append({"map_context": str((target / "context.png").relative_to(ROOT)), "placement": place})
        if args.previews:
            occupied, colors = parts_model.build(entry, np.asarray(drawing))
            parts_model.picture(occupied, colors, target / "view.png", 3)
            parts_model.front_view(occupied, colors, target / "front.png", 5)
        ledger.append(dict(key=name, kind="building", label=("Pokemon Center" if name in CENTER_IDS else "Poke Mart" if name in MART_IDS else "Littleroot left-door house"), family=family,
                           decision="changed", reason=reason, baseline_entry_sha256=digest(old), evidence=evidence,
                           visual_status="source-and-python-preview; awaiting Minecraft comparison"))
        print(name, "candidate emitted", flush=True)
    (args.out / "updates.json").write_text(json.dumps({"cells": {}, "buildings": patch}, indent=2) + "\n")
    (args.out / "ledger.json").write_text(json.dumps(ledger, indent=2) + "\n")
    (args.out / "provenance.json").write_text(json.dumps({"baseline_table_sha256": hashlib.sha256(raw).hexdigest(), "generator": str(Path(__file__).relative_to(ROOT)), "buildings": list(patch), "status": "candidate; runtime visual review pending"}, indent=2) + "\n")


if __name__ == "__main__":
    main()
