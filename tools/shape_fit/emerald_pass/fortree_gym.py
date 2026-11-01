"""Explicit Fortree bamboo posts, connected turnstile rails and gym fixtures."""
from __future__ import annotations
import argparse
import copy
import hashlib
from pathlib import Path
import numpy as np
from tools.shape_fit.emerald_pass import building_interiors as c,review,gym_qa_corrections as gym
from tools.shape_fit.blue_pass.common import Model,encode,render
from tools.model_gallery import write_json

REGISTRY=Path(__file__).with_name('fortree_gym_registry.json')
OUT=c.OUT.parent/'fortree-gym'


def green_material(sprite,rgb):
    pixels=np.asarray(sprite.convert('RGB')).astype(float).reshape(-1,3)
    eligible=(pixels[:,1]>pixels[:,0])&(pixels[:,1]>pixels[:,2])
    if not eligible.any():raise ValueError('This inspected bamboo variant has no native green')
    distance=((pixels-rgb)**2).sum(1);distance[~eligible]=np.inf
    return int(np.argmin(distance))


def bamboo(sprite,role):
    m=Model();green=green_material(sprite,(66,115,66));ring=green_material(sprite,(132,165,90));core=green_material(sprite,(206,239,165))
    m.cylinder((8,8),6,(0,16),green)
    for y in (1,12):m.cylinder((8,8),6.5,(y,y+2),ring)
    if role=='cap':m.cylinder((8,8),5.5,(15,16),core)
    else:m.cylinder((8,8),4,(15,16),ring)
    # A narrow native-light longitudinal ridge conveys the round culm.
    y,z,x=np.indices(m.occ.shape);m.paint[m.occ&(x>=4)&(x<6)&(z>=11)]=ring
    return m


def rail(sprite,role):
    """A two-rail turnstile section follows its actual north/south footprint."""
    m=Model();green=c.material(sprite,(66,115,66));light=c.material(sprite,(173,206,123));node=c.material(sprite,(239,247,206))
    start=2 if role in ('start','short') else 0
    end=14 if role in ('end','short') else 16
    y,z,x=np.indices(m.occ.shape)
    for xx in (4,12):
        tube=((x+.5-xx)**2+(y+.5-8)**2<=2.5**2)&(z>=start)&(z<end)
        m.put(tube,green);m.paint[tube&(y>=8)]=light
        for zz in (4,12):
            m.put(tube&(z>=zz)&(z<zz+1),node)
    for zz in (5,13):
        if start<=zz<end:m.box((4,12),(6,8),(zz,zz+2),green)
    # Supported pivot feet, below the slender horizontal frame.
    m.box((6,10),(0,7),(6,10),green)
    return m


def generate(table,art,registry):
    updates={}
    for row in registry['entries']:
        key=row['key'];old=table['cells'][key];recipe=row.get('recipe')
        if review.entry_hash(old)!=row['baseline_entry_sha256']:raise ValueError('Stale inspected source '+key)
        if not recipe:continue
        sprite=art.cell(key,'BAKED')
        if recipe.startswith('bamboo_'):m=bamboo(sprite,recipe[7:]);entry=m.entry(old,source='hand-emerald-fortree-gym')
        elif recipe.startswith('rail_'):m=rail(sprite,recipe[5:]);entry=m.entry(old,source='hand-emerald-fortree-gym')
        elif recipe=='ledge':
            m=Model();m.box((0,16),(0,4),(0,16),c.material(sprite,(90,90,107)))
            m.box((0,16),(3,4),(0,16),c.material(sprite,(132,156,90)))
            entry=m.entry(old,source='hand-emerald-fortree-gym')
        elif recipe.startswith('statue_'):
            entry=gym.generate(table,art,[row])[key]
        elif recipe=='walk_platform':
            entry=copy.deepcopy(old);entry['walk']=True
        else:raise ValueError(recipe)
        updates[key]=entry
    return updates


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args()
    registry=review.read(REGISTRY);table=review.read(review.BASELINE);art=c.EmeraldArt(c.BUNDLE);patch=generate(table,art,registry)
    current=copy.deepcopy(table);current['cells'].update(patch);args.out.mkdir(parents=True,exist_ok=True)
    rows=[];walk={}
    for item in registry['entries']:
        key=item['key'];row=dict(item,kind='cell',decision='changed' if key in patch else 'retained',occurrences=art.locations[key],visual_status='Source/model and actual-map assemblies inspected; Minecraft pending',evidence=[item['source_contact'],*['assemblies/'+a+'.png' for a in item['assemblies']]])
        rows.append(row)
        if item.get('recipe')=='walk_platform':
            walk[key]=dict(baseline_entry_sha256=review.entry_hash(table['cells'][key]),proposed_entry_sha256=review.entry_hash(patch[key]),old_metadata={k:v for k,v in table['cells'][key].items() if k not in review.GEOMETRY},new_metadata={k:v for k,v in patch[key].items() if k not in review.GEOMETRY},source_pixel_sha256=hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest(),occurrences=art.locations[key])
        if key in patch:render(patch[key],art.cell(key,'BAKED'),args.out/'models'/key.split('/')[1])
    folder=args.out/'assemblies';folder.mkdir(exist_ok=True)
    entities=[c.assembly_evidence(s,current,art,folder) for s in registry['assemblies']]
    for name,value in [('updates',dict(cells=patch,buildings={})),('ledger',rows),('entities',entities),('walk-proposal',walk)]:write_json(args.out/(name+'.json'),value)
    c.contact_pages('building__fortree_gym',review.read(c.OUT.parent/'inventory/building__fortree_gym/entries.json'),current,art,args.out/'current-contacts')
    print(f'{len(rows)} decisions; {len(patch)} changed; {len(entities)} map assemblies')


if __name__=='__main__':main()
