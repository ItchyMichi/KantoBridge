"""Rustboro School desks, chair variants, architectural feet and whole board."""
from __future__ import annotations
import argparse
import base64
import copy
import hashlib
import json

import numpy as np

from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,decode,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE,INVENTORY
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.inventory import contact_pages,compact_context_pages
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.center import source_occurrences
from tools.shape_fit.emerald_pass.residential import slice_model
from tools.shape_fit.emerald_pass.ship_beams import previews

OUT=ROOT/'local/model-gallery/emerald-pass-study/school'
GROUP='building__pokemon_school'
MAP='EM_RUSTBORO_CITY_POKEMON_SCHOOL'
EXTRA=('g3q/8656-8657-8658-8659','g3q/8660-8661-8662-8663','g3q/8664-8665-8666-8667')
ADD_ON={13:16,17:14,18:15,19:15}
REMOVE_ON={1:8,5:8}
WALK={8,9,10}


def rows(art=None):
    art=art or EmeraldArt(BUNDLE);result=copy.deepcopy(read(INVENTORY/GROUP/'entries.json'))
    for key in EXTRA:result.append({'key':key,'group':GROUP,'baseline_absent':True,'building_source_membership':[],
                                  'occurrences':art.locations[key],'maps':sorted({p['map'] for p in art.locations[key]})})
    for i,r in enumerate(result,1):r['source_ordinal']=i
    return result


def student_desk(sprite,n):
    m=Model();frame=material(sprite,(65,70,100));wood=material(sprite,(200,150,15));edge=material(sprite,(155,115,10))
    m.box((2,14),(7,8),(1,14),edge).box((2,14),(8,9),(1,14),wood)
    for x in (2,12):
        for z in (2,11):m.box((x,x+2),(0,7),(z,z+2),frame)
    if n==3:
        page=material(sprite,(255,255,230),(1,0,15,9));spine=material(sprite,(175,175,85),(1,0,15,9))
        m.box((3,13),(9,10),(3,12),spine).box((3,7),(10,11),(3,12),page).box((8,13),(10,11),(3,12),page)
        m.box((7,8),(10,11),(3,12),spine)
    else:
        for z in range(1,14):
            for x in range(2,14):m.paint[8,z,x]=(2+(z-1)*7//13)*16+x
    return m.occ,m.paint


def student_chair(sprite,n):
    m=Model();frame=material(sprite,(65,70,100));wood=material(sprite,(180,120,35),(2,0,14,7))
    m.box((4,12),(4,6),(2,12),wood)
    for x in (4,10):
        for z in (3,10):m.box((x,x+2),(0,4),(z,z+2),frame)
    # All chairs face the board. The distinct native7 source depicts a tilted
    # rear edge, retained as an inclined wooden back, not a broken-chair claim.
    if n==6:m.box((4,12),(6,11),(10,12),wood)
    else:
        for x in range(4,12):m.box((x,x+1),(6,8+(x-4)//2),(10,12),wood)
    return m.occ,m.paint


def wall_column(sprite,n):
    m=Model(height=32 if n in (1,5) else 16);cream=material(sprite,(240,240,185));edge=material(sprite,(100,110,130))
    if n in (1,5):
        m.box((3,13),(0,32),(9,16),cream)
        for x in (3,12):m.box((x,x+1),(0,32),(9,16),edge)
        yy,zz,xx=np.indices(m.occ.shape);front=m.occ&(zz==15)
        native=(np.where(yy>=16,31-yy,15)*16+xx).astype(np.uint8);m.paint[front]=native[front]
    else:
        green=material(sprite,(115,175,105),family='green');light=material(sprite,(205,240,210))
        m.box((3,13),(0,5),(0,6),green).box((2,14),(0,1),(0,7),edge)
        m.box((3,13),(4,5),(0,6),light)
        for x in (3,12):m.box((x,x+1),(1,5),(0,6),edge)
    return m.occ,m.paint


def teacher_floor(sprite):
    m=Model();m.box((0,16),(0,1),(0,16));yy,zz,xx=np.indices(m.occ.shape)
    # Rebuild the room's square gray floor motif from source-local floor
    # colors; repeating a cropped row would make false horizontal stripes.
    pale=material(sprite,(220,220,210),(0,5,16,10),family='neutral')
    gray=material(sprite,(190,190,180),(0,5,16,10),family='neutral')
    m.paint[:]=pale
    edge=(xx>=2)&(xx<14)&(zz>=2)&(zz<14)&~((xx>=5)&(xx<11)&(zz>=5)&(zz<11))
    m.paint[edge]=gray
    return m.occ,m.paint


def teacher_desk(sprite,n):
    m=Model(width=32,depth=16)
    m.box((1,31),(7,8),(2,15),0).box((1,31),(8,10),(2,15),1)
    for x in (2,28):
        for z in (3,12):m.box((x,x+2),(0,7),(z,z+2),0)
    m.box((3,29),(1,7),(12,14),2)
    m.box((3,29),(6,7),(13,15),0)
    return slice_model(m,((11,12),),n,sprite,[(120,110,40),(225,225,140),(215,175,25)])


def board(sprite,n):
    m=Model();m.box((0,16),(0,16),(10,14))
    # Existing13 has only an outline; fill the green writing surface using
    # exact native source addresses, continuous with the three missing cells.
    m.project()
    return m.occ,m.paint


def generate(baseline,art,owned):
    key_at={r['source_ordinal']:r['key'] for r in owned};updates={};ledger=[];amendments=[]
    for r in owned:
        n=r['source_ordinal'];key=r['key'];old=baseline['cells'].get(key,{'class':'voxels'});sprite=art.cell(key,'BAKED')
        if n in (2,3,4):
            o,p=student_desk(sprite,n);label={2:'Plain student desk',3:'Student desk with open book',4:'Student desk adjoining tilted chair'}[n];family='School student desk'
            reason='Exact source places each gold desk on SOLID7 north of a separate seat:true WALK chair. Give the desk a thin raised wooden top and four dark metal legs, retaining the native checked top or open pale book. Do not stack the desk onto the chair.'
        elif n in (6,7):
            o,p=student_chair(sprite,n);label='North-facing student chair' if n==6 else 'Student chair with inclined back edge';family='School student chair'
            reason='Native chair source lies south of the desk on WALK0 with seat:true. Author one grounded wooden seat, rear back facing the board and four dark legs. Variant7 retains the visually inclined rear edge without asserting damage; preserve the original seat interaction.'
        elif n in (1,5,8):
            o,p=wall_column(sprite,n);label={1:'Wall column with small notice',5:'Plain classroom wall column',8:'Projected green wall-column foot'}[n];family='School wall columns'
            reason='Complete room source shows four cream columns beside the windows/board, with green feet projecting onto the next floor row. Those four foot sources are all WALK0, so they are not valid vertical stack bases. Keep a32-high column at its SOLID wall position, matching the complete board height and four-block room headroom; preserve the native notice at original scale in the upper half. A shallow green foot occupies the adjacent floor. Remove only1/5-to8 links and enable decorative display only for foot8. Horizontal interfaces touch; source collision stays unchanged.'
        elif n in (9,10):
            o,p=teacher_floor(sprite);label='Teacher desk rear floor '+('left' if n==9 else 'right');family='School teacher desk floor'
            reason='This WALK0 source is gray floor with the top of the teacher desk projected into its south edge. The complete desk is authored in the two SOLID cells south of it; replace the duplicate tabletop projection by a one-voxel native gray floor and walk decor.'
        elif n in (11,12):
            o,p=teacher_desk(sprite,n);label='Teacher desk '+('left' if n==11 else 'right')+' half';family='School teacher desk'
            reason='The exact two-wide pale desk has a single level top, gold modesty panel and four grounded legs. Model it coherently across the two SOLID source cells and remove the upper WALK-row tabletop duplication independently.'
        else:
            o,p=board(sprite,n);label=('Chalkboard section '+str({17:1,18:2,19:3,13:4}[n])) if n in ADD_ON else 'Golden slatted wall panel '+str(n-13);family='School chalkboard and wall panel'
            reason='Complete four-wide source board contains a continuous green chalk surface, native gold/blue border, white writing and eraser; its lower source row is the matching gold slatted wall plinth. Three upper sources were absent from the fitted table. Add those source-pinned entries and four exact upper-to-lower links, forming one32-high shallow native-painted wall panel; preserve the source SOLID7 collision and four-block room headroom.'
        new=encode(o,p,old,'hand-emerald-school-review');changes={}
        if n in ADD_ON:
            assert 'on' not in old;new['on']=key_at[ADD_ON[n]];changes['on']={'old':None,'new':new['on']}
        if n in REMOVE_ON:
            assert old.get('on')==key_at[REMOVE_ON[n]];changes['on']={'old':new.pop('on'),'new':None}
        if n in WALK:
            assert 'walk' not in old;new['walk']=True;changes['walk']={'old':None,'new':True}
        absent=key not in baseline['cells'];sourcehash=hashlib.sha256(sprite.tobytes()).hexdigest()
        if changes:amendments.append({'key':key,'source_ordinal':n,'baseline_absent':absent,'baseline_entry_sha256':None if absent else entry_hash(old),
            'proposed_entry_sha256':entry_hash(new),'source_pixel_sha256':sourcehash,'source_pixel_mode':sprite.mode,
            'changes':changes,'occurrences':source_occurrences(art,key)})
        updates[key]=new
        row={'key':key,'kind':'cell','label':label,'family':family,'decision':'added' if absent else 'changed','reason':reason,
             'baseline_entry_sha256':None if absent else entry_hash(old),'occurrences':art.locations[key],
             'evidence':[str(OUT/'source-context/page-001.png'),str(OUT/'preview/current/page-001.png'),str(OUT/'source-evidence.json')]}
        if absent:row.update(baseline_absent=True,source_pixel_sha256=sourcehash,source_pixel_mode=sprite.mode,proposed_entry_sha256=entry_hash(new))
        ledger.append(row)
    return updates,ledger,amendments


def assemblies(art,owned,baseline,current):
    native_path=ROOT/'local/model-gallery/emerald-pass-study/school-native-fallback.json'
    native_hash=hashlib.sha256(native_path.read_bytes()).hexdigest()
    assert native_hash=='cc80632e07d8af5516ce06bce54cf67d3ee4937597c1d8636a4209a706088539'
    native_proof=read(native_path)
    assert native_proof['baseline_sha256']==hashlib.sha256(BASELINE.read_bytes()).hexdigest()
    native_cells={r['key']:dict(r,map=native_proof['map'],baseline_sha256=native_proof['baseline_sha256'],
                              evidence_sha256=native_hash) for r in native_proof['cells'] if r['key'] in EXTRA}
    key_at={r['source_ordinal']:r['key'] for r in owned};by_key={v:k for k,v in key_at.items()};m=art.maps[MAP]
    grid=art.map_cells(m);raw=base64.b64decode(m['cells']);found={}
    def add(x,y,w,h,label):
        reference=[line[x:x+w] for line in grid[y:y+h]];layout=[[k if k in by_key else None for k in line] for line in reference]
        def positions(table,before=False):
            present={}
            for zz,line in enumerate(layout):
                for xx,key in enumerate(line):
                    if not key:continue
                    collision=raw[((y+zz)*m['w']+x+xx)*4+2]
                    if key not in table['cells']:
                        if before and key in EXTRA:present[(xx,zz)]=key
                        continue
                    entry=table['cells'][key]
                    if collision==7 or any(entry.get(f) for f in ('on','seat','walk')):present[(xx,zz)]=key
            links={p:(p[0],p[1]+1) for p,k in present.items() if table['cells'].get(k,{}).get('on') is not None and table['cells'][k]['on']==present.get((p[0],p[1]+1))
                   and raw[((y+p[1]+1)*m['w']+x+p[0])*4+2]==7}
            points=[]
            for start in sorted(set(present)-set(links.values())):
                chain=[start]
                while chain[-1] in links:chain.append(links[chain[-1]])
                end=chain[-1]
                for level,p in enumerate(reversed(chain)):points.append({'source':list(p),'offset':[end[0],level,end[1]]})
            shown={tuple(p['source']) for p in points}
            return points,[[k if (xx,zz) in shown else None for xx,k in enumerate(line)] for zz,line in enumerate(layout)]
        now,shown=positions(current);before,old=positions(baseline,True)
        ident='emerald_school_'+hashlib.sha256(json.dumps([shown,now,before],separators=(',',':')).encode()).hexdigest()[:12]
        item=found.setdefault(ident,{'id':ident,'kind':'object (model as one)','note':label,'layout':shown,'reference_layout':reference,'placements':now,
                                    'baseline_layout':old,'baseline_placements':before,'occurrences':[]})
        missing=[key for line in old for key in line if key in EXTRA]
        if missing:item['baseline_native_cells']={key:copy.deepcopy(native_cells[key]) for key in missing}
        item['occurrences'].append({'map':MAP,'x':x,'y':y})
    for n in (2,3,4):
        for p in art.locations[key_at[n]]:add(p['x'],p['y'],1,2,'Complete student desk and north-facing chair')
    for n in (1,5):
        for p in art.locations[key_at[n]]:add(p['x'],p['y'],1,2,'Complete wall column and horizontal projected green foot')
    add(5,3,2,2,'Complete teacher desk and native rear floor')
    add(4,1,4,2,'Complete four-wide chalkboard and gold slatted wall plinth')
    add(0,1,12,2,'Classroom front wall columns and complete chalkboard')
    add(1,5,10,6,'All twelve student desks and chairs in source arrangement')
    return [{k:v for k,v in e.items() if k!='occurrences'} for e in found.values()],list(found.values())


def source_evidence(art,owned,baseline,updates):
    key_at={r['source_ordinal']:r['key'] for r in owned};stacks=[]
    for upper,lower in ADD_ON.items():
        for p in source_occurrences(art,key_at[upper]):
            m=art.maps[p['map']];raw=base64.b64decode(m['cells']);assert p['below_key']==key_at[lower] and p['collision']==7
            assert raw[((p['y']+1)*m['w']+p['x'])*4+2]==7
            stacks.append(dict(p,upper=key_at[upper],lower=key_at[lower],source_collisions=[7,7],carrier_height_blocks=4,
                               actual_geometry_height_voxels=32,headroom_blocks=6))
    return {'sources':[{'ordinal':r['source_ordinal'],'key':r['key'],'baseline_absent':r['key'] not in baseline['cells'],
                       'baseline_entry_sha256':entry_hash(baseline['cells'][r['key']]) if r['key'] in baseline['cells'] else None,
                       'proposed_entry_sha256':entry_hash(updates[r['key']]),'source_pixel_mode':'RGBA',
                       'source_pixel_sha256':hashlib.sha256(art.cell(r['key'],'BAKED').tobytes()).hexdigest(),
                       'occurrences':source_occurrences(art,r['key'])} for r in owned],
            'new_wall_stacks':stacks,'missing_solid_baseline':'Three absent entries remain present in baseline_layout with exact baseline_native_cells columns from EmeraldSchoolFallbackTest. The gallery represents every native CUBE at measured headroom6 and preserves the immutable baseline table. They are not invisible WALK cells.',
            'runtime_fixture':{'path':'fabric-bridge/src/test/java/dev/kantobridge/builder/shape/EmeraldSchoolFallbackTest.java','result':'one test passed, zero skips; source map and actual ArtUnitProbe baseline/current topology',
                               'evidence':'local/model-gallery/emerald-pass-study/school-native-fallback.json','evidence_sha256':'cc80632e07d8af5516ce06bce54cf67d3ee4937597c1d8636a4209a706088539'},
            'column_interpretation':'Four WALK0 green feet project south from the source wall columns. The cream body and shallow foot remain at adjacent floor positions with touching interfaces, not a vertical stack onto a WALK carrier.',
            'chair_variant':'The source4/7 rear chair edge is visibly inclined; retain that visual shape without inferring damage or a named object.'}


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=type(OUT),default=OUT);args=ap.parse_args()
    art=EmeraldArt(BUNDLE);baseline=read(BASELINE);owned=rows(art);updates,ledger,meta=generate(baseline,art,owned)
    current=read(ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json');current['cells'].update(updates)
    entities,places=assemblies(art,owned,baseline,current)
    for name,value in [('updates.json',{'cells':{k:v for k,v in updates.items() if k not in EXTRA},'buildings':{}}),
                       ('additions.json',{'cells':{k:v for k,v in updates.items() if k in EXTRA},'buildings':{}}),
                       ('ledger.json',[r for r in ledger if not r.get('baseline_absent')]),('added-ledger.json',[r for r in ledger if r.get('baseline_absent')]),
                       ('metadata-evidence.json',meta),('source-evidence.json',source_evidence(art,owned,baseline,updates)),
                       ('entities.json',entities),('assembly-placements.json',places),('ownership.json',{'owner':'emerald_scenery','keys':[r['key'] for r in owned]})]:write_json(args.out/name,value)
    contact_pages('source',copy.deepcopy(owned[:16]),baseline,art,args.out)
    contact_pages('current',copy.deepcopy(owned),current,art,args.out/'preview')
    compact_context_pages(owned,art,args.out/'source-context');previews(entities,current,art,args.out/'preview/assemblies')
    print('16 changed;3 additions;',len(entities),'whole objects with exact native baseline fallback')


if __name__=='__main__':main()
