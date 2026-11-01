"""Sootopolis ice-room fixtures, including one complete six-cell cascade."""
from __future__ import annotations
import argparse
import base64
import copy
import hashlib
from pathlib import Path
import numpy as np
from tools.shape_fit.emerald_pass import building_interiors as c,review,gym_qa_corrections as gym
from tools.shape_fit.blue_pass.common import Model,encode,render
from tools.model_gallery import write_json

REGISTRY=Path(__file__).with_name('sootopolis_gym_registry.json')
OUT=c.OUT.parent/'sootopolis-gym'


def dais():
    m=Model(width=80,depth=32)
    m.box((0,80),(0,3),(0,32),0)
    y,z,x=np.indices(m.occ.shape)
    dx=np.maximum(np.maximum(6-(x+.5),0),(x+.5)-74)
    dz=np.maximum(np.maximum(6-(z+.5),0),(z+.5)-26)
    footprint=(dx*dx+dz*dz<=16)
    m.occ&=footprint
    # The source's two lower horizontal bands are a shallow entrance step.
    m.box((16,64),(1,3),(28,32),cut=True)
    m.box((16,64),(2,3),(26,28),cut=True)
    return m


def crystal():
    m=Model(height=32)
    m.box((1,15),(0,2),(2,14),0);m.box((2,14),(2,4),(3,13),1)
    yy,zz,xx=np.indices(m.occ.shape)
    for cx,cz,height,radius in [(4,9,14,2.3),(8,7,23,2.6),(12,9,16,2.3)]:
        for y in range(3,height):
            r=radius if y<height-6 else radius*(height-y)/6
            mask=((xx+.5-cx)**2+(zz+.5-cz)**2<=r*r)&(yy==y)
            m.put(mask,3)
            m.paint[mask&((xx+.5-cx)>.6)]=2
            m.paint[mask&((xx+.5-cx)<-.2)&(zz+.5>=cz)]=4
    return m


def cascade():
    """Raised white header, recessed outlet, falling sheet and shallow pool.

    The source's first row includes the wall top and its last row is horizontal
    pool depth. All parts fit sixteen voxels high, including WALK variants.
    """
    m=Model(width=32,depth=48)
    m.box((0,32),(0,16),(3,16),0)
    m.box((3,29),(10,14),(13,16),cut=True)
    m.box((3,29),(10,14),(12,13),4)
    m.box((0,32),(14,16),(13,18),1)
    m.box((3,29),(12,14),(14,22),2)
    m.box((3,29),(3,13),(20,22),2)
    m.box((3,29),(0,3),(18,46),0)
    m.box((3,29),(2,3),(18,46),2)
    for x in (0,29):
        m.box((x,x+3),(0,6),(16,48),1)
        m.box((x,x+3),(5,6),(16,48),0)
    m.box((0,32),(0,5),(46,48),1)
    m.box((0,32),(4,5),(46,48),0)
    for x in (5,12,19,25):m.box((x,x+2),(3,4),(22,25),3)
    return m


def blue_material(sprite,rgb):
    pixels=np.asarray(sprite.convert('RGB')).astype(float).reshape(-1,3)
    eligible=(pixels[:,2]>pixels[:,0]+20)&(pixels[:,1]>pixels[:,0]+10)
    if not eligible.any():raise ValueError('Inspected crystal slice has no native blue')
    distance=((pixels-rgb)**2).sum(1);distance[~eligible]=np.inf
    return int(np.argmin(distance))


def generate(table,art,registry):
    patch={};gem=crystal();waterfall=cascade();platform=dais()
    for row in registry['entries']:
        key=row['key'];recipe=row.get('recipe');old=table['cells'][key]
        if review.entry_hash(old)!=row['baseline_entry_sha256']:raise ValueError('Stale inspected cell '+key)
        if not recipe:continue
        sprite=art.cell(key,'BAKED')
        if recipe.startswith('statue_'):
            patch[key]=gym.generate(table,art,[row])[key];continue
        if recipe.startswith('platform_'):
            _,x,z=recipe.split('_');x,z=int(x)*16,int(z)*16
            occ=platform.occ[:,z:z+16,x:x+16].copy()
            paint=np.full(occ.shape,c.material(sprite,(148,132,181)),np.uint8)
            yy,zz,xx=np.indices(occ.shape)
            top=occ&~np.concatenate((occ[1:],np.zeros_like(occ[:1])),axis=0)
            paint[top]=(zz*16+xx)[top]
            pixels=np.asarray(sprite.convert('RGB')).astype(int)
            surrounding_ice=(pixels[:,:,1]==pixels[:,:,2])&(pixels[:,:,1]>pixels[:,:,0]+10)
            paint[top&surrounding_ice[zz,xx]]=c.material(sprite,(132,132,156))
        elif recipe=='ice_floor':
            m=Model();m.box((0,16),(0,1),(0,16));occ=m.occ
            y,z,x=np.indices(occ.shape);paint=(z*16+x).astype(np.uint8)
        elif recipe.startswith('crystal_'):
            start=int(recipe.split('_')[1])*16
            occ=gem.occ[start:start+16].copy();semantic=gem.paint[start:start+16]
            colors=[c.material(sprite,(156,132,189)),c.material(sprite,(107,99,140)),c.material(sprite,(66,74,107)),blue_material(sprite,(58,173,206)),blue_material(sprite,(132,206,222))]
            paint=np.asarray(colors,np.uint8)[semantic]
        elif recipe.startswith('cascade_'):
            _,x,z=recipe.split('_');x,z=int(x)*16,int(z)*16
            occ=waterfall.occ[:,z:z+16,x:x+16].copy();semantic=waterfall.paint[:,z:z+16,x:x+16]
            colors=[c.material(sprite,(239,247,247)),c.material(sprite,(140,123,173)),c.material(sprite,(99,189,214)),c.material(sprite,(230,255,255)),c.material(sprite,(66,66,90))]
            paint=np.asarray(colors,np.uint8)[semantic]
            yy,zz,xx=np.indices(occ.shape)
            if z==16:
                front=(semantic==2)&(zz==5)&(yy>=3)&(yy<13)
                v=np.clip((12-yy)*16//10,0,15)
                paint[front]=(v*16+xx)[front]
            if z==32:
                top=(semantic==2)&(yy==2)
                pixels=np.asarray(sprite.convert('RGB')).astype(int)
                water=(pixels[:,:,2]>pixels[:,:,0]+15)&(pixels[:,:,1]>pixels[:,:,0]+10)
                foam=(pixels.min(2)>210)
                use=top&(water[zz,xx]|foam[zz,xx]);paint[use]=(zz*16+xx)[use]
        else:raise ValueError(recipe)
        patch[key]=encode(occ,paint,old,source='hand-emerald-sootopolis-gym',trim=True)
        if row.get('render_on_walk'):patch[key]['walk']=True
    return patch


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args()
    table=review.read(review.BASELINE);art=c.EmeraldArt(c.BUNDLE);registry=review.read(REGISTRY);patch=generate(table,art,registry)
    current=copy.deepcopy(table);current['cells'].update(patch);args.out.mkdir(parents=True,exist_ok=True)
    rows=[];walk={};proof={}
    for item in registry['entries']:
        key=item['key'];places=[]
        for place in art.locations[key]:
            m=art.maps[place['map']];raw=base64.b64decode(m['cells'])
            places.append(dict(place,collision=raw[(place['y']*m['w']+place['x'])*4+2],map_cells_sha256=hashlib.sha256(raw).hexdigest()))
        proof[key]=dict(source_pixel_sha256=hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest(),occurrences=places)
        rows.append(dict(item,kind='cell',decision='changed' if key in patch else 'retained',occurrences=art.locations[key],visual_status='Source/current model and whole map assemblies inspected; Minecraft pending',evidence=[item['source_contact'],*['assemblies/'+a+'.png' for a in item['assemblies']]]))
        if key in patch:
            render(patch[key],art.cell(key,'BAKED'),args.out/'models'/key.split('/')[1])
            if patch[key].get('walk') and not table['cells'][key].get('walk'):
                walk[key]=dict(proof[key],baseline_entry_sha256=review.entry_hash(table['cells'][key]),proposed_entry_sha256=review.entry_hash(patch[key]),old_metadata={k:v for k,v in table['cells'][key].items() if k not in review.GEOMETRY},new_metadata={k:v for k,v in patch[key].items() if k not in review.GEOMETRY})
    folder=args.out/'assemblies';folder.mkdir(exist_ok=True);entities=[]
    for spec in registry['assemblies']:
        entity=c.assembly_evidence(spec,current,art,folder)
        if spec.get('replaces_stack'):entity['replaces_stack']=True
        entities.append(entity)
    for name,value in [('updates',dict(cells=patch,buildings={})),('ledger',rows),('entities',entities),('walk-proposal',walk),('source-evidence',proof)]:write_json(args.out/(name+'.json'),value)
    c.contact_pages('building__sootopolis_gym',review.read(c.OUT.parent/'inventory/building__sootopolis_gym/entries.json'),current,art,args.out/'current-contacts')
    print(f'{len(rows)} decisions; {len(patch)} changed; {len(walk)} narrow walk render proposals')


if __name__=='__main__':main()
