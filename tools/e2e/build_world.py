"""Headless world build from the real game (docs/PLAN.md section 4.5, "Running the builder").

    python tools/e2e/build_world.py --maps pallet [--world Kanto] [--fresh] [--region kanto --slot 0]

1. Launches the Gen1Recomp test instance at the title screen (driver kb_export.lua on a test port).
2. Runs ExportCli through Gradle, which downloads the export into
   local/minecraft/kantobridge/export/<game>/ (a bundle: docs/PROTOCOL.md "Export resources").
3. Asks the driver to quit (debug.quit on a role-test connection).
4. Points local/minecraft/server.properties at saves/<world> with the kantobridge:kanto preset.
5. Runs the dev server headless with the build properties; it builds, writes the report and stops.
6. Checks local/reports/build_report.json, which holds counts and hashes only.
7. Writes local/reports/viewpoints.json: fixed spectator viewpoints for the voxel plan's measurements
   (docs/specs/VOXEL_BLOCKS_PLAN.md V1a, section 10), from the report's block coordinates.

Several games (docs/specs/MULTI_GAME_PLAN.md section 4.6, MG3): with --region <id> --slot <n> (both or
neither) the build is a region build: the server gets -Pkb.buildRegion/-Pkb.buildSlot (the system
properties kantobridge.build.region / kantobridge.build.slot), plans from the slot's origin and writes
<world>/kantobridge/regions/<id>/{world_index.json, shape_index.json, shape_map.bin, region.json} plus
the region table <world>/kantobridge/regions.json; the report's `region` object (region, slot, origin,
bounds, areas, families, shape_map_chars) is checked, and the written files against their schemas.
Without the options the build is a legacy build (today's root files; the report's `region` is null).

The voxel plan's shape gates (section 6.4) compare reports of several builds: shape_gates(); its budgets
(section 3.8) and the self-check's gates (section 5.6) read one report: budget_gates().

Data safety (PLAN section 0.3): only markers, counts, symbols and hashes are printed. The bundle,
world, datapack and resource pack are ROM-derived and stay under local/. The user accepts the
Minecraft EULA (local/minecraft/eula.txt); this script never writes or reads it.

A world is deleted only with --fresh, and only inside local/minecraft/saves. Automated checks build
into their own world (KantoE2E) so they never touch the world the user plays in.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parent
REPO = TOOLS.parent
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(HERE))

from fake_minecraft import FakeMinecraft  # noqa: E402
from kb_protocol import load_protocol  # noqa: E402
from run_gen1recomp import launch, preflight  # noqa: E402

MC_DIR = REPO / "local" / "minecraft"
SAVES = MC_DIR / "saves"
EXPORT_DIR = MC_DIR / "kantobridge" / "export"
REPORTS = REPO / "local" / "reports"
REPORT = REPORTS / "build_report.json"
# Symbols-only details per warning code (map ids, kinds), written beside the report (M8).
DIAGNOSTICS = REPORTS / "build_diagnostics.json"
# Fixed spectator viewpoints for the user's rendering measurements (voxel plan V1a): map ids and coordinates only.
VIEWPOINTS = REPORTS / "viewpoints.json"
# The self-check's scores per map id (voxel plan V2), written by the build beside the report.
SELFCHECK = REPORTS / "shape_selfcheck.json"
# world.shapes travels in parts of this many characters (WorldIndexParts.PART_CHARS); the map must fit in 8.
# MG3: the budget is per shape map, i.e. per region.
PART_CHARS = 64 * 1024
MAX_SHAPE_MAP_PARTS = 8
# Several games (MG3): a region id (protocol defs regionId) and the region table's slots.
REGION_ID = re.compile(r"[a-z][a-z0-9_]{0,23}")
MAX_SLOT = 63
# The builder's files (BuildTask.DATA_FOLDER and the per-region layout of MULTI_GAME_PLAN section 4.6).
DATA_FOLDER = "kantobridge"
REGIONS_FILE = "regions.json"
REGION_FILES = ("world_index.json", "shape_index.json", "shape_map.bin", "region.json")
# The voxel plan's budgets the build report can check (section 3.8).
MAX_QUADS_PER_BLOCK = 96
MAX_MEAN_QUADS_PER_BLOCK = 24
MAX_SECTION_QUADS_P95 = 12_000
MAX_SECTION_QUADS = 30_000
MIN_BLOCKS_PER_S = 40_000
MAX_FULL_BUILD_MS = 90_000
# The self-check's gates (section 5.6), fixed after the first recorded run (V3, 2026-10-04: no hole, ground match 1.0
# on every map, structure match 5th percentile 0.577 and lowest 0.375 per map): Tier A must show the ground exactly;
# the structure floors catch a regression without failing on the run that set them.
SELFCHECK_GROUND_MATCH_MIN = 1.0
SELFCHECK_STRUCTURE_P05_MIN = 0.55
SELFCHECK_STRUCTURE_MIN = 0.35
# V7's templates, from its first recorded full build: a jump in failed templates or a drop in buildings is a regression.
MAX_TEMPLATES_FAILED = 2
MIN_TEMPLATE_BUILDINGS = 20
# The overview viewpoint looks straight down from this far above the surface box's top.
OVERVIEW_HEIGHT = 128
FABRIC = REPO / "fabric-bridge"
DEFAULT_JAVA_HOME = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
EXPORT_PORT = 27196

# Map filters for the milestone gates (PLAN section 6).
MAP_SETS = {
    "pallet": ["PALLET_TOWN", "REDS_HOUSE_1F", "REDS_HOUSE_2F", "BLUES_HOUSE", "OAKS_LAB"],
    "route1": ["PALLET_TOWN", "REDS_HOUSE_1F", "REDS_HOUSE_2F", "BLUES_HOUSE", "OAKS_LAB",
               "ROUTE_1", "VIRIDIAN_CITY"],
    "viridian": ["PALLET_TOWN", "REDS_HOUSE_1F", "REDS_HOUSE_2F", "BLUES_HOUSE", "OAKS_LAB",
                 "ROUTE_1", "VIRIDIAN_CITY", "VIRIDIAN_POKECENTER", "VIRIDIAN_MART",
                 "VIRIDIAN_SCHOOL_HOUSE", "VIRIDIAN_NICKNAME_HOUSE", "VIRIDIAN_GYM", "ROUTE_2"],
}

# Server settings for a headless build (Minecraft fills in every other key with its defaults).
SERVER_PROPERTIES = {
    "level-type": "kantobridge\\:kanto",
    "online-mode": "false",
    "spawn-protection": "0",
    "max-tick-time": "-1",
    "difficulty": "peaceful",
    "gamemode": "adventure",
    "generate-structures": "false",
    "motd": "Kanto Bridge headless build",
}

# Lines from the Gradle/server output that are ours to show: our own logger, Gradle's verdicts,
# the GameTest-style summary. Everything else is counted only.
SHOW = re.compile(r"\(kantobridge\)|BUILD (SUCCESSFUL|FAILED)|^> Task :|KB_|^export:|^ExportCli")


def _gradle(args, timeout, label):
    env = dict(os.environ)
    env["JAVA_HOME"] = env.get("KB_JAVA_HOME", DEFAULT_JAVA_HOME)
    cmd = [str(FABRIC / ("gradlew.bat" if os.name == "nt" else "gradlew"))] + args
    started = time.monotonic()
    proc = subprocess.Popen(cmd, cwd=str(FABRIC), env=env, stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    hidden = 0
    try:
        for raw in iter(proc.stdout.readline, b""):
            line = raw.decode("utf-8", errors="replace").rstrip()
            if SHOW.search(line):
                print(f"  [{label}] {line}", flush=True)
            else:
                hidden += 1
        code = proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        code = None
    print(f"  [{label}] exit={code} other_lines={hidden} elapsed_s={time.monotonic() - started:.0f}", flush=True)
    return code


def _write_server_properties(world):
    MC_DIR.mkdir(parents=True, exist_ok=True)
    props = dict(SERVER_PROPERTIES, **{"level-name": f"saves/{world}"})
    text = "# Written by tools/e2e/build_world.py for headless world builds.\n"
    text += "".join(f"{k}={v}\n" for k, v in props.items())
    (MC_DIR / "server.properties").write_text(text, encoding="utf-8")


def _delete_world(world):
    target = (SAVES / world).resolve()
    if SAVES.resolve() not in target.parents:
        raise RuntimeError("refusing to delete outside local/minecraft/saves")
    if target.exists():
        shutil.rmtree(target)
        print(f"deleted world saves/{world}")


async def _quit_driver(port):
    tester = FakeMinecraft(port=port, role="test", ping_ms=1000)
    await tester.connect(attempts=10)
    try:
        await tester.hello()
        await tester.send("debug.quit", {"code": 0})
        await asyncio.sleep(0.5)
    finally:
        await tester.close()


def export_bundle(port=EXPORT_PORT, timeout=None):
    """Steps 1-3. Returns the bundle folder (local/minecraft/kantobridge/export/<game>).

    The export's index wait follows run_gen1recomp's INDEX_TIMEOUT (KB_EXPORT_INDEX_TIMEOUT: a slow host builds
    the export in minutes), passed to ExportCli as -Pkb.exportTimeout; the driver memoises the engine's dataset
    readiness checks for KB_DATASET_MEMO seconds (default 30, as showcase.py: the pod's network disk otherwise
    stretches the export from seconds to a quarter of an hour; 0 switches it off).
    """
    from run_gen1recomp import INDEX_TIMEOUT
    if timeout is None:
        timeout = max(600, int(INDEX_TIMEOUT) + 180)
    pf = preflight()
    if not pf.ok:
        raise RuntimeError("pre-flight not done: " + "; ".join(pf.reasons))
    memo = os.environ.get("KB_DATASET_MEMO", "30")
    with launch("kb_export.lua", extra_env={"KB_TEST_PORT": str(port), "KB_DATASET_MEMO": memo}) as running:
        if running.wait_marker("KB_TITLE", timeout=150) is None:
            raise RuntimeError("the test instance never reached the title screen")
        code = _gradle(["exportCli", f"-Pkb.exportPort={port}", f"-Pkb.exportOut={EXPORT_DIR}",
                        f"-Pkb.exportTimeout={int(INDEX_TIMEOUT)}", "--console=plain"],
                       timeout, "exportCli")
        asyncio.run(_quit_driver(port))
        result = running.wait(timeout=60)
    if code != 0:
        raise RuntimeError(f"ExportCli failed (exit {code})")
    if result.returncode != 0:
        raise RuntimeError(f"the test instance exited with {result.returncode}")
    bundle = EXPORT_DIR / pf.game
    if not (bundle / "index.json").is_file():
        raise RuntimeError("ExportCli wrote no index.json")
    return bundle


def region_args(region, slot):
    """The Gradle properties of a region build (MG3): both of --region/--slot, or neither (a legacy build)."""
    if region is None and slot is None:
        return []
    if region is None or slot is None:
        raise ValueError("--region and --slot go together")
    if not REGION_ID.fullmatch(region):
        raise ValueError("--region must match [a-z][a-z0-9_]{0,23}")
    if not isinstance(slot, int) or isinstance(slot, bool) or not 0 <= slot <= MAX_SLOT:
        raise ValueError(f"--slot must be a whole number from 0 to {MAX_SLOT}")
    return [f"-Pkb.buildRegion={region}", f"-Pkb.buildSlot={slot}"]


def build(bundle, maps, world, timeout=3600, region=None, slot=None):
    """Steps 4-5. With region and slot (MG3) the build is a region build."""
    extra = region_args(region, slot)
    _write_server_properties(world)
    REPORTS.mkdir(parents=True, exist_ok=True)
    for stale in (REPORT, DIAGNOSTICS):
        if stale.exists():
            stale.unlink()
    args = ["runServer", f"-Pkb.build={bundle}", f"-Pkb.buildReport={REPORT}", "-Pkb.buildExit=true",
            *extra, "--args=nogui", "--console=plain"]
    if maps:
        args.insert(2, "-Pkb.buildMaps=" + ",".join(maps))
    return _gradle(args, timeout, "build")


# Maps with wild encounters: a build that includes one must place habitat blocks (M6 gate).
ENCOUNTER_MAPS = {"ROUTE_1", "ROUTE_2", "ROUTE_22", "VIRIDIAN_FOREST"}


def check_report(maps, report=REPORT, region=None, slot=None, world=None):
    """Step 6: the milestone gates on the counts-only report. Returns (report, failed checks).

    MG3: for a region build (region and slot given) the report's `region` object and the files written under
    <world>/kantobridge/ are checked too (region_checks); a legacy build's report must say `region: null`.
    """
    data = json.loads(report.read_text(encoding="utf-8"))
    expected = len(maps) if maps else data.get("maps_requested")
    checks = {
        "maps_built": data.get("maps_built") == expected,
        "overlaps": data.get("overlaps") == 0,
        "story_cells": (data.get("story_cells") or 0) > 0,
        "warnings": data.get("warnings") == 0,
        "pack_textures": (data.get("pack_textures") or 0) > 0,
        "world_index": data.get("world_index") is True,
        "maps_unplaced": (data.get("maps_unplaced") or 0) == 0,
    }
    if not maps or ENCOUNTER_MAPS & set(maps):
        checks["habitat_blocks"] = (data.get("habitat_blocks") or 0) > 0
        checks["pools"] = (data.get("pools") or 0) > 0
    if region is None:
        checks["legacy_report"] = data.get("region") is None
    else:
        checks.update(region_checks(data, region, slot, (SAVES / world) if world else None))
    return data, [name for name, ok in checks.items() if not ok]


def region_checks(data, region, slot, world_dir=None):
    """MG3: the report's `region` object (region, slot, origin, bounds, areas, families, shape_map_chars) and, with the
    world folder, the region's files and the region table against their schemas. Returns {check: ok}."""
    rep = data.get("region") if isinstance(data.get("region"), dict) else {}
    families = rep.get("families") if isinstance(rep.get("families"), list) else []
    checks = {
        "region_reported": rep.get("region") == region and rep.get("slot") == slot,
        "region_origin": isinstance(rep.get("origin"), dict) and {"x", "y", "z"} <= set(rep["origin"]),
        "region_bounds": isinstance(rep.get("bounds"), dict)
        and {"minX", "minZ", "maxX", "maxZ", "minY", "maxY"} <= set(rep["bounds"]),
        "region_areas": isinstance(rep.get("areas"), dict) and {"surface", "zones", "dungeons", "caves"} <= set(rep["areas"]),
        "region_families": len(families) == 2 and all(isinstance(f, int) and 0 <= f <= 31 for f in families)
        and families[0] <= families[1],
        "region_shape_map_fits": 0 < (rep.get("shape_map_chars") or 0) <= MAX_SHAPE_MAP_PARTS * PART_CHARS,
    }
    if world_dir is not None:
        checks.update(region_files(world_dir, region, slot))
    return checks


def region_files(world_dir, region, slot):
    """MG3: the region's documents exist under <world>/kantobridge/regions/<id>/ and, with the region table, pass
    their schemas (regions.schema.json, region.schema.json, world_index.schema.json). Returns {check: ok}."""
    data_dir = Path(world_dir) / DATA_FOLDER
    region_dir = data_dir / "regions" / region
    checks = {f"region_file_{name}": (region_dir / name).is_file() for name in REGION_FILES}
    checks["regions_table_file"] = (data_dir / REGIONS_FILE).is_file()
    protocol = load_protocol()

    def valid(path, check):
        try:
            return not check(json.loads(path.read_text(encoding="utf-8")))
        except (OSError, ValueError):
            return False

    table_path = data_dir / REGIONS_FILE
    checks["regions_table_schema"] = checks["regions_table_file"] and valid(table_path, protocol.check_regions)
    if checks["regions_table_schema"]:
        table = json.loads(table_path.read_text(encoding="utf-8"))
        checks["regions_table_entry"] = any(e.get("id") == region and e.get("slot") == slot for e in table["regions"])
    else:
        checks["regions_table_entry"] = False
    checks["region_record_schema"] = checks["region_file_region.json"] and valid(region_dir / "region.json", protocol.check_region)
    checks["region_index_schema"] = checks["region_file_world_index.json"] \
        and valid(region_dir / "world_index.json", protocol.check_world_index)
    return checks


def shape_gates(full, again, filtered):
    """The voxel plan's shape gates (section 6.4) on counts-only reports: a fresh full build, the same build run again
    over it, and a filtered build. Returns the names of the failed gates.
    """
    checks = {
        "shapes_reported": bool(full.get("shape_index_hash")) and bool(full.get("shape_map_hash")),
        "shape_hashes_repeat": (again.get("shape_index_hash"), again.get("shape_map_hash"))
        == (full.get("shape_index_hash"), full.get("shape_map_hash")),
        "rebuild_changes_nothing": again.get("blocks_placed") == 0 and again.get("blocks_cleared") == 0,
        "filtered_shares_the_index": filtered.get("shape_index_hash") == full.get("shape_index_hash"),
        "shape_map_fits": 0 < (full.get("shape_map_wire_chars") or 0) <= MAX_SHAPE_MAP_PARTS * PART_CHARS,
        "selfcheck_recorded": isinstance(full.get("selfcheck"), dict) and (full["selfcheck"].get("maps") or 0) > 0,
    }
    return [name for name, ok in checks.items() if not ok]


def budget_gates(report, full_build=True):
    """The budgets of section 3.8 that a build report can check, the self-check's gates (no holes, the ground exact,
    the structure floors), Tier B's (no unit in the void fill beyond its ring; skipped decoration and structures
    reported), V5's (every tile story cell has its closed art's overlay) and V7's (templates and their fallbacks
    reported; for a full build, no jump in failed templates and no drop in buildings). The build's time and rate are
    judged for a full build only. Returns the names of the failed gates.
    """
    selfcheck = report.get("selfcheck") if isinstance(report.get("selfcheck"), dict) else {}
    throughput = report.get("throughput") if isinstance(report.get("throughput"), dict) else {}
    checks = {
        "quads_per_block": (report.get("shape_quads_max_block") or 0) <= MAX_QUADS_PER_BLOCK
        and (report.get("shape_quads_mean_block") or 0) <= MAX_MEAN_QUADS_PER_BLOCK,
        "quads_per_section": (report.get("shape_quads_section_p95") or 0) <= MAX_SECTION_QUADS_P95
        and (report.get("shape_quads_section_max") or 0) <= MAX_SECTION_QUADS,
        "selfcheck_holes": selfcheck.get("holes") == 0,
        "selfcheck_ground": (selfcheck.get("ground_match_min") or 0) >= SELFCHECK_GROUND_MATCH_MIN,
        "selfcheck_structure": (selfcheck.get("structure_match_p05") or 0) >= SELFCHECK_STRUCTURE_P05_MIN
        and (selfcheck.get("structure_match_min") or 0) >= SELFCHECK_STRUCTURE_MIN,
        "shape_map_fits": 0 < (report.get("shape_map_wire_chars") or 0) <= MAX_SHAPE_MAP_PARTS * PART_CHARS,
        # Tier B (V4): the void fill never holds a unit beyond its ring; skipped decoration and structures are reported.
        "void_hull_cells": report.get("void_hull_cells") == 0,
        "tier_b_reported": isinstance(report.get("shape_decor_skipped"), int) and isinstance(report.get("structures_by_kind"), dict),
        # V5: every tile story cell's closed art is in the shape map (its overlay), so the chunk mesh draws it.
        "closed_story_art": report.get("closed_shape_cells") == (report.get("story_cells_by_type") or {}).get("tile", 0),
        # The profile plan (P1): a profile the export carried is read as data, and units are reported by source.
        "profile_read": (report.get("profile") or {}).get("status") in ("absent", "ok")
        and isinstance(report.get("units_by_source"), dict),
        # V7: templates and their fallbacks are reported; a failure is one of the fallbacks.
        "templates_reported": isinstance(report.get("shape_templates"), int) and isinstance(selfcheck.get("templates_failed"), int)
        and selfcheck.get("templates_failed") <= ((report.get("structures_by_kind") or {}).get("fallback") or 0),
    }
    if full_build:
        by_kind = report.get("structures_by_kind") or {}
        checks["template_fallbacks"] = (selfcheck.get("templates_failed") or 0) <= MAX_TEMPLATES_FAILED \
            and (by_kind.get("building") or 0) >= MIN_TEMPLATE_BUILDINGS
        checks["placement_rate"] = (throughput.get("blocks_per_s") or 0) >= MIN_BLOCKS_PER_S
        checks["build_time"] = 0 < (report.get("elapsed_ms") or 0) <= MAX_FULL_BUILD_MS
    return [name for name, ok in checks.items() if not ok]


def viewpoints(report, world):
    """The fixed spectator viewpoints of a build (voxel plan V1a): the spawn cell at walking height, looking north
    over the town, and the centre of the surface box from above, looking down. Map ids and block coordinates only.
    """
    points = []
    vp = report.get("viewpoints") or {}
    if {"spawn_x", "spawn_feet_y", "spawn_z"} <= vp.keys():
        x, y, z = vp["spawn_x"] + 1.0, float(vp["spawn_feet_y"]), vp["spawn_z"] + 1.0
        points.append({"id": "spawn", "map": report.get("spawn_map"), "x": x, "y": y, "z": z, "yaw": 180.0, "pitch": 10.0})
    if {"surface_min_x", "surface_max_x", "surface_min_z", "surface_max_z", "surface_max_y"} <= vp.keys():
        x = (vp["surface_min_x"] + vp["surface_max_x"]) / 2.0
        z = (vp["surface_min_z"] + vp["surface_max_z"]) / 2.0
        y = float(vp["surface_max_y"] + OVERVIEW_HEIGHT)
        points.append({"id": "above", "map": None, "x": x, "y": y, "z": z, "yaw": 180.0, "pitch": 90.0})
    for point in points:
        point["command"] = "/tp @s {x:g} {y:g} {z:g} {yaw:g} {pitch:g}".format(**point)
    return {"version": 1, "world": world, "viewpoints": points}


def write_viewpoints(report, world, path=VIEWPOINTS):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(viewpoints(report, world), indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return path


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--maps", default="pallet", help="a map set (" + ", ".join(MAP_SETS) +
                        "), 'all', or a comma-separated list of map ids")
    parser.add_argument("--world", default="Kanto", help="world folder under local/minecraft/saves")
    parser.add_argument("--fresh", action="store_true", help="delete that world first")
    parser.add_argument("--skip-export", action="store_true", help="reuse the bundle already in local/")
    parser.add_argument("--game", default=None, help="with --skip-export: red or blue (default: the import)")
    parser.add_argument("--region", default=None, help="MG3: build as this region of the world (with --slot)")
    parser.add_argument("--slot", type=int, default=None, help=f"MG3: the region table slot, 0..{MAX_SLOT} (with --region)")
    args = parser.parse_args(argv)

    maps = [] if args.maps == "all" else MAP_SETS.get(args.maps) or args.maps.split(",")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.world):
        parser.error("--world must be a plain folder name")
    try:
        region_args(args.region, args.slot)
    except ValueError as exc:
        parser.error(str(exc))
    if not (MC_DIR / "eula.txt").exists():
        print("local/minecraft/eula.txt is missing: the user accepts the EULA (PLAN section 0.6)")
        return 2
    if args.fresh:
        _delete_world(args.world)
    if args.skip_export:
        bundle = EXPORT_DIR / (args.game or preflight().game)
    else:
        print("== export")
        bundle = export_bundle()
    where = f"saves/{args.world}" + (f" as region {args.region} (slot {args.slot})" if args.region is not None else "")
    print(f"== build ({len(maps) or 'all'} maps) into {where}")
    code = build(bundle, maps, args.world, region=args.region, slot=args.slot)
    if not REPORT.exists():
        print(f"no build report (server exit {code})")
        return 1
    data, failed = check_report(maps, region=args.region, slot=args.slot, world=args.world)
    print("report: " + json.dumps(data, sort_keys=True))
    if code != 0 or failed:
        print(f"FAILED: server exit {code}, checks {failed}")
        if DIAGNOSTICS.exists():
            # Symbols only (map ids, kinds, sources): safe to print.
            print("diagnostics: " + DIAGNOSTICS.read_text(encoding="utf-8").strip())
        return 1
    write_viewpoints(data, args.world)
    print(f"viewpoints: {VIEWPOINTS.relative_to(REPO).as_posix()}")
    print("build OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
