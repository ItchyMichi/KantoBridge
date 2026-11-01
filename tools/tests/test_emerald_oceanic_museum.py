import base64
import numpy as np
import pytest

from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import oceanic_museum as o


def connected(occ):
    todo=[tuple(np.argwhere(occ)[0])];seen=set()
    while todo:
        p=todo.pop()
        if p in seen:continue
        seen.add(p)
        for axis in range(3):
            for delta in (-1,1):
                q=list(p);q[axis]+=delta;q=tuple(q)
                if all(0<=v<occ.shape[a] for a,v in enumerate(q)) and occ[q] and q not in seen:todo.append(q)
    return len(seen)==int(occ.sum())


def test_case_and_computer_parts_are_supported_and_exclude_floor():
    for role in ('large_case','small_case','information','pc'):
        model=o.whole(role)
        assert connected(model.occ),role
        assert model.occ[0].any(),role
    pc=o.whole('pc')
    assert not pc.occ[:3,6:10,5:18].any()  # air under its cabinet
    assert pc.occ[18:25,10:12,3:13].all()  # the supported monitor
    big=o.whole('large_case')
    assert big.occ[:4,16:19].any() and not big.occ[4:,16:].any()
    assert not big.occ[:,:,24:].any()  # right-hand source wall is not case mass


@pytest.mark.skipif(not o.BASELINE.exists(),reason='local source export unavailable')
def test_metadata_amendments_have_exact_source_relationships():
    art=o.EmeraldArt(o.BUNDLE);base=o.read(o.BASELINE)
    patch,ledger,amendments=o.generate(base,art)
    assert len(patch)==31 and len(ledger)==57 and len(amendments)==14
    for a in amendments:
        new=patch[a['key']]
        if new.get('counter'):
            expected=dict(base['cells'][a['key']],counter=True)
            assert new==expected  # Original reviewed counter geometry is untouched.
        for p in a['occurrences']:
            if new.get('counter'):
                assert p['collision']==144
            if new.get('walk'):
                assert p['collision']==0
            if new.get('on'):
                assert p['below_key']==new['on']
                src=art.maps[p['map']];i=((p['y']+1)*src['w']+p['x'])*4+2
                assert base64.b64decode(src['cells'])[i]==7
    assert all(patch[k].get('on') is None for k,s in o.read(o.REGISTRY).items() if s.get('role')=='divider')


@pytest.mark.skipif(not o.BASELINE.exists(),reason='local source export unavailable')
def test_computer_uses_no_green_floor_texels_and_maintains_cell_seams():
    art=o.EmeraldArt(o.BUNDLE);registry=o.read(o.REGISTRY)
    cells={}
    for k,s in registry.items():
        if s.get('role')!='pc':continue
        m=o.model(art.cell(k,'BAKED'),s);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'))[m.paint//16,m.paint%16].astype(int)
        green=(rgb[...,1]>rgb[...,0]+15)&(rgb[...,1]>=rgb[...,2])
        assert not (green&m.occ).any(),k
        cells[s['x'],s['layer']]=m.occ
    full=o.whole('pc')
    for (x,y),occ in cells.items():assert np.array_equal(occ,full.occ[y*16:y*16+16,:,x*16:x*16+16])


def test_before_companions_keep_omitted_walk_rows_and_floor_depth():
    specs=o.read(o.REGISTRY.with_name('oceanic_museum_assemblies.json'))
    assert len(specs)==15
    pc=next(s for s in specs if s['id']=='emerald_oceanic_pc')
    assert pc['baseline_layout'][0]==[None,None]
    case=next(s for s in specs if s['id']=='emerald_oceanic_large_case')
    assert case['baseline_layout'][2]==[None,None]
    assert {tuple(p['offset']) for p in case['placements']}=={(x,y,z) for x in (0,1) for y,z in ((1,0),(0,0),(0,1))}
    divider=next(s for s in specs if s['id']=='emerald_oceanic_divider')
    assert all(p['offset'][1]==0 for p in divider['placements'])
    assert divider['placements']==divider['baseline_placements']
