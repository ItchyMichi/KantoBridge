"""Individually inspected Rustboro, Dewford and Lavaridge fixtures."""
from __future__ import annotations
import argparse
import copy
import hashlib
from pathlib import Path
import numpy as np
from tools.shape_fit.emerald_pass import building_interiors as common, review
from tools.shape_fit.blue_pass.common import Model, encode
from tools.model_gallery import write_json

REGISTRY=Path(__file__).with_name('gyms_west_registry.json')
OUT=common.OUT.parent/'gyms-west'


def lavaridge_dais():
    """One low 5-by-2 platform with an orange floor, timber sides and entry step."""
    m=Model(width=80,depth=32)
    m.box((0,80),(0,2),(0,32),0)
    m.box((0,3),(2,8),(0,32),1);m.box((77,80),(2,8),(0,32),1)
    m.box((0,80),(2,8),(0,3),1)
    m.box((0,80),(2,4),(29,32),1)
    return m


def generate(table,art,registry):
    patch={};platform=lavaridge_dais()
    for row in registry['entries']:
        key=row['key'];old=table['cells'].get(key)
        if old is not None and review.entry_hash(old)!=row['baseline_entry_sha256']:
            raise ValueError('Stale inspected cell '+key)
        recipe=row.get('recipe')
        if not recipe:continue
        sprite=art.cell(key,'BAKED')
        if recipe=='barrier_corner':
            m=Model();stone=common.material(sprite,(115,148,140));gold=common.material(sprite,(214,165,82))
            m.box((0,16),(0,6),(3,13),stone)
            m.box((0,16),(6,8),(2,14),gold)
            pale=common.material(sprite,(239,222,156))
            m.box((0,16),(7,8),(2,3),pale);m.box((0,16),(7,8),(13,14),pale)
            occ,paint=m.occ,m.paint
            y,z,x=np.indices(occ.shape);front=(z==12)&(y<6)
            paint[front]=((15-y)*16+x)[front]
        elif recipe=='closed_frieze':
            m=Model();m.box((0,16),(0,16),(13,16),common.material(sprite,(140,90,66)))
            occ,paint=m.occ,m.paint;y,z,x=np.indices(occ.shape)
            paint[z==15]=((15-y)*16+x)[z==15]
        elif recipe.startswith('dais_'):
            _,cx,cz=recipe.split('_');cx,cz=int(cx)*16,int(cz)*16
            occ=platform.occ[:,cz:cz+16,cx:cx+16]
            paint=np.full(occ.shape,common.material(sprite,(140,90,66)),np.uint8)
            y,z,x=np.indices(occ.shape)
            # Horizontal source artwork is floor paint, including the central
            # badge. Project only onto each column's actual upper surface.
            top=occ & ~np.concatenate((occ[1:],np.zeros_like(occ[:1])),axis=0)
            paint[top]=(z*16+x)[top]
        else:raise ValueError(recipe)
        source=old if old is not None else {'class':'voxels',**({'walk':True} if row['collision']==0 else {})}
        patch[key]=encode(occ,paint,source,source='hand-emerald-western-gym-review',trim=True)
    return patch


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args()
    table=review.read(review.BASELINE);registry=review.read(REGISTRY);art=common.EmeraldArt(common.BUNDLE)
    patch=generate(table,art,registry);current=copy.deepcopy(table);current['cells'].update(patch)
    args.out.mkdir(parents=True,exist_ok=True);rows=[]
    for item in registry['entries']:
        key=item['key'];decision='added' if key not in table['cells'] else 'changed' if key in patch else 'retained'
        row=dict(item,kind='cell',decision=decision,occurrences=art.locations[key],
            visual_status='Source, map and complete local assembly inspected; Minecraft pending',
            evidence=[item['source_contact'],*['assemblies/'+a+'.png' for a in item['assemblies']]])
        if decision=='added':
            row.update(baseline_absent=True,proposed_entry_sha256=review.entry_hash(patch[key]),
                       source_pixel_sha256=hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest())
        rows.append(row)
    folder=args.out/'assemblies';folder.mkdir(exist_ok=True)
    entities=[common.assembly_evidence(s,current,art,folder) for s in registry['assemblies']]
    write_json(args.out/'updates.json',dict(cells=patch,buildings={}))
    write_json(args.out/'ledger.json',rows);write_json(args.out/'entities.json',entities)
    for group in sorted({r['group'] for r in rows}):
        inv=review.read(review.ROOT/'local/model-gallery/emerald-pass-study/inventory'/group/'entries.json')
        common.contact_pages(group,inv,current,art,args.out/'current-contacts')
    print(f'{len(rows)} decisions; {len(patch)} changes/additions; {len(entities)} complete assemblies')


if __name__=='__main__':main()
