"""Source-authored bicycles and New Mauville industrial scenery.

Machines and bicycles are designed whole, then sliced into their original
solid footprints. Projected WALK cells stay native floor; story gates keep
their original state renderer. No image extrusion determines geometry.
"""
import base64,copy,hashlib,math
from pathlib import Path
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,render
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.review import ROOT,BASELINE,GEOMETRY,read,entry_hash
from tools.shape_fit.emerald_pass.scenery import BUNDLE

REGISTRY=Path(__file__).with_name('bike_shop_registry.json')
ASSEMBLIES=Path(__file__).with_name('bike_shop_assemblies.json')
OUT=ROOT/'local/model-gallery/emerald-pass-study/bike-shop'

def rod(m,a,b,radius,paint):
    y,z,x=np.indices(m.occ.shape);p=np.stack((x+.5,y+.5,z+.5),axis=-1)
    a=np.asarray(a);delta=np.asarray(b)-a;t=np.clip(((p-a)*delta).sum(axis=-1)/(delta@delta),0,1)
    return m.put(((p-a-t[...,None]*delta)**2).sum(axis=-1)<=radius**2,paint)

def whole(role):
    if role=='bicycle':
        m=Model(width=32,height=24);y,z,x=np.indices(m.occ.shape)
        for cx in (6,25):
            ring=(x+.5-cx)**2+(y+.5-5.5)**2
            m.put((ring<=5.5**2)&(ring>=3.6**2)&(z>=6)&(z<10),0)
            rod(m,(cx,5.5,7.5),(cx,5.5,9.5),1.1,1)
            rod(m,(cx-4,5.5,8),(cx+4,5.5,8),.65,1)
            rod(m,(cx,1.5,8),(cx,9.5,8),.65,1)
        for a,b in [((6,5.5,8),(12,13,8)),((6,5.5,8),(16,5.5,8)),((12,13,8),(16,5.5,8)),((12,13,8),(23,13,8)),((16,5.5,8),(23,13,8)),((23,13,8),(25,5.5,8))]:rod(m,a,b,1,2)
        rod(m,(23,13,8),(22,18,8),1,1);rod(m,(22,18,5),(22,18,12),1,0)
        rod(m,(12,13,8),(11,16,8),1,1);m.box((8,15),(16,18),(5,11),0)
        rod(m,(16,5.5,5),(16,5.5,12),.8,1);m.box((14,18),(5,7),(11,14),0)
        return m
    if role=='generator':
        m=Model(width=64,depth=64,height=48)
        m.box((1,63),(0,3),(1,63),3)
        m.cylinder((32,24),24,(3,30),1)
        for angle in range(0,360,45):
            a=math.radians(angle);x,z=32+22*math.cos(a),24+22*math.sin(a)
            m.box((x-3,x+3),(3,23),(z-3,z+3),0)
        m.cylinder((32,24),24,(23,29),2)
        m.ellipsoid((32,29,24),(24,11,24),1)
        m.cylinder((32,24),18,(35,39),2)
        m.cylinder((32,24),12,(38,46),1)
        m.cylinder((32,24),13,(43,47),2)
        m.cylinder((32,24),7,(43,48),cut=True)
        m.cylinder((32,24),7,(41,43),0)
        m.box((24,40),(25,30),(45,47),0).box((27,37),(26,29),(47,48),4)
        return m
    if role=='compressor':
        m=Model(width=32,depth=48,height=40)
        m.box((1,31),(0,3),(1,47),3)
        m.cylinder((12,17),9,(3,31),1).ellipsoid((12,31,17),(9,8,9),2)
        m.box((9,15),(35,38),(15,19),0)
        # Three joined cream duct bends connect the tank to the front plinth.
        for a,b in [((4,29,20),(4,23,28)),((4,23,28),(9,15,30)),((9,15,30),(6,5,37))]:rod(m,a,b,3.5,3)
        m.box((1,15),(2,12),(34,47),3)
        for yy in (4,7,10):m.box((3,13),(yy,yy+1),(46,48),0)
        m.box((18,31),(2,17),(33,47),1).box((20,29),(13,16),(46,48),4)
        m.box((20,30),(6,10),(46,48),0)
        for xx in (21,24,27):m.box((xx,xx+1),(6,10),(47,48),3)
        for zz in (17,23):
            m.box((21,29),(14,20),(zz,zz+4),0).box((23,31),(15,19),(zz+1,zz+3),3)
        m.box((20,24),(11,21),(17,27),1)
        return m
    if role=='wall':
        m=Model(height=32);m.box((0,16),(0,25),(4,8),1).box((0,16),(0,3),(7,10),0)
        return m
    if role=='crate':
        m=Model();m.box((2,14),(0,12),(2,14),1)
        m.box((2,14),(11,14),(2,14),2).box((7,9),(0,14),(2,14),3)
        m.box((2,14),(0,2),(2,14),0)
        return m
    if role=='open_crate':
        m=Model();m.box((2,14),(0,12),(2,14),1).box((4,12),(3,16),(4,12),cut=True)
        m.box((2,4),(11,16),(2,14),2).box((12,14),(11,14),(2,14),2)
        m.box((2,14),(0,2),(2,14),0).box((4,12),(2,3),(4,12),0)
        return m
    if role=='bench':
        m=Model(width=32)
        for xx in (2,27):
            for zz in (2,11):m.box((xx,xx+3),(0,13),(zz,zz+3),0)
        m.box((2,30),(3,6),(3,13),1).box((1,31),(11,15),(1,15),2)
        m.box((5,27),(4,10),(3,12),cut=True)
        m.box((3,29),(5,10),(11,13),1)
        return m
    if role=='book_desk':
        m=Model(width=32,height=24)
        for xx in (2,28):
            for zz in (3,12):m.box((xx,xx+2),(0,11),(zz,zz+2),0)
        m.box((0,32),(10,12),(2,16),1)
        m.box((20,30),(12,14),(5,14),3).box((20,30),(14,16),(5,14),2)
        m.box((17,20),(12,13),(8,13),3)
        m.box((0,16),(12,24),(1,3),4)
        return m
    if role=='switch':
        m=Model();m.box((3,13),(0,3),(3,13),0).box((4,12),(3,5),(4,12),1)
        rod(m,(8,4,8),(8,12,5),2,2).box((5,11),(10,13),(3,7),2)
        return m
    if role=='stool':
        m=Model();m.cylinder((8,8),4,(0,2),0).cylinder((8,8),2,(2,7),1)
        m.cylinder((8,8),5.5,(7,9),0).ellipsoid((8,9,8),(5.5,2,5.5),2)
        return m
    if role=='gate':return Model().box((0,16),(0,16),(6,9),0)
    raise ValueError(role)

PALETTES={
 'bicycle':[(66,70,105),(225,230,220),(220,220,30)],
 'generator':[(95,75,85),(220,145,60),(250,195,130),(240,240,210),(180,235,90)],
 'compressor':[(70,80,100),(120,175,95),(190,225,160),(235,240,210),(190,245,40)],
 'wall':[(140,115,95),(175,175,140)],
 'crate':[(95,80,95),(180,135,75),(250,200,100),(225,175,85)],
 'open_crate':[(85,80,100),(180,135,75),(250,200,100)],
 'bench':[(65,75,100),(60,135,165),(230,175,65)],
 'book_desk':[(65,75,100),(235,185,80),(225,60,75),(245,245,225),(145,195,165)],
 'switch':[(65,75,100),(200,70,80),(240,80,80)],
 'stool':[(65,75,100),(160,180,155),(195,235,140)],
 'gate':[(120,170,185)],
}

def model(sprite,spec):
    role=spec['role'];full=whole(role);x,z,y=[16*spec.get(k,0) for k in ('x','z','layer')]
    h=16 if role=='wall' else full.occ.shape[0]
    m=Model(height=h);m.occ=full.occ[y:y+h,z:z+16,x:x+16].copy();sem=full.paint[y:y+h,z:z+16,x:x+16].copy()
    colors=copy.deepcopy(PALETTES[role])
    if role=='bicycle':colors[2]=spec['accent']
    addresses=[material(sprite,c) for c in colors];m.paint=np.asarray(addresses,np.uint8)[sem]
    if role in ('wall','gate'):
        yy,zz,xx=np.indices(m.occ.shape);face=m.occ&~np.concatenate((m.occ[:,1:],np.zeros_like(m.occ[:,:1])),axis=1)
        m.paint[face]=((15-yy)*16+xx)[face]
    if role=='book_desk':
        yy,zz,xx=np.indices(m.occ.shape)
        if spec['x']==0:
            pane=m.occ&(sem==4)&(zz==2)
            m.paint[pane]=(np.clip((23-yy)*6//12,0,5)*16+xx)[pane]
            top=m.occ&(sem==1)&(yy==11)
            m.paint[top]=((4+zz*9//16)*16+xx)[top]
    return m

def generate(base,art):
    patch={};ledger=[];metadata=[]
    for key,spec in read(REGISTRY).items():
        old=base['cells'].get(key,{'class':'voxels'})
        assert (entry_hash(old) if key in base['cells'] else None)==spec['baseline_entry_sha256']
        if spec.get('role'):
            new=model(art.cell(key,'BAKED'),spec).entry(old,'hand-emerald-bike-shop',trim=True)
            for k,v in spec.get('metadata',{}).items():
                if v is None:new.pop(k,None)
                else:new[k]=v
            patch[key]=new
            before={k:v for k,v in old.items() if k not in GEOMETRY};after={k:v for k,v in new.items() if k not in GEOMETRY}
            if before!=after:
                occurrences=[]
                for loc in art.locations[key]:
                    src=art.maps[loc['map']];xx,yy=loc['x'],loc['y'];grid=art.map_cells(src);raw=base64.b64decode(src['cells'])
                    occurrences.append(dict(loc,collision=raw[(yy*src['w']+xx)*4+2],below_key=grid[yy+1][xx],below_collision=raw[((yy+1)*src['w']+xx)*4+2]))
                metadata.append(dict(key=key,before=before,after=after,source_pixel_sha256=hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest(),occurrences=occurrences))
        row=dict(spec,key=key,kind='cell',decision='added' if key not in base['cells'] else 'changed' if key in patch else 'retained',occurrences=art.locations[key],visual_status='Source/context and original geometry inspected; final candidate and Minecraft QA pending')
        if key not in base['cells']:
            row.update(baseline_absent=True,source_pixel_sha256=hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest(),proposed_entry_sha256=entry_hash(patch[key]))
        ledger.append(row)
    return patch,ledger,metadata

def main():
    art=EmeraldArt(BUNDLE);base=read(BASELINE);patch,ledger,metadata=generate(base,art)
    write_json(OUT/'updates.json',dict(cells=patch,buildings={}));write_json(OUT/'ledger.json',ledger);write_json(OUT/'metadata-evidence.json',metadata)
    write_json(OUT/'entities.json',read(ASSEMBLIES))
    for key,new in patch.items():render(new,art.cell(key,'BAKED'),OUT/'models'/key.split('/')[1])
    print(len(ledger),'reviewed;',len(patch),'changed;',len(metadata),'metadata corrections')

if __name__=='__main__':main()
