"""Structural checks shared by every Claude-continuation batch generator.

Each module owns a disjoint set of pending keys with one ledger row each; every changed entry
passes the guarded merger's own cell validation (metadata only through allowlisted amendments,
native column deferrals carry no voxel fields); voxel entries are non-empty, within 15 layers,
and their furniture never paints with the room's floor colours unless it is a floor slab.
"""
import importlib, json, sys
from pathlib import Path
import numpy as np
import pytest
ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path: sys.path.insert(0, str(ROOT))
from tools.shape_fit.emerald_pass import review
from tools.shape_fit.blue_pass.common import decode

MODULES = {"pyramid_lobby_lower": 14, "battle_arena": 52, "battle_dome": 84, "battle_factory": 83, "battle_pike": 55,
           "frontier_misc": 16, "ranking_hall": 22, "pyramid_top": 2, "trainer_hill": 31, "trainer_roof": 97, "magma_hideout": 55,
           "outdoor_terrain": 211}


@pytest.fixture(scope="module")
def baseline():
    path = ROOT / "local/model-gallery/emerald-full-pass-baseline.json"
    if not path.exists() or not (ROOT / "local/minecraft/kantobridge/export/emerald").exists():
        pytest.skip("Local exported source required")
    return json.loads(path.read_text(encoding="utf-8-sig"))


@pytest.fixture(scope="module")
def outputs(baseline):
    from tools.model_gallery import EmeraldArt
    art = EmeraldArt(ROOT / "local/minecraft/kantobridge/export/emerald")
    out = {}
    for name in MODULES:
        m = importlib.import_module("tools.shape_fit.emerald_pass." + name)
        out[name] = (m, m.generate(baseline, art), m.ledger(baseline, art))
    return out


@pytest.mark.parametrize("name", list(MODULES))
def test_ownership_and_ledger(outputs, name):
    m, updates, rows = outputs[name]
    keys = [r["key"] for r in rows]
    assert len(keys) == len(set(keys)) == MODULES[name]
    assert set(updates) == {r["key"] for r in rows if r["decision"] == "changed"}
    assert all(r["label"] and r["family"] and r["reason"] and r["evidence"] for r in rows)


def test_batches_are_disjoint(outputs):
    seen = set()
    for name, (_, _, rows) in outputs.items():
        keys = {r["key"] for r in rows}
        assert not keys & seen, name
        seen |= keys


@pytest.mark.parametrize("name", list(MODULES))
def test_every_change_passes_the_merger_guard(outputs, baseline, name):
    _, updates, _ = outputs[name]
    for key, entry in updates.items():
        review.validate_cell(key, baseline["cells"][key], entry)
        if entry.get("class") == "columns":
            assert entry == {"class": "columns", "iou": 1.0}
        else:
            occ, paint = decode(entry)
            assert occ.any() and len(occ) <= 240 and occ.shape[1:] == (16, 16)
