"""Geometry invariants for the reviewed interior furnishings, not image snapshots."""
import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from shape_fit.blue_pass import interiors
from shape_fit.blue_pass.common import decode, load_baseline, BASELINE


@pytest.fixture(scope='module')
def result():
    if not BASELINE.exists():
        pytest.skip('local Blue baseline and exported source art required')
    baseline=load_baseline()
    updates,ledger=interiors.generate(baseline)
    return baseline,updates,ledger


def test_every_assigned_cell_has_an_explicit_review(result):
    baseline,updates,ledger=result
    expected={k for k,v in baseline['cells'].items() if v.get('class')=='voxels' and k.split('/')[0] in {f'tilesets.{a}' for a in interiors.ATLASES}}
    assert len(expected)==173
    assert {r['key'] for r in ledger}==expected
    assert len(ledger)==len(expected)
    assert set(updates)=={r['key'] for r in ledger if r['decision']=='changed'}
    assert all(r['label'] and r['reason'] and r['source_review'] for r in ledger)


def test_no_empty_replacements_and_only_reviewed_metadata_changes(result):
    baseline,updates,_=result
    exceptions={'tilesets.ship/70-71-86-87','tilesets.pokecenter/57-11-60-27'}
    for key,entry in updates.items():
        occ,paint=decode(entry)
        assert occ.any(),key
        assert occ.shape==paint.shape and occ.shape[1:]==(16,16) and occ.shape[0] in (16,32),key
        assert all(occ[y:y+16].any() for y in range(0,len(occ),16)),key
        old=baseline['cells'][key]
        for field in ('seat','walk','on'):
            if field=='on' and key in exceptions:
                assert field not in entry
            else:
                assert entry.get(field)==old.get(field),(key,field)


def test_empty_baseline_cabinets_and_bins_are_repaired(result):
    _,updates,_=result
    cabinet,_=decode(updates['tilesets.lobby/34-35-50-51'])
    bin_,_=decode(updates['tilesets.ship/72-73-88-89'])
    assert cabinet[-1,4:14,:].all()  # load-bearing contact for all three uppers
    assert bin_[0].any() and bin_[11].any() and bin_[:,8,8].any()
    assert bin_[13,8,8]  # dark recessed opening is a closed surface, not a hole to ground


def test_bookcase_and_plant_stacks_have_real_contact(result):
    _,updates,_=result
    for key,entry in updates.items():
        below=entry.get('on')
        if below and below in updates:
            top,_=decode(entry);base,_=decode(updates[below])
            assert np.any(top[0] & base[-1]),(key,below)


def test_horizontal_bed_has_continuous_mattress_and_no_internal_headboard(result):
    _,updates,_=result
    head,_=decode(updates['tilesets.ship/70-71-86-87'])
    for suffix in ('86-87-59-60','86-87-86-87'):
        foot,_=decode(updates['tilesets.ship/'+suffix])
        assert np.array_equal(head[7:9,-1,:],foot[7:9,0,:])
        assert head[7:9,-1,2:14].all()
        assert not foot[9:].any()
    assert head[11:15,:2,1:15].all()


def test_modular_tables_and_counters_keep_one_continuous_surface(result):
    _,updates,ledger=result
    for row in ledger:
        if row['family'] in ('ship_table','book_table','lab_table','counter'):
            occ,_=decode(updates[row['key']])
            assert occ[9:11].all(),row['key']
    # Four diner corners meet across both seams; only the four outside tips cut.
    layout=[['9-39-54-55','39-25-55-57'],['70-55-85-86','55-71-87-55']]
    parts=[[decode(updates['tilesets.lobby/'+k])[0] for k in row] for row in layout]
    for row in parts:
        assert np.array_equal(row[0][9:11,:,-1],row[1][9:11,:,0])
    for x in (0,1):
        assert np.array_equal(parts[0][x][9:11,-1,:],parts[1][x][9:11,0,:])


def test_floor_fragments_are_flat_and_do_not_stack(result):
    _,updates,_=result
    for suffix in ('54-11-26-27','57-11-60-27'):
        entry=updates['tilesets.pokecenter/'+suffix]
        occ,_=decode(entry)
        assert occ[0].all() and not occ[1:].any()
        assert 'on' not in entry and not entry.get('walk')


def test_healing_station_rear_and_front_share_a_supported_plinth(result):
    _,updates,_=result
    for rear,front in [('40-58-40-74','72-76-72-6'),('59-40-75-40','77-7-22-13')]:
        a,_=decode(updates['tilesets.pokecenter/'+rear])
        b,_=decode(updates['tilesets.pokecenter/'+front])
        assert (a[:6,-1,:] & b[:6,0,:]).all()


def test_museum_shuttle_is_a_supported_volume_with_wings_and_tail(result):
    from collections import deque
    _,updates,_=result
    layout=[['64-65-80-81','66-67-82-82','68-69-84-85'],
            ['86-87-50-51','88-89-50-51','90-91-50-51']]
    model=np.zeros((32,32,48),bool)
    for z,row in enumerate(layout):
        for x,key in enumerate(row):
            occ,_=decode(updates['tilesets.gate/'+key])
            model[:len(occ),z*16:z*16+16,x*16:x*16+16]=occ
    aircraft=model.copy();aircraft[:13]=False
    y,z,x=np.where(aircraft)
    assert x.max()-x.min()>38 and z.max()-z.min()>23 and y.max()-y.min()>12
    # Flood from the cabinet base: no detached engines, wings or tail pieces.
    todo=deque([tuple(np.argwhere(model)[0])]);seen={todo[0]}
    while todo:
        p=todo.popleft()
        for axis in range(3):
            for sign in (-1,1):
                q=list(p);q[axis]+=sign;q=tuple(q)
                if all(0<=q[a]<model.shape[a] for a in range(3)) and model[q] and q not in seen:
                    seen.add(q);todo.append(q)
    assert len(seen)==int(model.sum())


def test_new_mineral_cases_are_explicitly_new_and_have_sculpted_stones(result):
    baseline,_,_=result
    additions,ledger=interiors.generate_additions(baseline)
    assert len(additions)==len(ledger)==4
    assert all(row['decision']=='added' and row['baseline']=='no fitted entry' for row in ledger)
    for key,entry in additions.items():
        occ,_=decode(entry)
        assert key not in baseline['cells'] and occ[13:].sum()>20


def test_game_corner_poster_is_a_grounded_shallow_plaque(result):
    _,updates,ledger=result
    key='tilesets.lobby/72-73-88-89'
    row=next(r for r in ledger if r['key']==key)
    assert row['family']=='plaque' and 'poster' in row['label'].lower()
    occ,paint=decode(updates[key]);y,z,x=np.where(occ)
    assert z.max()-z.min()<=2 and occ[0].any()
    # The displayed illustration excludes the unrelated final floor rows.
    assert (paint[:,4,:]//16<13).all()
