import base64
import numpy as np
import pytest
from tools.model_gallery import EmeraldArt
from tools.shape_fit.emerald_pass import retail
from tools.shape_fit.emerald_pass.review import read,validate_cell
from tools.shape_fit.blue_pass.common import decode

@pytest.fixture(scope='module')
def authored():
    if not retail.BASELINE.exists():pytest.skip('local Emerald inputs unavailable')
    b=read(retail.BASELINE);a=EmeraldArt(retail.BUNDLE);r=read(retail.REGISTRY)
    return b,a,r,retail.generate(b,a)

def test_native_plant_pots_and_crown_seams_exclude_floor_material(authored):
    _,art,registry,updates=authored
    for key,s in registry.items():
        if s.get('role') not in ('plant_cap','plant_base'):continue
        occ,paint=decode(updates[key]);pixels=np.asarray(art.cell(key,'BAKED').convert('RGB')).reshape(256,3).astype(int)
        if s['role']=='plant_cap':
            colors=pixels[paint[occ]]
            assert ((colors[:,1]>colors[:,0]+5)&(colors[:,1]>colors[:,2]+5)).all()
            lower=decode(updates[updates[key]['on']])[0]
            assert (occ[0]&lower[15]).any()
            # Curved leaves can widen across the slice; the complete plant must
            # remain one six-connected solid, with no detached crown fragments.
            whole=np.concatenate([lower,occ]);seen=set();todo=[tuple(np.argwhere(whole)[0])]
            while todo:
                q=todo.pop()
                if q in seen:continue
                seen.add(q)
                for axis in range(3):
                    for direction in (-1,1):
                        neighbor=list(q);neighbor[axis]+=direction;neighbor=tuple(neighbor)
                        if all(0<=v<whole.shape[a] for a,v in enumerate(neighbor)) and whole[neighbor] and neighbor not in seen:todo.append(neighbor)
            assert len(seen)==whole.sum()
        else:
            pot=occ & (np.indices(occ.shape)[0]<9);colors=pixels[paint[pot]]
            assert (abs(colors[:,0]-colors[:,1])<20).all()
            assert (colors[:,2]>=colors[:,0]-15).all()
            assert not occ[:,0,:].any() and not occ[:,:,0].any()

def test_furniture_seams_and_open_supports(authored):
    _,_,registry,updates=authored;by={s['source_ordinal']:k for k,s in registry.items()}
    left,right=[decode(updates[by[n]])[0] for n in (96,99)]
    assert np.array_equal(left[:,:,-1],right[:,:,0])
    whole=np.concatenate([left,right],axis=2)
    assert not whole[:6,6:12,8:24].any() and whole[6:8,4:7,8:24].all()
    # Herb display end caps meet the retained fifteen-high middle shelf.
    north=decode(updates[by[132]])[0];south=decode(updates[by[133]])[0]
    assert north[:15,-1,1:16].all() and south[:15,0,1:16].all()

def test_only_pinned_interactions_change_and_new_links_match_solid_neighbors(authored):
    base,art,registry,updates=authored
    for key,new in updates.items():
        if key in base['cells']:validate_cell(key,base['cells'][key],new)
        if registry[key].get('added_on'):
            for p in art.locations[key]:
                s=art.maps[p['map']];x,y=p['x'],p['y']
                assert art.map_cells(s)[y+1][x]==new['on']
                assert base64.b64decode(s['cells'])[((y+1)*s['w']+x)*4+2]==7

def test_explicit_whole_objects_are_exact_source_patterns(authored):
    _,art,_,_=authored
    for spec in read(retail.REGISTRY.with_name('retail_assemblies.json')):
        layout=spec['source_layout']
        for p in spec['occurrences']:
            g=art.map_cells(art.maps[p['map']]);x,y=p['x'],p['y']
            assert [g[y+z][x:x+len(row)] for z,row in enumerate(layout)]==layout
