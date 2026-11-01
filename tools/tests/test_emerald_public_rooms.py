"""Source-local surface and complete assembly checks for the public rooms."""
import base64
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import public_rooms as p,review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def data():
    table=review.read(review.BASELINE);art=p.common.EmeraldArt(p.common.BUNDLE);registry=review.read(p.REGISTRY)
    return table,art,registry,p.generate(table,art,registry)


def test_union_slices_form_one_shallow_wall_and_forward_projecting_display(data):
    _,_,registry,patch=data;wall=p.union_wall().occ;joined=np.zeros_like(wall)
    for row in registry['entries']:
        recipe=row.get('recipe') or ''
        if not recipe.startswith('union_'):continue
        _,x,level=recipe.split('_');x,level=int(x)*16,int(level)*16
        joined[level:level+16,:,x:x+16]=decode(patch[row['key']])[0]
    assert np.array_equal(joined,wall)
    assert not wall[:,:7].any()
    assert wall[:18,8:12].all()
    assert wall[7:20,12:16,17:31].all()
    assert not wall[7:20,12:16,:17].any()
    assert np.any(wall[15]&wall[16])


def test_terminal_caps_exclude_background_and_floor_cells_have_no_upright_vent(data):
    _,art,registry,patch=data
    for row in registry['entries']:
        recipe=row.get('recipe')
        if recipe not in ('terminal_cap','terminal_floor'):continue
        key=row['key'];occ,paint=decode(patch[key])
        if recipe=='terminal_floor':
            assert occ[0].all() and not occ[1:].any()
            assert (paint[occ]//16>=8).all()
        else:
            assert not occ[7:].any()
            assert not occ[:,:,:2].any()
            rgb=np.asarray(art.cell(key,'BAKED').convert('RGB'))[paint//16,paint%16]
            assert not (rgb[occ]==0).all(axis=1).any()
            colors=rgb[occ].astype(int)
            assert not ((colors[:,2]>colors[:,0]+40)&(colors[:,2]>colors[:,1]+40)).any()


def test_new_links_match_all_local_supports_and_no_other_metadata_changes(data):
    old,art,registry,patch=data
    additions={r['key']:r['added_on'] for r in registry['entries'] if r.get('added_on')}
    assert len(additions)==4
    for key,entry in patch.items():
        before={k:v for k,v in old['cells'][key].items() if k not in review.GEOMETRY}
        after={k:v for k,v in entry.items() if k not in review.GEOMETRY}
        if key in additions:
            assert 'on' not in before;before['on']=additions[key]
            for place in art.locations[key]:
                source=art.maps[place['map']];grid=art.map_cells(source);x,y=place['x'],place['y']
                assert grid[y+1][x]==additions[key]
                raw=base64.b64decode(source['cells'])
                assert raw[((y+1)*source['w']+x)*4+2] in (7,147)
        assert before==after
