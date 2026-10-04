"""Headless world build from the real game (docs/PLAN.md section 4.5, "Running the builder").

    python tools/e2e/build_world.py --maps pallet [--world Kanto] [--fresh]

1. Launches the Gen1Recomp test instance at the title screen (driver kb_export.lua on a test port).
2. Runs ExportCli through Gradle, which downloads the export into
   local/minecraft/kantobridge/export/<game>/ (a bundle: docs/PROTOCOL.md "Export resources").
3. Asks the driver to quit (debug.quit on a role-test connection).
4. Points local/minecraft/server.properties at saves/<world> with the kantobridge:kanto preset.
5. Runs the dev server headless with the build properties; it builds, writes the report and stops.
6. Checks local/reports/build_report.json, which holds counts and hashes only.
7. Writes local/reports/viewpoints.json: fixed spectator viewpoints for the voxel plan's measurements
   (docs/specs/VOXEL_BLOCKS_PLAN.md V1a, section 10), from the report's block coordinates.

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


def export_bundle(port=EXPORT_PORT, timeout=600):
    """Steps 1-3. Returns the bundle folder (local/minecraft/kantobridge/export/<game>)."""
    pf = preflight()
    if not pf.ok:
        raise RuntimeError("pre-flight not done: " + "; ".join(pf.reasons))
    with launch("kb_export.lua", extra_env={"KB_TEST_PORT": str(port)}) as running:
        if running.wait_marker("KB_TITLE", timeout=150) is None:
            raise RuntimeError("the test instance never reached the title screen")
        code = _gradle(["exportCli", f"-Pkb.exportPort={port}", f"-Pkb.exportOut={EXPORT_DIR}", "--console=plain"],
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


def build(bundle, maps, world, timeout=3600):
    """Steps 4-5."""
    _write_server_properties(world)
    REPORTS.mkdir(parents=True, exist_ok=True)
    for stale in (REPORT, DIAGNOSTICS):
        if stale.exists():
            stale.unlink()
    args = ["runServer", f"-Pkb.build={bundle}", f"-Pkb.buildReport={REPORT}", "-Pkb.buildExit=true",
            "--args=nogui", "--console=plain"]
    if maps:
        args.insert(2, "-Pkb.buildMaps=" + ",".join(maps))
    return _gradle(args, timeout, "build")


# Maps with wild encounters: a build that includes one must place habitat blocks (M6 gate).
ENCOUNTER_MAPS = {"ROUTE_1", "ROUTE_2", "ROUTE_22", "VIRIDIAN_FOREST"}


def check_report(maps, report=REPORT):
    """Step 6: the milestone gates on the counts-only report. Returns (report, failed checks)."""
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
    return data, [name for name, ok in checks.items() if not ok]


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
    args = parser.parse_args(argv)

    maps = [] if args.maps == "all" else MAP_SETS.get(args.maps) or args.maps.split(",")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.world):
        parser.error("--world must be a plain folder name")
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
    print(f"== build ({len(maps) or 'all'} maps) into saves/{args.world}")
    code = build(bundle, maps, args.world)
    if not REPORT.exists():
        print(f"no build report (server exit {code})")
        return 1
    data, failed = check_report(maps)
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
