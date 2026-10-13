"""M2 end to end, on the real engine: gen1-bridge's world export in the Gen1Recomp test instance,
read by fake_minecraft as role builder (docs/PLAN.md M2 [L], docs/PROTOCOL.md "Export resources").

The game stays at the title screen: the export reads the imported data, not a save. The test asks
for export.index, fetches every resource, and checks that every message passes its schema (strict),
every resource passes protocol/export/<kind>.schema.json, and every hash and size equals Python's
canonical JSON + SHA-256 of the data received. It then checks counts against what Kanto has and
that the surface layout agrees with every connection, and writes a counts-only report to
local/reports/export_report.json.

Data safety (docs/PLAN.md section 0.3): only kinds, ids (engine symbols), counts, sizes, times and
hashes are printed or reported. Assertion messages name keys, never values from the game.
"""

import asyncio
import hashlib
import json
import os
import time
from collections import Counter

import pytest

from fake_minecraft import FakeMinecraft
from kb_protocol import load_protocol
from kb_protocol.jsonfmt import canonical
from run_gen1recomp import INSTALL, REPORTS, launch, preflight

TEST_PORT = 27197
# The index wait and the test's own limit; a slow host (the pod under Xvfb builds the export at a few steps a
# second) raises them through the environment (ENGINE_UPGRADE_PLAN G2), a PC keeps the defaults.
INDEX_TIMEOUT = int(os.environ.get("KB_EXPORT_INDEX_TIMEOUT", "240"))
TEST_TIMEOUT = int(os.environ.get("KB_EXPORT_TEST_TIMEOUT", "600"))
PAGE_LIMIT = 1048576
KINDS = ("encounters", "field", "image", "layout", "map", "names", "palettes", "sprite", "swaps", "tileset")
# The profile plan (docs/specs/PROFILE_PLAN.md §5.8): with Dramaless Shape installed in the test install
# (tools/e2e/install_profile_mod.py), the export carries its table as profile/dramaless.
PROFILE_MOD = INSTALL / "mods" / "DRAMALESS_SHAPE"


def _kinds():
    return set(KINDS) | ({"profile"} if (PROFILE_MOD / "data" / "voxel_heights.lua").is_file() else set())
ROOT = "PALLET_TOWN"


OPPOSITE = {"north": "south", "south": "north", "west": "east", "east": "west"}


def _layout_checks(layout, maps):
    """Directed connection edges between surface maps that agree with the layout, those that don't,
    those without the mirrored edge back, and overlapping pairs. Origins in blocks, as the engine's
    neighbour arithmetic places them."""
    placed = layout["maps"]
    agree, disagree, one_way = 0, 0, 0
    for mid, origin in placed.items():
        for direction, conn in maps[("map", mid)]["connections"].items():
            other = placed.get(conn["map"])
            if other is None:
                disagree += 1
                continue
            back = maps[("map", conn["map"])]["connections"].get(OPPOSITE[direction])
            if not back or back["map"] != mid or back["offset"] != -conn["offset"]:
                one_way += 1
            off = conn["offset"]
            expected = {
                "north": (origin["x"] + off, origin["y"] - other["h"]),
                "south": (origin["x"] + off, origin["y"] + origin["h"]),
                "west": (origin["x"] - other["w"], origin["y"] + off),
                "east": (origin["x"] + origin["w"], origin["y"] + off),
            }[direction]
            if expected == (other["x"], other["y"]):
                agree += 1
            else:
                disagree += 1
    ids = sorted(placed)
    overlaps = 0
    for i, a_id in enumerate(ids):
        a = placed[a_id]
        for b_id in ids[i + 1:]:
            b = placed[b_id]
            if a["x"] < b["x"] + b["w"] and b["x"] < a["x"] + a["w"] and \
                    a["y"] < b["y"] + b["h"] and b["y"] < a["y"] + a["h"]:
                overlaps += 1
    xs = [p["x"] for p in placed.values()] + [p["x"] + p["w"] for p in placed.values()]
    ys = [p["y"] for p in placed.values()] + [p["y"] + p["h"] for p in placed.values()]
    return agree, disagree, one_way, overlaps, (max(xs) - min(xs), max(ys) - min(ys))


async def _export(port, game):
    protocol = load_protocol()
    builder = FakeMinecraft(port=port, role="builder", ping_ms=1000)
    await builder.connect(attempts=20)
    ok = (await builder.hello())["data"]
    assert (ok["protocol"], ok["engine"], ok["game"]) == (1, "0.3.85", game)
    await builder.expect("snapshot", timeout=10)

    started = time.monotonic()
    reply = await builder.request("export.index", {}, reply_type="export.index", timeout=INDEX_TIMEOUT)
    index_seconds = time.monotonic() - started
    index = reply["data"]
    assert (index["game"], index["engine"], index["bridge"]) == (game, "0.3.85", "0.1.0")
    entries = index["resources"]
    assert {e["kind"] for e in entries} == _kinds()
    assert len({(e["kind"], e["id"]) for e in entries}) == len(entries), "unique ids per kind"

    fetch_started = time.monotonic()
    want = {}
    for e in entries:
        message = await builder.send("export.get", {"kind": e["kind"], "id": e["id"]})
        want[message["seq"]] = e
    pages, lines = {}, {}
    while len(pages) < len(want):
        message = await builder.next(timeout=120)
        assert message is not None, "the connection ended during the fetch"
        if message.get("re") not in want:
            assert message["type"] in ("pong", "log"), message["type"]
            continue
        assert message["type"] == "export.data", message["type"]
        e = want[message["re"]]
        page = message["data"]
        key = (e["kind"], e["id"])
        assert (page["kind"], page["id"], page["hash"]) == (e["kind"], e["id"], e["hash"]), key
        pages[key] = page["data"]
        lines[key] = len(canonical(message).encode("utf-8")) + 1
    fetch_seconds = time.monotonic() - fetch_started
    assert not builder.conn.problems, "every message passed its schema (strict)"

    problems, mismatches = {}, 0
    for e in entries:
        key = (e["kind"], e["id"])
        found = protocol.check_export(e["kind"], pages[key])
        if found:
            problems[f"{key[0]}:{key[1]}"] = sorted({str(p) for p in found})[:5]
        raw = canonical(pages[key]).encode("utf-8")
        if len(raw) != e["size"] or hashlib.sha256(raw).hexdigest() != e["hash"]:
            mismatches += 1
    assert not problems, problems
    assert mismatches == 0, f"{mismatches} resources whose hash or size differs from the index"
    if "profile" in _kinds():
        # The installed mod's table, byte for byte (compared by hash only), and the mod switched off.
        profile = pages[("profile", "dramaless")]
        manifest = json.loads((PROFILE_MOD / "manifest.json").read_text(encoding="utf-8"))
        assert (profile["mod"], profile["version"], profile["enabled"]) == ("DRAMALESS_SHAPE", manifest["version"], False)
        on_disk = (PROFILE_MOD / "data" / "voxel_heights.lua").read_bytes()
        assert hashlib.sha256(profile["text"].encode("utf-8")).hexdigest() == hashlib.sha256(on_disk).hexdigest()
    await builder.close()

    tester = FakeMinecraft(port=port, role="test", ping_ms=1000)
    await tester.connect(attempts=20)
    await tester.hello()
    await tester.expect("snapshot", timeout=10)
    missing = await tester.request("export.get", {"kind": "map", "id": "NO_SUCH_MAP_KB"}, reply_type="error")
    assert missing["data"]["code"] == "not_found"
    status = (await tester.request("debug.status", reply_type="debug.status"))["data"]
    await tester.send("debug.quit", {"code": 0})
    await tester.close()
    return {
        "index": index, "pages": pages, "lines": lines, "status": status,
        "index_seconds": index_seconds, "fetch_seconds": fetch_seconds,
        "builder_counts": dict(builder.counts),
    }


def _swap_audit(swaps, pages):
    """M8's swap-site audit against the exported map data: per site kind, how many sites find their
    closed state (`before`: the map ships it, like a Cut tree) or their open one (`after`: the
    engine stamps the site shut on load, like a card-key door) in the map's block there, and how
    many find neither (a wrong row), no map or a block out of bounds. Only counts and the map
    symbols of wrong rows."""
    audit, wrong, by_map = {}, [], {}
    for s in swaps:
        m = pages.get(("map", s["map"]))
        if m is None:
            state = "no_map"
        elif s["bx"] >= m["width"] or s["by"] >= m["height"]:
            state = "out_of_bounds"
        else:
            block = m["blocks"][s["by"] * m["width"] + s["bx"]]
            state = "before" if block == s["before"] else ("after" if block == s["after"] else "neither")
        audit.setdefault(s["kind"], Counter())[state] += 1
        if s["kind"] != "cut":
            by_map.setdefault(f"{s['map']}:{s['kind']}", Counter())[state] += 1
        if state not in ("before", "after"):
            wrong.append(f"{s['map']}:{s['kind']}:{state}")
    return ({kind: dict(sorted(c.items())) for kind, c in sorted(audit.items())}, sorted(wrong),
            {key: dict(sorted(c.items())) for key, c in sorted(by_map.items())})


def _hash_diff(old, new):
    """Per kind: how many resources kept their hash, and the names (ids only) of the changed, added
    and removed ones, capped at 50 names a list."""
    out = {}
    for key in sorted(set(old) | set(new)):
        kind, id_ = key.split(":", 1)
        d = out.setdefault(kind, {"same": 0, "changed": [], "added": [], "removed": []})
        if key not in old:
            d["added"].append(id_)
        elif key not in new:
            d["removed"].append(id_)
        elif old[key] != new[key]:
            d["changed"].append(id_)
        else:
            d["same"] += 1
    for d in out.values():
        for name in ("changed", "added", "removed"):
            d[name] = d[name][:50]
    return out


def _summary(got):
    index, pages = got["index"], got["pages"]
    per_kind = {kind: 0 for kind in sorted(_kinds())}
    bytes_per_kind = {kind: 0 for kind in sorted(_kinds())}
    for e in index["resources"]:
        per_kind[e["kind"]] += 1
        bytes_per_kind[e["kind"]] += e["size"]
    maps = {k: v for k, v in pages.items() if k[0] == "map"}
    layout = pages[("layout", "kanto")]
    agree, disagree, one_way, overlaps, box = _layout_checks(layout, pages)
    surface = sorted(k[1] for k, m in maps.items() if m["surface"])
    cells = sum(len(row) for m in maps.values() for row in m["cells"])
    flags = {name: 0 for name in ("walkable", "water", "shore", "grass", "door", "warp_tile", "counter",
                                  "cut_tree", "pad", "hole", "spinner", "warp_entry", "sign", "ledge")}
    bits = list(flags)
    for m in maps.values():
        for row in m["cells"]:
            for f in row:
                for i, name in enumerate(bits):
                    if f >> i & 1:
                        flags[name] += 1
    encounters = pages[("encounters", "all")]
    names = pages[("names", "all")]
    swaps = pages[("swaps", "all")]["sites"]
    largest = max(index["resources"], key=lambda e: e["size"])
    largest_line = max(got["lines"].items(), key=lambda kv: kv[1])
    status = got["status"]
    return {
        "game": index["game"], "engine": index["engine"], "bridge": index["bridge"],
        "index_sha256": hashlib.sha256(canonical(index).encode("utf-8")).hexdigest(),
        "resources": len(index["resources"]), "per_kind": per_kind, "bytes_per_kind": bytes_per_kind,
        "total_bytes": sum(e["size"] for e in index["resources"]),
        "largest_resource": {"kind": largest["kind"], "id": largest["id"], "size": largest["size"]},
        "largest_line": {"kind": largest_line[0][0], "id": largest_line[0][1], "bytes": largest_line[1]},
        "maps": len(maps), "surface_maps": len(surface), "layout_maps": len(layout["maps"]),
        "layout_root": layout["root"], "root_origin": [layout["maps"][ROOT]["x"], layout["maps"][ROOT]["y"]],
        "layout_edges_agree": agree, "layout_edges_disagree": disagree, "layout_edges_one_way": one_way,
        "layout_overlaps": overlaps,
        "layout_box_blocks": list(box),
        "outside_maps": sum(1 for m in maps.values() if m["outside"]),
        "interiors": sum(1 for m in maps.values() if not m["surface"]),
        "interiors_with_parent": sum(1 for m in maps.values() if m["parent"] is not None),
        "interiors_without_parent": sorted(k[1] for k, m in maps.items() if not m["surface"] and m["parent"] is None),
        "interiors_with_last_map": sum(1 for m in maps.values() if m["lastMap"] is not None),
        "interiors_without_last_map": sorted(k[1] for k, m in maps.items()
                                             if not m["surface"] and m["lastMap"] is None),
        "dark_maps": sorted(k[1] for k, m in maps.items() if m["dark"]),
        "palettes_used": len({m["palette"] for m in maps.values()}),
        "warps": sum(len(m["warps"]) for m in maps.values()),
        "last_map_warps": sum(1 for m in maps.values() for w in m["warps"] if w["to"] == "LAST_MAP"),
        "signs": sum(len(m["signs"]) for m in maps.values()),
        "objects": sum(len(m["objects"]) for m in maps.values()),
        "cells": cells, "cell_flags": flags,
        "tilesets": per_kind["tileset"], "images": per_kind["image"], "sprites": per_kind["sprite"],
        "sgb_palettes": len(pages[("palettes", "sgb")]["palettes"]),
        "encounter_maps": len(encounters["maps"]),
        "encounter_tables": sum(len(v) for v in encounters["maps"].values()),
        "super_rod_maps": len(encounters["rods"]["super"]),
        "field_tables": len(pages[("field", "all")]["tables"]),
        "species": len(names["species"]), "moves": len(names["moves"]), "items": len(names["items"]),
        "swap_sites": len(swaps),
        "swap_audit": _swap_audit(swaps, pages)[0],
        "swap_audit_wrong": _swap_audit(swaps, pages)[1],
        "swap_audit_maps": _swap_audit(swaps, pages)[2],
        "swap_sites_by_kind": {kind: sum(1 for s in swaps if s["kind"] == kind)
                               for kind in sorted({s["kind"] for s in swaps})},
        "timing": {
            "index_seconds": round(got["index_seconds"], 2), "fetch_seconds": round(got["fetch_seconds"], 2),
            "build_ms": status.get("exportBuildMs"), "build_cpu_ms": status.get("exportCpuMs"),
            "build_steps": status.get("exportSteps"), "max_step_ms": status.get("exportMaxStepMs"),
        },
        "data_path": "datasets" if status.get("exportDatasets") else ("live" if status.get("exportLive") else "none"),
        "live_copy_at_boot": status.get("exportBootLive"),
        "warnings": status.get("exportWarnings"),
        "served": status.get("exportServed"),
        "builder_messages": got["builder_counts"],
    }


@pytest.mark.e2e
@pytest.mark.timeout(TEST_TIMEOUT)
def test_world_export_end_to_end():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    with launch("kb_export.lua", extra_env={"KB_TEST_PORT": str(TEST_PORT)}) as running:
        marker = running.wait_marker("KB_TITLE", timeout=150)
        assert marker is not None, running.result.summary()
        assert f"port={TEST_PORT}" in marker and "overworld=false" in marker
        got = asyncio.run(asyncio.wait_for(_export(TEST_PORT, pf.game), INDEX_TIMEOUT + 180))
        result = running.wait(timeout=60)
    report = _summary(got)
    # ENGINE_UPGRADE_PLAN G2/G6: every resource's hash by name, and the differences from a previous
    # report kept as local/reports/export_report_previous.json (an engine upgrade's old export): a
    # changed map, tileset, palette or sprite hash is what calls for a world rebuild (gate G6)
    hashes = {f'{e["kind"]}:{e["id"]}': e["hash"] for e in got["index"]["resources"]}
    report["hashes"] = hashes
    report["index_hash"] = hashlib.sha256(canonical(hashes).encode("utf-8")).hexdigest()
    previous = REPORTS / "export_report_previous.json"
    if previous.is_file():
        old = json.loads(previous.read_text(encoding="utf-8")).get("hashes") or {}
        report["hashes_vs_previous"] = _hash_diff(old, hashes)
    frames = result.facts("KB_EXPORT_FRAMES")
    report["frames"] = {"while_building": int(frames.get("frames", -1)),
                        "max_gap_ms": int(frames.get("max_gap_ms", -1))}
    report["exit"] = {"returncode": result.returncode, "timed_out": result.timed_out,
                      "crash_log_touched": result.crash_log_touched, "error_spots": len(result.error_spots)}
    report["bridge_warning_lines"] = sorted(line.split("] ", 2)[-1] for line in result.lines
                                            if "export.warning" in line)
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / "export_report.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n",
                                                encoding="utf-8")
    print("export report:", json.dumps(report, sort_keys=True))

    assert not result.timed_out and result.returncode == 0, result.summary()
    assert result.marker("KB_QUIT") is not None and result.marker("KB_FAIL") is None
    assert not result.crash_log_touched and not result.error_spots
    # Kanto, as Gen1Recomp imports it (refs/_research/P3_gen1_data_export.md sections 2-5)
    assert report["maps"] == 222 and report["surface_maps"] == 36 and report["layout_maps"] == 36
    assert report["layout_root"] == ROOT and report["root_origin"] == [0, 0]
    # 39 connections, each a mirrored pair of directed edges; all agree with the engine's placement
    assert report["layout_edges_disagree"] == 0 and report["layout_edges_one_way"] == 0
    assert report["layout_edges_agree"] == 78
    assert report["layout_overlaps"] == 0 and report["layout_box_blocks"] == [170, 180]
    assert report["outside_maps"] == 36 and report["interiors"] == 186
    assert report["interiors_with_parent"] == 184
    assert report["interiors_without_parent"] == ["COLOSSEUM", "TRADE_CENTER"], "the link-cable rooms"
    # no single LAST_MAP memory: unreachable, position-dependent, or two ends with different routes
    assert report["interiors_without_last_map"] == [
        "COLOSSEUM", "DIGLETTS_CAVE", "ROUTE_22_GATE", "TRADE_CENTER",
        "UNDERGROUND_PATH_NORTH_SOUTH", "UNDERGROUND_PATH_WEST_EAST"]
    assert report["last_map_warps"] == 242 and report["warps"] == 802
    assert report["tilesets"] == 24 and report["sprites"] >= 72
    assert report["images"] >= 19 + 4, "the atlases plus the flower and spinner frames"
    assert report["dark_maps"] == ["ROCK_TUNNEL_1F", "ROCK_TUNNEL_B1F"]
    assert report["species"] == 151 and report["moves"] == 165
    assert report["encounter_maps"] >= 50 and report["super_rod_maps"] >= 30
    assert report["swap_sites_by_kind"].get("cut", 0) > 0 and report["swap_sites_by_kind"].get("card_key", 0) > 0
    # M8's audit: every site's map block is its closed or its open state, except Agatha's exit seal:
    # the engine writes block (2, 0) of AGATHAS_ROOM on every entry ($3b shut, $0e open:
    # data/scripts/story4.lua:870-873, :929), so the map's own block there never shows in play
    assert report["swap_audit_wrong"] == ["AGATHAS_ROOM:script:neither"], report["swap_audit_wrong"]
    assert set(report["swap_sites_by_kind"]) == {"cut", "card_key", "script", "boulder"}
    assert report["largest_line"]["bytes"] <= PAGE_LIMIT
    assert report["data_path"] in ("datasets", "live")
