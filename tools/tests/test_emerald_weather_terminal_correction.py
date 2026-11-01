"""Detached source cornice, native WALK floor and exact terminal topology."""
from collections import deque
import numpy as np
import pytest
from tools.shape_fit.emerald_pass import weather_terminal_correction as p,review
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def data():
    table=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE)
    return table,art,*p.generate(table,art)


def test_only_detached_room_cornice_is_removed_from_the_authored_pc_rim(data):
    table,_,patch,_=data
    old,paint=decode(table['cells'][p.CAP]);new,newpaint=decode(patch[p.CAP])
    assert old[11:].sum()==536 and new.sum()==1173
    assert np.array_equal(old[:8],new[:8]) and np.array_equal(paint[:8],newpaint[:8])
    assert not new[8:].any()
    seen={tuple(np.argwhere(new)[0])};queue=deque(seen)
    while queue:
        v=queue.popleft()
        for axis in range(3):
            for step in (-1,1):
                n=list(v);n[axis]+=step;n=tuple(n)
                if all(0<=n[i]<new.shape[i] for i in range(3)) and new[n] and n not in seen:seen.add(n);queue.append(n)
    assert len(seen)==new.sum()
    live_pc=review.read(review.TABLE)['cells'][p.PC];pc,_=decode(live_pc)
    assert (pc[-1]&new[0]).sum()>100
    assert p.PC not in patch


def test_walk_foot_is_one_native_floor_layer_and_never_a_solid_cabinet(data):
    _,art,patch,_=data;occ,paint=decode(patch[p.FOOT])
    assert occ[0].all() and not occ[1:].any()
    assert patch[p.FOOT]['walk'] is True and 'on' not in patch[p.FOOT]
    y,z,x=np.indices(occ.shape);assert np.array_equal(paint[occ],((8+z//2)*16+x)[occ])
    rgb=np.asarray(art.cell(p.FOOT,'BAKED').convert('RGB')).reshape(-1,3)[paint[occ]]
    assert np.unique(rgb,axis=0).shape[0]==3


def test_actual_pc_interaction_source_and_single_occurrences_define_truthful_assembly(data):
    table,art,patch,proof=data
    assert set(patch)=={p.CAP,p.FOOT}
    assert [(r['key'],[(v['x'],v['y'],v['collision']) for v in r['occurrences']]) for r in proof]==[(p.CAP,[(2,0,7)]),(p.PC,[(2,1,147)]),(p.FOOT,[(2,2,0)])]
    assert patch[p.CAP]['on']==p.PC
    for key,flag in [(p.CAP,{'on':p.PC}),(p.FOOT,{'walk':True})]:
        assert {k:v for k,v in patch[key].items() if k not in review.GEOMETRY}==dict({k:v for k,v in table['cells'][key].items() if k not in review.GEOMETRY},**flag)
    spec=p.assembly();grid=art.map_cells(art.maps[p.MAP])
    assert spec['source_layout']==[[grid[y][2]] for y in range(3)]
    assert spec['placements']==[dict(source=[0,0],offset=[0,1,1]),dict(source=[0,1],offset=[0,0,1]),dict(source=[0,2],offset=[0,0,2])]


def test_original_owner_generator_reproduces_both_corrected_entries(data):
    table,art,patch,_=data
    regenerated=p.c.generate(table,art,review.read(p.c.REGISTRY))
    assert {key:regenerated[key] for key in patch}==patch
