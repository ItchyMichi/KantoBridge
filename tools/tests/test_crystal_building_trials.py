"""Structural regressions for the two hand-authored traditional buildings."""
import importlib.util
import json
from pathlib import Path

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[2]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


trial = module("crystal_building_trials", ROOT / "tools/shape_fit/crystal/building_trials.py")
parts = module("crystal_parts", ROOT / "tools/shape_fit/crystal/parts_model.py")


@pytest.fixture(scope="module")
def volumes():
    return {name: parts.build(spec, np.full((48 if name == trial.HOUSE else 80, 64, 3), 150, np.uint8))[0]
            for name, spec in trial.generate().items()}


def connected(volume):
    # Flood the published model independently of the named modelling parts.
    occupied = set(map(tuple, np.argwhere(volume)))
    reached = {next(iter(occupied))}
    pending = list(reached)
    while pending:
        y, z, x = pending.pop()
        for dy, dz, dx in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            p = (y + dy, z + dz, x + dx)
            if p in occupied and p not in reached:
                reached.add(p)
                pending.append(p)
    return reached == occupied


@pytest.mark.parametrize("name", trial.IDS)
def test_building_is_grounded_connected_and_keeps_its_drawing_footprint(name, volumes):
    v = volumes[name]
    assert connected(v)
    assert v[0].any()
    assert v.shape[1:] == ((48 if name == trial.HOUSE else 80), 64)
    assert v[-1].any(), "declared height should end at the actual ridge, not empty layers"


@pytest.mark.parametrize("name,start,back", [(trial.HOUSE, 39, 38), (trial.TOWER, 69, 68)])
def test_original_door_position_has_real_recess_and_headroom(name, start, back, volumes):
    v = volumes[name]
    assert not v[:20, start:, 18:30].any()
    assert v[:20, back, 18:30].all(), "source doorway panel is recessed at the back of the alcove"
    assert v[20:24, start:start + 3, 18:30].all(), "lintel supports the wall over the entry"


def test_house_roof_has_curved_eaves_and_a_raised_ridge(volumes):
    v = volumes[trial.HOUSE]
    top = lambda z: np.flatnonzero(v[:, z, 32])[-1]
    assert top(4) > top(10)
    assert top(47) > top(41)
    assert top(26) > top(4) + 14
    # The eave projects beyond the walls and leaves actual air underneath.
    assert not v[:24, 46, 32].any()
    assert v[24:, 46, 32].any()


def test_pagoda_has_three_distinct_overhanging_tiers(volumes):
    v = volumes[trial.TOWER]
    # Each roof overhangs its own wall; clear bands separate successive roofs.
    for y, outer_x, wall_y in ((25, 3, 10), (59, 7, 45), (93, 11, 80)):
        assert v[y, 50, outer_x]
        assert not v[wall_y, 50, outer_x]
    widths = [int(v[y, 50].sum()) for y in (10, 45, 80)]
    assert widths[0] > widths[1] > widths[2] > 20


def test_uv_rectangles_remain_inside_source_art_and_model_scope_is_exact():
    base = {"game": "crystal", "cells": {"unrelated": {"class": "voxels"}}, "buildings": [
        {"id": name, "atlas": "g3q", "tiles": [[i]], "roofRows": 29, "roof": "flat", "bands": [], "terrain": [[3, 4]]}
        for i, name in enumerate((*trial.IDS, "unrelated"))]}
    result = trial.apply(base)
    assert result["cells"] == base["cells"]
    assert result["buildings"][-1] == base["buildings"][-1]
    for before, after in zip(base["buildings"], result["buildings"]):
        for key in ("id", "atlas", "tiles", "terrain"):
            assert after[key] == before[key]
    for name, spec in trial.generate().items():
        depth = 48 if name == trial.HOUSE else 80
        for part in spec["parts"]:
            for rect in part.get("paint", {}).values():
                x0, y0, x1, y1 = rect
                assert 0 <= x0 < x1 <= 64 and 0 <= y0 < y1 <= depth


def test_bundled_templates_match_reproducible_authoring_rules():
    table = json.loads(trial.TABLE.read_text())
    assert trial.apply(table) == table
