import numpy as np
import pytest
from tools.shape_fit.emerald_pass import cable_palace as c
from tools.tests.test_emerald_oceanic_museum import connected


def test_machines_and_chairs_have_attached_parts_and_grounded_supports():
    for role in ('trade','pod','record','hall_terminal','palace_panel','palace_chair','trade_chair','trade_wall'):
        model=c.whole(role)
        assert connected(model.occ),role
        assert model.occ[0].any(),role
    chair=c.whole('trade_chair')
    assert not chair.occ[:5,6:10,6:10].any()
    pedestal=c.whole('palace_chair')
    assert not pedestal.occ[2:6,:,:5].any()


def test_repeated_record_console_keys_have_identical_geometry_at_both_positions():
    shape=c.whole('record')
    for z in range(3):
        assert np.array_equal(shape.occ[:,z*16:z*16+16,16:32],shape.occ[:,z*16:z*16+16,32:48])
        assert np.array_equal(shape.paint[:,z*16:z*16+16,16:32],shape.paint[:,z*16:z*16+16,32:48])


@pytest.mark.skipif(not c.BASELINE.exists(),reason='local source export unavailable')
def test_every_interaction_amendment_has_exact_source_witnesses():
    art=c.EmeraldArt(c.BUNDLE);base=c.read(c.BASELINE);patch,rows,changes=c.generate(base,art)
    assert (len(patch),len(rows),len(changes))==(35,83,10)
    for change in changes:
        before,after=change['before'],change['after']
        for loc in change['occurrences']:
            if after.get('walk'):assert loc['collision']==0
            if after.get('counter'):assert loc['collision']==144
            if after.get('on'):assert loc['below_key']==after['on'] and loc['collision']==7
            if 'on' in before and 'on' not in after:
                assert loc['collision']==7
                floor=patch[loc['below_key']]
                assert floor['walk'] and before['on']==loc['below_key']


def test_corrected_floor_projections_have_no_new_solids_and_baselines_keep_omissions():
    floor=c.whole('floor');assert floor.occ.sum()==256 and not floor.occ[1:].any()
    scenes=c.read(c.REGISTRY.with_name('cable_palace_assemblies.json'))
    for e in scenes:
        if e['name'] not in ('palace-panel','palace-chair1','palace-chair2'):continue
        assert any(k is None for row in e['baseline_layout'] for k in row)
        assert sum(k is not None for row in e['layout'] for k in row)>sum(k is not None for row in e['baseline_layout'] for k in row)
