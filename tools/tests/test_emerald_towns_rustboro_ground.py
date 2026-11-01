"""Source top projection must preserve native deck occupancy and routing."""
import copy,json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_rustboro_ground as t

@pytest.fixture(scope='module')
def data():
    if not t.BASELINE.exists() or not t.BUNDLE.exists():pytest.skip('Local export required')
    table=json.loads(t.BASELINE.read_text());old=copy.deepcopy(table);art=t.EmeraldArt(t.BUNDLE);updates=t.generate(table,art);assert table==old
    return table,art,t.rows_for('rustboro'),updates

def test_scope_unchanged_occupancy_and_all_metadata(data):
    table,_,rows,updates=data;assert len(t.OWNED)==25 and len(updates)==19
    meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
    for n in t.CHANGED:
        k=rows[n-1]['key'];o,_=t.decode(table['cells'][k]);new,_=t.decode(updates[k]);assert np.array_equal(o,new);assert meta(table['cells'][k])==meta(updates[k])
    assert all(rows[n-1]['key'] not in updates for n in t.RETAINED)

def test_actual_native_top_coordinates_and_timber_sides(data):
    _,art,rows,updates=data
    for n in t.CHANGED:
        k=rows[n-1]['key'];o,p=t.decode(updates[k]);above=np.zeros_like(o);above[:-1]=o[1:];top=o&~above;y,z,x=np.indices(o.shape);assert np.array_equal(p[top],(z*16+x)[top].astype(np.uint8))
        if n not in t.WATER:
            sprite=np.asarray(art.cell(k,'BAKED').convert('RGB'));side=o&above;colors=sprite[p[side]//16,p[side]%16];assert np.all(colors==((214,181,107) if n in t.WARM else (189,173,107)))

def test_complete_assemblies_use_exact_exported_rectangles(data):
    _,art,_,_=data
    for e in t.assemblies(art):
        place=e['occurrences'][0];g=art.map_cells(art.maps[place['map']]);x,y=place['x'],place['y'];layout=e['layout'];assert [r[x:x+len(layout[0])] for r in g[y:y+len(layout)]]==layout
