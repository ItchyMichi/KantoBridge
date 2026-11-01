"""Mauville's complete electrical pylons and low arena platform."""
from __future__ import annotations
import argparse
import base64
import copy
import hashlib
from pathlib import Path
import numpy as np
from tools.shape_fit.emerald_pass import building_interiors as common, review
from tools.shape_fit.blue_pass.common import Model,encode
from tools.model_gallery import write_json

REGISTRY=Path(__file__).with_name('mauville_gym_registry.json')
OUT=common.OUT.parent/'mauville-gym'


def coil(role):
    """Physical shaft, separate insulating rings and rounded terminal bowl."""
    m=Model()
    if role=='cap':
        m.ellipsoid((8,3,8),(6,3,6),0)
        m.cylinder((8,8),6,(0,1),2)
        m.cylinder((8,8),2,(5,11),2)
        m.cylinder((8,8),1,(10,13),0)
    elif role in ('base','electric'):
        m.cylinder((8,8),6,(0,3),2)
        m.cylinder((8,8),5,(3,8),1)
        m.cylinder((8,8),2,(8,16),2)
        for y in (8,13):m.cylinder((8,8),4,(y,y+2),0)
        if role=='electric':
            # Separate native-cyan discharge in front of the insulator.
            for y in range(16):
                phase=y%8;x=5+(phase if phase<4 else 7-phase)
                m.box((x,x+2),(y,y+1),(13,14),3)
    elif role=='middle':
        m.cylinder((8,8),2,(0,16),2)
        m.ellipsoid((8,3,8),(6,3,6),0)
        m.cylinder((8,8),6,(0,1),2)
        for y in (8,13):m.cylinder((8,8),4,(y,y+2),0)
    else:raise ValueError(role)
    return m


def dais():
    m=Model(width=64,depth=32)
    m.box((0,64),(0,2),(0,32),0)
    m.box((0,3),(2,6),(0,32),1);m.box((61,64),(2,6),(0,32),1)
    m.box((0,64),(2,4),(0,3),1);m.box((0,64),(2,4),(29,32),1)
    return m


def generate(table,art,registry):
    patch={};platform=dais()
    for row in registry['entries']:
        key,recipe=row['key'],row.get('recipe');old=table['cells'].get(key)
        if old is not None and review.entry_hash(old)!=row['baseline_entry_sha256']:raise ValueError('Stale inspected source '+key)
        if not recipe:continue
        sprite=art.cell(key,'BAKED')
        if recipe.startswith('coil_'):
            m=coil(recipe[5:]);occ=m.occ
            colors=[common.material(sprite,rgb) for rgb in [(239,239,255),(156,165,214),(66,66,107),(82,255,255)]]
            paint=np.asarray(colors,np.uint8)[m.paint]
        elif recipe.startswith('dais_'):
            _,x,z=recipe.split('_');x,z=int(x)*16,int(z)*16
            occ=platform.occ[:,z:z+16,x:x+16]
            paint=np.full(occ.shape,common.material(sprite,(156,165,181)),np.uint8)
            yy,zz,xx=np.indices(occ.shape);top=occ&~np.concatenate((occ[1:],np.zeros_like(occ[:1])),axis=0)
            paint[top]=(zz*16+xx)[top]
        else:raise ValueError(recipe)
        original=old if old is not None else {'class':'voxels','walk':True}
        entry=encode(occ,paint,original,source='hand-emerald-mauville-gym-review',trim=True)
        if row.get('on'):entry['on']=row['on']
        if row.get('alsoOn'):entry['alsoOn']=row['alsoOn']
        patch[key]=entry
    return patch


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args()
    table=review.read(review.BASELINE);registry=review.read(REGISTRY);art=common.EmeraldArt(common.BUNDLE)
    patch=generate(table,art,registry);current=copy.deepcopy(table);current['cells'].update(patch)
    args.out.mkdir(parents=True,exist_ok=True);rows=[];links={};additions={}
    for item in registry['entries']:
        key=item['key'];decision='added' if key not in table['cells'] else 'changed' if key in patch else 'retained'
        row=dict(item,kind='cell',decision=decision,occurrences=art.locations[key],
            visual_status='Source/model cards, map and complete local assemblies inspected; Minecraft pending',
            evidence=[item['source_contact'],*['assemblies/'+a+'.png' for a in item['assemblies']]])
        if decision=='added':
            row.update(baseline_absent=True,proposed_entry_sha256=review.entry_hash(patch[key]),
                source_pixel_sha256=hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest())
            additions[key]={**{k:row[k] for k in ('baseline_absent','baseline_entry_sha256','proposed_entry_sha256','source_pixel_sha256','occurrences')},'metadata':{'class':'voxels','walk':True}}
        if item.get('on') or item.get('alsoOn'):
            witnesses=[]
            for place in art.locations[key]:
                m=art.maps[place['map']];grid=art.map_cells(m);x,y=place['x'],place['y'];raw=base64.b64decode(m['cells'])
                below=grid[y+1][x];assert below in [patch[key]['on'],*patch[key].get('alsoOn',[])]
                witnesses.append(dict(place,below_key=below,below_collision=raw[((y+1)*m['w']+x)*4+2]))
            links[key]=dict(existing_on=table['cells'][key].get('on'),on=patch[key]['on'],alsoOn=patch[key].get('alsoOn',[]),occurrences=witnesses)
        rows.append(row)
    folder=args.out/'assemblies';folder.mkdir(exist_ok=True)
    entities=[common.assembly_evidence(s,current,art,folder) for s in registry['assemblies']]
    for name,value in [('updates',dict(cells=patch,buildings={})),('ledger',rows),('entities',entities),('stack-evidence',links),('additions-scope',additions)]:write_json(args.out/(name+'.json'),value)
    inv=review.read(common.OUT.parent/'inventory/building__mauville_gym/entries.json')
    common.contact_pages('building__mauville_gym',inv,current,art,args.out/'current-contacts')
    print(f'{len(rows)} decisions; {len(patch)} changes/additions; {len(entities)} complete assemblies')


if __name__=='__main__':main()
