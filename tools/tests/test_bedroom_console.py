"""Geometry regressions for the console, its supported controls and floor cable."""
import base64
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("bedroom_console", ROOT / "tools/shape_fit/bedroom_console.py")
console = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(console)


def model():
    entry = console.generate()
    raw, paint = base64.b64decode(entry["v"]), base64.b64decode(entry["t"])
    assert len(raw) == 512 and len(paint) == 4096
    occupied = {(x, y, z) for y in range(16) for z in range(16) for x in range(16)
                if raw[console.index(x, y, z) >> 3] >> (console.index(x, y, z) & 7) & 1}
    return occupied, paint


def test_case_controller_and_cable_form_one_supported_low_object():
    occupied, _ = model()
    assert min(y for _, y, _ in occupied) == 0
    assert max(y for _, y, _ in occupied) <= 4  # console rests flat, not an upright sprite extrusion
    reached = {next(iter(occupied))}
    pending = list(reached)
    while pending:
        x, y, z = pending.pop()
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            neighbour = x + dx, y + dy, z + dz
            if neighbour in occupied and neighbour not in reached:
                reached.add(neighbour)
                pending.append(neighbour)
    assert reached == occupied
    # Feet hold the case above genuine air; the controller itself sits on the floor.
    assert all((x, 0, z) in occupied and (x, 1, z) in occupied for x in (2, 8) for z in (2, 8))
    assert (5, 0, 5) not in occupied and (5, 1, 5) in occupied
    assert (12, 0, 12) in occupied and (12, 1, 12) in occupied
    # The offset sprite shadow has not become a solid slab beneath the cable loop.
    assert (5, 0, 10) not in occupied and (5, 0, 11) not in occupied
    assert (3, 0, 10) in occupied and (5, 0, 12) in occupied


def test_cartridge_opening_is_recessed_geometry_and_controls_are_separate():
    occupied, paint = model()
    for x in range(3, 8):
        for z in (3, 4):
            assert (x, 3, z) in occupied
            assert (x, 4, z) not in occupied
            assert paint[console.index(x, 3, z)] == console.DARK
    assert all(point in occupied for point in ((2, 4, 3), (8, 4, 3), (5, 4, 2), (5, 4, 5)))
    assert (3, 4, 6) in occupied and (7, 4, 6) in occupied
    assert (5, 4, 6) not in occupied  # gap between two raised controls


def test_controller_has_a_complete_supported_dpad_and_three_distinct_buttons():
    occupied, paint = model()
    cross = {(10, 2, 12), (10, 2, 13), (10, 2, 14), (9, 2, 13), (11, 2, 13)}
    assert cross <= occupied
    assert all((x, y - 1, z) in occupied for x, y, z in cross)
    assert all(paint[console.index(x, y, z)] == console.DARK for x, y, z in cross)
    buttons = {(13, 2, 9), (14, 2, 10), (12, 2, 11)}
    assert buttons <= occupied
    assert all((x, y - 1, z) in occupied for x, y, z in buttons)
    assert all(paint[console.index(x, y, z)] in {console.SHADE, console.ACCENT} for x, y, z in buttons)


def test_paint_uses_named_source_detail_texels_and_bundled_model_is_reproducible():
    occupied, paint = model()
    # Addressing contract: these are coordinates, so palette swaps remain possible.
    assert (console.PLASTIC % 16, console.PLASTIC // 16) == (4, 5)
    assert (console.ACCENT % 16, console.ACCENT // 16) == (4, 6)
    assert (console.SHADE % 16, console.SHADE // 16) == (2, 8)
    assert (console.DARK % 16, console.DARK // 16) == (4, 4)
    assert {paint[console.index(x, y, z)] for x, y, z in occupied} == {
        console.PLASTIC, console.ACCENT, console.SHADE, console.DARK}
    table = json.loads(console.TABLE.read_text(encoding="utf-8"))
    assert table["cells"][console.KEY] == console.generate()
