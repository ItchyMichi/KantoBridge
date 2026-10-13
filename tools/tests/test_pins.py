"""The Gen1Recomp pin, everywhere it is written, agrees with ``Engine.CHECKED`` (ENGINE_UPGRADE_PLAN 1.4; UP1 item 2).

``Engine.CHECKED = "<version>"`` in gen1-bridge/lib/kb_engine.lua is the one source of truth. Every file in PINS is
read as text, its ``0.3.N`` literals are collected (``v0.3.49`` counts; ``0.4.0`` and the like do not), the file's
rule drops the literals that are not the pin, and what is left must be exactly {CHECKED}: a stale file fails by name,
with its stray literals. Java sources are out of scope, their literals are self-consistent fakes (plan decision U6).

Rules:
* ALL: every literal is the pin.
* CODE: a source whose comments narrate the engine's history ("since v0.3.85", "the v0.3.49 rule"): only quoted
  literals ("0.3.49": the code and its examples) and a comment's "pinned vX" are the pin.
* FOILS: a Python test file. Inside a test function whose source mentions ``untested`` or ``unchecked`` (the tests of
  the warning about an engine other than the checked one) a literal that is not CHECKED is a deliberate foil and is
  skipped; everywhere else every literal is the pin.
* LINE(pattern): only the lines matching ``pattern`` hold the pin (CLAUDE.md names both tags of an upgrade in its
  summary of the plan; its install sentence is the pin).
* ROW: docs/VERSIONS.md: only the table row whose first cell is exactly ``Gen1Recomp``; history goes in other rows.
* JSON_RANGE, PINS_JSON: the manifest's ``game_version`` range ``>=X <0.4.0``, and the installer's gen1recomp entry
  (``version``, and the release zip named in ``url``).
The text rules skip a line that says "previous" (a previous version kept for reference, as in INSTALL.md).
"""
import ast
import json
import os
import re

import pytest

TOOLS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(TOOLS)
ENGINE = "gen1-bridge/lib/kb_engine.lua"

CHECKED = re.compile(r'^Engine\.CHECKED = "(0\.3\.\d+)"', re.M)
LITERAL = re.compile(r"(?<![\w.])v?(0\.3\.\d+)\b")
QUOTED = re.compile(r"[\"'](0\.3\.\d+)[\"']|\bpinned v(0\.3\.\d+)\b")
HISTORY = re.compile(r"\bprevious", re.I)
GAME_VERSION = re.compile(r">=(0\.3\.\d+) <0\.4\.0")
RELEASE_ZIP = re.compile(r"/v(0\.3\.\d+)/gen1recomp-(0\.3\.\d+)-windows\.zip$")


def _read(path):
    with open(os.path.join(REPO, path), encoding="utf-8") as f:
        return f.read()


def _checked():
    m = CHECKED.search(_read(ENGINE))
    assert m, f"{ENGINE}: Engine.CHECKED not found"
    return m.group(1)


def ALL(text, checked):
    return [v for line in text.split("\n") if not HISTORY.search(line) for v in LITERAL.findall(line)]


def CODE(text, checked):
    return [a or b for line in text.split("\n") if not HISTORY.search(line) for a, b in QUOTED.findall(line)]


def FOILS(text, checked):
    lines = text.split("\n")
    foils = []  # (first line, last line) of each test of the "other engine" warning, decorators included
    for node in ast.walk(ast.parse(text)):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            first = min([node.lineno] + [d.lineno for d in node.decorator_list])
            source = "\n".join(lines[first - 1:node.end_lineno])
            if "untested" in source or "unchecked" in source:
                foils.append((first, node.end_lineno))
    found = []
    for n, line in enumerate(lines, 1):
        for v in LITERAL.findall(line):
            if v != checked and any(a <= n <= b for a, b in foils):
                continue
            found.append(v)
    return found


def LINE(pattern):
    chosen = re.compile(pattern)

    def rule(text, checked):
        return [v for line in text.split("\n") if chosen.search(line) and not HISTORY.search(line)
                for v in LITERAL.findall(line)]

    rule.__name__ = f"LINE({pattern})"
    return rule


ROW = LINE(r"^\|\s*Gen1Recomp\s*\|")
ROW.__name__ = "ROW"


def JSON_RANGE(text, checked):
    m = GAME_VERSION.search(json.loads(text)["game_version"])
    return [m.group(1)] if m else []


def PINS_JSON(text, checked):
    entry = next(e for e in json.loads(text)["files"] if e["id"] == "gen1recomp")
    m = RELEASE_ZIP.search(entry["url"])
    return [entry["version"]] + (list(m.groups()) if m else [f"url {entry['url']}"])


PINS = [
    ("gen1-bridge/lib/kb_engine.lua", CODE),
    ("gen1-bridge/export/kb_swapsites.lua", ALL),
    ("gen1-bridge/manifest.json", JSON_RANGE),
    ("gen1-bridge/mod.card", ALL),
    ("tools/lua/fake_engine.lua", ALL),
    ("tools/lua/fake_center.lua", ALL),
    ("tools/lua/sandbox.lua", ALL),
    ("tools/lua/fake_mod.lua", ALL),
    ("tools/lua/test_lua_sandbox_main.py", FOILS),
    ("tools/lua/test_lua_session.py", FOILS),
    ("tools/lua/test_lua_export.py", ALL),
    ("tools/e2e/test_handshake.py", ALL),
    ("tools/e2e/test_s5_ready.py", ALL),
    ("tools/e2e/test_crash.py", ALL),
    ("tools/e2e/test_export.py", ALL),
    ("tools/e2e/README.md", ALL),
    ("tools/installer/pins.json", PINS_JSON),
    ("docs/VERSIONS.md", ROW),
    ("INSTALL.md", ALL),
    ("CLAUDE.md", LINE(r"\*\*The install:\*\*")),
]


def test_engine_checked_is_a_release():
    checked = _checked()
    assert re.fullmatch(r"0\.3\.\d+", checked)
    assert f"Engine.TESTED = Engine.CHECKED" in _read(ENGINE), "hello.ok.tested comes from the same pin"


@pytest.mark.parametrize("path, rule", PINS, ids=[path for path, _ in PINS])
def test_pin_agrees_with_engine_checked(path, rule):
    checked = _checked()
    found = rule(_read(path), checked)
    assert found, f"{path}: no version literal where the pin should be (rule {rule.__name__})"
    stray = sorted(set(found) - {checked})
    assert not stray, f"{path}: {', '.join(stray)} should be {checked} (rule {rule.__name__})"
