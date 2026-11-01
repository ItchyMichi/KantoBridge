"""Counter metadata is a narrow source-backed opt-in, never a geometry rewrite."""
import copy
import pytest
from tools.shape_fit.emerald_pass import oceanic_counter_render as p,review


def test_four_counter_flags_preserve_every_geometry_and_other_metadata_field():
    table=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE)
    patch,proof=p.generate(table,art)
    assert set(patch)==set(p.KEYS)
    for key,new in patch.items():
        expected=copy.deepcopy(table['cells'][key]);expected['counter']=True
        assert new==expected
    assert [len(row['occurrences']) for row in proof]==[5,4,1,2]
    assert all(place['collision']==144 for row in proof for place in row['occurrences'])


def test_existing_stack_or_upper_layers_cannot_be_silently_opted_in():
    table=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE)
    table['cells'][p.KEYS[0]]['on']='g3q/other'
    with pytest.raises(AssertionError):p.generate(table,art)
