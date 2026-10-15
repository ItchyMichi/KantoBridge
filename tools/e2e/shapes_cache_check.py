"""Shapes on demand in the real dev client (docs/SEVERAL_GAMES_FOLLOWUPS.md item 4, the voxel plan's R10): joins a
built world twice. The first join starts from an empty cache, so the client asks the server for every region's shape
documents (world.shapes.get) and keeps them; the second join takes them all from its cache and asks for none. Both
times every region's shape table must come up. The shapes come from the server; Blue only serves at its title
(drivers/kb_serve.lua), because the dev client's autopilot opens the world once a hidden game is connected.

    xvfb-run -a -s "-screen 0 1280x720x24" python tools/e2e/shapes_cache_check.py --world PassE2E

Region ids and counts only (from the client's log).
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import story_video as sv  # noqa: E402
from run_gen1recomp import launch  # noqa: E402

CACHE = sv.REPO / "local" / "minecraft" / "kantobridge" / "shapes" / "docs"
OFFER = re.compile(r"world\.shapes\.offer: region (\S+): from the cache (\d+), asked for (\d+)")
HELD = re.compile(r"world\.shapes\.offer: region (\S+) holds both")
RECEIVED = re.compile(r"world\.shapes: (index|map) received \(\d+ parts, hash \w+, region (\w+)")
TABLE = re.compile(r"shapes: table for region (\S+) index \S+ ready")


def join(world: str, regions: list[str], log_path: Path, timeout: float) -> dict:
    """One join: until every region's table is ready (or the timeout), then quit. Returns what the log said."""
    pilot = sv.Pilot(sv.PILOT_DIR)
    command = ["./gradlew", "--no-daemon", "--console=plain", "runClient", "-Pkb.autopilot=remote",
               f"-Pkb.autopilotWorld={world}", f"-Pkb.autopilotPilotDir={sv.PILOT_DIR}", "-Pkb.autopilotQuit=true",
               f"-Pkb.links=blue:{sv.BLUE_PORT}"]
    java_home = os.environ.get("KB_JAVA_HOME") or os.environ.get("JAVA_HOME") or "/opt/java/openjdk"
    client = subprocess.Popen(command, cwd=sv.GRADLE_DIR, env=dict(os.environ, JAVA_HOME=java_home),
                              stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    threading.Thread(target=sv.stream, args=(client, log_path), daemon=True).start()
    started = time.monotonic()
    seen: dict = {"offers": {}, "held": [], "documents": [], "tables": [], "seconds": None}
    try:
        while time.monotonic() - started < timeout:
            if client.poll() is not None:
                break
            text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""
            seen["offers"] = {m.group(1): (int(m.group(2)), int(m.group(3))) for m in OFFER.finditer(text)}
            seen["held"] = sorted({m.group(1) for m in HELD.finditer(text)})
            seen["documents"] = sorted({(m.group(2), m.group(1)) for m in RECEIVED.finditer(text)})
            seen["tables"] = sorted({m.group(1) for m in TABLE.finditer(text)})
            if set(regions) <= set(seen["tables"]):
                seen["seconds"] = round(time.monotonic() - started, 1)
                break
            time.sleep(1.0)
    finally:
        try:
            pilot.send("quit")
        except OSError:
            pass
        try:
            client.wait(timeout=180)
        except subprocess.TimeoutExpired:
            client.kill()
    return seen


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--world", default="PassE2E", help="a built world in local/minecraft/saves (copied first)")
    parser.add_argument("--regions", default="kanto,hoenn")
    parser.add_argument("--timeout", type=float, default=900)
    args = parser.parse_args(argv)
    regions = [r for r in args.regions.split(",") if r]
    world = args.world + "Shapes"
    if (sv.SAVES / world).exists():
        shutil.rmtree(sv.SAVES / world)
    shutil.copytree(sv.SAVES / args.world, sv.SAVES / world)
    if CACHE.exists():
        shutil.rmtree(CACHE)
    sv.PILOT_DIR.mkdir(parents=True, exist_ok=True)
    home = sv.make_home("kanto", "blue")
    env = {"KB_DATASET_MEMO": "30", "KB_PACE": "60", "KB_DRIVER_SECONDS": str(int(3 * args.timeout) + 600)}
    with launch("kb_union_off.lua", game="blue", install=home, echo=False, extra_env=env) as off:
        sv.say("union off", off.wait(timeout=180).marker("KB_UNION_OFF"))
    blue = launch("kb_serve.lua", game="blue", install=home, echo=False, extra_env=dict(env, KB_TEST_PORT=str(sv.BLUE_PORT)))
    try:
        sv.say("blue:", blue.wait_marker("KB_BOOTED", timeout=240))
        first = join(world, regions, sv.PILOT_DIR / "client-shapes-1.log", args.timeout)
        sv.say("first join (empty cache):", first)
        cached = sorted(p.name.split(".", 1)[1] for p in CACHE.glob("*.z")) if CACHE.exists() else []
        sv.say("cache after it:", len(cached), "documents", cached)
        second = join(world, regions, sv.PILOT_DIR / "client-shapes-2.log", args.timeout)
        sv.say("second join:", second)
    finally:
        blue.kill()
        blue.wait(timeout=30)
    ok = (set(regions) <= set(first["tables"]) and all(first["offers"].get(r) == (0, 2) for r in regions)
          and set(regions) <= set(second["tables"]) and all(second["offers"].get(r) == (2, 0) for r in regions)
          and not second["documents"])
    sv.say("OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
