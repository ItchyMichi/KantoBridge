"""Explicit four-high berry soil footprints, keeping surrounding grass at native floor."""
from __future__ import annotations
import argparse,copy,hashlib,json
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE
from tools.shape_fit.emerald_pass.review import read,TABLE,entry_hash
from tools.shape_fit.emerald_pass.inventory import contact_pages
from tools.shape_fit.emerald_pass.ship_beams import previews
from tools.shape_fit.emerald_pass import dirt_path_repairs as prior
OUT=ROOT/'local/model-gallery/emerald-pass-study/soil-footprint-repairs'
NW='g3q/10658-10659-10659-11194';N='g3q/10658-10659-11197-11198';NE='g3q/10658-10659-11199-10658'
SW='g3q/10658-11202-10659-17463';S='g3q/11204-11205-11221-11220';SE='g3q/16854-10659-16863-10658'
KEYS=(NW,N,NE,SW,S,SE,prior.LEFT,prior.RIGHT)
# Hand-authored plan extents from the inspected source; source colors never
# compute occupancy. All pieces retain the accepted four-voxel soil height.
RECTS={NW:((12,16,11,14),(10,16,14,16)),N:((0,16,10,16),),NE:((0,4,11,14),(0,5,14,16)),
       SW:((10,16,0,10),(11,16,10,12),(13,16,12,14)),S:((0,16,0,14),),SE:((0,6,0,9),(0,5,9,12),(0,3,12,14)),
       prior.LEFT:((11,16,1,5),(9,16,5,11),(10,16,11,16)),prior.RIGHT:((0,5,1,5),(0,7,5,11),(0,6,11,16))}
SOIL=((132,90,90),(156,115,115),(189,148,140))

def rows():
    rr=read(OUT/'initial-source.json')['sources'];old=read(prior.OUT/'source-evidence.json')['sources']
    return [*rr,*[r for r in old if r['key'] in (prior.LEFT,prior.RIGHT)]]

def generate(art,base,pre):
    updates={};ledger=[]
    for r in rows():
        k=r['key'];old=pre['cells'][k];m=Model(height=4)
        for x0,x1,z0,z1 in RECTS[k]:m.box((x0,x1),(0,4),(z0,z1),0)
        rgb=np.asarray(art.cell(k,'BAKED').convert('RGB')).reshape(-1,3)
        uv=np.asarray([np.flatnonzero((rgb==c).all(axis=1))[0] for c in SOIL if np.any((rgb==c).all(axis=1))],np.uint8);assert len(uv)>=2
        dist=((rgb[:,None,:].astype(int)-rgb[uv][None,:,:].astype(int))**2).sum(axis=2)
        m.paint[:]=uv[dist.argmin(axis=1)][np.arange(256,dtype=np.uint8).reshape(16,16)]
        updates[k]=encode(m.occ,m.paint,old,'hand-emerald-soil-footprint-repair')
        ledger.append({'key':k,'kind':'cell','decision':'changed','label':'Berry soil '+('northwest cap','north edge','northeast cap','southwest cap','south edge','southeast cap','west cap','east cap')[KEYS.index(k)],
            'family':'Berry bed soil footprint','baseline_entry_sha256':entry_hash(base['cells'][k]),'previous_entry_sha256':entry_hash(old),'occurrences':r['occurrences'],
            'reason':'Whole source and Minecraft QA identify soil in native grass, with berry objects on the SOLID center. The former footprint raised large green grass regions into a skirt or disconnected strips. Author an explicit continuous soil-only plan with rounded stepped end caps at the unchanged four-voxel height; keep native soil detail, and use only its own soil colors on sides. The two recently repaired west/east gap caps are refined from full tiles to their actual soil footprints. Preserve all source collisions, objects and fitted metadata; no WALK decoration or native roof/floor routing is changed. Pure-soil vertical body and reviewed horizontal/Lilycove middle geometry stay untouched.',
            'evidence':[str(OUT/'source-evidence.json'),str(OUT/'preview/assemblies/index.json'),str(OUT/'study/contexts/page-01.png')]})
    return updates,ledger

def assemblies(art):
    fitted=read(BASELINE)['cells'];found={};soil=set(KEYS)|{prior.MIDDLE,prior.BODY,*prior.CAPS}
    def add(source,p):
        layout=[[k if k in soil else None for k in row] for row in source]
        # The old Route119 short bed has a neighboring conifer projection,
        # explicitly outside this soil model and unchanged by this repair.
        if p['map']=='EM_ROUTE119' and source[0][0]==prior.LEFT:layout[1][0]=None
        stamp=json.dumps(layout,separators=(',',':'));ident='emerald_soil_footprint_'+hashlib.sha256(stamp.encode()).hexdigest()[:12]
        native_missing=sorted({k for row in source for k in row if k not in fitted})
        partial=p['map']=='EM_ROUTE121'
        e=found.setdefault(ident,{'id':ident,'kind':'object (model as one)','note':('Partial northern soil rim; southern native unfitted bed remains separate and is not represented as reviewed whole geometry' if partial else 'Fitted soil pieces of the complete berry-bed source context; native grass floors and berry objects remain separate'),'layout':layout,'reference_layout':source,
            'native_unfitted_keys':native_missing,'complete_fitted_soil':not partial,
            'placements':[{'source':[x,z],'offset':[x,0,z]} for z,row in enumerate(layout) for x,k in enumerate(row) if k is not None],'occurrences':[]})
        if p not in e['occurrences']:e['occurrences'].append(p)
    for e in prior.assemblies(art):
        for p in e['occurrences']:add(e['reference_layout'],p)
    for p in art.locations[N]:
        g=art.map_cells(art.maps[p['map']]);x,z=p['x'],p['y']
        if g[z][x-1]==N:continue
        end=x
        while end<len(g[z]) and g[z][end]==N:end+=1
        bottom=z+1
        while g[bottom][x]==prior.BODY:bottom+=1
        add([row[x-1:end+1] for row in g[z:bottom+1]],{'map':p['map'],'x':x-1,'y':z,'palette':'BAKED'})
    return list(found.values())

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');a=ap.parse_args();art=EmeraldArt(BUNDLE);base=read(BASELINE)
    if not (OUT/'initial-source.json').exists():write_json(OUT/'initial-source.json',read(OUT/'source-evidence.json'))
    pre=read(OUT/'preconditions.json')
    if not (OUT/'prior-cap-preconditions.json').exists():write_json(OUT/'prior-cap-preconditions.json',{'cells':{k:read(TABLE)['cells'][k] for k in (prior.LEFT,prior.RIGHT)}})
    pre['cells'].update(read(OUT/'prior-cap-preconditions.json')['cells']);updates,ledger=generate(art,base,pre);contexts=assemblies(art)
    entities=[e for e in contexts if all(k in KEYS for row in e['reference_layout'] for k in row)]
    partial=[dict(e,complete_fitted_soil=False,review_status='study-only: incomplete source-native or unreviewed soil perimeter context; not a whole-model approval') for e in contexts if e not in entities]
    if not (OUT/'context-preconditions.json').exists():write_json(OUT/'context-preconditions.json',{'cells':{k:read(TABLE)['cells'][k] for e in entities for row in e['layout'] for k in row if k is not None}})
    for n,v in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('partial-contexts.json',partial),('source-evidence.json',{'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'sources':rows(),'explicit_rectangles_xxz_z':RECTS,'groups':entities,'partial_contexts':partial,'limits':'Fitted soil footprints only; native grass/floor/roof/berry-object routes are untouched. Only two wholly fitted source variants are emitted as complete model comparisons. Other seven contexts contain unrepresented native/perimeter soil and remain study-only. Corrected Minecraft comparison pending.'})]:write_json(OUT/n,v)
    if a.previews:
        cur=copy.deepcopy(base);cur['cells'].update(read(OUT/'context-preconditions.json')['cells']);cur['cells'].update(updates);contact_pages('current',copy.deepcopy(rows()),cur,art,OUT/'preview');previews(entities,cur,art,OUT/'preview/assemblies')
    print(len(updates),'soil footprint repairs',len(entities),'whole variants',len(partial),'partial study contexts remain unapproved')
if __name__=='__main__':main()
