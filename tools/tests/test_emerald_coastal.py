import numpy as np
import pytest

from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import coastal as c
from tools.shape_fit.emerald_pass.review import read


@pytest.fixture(scope='module')
def sources():
    if not c.BASELINE.exists() or not c.BUNDLE.exists():
        pytest.skip("user's local source export and baseline required")
    table,art,rows=read(c.BASELINE),EmeraldArt(c.BUNDLE),c.inventory_rows()
    updates,ledger=c.generate(table,art,rows)
    return table,art,rows,updates,ledger


def test_only_owned_geometry_changes_and_source_native_tree_materials(sources):
    table,art,rows,updates,ledger=sources
    assert len(rows)==64 and len(ledger)==62 and len(updates)==61
    assert not set(updates)&set(c.KEYS.values())
    for row in ledger:
        assert row['baseline_entry_sha256']==c.entry_hash(table['cells'][row['key']])
    for key,entry in updates.items():
        meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
        assert meta(entry)==meta(table['cells'][key])
        occ,paint=decode(entry)
        assert occ.any() and all(layer.any() for layer in np.split(occ,len(occ)//16))
        if entry['from']=='hand-emerald-coastal-tree':
            a=np.asarray(art.cell(key,'BAKED').convert('RGB'))
            assert set(map(tuple,a[paint[occ]//16,paint[occ]%16]))<=set(c.LEAVES)|set(c.BARK)


@pytest.mark.parametrize('large',[False,True])
def test_whole_tree_connected_grounded_and_seams_match(large):
    occ,leaf,wood=c.coastal_tree(large)
    assert wood[0].any() and not leaf[:8].any()
    assert not np.any(leaf&wood)
    for edge in range(16,occ.shape[1],16):
        assert np.any(occ[:,edge-1]&occ[:,edge])
        assert np.any(occ[:,:,edge-1]&occ[:,:,edge])
    # Flood-fill real occupancy: trunks, branches and lobes must be one object.
    first=tuple(np.argwhere(occ)[0]);visited={first};pending=[first]
    while pending:
        p=pending.pop()
        for axis in range(3):
            for delta in (-1,1):
                q=list(p);q[axis]+=delta;q=tuple(q)
                if all(0<=q[i]<occ.shape[i] for i in range(3)) and occ[q] and q not in visited:
                    visited.add(q);pending.append(q)
    assert len(visited)==int(occ.sum())


def test_assemblies_cover_every_tree_hedge_and_sea_quarter_at_real_placements(sources):
    _,art,rows,updates,ledger=sources
    entities,placements=c.assemblies_for(art,rows,updates)
    covered={k for e in entities for row in e['layout'] for k in row if k}
    families={'broadleaf tree','coastal tree','large coastal tree','clipped hedge','pale sea boulder'}
    assert {r['key'] for r in ledger if r['family'] in families}<=covered
    assert len(entities)==23
    for item in placements:
        assert item['occurrences']
        for place in item['occurrences']:
            grid=art.map_cells(art.maps[place['map']]);x,y=place['x'],place['y']
            for dz,row in enumerate(item['layout']):
                for dx,key in enumerate(row):
                    assert key is None or grid[y+dz][x+dx]==key


def test_hedges_are_grounded_low_paths_and_do_not_use_mint_grass(sources):
    _,art,rows,updates,_=sources
    for row in rows:
        if row['group']!=c.PETALBURG or row['contact_position'] not in c.HEDGES:continue
        occ,paint=decode(updates[row['key']]);a=np.asarray(art.cell(row['key'],'BAKED').convert('RGB'))
        assert not occ[8:].any() and np.array_equal(occ[0],occ[7])
        assert set(map(tuple,a[paint[occ]//16,paint[occ]%16]))<=set(c.LEAVES[:3])
