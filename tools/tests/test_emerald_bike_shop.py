import numpy as np
import pytest
from tools.shape_fit.emerald_pass import bike_shop as b
from tools.tests.test_emerald_oceanic_museum import connected


def test_whole_machines_and_bicycles_are_connected_and_grounded():
    for role in b.PALETTES:
        model=b.whole(role)
        assert connected(model.occ),role
        assert model.occ[0].any(),role
    machine=b.whole('generator')
    assert not machine.occ[43:,20:28,28:36].any()
    bike=b.whole('bicycle')
    assert not bike.occ[11:15,:,1:7].any()
    assert not bike.occ[:4,:,12:20].any()
    crate=b.whole('open_crate')
    assert not crate.occ[3:,4:12,4:12].any()


@pytest.mark.skipif(not b.BASELINE.exists(),reason='local source export unavailable')
def test_crates_remove_only_false_solid_to_walk_links():
    art=b.EmeraldArt(b.BUNDLE);base=b.read(b.BASELINE);patch,rows,changes=b.generate(base,art)
    assert (len(patch),len(rows),len(changes))==(54,84,2)
    assert sum(r['decision']=='added' for r in rows)==1
    assert sum(len(r['occurrences']) for r in changes)==12
    for change in changes:
        assert change['after']=={'class':'voxels'}
        for loc in change['occurrences']:
            assert loc['collision']==7 and loc['below_collision']==0
            assert loc['below_key'] in {change['before']['on'],'g3q/13928-13844-13756-13747'}


@pytest.mark.skipif(not b.BASELINE.exists(),reason='local source export unavailable')
def test_generator_and_compressor_slices_reconstruct_exact_whole_occupancy():
    art=b.EmeraldArt(b.BUNDLE);registry=b.read(b.REGISTRY)
    for role in ('generator','compressor'):
        whole=b.whole(role);rebuilt=np.zeros_like(whole.occ)
        for key,spec in registry.items():
            if spec.get('role')!=role:continue
            m=b.model(art.cell(key,'BAKED'),spec);x,z=spec['x']*16,spec['z']*16
            rebuilt[:,z:z+16,x:x+16]=m.occ
        assert np.array_equal(rebuilt,whole.occ)


def test_book_desks_have_supported_top_and_book_with_open_leg_space():
    m=b.whole('book_desk')
    assert connected(m.occ)
    assert not m.occ[:9,6:11,5:27].any()
    assert m.occ[10:12,2:16,:].all()
    assert m.occ[14:16,5:14,20:30].all()
