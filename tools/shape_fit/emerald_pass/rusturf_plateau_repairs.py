"""Continuous grounded Rusturf SOLID plateau sections; native ledges stay native."""
from __future__ import annotations
import argparse,base64,copy,hashlib
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE,INVENTORY
from tools.shape_fit.emerald_pass.review import read,TABLE,entry_hash
from tools.shape_fit.emerald_pass.inventory import contact_pages,compact_context_pages
OUT=ROOT/'local/model-gallery/emerald-pass-study/rusturf-plateau-repairs'
KEYS=('g3q/25853-25804-25877-25805','g3q/25804-25804-25805-25805','g3q/25804-25854-25805-25843','g3q/25877-25805-25878-25844','g3q/25805-25843-25844-25879')
LEDGE='g3q/25805-25805-25844-25844'
SIDES=((49,66,58),(82,99,90),(90,132,90),(90,173,99))

def rows(art):
    inventory={r['key']:r for r in read(INVENTORY/'general__rusturf_tunnel/entries.json')};result=[]
    for k in KEYS:
        r=copy.deepcopy(inventory[k]);r['occurrences']=[]
        for p in art.locations[k]:
            m=art.maps[p['map']];raw=base64.b64decode(m['cells']);i=(p['y']*m['w']+p['x'])*4;assert raw[i+2]==7
            r['occurrences'].append(dict(p,collision=raw[i+2],elevation=raw[i+3],map_cells_sha256=hashlib.sha256(raw).hexdigest()))
        result.append(r)
    return result

def model(sprite):
    m=Model();m.occ[:]=True;rgb=np.asarray(sprite.convert('RGB')).reshape(-1,3)
    palette=np.asarray([np.flatnonzero((rgb==c).all(axis=1))[0] for c in SIDES if np.any((rgb==c).all(axis=1))],np.uint8);assert len(palette)>=2
    y,z,x=np.indices(m.occ.shape)
    # Broad stone body below the source moss cap, never disconnected sprite shards.
    shade=np.clip((y/16*.62+.10+((x//4+z//4)%3)*.08),0,.999)
    m.paint[:]=palette[(shade*len(palette)).astype(int)]
    m.paint[15]=np.arange(256,dtype=np.uint8).reshape(16,16)
    return m

def generate(art,base,pre):
    updates={};ledger=[]
    for r in rows(art):
        k=r['key'];old=pre['cells'][k];m=model(art.cell(k,'BAKED'));updates[k]=encode(m.occ,m.paint,old,'hand-emerald-rusturf-plateau-repair')
        ledger.append({'key':k,'kind':'cell','decision':'changed','label':'Grounded mossy plateau '+('northwest','north middle','northeast','southwest','southeast')[KEYS.index(k)],
            'family':'Rusturf plateau SOLID terrain','baseline_entry_sha256':entry_hash(base['cells'][k]),'previous_entry_sha256':entry_hash(old),'occurrences':r['occurrences'],
            'reason':'Exact source neighborhoods identify continuous mossy plateau terrain above continuing cave walls. Captured Minecraft and local orthographic views exposed holes, suspended lips and disconnected source fragments. Replace those shards with a continuous grounded16-high mass and the native moss cap on its horizontal top, using only this cell’s own rock/green side colors. Preserve SOLID metadata, native source elevations and the existing two-carrier route. The southern middle is a distinct raw163 native LEDGE slab and is untouched; surrounding native nine-block walls are also untouched. Do not represent this terrain as a flat isolated3x2 object.',
            'evidence':[str(OUT/'source-evidence.json'),str(OUT/'preview/contexts/index.json'),str(OUT.parent/'environment-repairs/rusturf-plateau-runtime-proof.json')]})
    return updates,ledger

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');a=ap.parse_args();art=EmeraldArt(BUNDLE);base=read(BASELINE)
    if not (OUT/'preconditions.json').exists():write_json(OUT/'preconditions.json',{'cells':{k:read(TABLE)['cells'][k] for k in KEYS}})
    pre=read(OUT/'preconditions.json');updates,ledger=generate(art,base,pre);proof=OUT.parent/'environment-repairs/rusturf-plateau-runtime-proof.json'
    for n,v in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('source-evidence.json',{'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'sources':rows(art),'native_route_proof_sha256':hashlib.sha256(proof.read_bytes()).hexdigest(),'native_ledge_unchanged':LEDGE,'limits':'Source terrain components only; native ledge and tall surrounding native walls must be shown with truthful runtime context before complete-context approval.'})]:write_json(OUT/n,v)
    if a.previews:
        cur=copy.deepcopy(base);cur['cells'].update(updates);rr=rows(art);contact_pages('current',copy.deepcopy(rr),cur,art,OUT/'preview');compact_context_pages(rr,art,OUT/'preview/contexts')
    print('5 continuous SOLID plateau pieces; native LEDGE and surrounding walls untouched')
if __name__=='__main__':main()
