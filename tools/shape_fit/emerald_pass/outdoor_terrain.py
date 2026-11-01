"""Outdoor terrain (Route 110/111/112/117/118, Jagged Pass, Mt Chimney, Lavaridge, Verdanturf, Slateport).

Runtime reading under the default flat outdoor planning (kantobridge.outdoorLevels unset):
claude-outdoor-current is an identity census of every placement of the 211 keys; claude-outdoor-probe
hands the 107 drawn keys to native columns and records native height against fitted height.

* Never drawn (104 keys): walkable, ledge or water at every placement, or solid cells another
  shape claims (folds, templates, stacks). Their fitted entries have no runtime effect: retained.
* Jagged Pass cliffs, rock and mud (14): a 2-block fitted model inside native cliffs measured 3-8
  blocks, a notch in the face. Native columns (amendment): the cliff stands continuous. The
  ash-covered trees and bushes (19) notch too, but as columns they merged into an 8-high wall in
  the batch-B capture: retained as individual trees.
* Ash plains (2): a thin slab under a 2-block solid collision (an invisible wall). Native columns.
* The Route 112 -> Mt Chimney cable (6 cells): the cable is drawn across solid cliff cells; the
  fitted model was a stick lying on the ground. Native columns: the terrace with the cable art.
* The cable car pylons (4 placements, 2 drawings): the base cell carries a standing steel pylon,
  6 blocks tall (footing ring, tube, crossbar) on its ash block; the shaft and crossbar cells
  become plain ash blocks with the blue drawing masked out (no ghost pylon on the ground).
* Everything else drawn (Cycling Road railings, trees, crates, Mt Chimney ash rocks and stacks,
  the meteorite machine, Route 118 banks) is retained: each reads as its drawing at the native
  height of its surroundings.
"""
from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import json
from pathlib import Path

import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import encode
from tools.shape_fit.emerald_pass import claude_furniture as fur
from tools.shape_fit.emerald_pass.claude_source_paint import CellPaint

ROOT = Path(__file__).resolve().parents[3]
BASELINE = ROOT / "local/model-gallery/emerald-full-pass-baseline.json"
BUNDLE = ROOT / "local/minecraft/kantobridge/export/emerald"
STUDY = ROOT / "local/model-gallery/emerald-pass-study"
OUT = STUDY / "claude-outdoor"
AMENDMENTS = Path(__file__).with_name("source_amendments.json")
SOURCE = "hand-emerald-outdoor-terrain"
K = lambda s: "g3q/" + s
NOTCH = [K(s) for s in (
    "12335-18596-12337-18597", "12353-18592-18593-18515", "12353-18592-18611-18515", "18181-18182-18183-18642", "18181-18182-18643-18184",
    "18181-18639-18640-18641", "18249-18321-18589-18406", "18315-18316-18396-18587", "18315-18316-18396-18616", "18315-18316-18403-18602",
    "18315-18316-18588-18397", "18315-18316-18603-18404", "18315-18316-18712-18397", "18365-18244-18395-18616", "18393-18244-18402-18587",
    "18508-18509-18510-18511", "18508-18509-18510-18541", "18512-18513-18514-18515", "18512-18513-18538-18515", "18512-18513-18538-18539",
    "18512-18570-18571-18572", "18542-18543-18544-18545", "18546-18547-18548-18549", "18546-18552-18548-18553", "18546-18583-18548-18584",
    "18550-18543-18551-18545", "18567-18509-18568-18569", "18581-18543-18582-18545", "18590-12350-18510-18591", "18590-12350-18510-18610",
    "18594-12332-18595-12334", "18624-18625-18626-18627", "18636-18182-18637-18638")]
TREES = [K(s) for s in (
    "18508-18509-18510-18511",
    "18508-18509-18510-18541",
    "18512-18513-18514-18515",
    "18512-18513-18538-18515",
    "18512-18513-18538-18539",
    "18512-18570-18571-18572",
    "18542-18543-18544-18545",
    "18546-18547-18548-18549",
    "18546-18552-18548-18553",
    "18546-18583-18548-18584",
    "18550-18543-18551-18545",
    "18567-18509-18568-18569",
    "18581-18543-18582-18545",
    "12335-18596-12337-18597",
    "12353-18592-18593-18515",
    "12353-18592-18611-18515",
    "18590-12350-18510-18591",
    "18590-12350-18510-18610",
    "18594-12332-18595-12334",
)]
NOTCH = [k for k in NOTCH if k not in TREES]
PLAINS = [K("18193-18198-18183-18184"), K("18413-18414-18183-18184")]
CABLE = [K(s) for s in ("18181-18182-18330-18184", "18320-18321-18322-18323", "18324-18321-18325-18326", "18327-18182-18328-18329",
                        "18343-18344-18227-18326", "18347-18348-18183-18184")]
BASES = [K("18369-18370-18371-18372"), K("18373-18374-18183-18184")]
SHAFTS = [K(s) for s in ("18181-18182-18331-18332", "18333-18334-18335-18336", "18349-18350-18335-18336", "18349-18350-18351-18352")]
COLUMNS = NOTCH + PLAINS + CABLE
ASH = {(214, 156, 140), (189, 132, 115), (156, 99, 82)}
EDGE, LIGHT, BLUE, BAND, CONCRETE = (66, 74, 107), (140, 206, 247), (115, 189, 239), (99, 99, 123), (148, 148, 140)


def keys():
    return json.loads((STUDY / "claude-terrain/outdoor-keys.json").read_text())


def census():
    return json.loads((OUT / "census.json").read_text())


def entry_hash(entry):
    return hashlib.sha256(json.dumps(entry, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def ash_index(cell):
    """Per pixel: its own index when it is ash, else the nearest ash pixel's (the pylon masked out)."""
    px = cell.pixels
    out = np.zeros((16, 16), int)
    for v in range(16):
        for u in range(16):
            out[v, u] = v * 16 + u if tuple(px[v, u]) in ASH else cell.pick(list(ASH), u, v)
    return out


def ash_block(cell, height=16):
    yy, zz, xx = np.indices((height, 16, 16))
    occ = np.ones((height, 16, 16), bool)
    up, ox, oz = fur.faces(occ)
    ash = ash_index(cell)
    side = ash[np.clip(15 - yy, 0, 15), xx]
    return occ, np.where(up, ash[zz, xx], side).astype(np.uint8)


def pylon(cell):
    """The base cell: its ash block (16 high) carrying a steel pylon to 48 (footing ring, tube, crossbar)."""
    occ, paint = ash_block(cell, 48)
    occ[16:] = False
    yy, zz, xx = np.indices((48, 16, 16))
    r = np.hypot(xx + .5 - 8, zz + .5 - 8)
    footing = (yy >= 16) & (yy < 18) & (r < 4.6)
    tube = (yy >= 16) & (yy < 41) & (r < 3.1)
    bar = (yy >= 40) & (yy < 45) & (xx >= 1) & (xx <= 14) & (zz >= 6) & (zz <= 9)
    occ |= footing | tube | bar
    up, ox, oz = fur.faces(occ)
    across = 4 + np.rint((np.where(oz, xx, zz) - 5) * 7 / 5).clip(0, 7).astype(int)    # the tube's drawn row, edge to edge
    px = cell.pixels
    row = next(v for v in range(16) if tuple(px[v, 4]) == EDGE and tuple(px[v, 11]) == EDGE and tuple(px[v, 7]) == BLUE)
    tube_paint = row * 16 + across                                                   # a pure tube row of this cell's drawing
    p = np.where(footing & ~tube, cell.pick([CONCRETE], 8, 5), tube_paint)
    p = np.where((yy % 12 == 4) & tube & ~up, cell.pick([BAND], 8, 4), p)          # joint bands up the tube
    bar_side = np.where(yy >= 43, cell.pick([LIGHT], 6, 1), np.where(yy == 40, cell.pick([EDGE], 4, 1), cell.pick([BLUE], 8, 1)))
    p = np.where(bar, np.where(up, cell.pick([LIGHT], 6, 1), bar_side), p)
    paint = np.where(yy >= 16, p, paint).astype(np.uint8)
    return occ, paint


def generate(table, art):
    updates = {k: {"class": "columns", "iou": 1.0} for k in COLUMNS}
    put = lambda key, occ, paint: updates.__setitem__(key, encode(occ, paint, copy.deepcopy(table["cells"][key]), source=SOURCE, trim=True))
    for key in BASES:
        put(key, *pylon(CellPaint(art, key)))
    for key in SHAFTS:
        put(key, *ash_block(CellPaint(art, key)))
    return updates


def witnesses(art, key):
    out = []
    for p in art.locations[key]:
        src = art.maps[p["map"]]; raw = base64.b64decode(src["cells"]); w = src["w"]
        out.append({"map": p["map"], "x": p["x"], "y": p["y"], "palette": "BAKED", "collision": raw[(p["y"] * w + p["x"]) * 4 + 2]})
    return out


def write_amendments(art):
    sha = lambda k: hashlib.sha256(art.cell(k, "BAKED").convert("RGB").tobytes()).hexdigest()
    data = json.loads(AMENDMENTS.read_text(encoding="utf-8"))
    for key in COLUMNS:
        spec = {"before": {"class": "voxels"}, "after": {"class": "columns", "iou": 1.0}, "source_pixel_sha256": sha(key),
                "witnesses": witnesses(art, key), "evidence": "claude-outdoor-probe/runtime-proof.json: native cliff, ash and cable columns"}
        if key in data["metadata"] and data["metadata"][key] != spec:
            raise ValueError("a different amendment already exists: " + key)
        data["metadata"][key] = spec
    AMENDMENTS.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def ledger(table, art):
    rows, seen = [], census()
    def row(key, label, decision, reason, family):
        rows.append({"key": key, "kind": "cell", "label": label, "family": family, "decision": decision, "reason": reason,
                     "baseline_entry_sha256": entry_hash(table["cells"][key]), "occurrences": art.locations.get(key, []),
                     "evidence": ["census.json", "probe-heights.json", "drawn/cells.png", "runtime-proof.json"]})
    for key in keys():
        c = seen[key]
        if key in NOTCH:
            row(key, "Jagged Pass cliff, rock or ash pile", "changed", "The 2-block fitted model notched native cliffs measured 3-8 blocks "
                "(probe-heights.json). Native columns (amendment): the cliff face stands continuous with this cell's art folded.", "volcanic-cliff")
        elif key in PLAINS:
            row(key, "ash plain on solid ground", "changed", "A thin fitted slab under a 2-block solid collision (an invisible wall). Native columns "
                "(amendment): the ash terrace at the collision's height.", "volcanic-ash")
        elif key in CABLE:
            row(key, "Route 112 cable car cable over the cliffs", "changed", "The cable crosses solid cliff cells; the fitted stick lay on the "
                "ground. Native columns (amendment): the terrace with the cable art folded on it.", "cable-car")
        elif key in BASES:
            row(key, "cable car pylon (base)", "changed", "A standing steel pylon 6 blocks tall (concrete footing ring, tube with joint bands, "
                "crossbar) on the cell's own ash block; all colours from this cell's drawing.", "cable-car")
        elif key in SHAFTS:
            row(key, "cable car pylon (shaft or crossbar drawing)", "changed", "The pylon now stands on its base cell; this cell is the plain "
                "ash block at the terrace height, the blue drawing masked to its nearest ash pixels (no ghost pylon).", "cable-car")
        elif key in TREES:
            row(key, "Jagged Pass ash-covered tree or bush", "retained", "Individual rounded tree and bush models read as the drawing's trees; "
                "as native columns they merged into an 8-high wall (claude-trial/batch-b-review, the Jagged Pass contexts), so retained.", "volcanic-trees")
        elif not c["voxel_drawn"]:
            kinds = "/".join(sorted(c["kinds"]))
            row(key, f"terrain cell ({kinds} at every placement)", "retained", f"Never drawn under the default planner (census.json: {kinds}, "
                "no placement uses the fitted voxels); the native terrain, folds and templates draw it.", "terrain-native")
        else:
            row(key, "terrain object", "retained", "Drawn at the native height of its surroundings (probe-heights.json) and reads as its "
                "drawing: Cycling Road railing, tree, crate, ash rock or stack, machine or bank piece.", "terrain-object")
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write-amendments", action="store_true")
    args = ap.parse_args()
    table = json.loads(BASELINE.read_text(encoding="utf-8-sig"))
    art = EmeraldArt(BUNDLE)
    assert set(COLUMNS + BASES + SHAFTS) <= set(keys()) and len(set(COLUMNS + BASES + SHAFTS)) == 28 and not set(TREES) & set(COLUMNS)
    if args.write_amendments:
        write_amendments(art)
    updates = generate(table, art)
    rows = ledger(table, art)
    assert len(rows) == len({r["key"] for r in rows}) == 211
    write_json(OUT / "updates.json", {"cells": updates, "buildings": {}})
    write_json(OUT / "ledger.json", rows)
    print(f"{len(updates)} changed, {sum(r['decision'] == 'retained' for r in rows)} retained -> {OUT}")


if __name__ == "__main__":
    main()
