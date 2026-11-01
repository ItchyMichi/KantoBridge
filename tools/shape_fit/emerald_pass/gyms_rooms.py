"""Mossdeep and Petalburg room fixtures, authored as complete objects."""
from __future__ import annotations
import argparse
import base64
import copy
import hashlib
from pathlib import Path
import numpy as np
from tools.shape_fit.emerald_pass import building_interiors as c,review
from tools.shape_fit.blue_pass.common import Model,encode
from tools.model_gallery import write_json

REGISTRY=Path(__file__).with_name('gyms_rooms_registry.json')
OUT=c.OUT.parent/'gyms-rooms'


def mossdeep_dais():
    m=Model(width=64,depth=32)
    m.box((0,64),(0,3),(0,32),0)
    m.box((0,5),(3,8),(0,32),1);m.box((59,64),(3,8),(0,32),1)
    m.box((0,64),(3,6),(0,3),1)
    m.box((16,48),(3,5),(3,16),2)
    return m


def alcove():
    """Three-wide wall niche with side pilasters, shelf and a white flower."""
    m=Model(width=48,height=32)
    m.box((0,48),(0,24),(0,3),0)
    m.box((0,48),(24,32),(0,3),1)
    m.box((16,32),(2,20),(0,3),8)
    for x in (0,45):m.box((x,x+3),(0,24),(0,6),2)
    m.box((16,32),(0,2),(3,13),3)
    m.cylinder((24,7),3,(2,6),4);m.cylinder((24,7),3.5,(5,7),4)
    m.cylinder((24,7),1,(6,19),5)
    m.ellipsoid((21,11,7),(3,2,2),5);m.ellipsoid((27,8,7),(2,3,2),5)
    m.ellipsoid((24,20,7),(4,4,2),6)
    m.box((23,26),(19,22),(8,10),7)
    return m


def generate(table,art,registry):
    updates={};platform=mossdeep_dais();niche=alcove()
    for row in registry['entries']:
        key=row['key'];old=table['cells'].get(key);recipe=row.get('recipe')
        if old is not None and review.entry_hash(old)!=row['baseline_entry_sha256']:raise ValueError('Stale inspected entry '+key)
        if not recipe:continue
        sprite=art.cell(key,'BAKED')
        if recipe=='wall':
            color=(140,150,180) if row['group']=='building__mossdeep_gym' else (140,90,66)
            m=Model();m.box((0,16),(0,16),(13,16),c.material(sprite,color))
            occ,paint=m.occ,m.paint;y,z,x=np.indices(occ.shape);paint[z==15]=((15-y)*16+x)[z==15]
        elif recipe.startswith('dais_'):
            _,x,z=recipe.split('_');x,z=int(x)*16,int(z)*16
            occ=platform.occ[:,z:z+16,x:x+16];paint=np.full(occ.shape,c.material(sprite,(82,123,189)),np.uint8)
            yy,zz,xx=np.indices(occ.shape);top=occ&~np.concatenate((occ[1:],np.zeros_like(occ[:1])),axis=0);paint[top]=(zz*16+xx)[top]
        elif recipe.startswith('alcove_'):
            _,x,level=recipe.split('_');x,level=int(x)*16,int(level)*16
            occ=niche.occ[level:level+16,:,x:x+16];semantic=niche.paint[level:level+16,:,x:x+16]
            materials=[c.material(sprite,rgb) for rgb in [(140,74,41),(255,214,156),(90,90,115),(189,82,49),(66,66,90),(49,148,41),(255,255,222),(239,230,107),(140,140,164)]]
            paint=np.asarray(materials,np.uint8)[semantic]
            yy,zz,xx=np.indices(occ.shape)
            if x!=16:
                front=(zz==2);paint[front]=((15-yy)*16+xx)[front]
        elif recipe=='statue_clean':
            occ,paint=c.decode(old);paint=paint.copy()
            colors=np.asarray(sprite.convert('RGB')).reshape(-1,3)[paint]
            floor=(colors[...,0]>180)&(colors[...,1]>160)&(colors[...,2]<160)
            paint[floor]=c.material(sprite,(90,90,115))
        else:raise ValueError(recipe)
        updates[key]=encode(occ,paint,old if old is not None else {'class':'voxels','walk':True},source='hand-emerald-gym-room-review',trim=True)
        if row.get('on'):updates[key]['on']=row['on']
    return updates


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args()
    table=review.read(review.BASELINE);registry=review.read(REGISTRY);art=c.EmeraldArt(c.BUNDLE);patch=generate(table,art,registry)
    current=copy.deepcopy(table);current['cells'].update(patch);args.out.mkdir(parents=True,exist_ok=True);rows=[];additions={};links={}
    for item in registry['entries']:
        key=item['key'];decision='added' if key not in table['cells'] else 'changed' if key in patch else 'retained'
        row=dict(item,kind='cell',decision=decision,occurrences=art.locations[key],visual_status='Source/map and complete local assembly inspected; Minecraft pending',evidence=[item['source_contact'],*['assemblies/'+a+'.png' for a in item['assemblies']]])
        if decision=='added':
            row.update(baseline_absent=True,proposed_entry_sha256=review.entry_hash(patch[key]),source_pixel_sha256=hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest())
            additions[key]={**{k:row[k] for k in ('baseline_absent','baseline_entry_sha256','proposed_entry_sha256','source_pixel_sha256','occurrences')},'metadata':{'class':'voxels','walk':True}}
        if item.get('on'):
            witnesses=[]
            for place in art.locations[key]:
                m=art.maps[place['map']];g=art.map_cells(m);x,y=place['x'],place['y'];raw=base64.b64decode(m['cells']);assert g[y+1][x]==item['on']
                witnesses.append(dict(place,top_collision=raw[(y*m['w']+x)*4+2],below_key=g[y+1][x],below_collision=raw[((y+1)*m['w']+x)*4+2],map_cells_sha256=hashlib.sha256(raw).hexdigest()))
            links[key]=dict(on=item['on'],occurrences=witnesses)
        rows.append(row)
    folder=args.out/'assemblies';folder.mkdir(exist_ok=True)
    entities=[c.assembly_evidence(s,current,art,folder) for s in registry['assemblies']]
    for name,value in [('updates',dict(cells=patch,buildings={})),('ledger',rows),('entities',entities),('additions-scope',additions),('stack-evidence',links)]:write_json(args.out/(name+'.json'),value)
    for group in sorted({r['group'] for r in rows}):c.contact_pages(group,review.read(c.OUT.parent/'inventory'/group/'entries.json'),current,art,args.out/'current-contacts')
    print(f'{len(rows)} decisions; {len(patch)} changes/additions; {len(entities)} complete assemblies')


if __name__=='__main__':main()
