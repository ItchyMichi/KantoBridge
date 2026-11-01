import base64,json,sys
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import sootopolis_marker_gallery as t

def test_exact_walk_ground_mask_preserves_all_four_marker_pairs_and_source_art():
    if not t.BASELINE.exists():pytest.skip('Local source required')
    art=t.EmeraldArt(t.BUNDLE);old,new=t.proposal(art);m=art.maps['EM_SOOTOPOLIS_CITY'];raw=base64.b64decode(m['cells'])
    for z,row in enumerate(old['layout']):
        for x,k in enumerate(row):
            i=((19+z)*m['w']+30+x)*4;assert raw[i+2]==(0 if x==1 else 7);assert new['layout'][z][x]==(None if x==1 else k)
    table=json.loads(t.LIVE.read_text());found=t.select_entries(t.catalog(table,[new],art),[t.IDENT]);assert len(found)==1 and len(found[0]['source_keys'])==4;assert 'g3q/10684-10690-10679-10680' not in found[0]['source_keys']
