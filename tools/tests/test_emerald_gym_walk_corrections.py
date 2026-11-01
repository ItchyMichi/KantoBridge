"""Source-verified metadata correction must preserve all reviewed geometry."""
import copy
import pytest
from tools.shape_fit.emerald_pass import gym_walk_corrections as p,review


@pytest.fixture(scope='module')
def data():
    table=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE)
    # Four Dewford center geometries have already been corrected in their owner
    # patch; use those entries to prove this overlay preserves the new geometry.
    for owner in ('gyms-west','gyms-rooms'):
        table['cells'].update(review.read(p.c.OUT.parent/owner/'updates.json')['cells'])
    for _,suffixes in p.GROUPS.values():
        for suffix in suffixes:table['cells']['g3q/'+suffix].pop('walk',None)
    patch,proof=p.generate(table,art)
    return table,art,patch,proof


def test_exact_sixteen_keys_keep_geometry_and_gain_only_walk_decor(data):
    table,_,patch,proof=data
    assert len(patch)==16
    for key,new in patch.items():
        old=table['cells'][key];expected=copy.deepcopy(old);expected['walk']=True
        assert new==expected
        assert all(p['collision']==0 for p in proof[key]['occurrences'])
        assert not p.c.decode(new)[0][16:].any()


def test_rejects_new_stack_or_existing_render_flag_instead_of_overwriting_it(data):
    table,art,_,_=data;table=copy.deepcopy(table)
    key='g3q/'+p.GROUPS['building__petalburg_gym'][1][0]
    table['cells'][key]['on']='g3q/unrelated'
    with pytest.raises(ValueError,match='unlinked solid-only'):p.generate(table,art)
