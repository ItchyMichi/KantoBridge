"""Individually map-reviewed wall details, cabinets, cargo and stage steps."""
import base64
import copy
import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import Model, encode, render
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.interior_furniture import console
from tools.shape_fit.emerald_pass.review import BASELINE, ROOT, entry_hash, read
from tools.shape_fit.emerald_pass.scenery import BUNDLE

REGISTRY = ROOT/'tools/shape_fit/emerald_pass/interior_details_registry.json'
OUT = ROOT/'local/model-gallery/emerald-pass-study/interior-details'


def restore_front(model, rect=(0,0,16,16)):
    """The outer printed face wins at shared top/side voxels and cell seams."""
    y,z,x=np.indices(model.occ.shape)
    exposed=model.occ & ~np.concatenate((model.occ[:,1:,:],np.zeros_like(model.occ[:,:1,:])),axis=1)
    u0,v0,u1,v1=rect
    addresses=(v0+(15-y)*(v1-v0)//16)*16+u0+x*(u1-u0)//16
    model.paint[exposed]=addresses[exposed]


def model(sprite, spec):
    role=spec['role'];m=Model()
    neutral=material(sprite,(105,110,130))
    if role=='panel':
        # A real wall panel has constant backing depth. Display artwork is
        # printed on the forward face, never used to infer an hourglass solid.
        m.box((0,16),(0,16),(2,6))
        if 'face' in spec:
            x0,v0,x1,v1=spec['face'];m.box((x0,x1),(16-v1,16-v0),(6,7))
        m.project(top=neutral,back=neutral)
        restore_front(m)
    elif role=='curtain':
        m.box((0,16),(0,16),(2,5))
        for x in (1,5,9,13):m.box((x,x+1),(0,16),(5,6))
        # Side recoloring on every fold would overwrite the printed banner.
        if spec.get('upper_curtain'):m.box((0,16),(13,16),(0,16),cut=True)
        m.project(top=neutral,back=neutral)
        restore_front(m)
    elif role=='stairs':
        y,z,x=np.indices((16,16,16));whole=z+spec['section']*16
        height=np.where(whole<16,12,np.where(whole<24,8,4))
        m.occ=y<height;m.paint=(z*16+x).astype(np.uint8)
        front=m.occ & ~np.concatenate((m.occ[:,1:,:],np.zeros_like(m.occ[:,:1,:])),axis=1)
        m.paint[front & (y<height-1)]=material(sprite,(110,120,120),(0,8,16,16))
    elif role in ('floor','pit'):
        m.box((0,16),(0,1),(0,16));y,z,x=np.indices(m.occ.shape)
        m.paint=(z*16+x).astype(np.uint8)
    elif role.startswith('judge_desk_'):
        # A low desk straddles the two projected source rows. Build its tabletop,
        # legs and front apron together, then slice along floor depth.
        full=Model(depth=32)
        full.box((1,15),(8,10),(8,24),0)
        for x in (2,12):
            for z in (9,21):full.box((x,x+2),(0,8),(z,z+2),1)
        full.box((2,14),(2,8),(22,24),0)
        start=0 if role.endswith('upper') else 16
        m.occ=full.occ[:,start:start+16,:].copy()
        white=material(sprite,(245,245,250),(1,8,15,16) if start==0 else (1,0,15,8))
        gold=material(sprite,(190,150,45),(1,8,15,16) if start==0 else (1,0,15,8))
        m.paint=np.where(full.paint[:,start:start+16,:]==1,gold,white).astype(np.uint8)
        y,z,x=np.indices(m.occ.shape)
        m.paint[m.occ & ((x==1)|(x==14))]=gold
        if start==16:
            front=m.occ & (z==7) & (y>=2) & (y<8)
            addresses=(((7-y)*8//6)*16+x).astype(np.uint8)
            m.paint[front]=addresses[front]
    elif role.startswith('extinguisher'):
        m.box((0,16),(0,16),(1,4)).project(side=neutral,top=neutral)
        red=material(sprite,(190,80,45),(3,0,13,14),'orange')
        dark=material(sprite,(70,70,90))
        if role.endswith('upper'):
            m.cylinder((8,8),3.8,(0,12),red)
            m.ellipsoid((8,12,8),(3.7,2,3.7),red)
            m.box((7,9),(13,15),(7,9),dark)
            m.box((10,11),(3,13),(8,9),dark)
        else:m.cylinder((8,8),3.2,(12,16),red)
    elif role.startswith('console_'):
        m.occ,m.paint=console(sprite,role)
    elif role.startswith('cabinet_'):
        base_height=8 if spec.get('short_base') else 3
        y=(0,16) if role.endswith('upper') else (base_height,16)
        m.box((1,15),y,(3,13)).project(spec.get('front',(0,0,16,16)),side=neutral,top=material(sprite,(190,190,190)))
        restore_front(m,spec.get('front',(0,0,16,16)))
        if role.endswith('base'):
            for x in ((2,4),(12,14)):
                for z in ((4,6),(10,12)):m.box(x,(0,base_height),z,neutral)
    elif role=='crate':
        wood=material(sprite,(195,150,60),spec.get('front',(0,4,16,16)))
        m.box((0,16),(0,16),(0,16)).project(spec.get('front',(0,4,16,16)),side=wood,top=wood)
    elif role=='plant_base':
        body=material(sprite,(190,190,165),(4,8,12,15));rim=material(sprite,(245,245,230),(3,6,13,12))
        soil=material(sprite,(65,65,70),(4,3,12,8));stem=material(sprite,(160,130,55),(6,0,10,7))
        m.cylinder((8,8),3.8,(0,2),body).cylinder((8,8),4.8,(2,10),body)
        m.cylinder((8,8),5.4,(10,12),rim).cylinder((8,8),4.4,(11,12),soil)
        m.box((7,9),(12,16),(7,9),stem)
    elif role=='frieze':
        m.box((0,16),(0,16),(1,5)).project((0,0,16,8),side=neutral,top=neutral)
    else:raise ValueError(role)
    return m


def generate(table,art):
    updates={}
    for key,spec in read(REGISTRY).items():
        old=table['cells'][key]
        if entry_hash(old)!=spec['baseline_entry_sha256']:raise ValueError('stale interior source '+key)
        new=model(art.cell(key,'BAKED'),spec).entry(old,source='hand-emerald-interior-details')
        if spec.get('remove_on'):new.pop('on')
        if spec.get('added_on'):new['on']=spec['added_on']
        if spec.get('render_on_walk'):new['walk']=True
        updates[key]=new
    return updates


def main():
    table=read(BASELINE);art=EmeraldArt(BUNDLE);updates=generate(table,art);rows=[];stack_evidence={}
    for key,spec in read(REGISTRY).items():
        rows.append(dict(spec,key=key,kind='cell',decision='changed',occurrences=art.locations[key]))
        render(updates[key],art.cell(key,'BAKED'),OUT/'models'/key.split('/')[1])
        if spec.get('added_on'):
            places=[]
            for p in art.locations[key]:
                s=art.maps[p['map']];g=art.map_cells(s);x,y=p['x'],p['y']
                assert g[y+1][x]==spec['added_on']
                collision=base64.b64decode(s['cells'])[((y+1)*s['w']+x)*4+2]
                assert collision==7
                places.append(dict(p,below_collision=collision))
            stack_evidence[key]={'on':spec['added_on'],'occurrences':places}
    write_json(OUT/'updates.json',{'cells':updates,'buildings':{}});write_json(OUT/'ledger.json',rows)
    write_json(OUT/'stack-evidence.json',stack_evidence)
    from tools.shape_fit.emerald_pass.building_interiors import assembly_evidence
    current=copy.deepcopy(table);current['cells'].update(updates)
    directory=OUT/'assemblies';directory.mkdir(exist_ok=True)
    specs=read(REGISTRY.with_name('interior_details_assemblies.json'))
    entities=[]
    for spec in specs:
        entity=assembly_evidence(spec,current,art,directory)
        if spec.get('replaces_stack'):entity['replaces_stack']=True
        entities.append(entity)
    write_json(OUT/'entities.json',entities);write_json(OUT/'assembly-placements.json',specs)
    print(len(updates),'explicit interior details; local and Minecraft QA required')


if __name__=='__main__':main()
