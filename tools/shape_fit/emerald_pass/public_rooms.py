"""Source-inspected public-room terminals, glass displays and wall equipment."""
from __future__ import annotations
import argparse
import copy
import base64
from pathlib import Path
import numpy as np
from tools.shape_fit.emerald_pass import building_interiors as common, review
from tools.shape_fit.blue_pass.common import Model,encode,decode
from tools.model_gallery import write_json

REGISTRY=Path(__file__).with_name('public_room_registry.json')
OUT=common.OUT.parent/'public-rooms'


def terminal_cap(sprite):
    """The pale curved housing above the shared monitor; surrounding wall is absent."""
    m=Model();edge=common.material(sprite,(99,99,123));shell=common.material(sprite,(239,239,222))
    m.box((2,14),(0,4),(4,14),edge)
    m.box((3,13),(0,5),(5,14),shell)
    m.box((4,12),(5,7),(5,12),edge)
    m.box((5,11),(5,6),(6,12),shell)
    return m


def union_wall():
    """One shallow red wall and projecting gold-framed display, before slicing."""
    m=Model(width=48,height=32)
    m.box((0,48),(0,18),(8,12),0)
    m.box((0,48),(18,22),(8,12),1)
    m.box((0,48),(22,27),(8,12),2)
    m.box((0,48),(27,29),(7,12),3)
    m.box((17,31),(7,20),(10,16),4)
    m.box((19,29),(9,18),(15,16),5)
    return m


def generate(table,art,registry):
    patch={};wall=union_wall()
    for row in registry['entries']:
        key,recipe=row['key'],row.get('recipe');old=table['cells'][key]
        if review.entry_hash(old)!=row['baseline_entry_sha256']:raise ValueError('Stale inspected source '+key)
        if not recipe:continue
        sprite=art.cell(key,'BAKED')
        if recipe=='terminal_cap':
            model=terminal_cap(sprite);occ,paint=model.occ,model.paint
        elif recipe=='terminal_floor':
            model=Model();model.box((0,16),(0,1),(0,16));occ=model.occ
            y,z,x=np.indices(occ.shape);paint=((8+z//2)*16+x).astype(np.uint8)
        elif recipe.startswith('union_'):
            _,x,layer=recipe.split('_');x,layer=int(x)*16,int(layer)*16
            occ=wall.occ[layer:layer+16,:,x:x+16]
            materials=[common.material(sprite,c) for c in [(239,99,82),(181,198,214),(214,214,214),(255,255,255),(255,198,0),(66,66,82)]]
            paint=np.asarray(materials,np.uint8)[wall.paint[layer:layer+16,:,x:x+16]]
            y,z,xx=np.indices(occ.shape);front=(z==11)|(z==15)
            paint[front]=((15-y)*16+xx)[front]
        else:raise ValueError(recipe)
        patch[key]=encode(occ,paint,old,source='hand-emerald-public-room-review',trim=True)
        if row.get('added_on'):patch[key]['on']=row['added_on']
    return patch


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args()
    table=review.read(review.BASELINE);registry=review.read(REGISTRY);art=common.EmeraldArt(common.BUNDLE)
    patch=generate(table,art,registry);current=copy.deepcopy(table);current['cells'].update(patch)
    args.out.mkdir(exist_ok=True,parents=True);rows=[];stack_evidence={}
    for item in registry['entries']:
        row=dict(item,kind='cell',decision='changed' if item['key'] in patch else 'retained',occurrences=art.locations[item['key']],
                 visual_status='Source, actual map and whole local assembly inspected; Minecraft comparison pending',
                 evidence=[item['source_contact'],*['assemblies/'+a+'.png' for a in item['assemblies']]])
        rows.append(row)
        if item.get('added_on'):
            evidence=[]
            for place in art.locations[item['key']]:
                source=art.maps[place['map']];grid=art.map_cells(source);x,y=place['x'],place['y'];assert grid[y+1][x]==item['added_on']
                raw=base64.b64decode(source['cells']);coll=raw[((y+1)*source['w']+x)*4+2]
                assert coll in (7,147)
                evidence.append(dict(place,below_collision=coll))
            stack_evidence[item['key']]=dict(on=item['added_on'],occurrences=evidence)
    directory=args.out/'assemblies';directory.mkdir(exist_ok=True)
    entities=[common.assembly_evidence(s,current,art,directory) for s in registry['assemblies']]
    write_json(args.out/'updates.json',{'cells':patch,'buildings':{}});write_json(args.out/'ledger.json',rows)
    write_json(args.out/'entities.json',entities);write_json(args.out/'stack-evidence.json',stack_evidence)
    for group in sorted({r['group'] for r in rows}):
        inventory=review.read(review.ROOT/'local/model-gallery/emerald-pass-study/inventory'/group/'entries.json')
        common.contact_pages(group,inventory,current,art,args.out/'current-contacts')
    print(f'{len(rows)} decisions; {len(patch)} changes; {len(entities)} complete map assemblies')


if __name__=='__main__':main()
