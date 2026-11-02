"""The Crystal pass's guarded merger and the reference module (tools/shape_fit/crystal_pass)."""
import base64
import copy
import json
import sys
from pathlib import Path

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from tools.shape_fit.crystal_pass import review  # noqa: E402


def layer(fill=1):
    occ = np.zeros((16, 16, 16), bool)
    occ[:fill] = True
    return {"v": base64.b64encode(np.packbits(occ.ravel(), bitorder="little").tobytes()).decode(),
            "t": base64.b64encode(bytes(4096)).decode()}


def table():
    return {"game": "crystal", "cells": {
        "g3q/1-2-3-4": {"class": "voxels", "from": "entity", **layer(16)},
        "g3q/5-6-7-8": {"class": "voxels", "from": "entity", "on": "g3q/1-2-3-4", **layer(16)},
    }, "buildings": [
        {"id": "crystal_b010_G2_ECRUTEAK_CITY", "atlas": "g3q", "tiles": [[0] * 8] * 6, "height": 46, "parts": [], "terrain": []},
        {"id": "crystal_b999_G2_TEST", "atlas": "g3q", "tiles": [[0] * 8] * 4, "roofRows": 10, "roof": "flat", "bands": []},
    ]}


def row(base, kind, key, decision):
    old = base["cells"][key] if kind == "cell" else {b["id"]: b for b in base["buildings"]}[key]
    return {"kind": kind, "key": key, "label": "x", "family": "f", "reason": "r", "evidence": ["e"],
            "decision": decision, "baseline_entry_sha256": review.entry_hash(old)}


def test_changed_cell_keeps_metadata_and_merges():
    base = table()
    new = copy.deepcopy(base["cells"]["g3q/1-2-3-4"])
    new.update(layer(4), **{"from": "hand-crystal-test"})
    result, report = review.merge(base, [{"cells": {"g3q/1-2-3-4": new}}], [[row(base, "cell", "g3q/1-2-3-4", "changed")]])
    assert result["cells"]["g3q/1-2-3-4"] == new
    assert report["counts"] == {"changed": 1, "pending": 3}


def test_metadata_change_without_amendment_is_refused():
    base = table()
    new = copy.deepcopy(base["cells"]["g3q/5-6-7-8"])
    new.pop("on")
    with pytest.raises(ValueError, match="metadata"):
        review.merge(base, [{"cells": {"g3q/5-6-7-8": new}}], [[row(base, "cell", "g3q/5-6-7-8", "changed")]])


def test_stack_amendment_allows_exactly_its_change(monkeypatch):
    base = table()
    new = copy.deepcopy(base["cells"]["g3q/5-6-7-8"])
    new.pop("on")
    monkeypatch.setitem(review.AMENDMENTS, "stacks", {"g3q/5-6-7-8": {"before": {"on": "g3q/1-2-3-4"}, "after": {}}})
    result, _ = review.merge(base, [{"cells": {"g3q/5-6-7-8": new}}], [[row(base, "cell", "g3q/5-6-7-8", "changed")]])
    assert "on" not in result["cells"]["g3q/5-6-7-8"]
    new["seat"] = True  # anything beyond the amendment is still refused
    with pytest.raises(ValueError):
        review.merge(base, [{"cells": {"g3q/5-6-7-8": new}}], [[row(base, "cell", "g3q/5-6-7-8", "changed")]])


def test_empty_geometry_stale_ledger_and_duplicates_are_refused():
    base = table()
    empty = dict(base["cells"]["g3q/1-2-3-4"], **layer(0))
    with pytest.raises(ValueError, match="empty"):
        review.merge(base, [{"cells": {"g3q/1-2-3-4": empty}}], [[row(base, "cell", "g3q/1-2-3-4", "changed")]])
    stale = row(base, "cell", "g3q/1-2-3-4", "retained")
    stale["baseline_entry_sha256"] = "0" * 64
    with pytest.raises(ValueError, match="stale"):
        review.merge(base, [], [[stale]])
    with pytest.raises(ValueError, match="duplicate"):
        review.merge(base, [], [[row(base, "cell", "g3q/1-2-3-4", "retained")], [row(base, "cell", "g3q/1-2-3-4", "retained")]])
    with pytest.raises(ValueError, match="disagrees"):
        review.merge(base, [], [[row(base, "cell", "g3q/1-2-3-4", "changed")]])


def test_protected_trial_and_building_identity():
    base = table()
    pilot = dict(base["buildings"][0], height=50)
    with pytest.raises(ValueError, match="pilot"):
        review.merge(base, [{"buildings": {pilot["id"]: pilot}}], [[row(base, "building", pilot["id"], "changed")]])
    old = base["buildings"][1]
    new = {k: v for k, v in old.items() if k not in ("roofRows", "roof", "bands")}
    new.update(height=24, terrain=[], parts=[{"shape": "box", "x": [0, 64], "z": [0, 32], "y": [0, 24],
                                             "paint": {"front": [0, 0, 64, 32]}}])
    result, _ = review.merge(base, [{"buildings": {old["id"]: new}}], [[row(base, "building", old["id"], "changed")]])
    assert result["buildings"][1] == new
    moved = dict(new, tiles=[[1] * 8] * 4)
    with pytest.raises(ValueError, match="identity"):
        review.merge(base, [{"buildings": {old["id"]: moved}}], [[row(base, "building", old["id"], "changed")]])
    outside = dict(new, parts=[dict(new["parts"][0], x=[0, 65])])
    with pytest.raises(ValueError, match="footprint"):
        review.merge(base, [{"buildings": {old["id"]: outside}}], [[row(base, "building", old["id"], "changed")]])


def test_require_complete_reports_pending():
    with pytest.raises(ValueError, match="semantic review"):
        review.merge(table(), [], [], require_complete=True)


@pytest.fixture(scope="module")
def reference():
    from tools.shape_fit.crystal_pass import common
    if not common.BASELINE.exists() or not common.BUNDLE.exists():
        pytest.skip("local Crystal export and pass baseline required")
    from tools.shape_fit.crystal_pass import players_house as ph
    return common, ph


def test_reference_bed_is_one_flat_object_and_bookcase_stacks(reference):
    common, ph = reference
    parts = ph.bed()
    head, foot = parts[ph.BED_HEAD][0], parts[ph.BED_FOOT][0]
    assert head[:9].any() and foot[:9].any() and not head[12:].any() and not foot[9:].any()
    assert head[3:7, 15, 2:14].all() and foot[3:7, 0, 2:14].all(), "the mattress crosses the seam"
    cases = ph.bookcase(ph.CASE_BASE_A, ph.CASE_TOP_A)
    base_occ, top_occ = cases[ph.CASE_BASE_A][0], cases[ph.CASE_TOP_A][0]
    assert base_occ[15].any() and top_occ[0].any() and not top_occ[12:].any()
    assert not base_occ[10:15, 13:15, 3:13].any(), "the shelf is a real recess"


def test_reference_paint_is_source_local(reference):
    common, ph = reference
    occ, paint = ph.stove()
    px = common.pixels(ph.STOVE)
    east = {tuple(px[t // 16, t % 16]) for t in paint[1:10, 4:13, 15].ravel()}
    assert occ[1:10, 4:13, 15].all() and east <= {(156, 156, 156), (107, 107, 107)}, "grey sides, not the outline"
    assert int(paint.max()) < 256 and occ.sum() > 0
