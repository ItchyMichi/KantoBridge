"""The landscape sections pilot's table from made-up marks (tools/landscape_sections/table.py)."""
import pytest

from landscape_sections.table import MarkError, counts, table_of

# A made-up map 4 x 3 cells: region 0 a cliff across the top row, region 1 a rock, the rest walkable.
REGIONS = {"w": 4, "h": 3, "grid": [0, 0, 0, 0,
                                    -1, 1, -1, -1,
                                    -1, -1, -1, -1],
           "regions": [{"id": 0}, {"id": 1}]}


def test_sections_take_their_regions_cells_in_their_boxes():
    marks = {"sections": [{"kind": "mouth", "region": 0, "boxes": [[1, 0, 2, 0]]},
                          {"kind": "face", "region": 0, "boxes": [[0, 0, 3, 2]], "top": [[0, 0, 0, 0]], "rows": 6},
                          {"kind": "objects", "region": 1, "boxes": [[0, 0, 3, 2]]}]}
    entry = table_of(REGIONS, marks)
    assert entry["cells"] == [1, 0, 0, 1,
                              -1, 2, -1, -1,
                              -1, -1, -1, -1], "a cell in two sections' boxes is the first's; only the region's cells"
    assert entry["top"][0] == 1 and sum(entry["top"]) == 1
    assert entry["sections"][1] == {"kind": "face", "rows": 6}
    assert counts(REGIONS, entry) == {"face": 2, "mass": 0, "objects": 1, "trees": 0, "mouth": 2, "building": 0, "default": 0,
                                      "top": 1}


def test_each_box_of_objects_is_a_section():
    marks = {"sections": [{"kind": "objects", "region": 0, "boxes": [[0, 0, 0, 0], [2, 0, 3, 0]]},
                          {"kind": "face", "region": 0, "boxes": [[1, 0, 1, 0]]}]}
    entry = table_of(REGIONS, marks)
    assert [e["kind"] for e in entry["sections"]] == ["objects", "objects", "face"]
    assert entry["cells"][:4] == [0, 2, 1, 1]


def test_a_cell_left_out_keeps_the_default():
    entry = table_of(REGIONS, {"sections": [{"kind": "face", "region": 0, "boxes": [[0, 0, 1, 0]]}]})
    assert counts(REGIONS, entry)["default"] == 3


@pytest.mark.parametrize("section", [{"kind": "cliff", "region": 0, "boxes": []},
                                     {"kind": "face", "region": 7, "boxes": []},
                                     {"kind": "face", "region": 0, "boxes": [[0, 0, 4, 0]]}])
def test_a_malformed_mark_is_refused(section):
    with pytest.raises(MarkError):
        table_of(REGIONS, {"sections": [section]})


def test_marked_slopes_go_in_the_table(tmp_path):
    from landscape_sections.slopes_table import add_slopes

    (tmp_path / "ROUTE.json").write_text('{"slopes": [[6, 55, "up"], [6, 54, "up"]], "levels": [[1, 1, 2]]}')
    (tmp_path / "CHECKED.json").write_text('{"slopes": []}')
    table = add_slopes({"maps": {}}, str(tmp_path))
    assert table["slopes"] == {"CHECKED": [], "ROUTE": [[6, 54, "up"], [6, 55, "up"]]}


def test_a_slope_without_a_way_up_is_refused(tmp_path):
    from landscape_sections.slopes_table import add_slopes

    (tmp_path / "ROUTE.json").write_text('{"slopes": [[6, 55, "north"]]}')
    with pytest.raises(ValueError):
        add_slopes({"maps": {}}, str(tmp_path))


# A made-up map 4 x 2 cells for the levels pipeline's tools: a rock at (3, 0), a ledge of ground 6 blocks up along the top
# row, the floor below; the computer climbs it on a ramp at (1, 1).
DUMP = {"w": 4, "kinds": "...#....", "levels": [6, 6, 6, 0, 0, 0, 0, 0], "ramps": [[1, 1, "up", 3]]}


def test_the_marks_meet_the_computer_where_most_cells_agree():
    from landscape_sections.compare_marks import compare

    # the user counts from the floor's half cell above it: the floor 1, the ledge 5; a slope on the ramp; the rock marked too
    marks = {"view": [0, 0, 4, 2], "levels": [[0, 0, 5], [1, 0, 5], [2, 0, 3], [3, 0, 9], [0, 1, 1], [2, 1, 1], [3, 1, 1]],
             "slopes": [[1, 1, "up"]]}
    r = compare(marks, DUMP)
    assert r["offset"] == -1, "the computer's level of the user's 0, in half cells"
    assert (r["agree"], r["total"]) == (5, 6), "rock and slopes aren't compared"
    assert r["differ"] == [(2, 0, 2, 4, ".")]
    assert r["rows"][0] == (0, "4428", "444#") and r["rows"][1] == (1, "0^00", "0^00")
    assert compare(marks, DUMP, offset=0)["agree"] == 0


def test_the_tiles_under_the_arrows_name_the_stairs():
    from landscape_sections.stair_tiles import emerald_key, under_arrows

    keys = [7, 7, 9, 1,
            7, 9, 9, 1]
    marks = {"view": [0, 0, 4, 2], "levels": [[1, 0, 2], [0, 1, 0]], "slopes": [[0, 0, "up"], [2, 0, "up"], [1, 1, "left"], [2, 1, "up"]]}
    found = under_arrows(marks, 4, keys)
    assert found == [{"tile": 9, "arrows": {"up": 2, "left": 1}, "cells": 3, "slopes": 3, "ground": 0},
                     {"tile": 7, "arrows": {"up": 1}, "cells": 3, "slopes": 1, "ground": 2}]
    assert emerald_key("general__fortree", 175) == "general/175" and emerald_key("general__fortree", 600) == "fortree/600"


def test_the_maps_chain_at_their_seams_and_a_loop_left_open_shows(tmp_path):
    import json

    from landscape_sections.seams import chain, load, report, seams

    # four maps of 3 x 3 cells (2 blocks a cell) in a square: A climbs 3 blocks to its east edge's two upper cells, so going
    # A -> B -> D lifts D by 3 while A -> C -> D doesn't: the loop can't close; B's rock cell on its south edge pairs with
    # nothing, so B | D has the fewest pairs and is the seam left over
    levels = {"A": [0, 0, 3, 0, 0, 3, 0, 0, 0], "B": [0] * 9, "C": [0] * 9, "D": [0] * 9}
    kinds = {"A": "." * 9, "B": ".......#.", "C": "." * 9, "D": "." * 9}
    at = {"A": (0, 0), "B": (6, 0), "C": (0, 6), "D": (6, 6)}
    placements = []
    for m, (x, z) in at.items():
        placements.append({"map": m, "x": x, "y": 64, "z": z, "bw": 6, "bd": 6, "w": 3, "h": 3, "sky": False})
        (tmp_path / f"{m}.json").write_text(json.dumps({"w": 3, "h": 3, "kinds": kinds[m], "levels": levels[m]}))
    (tmp_path / "placements_test.json").write_text(json.dumps(placements))
    maps = load(str(tmp_path), "test")
    found = seams(maps)
    assert sorted(found) == [("A", "B"), ("A", "C"), ("B", "D"), ("C", "D")]
    assert sorted(d for d, _, _, _ in found[("A", "B")]) == [-3, -3, 0] and len(found[("B", "D")]) == 2, "rock meets nothing"
    bases, tree = chain(maps, found)
    assert tree == [("A", "B"), ("A", "C"), ("C", "D")], "the seams with the most pairs first"
    assert bases == {"A": 0, "B": 3, "C": 0, "D": 0}
    rows = {(r["a"], r["b"]): r for r in report(maps, found, bases, tree)}
    assert rows[("B", "D")]["loop"] and rows[("B", "D")]["chained"] == {-3: 2}, "the loop is 3 blocks open at B | D"
    assert rows[("A", "B")]["chained"] == {0: 2, 3: 1}, "A's lower east cell still steps"


def test_a_reviews_pins_go_in_the_table_in_blocks(tmp_path):
    from landscape_sections.pins_table import add_pins

    (tmp_path / "TOWN.json").write_text('{"floor": 12, "pins": [[5, 4, 0], [3, 9, 6]], "slopes": []}')
    table = add_pins({"maps": {}}, str(tmp_path))
    assert table["pins"] == {"TOWN": [[3, 9, 21], [5, 4, 12]]}, "half cells over the view's floor, 1.5 blocks each"
    (tmp_path / "TOWN.json").write_text('{"floor": 0, "pins": [[1, 1, 3]]}')
    with pytest.raises(ValueError):
        add_pins({"maps": {}}, str(tmp_path))
