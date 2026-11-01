"""Regression checks for the ship defects visible in Minecraft scenery-08."""
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import ship_qa_corrections as q
from tools.tests.test_emerald_oceanic_museum import connected


def test_broken_frame_is_connected_grounded_and_passable():
    o=q.broken_frame().occ
    assert connected(o) and o[0].any()
    assert not o[:27,:,5:11].any()
    assert o[27:32,6:10,:].all()
    assert o[:,6:10,0:2].all() and o[:,6:10,14:16].all()


def test_alcove_is_a_single_wall_with_horizontal_rug_and_supported_trim():
    o=q.alcove().occ
    assert connected(o)
    assert o[:,0:8,:].all() and o[0,8:16,:].all()
    assert not o[1:,11:,:].any()
    assert o[1:5,8:11,12:20].all()


@pytest.fixture(scope='module')
def source():
    if not q.BASELINE.exists():pytest.skip('local source export required')
    return q.read(q.BASELINE),q.EmeraldArt(q.BUNDLE)


def test_exact_repairs_preserve_all_original_metadata_and_strip_void_paint(source):
    base,art=source;updates=q.cell_repairs(base,art)
    assert len(updates)==15
    for key,new in updates.items():
        omit={'v','t','up','from'}
        assert {k:v for k,v in new.items() if k not in omit}=={k:v for k,v in base['cells'][key].items() if k not in omit}
    rows=q.ship.rows_for(q.ship.GROUP)
    for n in (13,69,70):
        o,p=q.decode(updates[rows[n-1]['key']]);assert o.all()
        rgb=np.asarray(art.cell(rows[n-1]['key'],'BAKED').convert('RGB'))[p//16,p%16]
        assert not np.all(rgb[o]>245,axis=1).any()
    stair=q.decode(updates[rows[74]['key']])[0]
    assert connected(stair) and stair[:,:8,:].all()
    assert stair[8:10,7:11,:].all() and stair[:8,13:16,12:15].all()
    left=q.decode(updates[rows[18]['key']])[0]
    assert np.array_equal(np.concatenate([left,stair],axis=2),q.stair_landing_wall().occ)
    for left,right in ((30,58),(30,31),(59,35),(34,35)):
        a=q.decode(updates[rows[left-1]['key']])[0];b=q.decode(updates[rows[right-1]['key']])[0]
        assert np.array_equal(np.concatenate([a,b],axis=2),q.alcove().occ)


def test_whole_doors_render_on_warp_footprint_and_leave_upper_projection_empty(source):
    base,art=source;templates,ledger,groups=q.doorway_templates(art)
    assert len(templates)==2 and sum(len(g['occurrences']) for g in groups)==5
    for t,g in zip(templates,groups):
        draw=np.asarray(art.drawing(g['layout'],'BAKED').convert('RGB'))
        o,c=q.build(t,draw)
        assert connected(o) and o[0].any() and o.sum()>600
        assert not o[:,:16,:].any()
        assert not o[:27,:,5:11].any()
        assert t['interior'] and g['source_kinds']==[[0],[114]]
