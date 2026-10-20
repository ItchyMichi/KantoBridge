#!/usr/bin/env python3
"""Warp models (docs/specs/WARP_MODELS.md): a voxel shape per warp-cell drawing, by the kind and facing its review gave,
into a game's fitted table as a "walk" entry (stands on decor, no collision: the warp still triggers by position).

    python warp_models.py <game> <kinds.json> <designs.json> <sprites dir> <out.json> [--table] [--view <dir>]

<kinds.json> lists {"id", "kind", "facing"} per design (the agents' review), <designs.json> maps each id to its game and
key (kb-hub work/warps), <sprites dir> holds each key's 16 x 16 drawing (kb-hub images/sprites/<game>, "/" as "_").
Writes <out.json> ({key: {"v", "t"}}, base64: bits by (y * 16 + z) * 16 + x, least significant bit first; paint one byte a
voxel, the drawing's pixel row * 16 + column). With --table the entries also go into the game's table, marked "walk",
from "warp", replacing earlier warp entries and depth/entity shapes of the same drawing (on a walkable cell they
never showed), never a hand model or a seat; a drawing the game also draws on solid cells (kb-hub's map info beside the
sprites dir), or whose walkable cells are mostly not warps (plain floor), is left out. --view writes kb-hub viewer files.

Shapes, built facing south (the player comes from the south; the back is north) and turned to the review's facing:
  stairs_up     four steps rising to the back, 4 voxels deep and 4 more high each (to 16, two blocks); painted from above
  stairs_down,  a doorway at the back edge, 3 deep: jambs 2 wide, a lintel 3 high, a dark panel at the back (the
  wall_exit     drawing's darkest pixel); painted as seen from the front
  cave_mouth    an arch at the back edge, 3 deep, its opening a panel in the colour drawn in it (a glow, or dark)
  ladder_up     rails and rungs against the back edge, two blocks high, painted from the front
  ladder_down,  a rim 2 voxels high round the drawing's biggest dark patch (the hole), painted from above
  hole
Other kinds (building doors, mats, pads, plain floor) get no shape, nor do stairs drawn only outdoors (ground steps).
"""
from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

N = 16
REPO = Path(__file__).resolve().parents[2]
TURNS = {"south": 0, "east": 1, "north": 2, "west": 3}  # quarter turns from the south-facing build
TOP_PAINTED = {"stairs_up", "ladder_down", "hole"}  # painted from above: each voxel its own place's pixel, however turned


def shape(kind: str, draw: np.ndarray) -> tuple[np.ndarray, np.ndarray] | None:
    """Occupancy [y][z][x] and paint (row * 16 + col) of a kind, facing south, for a 16 x 16 x 3 drawing."""
    occ = np.zeros((N, N, N), bool)
    paint = np.zeros((N, N, N), np.uint8)
    lum = draw.astype(int).sum(axis=-1)
    top = np.add.outer(np.arange(N) * N, np.arange(N))  # [z][x]: the pixel seen from above
    front = lambda y, x: (N - 1 - y) * N + x  # noqa: E731  the pixel seen from the front
    if kind == "stairs_up":
        for k in range(4):
            z0, z1 = N - 4 * (k + 1), N - 4 * k
            occ[: 4 * (k + 1), z0:z1, :] = True
        for y in range(N):
            paint[y] = top
        return occ, paint
    if kind in ("stairs_down", "wall_exit", "cave_mouth", "ladder_up"):
        dark = int(np.argmin(lum.ravel()))
        # a cave mouth's opening: the colour drawn in it (a cave exit's white glow, a cave entrance's dark), the
        # middle's median pixel
        mid = lum[5:11, 5:11].ravel()
        glow = int(np.add.outer(np.arange(5, 11) * N, np.arange(5, 11)).ravel()[np.argsort(mid)[len(mid) // 2]])
        for y in range(N):
            for x in range(N):
                if kind == "ladder_up":
                    rail = x in (3, 4, 11, 12)
                    rung = 4 <= x <= 11 and y % 4 == 1
                    if rail or rung:
                        occ[y, 0:2, x] = True
                        paint[y, 0:2, x] = front(y, x)
                    continue
                if kind == "cave_mouth":
                    opening = ((x - 7.5) / 6.0) ** 2 + (y / 13.0) ** 2 <= 1.0
                else:
                    opening = 2 <= x <= 13 and y <= 12
                if opening:
                    occ[y, 0, x] = True  # the panel at the back: dark, or the cave's glow
                    paint[y, 0, x] = glow if kind == "cave_mouth" else dark
                else:
                    occ[y, 0:3, x] = True
                    paint[y, 0:3, x] = front(y, x)
        return occ, paint
    if kind in ("ladder_down", "hole"):
        # the hole: the biggest patch of pixels much darker than the drawing's middle value
        dark = lum < 0.6 * np.median(lum)
        hole = np.zeros_like(dark)
        seen = np.zeros_like(dark)
        for sz in range(N):
            for sx in range(N):
                if not dark[sz, sx] or seen[sz, sx]:
                    continue
                todo, comp = [(sz, sx)], []
                seen[sz, sx] = True
                while todo:
                    z, x = todo.pop()
                    comp.append((z, x))
                    for nz, nx in ((z + 1, x), (z - 1, x), (z, x + 1), (z, x - 1)):
                        if 0 <= nz < N and 0 <= nx < N and dark[nz, nx] and not seen[nz, nx]:
                            seen[nz, nx] = True
                            todo.append((nz, nx))
                if len(comp) > hole.sum():
                    hole[:] = False
                    for z, x in comp:
                        hole[z, x] = True
        if hole.sum() < 12:
            return None
        grown = hole.copy()
        grown[1:, :] |= hole[:-1, :]
        grown[:-1, :] |= hole[1:, :]
        grown[:, 1:] |= hole[:, :-1]
        grown[:, :-1] |= hole[:, 1:]
        rim = grown & ~hole
        if not rim.any():
            return None
        occ[0:2][:, rim] = True
        for y in range(2):
            paint[y] = top
        return occ, paint
    return None


def turn(occ: np.ndarray, paint: np.ndarray, quarter: int) -> tuple[np.ndarray, np.ndarray]:
    """The shape turned by quarter turns about the vertical (each turn moves its back from north to west); the paint turns with it."""
    for _ in range(quarter):
        occ = np.rot90(occ, k=1, axes=(1, 2))
        paint = np.rot90(paint, k=1, axes=(1, 2))
    return np.ascontiguousarray(occ), np.ascontiguousarray(paint)


def main() -> int:
    game, kinds_f, designs_f, sprites, out = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]), Path(sys.argv[5])
    designs = {d["id"]: d for d in json.loads(designs_f.read_text(encoding="utf-8"))}
    made: dict[str, dict] = {}
    count: dict[str, int] = {}
    for k in json.loads(kinds_f.read_text(encoding="utf-8")):
        d = designs.get(k["id"])
        if d is None or d["game"] != game:
            continue
        sprite = sprites / (d["key"].replace("/", "_") + ".png")
        if not sprite.is_file():
            continue
        draw = np.asarray(Image.open(sprite).convert("RGB"))
        if k["kind"].startswith("stairs") and all(e.get("surface") for e in d.get("examples", [])):
            continue  # outdoor steps are ground: they wait for the 3D world's heights (HEIGHTS_ALL_GAMES_PLAN.md)
        s = shape(k["kind"], draw)
        if s is None:
            continue
        occ, paint = turn(*s, TURNS.get(k.get("facing", "south"), 0))
        if k["kind"] in TOP_PAINTED:
            paint = np.broadcast_to(np.add.outer(np.arange(N) * N, np.arange(N)), (N, N, N)).astype(np.uint8).copy()
        bits = np.packbits(occ.ravel().astype(np.uint8), bitorder="little").tobytes()
        made[d["key"]] = {"v": base64.b64encode(bits).decode(), "t": base64.b64encode(paint.ravel().tobytes()).decode(), "kind": k["kind"]}
        count[k["kind"]] = count.get(k["kind"], 0) + 1
    out.write_text(json.dumps(made), encoding="utf-8")
    print(game, len(made), "warp shapes", count)
    if "--table" in sys.argv:
        table_file = REPO / "fabric-bridge/src/main/resources/kantobridge/fitted_shapes" / f"{game}.json"
        table = json.loads(table_file.read_text(encoding="utf-8"))
        cells = table.setdefault("cells", {})
        for key in [key for key, e in cells.items() if e.get("from") == "warp"]:
            del cells[key]
        # a drawing the game also uses on solid cells (a black void, a wall) would put its warp shape there: left out
        # and a drawing mostly drawn on ordinary floor (an invisible warp on plain cave floor) would raise that floor:
        # only drawings whose walkable cells are at least half warp cells
        solid: set[str] = set()
        walk: dict[str, int] = {}
        maps = sprites.parent.parent / "maps" / game
        for f in maps.glob("*.json"):
            info = json.loads(f.read_text(encoding="utf-8"))
            if "keys" in info:
                for c, k in zip(info["cell"], info["kind"]):
                    key = info["keys"][c]
                    if k == "W":
                        walk[key] = walk.get(key, 0) + 1
                    else:
                        solid.add(key)
        warps = {d["key"]: d["count"] for d in designs.values() if d["game"] == game}
        put = kept = shared = 0
        for key, e in made.items():
            if key in solid or warps.get(key, 0) * 2 < walk.get(key, 0):
                shared += 1
                continue
            if key in cells and cells[key].get("from") not in ("entity", "edited", "depth"):
                kept += 1  # a hand model or a seat stays
                continue
            cells[key] = {"class": "voxels", "v": e["v"], "t": e["t"], "walk": True, "from": "warp"}
            put += 1
        table_file.write_text(json.dumps(table, indent=2) + "\n", encoding="utf-8")
        print(f"{game}: {put} warp shapes in the table; {shared} drawings also on solid cells or mostly on plain floor and {kept} with their own entries left out")
    if "--view" in sys.argv:
        view = Path(sys.argv[sys.argv.index("--view") + 1])
        view.mkdir(parents=True, exist_ok=True)
        for key, e in made.items():
            occ = np.unpackbits(np.frombuffer(base64.b64decode(e["v"]), np.uint8), bitorder="little")[: N ** 3].astype(bool)
            paint = np.frombuffer(base64.b64decode(e["t"]), np.uint8)
            draw = np.asarray(Image.open(sprites / (key.replace("/", "_") + ".png")).convert("RGB")).reshape(-1, 3)
            cols = draw[paint]
            pal, idx = np.unique(cols, axis=0, return_inverse=True)
            pal = pal[:64]
            shade = np.where(occ, np.minimum(idx.ravel(), 63), 255).astype(np.uint8)
            doc = {"title": f"warp {e['kind']}: {key}", "dims": [N, N, N],
                   "voxels": base64.b64encode(np.packbits(occ.astype(np.uint8), bitorder="little").tobytes()).decode(),
                   "shades": base64.b64encode(shade.tobytes()).decode(),
                   "palette": ["#%02x%02x%02x" % tuple(int(v) for v in c) for c in pal] + ["#000000"] * (64 - len(pal)),
                   "voxelsPerBlock": 8}
            (view / f"wp_{game}_{key.replace('/', '_')}.json").write_text(json.dumps(doc), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
