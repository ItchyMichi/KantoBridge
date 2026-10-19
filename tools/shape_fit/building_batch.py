#!/usr/bin/env python3
"""Every building of a game's map review through building_model.py (docs/specs/HAND_MODELS.md section 7).

    python building_batch.py <game> <kb-hub data dir> <out dir> [--only MAP,MAP]

Buildings: the "make by hand" entities on outdoor maps (towns, cities, routes, islands, the plateau, the Safari Zone's areas) at least 3
x 2 cells whose note names no cave, rock, mound, tree or counter. One per design: entities with the same cell keys in
the same layout are one building (a Poké Center drawn in every town), made from its first map. Per building:
<out>/<game>/<id>.json (kb-hub's viewer), <id>_view.png and a line in <out>/<game>/buildings.json (id, map, box,
note, the placements of its design, what detect() found). A sheet per map, <out>/<game>/sheet_<map>.png.
Corrections: <data>/files/building_overrides_<game>.json, {"<map>:<x0>,<y0>": {"roof_rows": 16, "roof": "hip",
"bands": "7-15"}}, applied over detect().
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUTDOOR = re.compile(r"(_CITY|_TOWN|_ISLAND|^ROUTE_\d+|^INDIGO_PLATEAU|^SAFARI_ZONE_(EAST|WEST|NORTH|CENTER))$")
NOT_BUILDING = re.compile(r"\b(cave|rock|mound|tree|counter|partition|post)\b", re.I)


def buildings(game: str, data: Path, only: set[str]) -> list[dict]:
    flags = json.loads((data / "files" / f"flags_{game}.json").read_text(encoding="utf-8"))["_entities"]
    designs: dict[str, dict] = {}
    for map_id, ents in flags.items():
        if not OUTDOOR.search(map_id) or (only and map_id not in only):
            continue
        for e in ents:
            x0, y0, w, h = e["box"]
            if e["kind"] != "make by hand" or w < 3 or h < 2 or NOT_BUILDING.search(e.get("note", "")):
                continue
            layout = json.dumps(sorted(zip([(x - x0, y - y0) for x, y in e["cells"]], e["keys"])))
            d = designs.setdefault(layout, {"map": map_id, "box": e["box"], "note": e.get("note", ""), "placements": []})
            d["placements"].append([map_id, x0, y0])
    out = []
    for n, d in enumerate(designs.values()):
        d["id"] = f"{game}_b{n:03d}_{d['map']}"
        out.append(d)
    return out


def main() -> int:
    game, data, out = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
    only = set(sys.argv[sys.argv.index("--only") + 1].split(",")) if "--only" in sys.argv else set()
    dest = out / game
    dest.mkdir(parents=True, exist_ok=True)
    over_f = data / "files" / f"building_overrides_{game}.json"
    overrides = json.loads(over_f.read_text(encoding="utf-8")) if over_f.is_file() else {}
    listing = []
    for b in buildings(game, data, only):
        x0, y0, w, h = b["box"]
        maps = data / "images" / "maps" / game
        # a reviewer's corrections, by "<map>:<x0>,<y0>": roof_rows, roof (hip/flat), bands ("a-b,c-d")
        o = overrides.get(f"{b['map']}:{x0},{y0}", {})
        extra = []
        if "roof_rows" in o:
            extra += ["--roof-rows", str(o["roof_rows"])]
        if "roof" in o:
            extra += ["--roof", o["roof"]]
        if "bands" in o:
            extra += ["--bands", o["bands"]]
        b["override"] = o or None
        r = subprocess.run([sys.executable, str(HERE / "building_model.py"), str(maps / f"{b['map']}_art.png"),
                            str(maps / f"{b['map']}.json"), str(x0), str(y0), str(w), str(h), str(dest), "--name", b["id"], *extra],
                           capture_output=True, text=True)
        found = next((ln for ln in r.stdout.splitlines() if ln.startswith("detected:")), "")
        b["detected"] = found[len("detected: "):]
        b["ok"] = r.returncode == 0
        if not b["ok"]:
            b["error"] = (r.stderr.strip().splitlines() or ["?"])[-1]
        listing.append(b)
        print(b["id"], f"{w}x{h}", "ok" if b["ok"] else "FAILED " + b.get("error", ""), "|", b["detected"][:90], flush=True)
    (dest / "buildings.json").write_text(json.dumps(listing, indent=1), encoding="utf-8")
    sheets(dest, listing)
    print(len(listing), "buildings,", sum(1 for b in listing if b["ok"]), "made")
    return 0


def sheets(dest: Path, listing: list[dict], th: int = 300) -> None:
    from PIL import Image

    by_map: dict[str, list[str]] = {}
    for b in listing:
        if b["ok"]:
            by_map.setdefault(b["map"], []).append(b["id"])
    for map_id, ids in by_map.items():
        ims = [Image.open(dest / f"{i}_view.png") for i in ids]
        ims = [i.resize((max(1, int(i.width * th / i.height)), th)) for i in ims]
        per = 4
        rows = [ims[k:k + per] for k in range(0, len(ims), per)]
        sheet = Image.new("RGB", (max(sum(i.width + 10 for i in r) for r in rows) + 10, len(rows) * (th + 10) + 10), (40, 44, 52))
        y = 10
        for r in rows:
            x = 10
            for i in r:
                sheet.paste(i, (x, y))
                x += i.width + 10
            y += th + 10
        sheet.save(dest / f"sheet_{map_id}.png")


if __name__ == "__main__":
    raise SystemExit(main())
