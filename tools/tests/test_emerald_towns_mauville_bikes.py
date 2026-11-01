"""Source-facing bicycle pair, hollow wheels and cap-safe cut timber posts."""
import copy,json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_mauville_bikes as t

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local exported source required')
    table=json.loads(t.BASELINE.read_text());old=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);updates=t.generate(table,art);assert table==old
    return table,art,t.rows_for('mauville'),updates

def test_exact_scope_metadata_and_source_materials(data):
    table,art,rows,updates=data;assert len(updates)==10
    meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
    for n in t.OWNED:
        k=rows[n-1]['key'];assert meta(updates[k])==meta(table['cells'][k]);o,p=t.decode(updates[k]);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'))
        colors={tuple(int(v) for v in c) for c in rgb[p[o]//16,p[o]%16]};assert colors<=set(t.WOOD_COLORS if n in t.WOOD else t.BIKE_COLORS)

def test_complete_bike_pair_slices_wheels_and_source_orientation(data):
    _,art,rows,updates=data;whole=t.bicycles();joined=np.concatenate([t.decode(updates[rows[n-1]['key']])[0] for n in (37,38,39)],axis=2)
    assert np.array_equal(joined[:24],whole.occ) and not joined[24:].any()
    for offset in (0,24):
        # Real air between wheel spokes; the topmost handlebar is left of
        # the saddle in the inspected original source drawing.
        assert not whole.occ[3,8,offset+4] and whole.occ[0,8,offset+5]
        assert whole.occ[17,:,offset:offset+12].any() and not whole.occ[17,:,offset+12:offset+24].any()
    for place in rows[36]['occurrences']:
        g=art.map_cells(art.maps[place['map']]);assert g[place['y']][place['x']:place['x']+3]==[rows[n-1]['key'] for n in (37,38,39)]

def test_each_post_cap_stays_light_and_every_occurrence_is_registered(data):
    _,art,rows,_=data
    for n in t.WOOD:
        m=t.posts(n);assert np.all(m.paint[13][m.occ[13]]>=2)
        for x,z in t.POSTS[n]:assert m.occ[:,max(0,z-1):z+1,max(0,x-1):x+1].any(axis=(1,2))[:14].all()
    registered={(c['map'],x,z) for c in t.fence_components(art) for x,z in c['members']}
    expected={(p['map'],p['x'],p['y']) for n in t.WOOD for p in rows[n-1]['occurrences']};assert registered==expected
