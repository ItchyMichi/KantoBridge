"""Continuous native room walls behind projected furniture tops, and floor borders."""
from __future__ import annotations
import argparse,copy,hashlib,json
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE,INVENTORY
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.generic_soft_furnishings import occurrences
from tools.shape_fit.emerald_pass.inventory import contact_pages
from tools.shape_fit.emerald_pass.ship_beams import previews

OUT=ROOT/'local/model-gallery/emerald-pass-study/generic-wall-backdrops'
WALLS={10,17,51,52,57,58,59,69,70,90,91,94,95,96,106,107,112,113,154,155,168,200,201,212,213,214,223,224,237,238}
FLOORS={156,160};WOOD={223,224,237,238};LOWER={154,155}
PAIRS=[(51,52),(58,59),(69,70),(90,91),(95,96),(106,107),(112,113),(154,155),(200,201),(213,214),(237,238)]

def rows():return [dict(r,source_ordinal=n) for n,r in enumerate(read(INVENTORY/'building__generic_building/entries.json'),1) if n in WALLS|FLOORS]

def wall(sprite,n):
    m=Model();m.box((0,16),(0,16),(0,16));yy,zz,xx=np.indices(m.occ.shape)
    # All selected wall tips have independently inspected object occlusion in
    # their lowest pixels. Preserve native ceiling/moulding and wall pattern;
    # continue only the same cell's unobscured wall sample below the overlap.
    if n in LOWER:v=np.minimum(15-yy,10)
    elif n in WOOD:v=np.minimum(np.maximum(15-yy,4),7)
    else:
        v=15-yy;v=np.where(v>=10,8+(v-10)%2,v)
    uv=(v*16+xx).astype(np.uint8)
    target=(181,132,49) if n in WOOD else (222,222,156) if n>=168 else (214,214,181)
    side=material(sprite,(214,181,132) if n in LOWER else target,(0,0,16,8) if n in LOWER else (0,6 if n in WOOD else 8,16,8 if n in WOOD else 10))
    m.paint[:]=side;m.paint[:,15,:]=uv[:,15,:]
    # Top is an actual wall material, not the source's black outside-map band.
    m.paint[15,:,:]=material(sprite,(239,222,189) if n in WOOD else (239,239,222),(0,4 if n in WOOD else 0,16,8))
    return m.occ,m.paint

def floor(sprite):
    m=Model();m.box((0,16),(0,1),(0,16));_,zz,xx=np.indices(m.occ.shape)
    m.paint[:]=(zz*16+xx).astype(np.uint8)
    return m.occ,m.paint

def generate(baseline,art,owned):
    updates={};ledger=[];evidence=[]
    for r in owned:
        n=r['source_ordinal'];key=r['key'];old=baseline['cells'][key];sprite=art.cell(key,'BAKED')
        o,p=wall(sprite,n) if n in WALLS else floor(sprite);updates[key]=encode(o,p,old,'hand-emerald-generic-wall-backdrops')
        places=occurrences(art,key)
        for q in places:
            grid=art.map_cells(art.maps[q['map']]);q['below_key']=grid[q['y']+1][q['x']] if q['y']+1<len(grid) else None
        reason=('Actual source neighborhoods place a complete cabinet, television or fridge south of this wall. Its small top projection overlaps the lowest wall-art pixels; it is not a separate structure at the wall position. Replace the hollow/hourglass or isolated-plate mesh with one continuous wall panel, retaining the native ceiling band and wallpaper/wood background. Continue only unobscured source-local wall pixels below the furniture overlap, so the complete object supplies its own cap. This is a structural wall component, not a standalone furniture approval.' if n in WALLS else
            'This is the walkable southern room floor/border transition, repeated at actual exits and room edges. Use a one-voxel ground plane with native x/z floor projection for catalog geometry. Preserve absent walk/seat/on flags: normal map floor art remains the existing floor route, with no added fitted carrier or collision.')
        label='Native room wall behind furniture projection' if n in WALLS else 'Southern room floor and border plane'
        ledger.append({'key':key,'kind':'cell','decision':'changed','label':label,'family':'Generic wall backdrops and floor borders',
            'reason':reason,'baseline_entry_sha256':entry_hash(old),'occurrences':places,'source_ordinal':n,
            'evidence':[str(OUT/'source-evidence.json'),str(OUT/'preview/assemblies/index.json')]})
        evidence.append({'key':key,'source_ordinal':n,'source_pixel_mode':'RGBA','source_pixel_sha256':hashlib.sha256(sprite.tobytes()).hexdigest(),'occurrences':places})
    return updates,ledger,evidence

def assemblies(art,owned):
    at={r['source_ordinal']:r['key'] for r in owned};out=[];paired={v for p in PAIRS for v in p}
    patterns=[list(p) for p in PAIRS]+[[n] for n in sorted(WALLS|FLOORS-paired) if n not in paired]
    for ns in patterns:
        layout=[[at[n] for n in ns]];places=[]
        for p in art.locations[layout[0][0]]:
            g=art.map_cells(art.maps[p['map']]);x,y=p['x'],p['y']
            if g[y][x:x+len(ns)]==layout[0]:places.append(p)
        assert places,ns
        out.append({'id':'emerald_generic_wall_'+hashlib.sha256(json.dumps(layout).encode()).hexdigest()[:12],
            'kind':'object (model as one)','note':'Source wall segment behind furniture' if ns[0] in WALLS else 'Native room floor border component',
            'layout':layout,'placements':[{'source':[x,0],'offset':[x,0,0]} for x in range(len(ns))],'occurrences':places})
    return out

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    art=EmeraldArt(BUNDLE);baseline=read(BASELINE);owned=rows();updates,ledger,source=generate(baseline,art,owned);entities=assemblies(art,owned)
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),
        ('source-evidence.json',{'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'sources':source,
            'limits':'Source/local structural component review is separate from normal runtime routing and actual Minecraft QA.'})]:write_json(OUT/name,value)
    if args.previews:
        current=copy.deepcopy(baseline);current['cells'].update(updates)
        contact_pages('current',owned,current,art,OUT/'preview');previews(entities,current,art,OUT/'preview/assemblies')
    print(len(updates),'geometry-only changes;',len(entities),'wall/border comparisons')

if __name__=='__main__':main()
