"""Explicit link-room machines, Hall of Fame terminal and Palace furniture.

Each machine is authored whole before its source-key slices. Source-only WALK
projections remain references; corrected foot cells are flat native floor.
"""
import base64,copy,hashlib
from pathlib import Path
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,render
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.review import ROOT,BASELINE,GEOMETRY,read,entry_hash
from tools.shape_fit.emerald_pass.scenery import BUNDLE

REGISTRY=Path(__file__).with_name('cable_palace_registry.json')
OUT=ROOT/'local/model-gallery/emerald-pass-study/cable-palace'

def whole(role):
    if role=='trade':
        m=Model(width=32,height=32)
        for x in (0,16):
            m.box((x+2,x+14),(0,3),(3,14),0)
            m.box((x+1,x+15),(3,9),(2,14),1)
            m.box((x+2,x+14),(9,23),(3,13),1)
            m.ellipsoid((x+8,23,8),(6,8,5),1)
            m.box((x+4,x+12),(12,26),(12,14),0)
            m.box((x+5,x+11),(14,25),(13,15),3)
            m.box((x+4,x+12),(8,11),(13,16),2)
            m.box((x+6,x+10),(10,12),(14,16),0)
        m.box((14,18),(5,9),(5,11),0)
        return m
    if role=='pod':
        m=Model(height=32)
        m.cylinder((8,8),6.5,(0,3),0).cylinder((8,8),(5.5,5),(3,24),1)
        m.ellipsoid((8,24,8),(5.5,7,5),1)
        m.box((4,12),(17,27),(11,13),0).box((5,11),(18,26),(12,14),2)
        m.box((2,4),(16,26),(7,11),3).box((12,14),(16,26),(7,11),3)
        m.box((4,12),(6,14),(11,14),0).box((5,11),(7,13),(13,15),2)
        m.ellipsoid((8,10,14),(2.2,2.2,1.8),0)
        return m
    if role=='record':
        m=Model(width=64,depth=48)
        m.box((3,61),(0,3),(3,45),0).box((2,62),(3,11),(2,46),1)
        for x,z in ((0,0),(60,0),(0,44),(60,44)):m.box((x,x+4),(0,16),(z,z+4),cut=True)
        m.box((3,61),(11,13),(3,45),2)
        # Rear housings, sunken tray and two connected control strips.
        m.box((8,56),(10,15),(6,15),0).box((9,55),(12,16),(7,13),2)
        m.box((10,54),(12,16),(18,34),cut=True)
        m.box((10,54),(10,11),(18,34),0)
        m.box((6,58),(11,14),(37,42),1)
        m.box((12,52),(13,15),(38,41),3)
        return m
    if role=='hall_terminal':
        m=Model(width=48)
        m.box((5,43),(0,3),(2,14),0).box((7,41),(3,12),(3,14),1)
        m.box((9,39),(12,15),(4,13),2)
        m.box((15,33),(12,16),(4,10),0)
        m.box((16,32),(15,16),(5,9),4)
        m.box((12,36),(10,13),(12,16),2)
        m.box((16,32),(12,14),(14,16),3)
        for x in range(16,32,2):m.box((x,x+1),(12,14),(15,16),0)
        for x in (2,41):
            m.box((x,x+5),(3,7),(4,8),1).box((x,x+5),(3,7),(10,14),1)
        m.box((10,13),(10,14),(6,11),5).box((35,38),(10,14),(6,11),5)
        return m
    if role=='palace_panel':
        m=Model(width=48)
        m.box((0,48),(0,16),(3,6),0)
        m.box((7,41),(0,14),(6,10),1)
        m.box((9,39),(1,13),(10,12),2)
        m.box((11,22),(3,11),(12,14),3)
        m.box((24,37),(4,11),(12,14),2)
        return m
    if role=='palace_chair':
        m=Model();m.cylinder((8,8),5,(0,2),0).cylinder((8,8),2,(2,6),1)
        m.box((3,13),(6,9),(3,14),0).box((4,12),(8,10),(4,13),2)
        m.box((3,13),(9,16),(2,5),1).box((4,12),(10,15),(4,6),2)
        m.box((2,4),(8,12),(5,12),1).box((12,14),(8,12),(5,12),1)
        return m
    if role=='trade_chair':
        m=Model()
        for x in (3,11):
            for z in (3,11):m.box((x,x+2),(0,6),(z,z+2),0)
        m.box((3,13),(5,8),(3,13),1).box((2,5),(7,15),(3,13),0)
        m.box((3,5),(9,14),(4,12),1)
        return m
    if role=='trade_wall':
        m=Model(height=32);m.box((0,16),(0,32),(5,9),0)
        m.box((0,16),(12,28),(9,11),1)
        return m
    if role=='floor':return Model().box((0,16),(0,1),(0,16),0)
    raise ValueError(role)

def model(sprite,spec):
    role=spec['role'];full=whole(role);x,z,y=[16*spec.get(k,0) for k in ('x','z','layer')]
    h=32 if role=='trade' else 16
    m=Model(height=h);m.occ=full.occ[y:y+h,z:z+16,x:x+16].copy();sem=full.paint[y:y+h,z:z+16,x:x+16].copy()
    if spec.get('mirror'):
        m.occ=m.occ[:,:,::-1].copy();sem=sem[:,:,::-1].copy()
    if role=='floor':
        yy,zz,xx=np.indices(m.occ.shape);m.paint=((8+zz//2)*16+xx).astype(np.uint8);return m
    if role=='trade_chair':colors=[(80,85,100),(235,180,100)]
    elif role=='trade_wall':colors=[(90,130,150),(110,180,75)]
    elif role=='palace_chair':colors=[(75,85,105),(100,155,155),(235,235,205)]
    elif role=='palace_panel':colors=[(150,80,30),(85,115,115),(165,205,190),(245,250,235)]
    elif role=='hall_terminal':colors=[(65,70,95),(210,205,175),(245,240,210),(80,160,155),(70,70,95),(225,125,65)]
    else:colors=[(65,70,95),(175,190,190),(245,250,245),spec.get('accent',(115,160,175)),(70,70,95),(225,125,65)]
    addresses=[material(sprite,c,family='neutral' if role in ('trade','pod','record') and i<3 else None) for i,c in enumerate(colors)]
    m.paint=np.asarray(addresses,np.uint8)[sem]
    yy,zz,xx=np.indices(m.occ.shape)
    if role in ('trade_wall','palace_panel'):
        front=m.occ&~np.concatenate((m.occ[:,1:,:],np.zeros_like(m.occ[:,:1,:])),axis=1)
        m.paint[front]=((15-yy)*16+xx)[front]
    if role=='record':
        # The authored horizontal console top preserves its source-local panel
        # pattern. Vertical surfaces retain interior material, not floor paint.
        top=m.occ&~np.concatenate((m.occ[1:],np.zeros_like(m.occ[:1])),axis=0)
        m.paint[top]=(zz*16+xx)[top]
    return m

def generate(base,art):
    patch={};rows=[];metadata=[]
    for key,s in read(REGISTRY).items():
        old=base['cells'][key];assert entry_hash(old)==s['baseline_entry_sha256']
        if s.get('role'):
            new=model(art.cell(key,'BAKED'),s).entry(old,source='hand-emerald-cable-palace')
            for f,v in s.get('metadata',{}).items():
                if v is None:new.pop(f,None)
                else:new[f]=v
            patch[key]=new
            before={k:v for k,v in old.items() if k not in GEOMETRY};after={k:v for k,v in new.items() if k not in GEOMETRY}
            if before!=after:
                places=[]
                for loc in art.locations[key]:
                    src=art.maps[loc['map']];x,y=loc['x'],loc['y'];g=art.map_cells(src)
                    places.append(dict(loc,collision=base64.b64decode(src['cells'])[(y*src['w']+x)*4+2],below_key=g[y+1][x] if y+1<src['h'] else None))
                metadata.append(dict(key=key,before=before,after=after,source_pixel_sha256=hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest(),occurrences=places))
        rows.append(dict(s,key=key,kind='cell',decision='changed' if key in patch else 'retained',occurrences=art.locations[key],visual_status='Local authoring review pending; Minecraft comparison pending',evidence=['inventory/'+s['group'],'cable-palace-study','cable-palace/assemblies']))
    return patch,rows,metadata

def main():
    art=EmeraldArt(BUNDLE);base=read(BASELINE);patch,rows,metadata=generate(base,art)
    write_json(OUT/'updates.json',dict(cells=patch,buildings={}));write_json(OUT/'ledger.json',rows);write_json(OUT/'metadata-evidence.json',metadata)
    entities=[]
    for spec in read(REGISTRY.with_name('cable_palace_assemblies.json')):
        src=art.map_cells(art.maps[spec['map']]);x,y,w,h=spec['rect'];assert [r[x:x+w] for r in src[y:y+h]]==spec['source_layout']
        entities.append({f:copy.deepcopy(spec[f]) for f in ('id','kind','note','layout','placements','baseline_layout','baseline_placements','reference_layout','baseline_native_fragments') if f in spec})
    write_json(OUT/'entities.json',entities)
    for key,new in patch.items():render(new,art.cell(key,'BAKED'),OUT/'models'/key.split('/')[1])
    print(len(patch),'changes;',len(rows),'decisions;',len(entities),'assemblies;',len(metadata),'metadata amendments')

if __name__=='__main__':main()
