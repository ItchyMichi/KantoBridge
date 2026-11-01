"""Ground the two source stair-edge stripes at the residential warp threshold."""
import argparse,copy,hashlib
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE
from tools.shape_fit.emerald_pass.review import read,TABLE,entry_hash
from tools.shape_fit.emerald_pass.inventory import contact_pages
from tools.shape_fit.emerald_pass.ship_beams import previews
OUT=ROOT/'local/model-gallery/emerald-pass-study/residential-threshold-repair'
KEY='g3q/840-840-2938-2939'
IDS=('emerald_residential_a7687b40a2ff','emerald_residential_a79ff47e7877')

def generate(base,old):
    assert old.get('walk') is True and not old.get('on') and not old.get('seat')
    m=Model(height=2);m.box((0,16),(0,1),(11,13),0).box((0,16),(0,2),(13,15),0)
    m.paint[:]=np.arange(256,dtype=np.uint8).reshape(16,16)
    # Each tread retains its own horizontal source stripe. Source background
    # stays at floor depth and never becomes a header above the passage.
    entry=encode(m.occ,m.paint,old,'hand-emerald-residential-threshold-repair')
    source=read(OUT/'source-evidence.json')['sources'][0];assert len(source['occurrences'])==2 and all(p['collision']==114 for p in source['occurrences'])
    row={'key':KEY,'kind':'cell','decision':'changed','label':'Grounded descending bedroom stair threshold','family':'Residential shallow warp treads','baseline_entry_sha256':entry_hash(base['cells'][KEY]),'previous_entry_sha256':entry_hash(old),'occurrences':source['occurrences'],
        'reason':'QA17 whole stair openings confirm the old fitted model repeats its gold source floor stripes at y0 and y15, placing an unsupported bar across the passage. Both exact source cells are descending stair/warp114 thresholds; the two gold stripes lie only along the southern bottom of the source drawing. Author two grounded shallow tread surfaces at heights1 and2 across source z11..14, with horizontal native source paint. Remove the invented overhead strip; keep walk:true, the existing WALK/warp route and every other metadata field. The surrounding jambs/header remain untouched.',
        'evidence':[str(OUT/'source-evidence.json'),str(OUT/'source.png'),str(OUT/'contexts/page-001.png'),str(OUT/'runtime-proof.json')],'whole_entities':list(IDS)}
    return entry,row

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');a=ap.parse_args();base=read(BASELINE);pre=read(OUT/'preconditions.json');entry,row=generate(base,pre['cells'][KEY])
    if not (OUT/'parent-entities.json').exists():write_json(OUT/'parent-entities.json',[e for e in read(ROOT/'tools/shape_fit/entities/emerald.json') if e['id'] in IDS])
    entities=read(OUT/'parent-entities.json');assert len(entities)==2
    if not (OUT/'context-preconditions.json').exists():write_json(OUT/'context-preconditions.json',{'cells':{k:read(TABLE)['cells'][k] for e in entities for line in e['layout'] for k in line if k is not None}})
    for n,v in [('updates.json',{'cells':{KEY:entry},'buildings':{}}),('ledger.json',[row])]:write_json(OUT/n,v)
    if a.previews:
        art=EmeraldArt(BUNDLE);current=copy.deepcopy(base);current['cells'].update(read(OUT/'context-preconditions.json')['cells']);current['cells'][KEY]=entry;contact_pages('candidate',read(OUT/'source-evidence.json')['sources'],current,art,OUT);previews(entities,current,art,OUT/'whole-current')
    print('One grounded shallow stair threshold; both existing whole definitions preserved')
if __name__=='__main__':main()
