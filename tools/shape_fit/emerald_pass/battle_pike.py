"""Battle Pike (lobby, corridor, rooms): curtain walls, stage arch, crowned armchairs.

Source reading (claude-pike/study): a theatre of red curtain walls with gold valances and a
pink-lit stage arch, furnished with armchairs (red, gold and silver) each drawn as two cells: a
crowned backrest over the seat, already an existing stack. Curtain, valance and arch cells are
flat wall art over the wall rows: native columns (amendment), including the PC and screen tops
drawn on the curtain, which the fold then paints directly above the real objects. Armchairs are
rebuilt as one object per stack: the seat cell carries legs, cushion, arms and the lower back,
the stacked top the tall back and its crown, both faces by projection of their own drawings.
The reception monitor and screen stand on the curtain wall's front row as full-height wall
sections carrying them. The stage door and two projected feet stay as drawn (retained).
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
OUT = ROOT / "local/model-gallery/emerald-pass-study/claude-pike"
AMENDMENTS = Path(__file__).with_name("source_amendments.json")
SOURCE = "hand-emerald-battle-pike"
WALL_H = 72
K = lambda s: "g3q/" + s

WALL_COLUMNS = [K(s) for s in (
    "2061-2061-2062-2063", "2061-2064-2065-2066", "2064-2064-2067-2068", "2076-2077-2078-2189", "2143-2144-2190-2191", "2084-2085-2192-2087",
    "2154-2193-2156-2194", "2190-2191-2190-2191", "2195-2163-2196-2165", "2175-2197-2117-2198", "2199-2200-2201-2202", "2203-2181-2204-2126",
    "2205-2206-2207-2208", "2209-2210-2211-2212", "2076-2077-2078-2213", "2214-2215-2216-2217", "2084-2085-2218-2087", "2219-2220-2221-2222",
    "2223-2224-2225-2226", "2227-2228-2229-2230", "2231-2232-2233-2234", "2219-2220-2235-2236", "2241-2242-2243-2244", "2245-2246-2247-2248",
    "2154-2193-2383-2384", "2195-2163-2385-2282")]
RETAINED = {K("2119-2120-2121-2122"): ("pink-lit stage door (warp, walk flag)", "A warp doorway with its existing walk entry; the stage light reads correctly and warp behaviour is not touched."),
            K("2283-2284-2264-2285"): ("PC foot drawn on the floor (WALK0)", "Projected foot of the PC; floor art."),
            K("2394-2395-2264-2285"): ("screen foot drawn on the floor (WALK0)", "Projected foot of the standing screen; floor art.")}
CHAIR_TOPS = [K(s) for s in ("2069-2070-2071-2072", "2073-2070-2074-2072", "2073-2075-2071-2072", "2105-2106-2107-2108", "2109-2110-2111-2112",
                             "2134-2135-2136-2137", "2138-2139-2140-2141", "2166-2167-2168-2169", "2170-2171-2172-2173", "2307-2308-2140-2137",
                             "2309-2167-2310-2169", "2375-2376-2377-2378")]
CHAIR_BASES = [K(s) for s in ("2088-2089-2090-2091", "2092-2089-2090-2091", "2092-2127-2090-2091", "2128-2129-2130-2131", "2148-2149-2150-2151",
                              "2152-2153-2150-2151", "2183-2184-2185-2186", "2183-2187-2185-2188", "2183-2184-2185-2188", "2332-2184-2185-2188")]
SCREEN = K("43-44-2386-2387")
MONITOR = [K("2175-2388-2117-2389"), K("2390-2391-203-204"), K("2392-2181-2393-2126")]
RED, CURTAIN_D, FOLD, FRAME, PALE = (255, 0, 0), (197, 0, 0), (123, 49, 0), (99, 99, 123), (206, 206, 197)


def entry_hash(entry):
    return hashlib.sha256(json.dumps(entry, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def project(occ, v_of_y):
    """Faces by projection: south/north faces show (row(y), x), east/west faces (row(y), z), tops from above."""
    yy, zz, xx = np.indices(occ.shape)
    up, ox, oz = fur.faces(occ)
    v = v_of_y(yy).clip(0, 15)
    return np.where(up & ~oz & ~ox, zz * 16 + xx, v * 16 + np.where(ox & ~oz, zz, xx)).astype(np.uint8)


def chair_base(cell):
    yy, zz, xx = np.indices((16, 16, 16))
    legs = ((xx < 3) | (xx >= 13)) & ((zz >= 12) & (zz < 15) | (zz >= 2) & (zz < 5)) & (yy < 6)
    seat = (xx >= 2) & (xx < 14) & (zz >= 3) & (zz < 15) & (yy >= 5) & (yy < 8)
    arms = ((xx >= 1) & (xx < 3) | (xx >= 13) & (xx < 15)) & (zz >= 4) & (zz < 15) & (yy >= 5) & (yy < 11)
    back = (xx >= 2) & (xx < 14) & (zz >= 2) & (zz < 5) & (yy >= 5)
    occ = legs | seat | arms | back
    return occ, project(occ, lambda y: 15 - y)


def chair_top(cell):
    yy, zz, xx = np.indices((16, 16, 16))
    back = (xx >= 2) & (xx < 14) & (zz >= 2) & (zz < 5) & (yy < 9)
    crown = (((xx + .5 - 8) / 4.5) ** 2 + ((yy + .5 - 10) / 4) ** 2 <= 1) & (zz >= 2) & (zz < 5) & (yy >= 8)
    finials = ((np.abs(xx + .5 - 4.5) < 1) | (np.abs(xx + .5 - 11.5) < 1) | (np.abs(xx + .5 - 8) < 1)) & (zz >= 2) & (zz < 4) & (yy >= 13) & (yy < 15)
    occ = back | crown | finials
    return occ, project(occ, lambda y: 15 - y)


def wall_with(cell, front_part):
    """A full-height wall section on the curtain's front row; front_part (occ, paint) stands in front."""
    yy, zz, xx = np.indices((WALL_H, 16, 16))
    wall = zz < 9
    occ = wall.copy()
    # curtain red with brown folds; a cell drawn entirely as its object gets a dark backdrop panel
    paint = np.full(occ.shape, cell.pick([RED, CURTAIN_D, FRAME], 8, 2), np.uint8)
    paint[wall & (xx % 5 == 0)] = cell.pick([FOLD, CURTAIN_D, FRAME], 8, 2)
    o, p = front_part
    n = min(WALL_H, len(o))
    occ[:n] |= o[:n]
    paint[:n][o[:n]] = p[:n][o[:n]]
    return occ, paint


def generate(table, art):
    updates = {}
    def put(key, occ, paint):
        updates[key] = encode(occ, paint, copy.deepcopy(table["cells"][key]), source=SOURCE, trim=True)
    for key in WALL_COLUMNS:
        updates[key] = {"class": "columns", "iou": 1.0}
    for key in CHAIR_BASES:
        put(key, *chair_base(CellPaint(art, key)))
    for key in CHAIR_TOPS:
        put(key, *chair_top(CellPaint(art, key)))
    c = CellPaint(art, SCREEN)
    put(SCREEN, *wall_with(c, fur.framed_screen(c, base=0, h=WALL_H, rows=(0, 13), x0=2, x1=14, frame=[PALE, FRAME])))
    cells = [CellPaint(art, k) for k in MONITOR]
    yy, zz, xx = np.indices((WALL_H, 16, 48))
    board = (xx >= 9) & (xx < 39) & (zz >= 9) & (zz < 11) & (yy >= 2) & (yy < 15)
    for i, key in enumerate(MONITOR):
        part = slice(i * 16, (i + 1) * 16)
        b = board[:, :, part]
        v = (15 - (yy[:, :, part] - 1)).clip(0, 15)
        bp = (v * 16 + xx[:, :, part] - i * 16).astype(np.uint8)
        put(key, *wall_with(cells[i], (b, bp)))
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
    for key in WALL_COLUMNS:
        spec = {"before": {"class": "voxels"}, "after": {"class": "columns", "iou": 1.0}, "source_pixel_sha256": sha(key),
                "witnesses": witnesses(art, key), "evidence": "claude-pike/runtime-proof.json: native curtain wall columns replace the notching single layer"}
        if key in data["metadata"] and data["metadata"][key] != spec:
            raise ValueError("a different amendment already exists: " + key)
        data["metadata"][key] = spec
    AMENDMENTS.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def ledger(table, art):
    rows = []
    def row(key, label, decision, reason, family):
        rows.append({"key": key, "kind": "cell", "label": "Battle Pike " + label, "family": family, "decision": decision, "reason": reason,
                     "baseline_entry_sha256": entry_hash(table["cells"][key]), "occurrences": art.locations.get(key, []),
                     "evidence": ["study/cells.png", "runtime-proof.json"]})
    for k in WALL_COLUMNS:
        row(k, "curtain wall, valance or stage arch section", "changed", "Flat wall art over the wall rows; its fitted single layer cut the "
            "native wall. Native columns (amendment): the fold paints the drawn curtains at the measured height.", "battle-pike-curtain-wall")
    for k, (l, r) in RETAINED.items():
        row(k, l, "retained", r, "battle-pike-native")
    for k in CHAIR_BASES:
        row(k, "armchair seat", "changed", "Seat of the existing two-cell armchair stack: four legs, cushion, padded arms and the lower back, "
            "faces by projection of this drawing.", "battle-pike-armchair")
    for k in CHAIR_TOPS:
        row(k, "armchair back and crown (stack top)", "changed", "Existing stack top: the tall back with its rounded crown and three finials, "
            "standing on the seat; faces by projection of this drawing.", "battle-pike-armchair")
    row(SCREEN, "information screen against the curtain", "changed", "The curtain's front row as a full-height wall section with the framed "
        "screen standing in front of it.", "battle-pike-reception-furniture")
    for k in MONITOR:
        row(k, "wall monitor on the curtain", "changed", "Full-height curtain wall section with the framed board on its face, one board across "
            "the three cells.", "battle-pike-reception-furniture")
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write-amendments", action="store_true")
    args = ap.parse_args()
    table = json.loads(BASELINE.read_text(encoding="utf-8-sig"))
    art = EmeraldArt(BUNDLE)
    if args.write_amendments:
        write_amendments(art)
    updates = generate(table, art)
    rows = ledger(table, art)
    assert len(rows) == len({r["key"] for r in rows}) == 55, len(rows)
    write_json(OUT / "updates.json", {"cells": updates, "buildings": {}})
    write_json(OUT / "ledger.json", rows)
    print(f"{len(updates)} changed, {sum(r['decision'] == 'retained' for r in rows)} retained -> {OUT}")


if __name__ == "__main__":
    main()
