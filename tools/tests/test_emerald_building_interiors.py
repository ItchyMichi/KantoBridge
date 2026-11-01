"""Semantic geometry contracts for the inspected lab furniture candidates."""
import base64
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import building_interiors as lab, review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def candidate():
    table=review.read(lab.BASELINE)
    art=lab.EmeraldArt(lab.BUNDLE)
    registry=review.read(lab.REGISTRY)
    return table,art,registry,lab.generate(table,art,registry)


def test_every_change_preserves_source_identity_and_interactions(candidate):
    table,art,registry,patch=candidate
    for key,new in patch.items():
        review.validate_cell(key,table['cells'][key],new)
        assert decode(new)[0].any()


def test_chair_has_air_under_seat_and_back_rotates_with_orientation(candidate):
    _,art,registry,_=candidate
    row=next(r for r in registry['entries'] if r.get('recipe')=='chair_north')
    sprite=art.cell(row['key'],'BAKED')
    north=lab.chair('north',sprite).occ
    assert not north[:6,6:11,5:11].any()
    assert north[8,5:12,4:12].all()
    assert north[12,3:5,4:12].all()
    assert not north[12,10:14].any()
    assert np.array_equal(lab.chair('east',sprite).occ,np.rot90(north,3,axes=(1,2)))
    assert np.array_equal(lab.chair('west',sprite).occ,np.rot90(north,1,axes=(1,2)))


def test_bed_is_one_horizontal_frame_with_sliced_seams(candidate):
    _,_,registry,patch=candidate
    full=lab.bed().occ
    assembled=np.zeros_like(full)
    for row in registry['entries']:
        recipe=row.get('recipe') or ''
        if not recipe.startswith('bed_'):continue
        _,x,z=recipe.split('_');x,z=int(x)*16,int(z)*16
        assembled[:,z:z+16,x:x+16]=decode(patch[row['key']])[0]
    assert np.array_equal(assembled,full)
    assert not full[:4,5:27,7:24].any()
    assert full[7,4:29,5:27].all()
    assert full[10,14:29,6:26].all()


def test_plant_pair_is_connected_and_uses_native_leaf_tones(candidate):
    table,art,registry,patch=candidate
    full=lab.plant().occ
    assert full[15:17].any(axis=0).any()
    assert full[:2,6:10,6:10].all()
    assert not full[:7,:2,:2].any()
    for row in registry['entries']:
        recipe=row.get('recipe') or ''
        if not recipe.startswith('plant_'):continue
        key=row['key'];occ,paint=decode(patch[key]);source=np.asarray(art.cell(key,'BAKED').convert('RGB'))
        colors=source[paint//16,paint%16]
        start=16 if recipe=='plant_cap' else 0
        assert np.array_equal(occ,full[start:start+16])
        if recipe=='plant_base':
            leaf_colors={tuple(c) for c in colors[9:][occ[9:]]}
            assert leaf_colors=={(82,181,66),(140,230,115)}
        else:
            assert table['cells'][key]['on'] in patch


def test_bookcases_have_continuous_frames_and_open_recesses(candidate):
    _,_,registry,patch=candidate
    rows={q['source_card']:q for q in registry['entries']}
    for family,indices in [('wall',[(13,14),(6,7)]),('free',[(32,33),(27,28),(22,23)])]:
        full=lab.bookcase(family=='wall',3 if family=='free' else None).occ
        joined=np.zeros_like(full)
        for layer,halves in enumerate(indices):
            for x,ordinal in enumerate(halves):
                joined[layer*16:layer*16+16,:,x*16:x*16+16]=decode(patch[rows[ordinal]['key']])[0]
        assert np.array_equal(joined,full)
        assert full[0,2:14,1:31].all()
        assert not full[6:8,5:8,4:14].any() # actual shelf depth, not a painted solid rectangle
        assert np.any(full[15]&full[16])
        if family=='free':assert np.any(full[31]&full[32])
    floor=decode(patch[rows[21]['key']])[0]
    assert floor[0].all() and not floor[1:].any()


def test_every_added_bookcase_link_matches_all_exported_neighbors(candidate):
    table,art,registry,patch=candidate
    additions=[q for q in registry['entries'] if q.get('added_on') and (q.get('recipe') or '').startswith('shelf_')]
    assert len(additions)==7
    checked=0
    for row in additions:
        key,below=row['key'],row['added_on']
        assert 'on' not in table['cells'][key]
        assert patch[key]['on']==below
        for place in art.locations[key]:
            source=art.maps[place['map']];grid=art.map_cells(source)
            assert grid[place['y']+1][place['x']]==below
            data=base64.b64decode(source['cells'])
            index=((place['y']+1)*source['w']+place['x'])*4
            assert data[index+2]==7
            checked+=1
        seen=set();cursor=key
        while cursor in patch and patch[cursor].get('on'):
            assert cursor not in seen
            seen.add(cursor);cursor=patch[cursor]['on']
        assert len(seen)<=2
    assert checked==38
