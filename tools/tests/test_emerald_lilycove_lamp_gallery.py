import json,sys
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import lilycove_lamp_gallery as t

def test_exact_source_stack_entity_correction_preserves_geometry_and_identity():
    if not t.BASELINE.exists():pytest.skip('Local source required')
    art=t.EmeraldArt(t.BUNDLE);old,new=t.proposal(art);assert {**old,'kind':'stack (model as one)'}==new
    table=json.loads(t.LIVE.read_text());top,base=new['layout'][0][0],new['layout'][1][0];assert table['cells'][top]['on']==base
    found=t.select_entries(t.catalog(table,[new],art),[t.IDENT]);assert len(found)==1;assert found[0]['bounds']==[2,4,2] and found[0]['placement_mode']=='stack'
    loc=new['occurrences'][0];g=art.map_cells(art.maps[loc['map']]);assert [[r[loc['x']]] for r in g[loc['y']:loc['y']+2]]==new['layout']
