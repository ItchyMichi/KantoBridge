"""Native timber paint only: remove source background from existing bridge volumes."""
from __future__ import annotations
import argparse,copy,hashlib,json
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import decode,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.inventory import contact_pages
from tools.shape_fit.emerald_pass.ship_beams import previews

OUT=ROOT/'local/model-gallery/emerald-pass-study/bridge-material-repairs'
STUDY=ROOT/'local/model-gallery/emerald-pass-study/bridge-repairs'
WOOD=((66,49,66),(99,66,82),(132,90,90),(156,115,115),(189,148,140),(222,181,165))
CLEAN='g3q/16066-16066-16066-16066'

def rows():
    return [r for r in read(STUDY/'source-evidence.json')['source_keys'] if r['family']=='timber bridge' and r['key']!=CLEAN]

def repaint(entry,sprite):
    occ,p=decode(entry);rgb=np.asarray(sprite.convert('RGB')).reshape(-1,3)
    uv=np.asarray([np.flatnonzero((rgb==c).all(axis=1))[0] for c in WOOD if np.any((rgb==c).all(axis=1))],np.uint8)
    assert len(uv)==6
    distance=((rgb[:,None,:].astype(int)-rgb[uv][None,:,:].astype(int))**2).sum(axis=2)
    lookup=uv[distance.argmin(axis=1)]
    # Native wood addresses keep their exact RGB; only background shades move.
    return occ,lookup[p]

def generate(art,base,pre):
    updates={};ledger=[]
    for r in rows():
        k=r['key'];old=pre['cells'][k];occ,p=repaint(old,art.cell(k,'BAKED'))
        oldocc,oldp=decode(old);assert np.array_equal(occ,oldocc)
        rgb=np.asarray(art.cell(k,'BAKED').convert('RGB')).reshape(-1,3)
        assert np.any(rgb[p[occ]]!=rgb[oldp[occ]]),k
        updates[k]=encode(occ,p,old,'hand-emerald-timber-material-repair')
        ledger.append({'key':k,'kind':'cell','decision':'changed','label':'Native timber bridge deck '+('south half' if k.startswith('g3q/14961-14961-') else 'north half'),
            'family':'Timber bridge material repair','baseline_entry_sha256':entry_hash(base['cells'][k]),'previous_entry_sha256':entry_hash(old),'occurrences':r['occurrences'],
            'reason':'Source and complete map neighborhoods show timber planks spanning water, cliff or foliage; small blue/green/gray/yellow perimeter pixels depict that background. Captured Minecraft comparisons exposed those pixels stretched along deck sides. Preserve every occupied voxel, deck dimensions, collision metadata and source placement; remap only non-wood paint to this key’s closest native wood shade. Preserve all existing wood colors. Native elevation-15 deck routing is a separate runtime path; this repairs fitted catalog material without asserting a map-placement change.',
            'evidence':[str(STUDY/'source-evidence.json'),str(OUT/'source-evidence.json'),str(OUT/'preview/assemblies/index.json')]})
    return updates,ledger

def assemblies(art):
    keys={r['key'] for r in rows()};found={}
    for name,m in art.maps.items():
        grid=art.map_cells(m);unseen={(x,z) for z,row in enumerate(grid) for x,k in enumerate(row) if k in keys}
        while unseen:
            todo=[min(unseen)];component=set()
            while todo:
                x,z=todo.pop()
                if (x,z) not in unseen:continue
                unseen.remove((x,z));component.add((x,z));todo.extend(((x-1,z),(x+1,z),(x,z-1),(x,z+1)))
            x0=min(x for x,z in component);x1=max(x for x,z in component)+1;z0=min(z for x,z in component);z1=max(z for x,z in component)+1
            assert z1-z0==2 and len(component)==(x1-x0)*2,(name,component)
            layout=[row[x0:x1] for row in grid[z0:z1]]
            ident='emerald_timber_bridge_'+hashlib.sha256(json.dumps(layout,separators=(',',':')).encode()).hexdigest()[:12]
            e=found.setdefault(ident,{'id':ident,'kind':'object (model as one)','note':'Complete source timber bridge; fitted deck material comparison, native elevation/deck routes preserved','layout':layout,
                'placements':[{'source':[x,z],'offset':[x,0,z]} for z in range(2) for x in range(x1-x0)],'occurrences':[]})
            e['occurrences'].append({'map':name,'x':x0,'y':z0})
    return list(found.values())

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');a=ap.parse_args();art=EmeraldArt(BUNDLE);base=read(BASELINE)
    pre={'cells':{**read(STUDY/'preconditions.json')['cells'],**read(STUDY/'companion-preconditions.json')['cells']}}
    updates,ledger=generate(art,base,pre);entities=assemblies(art)
    for n,v in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('preconditions.json',{'cells':{k:pre['cells'][k] for k in updates}}),
        ('source-evidence.json',{'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'source_study_sha256':hashlib.sha256((STUDY/'source-evidence.json').read_bytes()).hexdigest(),'native_wood_rgb':WOOD,'sources':rows(),'groups':entities,'limits':'Paint-only fitted catalog repair. Source kinds, native map decks, underpass art and floor routing remain unchanged. Fresh Minecraft comparison pending.'})]:write_json(OUT/n,v)
    if a.previews:
        current=copy.deepcopy(base);current['cells'].update(pre['cells']);current['cells'].update(updates)
        contact_pages('current',copy.deepcopy(rows()),current,art,OUT/'preview');previews(entities,current,art,OUT/'preview/assemblies')
    print(len(updates),'paint-only repairs',len(entities),'whole bridge variants',sum(len(e['occurrences']) for e in entities),'occurrences')
if __name__=='__main__':main()
