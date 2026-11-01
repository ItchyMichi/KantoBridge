"""Manually audited Petalburg hedges and Mossdeep's complete coastal trees.

Source positions refer to immutable inventory contact sheets. Geometry is
explicit parts in whole-object coordinates; exported pixels provide paint only.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path

import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import Model, decode, encode
from tools.shape_fit.emerald.littleroot_tree import KEYS, ROLES, generate as broadleaf
from tools.shape_fit.emerald_pass.environment import assembly_previews, closest_uv, rock
from tools.shape_fit.emerald_pass.inventory import contact_pages
from tools.shape_fit.emerald_pass.projected_terrain import surface
from tools.shape_fit.emerald_pass.review import entry_hash, read
from tools.shape_fit.emerald_pass.vegetation import (
    ROOT, BASELINE, BUNDLE, LEAVES, BARK, BROADLEAF, color_uv, repaint_template,
)

OUT = ROOT/'local/model-gallery/emerald-pass-study/coastal'
INVENTORY = ROOT/'local/model-gallery/emerald-pass-study/inventory'
PETALBURG = 'general__petalburg'
MOSSDEEP = 'general__mossdeep'
HEDGES = (2,3,4,5,6,7,8,9,12,17,18,19)
PETALBURG_TREES = {10:476,11:477,13:476,14:476,25:486}
# Roof-occluded quarters have no bark texels; require explicit runtime treatment.
PENDING = {15,16}
SMALL_ROLES = {(0,0):(5,20,30,32,34,39,43), (1,0):(6,21,31,33,35,38,42),
               (0,1):(10,25,36), (1,1):(11,26,37)}
LARGE_ROLES = {(0,0):(7,12,16), (1,0):(8,13,17), (2,0):(9,18),
               (0,1):(15,22), (1,1):(23,), (2,1):(19,24),
               (0,2):(27,), (1,2):(28,), (2,2):(29,)}
ROCK_ROLES = {(0,0):(1,), (1,0):(2,), (0,1):(40,), (1,1):(41,)}


def inventory_rows():
    by_group={g:read(INVENTORY/g/'entries.json') for g in (PETALBURG,MOSSDEEP)}
    rows=[r for r in by_group[PETALBURG] if r['contact_position'] not in (1,22)]
    rows += [r for r in by_group[MOSSDEEP] if r['contact_position'] not in (3,4)]
    assert len(rows)==64 and len({r['key'] for r in rows})==64
    return rows


def coastal_tree(large=False):
    """Connected trunk and overlapping tiered rounded leaf lobes, before slicing."""
    size,height=(48,48) if large else (32,36)
    m=Model(height=height,width=size,depth=size)
    cx,cz=(24,38) if large else (16,23)
    m.cylinder((cx,cz),4.2 if large else 2.6,(0,24 if large else 18))
    m.cylinder((cx,cz),5.6 if large else 3.8,(0,2))
    for side in (-1,1):
        for y in range(8,23 if large else 18):
            t=(y-8)/(14 if large else 9)
            m.cylinder((cx+side*t*(7 if large else 5),cz),1.8 if large else 1.4,(y,y+1))
    wood=m.occ.copy()
    leaf_model=Model(height=height,width=size,depth=size)
    lobes=(((24,21,23),(22,11,21)),((24,28,25),(20,12,18)),((10,23,27),(9.5,10,13)),((38,23,27),(9.5,10,13)),
           ((24,20,35),(18,8,11)),((24,37,20),(12,10,12)),((24,29,10),(13,9,9))) if large else (
          ((16,23,16),(13,8,12)),((8,20,17),(7.5,7,8)),((24,20,17),(7.5,7,8)),
          ((16,18,24),(11,6,7.5)),((16,29,14),(8.5,6.5,8)),((16,22,8),(10,7,6.5)))
    for center,radii in lobes:leaf_model.ellipsoid(center,radii)
    leaf=leaf_model.occ
    m.occ |= leaf
    return m.occ,leaf,wood&~leaf


def painted_tree_slice(whole,sprite,role,original,large=False):
    occ,leaves,wood=whole;x,z=role
    selection=(slice(None),slice(z*16,(z+1)*16),slice(x*16,(x+1)*16))
    o=occ[selection];l=leaves[selection];w=wood[selection]
    yy,zz,xx=np.indices(o.shape);zz+=z*16;xx+=x*16
    n=((xx//2*73856093)^(yy//2*19349663)^(zz//2*83492791))%100
    tone=np.where(n<14,0,np.where(n<62,1,2))
    # Southern native cells lack lime. Fade highlights before their source seam.
    bright_until=30 if large else 14
    tone=np.where((yy>(29 if large else 23))&(n<np.clip((bright_until-zz)*8,0,35)),3,tone)
    available=[c for c in LEAVES if np.any(np.all(np.asarray(sprite.convert('RGB'))==c,axis=2))]
    palette=[color_uv(sprite,c if c in available else available[-1]) for c in LEAVES]
    paint=np.asarray(palette,np.uint8)[tone]
    if w.any():
        bark=[color_uv(sprite,c) for c in BARK]
        paint[w]=np.asarray(bark,np.uint8)[((xx+zz)//2)%2][w]
    assert o.any()
    return encode(o,paint,original,source='hand-emerald-coastal-tree',trim=True)


def hedge(sprite,n):
    """Low clipped hedge paths, with top/side materials distinct from grass."""
    z,x=np.indices((16,16));mask=np.zeros((16,16),bool)
    if n in (2,3,4):
        mask=z>=12
        if n==2:mask &= ((x-15)**2+(z-16)**2<=16**2)
        if n==4:mask &= ((x-0)**2+(z-16)**2<=16**2)
    elif n==5:mask=(x>=2)&(x<14)&((z>=8)|((x-8)**2+(z-8)**2<=6**2))
    elif n in (6,8,9,19):
        mask=(x>=2)&(x<14)
        if n==6:mask|=(z<4)&(x>=2)
        if n==8:mask|=(z<4)&(x<14)
        if n==19:mask|=(z>=12)&(x<14)
    elif n==7:mask=z<14
    elif n==12:mask=(x>=2)&(x<14)&((z<8)|((x-8)**2+(z-8)**2<6**2))
    elif n==17:mask=(x>=2)&(z<14)
    elif n==18:mask=(x<14)&(z<14)
    else:raise ValueError(n)
    m=Model(height=8)
    m.occ[:]=mask
    yy,zz,xx=np.indices(m.occ.shape)
    colors=set(map(tuple,np.asarray(sprite.convert('RGB')).reshape(-1,3)))
    dark,green,light=[color_uv(sprite,c if c in colors else LEAVES[1]) for c in LEAVES[:3]]
    noise=((xx//2*73856093)^(yy//2*19349663)^(zz//2*83492791))%100
    m.paint[:]=np.where(yy==7,np.where(noise<20,green,light),
                       np.where(noise<12,dark,np.where(noise>88,light,green)))
    return m


def sea_boulder(sprite,role):
    whole=Model(height=32,width=32,depth=32)
    whole.ellipsoid((16,11,17),(14.5,18,13.5))
    whole.ellipsoid((13,7,19),(10,10,10))
    x,z=role;m=Model(height=32);m.occ[:]=whole.occ[:,z*16:(z+1)*16,x*16:(x+1)*16]
    yy,zz,xx=np.indices(m.occ.shape)
    tones=((148,148,123),(173,173,156),(206,206,189),(247,247,239))
    palette=np.array([closest_uv(sprite,c) for c in tones],np.uint8)
    noise=(((xx+x*16)//3*73856093)^(yy//3*19349663)^((zz+z*16)//3*83492791))%100
    tone=np.clip(yy//8+(noise<23).astype(int)-(noise>84).astype(int),0,3)
    m.paint[:]=palette[tone]
    # Native surrounding sea is horizontal at the base, never an upright wall.
    m.occ[0]=True;m.paint[0]=(zz[0]*16+xx[0]).astype(np.uint8)
    return m


def generate(table,art,rows):
    updates={};ledger=[];approved=broadleaf();small=coastal_tree();large=coastal_tree(True)
    role_lookup={n:role for role,values in SMALL_ROLES.items() for n in values}
    large_lookup={n:role for role,values in LARGE_ROLES.items() for n in values}
    rock_lookup={n:role for role,values in ROCK_ROLES.items() for n in values}
    for row in rows:
        key,n,group=row['key'],row['contact_position'],row['group'];old=table['cells'][key]
        assert entry_hash(old)==row['baseline_entry_sha256'],key
        sprite=art.cell(key,'BAKED')
        assert hashlib.sha256(sprite.tobytes()).hexdigest()==row['sprite_sha256'],key
        if group==PETALBURG and n in PENDING:continue
        entry=None
        if group==PETALBURG:
            if n in HEDGES:
                entry=hedge(sprite,n).entry(old,source='hand-emerald-clipped-hedge',trim=True)
                label,family='Clipped hedge path or rounded end','clipped hedge'
                reason='Petalburg map neighborhoods show connected planted hedge runs and corners around buildings and flowerbeds. Model low solid foliage paths with rounded ends, a light green top and dark green sides; exclude mint grass and shadow from the foliage material. Whole connected hedge layouts are recorded separately.'
            elif n in PETALBURG_TREES:
                mid=PETALBURG_TREES[n];base=approved[KEYS[mid]]
                entry=repaint_template(base,art.cell(KEYS[mid],'BAKED'),sprite,old)
                label,family='Broadleaf tree quarter beside hedge or roof','broadleaf tree'
                reason='Exact two-by-two map adjacency identifies this lower tree quadrant. Extend the approved whole-tree geometry with its native leaf and bark texels; hedge/Center-roof pixels are adjoining background, not trunk or foliage. Preserve original placement metadata.'
            elif n in (20,21,23):
                entry=surface(sprite).entry(old,source='hand-emerald-projected-terrain')
                label,family='Route 102 grass below projected crown tip','projected tree terrain'
                reason='Route 102 neighborhoods place this corner foliage north of a complete solid two-by-two broadleaf tree. Keep one-voxel grass painted across x/z, replacing only projected leaf addresses with nearest native grass; do not add a detached miniature crown.'
            elif n==24:
                label,family='Route 102 curved cliff-foot section','cliff edge'
                reason='At Route 102 (34,8), this is the curved lower end of a longer rock ledge. Inspected source/front/east/top show an already low, continuous four-voxel ledge section rather than a freestanding prop. Retain its footprint, native rock band and matching metadata.'
            else:raise ValueError((group,n))
        elif n in role_lookup or n in large_lookup:
            big=n in large_lookup;role=(large_lookup if big else role_lookup)[n]
            entry=painted_tree_slice(large if big else small,sprite,role,old,big)
            label,family=('Large coastal tree section','large coastal tree') if big else ('Coastal tree quadrant','coastal tree')
            reason=f'Mossdeep map context identifies the {role} section of a {3 if big else 2}-by-{3 if big else 2} coastal tree. Author a connected rooted trunk and tiered rounded foliage in whole-object coordinates, then slice and paint with native leaf/bark colors. Water, sand, cliff and adjoining-tree shadows are not physical tree material.'
        elif n in rock_lookup:
            entry=sea_boulder(sprite,rock_lookup[n]).entry(old,source='hand-emerald-sea-boulder',trim=True)
            label,family='Route 126 pale sea-boulder quadrant','pale sea boulder'
            reason='Four exact adjacent Route 126 source quarters form a single large pale sea boulder, not a wall edge. Slice one rounded irregular solid across its two-by-two footprint; pale mineral colors shade the rock while the surrounding water remains a one-voxel horizontal surface.'
        elif n==14:
            entry=rock(sprite,n,MOSSDEEP).entry(old,source='hand-emerald-coastal-rock',trim=True)
            label,family='Small pale Mossdeep boulder','pale boulder'
            reason='Mossdeep (57,21) shows a single rounded pale boulder beside the sand path and cliff. Explicit overlapping rounded rock masses replace the flat foreground extrusion, using native neutral mineral colors and excluding grass backing.'
        else:raise ValueError((group,n))
        if entry is not None:updates[key]=entry
        place=row['occurrences'][0]
        ledger.append({'key':key,'kind':'cell','label':label,'family':family,'decision':'changed' if entry is not None else 'retained',
            'reason':reason+f" Inspected indexed source context: {place['map']} ({place['x']},{place['y']}); original occupied bounds {row['occupied_bounds_yzx']} and {row['voxels']} voxels.",
            'baseline_entry_sha256':entry_hash(old),'occurrences':row['occurrences'],
            'evidence':[str(INVENTORY/group/f"page-{(n-1)//32+1:03d}.png"),str(INVENTORY.parent/'towns'/group/'contexts'/f"page-{(n-1)//16+1:03d}.png")]})
    return updates,ledger


def assemblies_for(art,rows,updates):
    by_group={g:{r['contact_position']:r['key'] for r in rows if r['group']==g} for g in (PETALBURG,MOSSDEEP)}
    types=[]
    for label,roles,size in [('Coastal tree',SMALL_ROLES,2),('Large coastal tree',LARGE_ROLES,3),('Pale sea boulder',ROCK_ROLES,2)]:
        types.append((label,{by_group[MOSSDEEP][n]:role for role,ns in roles.items() for n in ns},size))
    broad_roles={KEYS[mid]:role for mid,role in ROLES.items()}
    broad_roles.update({key:ROLES[mid] for key,mid in BROADLEAF.items()})
    broad_roles.update({by_group[PETALBURG][n]:ROLES[mid] for n,mid in PETALBURG_TREES.items()})
    types.append(('Broadleaf hedge/roof source variant',broad_roles,2))
    hedge_keys={by_group[PETALBURG][n] for n in HEDGES};found={}
    def add(layout,label,place):
        identifier='emerald_coastal_'+hashlib.sha256(json.dumps(layout,separators=(',',':')).encode()).hexdigest()[:12]
        item=found.setdefault(identifier,{'id':identifier,'kind':'object (model as one)','note':label,'layout':layout,'occurrences':[]})
        item['occurrences'].append(place)
    for name,source in art.maps.items():
        grid=art.map_cells(source);h,w=len(grid),len(grid[0])
        for label,roles,size in types:
            for y in range(h-size+1):
                for x in range(w-size+1):
                    if roles.get(grid[y][x])!=(0,0):continue
                    layout=[line[x:x+size] for line in grid[y:y+size]]
                    if all(roles.get(key)==(dx,dz) for dz,line in enumerate(layout) for dx,key in enumerate(line)) and any(k in updates for line in layout for k in line):
                        add(layout,label,{'map':name,'x':x,'y':y})
        unseen={(x,y) for y,line in enumerate(grid) for x,key in enumerate(line) if key in hedge_keys}
        while unseen:
            component=set();todo=[min(unseen)]
            while todo:
                x,y=todo.pop()
                if (x,y) not in unseen:continue
                unseen.remove((x,y));component.add((x,y));todo.extend(((x-1,y),(x+1,y),(x,y-1),(x,y+1)))
            x0,x1=min(x for x,y in component),max(x for x,y in component)+1;y0,y1=min(y for x,y in component),max(y for x,y in component)+1
            if len(component)>1:add([[grid[y][x] if (x,y) in component else None for x in range(x0,x1)] for y in range(y0,y1)],'Complete clipped hedge path',{'map':name,'x':x0,'y':y0})
    definitions=[{k:v for k,v in item.items() if k!='occurrences'} for item in found.values()]
    return definitions,list(found.values())


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args()
    table=read(BASELINE);art=EmeraldArt(BUNDLE);rows=inventory_rows();updates,ledger=generate(table,art,rows)
    definitions,placements=assemblies_for(art,rows,updates)
    write_json(args.out/'updates.json',{'cells':updates,'buildings':{}});write_json(args.out/'ledger.json',ledger)
    write_json(args.out/'entities.json',definitions);write_json(args.out/'assembly-placements.json',placements)
    write_json(args.out/'ownership.json',{'owner':'emerald_scenery','keys':[r['key'] for r in rows]})
    write_json(args.out/'pending.json',[r for r in rows if r['group']==PETALBURG and r['contact_position'] in PENDING])
    current=copy.deepcopy(table);current['cells'].update(updates)
    # Include already proposed vegetation variants in whole-tree previews only.
    current['cells'].update(read(INVENTORY.parent/'vegetation/updates.json')['cells'])
    contact_pages('coastal-current',[copy.deepcopy(r) for r in rows if r['key'] in updates],current,art,args.out/'preview')
    assembly_previews(definitions,current,art,args.out/'preview/assemblies')
    print(f'{len(ledger)} audited, {len(updates)} changed, {len(rows)-len(ledger)} pending; {len(definitions)} complete assemblies')


if __name__=='__main__':main()
