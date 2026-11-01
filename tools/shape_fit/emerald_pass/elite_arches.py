"""Exact Elite Four arches in the built open state; native story leaves stay separate."""
from __future__ import annotations
import argparse,base64,hashlib,json
from collections import Counter
import numpy as np
from tools.model_gallery import EmeraldArt,write_json,native_assembly_source
from tools.shape_fit.emerald.building_trials import box,paint
from tools.shape_fit.emerald.parts_model import build
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE,INVENTORY
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.environment_templates import tiles_for,swatch

OUT=ROOT/'local/model-gallery/emerald-pass-study/elite-arches'


def generate(art):
    rows=read(INVENTORY/'building__elite_four/entries.json');groups={}
    sites_sha=hashlib.sha256((art.bundle/'g3sites/all.json').read_bytes()).hexdigest()
    mode={'mode':'elite-open-arch-v1','sites_sha256':sites_sha}
    for n in (1,70):
        for p in art.locations[rows[n-1]['key']]:
            m=art.maps[p['map']];g=art.map_cells(m);raw=base64.b64decode(m['cells']);x,y=p['x'],p['y']
            raw_layout=[line[x:x+3] for line in g[y:y+2]]
            raw_kinds=[[raw[((y+z)*m['w']+x+c)*4+2] for c in range(3)] for z in range(2)]
            raw_sha=hashlib.sha256(raw).hexdigest()
            _,built,built_grid=native_assembly_source({'descriptor':{'occurrence':p,'source_layout':raw_layout},
                'record':{'map_cells_sha256':raw_sha,'built_source':mode}},art)
            layout=[line[x:x+3] for line in built_grid[y:y+2]]
            kinds=[[built[((y+z)*m['w']+x+c)*4+2] for c in range(3)] for z in range(2)]
            sig=hashlib.sha256(json.dumps(layout,separators=(',',':')).encode()).hexdigest()[:12]
            identifier='emerald_object_elite_arch_'+sig
            group=groups.setdefault(identifier,{'id':identifier,'layout':layout,'built_source':mode,'mosaic':n==70,'occurrences':[]})
            assert kinds in ([[7,7,7],[7,114,7]],[[7,0,7],[7,114,7]])
            group['occurrences'].append(dict(p,map_cells_sha256=raw_sha,source_kinds=kinds,
                raw_source_layout=raw_layout,raw_source_kinds=raw_kinds))
    templates=[];ledger=[]
    for g in groups.values():
        drawing=art.drawing(g['layout'],'BAKED').convert('RGB')
        blue=swatch(drawing,(49,107,148));light=swatch(drawing,(115,189,230))
        parts=[]
        for x in (0,32):
            parts.append(box([x,x+16],[24,32],[0,32],paint([x,0,x+16,32],light,blue,blue)))
        parts.append(box([16,32],[24,32],[24,32],paint([16,0,32,8],light,blue,blue)))
        template={'id':g['id'],'atlas':'g3q','tiles':tiles_for(g['layout']),'height':32,'interior':True,'terrain':[],'parts':parts}
        templates.append(template)
        ledger.append({'key':g['id'],'kind':'building','label':'Open '+('mosaic Champion' if g['mosaic'] else 'blue Elite room and hall')+' arch',
                       'family':'Elite Four complete indoor arch','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,
                       'proposed_entry_sha256':entry_hash(template),'source_pixel_mode':'RGB','source_pixel_sha256':hashlib.sha256(drawing.tobytes()).hexdigest(),
                       'occurrences':g['occurrences'],'reason':'Exact normalized 3x2 G3Built open pattern becomes one upright 32-high shallow arch at the terminal wall row. Native blue/mosaic wall art remains at front scale. The center stays clear for 24 voxels below its lintel and preserves the center WARP114 source. Source kinds are recorded separately for each occurrence: upper centers are WALK in rooms and SOLID in blue halls. Per-player closed leaves remain the existing native story overlay, outside this fitted model; no closed leaf cell additions or closed whole template is proposed.',
                       'evidence':[str(OUT/'source-evidence.json'),str(OUT/'inspection/index.json')]})
    assert len(templates)==2 and sorted(len(g['occurrences']) for g in groups.values())==[1,9]
    return templates,ledger,list(groups.values())


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    art=EmeraldArt(BUNDLE);templates,ledger,groups=generate(art);baseline=read(BASELINE)
    write_json(OUT/'additions.json',{'cells':{},'buildings':{t['id']:t for t in templates}});write_json(OUT/'added-ledger.json',ledger)
    write_json(OUT/'source-evidence.json',{'groups':groups,'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),
        'source_cells':{key:{'baseline_entry_sha256':entry_hash(baseline['cells'][key]) if key in baseline['cells'] else None,
                             'baseline_absent':key not in baseline['cells'],'occurrences':art.locations[key]} for g in groups for line in g['layout'] for key in line},
        'limits':'Actual normalized planner baseline/interface fixture and Minecraft QA pending. Raw closed leaf states remain native per-player overlays, not fitted or visually approved. Baseline companions must reconstruct exact built open state from the pinned story export. Open center warp and source collision remain unchanged.'})
    if args.previews:
        from tools.shape_fit import hand_check
        images=[]
        for t,g in zip(templates,groups):
            target=OUT/'inspection'/t['id'];target.mkdir(parents=True,exist_ok=True)
            drawing=art.drawing(g['layout'],'BAKED').convert('RGB');drawing.save(target/'source.png')
            o,c=build(t,np.asarray(drawing));hand_check.picture(o,c,target/'angled.png',6)
            hand_check.front(o,c,target/'front.png',6);hand_check.front(o.transpose(0,2,1),c.transpose(0,2,1,3),target/'side.png',6)
            images.append({'id':t['id'],'source':str(target/'source.png'),'front':str(target/'front.png'),'side':str(target/'side.png'),'angled':str(target/'angled.png')})
        write_json(OUT/'inspection/index.json',images)
    print('2 exact normalized open interior arches, 10 source occurrences; native closed leaves preserved')


if __name__=='__main__':main()
