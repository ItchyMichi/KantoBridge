"""Whole-object authoring for Slateport's Fan Club furniture and plant displays."""
import base64,copy,hashlib
from pathlib import Path
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,render
from tools.shape_fit.emerald_pass.building_interiors import plant
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.review import BASELINE,ROOT,GEOMETRY,entry_hash,read
from tools.shape_fit.emerald_pass.scenery import BUNDLE

REGISTRY=Path(__file__).with_name('fan_club_registry.json')
OUT=ROOT/'local/model-gallery/emerald-pass-study/fan-club'


def whole(role):
    if role in ('sofa_east','sofa_west'):
        m=Model(depth=32)
        for x in (3,11):
            for z in (3,27):m.box((x,x+2),(0,5),(z,z+2),0)
        m.box((2,14),(4,7),(2,30),0).box((3,13),(7,9),(3,29),1)
        m.box((11,14),(8,15),(2,30),2)
        m.box((2,14),(8,12),(1,4),2).box((2,14),(8,12),(28,31),2)
        m.box((3,11),(7,9),(15,17),2)
        if role=='sofa_west':m.occ=m.occ[:,:,::-1].copy();m.paint=m.paint[:,:,::-1].copy()
        return m
    if role=='sofa_north':
        m=Model(width=64,depth=32)
        for x in (3,29,45,59):
            for z in (14,27):m.box((x,x+2),(0,5),(z,z+2),0)
        m.box((2,62),(4,7),(12,30),0).box((4,60),(7,9),(15,28),1)
        m.box((3,61),(8,15),(12,16),2)
        m.box((1,5),(7,12),(12,30),2).box((59,63),(7,12),(12,30),2)
        for x in (17,33,49):m.box((x,x+1),(7,9),(16,28),2)
        return m
    if role=='glass_table':
        m=Model(width=64,depth=32)
        for x in (2,60):
            for z in (8,27):m.box((x,x+2),(0,9),(z,z+2),0)
        m.box((2,62),(6,9),(7,30),0)
        m.box((4,60),(6,10),(9,28),cut=True)
        m.box((4,60),(8,9),(9,28),1)
        m.box((2,62),(9,10),(7,10),2).box((2,62),(9,10),(27,30),2)
        m.box((2,5),(9,10),(7,30),2).box((59,62),(9,10),(7,30),2)
        return m
    if role=='plant':return plant()
    if role in ('low_bookcase','tall_bookcase'):
        tall=role=='tall_bookcase';w=16 if tall else 32;h=32
        m=Model(width=w,height=h);top=30 if tall else 17
        m.box((1,w-1),(2,top),(2,4),0)
        m.box((0,2),(0,top+2),(2,14),0).box((w-2,w),(0,top+2),(2,14),0)
        m.box((0,w),(top,top+2),(2,15),1)
        shelves=(3,12,21) if tall else (3,10)
        for y in shelves:
            m.box((2,w-2),(y,y+2),(4,14),0)
            for i,x in enumerate(range(3,w-3,3)):
                m.box((x,x+2),(y+2,min(y+7+(i%2),top if tall else 16)),(8,13),2+i%4)
        if not tall:m.box((15,17),(2,top),(4,14),0)
        return m
    raise ValueError(role)


def model(sprite,spec):
    full=whole(spec['role']);x=spec.get('x',0)*16;z=spec.get('z',0)*16;y=spec.get('layer',0)*16
    m=Model();m.occ=full.occ[y:y+16,z:z+16,x:x+16].copy();semantic=full.paint[y:y+16,z:z+16,x:x+16]
    role=spec['role']
    if role.startswith('sofa'):
        addresses=[material(sprite,(65,70,85)),material(sprite,(175,220,120),family='green'),material(sprite,(90,140,80),family='green')]
    elif role=='plant':
        green=[material(sprite,c,family='green') for c in [(145,205,80),(180,225,100)]]
        addresses=([green[0]]*3 if y else [material(sprite,c,(3,2,13,14),'neutral') for c in [(145,145,165),(215,215,230),(65,65,85)]])+green
    elif role=='glass_table':addresses=[material(sprite,c,family='neutral') for c in [(100,100,120),(190,225,235),(235,235,250)]]
    else:
        addresses=[material(sprite,(70,105,70),family='green'),material(sprite,(170,220,115),family='green')]
        book_rect=(0,11,16,16) if role=='tall_bookcase' and y else (0,0,16,16)
        addresses += [material(sprite,c,book_rect) for c in [(230,110,95),(115,145,210),(230,230,235),(230,195,90)]]
    m.paint=np.asarray(addresses,np.uint8)[semantic]
    if role=='glass_table':
        yy,zz,xx=np.indices(m.occ.shape);top=m.occ&(yy==8)&(semantic==1)
        m.paint[top]=(zz*16+xx)[top]
    return m


def generate(base,art):
    patch={};rows=[];metadata=[]
    for key,spec in read(REGISTRY).items():
        old=base['cells'][key];assert entry_hash(old)==spec['baseline_entry_sha256'],key
        if spec.get('role'):
            new=model(art.cell(key,'BAKED'),spec).entry(old,source='hand-emerald-fan-club')
            if spec.get('added_on'):new['on']=spec['added_on']
            if spec.get('added_also_on'):new['alsoOn']=spec['added_also_on']
            patch[key]=new
            before={k:v for k,v in old.items() if k not in GEOMETRY};after={k:v for k,v in new.items() if k not in GEOMETRY}
            if before!=after:
                places=[]
                for p in art.locations[key]:
                    s=art.maps[p['map']];x,y=p['x'],p['y'];g=art.map_cells(s)
                    places.append(dict(p,collision=base64.b64decode(s['cells'])[(y*s['w']+x)*4+2],below_key=g[y+1][x]))
                metadata.append(dict(key=key,before=before,after=after,source_pixel_sha256=hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest(),occurrences=places))
        rows.append(dict(spec,key=key,kind='cell',decision='changed' if key in patch else 'retained',occurrences=art.locations[key],evidence=['museums-clubs-study/context/building__pokemon_fan_club', 'fan-club/assemblies'],visual_status='Local authoring review; actual Minecraft pending'))
    return patch,rows,metadata


def main():
    art=EmeraldArt(BUNDLE);base=read(BASELINE);patch,rows,metadata=generate(base,art)
    write_json(OUT/'updates.json',dict(cells=patch,buildings={}))
    write_json(OUT/'ledger.json',rows);write_json(OUT/'metadata-evidence.json',metadata)
    specs=read(REGISTRY.with_name('fan_club_assemblies.json'))
    entities=[]
    for s in specs:
        g=art.map_cells(art.maps[s['map']]);x,y,w,h=s['rect'];assert [row[x:x+w] for row in g[y:y+h]]==s['source_layout']
        e={k:copy.deepcopy(s[k]) for k in ('id','kind','note','layout','placements','baseline_layout','baseline_placements')}
        if s['source_layout']!=s['layout']:e['reference_layout']=s['source_layout']
        entities.append(e)
    write_json(OUT/'entities.json',entities)
    for key,new in patch.items():render(new,art.cell(key,'BAKED'),OUT/'models'/key.split('/')[1])
    print(len(patch),'changed,',len(rows),'decisions,',len(entities),'whole objects')


if __name__=='__main__':main()
