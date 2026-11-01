"""Source-specific generic-room beds, sofas and two-row window panels."""
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

OUT=ROOT/'local/model-gallery/emerald-pass-study/generic-soft-furnishings'
GROUP='building__generic_building'
WINDOWS={1:5,2:6,172:1001,173:1002}
ADDED={1001:'g3q/6067-6068-5805-5805',1002:'g3q/6069-6070-5805-5805'}
BEDS=[[[40,41],[45,46]],[[117,118,119],[122,123,124]],[[161],[239]]]
SOFAS=[[[67,68],[74,75]],[[78,79],[85,86]],[[190,171,191],[195,174,196]],[[190,191],[195,196]]]
OWNED=set(WINDOWS)|set(WINDOWS.values())|{n for a in BEDS+SOFAS for row in a for n in row}
WALK={74,75,85,86,174,195,196,117,118,119,122,124}


def rows():
    source=read(INVENTORY/GROUP/'entries.json')
    return [dict(r,source_ordinal=n) for n,r in enumerate(source,1) if n in OWNED]+[
        {'key':key,'source_ordinal':n,'group':GROUP} for n,key in ADDED.items()]


def occurrences(art,key):
    result=[]
    for p in art.locations[key]:
        m=art.maps[p['map']];raw=base64.b64decode(m['cells']);i=(p['y']*m['w']+p['x'])*4
        result.append(dict(p,raw=list(raw[i:i+4]),collision=raw[i+2],map_cells_sha256=hashlib.sha256(raw).hexdigest()))
    return result


def cut(m,layout,n,sprite,palette):
    z,x=next((z,x) for z,row in enumerate(layout) for x,v in enumerate(row) if v==n)
    s=(slice(None),slice(z*16,z*16+16),slice(x*16,x*16+16))
    native=np.asarray([material(sprite,c) for c in palette],np.uint8)
    return m.occ[s],native[m.paint[s]]


def bed(sprite,n):
    layout=next(a for a in BEDS if any(n in row for row in a));w=len(layout[0])*16
    left,right=(3,29) if w==32 else (14,34) if w==48 else (1,15)
    m=Model(width=w,depth=32)
    m.box((left,right),(3,6),(3,30),0)
    m.box((left+1,right-1),(6,9),(5,28),1)
    m.box((left,right),(0,14),(2,5),0)
    m.box((left,right),(5,8),(28,31),0)
    m.box((left+4,right-4),(9,11),(6,11),1)
    # A blue bed has a complete blanket, while white beds only have the native
    # narrow blue/lavender fold below the pillow.
    if n in (40,41,45,46):m.box((left+1,right-1),(9,10),(13,28),2)
    else:m.box((left+1,right-1),(9,10),(12,15),2)
    for x in (left,right-2):
        for z in (3,28):m.box((x,x+2),(0,3),(z,z+2),0)
    return cut(m,layout,n,sprite,[(90,90,115),(250,250,255),(165,160,255)])


def sofa(sprite,n):
    layout=next(a for a in SOFAS if any(n in row for row in a));w=len(layout[0])*16;m=Model(width=w,depth=32)
    m.box((2,w-2),(3,6),(10,29),0)
    m.box((4,w-4),(6,8),(12,27),1)
    m.box((2,w-2),(5,15),(9,13),0)
    m.box((4,w-4),(8,14),(12,14),1)
    for x in (2,w-4):m.box((x,x+2),(5,11),(12,28),0)
    for x in (3,w-6):
        for z in (11,26):m.box((x,x+3),(0,3),(z,z+3),0)
    return cut(m,layout,n,sprite,[(150,150,130),(250,250,235)])


def window(sprite,n):
    m=Model();m.box((0,16),(0,16),(11,15))
    # Native front art continues vertically across upper/lower source rows.
    # Shallow relief follows only the visible window rectangle, never the floor.
    yy,zz,xx=np.indices(m.occ.shape);upper=n in WINDOWS
    if upper:relief=(yy<8)&(xx>=2)&(xx<14)
    else:relief=(yy>=7)&(xx>=2)&(xx<14)
    m.put(relief&(zz==15))
    native=((15-yy)*16+xx).astype(np.uint8)
    m.paint[:]=material(sprite,(210,210,180),(0,3,2,12))
    m.paint[m.occ&(zz>=14)]=native[m.occ&(zz>=14)]
    return m.occ,m.paint


def generate(baseline,art,owned):
    at={r['source_ordinal']:r['key'] for r in owned};updates={};added={};ledger=[];added_ledger=[];meta=[]
    for r in owned:
        n=r['source_ordinal'];key=r['key'];old=baseline['cells'].get(key);sprite=art.cell(key,'BAKED')
        if n in set(WINDOWS)|set(WINDOWS.values()):
            o,p=window(sprite,n);family='Generic wall windows';label='Window '+('upper' if n in WINDOWS else 'lower')+' wall panel'
            reason='Every exact source window occupies two north-wall rows. Construct one shallow32-high wall panel with a raised native window face and continuous wall/sill paint. Existing1→5/2→6 stacks are preserved;172/173 require the exact missing lower source entries and upper links. Source SOLID collision remains unchanged.'
        elif any(n in row for a in BEDS for row in a):
            o,p=bed(sprite,n);family='Generic complete beds';label='Bed frame, pillow or blanket section'
            reason='The source shows one bed across this floor-depth footprint. Build one grounded frame, headboard, mattress, pillow, native blanket/fold and foot rail before slicing. Source side slivers belong to the same frame and exclude yellow/green floor paint. Exact WALK projection pieces receive decor-only display; source SOLID cells and collision remain unchanged.'
        else:
            o,p=sofa(sprite,n);family='Generic white sofas';label='South-facing white sofa section'
            reason='Complete map context shows a gray back north of the white seat, with outer arms and feet. Build one floor-depth sofa facing south with a continuous raised back, cushion, arms and grounded feet; repeat middle sections only in the exact three-wide source run. Lower WALK cells are the projected seat/feet and need decor-only display, not vertical stacking or new collision.'
        new=encode(o,p,old or {'class':'voxels'},'hand-emerald-generic-soft-furnishings')
        if n in WALK:new['walk']=True
        if n in (172,173):new['on']=at[WINDOWS[n]]
        for field in ('walk','on'):
            if old is not None and old.get(field)!=new.get(field):
                meta.append({'key':key,'field':field,'old_value':old.get(field),'new_value':new.get(field),
                    'baseline_entry_sha256':entry_hash(old),'proposed_entry_sha256':entry_hash(new),'source_pixel_mode':'RGBA',
                    'source_pixel_sha256':hashlib.sha256(sprite.tobytes()).hexdigest(),'occurrences':occurrences(art,key),'reason':reason})
        row={'key':key,'kind':'cell','label':label,'family':family,'decision':'added' if old is None else 'changed','reason':reason,
            'baseline_entry_sha256':entry_hash(old) if old else None,'occurrences':occurrences(art,key),
            'evidence':[str(OUT/'source-evidence.json'),str(OUT/'preview/assemblies/index.json')]}
        if old is None:
            row.update(baseline_absent=True,proposed_entry_sha256=entry_hash(new),source_pixel_mode='RGBA',source_pixel_sha256=hashlib.sha256(sprite.tobytes()).hexdigest())
            added[key]=new;added_ledger.append(row)
        else:updates[key]=new;ledger.append(row)
    return updates,added,ledger,added_ledger,meta


def assemblies(art,owned,baseline,current):
    at={r['source_ordinal']:r['key'] for r in owned};out=[]
    patterns=[(a,'Complete '+('blue' if a[0][0]==40 else 'white')+' bed',False) for a in BEDS]
    patterns +=[(a,'Complete south-facing white sofa',False) for a in SOFAS]
    patterns +=[([[upper],[lower]],'Complete two-row wall window',True) for upper,lower in WINDOWS.items()]
    for pattern,label,stack in patterns:
        layout=[[at[n] for n in row] for row in pattern];places=[]
        for p in art.locations[layout[0][0]]:
            g=art.map_cells(art.maps[p['map']]);x,y=p['x'],p['y']
            if [row[x:x+len(layout[0])] for row in g[y:y+len(layout)]]==layout:places.append(p)
        assert places,(pattern,label)
        now=[{'source':[x,z],'offset':[x,1-z if stack else 0,0 if stack else z]} for z,row in enumerate(layout) for x,k in enumerate(row)]
        # Explicit truthful fitted comparison placement; two new SOLID window
        # bases require separately proven native columns, never omission.
        before=[];oldlayout=copy.deepcopy(layout);missing=[]
        for z,row in enumerate(layout):
            for x,k in enumerate(row):
                if k not in baseline['cells']:
                    missing.append(k);continue
                source=occurrences(art,k)[0];old=baseline['cells'][k]
                if source['collision']==0 and not any(old.get(f) for f in ('walk','seat','on')):
                    oldlayout[z][x]=None;continue
                oldstack=stack and k not in (at[172],at[173])
                before.append({'source':[x,z],'offset':[x,1-z if oldstack else 0,0 if oldstack else z]})
        ident='emerald_generic_soft_'+hashlib.sha256(json.dumps(layout,separators=(',',':')).encode()).hexdigest()[:12]
        entity={'id':ident,'kind':'stack (model as one)' if stack else 'object (model as one)','note':label,'layout':layout,
            'placements':now,'occurrences':places}
        if not missing:entity.update(baseline_layout=oldlayout,baseline_placements=before)
        else:
            descriptors=read(OUT/'baseline-descriptors.json');descriptor=descriptors[ident]
            assert descriptor['source_layout']==layout
            assert descriptor['baseline_sha256']==hashlib.sha256(BASELINE.read_bytes()).hexdigest()
            assert descriptor['evidence_sha256']==hashlib.sha256((ROOT/descriptor['evidence']).read_bytes()).hexdigest()
            entity['baseline_native_fragments']=copy.deepcopy(descriptor)
        out.append(entity)
    return out


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    art=EmeraldArt(BUNDLE);baseline=read(BASELINE);owned=rows();updates,added,ledger,added_ledger,meta=generate(baseline,art,owned)
    for r in owned:
        r['occurrences']=art.locations[r['key']]
        r.setdefault('building_source_membership',[])
        r.setdefault('maps',sorted({p['map'] for p in r['occurrences']}))
    current=read(ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json');current['cells'].update(updates|added)
    entities=assemblies(art,owned,baseline,current)
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('additions.json',{'cells':added,'buildings':{}}),('ledger.json',ledger),('added-ledger.json',added_ledger),('metadata-evidence.json',meta),('entities.json',entities),
        ('source-evidence.json',{'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'sources':[{'key':r['key'],'ordinal':r['source_ordinal'],'occurrences':occurrences(art,r['key'])} for r in owned],
            'limits':'Exact source runtime fixtures and native window baseline proof passed; actual Minecraft QA pending. No fitted entry has been invented for the missing original SOLID window bases.'})]:write_json(OUT/name,value)
    if args.previews:
        contact_pages('current',copy.deepcopy(owned),current,art,OUT/'preview');previews(entities,current,art,OUT/'preview/assemblies')
    print(len(updates),'existing changed;',len(added),'source-proven additions;',len(meta),'exact metadata amendments;',len(entities),'complete models')


if __name__=='__main__':main()
