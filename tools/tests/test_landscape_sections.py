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


def test_a_review_in_blocks_pins_its_own_numbers(tmp_path):
    from landscape_sections.pins_table import add_pins

    (tmp_path / "ROUTE.json").write_text('{"unit": "blocks", "floor": 3, "pins": [[2, 2, 1], [4, 4, 7]]}')
    assert add_pins({"maps": {}}, str(tmp_path))["pins"] == {"ROUTE": [[2, 2, 4], [4, 4, 10]]}, "a ledge's block shows"


def test_the_reviews_go_in_the_table_and_name_the_stairs(tmp_path):
    import json

    from landscape_sections.apply_reviews import apply, stairs

    reviews, dumps = tmp_path / "reviews", tmp_path / "dumps"
    reviews.mkdir()
    dumps.mkdir()
    # a Crystal map 3 x 2: a stair drawing "s" twice, both marked; a ground drawing "g" marked once of three times
    (dumps / "G2_TOWN.json").write_text(json.dumps({"w": 3, "h": 2, "kinds": "......"}))
    (dumps / "G2_TOWN_keys.json").write_text(json.dumps({"keys": ["s", "g", "g", "s", "g", "x"]}))
    (reviews / "G2_TOWN.json").write_text(json.dumps({"unit": "blocks", "floor": 0, "pins": [],
                                                      "slopes": [[0, 0, "up"], [0, 1, "up"], [1, 0, "up"]]}))
    (reviews / "EM_ROUTE.json").write_text(json.dumps({"unit": "blocks", "floor": 3, "pins": [[1, 1, 3]], "slopes": []}))
    (reviews / "EM_BAD.json").write_text('{"unit": "blocks", "pins": [[1, 1]]}')
    table, done = apply(str(reviews), {"maps": {}})
    assert table["pins"] == {"EM_ROUTE": [[1, 1, 6]]}
    assert table["slopes"] == {"G2_TOWN": [[0, 0, "up"], [0, 1, "up"], [1, 0, "up"]]}, "a map with no slopes left out"
    assert "EM_BAD" not in done
    rows = {r["key"]: r for r in stairs(str(reviews), str(dumps))}
    assert rows["s"]["candidate"] and rows["s"]["way"] == "up" and rows["s"]["cells"] == 2
    assert not rows["g"]["candidate"], "marked once of three: plain ground"


def test_the_rules_check_sends_back_only_the_maps_whose_review_no_longer_holds(tmp_path):
    import json

    from landscape_sections.rules_check import check, pinned

    old, raw, pin = tmp_path / "old", tmp_path / "raw", tmp_path / "pin"
    for d in (old, raw, pin):
        d.mkdir()

    def dump(d, name, levels):
        (d / f"{name}.json").write_text(json.dumps({"w": 4, "h": 2, "kinds": "..#...#.", "levels": levels, "ramps": [],
                                                    "outcome": "raised", "breaks": []}))

    # A: the rules leave it; its pin lifts the right strip.  B: the rules alone do what the pin did.  C: neither.
    dump(old, "A", [0, 0, 0, 0, 0, 0, 0, 0])
    dump(raw, "A", [0, 0, 0, 0, 0, 0, 0, 0])
    dump(pin, "A", [0, 0, 0, 3, 0, 0, 0, 3])
    dump(old, "B", [0, 0, 0, 0, 0, 0, 0, 0])
    dump(raw, "B", [3, 3, 3, 6, 3, 3, 3, 6])
    dump(pin, "B", [3, 3, 3, 6, 3, 3, 3, 6])
    dump(old, "C", [0, 0, 0, 0, 0, 0, 0, 0])
    dump(raw, "C", [0, 0, 0, 0, 3, 3, 0, 0])
    dump(pin, "C", [0, 0, 0, 3, 3, 3, 0, 3])
    # the rock column x 2 parts the right strip: the pin moves only it
    assert pinned({"w": 4, "h": 2, "kinds": "..#...#.", "levels": [0] * 8}, [[3, 0, 3]]) == [0, 0, 0, 3, 0, 0, 0, 3]
    table = {"pins": {"A": [[3, 0, 3]], "B": [[3, 0, 3]], "C": [[3, 0, 3]]}}
    verdicts = {r["map"]: r["verdict"] for r in check(str(old), table, str(raw), str(pin))}
    assert verdicts == {"A": "same", "B": "fixed", "C": "look"}


def test_a_stair_goes_with_the_ground_at_its_ends():
    from landscape_sections.rules_check import loose_ends, pinned_ramps

    # a step at (1, 1) climbing north from the strip at 0 to the strip at 3, rock either side of it
    dump = {"w": 3, "h": 3, "kinds": "..." "#.#" "...", "levels": [3, 3, 3, 0, 0, 0, 0, 0, 0], "ramps": [[1, 1, "up", 3]]}
    levels, ramps = pinned_ramps(dump, [[0, 0, 9], [0, 2, 6]])
    assert levels[4] == 6 and ramps == [[1, 1, "up", 3]], "both ends 6 up: the step with them"
    levels, ramps = pinned_ramps(dump, [[0, 0, 0]])
    assert levels[4] == 0 and ramps == [], "its ends level: no step"
    levels, ramps = pinned_ramps(dump, [[0, 0, 6]])
    assert levels[4] == 0 and ramps == [[1, 1, "up", 3]], "one end moved: the step stays"
    assert loose_ends(dict(dump, levels=levels)) == [[1, 1, "top"]], "its top off the ground"
    assert loose_ends(dump) == []


def test_a_pin_stops_at_the_dumps_walls():
    from landscape_sections.rules_check import pinned

    # two strips walled apart along their whole edge (south sides of row 0, north sides of row 1)
    dump = {"w": 3, "h": 2, "kinds": "......", "levels": [0] * 6, "ramps": [], "walls": [4, 4, 4, 1, 1, 1]}
    assert pinned(dump, [[1, 0, 3]]) == [3, 3, 3, 0, 0, 0]
    assert pinned(dict(dump, walls=None), [[1, 0, 3]]) == [3] * 6, "no walls: one ground"


def test_the_face_drawings_are_the_solid_runs_between_ground():
    from landscape_sections.face_drawings import face_drawings

    # a fence row (key 1) between two strips of ground, a run of four (key 2) that is too tall, water beyond rock (key 3)
    survey = {"game": "g", "keys": ["ground", "fence", "wall", "rock"], "maps": [{
        "map": "M", "w": 2, "h": 9, "outdoor": True,
        "kind": "WW" "SS" "WW" "SS" "SS" "SS" "SS" "WW" "WW",
        "why": ["walk"] * 18,
        "cell": [0, 0, 1, 1, 0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 0, 0, 0, 0]}]}
    rows = face_drawings(survey)
    assert [(r["key"], r["n"]) for r in rows] == [("fence", 2)]
    assert rows[0]["ex"] == ["M", 0, 1]


def test_the_second_look_shows_what_the_first_review_meant_and_tries_new_pins(tmp_path, capsys):
    import json

    from landscape_sections.second_look import second, try_pins

    # the old levels with the first review's pin lifting the right strip (over the rock column x 2) to 3; the new rules
    # move the map's floor up by 3 and lift only the strip's top cell, the moved pin the strip again
    old = {"map": "M", "w": 4, "h": 2, "kinds": "..#...#.", "levels": [0] * 8, "ramps": []}
    raw = {"map": "M", "w": 4, "h": 2, "kinds": "..#...#.", "levels": [3, 3, 3, 6, 3, 3, 3, 3], "ramps": []}
    pin = {"map": "M", "w": 4, "h": 2, "kinds": "..#...#.", "levels": [3, 3, 3, 6, 3, 3, 3, 3], "ramps": []}
    facts = second(old, [[3, 0, 3]], raw, pin, [[3, 1, 9]])
    assert facts["floor"] == 3 and facts["pins_now"] == [[3, 1, 6]], "the new table's pin in blocks over the pictures' floor"
    assert facts["intent"] == [0, 0, None, 3, 0, 0, None, 3]
    assert facts["differ"] == [[3, 1, 0, 3]], "the strip's lower cell stands under what the review meant"
    assert "intent" not in second(None, None, raw, pin, []), "a map nobody reviewed has no intent"
    (tmp_path / "raw.json").write_text(json.dumps(raw))
    (tmp_path / "review.json").write_text(json.dumps({"floor": 3, "pins": [[3, 1, 3]], "slopes": []}))
    try_pins([str(tmp_path / "raw.json"), str(tmp_path / "review.json")])
    out = capsys.readouterr().out
    assert "   1    0   0   #   3" in out and "1 pins changed 1 cells" in out, out
    # beside the second look's facts, a cell off what the first review meant is starred
    (tmp_path / "M_raw.json").write_text(json.dumps(raw))
    (tmp_path / "M_second.json").write_text(json.dumps(facts))
    (tmp_path / "none.json").write_text(json.dumps({"floor": 3, "pins": [], "slopes": []}))
    try_pins([str(tmp_path / "M_raw.json"), str(tmp_path / "none.json")])
    out = capsys.readouterr().out
    assert "   1     0    0    #   0*" in out and "1 cells (*) differ" in out, out


def test_a_second_looks_lists_replace_the_first(tmp_path):
    import json

    from landscape_sections.apply_reviews import apply

    reviews = tmp_path / "r2"
    reviews.mkdir()
    (reviews / "EM_A.json").write_text(json.dumps({"map": "EM_A", "unit": "blocks", "floor": 3, "pins": [], "slopes": [], "notes": ""}))
    (reviews / "EM_B.json").write_text(json.dumps({"map": "EM_B", "unit": "blocks", "floor": 0, "pins": [[1, 1, 6]],
                                                   "slopes": [[2, 2, "up"]], "notes": ""}))
    table = {"pins": {"EM_A": [[0, 0, 9]], "EM_B": [[5, 5, 3]], "EM_C": [[1, 1, 3]]}, "slopes": {"EM_A": [[0, 1, "up"]]}}
    out, done = apply(str(reviews), table, replace=True)
    assert "EM_A" not in out["pins"] and "EM_A" not in out["slopes"], "a second look with no pins and no slopes clears them"
    assert out["pins"]["EM_B"] == [[1, 1, 6]] and out["slopes"]["EM_B"] == [[2, 2, "up"]]
    assert out["pins"]["EM_C"] == [[1, 1, 3]], "a map with no second look keeps its pins"
