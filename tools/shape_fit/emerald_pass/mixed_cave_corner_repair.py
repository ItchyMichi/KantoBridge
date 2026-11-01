"""Source-local Seafloor cliff return with native WALK use preserved."""
from __future__ import annotations
import argparse,copy,hashlib
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE
from tools.shape_fit.emerald_pass.review import read,TABLE,entry_hash
from tools.shape_fit.emerald_pass.cave_mass_repairs import mass
from tools.shape_fit.emerald_pass.inventory import contact_pages,compact_context_pages
from tools.shape_fit.emerald_pass.ship_beams import previews
OUT=ROOT/'local/model-gallery/emerald-pass-study/mixed-cave-corner-repair'
KEY='g3q/15184-15185-15186-14825'
CAP='g3q/15050-15051-14667-14668'

def generate(art,base,pre):
    r=next(r for r in read(OUT.parent/'environment-repairs/entries.json') if r['key']==KEY);old=pre['cells'][KEY]
    assert sorted(p['collision'] for p in r['occurrences'])==[0,7,7,7]
    assert not any(old.get(f) for f in ('walk','seat','on'))
    m=mass(art.cell(KEY,'BAKED'),'southwest',KEY)
    entry=encode(m.occ,m.paint,old,'hand-emerald-mixed-cave-corner-repair')
    row={'key':KEY,'kind':'cell','decision':'changed','label':'Continuous Seafloor southwest cliff return','family':'Cave cliff mass repair',
        'baseline_entry_sha256':entry_hash(base['cells'][KEY]),'previous_entry_sha256':entry_hash(old),'occurrences':r['occurrences'],'captured_findings':r['captured_findings'],
        'reason':'All four source neighborhoods show a southwest return of the brown cave cliff. The three SOLID7 uses belong to continuous cliff terrain; the fourth Room4 position is WALK0. Replace the unsupported overhang/hollow body with the same grounded16-high mass and small southwest plan chamfer used by the adjoining reviewed Seafloor cliff family, painted solely from this source’s native stone with its front detail preserved. Keep every metadata field; no walk/seat/on flag is introduced, so the exceptional WALK source retains native floor routing. Fitted terrain context comparisons do not stand in for native map elevation validation.',
        'evidence':[str(OUT/'source-evidence.json'),str(OUT/'preview/contexts/index.json'),str(OUT/'preview/assemblies/index.json')]}
    extra=read(OUT/'extra-source.json');assert len(extra['occurrences'])==15 and all(p['collision']==7 for p in extra['occurrences'])
    oldcap=pre['cells'][CAP];capmass=mass(art.cell(CAP,'BAKED'),'north',CAP)
    capentry=encode(capmass.occ,capmass.paint,oldcap,'hand-emerald-mixed-cave-corner-repair')
    caprow={'key':CAP,'kind':'cell','decision':'changed','label':'Continuous stone cap beside southern cave exit','family':'Cave cliff mass repair',
        'baseline_entry_sha256':entry_hash(base['cells'][CAP]),'previous_entry_sha256':entry_hash(oldcap),'occurrences':extra['occurrences'],
        'reason':'Inspection of all15 exact source neighborhoods shows the same SOLID7 left stone cap beside a pale southern cave exit, never a freestanding stepped pile. The complete return preview exposed the original narrow pyramid detached from adjoining cliff blocks. Author a grounded continuous16-high stone section with native front detail, matching neighboring repaired cave masses; preserve every source and fitted metadata field. The separate pale exit and actual warp rows are outside this correction.',
        'evidence':[str(OUT/'extra-source.json'),str(OUT/'extra-study/all-contexts/page-001.png'),str(OUT/'preview/assemblies/index.json')]}
    return {KEY:entry,CAP:capentry},[row,caprow],[r,extra]

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');a=ap.parse_args();art=EmeraldArt(BUNDLE);base=read(BASELINE)
    grid=art.map_cells(art.maps['EM_SEAFLOOR_CAVERN_ROOM1']);layout=[r[14:17] for r in grid[14:16]]
    entity={'id':'emerald_seafloor_cliff_return_15184','kind':'object (model as one)','note':'Exact fitted Seafloor cliff return context; continuous terrain component, native map heights remain separate','layout':layout,
        'placements':[{'source':[x,z],'offset':[x,0,z]} for z in range(2) for x in range(3)],'occurrences':[{'map':'EM_SEAFLOOR_CAVERN_ROOM1','x':14,'y':14}]}
    if not (OUT/'preconditions.json').exists():write_json(OUT/'preconditions.json',{'cells':{k:read(TABLE)['cells'][k] for row in layout for k in row}})
    pre=read(OUT/'preconditions.json');updates,ledger,rr=generate(art,base,pre)
    for n,v in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',[entity]),('source-evidence.json',{'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'sources':rr,'groups':[entity],'limits':'Source and fitted terrain component inspected; native WALK routing remains unchanged and fresh Minecraft comparison is pending.'})]:write_json(OUT/n,v)
    if a.previews:
        cur=copy.deepcopy(base);cur['cells'].update(pre['cells']);cur['cells'].update(updates);contact_pages('current',copy.deepcopy(rr),cur,art,OUT/'preview');compact_context_pages(rr,art,OUT/'preview/contexts');previews([entity],cur,art,OUT/'preview/assemblies')
    print('2 continuous cliff masses; exceptional WALK source and separate cave exit unchanged')
if __name__=='__main__':main()
