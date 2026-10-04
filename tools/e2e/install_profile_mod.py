"""Installs the user's copy of Dramaless Shape into the test install, switched off (docs/specs/PROFILE_PLAN.md §5.8).

    tools/.venv/Scripts/python.exe tools/e2e/install_profile_mod.py [--game blue]

Copies refs/DRAMALESS_SHAPE-main (the zip extract the user supplied; MIT for its 2.0 line, docs/REFERENCES.md) into
local/gen1recomp-e2e/mods/DRAMALESS_SHAPE: the files the mod runs from and its data, never assets/ (its building
catalogue is composited from game art), tests/ or tools/, and none of its scripts run. Then boots the test install
once with drivers/kb_mods_off.lua, which switches the mod off (Gen1Recomp keeps that for the next boot), so no test
boot runs its renderer or hooks; gen1-bridge reads its table either way. Only the test install is touched, never the
user's local/gen1recomp/. Prints markers, names and counts only.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_gen1recomp as rg  # noqa: E402

SOURCE = rg.REPO / "refs" / "DRAMALESS_SHAPE-main"
MOD_ID = "DRAMALESS_SHAPE"
TARGET = rg.INSTALL / "mods" / MOD_ID
FILES = ("manifest.json", "main.lua", "mod.card", "LICENSE", "README.md", "CREDITS.md", "CHANGELOG.md")
FOLDERS = ("lib", "data", "docs")


def copy_mod(source: Path = SOURCE, target: Path = TARGET) -> int:
    """Copies the mod's runtime files; returns how many files were copied."""
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("id") != MOD_ID:
        raise RuntimeError("not the Dramaless Shape mod")
    if target.is_symlink() or (target.exists() and not target.is_dir()):
        raise RuntimeError("the target is not a plain folder")
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    copied = 0
    for name in FILES:
        if (source / name).is_file():
            shutil.copy2(source / name, target / name)
            copied += 1
    for name in FOLDERS:
        if (source / name).is_dir():
            shutil.copytree(source / name, target / name)
            copied += sum(1 for p in (target / name).rglob("*") if p.is_file())
    return copied


def switch_off(game: str | None) -> bool:
    result = rg.run("kb_mods_off.lua", game=game, timeout=180.0)
    line = result.marker("KB_MODS_OFF")
    print(f"KB_PROFILE_MOD switch_off {line or 'no marker'}")
    return line is not None and "ok=true" in line


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--game", choices=rg.VERSIONS)
    args = parser.parse_args(argv)
    if not SOURCE.is_dir():
        print("KB_PROFILE_MOD no_source")
        return 1
    copied = copy_mod()
    print(f"KB_PROFILE_MOD copied files={copied}")
    return 0 if switch_off(args.game) else 1


if __name__ == "__main__":
    raise SystemExit(main())
