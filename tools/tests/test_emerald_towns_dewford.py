"""Dewford geometry scope, source coverage and one exact native stack alias."""
import copy
import json
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import towns_dewford as d


@pytest.fixture(scope='module')
def authored():
    if not d.BASELINE.exists():pytest.skip('Local export required')
    table=json.loads(d.BASELINE.read_text());old=copy.deepcopy(table);art=d.EmeraldArt(d.BUNDLE);updates=d.generate(table,art)
    assert table==old
    return table,art,updates


def test_all_source_decisions_and_only_one_metadata_amendment(authored):
    table,art,updates=authored;assert set(d.decisions())==set(range(1,60));assert len(updates)==20
    meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
    for n in d.CHANGED:
        key=d.key(n);expected=meta(table['cells'][key])
        if n==9:
            expected['alsoOn']=[d.key(10)]
            expected['stackFit']=True
        assert meta(updates[key])==expected
    proof=d.coverage(art,table);assert len(proof)==39
    assert all(r['fully_covered'] and r['occurrences'] for r in proof.values())


def test_tree_materials_exclude_ground_and_stacks_join(authored):
    table,art,updates=authored
    for n in (4,9,10,11,12,13,14,15):
        key=d.key(n);o,p=d.decode(updates[key]);rgb=np.asarray(art.cell(key,'BAKED').convert('RGB'))
        colors=set(map(tuple,rgb[p[o]//16,p[o]%16]));allowed=set(d.TREE_COLORS)
        if n==10:allowed|={(107,90,90),(165,99,90)}
        assert colors<=allowed
    base=d.decode(updates[d.key(10)])[0];middle=d.decode(updates[d.key(12)])[0];crown=d.decode(updates[d.key(9)])[0]
    assert base[0].any() and (base[-1]&middle[0]).any() and (base[-1]&crown[0]).any() and (middle[-1]&crown[0]).any()
    places=d.alias_witnesses(art);assert len(places)==9 and all(p['below_collision']==7 for p in places)


def test_sign_background_removed_and_native_floor_geometry_unchanged(authored):
    table,art,updates=authored
    for n in (1,2):
        key=d.key(n);o,p=d.decode(updates[key]);rgb=np.asarray(art.cell(key,'BAKED').convert('RGB'));colors=rgb[p[o]//16,p[o]%16]
        assert not np.any(np.all(colors[:,None,:]==np.asarray(((222,206,132),(214,181,107),(239,230,165))),axis=-1))
    lower=d.decode(updates[d.key(2)])[0];upper=d.decode(updates[d.key(1)])[0]
    assert lower[0].any() and (lower[-1]&upper[0]).any()
    for n in (5,6,7,8,35,36,37,38,39,40):
        key=d.key(n);assert np.array_equal(d.decode(table['cells'][key])[0],d.decode(updates[key])[0])
        assert not updates[key].get('walk')
