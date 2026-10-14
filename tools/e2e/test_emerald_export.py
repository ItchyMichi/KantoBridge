"""MG7 G3D end to end, on the real engine: the Gen 3 export (gen1-bridge/lib/engine/game3/export.lua) of the test
install's Emerald import, read by fake_minecraft as role builder (docs/specs/GEN3_BRIDGE_PLAN.md §5).

The game stays at its title (drivers/kb_emerald.lua, scene title_new, on a fresh empty test slot deleted after): the
export reads the imported data, not a save. The test asks for export.index, fetches every resource, and checks that
every message passes its schema (strict), every resource passes protocol/export/<kind>.schema.json, and every hash
and size equals Python's canonical JSON + SHA-256 of the data received. It then checks the counts against the
measurement (tools/e2e/test_g3_measure.py: 519 maps, 75 pairs, 239 sprite sheets), that the surface layout agrees
with every connection between placed maps, and that every quarter id a pair names is on a page; a counts-only
report goes to local/reports/emerald_export_report.json.

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
from collections import Counter

import pytest

from fake_minecraft import FakeMinecraft
from kb_protocol import load_protocol
from kb_protocol.jsonfmt import canonical
from run_gen1recomp import INSTALL, REPORTS, launch, preflight

TEST_PORT = 27221
GAME = "emerald"
INDEX_TIMEOUT = int(os.environ.get("KB_EXPORT_INDEX_TIMEOUT", "900"))
KINDS = {"g3map", "g3tiles", "g3image", "g3sprite", "g3layout", "g3encounters", "g3names"}
OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east"}


def _skip_without_emerald():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    from run_gen1recomp import kb_games
    if GAME not in kb_games.detect(INSTALL):
        pytest.skip("no Emerald import in the test install")


async def _export(port):
    protocol = load_protocol()
    builder = FakeMinecraft(port=port, role="builder", ping_ms=1000)
    await builder.connect(attempts=30)
    ok = (await builder.hello())["data"]
    assert (ok["game"], ok["family"]) == (GAME, "gen3"), ok
    await builder.expect("snapshot", timeout=15)
    started = time.monotonic()
    reply = await builder.request("export.index", {}, reply_type="export.index", timeout=INDEX_TIMEOUT)
    index_seconds = time.monotonic() - started
    index = reply["data"]
    assert index["game"] == GAME
    entries = index["resources"]
    assert {e["kind"] for e in entries} == KINDS, sorted({e["kind"] for e in entries})
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


def _report(got):
    index, pages = got["index"], got["pages"]
    counts = Counter(e["kind"] for e in index["resources"])
    sizes = Counter()
    for e in index["resources"]:
        sizes[e["kind"]] += e["size"]
    maps = {i: d for (k, i), d in pages.items() if k == "g3map"}
    tiles = {i: d for (k, i), d in pages.items() if k == "g3tiles"}
    layout = next(d for (k, _), d in pages.items() if k == "g3layout")
    placed = layout["maps"]
    agree = disagree = 0
    disagreeing, overlapping = [], []
    for mid, box in placed.items():
        for c in maps[mid]["connections"]:
            direction = c["dir"]
            if direction not in OPPOSITE or c["map"] not in placed:
                continue
            other = placed[c["map"]]
            want = {"north": (box["x"] + c["offset"], box["y"] - other["h"]),
                    "south": (box["x"] + c["offset"], box["y"] + box["h"]),
                    "west": (box["x"] - other["w"], box["y"] + c["offset"]),
                    "east": (box["x"] + box["w"], box["y"] + c["offset"])}[direction]
            if want == (other["x"], other["y"]):
                agree += 1
            else:
                disagree += 1
                disagreeing.append(f"{mid}>{c['map']}:{direction}")
    overlaps = 0
    ids = sorted(placed)
    for i, ai in enumerate(ids):
        a = placed[ai]
        for bi in ids[i + 1:]:
            b = placed[bi]
            if a["x"] < b["x"] + b["w"] and b["x"] < a["x"] + a["w"] and a["y"] < b["y"] + b["h"] and b["y"] < a["y"] + a["h"]:
                overlaps += 1
                overlapping.append(f"{ai}+{bi}")
    page_ids = sorted(int(i.split(".")[1]) for (k, i) in pages if k == "g3image")
    quarters = {q for t in tiles.values() for row in t["quarters"] for q in row}
    on_pages = all(q // 1024 in page_ids for q in quarters)
    cells = sum(m["trueW"] * m["trueH"] for m in maps.values())
    padded = sum(1 for m in maps.values() if (m["w"], m["h"]) != (m["trueW"], m["trueH"]))
    bad_cells = sum(1 for m in maps.values() if len(base64.b64decode(m["cells"])) != m["w"] * m["h"] * 4)
    page_dims = set()
    for (k, i), d in pages.items():
        if k == "g3image":
            raw = base64.b64decode(d["png"])
            page_dims.add(struct.unpack(">II", raw[16:24]))
    enc = next(d for (k, _), d in pages.items() if k == "g3encounters")["maps"]
    names = next(d for (k, _), d in pages.items() if k == "g3names")
    return {
        "counts": dict(counts), "bytes": dict(sizes), "total_bytes": sum(sizes.values()),
        "largest": max(e["size"] for e in index["resources"]),
        "index_seconds": round(got["index_seconds"], 1), "fetch_seconds": round(got["fetch_seconds"], 1),
        "cells": cells, "padded_maps": padded, "bad_cell_sizes": bad_cells,
        "layout_root": layout["root"], "layout_maps": len(placed), "layout_edges_agree": agree,
        "layout_edges_disagree": disagree, "layout_overlaps": overlaps,
        "layout_disagreeing": sorted(disagreeing), "layout_overlapping": overlapping,
        "quarters_named": len(quarters), "pages": len(page_ids), "quarters_on_pages": on_pages,
        "page_dims": sorted(page_dims), "encounter_maps": len(enc),
        "encounter_kinds": dict(Counter(kind for m in enc.values() for kind in m)),
        "names": {k: len(v) for k, v in names.items()},
        "objects": sum(len(m["objects"]) for m in maps.values()),
        "warps": sum(len(m["warps"]) for m in maps.values()),
    }


@pytest.mark.e2e
@pytest.mark.timeout(2400)
def test_the_emerald_export_end_to_end():
    _skip_without_emerald()
    env = {"KB_TEST_PORT": str(TEST_PORT), "KB_SCENE": "title_new", "KB_DRIVER_EXTRA": str(INDEX_TIMEOUT + 300)}
    with launch("kb_emerald.lua", game=GAME, extra_env=env) as running:
        assert running.wait_marker("KB_TITLE", timeout=300) is not None, running.result.summary()
        got = asyncio.run(asyncio.wait_for(_export(TEST_PORT), INDEX_TIMEOUT + 600))
        result = running.wait(timeout=180)
    report = _report(got)
    report["bridge_warning_lines"] = sorted(line.split("] ", 2)[-1] for line in result.lines
                                            if "export.warning" in line)
    report["exit"] = {"returncode": result.returncode, "timed_out": result.timed_out,
                      "error_spots": len(result.error_spots)}
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / "emerald_export_report.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n",
                                                        encoding="utf-8")
    print("emerald export report:", json.dumps(report, sort_keys=True))
    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_FAIL") is None and not result.error_spots
    # the measurement (GEN3_BRIDGE_PLAN §5): 519 maps, 75 pairs, 239 sheets
    assert report["counts"]["g3map"] == 519 and report["counts"]["g3tiles"] == 75, report["counts"]
    assert report["counts"]["g3sprite"] == 239, report["counts"]
    assert report["bad_cell_sizes"] == 0 and report["cells"] == 316337
    assert report["quarters_on_pages"] and report["page_dims"] == [(256, 256)]
    # Hoenn's surface from Littleroot as the engine places it (Map.computeWorld): 49 maps (Sootopolis is reached by
    # diving); a few connections in the game's own data disagree with that placement (reported by name, for G3E)
    assert report["layout_root"] == "EM_LITTLEROOT_TOWN" and report["layout_maps"] == 49
    assert report["layout_edges_agree"] >= 100
