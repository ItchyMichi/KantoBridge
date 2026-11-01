"""Whole-platform seams, closed walls and exact exported gym additions."""
import base64
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import gyms_west as p,review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def data():
    table=review.read(review.BASELINE);art=p.common.EmeraldArt(p.common.BUNDLE);registry=review.read(p.REGISTRY)
    return table,art,registry,p.generate(table,art,registry)


def test_complete_dais_slices_join_rails_floor_and_shallow_entry_step(data):
    table,art,registry,patch=data;full=p.lavaridge_dais().occ;joined=np.zeros_like(full)
    for row in registry['entries']:
        recipe=row.get('recipe') or ''
        if not recipe.startswith('dais_'):continue
        _,x,z=recipe.split('_');x,z=int(x)*16,int(z)*16
        occ,paint=decode(patch[row['key']]);joined[:,z:z+16,x:x+16]=occ
        # Every occupied column has source-projected top paint from that cell.
        for zz in range(16):
            for xx in range(16):
                top=np.flatnonzero(occ[:,zz,xx])[-1]
                assert paint[top,zz,xx]==zz*16+xx
    assert np.array_equal(full,joined)
    assert full[:2].all() and not full[8:].any()
    assert not full[2:,3:29,3:77].any()
    assert full[:4,29:32].all()


def test_six_missing_center_sections_have_exact_map_adjacency_and_collision(data):
    table,art,registry,patch=data
    added=[r for r in registry['entries'] if r['key'] not in table['cells']]
    assert len(added)==6
    for row in added:
        assert row['baseline_entry_sha256'] is None
        assert patch[row['key']].get('walk') is True
        assert row['collision']==0
        for place in art.locations[row['key']]:
            assert place['map']=='EM_LAVARIDGE_TOWN_GYM_1F'
            assert 12<=place['x']<=14 and 9<=place['y']<=10
            m=art.maps[place['map']];raw=base64.b64decode(m['cells'])
            assert raw[(place['y']*m['w']+place['x'])*4+2]==0
    for key,entry in patch.items():
        if key in table['cells']:review.validate_cell(key,table['cells'][key],entry)


def test_friezes_are_continuous_closed_walls_and_source_membership_is_guarded(data):
    table,art,registry,patch=data
    for row in registry['entries']:
        if row.get('recipe')=='closed_frieze':
            occ,_=decode(patch[row['key']]);assert occ[:,13:16].all();assert not occ[:,:13].any()
    for spec in registry['assemblies']:
        x,y,w,d=spec['rect'];grid=art.map_cells(art.maps[spec['map']])
        assert spec['source_layout']==[r[x:x+w] for r in grid[y:y+d]]
        assert all(k in table['cells'] or k in patch for row in spec['source_layout'] for k in row)
