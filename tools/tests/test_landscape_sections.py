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
