"""Semantic checks for the reviewed Blue props authoring pass."""
import copy

import numpy as np
import pytest

from model_gallery import BlueArt, DEFAULT_BUNDLE
from shape_fit.blue_pass.common import BASELINE, decode, load_baseline, source_shades
from shape_fit.blue_pass.props import generate, specifications


@pytest.fixture(scope="module")
def authored():
    if not BASELINE.exists() or not DEFAULT_BUNDLE.exists():
        pytest.skip("local exported Blue source art and review baseline are required")
    baseline=load_baseline()
    art=BlueArt(DEFAULT_BUNDLE)
    before=copy.deepcopy(baseline)
    updates,ledger=generate(baseline,art)
    assert baseline==before, "authoring must not mutate the preserved baseline"
    return baseline,art,updates,ledger


def test_all_164_decisions_are_explicit_and_deterministic(authored):
    baseline,art,updates,ledger=authored
    assert len(ledger)==164
    assert {r["key"] for r in ledger}==set(specifications())
    assert {r["key"] for r in ledger if r["decision"]=="changed"}==set(updates)
    assert all(r["label"] and r["reason"] for r in ledger)
    assert generate(baseline,art)==(updates,ledger)


def test_protected_console_and_architecture_remain_identical(authored):
    baseline,_,updates,ledger=authored
    assert "tilesets.reds_house/14-15-30-31" not in updates
    for row in ledger:
        if "building" in row["reason"]:
            assert row["decision"]=="retained"
            assert row["key"] not in updates
    assert baseline["cells"]["tilesets.reds_house/14-15-30-31"]["class"]=="voxels"


def test_interaction_flags_are_preserved_except_authorised_bed_fix(authored):
    baseline,_,updates,_=authored
    for key,entry in updates.items():
        original=baseline["cells"][key]
        for flag in ("on","walk","seat"):
            if key=="tilesets.facility/40-41-56-57" and flag=="on":
                assert flag in original and flag not in entry
            else:
                assert entry.get(flag)==original.get(flag)


def test_replacement_pot_and_stacked_fixtures_have_supported_seams(authored):
    _,_,updates,_=authored
    empty_before="tilesets.reds_house/70-71-24-25"
    pot,_=decode(updates[empty_before])
    assert pot[0].any() and pot[15].any()
    for key,entry in updates.items():
        below=entry.get("on")
        if below in updates:
            upper,_=decode(entry);lower,_=decode(updates[below])
            assert (upper[0]&lower[15]).any(), f"unsupported stacked seam: {key}"


@pytest.mark.parametrize("atlas,head,foot",[
    ("reds_house","45-46-61-62","61-62-63-47"),
    ("mansion","45-46-61-62","61-62-63-47"),
    ("facility","40-41-56-57","56-57-25-26"),
])
def test_beds_have_low_connected_mattress_and_grounded_legs(authored,atlas,head,foot):
    _,_,updates,_=authored
    a,_=decode(updates[f"tilesets.{atlas}/{head}"])
    b,_=decode(updates[f"tilesets.{atlas}/{foot}"])
    assert (a[:,15,:]&b[:,0,:]).sum()>=48
    assert a[0].any() and b[0].any()
    assert not a[14:].any() and not b[10:].any()
    assert a[5:8,:,2:14].all() and b[5:8,:,2:14].all()


def test_table_parts_match_retained_neighbour_height(authored):
    baseline,_,updates,_=authored
    for key,(_,family,_) in specifications().items():
        if family=="table":
            occ,_=decode(updates[key])
            assert occ[6:8].all()
            assert not occ[8:].any()
    retained,_=decode(baseline["cells"]["tilesets.lab/64-65-80-81"])
    assert retained[7].any() and not retained[8:].any()


def test_floor_checkers_do_not_colour_plant_or_bicycle_parts(authored):
    _,art,updates,_=authored
    for key,(_,family,_) in specifications().items():
        if family in ("foliage","pot","bicycle"):
            occ,paint=decode(updates[key]);shades=source_shades(art,key)
            used=shades[paint[occ]//16,paint[occ]%16]
            assert 1 not in set(used), f"floor checker colour leaked into {key}"


def test_bicycle_has_open_wheels_and_transverse_parts(authored):
    _,_,updates,_=authored
    a,_=decode(updates["tilesets.club/11-12-27-28"])
    b,_=decode(updates["tilesets.club/14-31-9-15"])
    whole=np.concatenate((a,b),axis=2)
    assert (a[:,:,15]&b[:,:,0]).any()
    # Wheel centres have open quadrants, not filled sprite silhouettes.
    assert not whole[3,7,4] and not whole[3,7,24]
    assert whole[:,4,:].any() and whole[:,11,:].any()


def test_transporter_cells_are_grounded_and_join_across_both_axes(authored):
    _,_,updates,_=authored
    keys=[["32-23-48-39","24-25-40-41","26-37-42-53"],
          ["69-55-31-80","56-57-1-2","58-69-15-31"]]
    cells=[[decode(updates["tilesets.interior/"+k])[0] for k in row] for row in keys]
    for z in range(2):
        for x in range(3):
            assert cells[z][x][0].any()
            if x<2:
                a,b=cells[z][x],cells[z][x+1];h=min(len(a),len(b))
                assert (a[:h,:,-1]&b[:h,:,0]).any()
            if z==0:
                a,b=cells[z][x],cells[1][x];h=min(len(a),len(b))
                assert (a[:h,-1,:]&b[:h,0,:]).any()


def test_floor_pads_and_down_stairs_preserve_native_top_art(authored):
    baseline,_,updates,_=authored
    for key,(_,family,_) in specifications().items():
        if family not in ("floorpad","stairwell"):
            continue
        occ,paint=decode(updates[key])
        assert occ[0].all(), "all 256 source pixels must have a horizontal surface"
        assert not occ[5:].any(), "a descending stair opening must not become a wall"
        if family=="floorpad":
            assert not occ[1:].any()
        assert updates[key].get("walk")==baseline["cells"][key].get("walk")
        for z in range(16):
            for x in range(16):
                top=np.flatnonzero(occ[:,z,x])[-1]
                assert paint[top,z,x]==z*16+x
