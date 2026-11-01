import copy

import numpy as np
import pytest

from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import shared_final as f
from tools.shape_fit.emerald_pass.review import read


@pytest.fixture(scope='module')
def sources():
    if not f.BASELINE.exists() or not f.BUNDLE.exists() or not f.HANDOFF.exists():
        pytest.skip('user source export, exact baseline and source handoff required')
    baseline=read(f.BASELINE);art=EmeraldArt(f.BUNDLE);rows=f.inventory_rows()
    updates,ledger=f.generate(baseline,art,rows)
    current=read(f.ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json')
    current['cells'].update(updates)
    return baseline,art,rows,updates,ledger,current


def test_exact_ownership_and_only_documented_metadata_changes(sources):
    baseline,_,rows,updates,ledger,_=sources
    assert len(rows)==len(ledger)==59 and len(updates)==58
    retained={r['key'] for r in ledger if r['decision']=='retained'}
    assert retained=={r['key'] for r in rows if r['source_ordinal']==387}
    lookup={r['source_ordinal']:r['key'] for r in rows}
    exceptions={lookup[140]:lookup[161],lookup[161]:lookup[178],lookup[141]:lookup[163],lookup[163]:lookup[180],lookup[196]:lookup[196]}
    for key,entry in updates.items():
        metadata=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
        expected=metadata(baseline['cells'][key])
        if key in exceptions:expected['on']=exceptions[key]
        assert metadata(entry)==expected
        occ,_=decode(entry)
        assert all(layer.any() for layer in np.split(occ,len(occ)//16))


def test_dining_table_is_one_supported_object_and_excludes_floor_colors(sources):
    _,art,_,updates,_,_=sources
    whole=np.zeros((16,32,32),bool)
    for z,line in enumerate(f.YELLOW):
        for x,key in enumerate(line):
            o,p=decode(updates[key]);whole[:,z*16:z*16+16,x*16:x*16+16]=o
            rgb=np.asarray(art.cell(key,'BAKED').convert('RGB'))
            assert set(map(tuple,rgb[p[o]//16,p[o]%16]))<={(189,173,82),(255,247,132)}
    assert whole[6:8,2:29,2:30].all()
    assert not whole[:6,8:23,8:23].any()
    assert whole[:6,3:6,3:6].all() and whole[:6,25:28,26:29].all()
    assert np.any(whole[:,15]&whole[:,16]) and np.any(whole[:,:,15]&whole[:,:,16])


def test_paper_desks_preserve_complete_supported_geometry(sources):
    baseline,_,rows,updates,_,_=sources
    allrows=read(f.INVENTORY/'shared-building/entries.json')
    lookup={r['source_ordinal']:r['key'] for r in rows}
    for target,source in f.PAPER.items():
        actual=decode(updates[lookup[target]])[0]
        donor=decode(baseline['cells'][allrows[source-1]['key']])[0]
        assert np.array_equal(actual,donor)
        assert actual[0].any() and not actual[9:].any()


def test_every_real_rail_interface_joins_and_remains_open(sources):
    _,art,rows,updates,_,current=sources
    definitions,_=f.assemblies(art,current,rows)
    interfaces=0
    for item in definitions:
        if 'guardrail' not in item['note']:continue
        layout=item['layout']
        for z,line in enumerate(layout):
            for x,key in enumerate(line):
                if not key:continue
                here=decode(current['cells'][key])[0]
                for dx,dz in ((1,0),(0,1)):
                    if z+dz>=len(layout) or x+dx>=len(line):continue
                    other=layout[z+dz][x+dx]
                    if not other:continue
                    there=decode(current['cells'][other])[0]
                    a,b=(here[:,:,-1],there[:,:,0]) if dx else (here[:,-1,:],there[:,0,:])
                    assert np.any(a & b),(item['id'],key,other)
                    interfaces+=1
    assert interfaces>100
    lookup={r['source_ordinal']:r['key'] for r in rows}
    o,_=decode(updates[lookup[236]])
    assert not o[5:11,7:9,10:14].any()
    assert o[11:13,7:9,:].all() and o[0].any()


def test_machine_slices_keep_pipe_and_body_connections(sources):
    _,_,rows,updates,_,_=sources
    lookup={r['source_ordinal']:r['key'] for r in rows}
    m=f.machine_whole();whole=np.zeros_like(m.occ)
    for n in (310,298,308,309,318,319):
        o,_=decode(updates[lookup[n]]);x,z=f.MACHINE[n]
        whole[:len(o),z*16:z*16+16,x*16:x*16+16]=o
    assert np.array_equal(whole,m.occ)
    assert np.array_equal(decode(updates[lookup[310]])[0],decode(updates[lookup[296]])[0])
    for z in (16,32):assert np.any(whole[:,z-1]&whole[:,z])
    assert np.any(whole[:,:,15]&whole[:,:,16])
    assert whole[0].any() and whole[28:].any()


def test_stack_witnesses_terminal_collision_and_actual_headroom(sources):
    _,art,rows,_,_,_=sources
    proof=f.stack_evidence(art,rows)
    lookup={r['source_ordinal']:r['key'] for r in rows}
    repeat=proof['amendments'][lookup[196]]['occurrences']
    first=[p for p in repeat if p['map'].endswith('_1F')]
    assert len(first)==5 and sum(p['kind']==7 for p in first)==2
    assert all(p['below_key']==lookup[196] for p in first if p['kind']==0)
    assert all(p['below_key']!=lookup[196] for p in first if p['kind']==7)
    assert all(w['headroom_blocks']>=8 for w in proof['headroom_witnesses'])
    for n in (140,161):
        p=proof['amendments'][lookup[n]]
        assert len(p['occurrences'])==4
        assert all(o['below_key']==p['on'] and o['below_kind']==7 for o in p['occurrences'])
    for n in (141,163):
        p=proof['amendments'][lookup[n]]
        assert len(p['occurrences'])==1 and p['semantic_inference']
        assert p['occurrences'][0]['below_key']==p['on']
        assert p['occurrences'][0]['kind']==p['occurrences'][0]['below_kind']==7


def test_devon_template_keeps_shared_wall_unchanged_and_open_underside(sources):
    baseline,art,_,updates,_,_=sources
    from tools.shape_fit.emerald.parts_model import build
    template,proof,drawing=f.devon_template(art)
    assert 'g3q/840-840-840-840' not in updates
    assert len(template['tiles'])==10 and all(len(r)==10 for r in template['tiles'])
    assert proof['occurrences']==[{'map':'EM_RUSTBORO_CITY_DEVON_CORP_3F','x':7,'y':3}]
    o,_=build(template,np.asarray(drawing))
    assert not o[0:6,23:55,9:70].any()
    assert o[6:8,14:64,1:79].all()
    assert o[0].any() and template['height']==8

