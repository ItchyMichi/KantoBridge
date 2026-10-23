#!/usr/bin/env python3
"""Every building of a game's map review through building_model.py (docs/specs/HAND_MODELS.md section 7).

    python building_batch.py <game> <kb-hub data dir> <out dir> [--only MAP,MAP] [--outdoor DIR] [--add]

Buildings: the "make by hand" entities on outdoor maps at least 3 x 2 cells whose note names no cave, rock, mound,
tree, counter, partition, post, fence, flower bed or planter. Outdoor maps: with --outdoor, the maps
of an OutdoorLevelsDumpTest dump made with "all" (one <MAP>.json each: every map on the surface or under the open
sky, the game's own choice); without it, by name (towns, cities, routes, islands, the plateau, the Safari Zone's
areas, ports, a map's outside or exterior, the National Park). One per design: entities with the same cell keys in
the same layout are one building (a Poké Center drawn in every town), made from its first map. Per building:
<out>/<game>/<id>.json (kb-hub's viewer), <id>_view.png and a line in <out>/<game>/buildings.json (id, map, box,
note, the placements of its design, what detect() found). A sheet per map, <out>/<game>/sheet_<map>.png.
Corrections: <data>/files/building_overrides_<game>.json, {"<map>:<x0>,<y0>": {"terrain": [[col, row], ...] (cells of the
box that are terrain: the box shrinks to the rest), "roof_rows": 16, "roof": "hip",
"bands": "7-15"}}, applied over detect(); "building": true takes an entity the note's words would leave out (the
National Park's flower bed, the Battle Dome), false leaves one out.
--add keeps an earlier run's <out>/<game>/buildings.json: its designs keep their ids and models, new designs are
numbered after them (and made), and an entity drawn like an earlier design is one more of its placements.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUTDOOR = re.compile(r"(_CITY|_TOWN|_ISLAND|^((G2|EM)_)?ROUTE_?\d+|INDIGO_PLATEAU|SAFARI_ZONE_[A-Z]+|_OUTSIDE(_[A-Z]+)?|_EXTERIOR|_PORT"
                     r"|NATIONAL_PARK(_BUG_CONTEST)?)$")
NOT_BUILDING = re.compile(r"\b(cave|rock|mound|tree|counter|partition|post|fence|flower bed|planter)\b", re.I)


def outdoor_maps(dump: Path | None) -> set[str] | None:
    """The outdoor maps of an OutdoorLevelsDumpTest dump ("all": on the surface or under the open sky), or None."""
    if dump is None:
        return None
    return {f.stem for f in dump.glob("*.json") if not f.stem.endswith(("_mids", "_elev"))}


def layout_of(e: dict) -> str:
    x0, y0 = e["box"][0], e["box"][1]
    return json.dumps(sorted(zip([(x - x0, y - y0) for x, y in e["cells"]], e["keys"])))


def buildings(game: str, data: Path, only: set[str], outdoor: set[str] | None = None, overrides: dict | None = None,
              earlier: list[dict] | None = None) -> list[dict]:
    """The designs, new ones only when there are earlier ones (`earlier`, an earlier listing: kept as they are, an entity
    drawn like one of them added to its placements)."""
    flags = json.loads((data / "files" / f"flags_{game}.json").read_text(encoding="utf-8"))["_entities"]
    overrides = overrides or {}
    known: dict[str, dict] = {}
    for d in earlier or []:
        for p in d.get("placements", []):
            # the entity at the placement (its box's corner; a box shrunk by "terrain" lies inside it)
            e = next((e for e in flags.get(p[0], []) if e["kind"] == "make by hand"
                      and e["box"][0] <= p[1] < e["box"][0] + e["box"][2] and e["box"][1] <= p[2] < e["box"][1] + e["box"][3]), None)
            if e is not None:
                known.setdefault(layout_of(e), d)
    designs: dict[str, dict] = {}
    for map_id, ents in flags.items():
        if not (map_id in outdoor if outdoor is not None else OUTDOOR.search(map_id)) or (only and map_id not in only):
            continue
        for e in ents:
            x0, y0, w, h = e["box"]
            if e["kind"] != "make by hand" or w < 3 or h < 2:
                continue
            forced = overrides.get(f"{map_id}:{x0},{y0}", {}).get("building")
            if forced is False or (forced is not True and NOT_BUILDING.search(e.get("note", ""))):
                continue
            layout = layout_of(e)
            if layout in known:
                if [map_id, x0, y0] not in known[layout]["placements"]:
                    known[layout]["placements"].append([map_id, x0, y0])
                continue
            d = designs.setdefault(layout, {"map": map_id, "box": e["box"], "note": e.get("note", ""), "placements": []})
            d["placements"].append([map_id, x0, y0])
    first = 1 + max((int(re.match(r".*?_b(\d+)_", d["id"]).group(1)) for d in earlier or []), default=-1)
    out = []
    for n, d in enumerate(designs.values(), start=first):
        d["id"] = f"{game}_b{n:03d}_{d['map']}"
        out.append(d)
    return out


def main() -> int:
    game, data, out = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
    only = set(sys.argv[sys.argv.index("--only") + 1].split(",")) if "--only" in sys.argv else set()
    outdoor = outdoor_maps(Path(sys.argv[sys.argv.index("--outdoor") + 1])) if "--outdoor" in sys.argv else None
    dest = out / game
    dest.mkdir(parents=True, exist_ok=True)
    over_f = data / "files" / f"building_overrides_{game}.json"
    overrides = json.loads(over_f.read_text(encoding="utf-8")) if over_f.is_file() else {}
    # --add: an earlier run's designs stay as they are (ids, models); only new designs are made
    earlier = json.loads((dest / "buildings.json").read_text(encoding="utf-8")) if "--add" in sys.argv and (dest / "buildings.json").is_file() else []
    listing = list(earlier)
    for b in buildings(game, data, only, outdoor, overrides, earlier):
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
        if o.get("terrain"):
            # cells drawn with the building that are terrain (ledges, rock bases, ground): the model and the game's
            # claim take only the box round the rest, so those cells stay the map's own floor
            ts = {(c[0], c[1]) for c in o["terrain"]}
            keep = [(cx, cy) for cy in range(h) for cx in range(w) if (cx, cy) not in ts]
            if keep:
                kx0, kx1 = min(c[0] for c in keep), max(c[0] for c in keep)
                ky0, ky1 = min(c[1] for c in keep), max(c[1] for c in keep)
                x0, y0, w, h = x0 + kx0, y0 + ky0, kx1 - kx0 + 1, ky1 - ky0 + 1
                b["box"] = [x0, y0, w, h]
                b["placements"] = [[p[0], p[1] + kx0, p[2] + ky0] for p in b.get("placements", [])]
        b["override"] = o or None
        r = subprocess.run([sys.executable, str(HERE / "building_model.py"), str(maps / f"{b['map']}_art.png"),
                            str(maps / f"{b['map']}.json"), str(x0), str(y0), str(w), str(h), str(dest), "--name", b["id"], *extra],
                           capture_output=True, text=True)
        found = next((ln for ln in r.stdout.splitlines() if ln.startswith("detected:")), "")
        b["detected"] = found[len("detected: "):]
        # what the model used (detected, or the reviewer's override): the numbers the game's BoxBuilding takes
        m = re.search(r"using roof rows (\d+) roof (\w+) bands (\S+)", found)
        if m:
            b["roofRows"], b["roof"] = int(m.group(1)), m.group(2)
            b["bands"] = [[int(v) for v in pair.split("-")] for pair in m.group(3).split(",") if pair != "-"]
        b["ok"] = r.returncode == 0
        if not b["ok"]:
            b["error"] = (r.stderr.strip().splitlines() or ["?"])[-1]
        listing.append(b)
        print(b["id"], f"{w}x{h}", "ok" if b["ok"] else "FAILED " + b.get("error", ""), "|", b["detected"][:90], flush=True)
    (dest / "buildings.json").write_text(json.dumps(listing, indent=1), encoding="utf-8")
    sheets(dest, [b for b in listing if (dest / f"{b['id']}_view.png").is_file()])
    print(len(listing), "buildings,", sum(1 for b in listing if b["ok"]), "made,", len(listing) - len(earlier), "new")
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
