"""Ship furniture is complete, grounded, joined, and metadata-preserving."""
import json
from pathlib import Path
import sys
import numpy as np
import pytest

ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_ship as ship


@pytest.fixture(scope='module')
def authored():
    if not ship.BASELINE.exists():pytest.skip('local source export required')
    table=json.loads(ship.BASELINE.read_text());art=ship.EmeraldArt(ship.BUNDLE)
    return table,art,ship.generate(table,art)


def test_complete_ship_decisions_and_matching_metadata(authored):
    table,art,updates=authored
    assert len(ship.decisions())==108 and len(updates)==64
    rows=ship.rows_for(ship.GROUP)
    assert set(updates)=={rows[i-1]['key'] for i,(s,*_) in ship.decisions().items() if s=='changed'}
    for key,new in updates.items():
        old=table['cells'][key]
        assert {k:v for k,v in old.items() if k not in ('v','t','up','from')}=={k:v for k,v in new.items() if k not in ('v','t','up','from')}


def test_deck_holes_are_horizontal_and_do_not_become_raised_blocks(authored):
    table,art,updates=authored;rows=ship.rows_for(ship.GROUP)
    for i in ship.HOLES:
        occ,paint=ship.decode(updates[rows[i-1]['key']])
        assert occ[0].all() and not occ[1:].any()
        assert np.array_equal(paint[0],np.arange(256,dtype=np.uint8).reshape(16,16))


def test_bin_has_an_open_mouth_and_grounded_body(authored):
    table,art,updates=authored;rows=ship.rows_for(ship.GROUP)
    for i in ship.BINS:
        occ,_=ship.decode(updates[rows[i-1]['key']])
        assert occ[0].any() and occ[8,8,8] and not occ[9:13,8,8].any()
        assert occ[11,8,3] and occ[11,8,12]


def test_complete_beds_have_under_mattress_air_and_one_clean_pillow():
    for orientation in ('left','right','blue'):
        m=ship.bed(orientation);x=21 if orientation=='left' else 10
        assert not m.occ[2,16,x] and m.occ[7,16,x]
        assert m.occ[9,7,x] and not m.occ[9,20,x]
        assert m.occ[0].any()


def test_long_table_has_only_corner_legs_and_continuous_lid():
    m=ship.dining_table()
    assert m.occ[12,16,2:62].all()
    assert not m.occ[:8,:,16:48].any()
    assert m.occ[0,5,5] and m.occ[0,25,58]


def test_cargo_has_no_accidental_through_holes(authored):
    table,art,updates=authored;rows=ship.rows_for(ship.GROUP)
    for i in ship.CARGO:
        occ,_=ship.decode(updates[rows[i-1]['key']])
        assert occ.all()


def test_actual_map_assemblies_have_recorded_occurrences():
    path=ship.OUT/'assembly-placements.json'
    if not path.exists():pytest.skip('assembly preview evidence required')
    rows=json.loads(path.read_text())
    assert len(rows)==16 and all(row['occurrences'] for row in rows)
