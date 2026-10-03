"""Writes gen1-bridge/lib/kb_catalog.lua from protocol/catalog.json.

    tools/.venv/Scripts/python.exe tools/lua/gen_catalog.py          (rewrite)
    tools/.venv/Scripts/python.exe tools/lua/gen_catalog.py --check  (fail if stale)

gen1-bridge can't read protocol/ at runtime (it only sees its own mod folder), so it carries this
derived copy of the message types and their legs. test_lua_catalog.py fails when it is stale.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
CATALOG = REPO / "protocol" / "catalog.json"
TARGET = REPO / "gen1-bridge" / "lib" / "kb_catalog.lua"
LEGS = ("g2m", "m2g", "c2s", "s2c")


def render(catalog: dict) -> str:
    limits, beat = catalog["limits"], catalog["heartbeat"]
    lines = [
        "-- kb_catalog: the protocol's message types and the hops each travels",
        "-- (docs/PROTOCOL.md \"Directions\"), for gen1-bridge's dispatch checks.",
        "--",
        "-- GENERATED from protocol/catalog.json by tools/lua/gen_catalog.py; never",
        "-- edit by hand. A test fails when it is stale.",
        "",
        "return {",
        f"  protocol = {int(catalog['protocol'])},",
        f"  maxLineBytes = {int(limits['maxLineBytes'])},",
        f"  pingMs = {int(beat['pingMs'])},",
        f"  timeoutMs = {int(beat['timeoutMs'])},",
        "  types = {",
    ]
    for msg_type in sorted(catalog["messages"]):
        entry = catalog["messages"][msg_type]
        legs = ", ".join(f"{leg} = true" for leg in LEGS if leg in entry["legs"])
        lines.append(f'    ["{msg_type}"] = {{ {legs}, re = "{entry["re"]}" }},')
    lines += ["  },", "}", ""]
    return "\n".join(lines)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="fail if kb_catalog.lua is stale")
    args = parser.parse_args(argv)
    text = render(json.loads(CATALOG.read_text(encoding="utf-8")))
    current = TARGET.read_text(encoding="utf-8") if TARGET.exists() else None
    if current == text:
        return 0
    if args.check:
        print("stale: gen1-bridge/lib/kb_catalog.lua (run tools/lua/gen_catalog.py)")
        return 1
    TARGET.write_text(text, encoding="utf-8", newline="\n")
    print("wrote gen1-bridge/lib/kb_catalog.lua")
    return 0


if __name__ == "__main__":
    sys.exit(main())
