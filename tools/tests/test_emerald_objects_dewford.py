"""Exact source additions preserve movement while reconstructing whole objects."""
import copy
import json
from collections import deque
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import objects_dewford as d


@pytest.fixture(scope='module')
def authored():
    if not d.BASELINE.exists():pytest.skip('Local export required')
    table=json.loads(d.BASELINE.read_text());old=copy.deepcopy(table);art=d.EmeraldArt(d.BUNDLE);updates=d.generate(table,art)
    assert table==old
    return table,art,updates


def connected(o):
    start=tuple(np.argwhere(o)[0]);seen={start};q=deque([start])
    while q:
        a=q.popleft()
        for axis in range(3):
            for step in (-1,1):
                b=list(a);b[axis]+=step;b=tuple(b)
                if all(0<=b[i]<o.shape[i] for i in range(3)) and o[b] and b not in seen:seen.add(b);q.append(b)
    return len(seen)==int(o.sum())


def test_exact_patterns_and_warp_counts(authored):
    from tools.shape_fit.emerald_pass.review import validate_building
    table,art,updates=authored;assert len(updates)==5
    for name in d.CASES:
        e=updates['emerald_object_'+name];proof=d.evidence(art,e,name)
        validate_building(e['id'],e,e)
        assert len(proof)==d.CASES[name][-1]
        assert all(e['tiles']!=old['tiles'] for old in table['buildings'])
        assert sum(len(p['warps']) for p in proof)==({'dewford_house1':1,'island_ferry':2}.get(name,0))


def test_trees_grounded_connected_and_walk_crowns_have_headroom(authored):
    _,art,updates=authored
    for name in [n for n in d.CASES if 'tree' in n]:
        e=updates['emerald_object_'+name];source=d.p.drawing(e,d.BUNDLE);o,c=d.parts_model.build(e,np.asarray(source));assert connected(o)
        assert o[0].any()
        colors=set(map(tuple,c[o]));assert colors<={(107,90,90),(165,99,90),(58,140,49),(132,197,99),(181,255,132)}
        for loc in d.evidence(art,e,name):
            for cell in loc['cells']:
                if cell['collision']!=0:continue
                x,z=cell['local'];assert not o[:18,z*16:(z+1)*16,x*16:(x+1)*16].any()


def test_house_roof_overhang_and_ferry_gangway_stay_open(authored):
    _,art,updates=authored
    house=updates['emerald_object_dewford_house1'];o,_=d.parts_model.build(house,np.asarray(d.p.drawing(house,d.BUNDLE)))
    assert not o[:28,:16].any();assert o[28:,:16].any()
    ferry=updates['emerald_object_island_ferry'];o,_=d.parts_model.build(ferry,np.asarray(d.p.drawing(ferry,d.BUNDLE)))
    for x,z in ferry['terrain']:assert not o[:,z*16:(z+1)*16,x*16:(x+1)*16].any()
    assert not o[:20,16:25,48:64].any()
    # Four narrow pod supports join the cabin rather than float beside it.
    assert connected(o)
