"""Explicit first Emerald scenery batch: Route 112/Lavaridge narrow conifers.

The crown/base stack was cyclic because a repeating source drawing was mistaken
for another supporting object. Only the base's reverse link is removed. Other
flagged empty/missing-source models are documented as pending, never waved through.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path

import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import Model, encode, render, uv

ROOT = Path(__file__).resolve().parents[3]
BASELINE = ROOT / "local/model-gallery/emerald-full-pass-baseline.json"
BUNDLE = ROOT / "local/minecraft/kantobridge/export/emerald"
OUT = ROOT / "local/model-gallery/emerald-pass-study/scenery"
BASE = "g3q/18237-18238-18239-18240"
CROWNS = (
    "g3q/10875-10876-18187-18201",
    "g3q/18199-18200-18187-18201",
    "g3q/18202-18203-18191-18188",
)
NAMES = ("repeating forest conifer", "rock-path conifer", "cliff-edge conifer")
PENDING = {
    "g3q/10728-10729-19702-11304": (
        "Path with projected broadleaf crown tip", "projected foliage over path",
        "Empty fitted model is source-projected foliage over path in Petalburg/Route 117. "
        "Resolve Tier A/columns behavior and adjoining broadleaf variants before reclassifying."),
    "g3q/12112-12121-12129-12136": (
        "Water-edge conifer forest crown", "repeating forest conifer",
        "Empty crown sits above repeated forest-fill cells on Routes 103/119/123. "
        "The shared support chain spans other forest variants; author and audit that family together."),
    "g3q/10202-10328-10204-10329": (
        "Trick House red-edged floor panel, right edge (unplaced variant)", "Trick House puzzle floor",
        "No exact exported map occurrence. Found as metatile 593 in building__trick_house_puzzle; "
        "adjacent atlas variants and Puzzle7 map identify the green striped floor, but placement/state is unresolved."),
    "g3q/10326-10203-10327-10205": (
        "Trick House red-edged floor panel, left edge (unplaced variant)", "Trick House puzzle floor",
        "No exact exported map occurrence. Found as metatile 592 in building__trick_house_puzzle; "
        "adjacent atlas variants and Puzzle7 map identify the green striped floor, but placement/state is unresolved."),
    "g3q/3656-3656-4546-4546": (
        "Elite Four dark threshold strip (unplaced variant)", "Elite Four warp",
        "No exact exported map occurrence. Found as metatile 839 in building__elite_four; "
        "preserve walk/warp metadata and geometry pending placement/state context."),
}


def entry_hash(entry):
    return hashlib.sha256(json.dumps(entry, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def tree():
    """A rooted stem and three connected, tapered foliage lobes, one whole tree."""
    model = Model(height=32)
    # Narrow exposed trunk, flared root collar and rising boughs support the canopy.
    model.cylinder((8, 8), 1.8, (0, 16), 0)
    model.cylinder((8, 8), 2.4, (0, 2), 0)
    for center, radii in (((8, 13, 8), (7, 6, 6)),
                          ((8, 20, 8), (6, 6, 5.2)),
                          ((8, 27, 8), (4, 5, 3.8))):
        model.ellipsoid(center, radii)
    # Anything inside a lobe is foliage even when overlapping the stem.
    canopy = Model(height=32)
    for center, radii in (((8, 13, 8), (7, 6, 6)),
                          ((8, 20, 8), (6, 6, 5.2)),
                          ((8, 27, 8), (4, 5, 3.8))):
        canopy.ellipsoid(center, radii)
    return model.occ, canopy.occ


def painted(key, full_occ, foliage):
    """Source-local material addresses; terrain/cast-shadow colors are excluded."""
    yy, zz, xx = np.indices(full_occ.shape)
    n = ((xx // 2 * 73856093) ^ (yy // 2 * 19349663) ^ (zz // 2 * 83492791)) % 100
    top = ~np.concatenate((foliage[1:], np.zeros_like(foliage[:1])))
    tones = np.where(n < 20, 0, np.where(n < 65, 1, 2))
    tones = np.where(top & (yy >= 20) & (n < 52), 3, tones)
    if key == BASE:
        leaves = [uv(0, 0), uv(1, 0), uv(1, 1), uv(7, 0)]
        bark = np.where((xx + zz * 2) % 5 < 2, uv(5, 8), uv(5, 10))
    elif key == CROWNS[0]:
        leaves = [uv(0, 0), uv(2, 0), uv(1, 0), uv(5, 2)]
        bark = np.zeros_like(xx)
    else:
        leaves = [uv(7, 2), uv(4, 1), uv(2, 4), uv(5, 2)]
        bark = np.zeros_like(xx)
    result = np.asarray(leaves, dtype=np.uint8)[tones]
    return np.where(foliage, result, bark).astype(np.uint8)


def generate(table):
    occ, foliage = tree()
    updates = {}
    for key in (*CROWNS, BASE):
        start = 0 if key == BASE else 16
        original = copy.deepcopy(table["cells"][key])
        if key == BASE:
            original.pop("on", None)
        updates[key] = encode(occ[start:start+16], painted(key, occ, foliage)[start:start+16],
                              original, source="hand-emerald-conifer-review")
    return updates


def entities():
    result = []
    for key, name in zip(CROWNS, NAMES):
        layout = [[key], [BASE]]
        signature = json.dumps([layout, "stack"], separators=(",", ":"))
        identifier = "emerald_compound_" + hashlib.sha256(signature.encode()).hexdigest()[:12]
        result.append({"id": identifier, "kind": "stack (model as one)",
                       "note": "Route 112 / Lavaridge " + name, "layout": layout})
    return result


def study_evidence(table, updates, art, out):
    from PIL import Image, ImageDraw
    from tools.shape_fit.blue_pass.common import decode
    from tools.shape_fit.hand_check import picture, front
    keys = [*CROWNS, BASE, *PENDING]
    page = Image.new("RGB", (1100, len(keys) * 240), "#eeeeee")
    draw = ImageDraw.Draw(page)
    for i, key in enumerate(keys):
        page.paste(art.cell(key, "BAKED").resize((160, 160), Image.Resampling.NEAREST), (5, i*240+35))
        draw.text((5, i*240+5), key, fill="black")
        seen = set()
        for place in art.locations.get(key, []):
            if place["map"] in seen:
                continue
            j = len(seen)
            seen.add(place["map"])
            x, y = place["x"]*16, place["y"]*16
            crop = art.map_image(place["map"]).crop((max(0, x-32), max(0, y-32), x+48, y+48))
            page.paste(crop.resize((240, 240), Image.Resampling.NEAREST), (180+j*300, i*240))
            draw.text((180+j*300, i*240+5), f"{place['map']} ({place['x']},{place['y']})", fill="red")
            if len(seen) == 3:
                break
    page.save(out / "issues.png")
    write_json(out / "occurrences.json", {key: art.locations.get(key, []) for key in keys})
    membership = {}
    for key in PENDING:
        quarters = list(map(int, key.split("/")[1].split("-")))
        membership[key] = [{"tileset": pair, "metatiles": [mid for mid, values in tiles.items() if values == quarters],
                            "maps_using_tileset": [name for name, source in art.maps.items() if source["pair"] == pair]}
                           for pair, tiles in art.quarters.items() if quarters in tiles.values()]
    write_json(out / "source-variants.json", membership)
    for pair, mid in (("building__trick_house_puzzle", 592), ("building__elite_four", 839)):
        quarters = art.quarters[pair]
        mids = [value for value in range(mid-8, mid+16) if value in quarters]
        sheet = Image.new("RGB", (1024, 450), "white")
        labels = ImageDraw.Draw(sheet)
        for i, value in enumerate(mids):
            key = "g3q/" + "-".join(map(str, quarters[value]))
            x, y = i % 8 * 128, i // 8 * 150
            sheet.paste(art.cell(key, "BAKED").resize((128, 128), Image.Resampling.NEAREST), (x, y+20))
            labels.text((x, y), str(value), fill="black")
        sheet.save(out / f"{pair}.png")
    art.map_image("EM_ROUTE110_TRICK_HOUSE_PUZZLE7").save(out / "trick7.png")
    for variant, key in enumerate(CROWNS):
        dest = out / f"whole-{variant}"
        dest.mkdir(exist_ok=True)
        for revision, entries in (("before", table["cells"]), ("current", updates)):
            masks, colors = [], []
            for part_key in (BASE, key):
                occ, paint = decode(entries[part_key])
                source = np.asarray(art.cell(part_key, "BAKED").convert("RGB"))
                masks.append(occ)
                colors.append(source[paint//16, paint%16])
            occ, colors = np.concatenate(masks), np.concatenate(colors)
            picture(occ, colors, dest / f"{revision}-angled.png", 10)
            front(occ, colors, dest / f"{revision}-front.png", 10)
        art.drawing([[key], [BASE]], "BAKED").resize((160, 320), Image.Resampling.NEAREST).save(dest / "source.png")


def main():
    from PIL import Image
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--baseline", type=Path, default=BASELINE)
    ap.add_argument("--bundle", type=Path, default=BUNDLE)
    ap.add_argument("--out", type=Path, default=OUT)
    args = ap.parse_args()
    table = json.loads(args.baseline.read_text())
    art = EmeraldArt(args.bundle)
    updates = generate(table)
    ledger = []
    for key in (*CROWNS, BASE):
        locations = art.locations[key]
        name = NAMES[CROWNS.index(key)] + " crown" if key in CROWNS else "Conifer lower foliage, trunk and root collar"
        reason = "Whole tree authored with three tapered foliage lobes and narrow rooted trunk; split vertically at y=16, excluding rock and cast-shadow pixels."
        if key == BASE:
            reason += " Removed erroneous reverse on-link to crown: Route112 (9,10)->(9,11) and Lavaridge (19,3)->(19,4) establish crown above base."
        ledger.append({"key": key, "kind": "cell", "label": name, "family": "Route112 conifer",
                       "decision": "changed", "reason": reason,
                       "baseline_entry_sha256": entry_hash(table["cells"][key]),
                       "occurrences": locations, "evidence": ["issues.png", "occurrences.json", "whole-1/source.png"]})
        dest = args.out / key.split("/")[1]
        dest.mkdir(parents=True, exist_ok=True)
        sprite = art.cell(key, "BAKED")
        sprite.resize((256, 256), Image.Resampling.NEAREST).save(dest / "source.png")
        render(updates[key], sprite, dest / "current")
    pending = [{"key": k, "kind": "cell", "label": label, "family": family,
                "status": "pending", "reason": reason, "occurrences": art.locations.get(k, []),
                "baseline_entry_sha256": entry_hash(table["cells"][k]),
                "evidence": ["issues.png", "occurrences.json", "source-variants.json"]} for k, (label, family, reason) in PENDING.items()]
    write_json(args.out / "updates.json", {"cells": updates, "buildings": {}})
    write_json(args.out / "ledger.json", ledger)
    write_json(args.out / "pending.json", pending)
    write_json(args.out / "entities.json", entities())
    study_evidence(table, updates, art, args.out)
    print(f"{len(updates)} conifer pieces authored; {len(pending)} source-context issues remain pending")


if __name__ == "__main__":
    main()
