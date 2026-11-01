"""Native gold jars and Steven's paired rock display cases; explicit source models."""
from __future__ import annotations
import argparse,copy,hashlib
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE,INVENTORY
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.generic_soft_furnishings import occurrences
from tools.shape_fit.emerald_pass.inventory import contact_pages
from tools.shape_fit.emerald_pass.ship_beams import previews

OUT=ROOT/'local/model-gallery/emerald-pass-study/generic-display-props'
OWNED={97,98,240,194,199,105,111};POTS={97,98,240}
SCRIPT='https://raw.githubusercontent.com/pret/pokeemerald/master/data/maps/MossdeepCity_StevensHouse/scripts.inc'

def rows():return [dict(r,source_ordinal=n) for n,r in enumerate(read(INVENTORY/'building__generic_building/entries.json'),1) if n in OWNED]

def jar(sprite):
    m=Model();body=material(sprite,(181,132,33),(3,6,13,14));gold=material(sprite,(239,181,66),(3,6,13,14))
    dark=material(sprite,(66,66,90),(5,1,11,6));rim=material(sprite,(214,165,66),(3,1,13,8))
    m.cylinder((8,8),4,(0,2),body).ellipsoid((8,6,8),(6,6,6),body)
    m.cylinder((8,8),4.5,(8,12),gold).cylinder((8,8),5,(11,14),rim)
    m.cylinder((8,8),3,(7,16),cut=True).cylinder((8,8),3,(6,7),dark)
    # Broad native gold glints follow the rounded bowl, without stretching its
    # source floor or gray opening over the exterior.
    yy,zz,xx=np.indices(m.occ.shape)
    m.paint[m.occ&(xx<5)&(zz>8)&(yy>3)&(yy<9)]=gold
    return m.occ,m.paint

def display(sprite,n):
    m=Model(height=32)
    m.box((2,14),(5,9),(3,13),0).box((2,14),(9,29),(4,12),1)
    m.box((2,14),(29,30),(4,12),2)
    for x in (2,12):
        for z in (3,11):m.box((x,x+2),(0,5),(z,z+2),0)
    lower=n==199;s=slice(0,16) if lower else slice(16,32)
    o=m.occ[s].copy();p=m.paint[s].copy()
    colors=[(181,148,82),(132,173,222),(99,107,140)]
    native=np.asarray([material(sprite,c,(1,0,15,11) if lower else (1,3,15,16)) for c in colors],np.uint8)
    p=native[p]
    yy,zz,xx=np.indices(o.shape)
    # Glass reflections, the specimen and carved pedestal motif remain native
    # front paint. The five-voxel feet use wood material, never striped floor.
    front=o&(zz>=11)&((yy>=5) if lower else (yy<13))
    p[front]=((15-yy)*16+xx)[front]
    return o,p

def refrigerator(sprite,n):
    m=Model(height=32)
    m.box((1,15),(1,30),(3,13),0).box((1,15),(30,32),(3,13),2)
    m.box((1,15),(16,17),(13,14),1).box((2,14),(0,1),(4,12),1)
    for lo,hi in ((8,13),(22,27)):m.box((3,5),(lo,hi),(13,15),3)
    s=slice(16,32) if n==105 else slice(0,16)
    native=np.asarray([material(sprite,c) for c in [(181,181,165),(66,66,90),(255,255,255),(132,132,156)]],np.uint8)
    return m.occ[s],native[m.paint[s]]

def generate(baseline,art,owned):
    updates={};ledger=[]
    for r in owned:
        n=r['source_ordinal'];key=r['key'];sprite=art.cell(key,'BAKED');old=baseline['cells'][key]
        o,p=jar(sprite) if n in POTS else refrigerator(sprite,n) if n in (105,111) else display(sprite,n)
        new=encode(o,p,old,'hand-emerald-generic-display-props');updates[key]=new
        reason=('Complete native gold jar has a round belly, grounded foot, narrower neck, thick rim and real open mouth. Source grass/floor and gray mouth pixels are excluded from exterior paint.' if n in POTS else
            'Sootopolis refrigerator has two actual SOLID source rows and an existing valid upper on→lower stack. Build one grounded32-high shell with white cap, separate door seam and raised handles, then split into the existing vertical layers. All source collision and metadata are preserved. Other floor-projected fridge variants use exact separate templates.' if n in (105,111) else
            'Two exact cases in Stevens House display rare rocks, identified by the primary map interaction script. Source glass reflections enclose the specimen above a carved gold pedestal with separate grounded feet. One coherent32-high case is split into its existing upper WALK on→lower SOLID stack; all metadata and collision are preserved. The native lower glass face carries the specimen, not an invented mirror or television.')
        ledger.append({'key':key,'kind':'cell','decision':'changed','label':'Open gold storage jar' if n in POTS else 'Refrigerator '+('upper door' if n==105 else 'lower door and foot') if n in (105,111) else 'Steven rock display '+('upper glass' if n==194 else 'specimen pedestal and feet'),
            'family':'Generic jars and rock displays','reason':reason,'baseline_entry_sha256':entry_hash(old),'occurrences':occurrences(art,key),
            'evidence':[str(OUT/'source-evidence.json'),str(OUT/'preview/assemblies/index.json')]+([SCRIPT] if n in (194,199) else [])})
    return updates,ledger

def assemblies(art,owned):
    at={r['source_ordinal']:r['key'] for r in owned};out=[]
    for pattern,label in [([[n]],'Complete open gold jar') for n in sorted(POTS)]+[([[194],[199]],'Complete Steven rock specimen display'),([[105],[111]],'Complete Sootopolis two-door refrigerator')]:
        layout=[[at[n] for n in row] for row in pattern];stack=len(layout)==2
        places=[p for p in art.locations[layout[0][0]] if not stack or art.map_cells(art.maps[p['map']])[p['y']+1][p['x']]==layout[1][0]]
        out.append({'id':'emerald_generic_display_'+hashlib.sha256(str(layout).encode()).hexdigest()[:12],
            'kind':'stack (model as one)' if stack else 'object (model as one)','note':label,'layout':layout,
            'placements':[{'source':[0,z],'offset':[0,1-z if stack else 0,0]} for z in range(len(layout))],
            'occurrences':places})
    return out

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    art=EmeraldArt(BUNDLE);baseline=read(BASELINE);owned=rows();updates,ledger=generate(baseline,art,owned);entities=assemblies(art,owned)
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),
        ('source-evidence.json',{'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'sources':[dict(r,occurrences=occurrences(art,r['key'])) for r in owned],
            'primary_semantic_reference':{'url':SCRIPT,'event':'RockDisplayEventScript','use':'Object identification only; no source code reused'},
            'limits':'Local source/geometry and runtime/Minecraft inspection remain separate.'})]:write_json(OUT/name,value)
    if args.previews:
        current=copy.deepcopy(baseline);current['cells'].update(updates)
        contact_pages('current',owned,current,art,OUT/'preview');previews(entities,current,art,OUT/'preview/assemblies')
    print('7 existing source cells;5 whole exhibits;metadata unchanged')

if __name__=='__main__':main()
