#!/usr/bin/env python3
"""The seams between maps (docs/specs/LEVELS_PIPELINE.md, open item 1): where two outdoor maps meet in the world, how far
apart their grounds are, and what is left if each map gets a base height of its own, chained from map to map so each
starts where its neighbour's ground leaves off (the user's wish, 2026-10-21).

    python seams.py <dump dir> <game> [--json <out.json>]

<dump dir>: OutdoorLevelsDumpTest's dump with "all" (-Pkb.outdoorDump="<game>;<bundle>;<dir>;all"): placements_<game>.json
(each outdoor map's place in the world, in blocks) and each map's <MAP>.json (w, kinds, levels in blocks over its floor).
Two maps meet where a cell of one touches a cell of the other across its edge, both walkable ground ('.', 'L') or both
water ('~'); a seam is every such pair of cells between two maps. Its step is the commonest difference of their levels.

The chain: the seams with the most cell pairs first (the most seen), each joining two groups of maps not yet joined
fixes the second map's base so their commonest cells meet (a spanning forest); a seam left over closes a loop, and its
step after the chain is what the loop doesn't close. The bases are then lifted so the lowest floor stands on the surface.
Prints a report; --json writes the seams and bases (map ids and numbers only). Plain Python.
"""
import json
import os
import sys
from collections import Counter, defaultdict

WALK = ".L"


def load(dump_dir, game):
    """{map: {x, y, z, w, h, cs (blocks a cell), sky, kinds, levels, outcome, skipped, breaks}}."""
    maps = {}
    for p in json.load(open(os.path.join(dump_dir, f"placements_{game}.json"))):
        d = json.load(open(os.path.join(dump_dir, p["map"] + ".json")))
        cs = p["bw"] // p["w"]
        if cs * p["w"] != p["bw"] or cs * p["h"] != p["bd"]:
            raise ValueError(f"{p['map']}: {p['bw']} x {p['bd']} blocks for {p['w']} x {p['h']} cells")
        maps[p["map"]] = {**p, "cs": cs, "kinds": d["kinds"], "levels": d["levels"], "outcome": d.get("outcome"),
                          "skipped": d.get("skipped", 0), "breaks": len(d.get("breaks", [])),
                          "ramps": {(r[0], r[1]): r for r in d.get("ramps", [])}}
    return maps


def seams(maps):
    """{(a, b): [(diff, kind, a cell, b cell), ...]}, a before b by name: every pair of touching cells, both walkable or
    both water, diff b's level minus a's (blocks), kind 'walk' or 'water'."""
    index = defaultdict(list)  # 64-block squares -> the maps over them; the surface's maps one space, whatever their
    # chained heights; each open-sky layer its own

    def space(m):
        return ("sky", m["y"]) if m.get("sky") else "surface"

    for name, m in maps.items():
        for qx in range(m["x"] // 64, (m["x"] + m["bw"] - 1) // 64 + 1):
            for qz in range(m["z"] // 64, (m["z"] + m["bd"] - 1) // 64 + 1):
                index[(space(m), qx, qz)].append(name)

    def at(where, bx, bz):
        for name in index.get((where, bx // 64, bz // 64), ()):
            m = maps[name]
            if m["x"] <= bx < m["x"] + m["bw"] and m["z"] <= bz < m["z"] + m["bd"]:
                return name, (bx - m["x"]) // m["cs"], (bz - m["z"]) // m["cs"]
        return None

    found = {}
    for name, m in maps.items():
        w, h, cs = m["w"], m["h"], m["cs"]
        edges = [((w - 1, cy), (1, 0)) for cy in range(h)] + [((0, cy), (-1, 0)) for cy in range(h)] \
            + [((cx, h - 1), (0, 1)) for cx in range(w)] + [((cx, 0), (0, -1)) for cx in range(w)]
        for (cx, cy), (dx, dz) in edges:
            for i in range(cs):
                bx = m["x"] + cx * cs + (cs if dx > 0 else -1 if dx < 0 else i)
                bz = m["z"] + cy * cs + (cs if dz > 0 else -1 if dz < 0 else i)
                other = at(space(m), bx, bz)
                if other is None or other[0] == name:
                    continue
                key = tuple(sorted([(name, cx, cy), other]))
                # the way from the key's first cell to its second
                found[key] = (dx, dz) if key[0] == (name, cx, cy) else (-dx, -dz)
    out = defaultdict(list)
    for ((a, ax, ay), (b, bx, by)), (dx, dz) in found.items():
        ma, mb = maps[a], maps[b]
        ka, kb = ma["kinds"][ay * ma["w"] + ax], mb["kinds"][by * mb["w"] + bx]
        kind = "walk" if ka in WALK and kb in WALK else "water" if ka == "~" and kb == "~" else None
        if kind:
            diff = edge_level(mb, bx, by, (-dx, -dz)) - edge_level(ma, ax, ay, (dx, dz))
            out[(a, b)].append((diff, kind, (ax, ay), (bx, by)))
    return dict(out)


WAYS = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}


def edge_level(m, x, y, toward):
    """A cell's level at its edge the way toward goes: a step's top where it climbs that way (a join's stair, MapChain)."""
    r = m.get("ramps", {}).get((x, y))
    return m["levels"][y * m["w"] + x] + (r[3] if r and WAYS[r[2]] == toward else 0)


def step(pairs):
    """A seam's step: the commonest difference (on a tie the smallest), and how many pairs have it."""
    counts = Counter(d for d, _, _, _ in pairs)
    best = max(counts.items(), key=lambda kv: (kv[1], -abs(kv[0]), -kv[0]))
    return best[0], best[1]


def chain(maps, found):
    """(bases {map: blocks}, tree [(a, b)]): each group of joined maps chained from its seams with the most pairs; the bases
    lifted so each group's lowest floor stands at 0 (a map's levels count from its floor: no ground of it under 0)."""
    parent = {m: m for m in maps}

    def root(m):
        while parent[m] != m:
            parent[m] = parent[parent[m]]
            m = parent[m]
        return m

    tree = []
    for (a, b), pairs in sorted(found.items(), key=lambda kv: (-len(kv[1]), kv[0])):
        ra, rb = root(a), root(b)
        if ra != rb:
            parent[rb] = ra
            tree.append((a, b))
    links = defaultdict(list)
    for a, b in tree:
        s, _ = step(found[(a, b)])
        links[a].append((b, -s))  # a's ground meets b's: b's base = a's base - the step
        links[b].append((a, s))
    bases = {}
    for start in sorted(maps, key=lambda m: (-maps[m]["w"] * maps[m]["h"], m)):
        if start in bases:
            continue
        group, todo = [start], [start]
        bases[start] = 0
        while todo:
            m = todo.pop()
            for n, d in links[m]:
                if n not in bases:
                    bases[n] = bases[m] + d
                    group.append(n)
                    todo.append(n)
        low = min(bases[m] for m in group)
        for m in group:
            bases[m] -= low
    return bases, tree


def highest(m):
    return max((v for v, k in zip(m["levels"], m["kinds"]) if k != "#"), default=0)


def report(maps, found, bases, tree):
    """The seams with their steps today (every base 0) and after the chain."""
    rows = []
    in_tree = set(tree)
    for (a, b), pairs in sorted(found.items()):
        s, n = step(pairs)
        left = Counter(d + bases[b] - bases[a] for d, _, _, _ in pairs)
        rows.append({"a": a, "b": b, "pairs": len(pairs), "walk": sum(1 for p in pairs if p[1] == "walk"), "step": s,
                     "at_step": n, "today": dict(Counter(d for d, _, _, _ in pairs)), "chained": dict(left),
                     "loop": (a, b) not in in_tree})
    return rows


def main(argv):
    dump_dir, game = argv[1], argv[2]
    maps = load(dump_dir, game)
    found = seams(maps)
    bases, tree = chain(maps, found)
    rows = report(maps, found, bases, tree)
    touching = {m for ab in found for m in ab}
    print(f"{game}: {len(maps)} outdoor maps, {len(touching)} meeting another, {len(found)} seams, "
          f"{sum(len(p) for p in found.values())} cell pairs")
    stepped = [r for r in rows if r["step"] != 0]
    print(f"today (every map's floor on the surface): {len(stepped)} seams step at their commonest cells; "
          f"{sum(1 for r in rows if any(d for d in r['today']))} have a step somewhere")
    for r in sorted(stepped, key=lambda r: -abs(r["step"]))[:12]:
        print(f"  {r['a']} | {r['b']}: {r['step']:+d} blocks ({r['at_step']} of {r['pairs']} pairs)")
    loops = [r for r in rows if r["loop"]]
    open_loops = [r for r in loops if max(r["chained"].items(), key=lambda kv: kv[1])[0] != 0]
    print(f"chained: {len(tree)} seams set the bases; {len(loops)} close loops, {len(loops) - len(open_loops)} of them meet")
    for r in sorted(open_loops, key=lambda r: -abs(max(r["chained"].items(), key=lambda kv: kv[1])[0])):
        left = max(r["chained"].items(), key=lambda kv: kv[1])[0]
        print(f"  loop open at {r['a']} | {r['b']}: {left:+d} blocks ({r['pairs']} pairs)")
    ragged = [r for r in rows if not r["loop"] and r["at_step"] < r["pairs"]]
    print(f"seams whose cells don't all meet even chained: {len(ragged)}")
    for r in sorted(ragged, key=lambda r: r["at_step"] - r["pairs"])[:12]:
        off = {d: n for d, n in r["chained"].items() if d}
        print(f"  {r['a']} | {r['b']}: {r['pairs'] - r['at_step']} of {r['pairs']} pairs step {off}")
    tops = sorted(((bases[m] + highest(maps[m]), bases[m], m) for m in touching), reverse=True)
    print("highest ground after the chain (blocks over the surface; base):", [(m, t, b) for t, b, m in tops[:8]])
    print("bases:", {m: bases[m] for m in sorted(touching, key=lambda m: -bases[m])[:20]})
    if "--json" in argv:
        out = {"game": game, "bases": {m: bases[m] for m in sorted(maps)}, "seams": rows}
        with open(argv[argv.index("--json") + 1], "w") as fh:
            json.dump(out, fh, indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
