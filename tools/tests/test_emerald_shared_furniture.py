import numpy as np
import pytest

from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import shared_furniture as f
from tools.shape_fit.emerald_pass.review import read


@pytest.fixture(scope='module')
def sources():
    if not f.BASELINE.exists() or not f.BUNDLE.exists():
        pytest.skip("user's source export and exact baseline required")
    table,art,rows=read(f.BASELINE),EmeraldArt(f.BUNDLE),f.inventory_rows()
    return table,art,rows,*f.generate(table,art,rows)


def test_exact_ownership_metadata_and_absent_cap(sources):
    table,art,rows,updates,ledger,added,added_ledger=sources
    assert len(rows)==len(updates)==len(ledger)==34
    assert set(updates)=={r['key'] for r in rows}
    for key,entry in updates.items():
        meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
        assert meta(entry)==meta(table['cells'][key])
        occ,_=decode(entry)
        assert all(part.any() for part in np.split(occ,len(occ)//16))
    assert set(added)=={f.CAP} and f.CAP not in table['cells']
    assert added_ledger[0]['baseline_absent'] is True
    assert {p['map'] for p in art.locations[f.CAP]}=={'EM_MT_CHIMNEY_CABLE_CAR_STATION','EM_ROUTE112_CABLE_CAR_STATION'}
    occ,_=decode(added[f.CAP])
    assert occ[:,:4,:].any() and not occ[:,4:,:].any()
    assert added[f.CAP]['seat'] is True


def test_furniture_materials_exclude_carpet_wall_and_caution_stripe(sources):
    _,art,rows,updates,_,added,_=sources
    for row in rows:
        n,key=row['contact_position'],row['key'];o,p=decode(updates[key]);a=np.asarray(art.cell(key,'BAKED').convert('RGB'))
        colors=set(map(tuple,a[p[o]//16,p[o]%16]))
        if n in f.ORANGE:
            assert colors<={(255,99,49),(222,74,33),(82,82,107)}
        if n in f.CABLE:
            assert colors<={(107,222,115),(74,181,99),(189,239,181),(66,74,107)}
        if n in f.SOFA:
            assert colors<={(66,74,107),(99,99,123),(115,123,165),(49,49,74)}


def test_sofa_supported_cushions_air_below_and_full_seams():
    m=f.sofa_parts();o=m.occ
    assert not o[0:5,16:24,8:20].any() # air under central seat
    assert o[0:5,11:13,3:5].all() # rear leg
    assert o[0:5,26:28,43:45].all() # front leg
    assert o[8:10,15:28,5:43].all() # seat cushion support
    assert o[8:20,10:14,2:46].all() # raised backrest
    assert np.any(o[:,15,:]&o[:,16,:])
    for x in (16,32):assert np.any(o[:,:,x-1]&o[:,:,x])


def test_bench_sections_join_and_keep_two_benches_separate(sources):
    _,art,rows,updates,_,added,_=sources
    lookup={r['contact_position']:r['key'] for r in rows}
    pieces=[decode(updates[lookup[n]])[0] for n in (396,405,413,405)]
    pieces.append(decode(added[f.CAP])[0])
    for a,b in zip(pieces,pieces[1:]):assert np.any(a[:,-1]&b[:,0])
    full=np.concatenate(pieces,axis=1)
    assert not full[:,36:44,:].any()
    assert full[0].any() and full[:,12:36].any() and full[:,44:68].any()


def test_rail_openings_and_exact_source_assemblies(sources):
    _,art,rows,updates,_,added,_=sources
    lookup={r['contact_position']:r['key'] for r in rows}
    o,_=decode(updates[lookup[433]])
    assert o[0].any() and not o[5:11,7:9,3:6].any()
    assert o[11:13,7:9,:].all()
    corner=decode(updates[lookup[233]])[0]
    north=decode(updates[lookup[427]])[0]
    east=decode(updates[lookup[439]])[0]
    assert np.any(north[:,-1,:]&corner[:,0,:])
    assert np.any(corner[:,:,-1]&east[:,:,0])
    entities,placements=f.assemblies_for(art,rows)
    assert len(entities)==18
    assert f.CAP in {k for e in entities for row in e['layout'] for k in row if k}
    for item in placements:
        assert item['occurrences']
        for place in item['occurrences']:
            grid=art.map_cells(art.maps[place['map']]);x,y=place['x'],place['y']
            for dz,row in enumerate(item['layout']):
                for dx,key in enumerate(row):assert key is None or grid[y+dz][x+dx]==key


def test_complete_harbor_l_run_is_one_connected_rail(sources):
    _,art,rows,updates,_,_,_=sources
    _,placements=f.assemblies_for(art,rows)
    item=next(p for p in placements if p['note']=='Metal guardrail run' and len(p['layout'])>1
              and any(o['map']=='EM_LILYCOVE_CITY_HARBOR' for o in p['occurrences']))
    layout=item['layout'];occ=np.zeros((16,len(layout)*16,len(layout[0])*16),bool)
    for z,row in enumerate(layout):
        for x,key in enumerate(row):
            if key:occ[:,z*16:(z+1)*16,x*16:(x+1)*16]=decode(updates[key])[0]
    first=tuple(np.argwhere(occ)[0]);visited={first};pending=[first]
    while pending:
        p=pending.pop()
        for axis in range(3):
            for delta in (-1,1):
                q=list(p);q[axis]+=delta;q=tuple(q)
                if all(0<=q[i]<occ.shape[i] for i in range(3)) and occ[q] and q not in visited:
                    visited.add(q);pending.append(q)
    assert len(visited)==int(occ.sum())
