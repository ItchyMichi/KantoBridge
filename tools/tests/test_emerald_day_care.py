"""Actual source furniture, open pen rails, and narrow interaction changes."""
import copy
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import day_care as p,review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def data():
    table=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE)
    patch,rows,metadata=p.generate(table,art)
    return table,art,patch,rows,metadata


def test_exact_twenty_source_cells_and_five_narrow_metadata_transitions(data):
    table,art,patch,rows,metadata=data;assert len(patch)==len(rows)==20 and len(metadata)==5
    amendments={r['key']:r for r in metadata}
    for row in rows:
        key=row['key'];old=table['cells'][key];new=patch[key];adjusted=copy.deepcopy(new)
        if key in amendments:
            assert {k:v for k,v in new.items() if k not in review.GEOMETRY}==amendments[key]['after']
            for field in ('walk','counter','on'):
                if field in old:adjusted[field]=old[field]
                else:adjusted.pop(field,None)
        review.validate_cell(key,old,adjusted);assert row['occurrences']==art.locations[key]
    by={r['source_ordinal']:r['key'] for r in rows}
    assert patch[by[1]]['walk'] and 'on' not in patch[by[20]]
    assert all(patch[by[n]]['counter'] for n in (3,6,7))
    assert all(patch[by[n]]['on']==table['cells'][by[n]]['on'] for n in (4,5,9))


def test_complete_table_and_potted_shrub_reassemble_without_floor_backing(data):
    _,art,patch,rows,_=data;by={r['source_ordinal']:r['key'] for r in rows}
    whole=np.zeros((16,32,32),bool)
    for n,x,z in [(12,0,0),(13,16,0),(15,0,16),(16,16,16)]:whole[:,z:z+16,x:x+16]=decode(patch[by[n]])[0]
    assert np.array_equal(whole,p.table().occ) and not whole[0,16,16]
    plant=np.concatenate([decode(patch[by[10]])[0],decode(patch[by[5]])[0]])
    assert np.array_equal(plant,p.shrub().occ) and not plant[0,0,0]
    assert plant[8,8,8] and plant[16,8,8]
    for n in (5,10,12,13,15,16):
        occ,paint=decode(patch[by[n]]);rgb=np.asarray(art.cell(by[n],'BAKED').convert('RGB'))[paint//16,paint%16][occ].astype(int)
        assert not ((rgb[:,2]>170)&(rgb[:,2]>rgb[:,0]+70)).any(),n


def test_counter_top_wall_crop_floor_and_open_fence_geometry(data):
    _,_,patch,rows,_=data;by={r['source_ordinal']:r['key'] for r in rows}
    occ,paint=decode(patch[by[1]]);assert occ[0].all() and not occ[1:].any()
    occ,paint=decode(patch[by[2]]);assert (paint[occ]//16>=8).all() and occ[0].any()
    occ,_=decode(patch[by[7]]);assert not occ[9,8,8] and occ[9,8,11] and not occ[10:].any()
    front,_=decode(patch[by[19]]);west,_=decode(patch[by[20]]);corner,_=decode(patch[by[18]])
    assert not front[:,0:8,:].any() and not front[0,12,8]
    assert west[4,15,2] and corner[4,0,2]
    assert corner[4,12,15] and front[4,12,0]
    assert not west[:,:,6:].any()


def test_missing_cap_is_exact_source_and_joins_shared_pc_without_altering_it(data):
    table,art,_,_,_=data;old=copy.deepcopy(table['cells'][p.PC]);entry,row,evidence=p.added_cap(table,art)
    assert p.CAP not in table['cells'] and entry['on']==p.PC and table['cells'][p.PC]==old
    assert row['baseline_absent'] and row['baseline_entry_sha256'] is None
    assert row['proposed_entry_sha256']==review.entry_hash(entry)
    assert evidence['occurrences'][0]['collision']==7 and evidence['occurrences'][0]['below_collision']==147
    occ,paint=decode(entry);assert occ[0,6,8] and not occ[6:].any()
