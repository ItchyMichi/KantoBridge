"""Separate native shoreline bank matter from projected water in three fitted cells."""
from __future__ import annotations
import argparse,copy,hashlib
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE
from tools.shape_fit.emerald_pass.review import read,TABLE,entry_hash
from tools.shape_fit.emerald_pass.inventory import contact_pages
from tools.shape_fit.emerald_pass.interior_props import material

OUT=ROOT/'local/model-gallery/emerald-pass-study/shore-bank-repairs'
SOURCE=OUT.parent/'shoreline-source-study'
NORTH='g3q/11667-11676-11010-11001'
WEST='g3q/11754-11010-11763-11001'
TIP='g3q/12198-11010-11010-11001'
KEYS=(NORTH,WEST,TIP)
BROWN=((99,66,82),(132,90,90),(156,115,115),(189,148,140))

def bank(sprite,key):
    m=Model()
    # Source-oriented plan strips are authored explicitly. Brown source strokes
    # are a narrow extension of the adjoining cliff/bank, never blue water fins.
    if key==NORTH:m.box((0,16),(0,16),(0,3))
    elif key==WEST:m.box((0,3),(0,16),(0,16))
    elif key==TIP:
        m.box((0,4),(0,16),(0,2)).box((0,2),(0,16),(2,4))
    else:raise ValueError(key)
    rgb=np.asarray(sprite.convert('RGB')).reshape(-1,3)
    actual=[color for color in BROWN if np.any(np.all(rgb==color,axis=1))]
    assert actual
    uv=np.asarray([material(sprite,c) for c in actual],np.uint8)
    yy,zz,xx=np.indices(m.occ.shape)
    # Broad native stone facets, with source-local tones only. The tiny corner
    # source contains only its dark bank tone; it must not import another cell.
    shade=((xx//3+zz//3+yy//4)%len(uv));m.paint[:]=uv[shade]
    native=np.arange(256,dtype=np.uint8).reshape(16,16)
    is_bank=np.isin(native,[i for i,c in enumerate(rgb) if tuple(c) in BROWN])
    m.paint[15]=np.where(is_bank,native,m.paint[15])
    return m

def generate(art,base,pre):
    evidence=read(SOURCE/'source-evidence.json');updates={};ledger=[]
    roles={NORTH:'north bank edge',WEST:'west bank edge',TIP:'northwest bank tip'}
    for row in evidence['sources']:
        key=row['key'];old=pre['cells'][key];assert not any(old.get(f) for f in ('walk','seat','on'))
        assert {p['collision'] for p in row['occurrences']}=={7,41}
        m=bank(art.cell(key,'BAKED'),key);updates[key]=encode(m.occ,m.paint,old,'hand-emerald-shore-bank-repair')
        ledger.append({'key':key,'kind':'cell','decision':'changed','label':'Continuous shoreline '+roles[key],'family':'Source shoreline bank repair',
            'baseline_entry_sha256':entry_hash(base['cells'][key]),'previous_entry_sha256':entry_hash(old),'occurrences':row['occurrences'],
            'reason':'All125 playable SOLID source neighborhoods identify these three drawings as narrow brown bank edges next to blue water, not freestanding blue slabs or suspended fins. Author a grounded16-high strip or small corner tip joined to its north/west terrain boundary; paint solely from this cell’s brown stone pixels. Preserve the two-carrier fitted height and all metadata. Read-only actual-source evidence distinguishes112 playable fitted CELL2 uses from13 native SOLID columns, and1400+ native WATER uses: no walk/seat/stack flag is added and native routes remain unchanged. Ten exported padding positions are explicitly excluded from the125 playable SOLID count. Complete native water/bank context review remains required before visual approval.',
            'evidence':[str(SOURCE/'source-evidence.json'),str(SOURCE/'solid-contexts/index.json'),str(SOURCE/'runtime-proof.json'),str(SOURCE/'source-domain-audit.json'),str(OUT/'surface-runtime-proof.json'),str(OUT/'native-entities.json')]})
    assert set(updates)==set(KEYS)
    return updates,ledger

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');a=ap.parse_args();OUT.mkdir(exist_ok=True)
    art=EmeraldArt(BUNDLE);base=read(BASELINE)
    if not (OUT/'preconditions.json').exists():write_json(OUT/'preconditions.json',{'cells':{k:read(TABLE)['cells'][k] for k in KEYS}})
    updates,ledger=generate(art,base,read(OUT/'preconditions.json'))
    write_json(OUT/'updates.json',{'cells':updates,'buildings':{}});write_json(OUT/'ledger.json',ledger)
    write_json(OUT/'source-evidence.json',{'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'sources':read(SOURCE/'source-evidence.json')['sources'],
        'source_runtime_proof_sha256':hashlib.sha256((SOURCE/'runtime-proof.json').read_bytes()).hexdigest(),
        'complete_surface_runtime_proof_sha256':hashlib.sha256((OUT/'surface-runtime-proof.json').read_bytes()).hexdigest() if (OUT/'surface-runtime-proof.json').exists() else None,
        'limits':'Three complete native contexts preserve actual water half-blocks, floor levels, invisible collision/story carriers and exact original/current shapes. Source/Python and runtime/catalog checks are separate from actual Minecraft visual approval, which remains pending.'})
    if a.previews:
        current=copy.deepcopy(base);current['cells'].update(updates);contact_pages('candidate',copy.deepcopy(read(SOURCE/'source-evidence.json')['sources']),current,art,OUT)
    print('Three grounded source-local bank candidates; native water routing unchanged, complete native QA pending')

if __name__=='__main__':main()
