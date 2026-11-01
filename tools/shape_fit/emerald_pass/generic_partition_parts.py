"""Existing timber wall stacks, floor projection edges and retained stair jambs."""
from __future__ import annotations
import argparse,copy,hashlib,json
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE,INVENTORY
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.generic_soft_furnishings import occurrences
from tools.shape_fit.emerald_pass.generic_wall_backdrops import floor
from tools.shape_fit.emerald_pass.inventory import contact_pages
from tools.shape_fit.emerald_pass.ship_beams import previews

OUT=ROOT/'local/model-gallery/emerald-pass-study/generic-partition-parts'
STACKS={129:135,130:136};FLOORS={87,159};RETAIN={3,4};OWNED=set(STACKS)|set(STACKS.values())|FLOORS|RETAIN

def rows():return [dict(r,source_ordinal=n) for n,r in enumerate(read(INVENTORY/'building__generic_building/entries.json'),1) if n in OWNED]

def pillar(sprite,n):
    right=n in (130,136);upper=n in STACKS;m=Model(height=32)
    m.box((0,16),(0,32),(2,4),0)
    m.box((9,15) if right else (1,7),(0,32),(4,12),1)
    m.box((8,16) if right else (0,8),(0,3),(3,13),1)
    s=slice(16,32) if upper else slice(0,16);o=m.occ[s].copy();p=m.paint[s].copy()
    woodrect=(8,0,16,16) if right else (0,0,8,16)
    wood=material(sprite,(165,123,49),woodrect);paper=material(sprite,(214,189,115),(0,0,8,8) if right else (8,0,16,8))
    p=np.asarray([paper,wood],np.uint8)[p];yy,zz,xx=np.indices(o.shape)
    visible=o&(((zz==3)&((xx<9) if right else (xx>=7)))|(zz==11))
    p[visible]=((15-yy)*16+xx)[visible]
    return o,p

def generate(baseline,art,owned):
    updates={};ledger=[]
    for r in owned:
        n=r['source_ordinal'];key=r['key'];old=baseline['cells'][key];sprite=art.cell(key,'BAKED');places=occurrences(art,key)
        if n in RETAIN:
            assert {p['collision'] for p in places}=={144}
            label='Pale wall and mustard stair jamb';decision='retained'
            reason='Exact Mossdeep Game Corner basement source context identifies the two outer stair-wall jambs, with COUNTER144 source classification. Their existing hand-authored continuous wall mass and recessed inner edge match these component silhouettes; retain geometry and flags. Actual COUNTER map rendering stays on its existing native one-block route. This is component retention, not a new whole-stair or runtime-fitted claim.'
        else:
            o,p=floor(sprite) if n in FLOORS else pillar(sprite,n);updates[key]=encode(o,p,old,'hand-emerald-generic-partition-parts');decision='changed'
            label='Native floor projection along horizontal room divider' if n in FLOORS else 'Timber wall support '+('upper' if n in STACKS else 'lower')
            reason=('The actual WALK source cell combines the checkered floor with the narrow projected white top of the wall immediately south. Keep this on the unchanged native floor route; represent the catalog component as one native x/z ground plane, not a raised free-standing slab. No walk/seat/on flag is added.' if n in FLOORS else
                'All source occurrences form the existing two-row timber support with on→lower metadata. Build one continuous32-high vertical timber, grounded flared foot and shallow wallpaper backing before slicing. Native vertical wood detail stays on the post; surrounding wallpaper stays on the backing. Existing stack metadata and every collision remain unchanged.')
        ledger.append({'key':key,'kind':'cell','decision':decision,'label':label,'family':'Generic partition and jamb components','reason':reason,
            'baseline_entry_sha256':entry_hash(old),'occurrences':places,'source_ordinal':n,'evidence':[str(OUT/'source-evidence.json'),str(OUT/'preview/assemblies/index.json')]})
    return updates,ledger

def assemblies(art,owned):
    at={r['source_ordinal']:r['key'] for r in owned};out=[]
    for ns in [[a,b] for a,b in STACKS.items()]+[[n] for n in sorted(FLOORS|RETAIN)]:
        layout=[[at[n]] for n in ns];stack=len(ns)==2
        places=[p for p in art.locations[layout[0][0]] if not stack or art.map_cells(art.maps[p['map']])[p['y']+1][p['x']]==layout[1][0]]
        assert len(places)==len(art.locations[layout[0][0]])
        out.append({'id':'emerald_generic_partition_'+hashlib.sha256(json.dumps(layout).encode()).hexdigest()[:12],
            'kind':'stack (model as one)' if stack else 'object (model as one)',
            'note':'Complete timber support and wall backing' if stack else 'Source floor/jamb component',
            'layout':layout,'placements':[{'source':[0,z],'offset':[0,1-z if stack else 0,0]} for z in range(len(ns))],'occurrences':places})
    return out

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    art=EmeraldArt(BUNDLE);baseline=read(BASELINE);owned=rows();updates,ledger=generate(baseline,art,owned);entities=assemblies(art,owned)
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),
        ('source-evidence.json',{'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'sources':[dict(r,occurrences=occurrences(art,r['key'])) for r in owned],
            'limits':'Timber stacks preserve existing on topology. Floor and stair-jamb components retain their original non-fitted native map routes; no whole-stair claim. Minecraft QA pending.'})]:write_json(OUT/name,value)
    if args.previews:
        current=copy.deepcopy(baseline);current['cells'].update(updates);contact_pages('current',owned,current,art,OUT/'preview');previews(entities,current,art,OUT/'preview/assemblies')
    print('6 changed;2 retained;6 complete/component comparisons;metadata unchanged')

if __name__=='__main__':main()
