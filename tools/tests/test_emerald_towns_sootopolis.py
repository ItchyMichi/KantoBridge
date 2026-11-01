"""Scope, movement and complete-object checks for the Sootopolis cell batch."""
import copy
import json
from pathlib import Path
import sys

import numpy as np
import pytest

ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import towns_sootopolis as town


@pytest.fixture(scope='module')
def context():
    if not town.BASELINE.exists() or not town.BUNDLE.exists():pytest.skip('Local source export and immutable baseline required')
    return json.loads(town.BASELINE.read_text()),EmeraldArt(town.BUNDLE),town.rows_for(town.GROUP)


def test_complete_inventory_and_only_five_exact_changes(context):
    table,art,rows=context;old=copy.deepcopy(table);updates=town.generate(table,art)
    assert len(town.decisions())==39
    assert set(updates)=={rows[n-1]['key'] for n in (25,31,32,33,39)}
    assert table==old
    for n in (25,31,32,33,39):
        key=rows[n-1]['key'];a={k:v for k,v in table['cells'][key].items() if k not in ('v','t','up','from')};b={k:v for k,v in updates[key].items() if k not in ('v','t','up','from')}
        if n in (25,32,33):a['walk']=True
        assert a==b


def test_crossbar_has_no_floor_and_only_metadata_change(context):
    table,art,rows=context;key=rows[24]['key'];old=table['cells'][key];new=town.generate(table,art)[key]
    assert {k:v for k,v in new.items() if k!='walk'}==old
    occ,_=decode(new)
    assert not occ[:13].any() and occ[13:].any()
    assert [(p['x'],p['y']) for p in rows[24]['occurrences']]==[(30,17),(31,17),(32,17)]


def test_stairs_join_at_three_equal_tread_heights(context):
    _,art,rows=context;a=town.stair(art.cell(rows[31]['key'],'BAKED'),True);b=town.stair(art.cell(rows[32]['key'],'BAKED'))
    assert np.array_equal(a.occ[:,:,15],b.occ[:,:,0])
    assert [int(b.occ[:,z,8].sum()) for z in (0,5,11)]==[12,8,4]
    assert not a.occ[:,0,0].any() and a.occ[:,5,0].any()
    stairs=[e for e in town.assemblies(art) if 'stair' in e['id']]
    assert sum(len(e['layout'][0])*len(e['occurrences']) for e in stairs)==52


def test_markers_are_two_grounded_posts_with_clear_gap(context):
    _,art,rows=context;m=town.markers(art.cell(rows[38]['key'],'BAKED'))
    assert m.occ[0,:,1:7].any() and m.occ[0,:,9:15].any()
    assert not m.occ[:,:,7:9].any()
    assert m.occ[14].any() and m.occ[3,:,3].any()


def test_cliff_only_uses_own_unoccluded_native_pixels(context):
    table,art,rows=context;m=town.cliff_background(art.cell(rows[30]['key'],'BAKED'))
    assert m.occ.all() and int(m.paint.max())<128
    source=np.asarray(art.cell(rows[30]['key'],'BAKED').convert('RGB'))
    colors=source[m.paint//16,m.paint%16]
    assert not np.any(np.all(colors==(82,90,82),axis=-1))
    proof=town.cliff_evidence(art,table)
    assert len(proof['occurrences'])==8 and not proof['pure_reference_has_fitted_override']
