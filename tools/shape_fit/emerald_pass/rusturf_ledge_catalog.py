"""Match the fitted Rusturf ledge catalog silhouette to its proven native slab."""
import argparse,copy,hashlib
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.inventory import contact_pages
OUT=ROOT/'local/model-gallery/emerald-pass-study/rusturf-ledge-catalog'
KEY='g3q/25805-25805-25844-25844'

def generate(art,base,old):
    source=next(r for r in read(OUT.parent/'origin-ledge-study/sources.json')['sources'] if r['key']==KEY)
    assert len(source['occurrences'])==11 and all(p['collision']==163 for p in source['occurrences'])
    assert not any(old.get(f) for f in ('walk','seat','on'))
    m=Model(height=4);m.occ[:]=True;rgb=np.asarray(art.cell(KEY,'BAKED').convert('RGB')).reshape(-1,3)
    palette=[np.flatnonzero((rgb==c).all(axis=1))[0] for c in ((49,66,58),(82,99,90),(90,132,90)) if np.any((rgb==c).all(axis=1))];assert palette
    for y in range(4):m.paint[y]=palette[min(len(palette)-1,y*len(palette)//4)]
    m.paint[3]=np.arange(256,dtype=np.uint8).reshape(16,16)
    entry=encode(m.occ,m.paint,old,'hand-emerald-native-ledge-catalog')
    ledger={'key':KEY,'kind':'cell','decision':'changed','label':'Shallow mossy Rusturf ledge surface','family':'Native Rusturf ledge catalog companion','baseline_entry_sha256':entry_hash(base['cells'][KEY]),'previous_entry_sha256':entry_hash(old),'occurrences':source['occurrences'],
        'reason':'All11 exact source contexts are a shallow south-facing ledge lip of mossy terrain, raw163. Actual exported-map proof routes them to native four-voxel SLAB carriers independently of the fitted table. Replace the malformed tall, split standalone fitted catalog model with a continuous four-voxel slab, native moss artwork on top and source-local stone sides. Preserve every fitted/source metadata field and actual native routing. The complete terrain comparisons retain native slab geometry and surrounding wall heights; this correction changes the catalog only.',
        'evidence':[str(OUT.parent/'origin-ledge-study/sources.json'),str(OUT.parent/'origin-ledge-study/contexts/page-001.png'),str(OUT.parent/'environment-repairs/rusturf-plateau-runtime-proof.json'),str(OUT/'runtime-proof.json')]}
    return entry,ledger,source

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--previews',action='store_true');args=p.parse_args();art=EmeraldArt(BUNDLE);base=read(BASELINE);OUT.mkdir(exist_ok=True)
    if not (OUT/'preconditions.json').exists():write_json(OUT/'preconditions.json',{'cells':{KEY:read(OUT.parent/'origin-ledge-study/preconditions.json')['cells'][KEY]}})
    entry,row,source=generate(art,base,read(OUT/'preconditions.json')['cells'][KEY])
    for name,value in [('updates.json',{'cells':{KEY:entry},'buildings':{}}),('ledger.json',[row]),('source-evidence.json',{'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'sources':[source],'native_route':'raw163 LEDGE, native slab4, zero collision/decor; fitted catalog entry unused by this source route'})]:write_json(OUT/name,value)
    if args.previews:
        current=copy.deepcopy(base);current['cells'][KEY]=entry;contact_pages('candidate',[source],current,art,OUT)
    print('One source-proven native slab companion; source map routing unchanged')
if __name__=='__main__':main()
