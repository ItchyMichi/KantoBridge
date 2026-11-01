"""Manually reviewed Mart, department-store and herb-shop scenery.

Source rectangles and semantic roles are explicit registry decisions. Painting
uses local source addresses; source silhouettes never determine solid depth.
"""
import copy
import hashlib
from pathlib import Path

import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import Model, encode, render
from tools.shape_fit.emerald_pass.interior_details import restore_front
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.review import BASELINE, ROOT, entry_hash, read
from tools.shape_fit.emerald_pass.scenery import BUNDLE

REGISTRY=Path(__file__).with_name('retail_registry.json')
OUT=ROOT/'local/model-gallery/emerald-pass-study/retail'


def planter_whole():
    m=Model(width=32,depth=32)
    m.box((1,31),(1,8),(16,30),0).box((0,32),(7,10),(15,31),1)
    m.box((2,30),(9,10),(17,29),2)
    for x in (3,27):
        for z in (18,27):m.box((x,x+2),(0,3),(z,z+2),0)
    for x in (5,13,21,28):
        m.ellipsoid((x,11,22),(4.5,4.5,8),3)
        m.ellipsoid((x,13,21),(3,2.5,3),4)
    return m


def bench_whole():
    m=Model(width=32)
    for x in (2,28):
        for z in (4,12):m.box((x,x+2),(0,7),(z,z+2),0)
    for z in (4,8,12):m.box((1,31),(6,8),(z,z+3),1)
    m.box((2,4),(7,15),(3,5),0).box((28,30),(7,15),(3,5),0)
    m.box((2,30),(10,15),(3,5),1)
    return m


def plant_whole():
    m=Model(height=32)
    m.cylinder((8,8),3.8,(0,2),0).cylinder((8,8),4.7,(2,9),0)
    m.cylinder((8,8),5.3,(8,11),1).cylinder((8,8),4.2,(10,11),2)
    m.box((7,9),(11,21),(7,9),3)
    for x,y,z in [(8,21,8),(4,19,5),(12,19,5),(4,18,11),(12,18,11),(8,25,8)]:
        m.ellipsoid((x,y,z),(3.5,4,3.5),4)
        m.ellipsoid((x-.5,y+2,z-.5),(2.4,1.5,2.4),5)
    return m


def model(sprite,spec):
    role=spec['role'];m=Model()
    neutral=material(sprite,(90,95,115));light=material(sprite,(230,235,230))
    if role=='panel':
        m.box((0,16),(0,16),(2,5)).project(top=neutral,back=neutral)
        restore_front(m)
    elif role=='clock':
        full=Model(height=32);full.box((0,16),(0,32),(2,4))
        y,z,x=np.indices(full.occ.shape)
        full.put(((x+.5-8)**2+(y+.5-16)**2<4.6**2)&(z>=4)&(z<6))
        start=16*spec['layer'];m.occ=full.occ[start:start+16].copy()
        m.project(top=neutral,back=neutral);restore_front(m)
    elif role.startswith('plant_'):
        full=plant_whole();start=16 if role=='plant_cap' else 0
        greens=[material(sprite,c,family='green') for c in [(90,155,50),(170,220,105)]]
        colors=([greens[0]]*4 if start else [
            material(sprite,(160,155,175),(3,8,13,16),'neutral'),
            material(sprite,(225,225,235),(2,7,14,13),'neutral'),
            material(sprite,(70,70,95),(4,3,12,9),'neutral'),
            material(sprite,(170,130,60),(6,0,10,8))])+greens
        m.occ=full.occ[start:start+16].copy();m.paint=np.asarray(colors,np.uint8)[full.paint[start:start+16]]
    elif role=='shelf_foot':
        # The lower source row is chiefly floor beyond the front of a cabinet.
        # A low toe extends its base; the painted circular floor is not a leg.
        m.box((0,16),(0,3),(0,3),neutral)
    elif role=='register_back':
        m.box((8,16),(7,16),(8,16)).project((8,8,16,16),side=neutral,top=light)
        m.box((10,14),(0,7),(11,16),neutral)
    elif role=='pc_foot':
        m.box((2,14),(0,3),(0,5),neutral)
    elif role=='cabinet':
        # These are the north/south ends of a floor-depth merchandise run.
        # Match its retained middle section's fifteen-high shelf and seam.
        depth=(1,16) if spec['source_ordinal']==132 else (0,15)
        m.box((1,16),(0,15),depth).project((0,8,16,15),side=neutral,top=light)
        y,z,x=np.indices(m.occ.shape);top=m.occ & (y==14)
        m.paint[top]=(z*16+x)[top]
    elif role=='vending':
        m.box((1,15),(0 if spec['layer'] else 2,16),(3,14)).project(side=neutral,top=light)
        restore_front(m)
        if not spec['layer']:
            m.box((2,4),(0,2),(4,13),neutral).box((12,14),(0,2),(4,13),neutral)
    elif role=='stool':
        red=material(sprite,(250,120,95));dark=material(sprite,(65,65,85))
        for x in (4,10):
            for z in (4,10):m.box((x,x+2),(0,7),(z,z+2),dark)
        m.box((3,13),(6,8),(3,13),light).box((4,12),(8,10),(4,12),red)
        m.paint[9,4:12,4:12]=np.array([[(v*16+u) for u in range(4,12)] for v in range(2,10)],dtype=np.uint8)
    elif role=='seat':
        purple=material(sprite,(125,105,245));edge=material(sprite,(65,65,90))
        for x in (3,11):
            for z in (4,11):m.box((x,x+2),(0,6),(z,z+2),edge)
        m.box((2,14),(5,8),(3,14),purple).box((2,14),(8,15),(3,5),purple)
        m.box((1,3),(8,11),(4,13),edge).box((13,15),(8,11),(4,13),edge)
        turns=spec['turns'];m.occ=np.rot90(m.occ,turns,axes=(1,2)).copy();m.paint=np.rot90(m.paint,turns,axes=(1,2)).copy()
    elif role=='bench':
        full=bench_whole();x=spec['x']*16;m.occ=full.occ[:,:,x:x+16].copy()
        colors=np.array([material(sprite,(65,65,90)),material(sprite,(125,105,245))],np.uint8)
        m.paint=colors[full.paint[:,:,x:x+16]]
    elif role=='planter':
        full=planter_whole();x,z=spec['x']*16,spec['z']*16
        m.occ=full.occ[:,z:z+16,x:x+16].copy()
        colors=np.array([material(sprite,(210,140,50)),material(sprite,(255,210,90)),material(sprite,(90,85,60)),material(sprite,(90,170,65),family='green'),material(sprite,(175,230,100),family='green')],np.uint8)
        m.paint=colors[full.paint[:,z:z+16,x:x+16]]
    elif role=='rail':
        metal=material(sprite,(115,125,145));trim=material(sprite,(220,150,110))
        arms=spec['arms'];m.box((7,9),(0,12),(7,9),metal)
        for direction in arms:
            if direction in ('n','s'):
                z=(0,9) if direction=='n' else (7,16)
                m.box((7,9),(9,12),z,trim).box((7,9),(3,5),z,metal)
            else:
                x=(0,9) if direction=='w' else (7,16)
                m.box(x,(9,12),(7,9),trim).box(x,(3,5),(7,9),metal)
    else:raise ValueError(role)
    return m


def generate(table,art):
    updates={}
    for key,spec in read(REGISTRY).items():
        old=table['cells'].get(key,{'class':'voxels'})
        if key in table['cells'] and entry_hash(old)!=spec['baseline_entry_sha256']:raise ValueError('stale retail source '+key)
        if not spec.get('role'):continue
        new=model(art.cell(key,'BAKED'),spec).entry(old,source='hand-emerald-retail')
        if spec.get('added_on'):new['on']=spec['added_on']
        if spec.get('render_on_walk'):new['walk']=True
        updates[key]=new
    return updates


def main():
    table=read(BASELINE);art=EmeraldArt(BUNDLE);patch=generate(table,art);rows=[]
    for key,spec in read(REGISTRY).items():
        added=key not in table['cells'];row=dict(spec,key=key,kind='cell',decision='added' if added else 'changed' if key in patch else 'retained',occurrences=art.locations[key])
        if added:row.update(baseline_absent=True,baseline_entry_sha256=None,proposed_entry_sha256=entry_hash(patch[key]),source_pixel_sha256=hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest())
        rows.append(row)
        if key in patch:render(patch[key],art.cell(key,'BAKED'),OUT/'models'/key.split('/')[1])
    write_json(OUT/'updates.json',{'cells':patch,'buildings':{}});write_json(OUT/'ledger.json',rows)
    from tools.shape_fit.emerald_pass.building_interiors import assembly_evidence
    current=copy.deepcopy(table);current['cells'].update(patch)
    specs=read(REGISTRY.with_name('retail_assemblies.json'))
    directory=OUT/'assemblies';directory.mkdir(exist_ok=True)
    entities=[]
    for spec in specs:
        e=assembly_evidence(spec,current,art,directory)
        for field in ('baseline_placements','baseline_layout'):
            if field in spec:e[field]=spec[field]
        entities.append(e)
    write_json(OUT/'entities.json',entities)
    print(len(rows),'reviewed retail cells;',len(patch),'authored candidates; complete-object QA pending')


if __name__=='__main__':main()
