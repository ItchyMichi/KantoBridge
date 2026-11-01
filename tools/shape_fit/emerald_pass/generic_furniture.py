"""Source-specific generic-room tables, chairs and potted foliage.

This is a partial authoring batch within the independently audited generic
interior inventory. No live resource or unrelated cell is written.
"""
from __future__ import annotations
import argparse,base64,copy,hashlib,json
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE,INVENTORY
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.inventory import contact_pages
from tools.shape_fit.emerald_pass.ship_beams import previews

OUT=ROOT/'local/model-gallery/emerald-pass-study/generic-furniture'
GROUP='building__generic_building'
# Family, west/east corner (or repeating middle), north/south source row.
TABLE_ROLES={}
def register(family,columns):
    for (x,z),ordinals in columns.items():
        for n in ordinals:
            assert n not in TABLE_ROLES
            TABLE_ROLES[n]=(family,x,z)
register('cream',{('L',0):[23,25,60,202],('R',0):[24,26,42,61,203],('L',1):[31,33,206],('R',1):[32,34,207]})
register('striped',{('L',0):[27,204,229,260],('R',0):[28,205,230,261],('L',1):[35,208,235,266],('R',1):[36,209,236,262]})
register('olive',{('L',0):[29],('R',0):[30,43],('L',1):[37],('R',1):[38]})
register('rounded',{('L',0):[132,215],('M',0):[133],('R',0):[134,221],('L',1):[140,216],('M',1):[141,116,217],('R',1):[142,222]})
CHAIRS={15:'L',16:'R',18:'L',20:'R',125:'L',126:'R',127:'L',128:'R',176:'L',177:'R',231:'L',232:'R'}
SLATTED={18,20,231,232}
CUSHIONS={22,39}
CROWNS={13:14,76:83,77:84,157:163,158:162,175:184,178:184,179:185}
BASES=set(CROWNS.values())
OWNED=set(TABLE_ROLES)|set(CHAIRS)|CUSHIONS|set(CROWNS)|BASES


def rows():
    result=[]
    for n,r in enumerate(read(INVENTORY/GROUP/'entries.json'),1):
        if n in OWNED:result.append(dict(r,source_ordinal=n))
    assert {r['source_ordinal'] for r in result}==OWNED
    return result


def table(sprite,n):
    family,side,row=TABLE_ROLES[n];m=Model()
    yy,zz,xx=np.indices(m.occ.shape)
    edge=material(sprite,(165,140,55));top=material(sprite,(250,248,140) if family!='olive' else (185,170,70))
    leg=material(sprite,(68,74,105));light=material(sprite,(255,255,185))
    x0=1 if side=='L' else 0;x1=15 if side=='R' else 16
    z0=1 if row==0 else 0;z1=15 if row==1 else 16
    h=6 if family=='olive' else 10
    outline=(xx>=x0)&(xx<x1)&(zz>=z0)&(zz<z1)
    if family=='rounded':
        # Explicit clipped outer corners; repeating middle pieces stay flush.
        dx=xx if side=='L' else 15-xx if side=='R' else np.full_like(xx,16)
        dz=zz if row==0 else 15-zz
        outline&=(dx+dz>=5)
    m.put(outline&(yy>=h-2)&(yy<h),edge)
    inner=outline&(xx>=x0+(side=='L'))&(xx<x1-(side=='R'))&(zz>=z0+(row==0))&(zz<z1-(row==1))
    m.put(inner&(yy==h-1),top)
    if family=='rounded':
        inlay=material(sprite,(222,132,90)) if side!='M' else top
        corner=outline&(dx+dz<9)&(yy==h-1)
        m.put(corner,inlay)
    if family=='striped':
        stripe=material(sprite,(215,205,85))
        m.put(inner&(yy==h-1)&((xx//4)%2==0),stripe)
    elif family=='cream':
        rim=inner&(((xx==3)&(side=='L'))|((xx==12)&(side=='R'))|((zz==3)&(row==0))|((zz==12)&(row==1)))
        m.put(rim&(yy==h-1),light)
    if side!='M':
        lx=3 if side=='L' else 10;lz=3 if row==0 else 10
        m.box((lx,lx+3),(0,h-2),(lz,lz+3),leg)
        # Short apron joining the legs to the tabletop, not a solid pedestal.
        apron=outline&(yy>=h-4)&(yy<h-2)&((zz==z0) if row==0 else (zz==z1-1))
        m.put(apron,edge)
    if n in (24,30):
        # Native cups lie within the northern source row. Lower quarters are
        # shared by both cup and plain tables and must never invent a cup half.
        cx=5 if n==24 else 3;cz=12
        cup=material(sprite,(245,245,250));dark=material(sprite,(65,75,105))
        m.cylinder((cx,cz),2.4,(h,h+3),cup)
        m.cylinder((cx,cz),1.3,(h+2,h+3),dark)
        m.box((cx+2,cx+4),(h+1,h+2),(cz-1,cz+1),cup)
    if n==116:
        paper=material(sprite,(250,250,245));ink=material(sprite,(80,85,115))
        m.box((4,12),(h,h+1),(2,8),ink).box((4,12),(h+1,h+2),(2,8),paper)
        m.box((7,8),(h+1,h+2),(2,8),ink)
    return m


def chair(sprite,n):
    m=Model();frame=material(sprite,(70,75,110));wood=material(sprite,(185,150,55))
    fill=wood if n in SLATTED else material(sprite,(255,160,85))
    m.box((3,13),(5,7),(3,13),wood).box((4,12),(7,8),(4,12),fill)
    if n not in SLATTED:
        pale=material(sprite,(255,205,140));orange=material(sprite,(240,125,75))
        m.box((5,11),(7,8),(5,11),pale)
        for x,z in ((7,6),(6,7),(8,7),(7,8)):m.box((x,x+1),(7,8),(z,z+1),orange)
    for x in (3,11):
        for z in (3,11):m.box((x,x+2),(0,5),(z,z+2),frame)
    m.box((2,4),(6,14),(3,13),frame)
    if n in SLATTED:
        for z in (4,7,10):m.box((2,4),(8,13),(z,z+2),wood)
    else:m.box((2,4),(8,13),(4,12),fill)
    if CHAIRS[n]=='R':m.occ=m.occ[:,:,::-1].copy();m.paint=m.paint[:,:,::-1].copy()
    return m


def cushion(sprite):
    m=Model();rim=material(sprite,(175,160,55));green=material(sprite,(160,185,70))
    m.box((2,14),(0,2),(2,14),rim).ellipsoid((8,2,8),(6,2,6),green)
    # Green stitched center seen in both native cushion variants.
    stitch=material(sprite,(100,150,70));m.box((6,10),(3,4),(7,9),stitch)
    return m


def plant(sprite,n):
    m=Model()
    if n in CROWNS:
        colors=[material(sprite,c,family='green') for c in ((70,110,40),(110,165,55),(175,220,100))]
        m.box((7,9),(0,8),(7,9),colors[0])
        for cx,cy,cz,r,t in [(8,7,8,6.7,0),(5,8,7,4.3,1),(11,8,9,4.2,1),(8,11,8,4.4,2),(8,7,4,3.8,1),(8,8,12,3.8,0)]:
            m.ellipsoid((cx,cy,cz),(r,r*.75,r),colors[t])
    else:
        bark=material(sprite,(160,130,65),(5,0,11,9));dark=material(sprite,(60,65,90),(3,3,13,10))
        body=material(sprite,(170,175,150),(3,8,13,15));rim=material(sprite,(235,240,220),(2,3,14,10))
        m.cylinder((8,8),4,(0,2),dark).cylinder((8,8),5,(2,8),body)
        m.cylinder((8,8),5.6,(8,10),rim).cylinder((8,8),4.5,(9,10),dark)
        m.box((7,9),(9,16),(7,9),bark)
    return m


def generate(baseline,art,owned):
    updates={};ledger=[]
    for r in owned:
        n=r['source_ordinal'];key=r['key'];old=baseline['cells'][key];sprite=art.cell(key,'BAKED')
        if n in TABLE_ROLES:
            family,side,row=TABLE_ROLES[n];m=table(sprite,n)
            label=f'{family.title()} dining table {"north" if row==0 else "south"} {dict(L="left",M="middle",R="right")[side]}'
            reason='Actual map sources form floor-depth two-row tables. Author one level thin tabletop, source-local wood/cream edge and discrete corner legs; repeating middle sections join flush. Preserve native cup/book details only in their exact source cells. No table quarter is a vertical stack or solid pedestal.'
            family='Generic dining table'
        elif n in CHAIRS:
            m=chair(sprite,n);label=('Slatted wood' if n in SLATTED else 'Orange padded')+' chair facing '+('east' if CHAIRS[n]=='L' else 'west')
            family='Generic side-facing chair';reason='Source chair faces the adjacent dining table. Give it four grounded legs, raised seat and a clear upright side back; preserve the exact seat:true interaction and WALK source collision.'
        elif n in CUSHIONS:
            m=cushion(sprite);label='Low green floor cushion';family='Generic floor cushion'
            reason='The source is a low floor cushion beside the olive table. Retain a shallow padded form with a gold edge and green stitched center; preserve existing seat:true.'
        else:
            m=plant(sprite,n);label='Potted foliage crown' if n in CROWNS else 'Gray plant vessel and trunk';family='Generic potted foliage'
            reason='Source proves a crown immediately above its SOLID gray vessel: the four ordinal-175 crowns are SOLID, while the other seven crown variants are WALK projections. Preserve every existing exact on relation and source collision; give the crown connected layered leaf lobes, centered stem, continuous brown trunk and rounded vessel. Each native background variant uses only its own leaf/bark/vessel pixels.'
        new=encode(m.occ,m.paint,old,'hand-emerald-generic-furniture')
        assert {k:v for k,v in old.items() if k not in ('v','t','up','from')}=={k:v for k,v in new.items() if k not in ('v','t','up','from')}
        updates[key]=new
        ledger.append({'key':key,'kind':'cell','label':label,'family':family,'decision':'changed','reason':reason,
                       'baseline_entry_sha256':entry_hash(old),'occurrences':art.locations[key],
                       'evidence':[str(OUT/'source-evidence.json'),str(ROOT/'local/model-gallery/emerald-pass-study/generic-elite/context'/GROUP/'index.json')]})
    return updates,ledger


def assemblies(art,owned):
    allrows=read(INVENTORY/GROUP/'entries.json');at={i+1:r['key'] for i,r in enumerate(allrows)};ordinal={v:k for k,v in at.items()};found={}
    def add(layout,places,label,stack=False):
        sig=hashlib.sha256(json.dumps([layout,stack],separators=(',',':')).encode()).hexdigest()[:12]
        identifier='emerald_generic_furniture_'+sig
        points=[{'source':[x,z],'offset':[x,len(layout)-1-z if stack else 0,0 if stack else z]} for z,line in enumerate(layout) for x,key in enumerate(line) if key]
        obj=found.setdefault(identifier,{'id':identifier,'kind':'stack (model as one)' if stack else 'object (model as one)','note':label,'layout':layout,'placements':points,'occurrences':[]})
        obj['occurrences'].extend(p for p in places if p not in obj['occurrences'])
    for n,(family,side,row) in TABLE_ROLES.items():
        if side!='L' or row!=0:continue
        for p in art.locations[at[n]]:
            g=art.map_cells(art.maps[p['map']]);x,y=p['x'],p['y'];width=1
            while x+width<len(g[y]):
                role=TABLE_ROLES.get(ordinal.get(g[y][x+width]))
                if not role or role[0]!=family or role[2]!=0:break
                width+=1
                if role[1]=='R':break
            layout=[line[x:x+width] for line in g[y:y+2]]
            assert width>=2 and TABLE_ROLES[ordinal[layout[0][-1]]][1]=='R',(n,p,layout)
            assert all(ordinal.get(k) in TABLE_ROLES for line in layout for k in line),(n,p,layout)
            add(layout,[p],f'Complete {family} dining table in its exact source footprint')
            # Include actual adjacent seats when present on either side.
            x0=max(0,x-1);x1=min(len(g[y]),x+width+1)
            context=[[k if x<=x0+i<x+width or ordinal.get(k) in set(CHAIRS)|CUSHIONS else None for i,k in enumerate(line[x0:x1])] for line in g[y:y+2]]
            if any(ordinal.get(k) in set(CHAIRS)|CUSHIONS for line in context for k in line if k):
                add(context,[dict(p,x=x0)],f'Complete {family} table with adjacent source chairs/cushions')
    for upper,base in CROWNS.items():
        places=[]
        for p in art.locations[at[upper]]:
            g=art.map_cells(art.maps[p['map']]);assert g[p['y']+1][p['x']]==at[base]
            places.append(p)
        add([[at[upper]],[at[base]]],places,'Complete source-proven potted plant',True)
    return list(found.values())


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    art=EmeraldArt(BUNDLE);baseline=read(BASELINE);owned=rows();updates,ledger=generate(baseline,art,owned)
    entities=assemblies(art,owned);source=[]
    for r in owned:
        key=r['key'];places=[]
        for p in art.locations[key]:
            raw=base64.b64decode(art.maps[p['map']]['cells']);w=art.maps[p['map']]['w'];i=(p['y']*w+p['x'])*4
            places.append(dict(p,raw=list(raw[i:i+4]),map_cells_sha256=hashlib.sha256(raw).hexdigest()))
        source.append({'key':key,'ordinal':r['source_ordinal'],'baseline_entry_sha256':entry_hash(baseline['cells'][key]),
                       'proposed_entry_sha256':entry_hash(updates[key]),'source_pixel_mode':'RGBA',
                       'source_pixel_sha256':hashlib.sha256(art.cell(key,'BAKED').tobytes()).hexdigest(),'occurrences':places})
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('source-evidence.json',source),('ownership.json',{'keys':sorted(updates)})]:write_json(OUT/name,value)
    if args.previews:
        current=read(ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json');current['cells'].update(updates)
        contact_pages('current',copy.deepcopy(owned),current,art,OUT/'preview');previews(entities,current,art,OUT/'preview/assemblies')
    print(len(updates),'proposed cells;',len(entities),'complete source assemblies; metadata unchanged')


if __name__=='__main__':main()
