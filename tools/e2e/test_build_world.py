"""M2, M6, M7 and M8 gates (docs/PLAN.md section 6): the real export builds headless; and the voxel plan's shape
gates (docs/specs/VOXEL_BLOCKS_PLAN.md section 6.4).

The full build (every reachable map) covers M2's checks, M6's habitat blocks and pools, M7's maps
and M8's full-build gate (every map placed, 0 warnings, 0 overlaps) in one build of about a minute.
The shape gates add a filtered build (Pallet Town, into its own world) and the full build run again
over itself: equal shape hashes, the filtered build's shape index equal to the full one's, no block
changed by the rebuild; the full build meets the budgets the report can check (quads per block and
per section, placement rate, build time, the shape map's size) and its self-check finds no hole.

Builds into its own worlds, saves/KantoE2E and saves/KantoE2EPallet, recreated on every run, so it
never touches the world the user plays in (saves/Kanto). Prints and checks only the counts-only
build reports; the fresh full build's report and self-check are the ones left in local/reports/.
"""
import pytest

import build_world
from run_gen1recomp import INSTALL, preflight


def _build(bundle, maps, world):
    code = build_world.build(bundle, maps, world)
    assert build_world.REPORT.exists(), f"no build report (server exit {code})"
    data, failed = build_world.check_report(maps)
    details = build_world.DIAGNOSTICS.read_text(encoding="utf-8") if build_world.DIAGNOSTICS.exists() else None
    assert code == 0 and not failed, {"exit": code, "failed": failed,
                                      "warning_codes": data.get("warning_codes"), "diagnostics": details}
    return data


@pytest.mark.e2e
@pytest.mark.timeout(3600)
def test_full_build_from_the_real_export():
    pf = preflight()
    if not pf.ok:
        pytest.skip("pre-flight not done: " + "; ".join(pf.reasons))
    if not (build_world.MC_DIR / "eula.txt").exists():
        pytest.skip("the user hasn't accepted the Minecraft EULA (local/minecraft/eula.txt)")
    bundle = build_world.export_bundle()

    # A filtered build first, into its own world, so the full build's files are the ones left behind.
    pallet = build_world.MAP_SETS["pallet"]
    build_world._delete_world("KantoE2EPallet")
    filtered = _build(bundle, pallet, "KantoE2EPallet")
    build_world._delete_world("KantoE2EPallet")

    maps = []  # every reachable map
    build_world._delete_world("KantoE2E")
    full = _build(bundle, maps, "KantoE2E")
    report_text = build_world.REPORT.read_text(encoding="utf-8")
    selfcheck_text = build_world.SELFCHECK.read_text(encoding="utf-8") if build_world.SELFCHECK.exists() else None
    again = _build(bundle, maps, "KantoE2E")
    build_world.REPORT.write_text(report_text, encoding="utf-8")
    if selfcheck_text is not None:
        build_world.SELFCHECK.write_text(selfcheck_text, encoding="utf-8")
    assert selfcheck_text is not None, "no shape_selfcheck.json beside the report"

    failed = build_world.shape_gates(full, again, filtered) + build_world.budget_gates(full)
    assert not failed, {"failed": failed, "shape_map_wire_chars": full.get("shape_map_wire_chars"),
                        "rebuild": (again.get("blocks_placed"), again.get("blocks_cleared")),
                        "quads": (full.get("shape_quads_mean_block"), full.get("shape_quads_max_block"),
                                  full.get("shape_quads_section_p95"), full.get("shape_quads_section_max")),
                        "throughput": full.get("throughput"), "elapsed_ms": full.get("elapsed_ms"),
                        "selfcheck": full.get("selfcheck")}

    # The profile plan (P1): with Dramaless Shape in the test install, its table is read and its pins make units.
    if (INSTALL / "mods" / "DRAMALESS_SHAPE" / "data" / "voxel_heights.lua").is_file():
        profile = full.get("profile") or {}
        assert profile.get("status") == "ok" and profile.get("pins", 0) > 0 and profile.get("enabled") is False, profile
        assert (full.get("units_by_source") or {}).get("pin", 0) > 0, full.get("units_by_source")

    points = build_world.viewpoints(full, "KantoE2E")["viewpoints"]
    assert [p["id"] for p in points] == ["spawn", "above"], points
    build_world.write_viewpoints(full, "KantoE2E")


# ------------------------------------------------------------ MG3: the region build's helpers (no game needed)


def test_region_args_take_both_options_or_neither():
    assert build_world.region_args(None, None) == []
    assert build_world.region_args("kanto", 0) == ["-Pkb.buildRegion=kanto", "-Pkb.buildSlot=0"]
    assert build_world.region_args("test_kanto2", 63) == ["-Pkb.buildRegion=test_kanto2", "-Pkb.buildSlot=63"]
    for region, slot in (("kanto", None), (None, 1), ("Kanto", 0), ("kanto", 64), ("kanto", -1), ("kanto", True)):
        with pytest.raises(ValueError):
            build_world.region_args(region, slot)


def _region_report(region="kanto", slot=0, chars=4096, index_chars=8192, world_chars=2048):
    return {"region": {"region": region, "slot": slot, "origin": {"x": 0, "y": 64, "z": 0},
                       "bounds": {"minX": -300, "minZ": -9400, "maxX": 5400, "maxZ": 4200, "minY": -256, "maxY": 319},
                       "areas": {"surface": {"x": 0, "z": 0, "w": 704, "d": 744}, "zones": {"x": 0, "z": 3744, "w": 5120, "d": 640},
                                 "dungeons": {"x": 0, "z": -3600, "w": 1200, "d": 600}, "caves": {"x": 0, "z": -9400, "w": 0, "d": 0}},
                       "families": [0, 15], "shape_map_chars": chars, "shape_index_chars": index_chars, "index_chars": world_chars}}


def _write_region_world(root, region="kanto", slot=0):
    import hashlib
    import json
    from kb_protocol.paths import REPO_ROOT
    golden = REPO_ROOT / "fabric-bridge" / "src" / "gametest" / "resources" / "kantobridge" / "golden" / "synthetic_world_index.json"
    data = root / build_world.DATA_FOLDER
    region_dir = data / "regions" / region
    region_dir.mkdir(parents=True)
    (region_dir / "world_index.json").write_text(golden.read_text(encoding="utf-8"), encoding="utf-8")
    (region_dir / "shape_index.json").write_text("{}", encoding="utf-8")
    (region_dir / "shape_map.bin").write_bytes(b"KBSM")
    digest = hashlib.sha256(b"kb-test").hexdigest()
    record = {"version": 1, "id": region, "game": "red", "family": "gen1", "exportHash": digest, "slot": slot,
              "origin": {"x": 8192 * slot, "y": 64, "z": 0},
              "bounds": {"minX": -300, "minZ": -9400, "maxX": 5400, "maxZ": 4200, "minY": -256, "maxY": 319},
              "areas": {"surface": {"x": 0, "z": 0, "w": 704, "d": 744}, "zones": {"x": 0, "z": 3744, "w": 5120, "d": 640},
                        "dungeons": {"x": 0, "z": -3600, "w": 1200, "d": 600}, "caves": {"x": 0, "z": -9400, "w": 0, "d": 0}},
              "families": [0, 15],
              "files": {"index": f"regions/{region}/world_index.json", "shapeIndex": f"regions/{region}/shape_index.json",
                        "shapeMap": f"regions/{region}/shape_map.bin"},
              "hashes": {"index": digest, "shapeIndex": digest, "shapeMap": digest},
              "generator": {"shapes": "kb-shapes/11"},
              "rules": {"profile": "gen1", "compat": {"maxDex": 151, "maxMove": 165, "nick": 10, "onIncompatible": "leave_out"}},
              "namespaces": {"artSet": "red", "pools": f"kantobridge:{region}"}}
    (region_dir / "region.json").write_text(json.dumps(record), encoding="utf-8")
    (data / build_world.REGIONS_FILE).write_text(
        json.dumps({"version": 1, "pitch": 8192, "regions": [{"id": region, "game": "red", "slot": slot}]}), encoding="utf-8")
    return data


def test_a_heap_goes_to_the_server(monkeypatch, tmp_path):
    # MG7 G3G: a full Hoenn needs more than the JVM's default heap (-Pkb.serverHeap -> -Xmx)
    seen = []

    def gradle(args, timeout, label):
        seen.append(args)
        return 0

    monkeypatch.setattr(build_world, "_write_server_properties", lambda world: None)
    monkeypatch.setattr(build_world, "REPORTS", tmp_path)
    monkeypatch.setattr(build_world, "REPORT", tmp_path / "report.json")
    monkeypatch.setattr(build_world, "DIAGNOSTICS", tmp_path / "diagnostics.json")
    monkeypatch.setattr(build_world, "_gradle", gradle)
    assert build_world.build(tmp_path / "bundle", [], "HoennE2E", region="hoenn", slot=0, heap="8g") == 0
    assert "-Pkb.serverHeap=8g" in seen[0] and "-Pkb.buildRegion=hoenn" in seen[0]
    assert build_world.build(tmp_path / "bundle", [], "KantoE2E") == 0
    assert not any(a.startswith("-Pkb.serverHeap") for a in seen[1]), "the default heap without --heap"
    with pytest.raises(ValueError):
        build_world.build(tmp_path / "bundle", [], "HoennE2E", heap="lots")


def test_region_checks_pass_on_a_complete_region_world(tmp_path):
    _write_region_world(tmp_path)
    checks = build_world.region_checks(_region_report(), "kanto", 0, tmp_path)
    assert checks and all(checks.values()), [name for name, ok in checks.items() if not ok]


def test_region_checks_name_what_is_wrong(tmp_path):
    data = _write_region_world(tmp_path)
    # the report names another region, its shape map is empty
    checks = build_world.region_checks(_region_report(region="hoenn", chars=0), "kanto", 0, tmp_path)
    assert not checks["region_reported"] and not checks["region_shape_map_fits"]
    assert checks["region_record_schema"] and checks["region_index_schema"]  # the files are fine
    # a missing record and a table without the region
    (data / "regions" / "kanto" / "region.json").unlink()
    (data / build_world.REGIONS_FILE).write_text('{"version": 1, "pitch": 8192, "regions": []}', encoding="utf-8")
    checks = build_world.region_files(tmp_path, "kanto", 0)
    assert not checks["region_file_region.json"] and not checks["region_record_schema"]
    assert checks["regions_table_schema"] and not checks["regions_table_entry"]
    # a legacy report (region null) fails a region check
    assert not build_world.region_checks({"region": None}, "kanto", 0)["region_reported"]


def test_check_report_tells_legacy_and_region_builds_apart(tmp_path):
    import json
    base = {"maps_requested": 5, "maps_built": 5, "overlaps": 0, "story_cells": 3, "warnings": 0, "pack_textures": 9,
            "world_index": True, "maps_unplaced": 0}
    maps = build_world.MAP_SETS["pallet"]
    report = tmp_path / "build_report.json"
    report.write_text(json.dumps(dict(base, region=None)), encoding="utf-8")
    data, failed = build_world.check_report(maps, report=report)
    assert data["maps_built"] == 5 and failed == []
    report.write_text(json.dumps(base), encoding="utf-8")  # an older report without the key is legacy too
    assert build_world.check_report(maps, report=report)[1] == []
    report.write_text(json.dumps(dict(base, **_region_report())), encoding="utf-8")
    assert "legacy_report" in build_world.check_report(maps, report=report)[1]  # a region report in a legacy check
    failed = build_world.check_report(maps, report=report, region="kanto", slot=0)[1]
    assert failed == []  # the report alone (no world folder given)
    failed = build_world.check_report(maps, report=report, region="hoenn", slot=1)[1]
    assert failed == ["region_reported"]


def test_a_gen3_build_may_carry_hoenns_known_seams_only(tmp_path):
    # MG7 G3G: until MG8 joins Hoenn's seams, a Gen 3 build's edge mismatches, overlaps and arrival-less interiors are
    # known; any other warning fails, and so does any warning of a Gen 1 build. The shape map has a Gen 3 budget.
    import json
    codes = {"interior_arrival_missing": 36, "layout_edge_mismatch": 6, "layout_overlap": 1}
    base = {"maps_requested": 319, "maps_built": 319, "overlaps": 1, "story_cells": 52987, "warnings": 43,
            "warning_codes": codes, "pack_textures": 18358, "world_index": True, "maps_unplaced": 0, "habitat_blocks": 386,
            "pools": 81}
    report = tmp_path / "build_report.json"
    hoenn = _region_report(region="hoenn", chars=1_682_080)
    report.write_text(json.dumps(dict(base, game="emerald", **hoenn)), encoding="utf-8")
    assert build_world.check_report([], report=report, region="hoenn", slot=0)[1] == []
    report.write_text(json.dumps(dict(base, game="emerald", warnings=44, warning_codes=dict(codes, spawn_missing=1),
                                      **hoenn)), encoding="utf-8")
    assert build_world.check_report([], report=report, region="hoenn", slot=0)[1] == ["overlaps", "warnings"]
    report.write_text(json.dumps(dict(base, game="blue", **_region_report(chars=1_682_080))), encoding="utf-8")
    assert build_world.check_report([], report=report, region="kanto", slot=0)[1] == [
        "overlaps", "warnings"], "a Gen 1 build keeps every warning gate; the shape budgets are the same for every game (item 4)"
    too_big = build_world.MAX_SHAPE_DOC_PARTS * build_world.PART_CHARS + 1
    report.write_text(json.dumps(dict(base, game="emerald", **_region_report(region="hoenn", chars=too_big))), encoding="utf-8")
    assert build_world.check_report([], report=report, region="hoenn", slot=0)[1] == ["region_shape_map_fits"]
    report.write_text(json.dumps(dict(base, game="emerald", **_region_report(region="hoenn", index_chars=too_big))), encoding="utf-8")
    assert build_world.check_report([], report=report, region="hoenn", slot=0)[1] == ["region_shape_index_fits"]
    report.write_text(json.dumps(dict(base, game="emerald", **_region_report(region="hoenn", index_chars=0))), encoding="utf-8")
    assert build_world.check_report([], report=report, region="hoenn", slot=0)[1] == ["region_shape_index_fits"], \
        "a report from before item 4 (no index size) fails until rebuilt"
    report.write_text(json.dumps(dict(base, game="emerald", **_region_report(region="hoenn", world_chars=too_big))), encoding="utf-8")
    assert build_world.check_report([], report=report, region="hoenn", slot=0)[1] == ["region_index_fits"], \
        "MG9 G2G: the WorldIndex is a region's third document on demand, with the same budget"
    report.write_text(json.dumps(dict(base, game="emerald", **_region_report(region="hoenn", world_chars=0))), encoding="utf-8")
    assert build_world.check_report([], report=report, region="hoenn", slot=0)[1] == ["region_index_fits"], \
        "a report from before G2G (no index size) fails until rebuilt"


def test_a_gen2_build_may_carry_crystals_known_warnings_only(tmp_path):
    # MG9 G2G: the real Crystal's build gives 3 warnings: Celadon City and Route 7's loop (an edge mismatch each way) and
    # one object off its map in Goldenrod's Pokemon Center. Any other warning, and any overlap, fails.
    import json
    codes = {"layout_edge_mismatch": 2, "object_out_of_bounds": 1}
    base = {"maps_requested": 388, "maps_built": 388, "overlaps": 0, "story_cells": 19764, "warnings": 3,
            "warning_codes": codes, "pack_textures": 3825, "world_index": True, "maps_unplaced": 0, "habitat_blocks": 555,
            "pools": 91}
    report = tmp_path / "build_report.json"
    johto = _region_report(region="johto", slot=2, chars=648_980, index_chars=414_040, world_chars=402_116)
    report.write_text(json.dumps(dict(base, game="crystal", **johto)), encoding="utf-8")
    assert build_world.check_report([], report=report, region="johto", slot=2)[1] == []
    report.write_text(json.dumps(dict(base, game="crystal", warnings=4, warning_codes=dict(codes, spawn_missing=1),
                                      **johto)), encoding="utf-8")
    assert build_world.check_report([], report=report, region="johto", slot=2)[1] == ["warnings"]
    report.write_text(json.dumps(dict(base, game="crystal", overlaps=1, **johto)), encoding="utf-8")
    assert build_world.check_report([], report=report, region="johto", slot=2)[1] == ["overlaps"], "Gen 2 has no known overlaps"
    report.write_text(json.dumps(dict(base, game="blue", **_region_report())), encoding="utf-8")
    assert build_world.check_report([], report=report, region="kanto", slot=0)[1] == ["warnings"], "a Gen 1 build keeps every gate"
