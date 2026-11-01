"""Source-audited Oceanic Museum cases, room divider and computer consoles.

All selections were inspected as source cells, map context and complete objects.
The existing stairs and miniature exhibits remain individual retained decisions.
Four native counters keep their geometry and explicitly opt into counter rendering.
The long central divider occupies four floor rows, not a tall stack.
"""
import base64
import copy
import hashlib
from pathlib import Path

import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import Model, render
from tools.shape_fit.emerald_pass.interior_details import restore_front
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.review import BASELINE, GEOMETRY, ROOT, entry_hash, read
from tools.shape_fit.emerald_pass.scenery import BUNDLE

REGISTRY=Path(__file__).with_name('oceanic_museum_registry.json')
OUT=ROOT/'local/model-gallery/emerald-pass-study/oceanic-museum'


def whole(role):
    if role=='small_case':
        m=Model(height=32)
        m.cylinder((8,8),5,(0,4),0).cylinder((8,8),4.4,(4,25),1)
        m.cylinder((8,8),5,(25,28),2).cylinder((8,8),4.2,(28,29),2)
        m.box((4,5),(4,25),(6,10),2).box((11,12),(4,25),(6,10),2)
        m.box((7,9),(8,21),(7,9),3)
        return m
    if role=='information':
        m=Model(height=32)
        m.box((2,14),(0,3),(3,12),0).box((2,14),(3,27),(3,11),1)
        m.box((3,13),(10,25),(10,12),2)
        m.box((3,13),(3,9),(10,12),3)
        m.box((2,14),(26,28),(3,12),0)
        return m
    if role=='large_case':
        m=Model(height=32,width=32,depth=32)
        m.cylinder((12,11),(11,8),(0,4),0)
        m.cylinder((12,9),(10.4,6.8),(4,28),1)
        m.cylinder((12,9),(11,7.5),(28,31),2)
        m.cylinder((12,9),(10,6.5),(31,32),2)
        m.box((2,4),(4,28),(6,12),2).box((20,22),(4,28),(6,12),2)
        m.box((10,14),(6,25),(8,10),3)
        return m
    if role=='vent':
        m=Model(height=32,width=32)
        m.box((3,29),(3,29),(1,3),0)
        y,z,x=np.indices(m.occ.shape)
        m.put(((x+.5-16)**2+(y+.5-16)**2<7.5**2)&(z>=3)&(z<5),1)
        for xx in (4,6,8,23,25,27):m.box((xx,xx+1),(9,24),(3,6),2)
        m.box((8,24),(3,7),(2,6),3).box((8,24),(27,31),(2,6),3)
        return m
    if role=='pc':
        m=Model(height=32,width=32)
        for x in (2,19):
            for z in (4,12):m.box((x,x+2),(0,3),(z,z+2),0)
        m.box((1,23),(3,11),(4,14),1)
        m.box((0,24),(11,13),(3,15),2)
        m.box((2,13),(13,15),(12,15),2)
        m.box((3,13),(13,18),(5,8),0)
        m.box((1,15),(16,27),(3,11),0)
        m.box((3,13),(18,25),(10,12),3)
        m.box((15,21),(16,19),(4,10),2)
        return m
    raise ValueError(role)


def model(sprite,spec):
    role=spec['role'];m=Model()
    blue=material(sprite,(105,125,210));light=material(sprite,(220,235,250))
    if role=='wall_case':
        m.box((0,16),(0,16),(2,6)).project(side=blue,top=light,back=blue)
        restore_front(m)
    elif role=='aquarium':
        m.box((0,16),(0,16),(2,5)).project(side=blue,top=light,back=blue)
        restore_front(m)
    elif role=='divider':
        light=material(sprite,(255,255,255))
        m.box((1,15),(0,16),(0,16),blue)
        m.box((2,14),(15,16),(0,16),light)
        if spec['source_ordinal']==32:
            m.box((1,15),(0,16),(14,16))
            m.project(side=blue,top=light,back=blue);restore_front(m)
    else:
        full=whole(role);x=spec.get('x',0)*16;y=spec.get('layer',0)*16;z=spec.get('z',0)*16
        m.occ=full.occ[y:y+16,z:z+16,x:x+16].copy()
        semantic=full.paint[y:y+16,z:z+16,x:x+16]
        rect=spec.get('material_rect',[0,0,16,16])
        palette={'small_case':[(210,170,65),(150,195,255),(250,247,220),(93,127,220)],
                 'large_case':[(210,170,65),(150,195,255),(250,247,220),(93,127,220)],
                 'information':[(75,100,150),(150,165,225),(210,225,255),(120,135,210)],
                 'vent':[(43,64,85),(175,210,255),(115,165,235),(215,230,255)],
                 'pc':[(60,75,100),(145,160,220),(225,235,250),(100,125,220)]}[role]
        addresses=np.array([material(sprite,c,rect) for c in palette],np.uint8)
        m.paint=addresses[semantic]
        if role in ('small_case','large_case','information','vent') and spec.get('z',0)==0:
            # Paint only forward exposed body surfaces with the native source
            # face. Structural rims, rounded sides and tops keep named swatches.
            neighbor=np.roll(m.occ,-1,axis=1);neighbor[:,-1,:]=False
            yy,zz,xx=np.indices(m.occ.shape)
            front=m.occ&~neighbor&(zz>=7 if role!='vent' else zz>=3)
            if role in ('small_case','large_case'):front&=(semantic==1)
            if role=='information':front&=(semantic==2)|(semantic==3)
            if role=='vent':front&=(semantic==1)|(semantic==3)
            m.paint[front]=((15-yy)*16+xx)[front]
        if role=='pc':
            # The left source contains the screen and keyboard. The right source
            # contains only a narrow casing extension; carpet never paints it.
            if spec.get('x',0)==0 and spec.get('layer',0)==1:
                screen=m.occ&(semantic==3);yy,zz,xx=np.indices(m.occ.shape)
                m.paint[screen]=((12+(24-(yy+16))*3//7)*16+np.clip(5+(xx-3)*5//10,5,10))[screen]
            if spec.get('x',0)==0 and spec.get('layer',0)==0:
                yy,zz,xx=np.indices(m.occ.shape)
                keyboard=m.occ&(yy==14)&(zz>=12)&(xx>=2)&(xx<13)
                m.paint[keyboard]=((5+(zz-12))*16+xx)[keyboard]
    return m


def generate(table,art,registry=None):
    registry=registry or read(REGISTRY);patch={};ledger=[];amendments=[]
    for key,spec in registry.items():
        old=table['cells'][key];assert entry_hash(old)==spec['baseline_entry_sha256'],key
        if spec.get('role') or spec.get('render_on_counter'):
            new=model(art.cell(key,'BAKED'),spec).entry(old,source='hand-emerald-oceanic-museum') if spec.get('role') else copy.deepcopy(old)
            if spec.get('added_on'):new['on']=spec['added_on']
            if spec.get('render_on_walk'):new['walk']=True
            if spec.get('render_on_counter'):new['counter']=True
            patch[key]=new
            before={k:v for k,v in old.items() if k not in GEOMETRY};after={k:v for k,v in new.items() if k not in GEOMETRY}
            if before!=after:
                places=[]
                for p in art.locations[key]:
                    src=art.maps[p['map']];x,y=p['x'],p['y'];grid=art.map_cells(src)
                    places.append(dict(p,collision=base64.b64decode(src['cells'])[(y*src['w']+x)*4+2],below_key=grid[y+1][x] if y+1<src['h'] else None))
                amendments.append(dict(key=key,before=before,after=after,source_pixel_sha256=hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest(),occurrences=places))
        ledger.append(dict(spec,key=key,kind='cell',decision='changed' if key in patch else 'retained',occurrences=art.locations[key],evidence=[spec['source_context'],'oceanic-museum/assemblies','museums-clubs-study/baseline-wholes'],visual_status='Authoring review; Minecraft comparison pending'))
    return patch,ledger,amendments


def assemblies(art,table):
    specs=read(REGISTRY.with_name('oceanic_museum_assemblies.json'));result=[]
    for spec in specs:
        grid=art.map_cells(art.maps[spec['map']]);x,y,w,h=spec['rect']
        reference=[line[x:x+w] for line in grid[y:y+h]]
        assert reference==spec['source_layout'],spec['id']
        entity={k:copy.deepcopy(spec[k]) for k in ('id','kind','note','layout','placements','baseline_layout','baseline_placements')}
        if reference!=entity['layout']:entity['reference_layout']=reference
        result.append(entity)
    return result


def main():
    table=read(BASELINE);art=EmeraldArt(BUNDLE);patch,ledger,metadata=generate(table,art)
    current=copy.deepcopy(table);current['cells'].update(patch)
    write_json(OUT/'updates.json',dict(cells=patch,buildings={}))
    write_json(OUT/'ledger.json',ledger);write_json(OUT/'metadata-evidence.json',metadata)
    write_json(OUT/'entities.json',assemblies(art,current))
    for key,entry in patch.items():render(entry,art.cell(key,'BAKED'),OUT/'models'/key.split('/')[1])
    print(len(patch),'changes;',len(ledger),'decisions;',len(metadata),'metadata amendments')


if __name__=='__main__':main()
