"""MG9 G2D end to end, on the real engine: the Gen 2 export (gen1-bridge/lib/engine/gen2/export.lua) of the test
install's Crystal import, read by fake_minecraft as role builder (docs/specs/GEN2_BRIDGE_PLAN.md §6).

The driver (drivers/kb_crystal.lua, scene export, on a fresh empty test slot deleted after) starts the world in
driver mode, bakes a sample of maps with the engine's own World:bakeMapImage and prints a hash per distinct block
(KB_BAKE), then serves. The test asks for export.index, fetches every resource, and checks that every message passes
its schema (strict), every resource passes protocol/export/<kind>.schema.json, and every hash and size equals Python's
canonical JSON + SHA-256 of the data received. It then checks the counts against the inspection (PROGRESS.md G2-S8:
388 maps), that each surface area's layout agrees with every connection between its maps (but one loop of Crystal's
Kanto that doesn't close on a flat plane: it gives way between Celadon City and Route 7), that every tile a variant
names is on a page, that the swap sites are the story's (137 on the import G2C measured), and that every block the
engine baked looks exactly like the export's baked tiles for that block (pixels hashed on both sides; blocks with an
animated tile are left out on both). A counts-only report goes to local/reports/crystal_export_report.json.

Data safety: only kinds, ids (engine symbols), counts, sizes, times and hashes are printed or reported. Resources stay
in memory; assertion messages name keys, never values from the game.
"""

import asyncio
import base64
import hashlib
import json
import os
import struct
import time
import zlib
from collections import Counter

import pytest

from fake_minecraft import FakeMinecraft
from kb_protocol import load_protocol
from kb_protocol.jsonfmt import canonical
from run_gen1recomp import INSTALL, REPORTS, launch, preflight
from test_crystal import save_files

TEST_PORT = 27223
GAME = "crystal"
INDEX_TIMEOUT = int(os.environ.get("KB_EXPORT_INDEX_TIMEOUT", "900"))
KINDS = {"g2map", "g2tiles", "g2image", "g2anims", "g2sprite", "g2layout", "g2encounters", "g2names", "swaps"}
OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east"}


def _skip_without_crystal():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    from run_gen1recomp import kb_games
    if GAME not in kb_games.detect(INSTALL):
        pytest.skip("no Crystal import in the test install")


def png_rgba(b64):
    """A PNG's (width, height, RGBA bytes): non-interlaced, any row filter, any colour type (LÖVE's encoder writes a
    palette image when the colours fit one), bit depths 1-8."""
    raw = base64.b64decode(b64)
    w, h, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", raw[16:29])
    assert interlace == 0 and depth in (1, 2, 4, 8) and colour in (0, 2, 3, 4, 6), "a plain PNG"
    at, data, palette, trns = 8, b"", [], b""
    while at < len(raw):
        n = struct.unpack(">I", raw[at:at + 4])[0]
        tag, body = raw[at + 4:at + 8], raw[at + 8:at + 8 + n]
        if tag == b"IDAT":
            data += body
        elif tag == b"PLTE":
            palette = [body[i:i + 3] for i in range(0, len(body), 3)]
        elif tag == b"tRNS":
            trns = body
        at += 12 + n
    rows = zlib.decompress(data)
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[colour]
    bits = channels * depth
    stride, bpp = (w * bits + 7) // 8, max(1, bits // 8)
    out = bytearray()
    prev = bytearray(stride)
    for y in range(h):
        f = rows[y * (stride + 1)]
        line = bytearray(rows[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 1:
                line[i] = (line[i] + a) & 0xff
            elif f == 2:
                line[i] = (line[i] + b) & 0xff
            elif f == 3:
                line[i] = (line[i] + (a + b) // 2) & 0xff
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xff
        prev = line
        if depth == 8:
            samples = line
        else:
            per = 8 // depth
            samples = [(line[x // per] >> (8 - depth * (x % per + 1))) & ((1 << depth) - 1) for x in range(w * channels)]
        for x in range(w):
            if colour == 6:
                out += bytes(samples[x * 4:x * 4 + 4])
            elif colour == 2:
                out += bytes(samples[x * 3:x * 3 + 3]) + b"\xff"
            elif colour == 3:
                k = samples[x]
                alpha = trns[k] if k < len(trns) else 255
                out += palette[k] + bytes((alpha,))
            elif colour == 4:
                g = samples[x * 2]
                out += bytes((g, g, g, samples[x * 2 + 1]))
            else:
                g = samples[x] * 255 // ((1 << depth) - 1)
                out += bytes((g, g, g, 255))
    return w, h, bytes(out)


async def _export(port):
    protocol = load_protocol()
    builder = FakeMinecraft(port=port, role="builder", ping_ms=1000)
    await builder.connect(attempts=30)
    ok = (await builder.hello())["data"]
    assert (ok["game"], ok["family"]) == (GAME, "gen2"), ok
    await builder.expect("snapshot", timeout=15)
    started = time.monotonic()
    reply = await builder.request("export.index", {}, reply_type="export.index", timeout=INDEX_TIMEOUT)
    index_seconds = time.monotonic() - started
    index = reply["data"]
    assert index["game"] == GAME
    entries = index["resources"]
    assert {e["kind"] for e in entries} == KINDS | {"routes"}, sorted({e["kind"] for e in entries})
    fetch_started = time.monotonic()
    want = {}
    for e in entries:
        message = await builder.send("export.get", {"kind": e["kind"], "id": e["id"]})
        want[message["seq"]] = e
    pages = {}
    while len(pages) < len(want):
        message = await builder.next(timeout=180)
        assert message is not None, "the connection ended during the fetch"
        if message.get("re") not in want:
            continue
        assert message["type"] == "export.data", message["type"]
        e = want[message["re"]]
        assert (message["data"]["kind"], message["data"]["id"], message["data"]["hash"]) == (e["kind"], e["id"], e["hash"])
        pages[(e["kind"], e["id"])] = message["data"]["data"]
    fetch_seconds = time.monotonic() - fetch_started
    assert not builder.conn.problems, "every message passed its schema (strict)"
    problems, mismatches = {}, []
    for e in entries:
        key = (e["kind"], e["id"])
        found = protocol.check_export(e["kind"], pages[key])
        if found:
            problems[f"{key[0]}:{key[1]}"] = sorted({str(p) for p in found})[:5]
        raw = canonical(pages[key]).encode("utf-8")
        if len(raw) != e["size"] or hashlib.sha256(raw).hexdigest() != e["hash"]:
            mismatches.append(f"{key[0]}:{key[1]}")
    assert not problems, problems
    assert not mismatches, mismatches[:5]
    await builder.close()
    tester = FakeMinecraft(port=port, role="test", ping_ms=1000)
    await tester.connect(attempts=20)
    await tester.hello()
    await tester.expect("snapshot", timeout=15)
    await tester.send("debug.quit", {"code": 0})
    await tester.close()
    return {"index": index, "pages": pages, "index_seconds": index_seconds, "fetch_seconds": fetch_seconds}


def _layout_check(maps, layout):
    placed = layout["maps"]
    agree = disagree = overlaps = 0
    bad = []
    for mid, box in placed.items():
        for c in maps[mid]["connections"]:
            if c["map"] not in placed:
                continue
            other = placed[c["map"]]
            want = {"north": (box["x"] + c["offset"], box["y"] - other["h"]),
                    "south": (box["x"] + c["offset"], box["y"] + box["h"]),
                    "west": (box["x"] - other["w"], box["y"] + c["offset"]),
                    "east": (box["x"] + box["w"], box["y"] + c["offset"])}[c["dir"]]
            if want == (other["x"], other["y"]):
                agree += 1
            else:
                disagree += 1
                bad.append(f"{mid}>{c['map']}:{c['dir']}")
    ids = sorted(placed)
    for i, ai in enumerate(ids):
        a = placed[ai]
        for bi in ids[i + 1:]:
            b = placed[bi]
            if a["x"] < b["x"] + b["w"] and b["x"] < a["x"] + a["w"] and a["y"] < b["y"] + b["h"] and b["y"] < a["y"] + a["h"]:
                overlaps += 1
    return {"maps": len(placed), "agree": agree, "disagree": disagree, "overlaps": overlaps, "disagreeing": bad[:10]}


def _bake_check(pages, lines):
    """Every block the engine baked (KB_BAKE) against the export's tiles for that block id in the map's variant."""
    maps = {i: d for (k, i), d in pages.items() if k == "g2map"}
    tiles = {i: d for (k, i), d in pages.items() if k == "g2tiles"}
    animated = {a["q"] for a in next(d for (k, _), d in pages.items() if k == "g2anims")["anims"]}
    decoded = {}

    def page(n):
        if n not in decoded:
            w, h, rgba = png_rgba(pages[("g2image", f"g2q.{n}")]["png"])
            assert (w, h) == (256, 256)
            decoded[n] = rgba
        return decoded[n]

    def block_hash(ids16):
        rows = []
        for r in range(32):
            parts = []
            for c in range(4):
                tid = ids16[(r // 8) * 4 + c]
                rgba = page(tid // 1024)
                q = tid % 1024
                px, py = (q % 32) * 8, (q // 32) * 8 + r % 8
                parts.append(rgba[(py * 256 + px) * 4:(py * 256 + px) * 4 + 32])
            rows.append(b"".join(parts))
        return hashlib.md5(b"".join(rows)).hexdigest()[:8]

    compared = same = skipped_both = 0
    differing = Counter()
    for line in lines:
        if not line.startswith("KB_BAKE "):
            continue
        facts = dict(kv.split("=", 1) for kv in line.split()[1:])
        mid = "G2_" + facts["map"]
        if facts["ids"] in ("-", "failed") or mid not in maps:
            continue
        m = maps[mid]
        assert facts["daytime"] == m["daytime"], (mid, facts["daytime"], m["daytime"])
        variant = tiles[m["variant"]]
        for item in facts["ids"].split("+"):
            block, want = item.split(":")
            ids16 = variant["blocks"][int(block)]
            if animated & set(ids16):
                skipped_both += 1
                continue
            compared += 1
            if block_hash(ids16) == want:
                same += 1
            else:
                differing[mid] += 1
    return {"compared": compared, "same": same, "skipped": skipped_both, "differing": dict(differing.most_common(10))}


def _report(got, lines):
    index, pages = got["index"], got["pages"]
    counts = Counter(e["kind"] for e in index["resources"])
    sizes = Counter()
    for e in index["resources"]:
        sizes[e["kind"]] += e["size"]
    maps = {i: d for (k, i), d in pages.items() if k == "g2map"}
    tiles = {i: d for (k, i), d in pages.items() if k == "g2tiles"}
    layouts = {i: d for (k, i), d in pages.items() if k == "g2layout"}
    page_ids = sorted(int(i.split(".")[1]) for (k, i) in pages if k == "g2image")
    named = {q for t in tiles.values() for b in t["blocks"] for q in b}
    anims = next(d for (k, _), d in pages.items() if k == "g2anims")["anims"]
    encounters = next(d for (k, _), d in pages.items() if k == "g2encounters")
    names = next(d for (k, _), d in pages.items() if k == "g2names")
    sites = next(d for (k, _), d in pages.items() if k == "swaps")["sites"]
    return {
        "counts": dict(counts), "bytes": dict(sizes), "total_bytes": sum(sizes.values()),
        "largest": max(e["size"] for e in index["resources"]),
        "index_seconds": round(got["index_seconds"], 1), "fetch_seconds": round(got["fetch_seconds"], 1),
        "maps": len(maps), "areas": {a: _layout_check(maps, lay) for a, lay in layouts.items()},
        "map_areas": dict(Counter(m["area"] for m in maps.values())),
        "dark_maps": sum(1 for m in maps.values() if m.get("dark")),
        "daytimes": dict(Counter(m["daytime"] for m in maps.values())),
        "variants": len(tiles), "variants_with_roof": sum(1 for t in tiles.values() if t.get("roof")),
        "tiles_named": len(named), "pages": len(page_ids),
        "tiles_on_pages": all(q // 1024 in page_ids for q in named),
        "animated": len(anims), "animated_in_tiles": len({a["q"] for a in anims} & named),
        "anim_frames_on_pages": all(f // 1024 in page_ids for a in anims for f in a["f"]),
        "anim_loop_lengths": dict(Counter(sum(s[1] for s in a["s"]) for a in anims)),
        "coll_bytes": len({c for t in tiles.values() for b in t["coll"] for c in b}),
        "objects": sum(len(m["objects"]) for m in maps.values()),
        "object_kinds": dict(Counter(o["kind"] for m in maps.values() for o in m["objects"])),
        "variable_objects": sum(1 for m in maps.values() for o in m["objects"] if "var" in o),
        "big_objects": sum(1 for m in maps.values() for o in m["objects"] if "big" in o),
        "warps": sum(len(m["warps"]) for m in maps.values()),
        "dynamic_warps": sum(1 for m in maps.values() for w in m["warps"] if w.get("dynamic")),
        "sprites": counts["g2sprite"],
        "encounters": {k: len(v) for k, v in encounters.items() if isinstance(v, (dict, list))},
        "names": {k: len(v) for k, v in names.items()},
        "sites": len(sites), "site_kinds": dict(Counter(s["kind"] for s in sites)),
        "bake": _bake_check(pages, lines),
    }


@pytest.mark.e2e
@pytest.mark.timeout(1800)
def test_crystal_export_counts_hashes_and_the_engines_own_art():
    _skip_without_crystal()
    before = save_files()
    env = {"KB_TEST_PORT": str(TEST_PORT), "KB_SCENE": "export", "KB_DRIVER_EXTRA": "1200"}
    with launch("kb_crystal.lua", game=GAME, extra_env=env) as running:
        assert running.wait_marker("KB_BOOTED", timeout=240) is not None, running.result.summary()
        ready = running.wait_marker("KB_EXPORT_READY", timeout=600)
        assert ready is not None, running.result.summary()
        got = asyncio.run(asyncio.wait_for(_export(TEST_PORT), INDEX_TIMEOUT + 600))
        result = running.wait(timeout=300)
    lines = result.lines
    report = _report(got, lines)
    report["ready"] = running.result.facts("KB_EXPORT_READY")
    report["log"] = [line for line in lines if "export.g2" in line or "story.sites" in line]
    os.makedirs(REPORTS, exist_ok=True)
    with open(os.path.join(REPORTS, "crystal_export_report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, sort_keys=True)
    print("crystal export:", json.dumps(report, sort_keys=True))
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_FAIL") is None and not result.crash_log_touched and not result.error_spots
    assert report["maps"] == 388 and report["tiles_on_pages"] and report["anim_frames_on_pages"]
    assert report["sites"] == 137, report["site_kinds"]
    # the surface areas place without overlaps, and every connection agrees but one loop of Crystal's own Kanto that
    # does not close on a flat plane (Celadon, Route 7, Saffron and the routes round them); placed in a fixed order,
    # it gives way at the same edge every time (Celadon City and Route 7, both ways), noted for G2E as Hoenn's three
    for area, check in report["areas"].items():
        assert check["maps"] > 1 and check["overlaps"] == 0, (area, check)
    assert report["areas"]["johto"]["disagree"] == 0, report["areas"]["johto"]
    assert sorted(report["areas"]["kanto"]["disagreeing"]) == [
        "G2_CELADON_CITY>G2_ROUTE_7:east", "G2_ROUTE_7>G2_CELADON_CITY:west"], report["areas"]["kanto"]
    bake = report["bake"]
    assert bake["compared"] > 200 and bake["same"] == bake["compared"], bake
    assert int(result.facts("KB_CLEANED")["deleted"]) == 1
    assert save_files() == before, "the test slot's files are gone and the rest untouched"
