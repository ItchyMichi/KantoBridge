"""Complete source bed rims while preserving native floors and western cliffs."""
from __future__ import annotations
import argparse,copy,hashlib
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,decode,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.inventory import contact_pages,compact_context_pages
from tools.shape_fit.emerald_pass.soil_footprint_repairs import SOIL
OUT=ROOT/'local/model-gallery/emerald-pass-study/soil-perimeter-completion'
SW='g3q/10658-11219-10659-10658';S='g3q/11220-11221-10659-10658';SE='g3q/11222-10659-10659-10658'
CLIFF_N='g3q/17071-10659-17072-11194';CLIFF_M='g3q/17071-11202-17072-11203';CLIFF_S='g3q/17071-11219-17072-10658'
KEYS=(SW,S,SE,CLIFF_N,CLIFF_M,CLIFF_S)
# Explicit soil outline, independently authored from the inspected source.
# Western cliff columns x<8 are copied without alteration for the mixed keys.
RECTS={SW:((12,16,0,3),(13,16,3,5)),S:((0,16,0,5),),SE:((0,5,0,1),(0,4,1,3),(0,3,3,4)),
       CLIFF_N:((12,16,11,14),(10,16,14,16)),CLIFF_M:((10,16,0,16),),CLIFF_S:((12,16,0,3),(13,16,3,5))}

def generate(art,base,pre):
    rows={r['key']:r for r in read(OUT/'sources.json')['sources']};updates={};ledger=[]
    for key in KEYS:
        old=pre['cells'][key];m=Model(height=4)
        for x0,x1,z0,z1 in RECTS[key]:m.box((x0,x1),(0,4),(z0,z1),0)
        rgb=np.asarray(art.cell(key,'BAKED').convert('RGB')).reshape(-1,3)
        palette=np.asarray([np.flatnonzero((rgb==c).all(axis=1))[0] for c in SOIL if np.any((rgb==c).all(axis=1))],np.uint8)
        assert len(palette)>=2
        distance=((rgb[:,None,:].astype(int)-rgb[palette][None,:,:].astype(int))**2).sum(axis=2)
        m.paint[:]=palette[distance.argmin(axis=1)][np.arange(256,dtype=np.uint8).reshape(16,16)]
        mixed=key in (CLIFF_N,CLIFF_M,CLIFF_S)
        if mixed:
            occ,paint=decode(old);assert not occ[4:].any()
            m.occ[:,:,:8]=occ[:4,:,:8];m.paint[:,:,:8]=paint[:4,:,:8]
        updates[key]=encode(m.occ,m.paint,old,'hand-emerald-soil-perimeter-completion')
        r=rows[key]
        ledger.append({'key':key,'kind':'cell','decision':'changed','label':('Berry soil southwest tip','Berry soil southern lip','Berry soil southeast tip','Cliff-adjacent soil north tip','Cliff-adjacent soil west edge','Cliff-adjacent soil south tip')[KEYS.index(key)],
            'family':'Berry soil perimeter completion','baseline_entry_sha256':entry_hash(base['cells'][key]),'previous_entry_sha256':entry_hash(old),'occurrences':r['occurrences'],
            'reason':'Complete source beds show a narrow brown perimeter against level grass. The former fitted footprint raised that grass into a four-high skirt. Replace only the soil region with its explicit rounded plan and native brown surface pixels at unchanged height4. '+('Copy all western cliff occupancy and paint at x<8 byte-for-byte; raw161 source routing remains unchanged. ' if mixed else 'Source WALK metadata and native floor routing remain unchanged. ')+
            'No fitted entries are invented for the five native perimeter variants; actual planner companions preserve their native floor/berry columns. Pure-soil BODY and reviewed horizontal middle remain unchanged. Fresh Minecraft visual comparison is required.',
            'evidence':[str(OUT/'sources.json'),str(OUT/'whole-source.png'),str(OUT/'all-contexts/index.json'),str(OUT/'runtime-proof.json')]})
    return updates,ledger

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');a=ap.parse_args();art=EmeraldArt(BUNDLE);base=read(BASELINE);pre=read(OUT/'preconditions.json');updates,ledger=generate(art,base,pre)
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger)]:write_json(OUT/name,value)
    if a.previews:
        current=copy.deepcopy(base);current['cells'].update(pre['cells']);current['cells'].update(updates);rr=[r for r in read(OUT/'sources.json')['sources'] if r['key'] in KEYS]
        contact_pages('candidate',copy.deepcopy(rr),current,art,OUT)
        all_rows=[dict(r,occurrences=[p]) for r in rr for p in r['occurrences']];compact_context_pages(all_rows,art,OUT/'all-contexts')
    print('Six soil perimeter corrections; native five and western cliff routing preserved.')

if __name__=='__main__':main()
