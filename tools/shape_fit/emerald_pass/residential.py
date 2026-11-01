"""Individually source-audited Brendan/May furnishings and complete assemblies."""
from __future__ import annotations
import argparse
import base64
import copy
import hashlib
import json

import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import Model, decode, encode
from tools.shape_fit.emerald_pass.coastal import ROOT, BASELINE, BUNDLE, INVENTORY
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.inventory import contact_pages, compact_context_pages
from tools.shape_fit.emerald_pass.review import read, entry_hash
from tools.shape_fit.emerald_pass.environment import steps
from tools.shape_fit.emerald_pass.ship_beams import previews

OUT=ROOT/'local/model-gallery/emerald-pass-study/residential'
GROUP='building__brendans_mays_house'
EXTRA=('g3q/3080-3081-3082-3083','g3q/3084-3085-2913-2914')
RETAIN={1,2,3,4,5,7,8,52,53}
JAMBS={1:'Upper left stair jamb against the west room wall',2:'Full-width upper stair header',3:'Upper right stair jamb in a continuous wall',
       4:'Upper left stair jamb in a continuous wall',5:'Lower left recessed stair post against the west wall',7:'Lower right recessed stair post',
       8:'Lower left recessed stair post in a continuous wall',52:'Upper right stair jamb at the east room boundary',53:'Lower right recessed stair post at the east boundary'}
TABLES=(((11,12),(14,15)),((72,73),(75,76)))
SOFAS=(((28,29),(32,33)),((28,29),(70,71)))
CASES=(((21,22),(26,27)),((30,31),(34,35)))
BEDS=(((54,55,56),(58,59,60),(65,66,67)),((57,55,56),(62,63,64),(69,66,67)))
WALK={6,9,32,33,46,47,48,50,51,54,55,56,57,58,59,60,62,63,64,65,67,69,70,71,78,79}
REMOVE_ON={41:51,42:47}
ADD_ON={16:17,61:68,36:39,37:43,38:44}


def rows(art=None):
    result=copy.deepcopy(read(INVENTORY/GROUP/'entries.json'))
    art=art or EmeraldArt(BUNDLE)
    for key in EXTRA:
        result.append({'key':key,'group':GROUP,'baseline_absent':True,'building_source_membership':[],
                       'occurrences':art.locations[key],'maps':sorted({p['map'] for p in art.locations[key]})})
    for i,r in enumerate(result,1):r['source_ordinal']=i
    assert len(result)==79 and len({r['key'] for r in result})==79
    return result


def colors(sprite,targets):return np.asarray([material(sprite,t) for t in targets],np.uint8)


def slice_model(m,layout,n,sprite,targets):
    z,x=next((z,x) for z,line in enumerate(layout) for x,v in enumerate(line) if v==n)
    s=(slice(None),slice(z*16,z*16+16),slice(x*16,x*16+16))
    return m.occ[s],colors(sprite,targets)[m.paint[s]]


def dining(sprite,n):
    layout=next(a for a in TABLES if any(n in row for row in a));m=Model(width=32,depth=32)
    yy,zz,xx=np.indices(m.occ.shape)
    outline=(xx>=2)&(xx<30)&(zz>=2)&(zz<29)&((xx-16)**2+(zz-15)**2<18**2)
    m.put(outline&(yy>=7)&(yy<9),0);m.put(outline&(yy==9),1)
    for x in (5,25):
        for z in (5,24):m.box((x,x+2),(0,7),(z,z+2),2)
    tint=(247,200,240) if n<70 else (250,245,140)
    return slice_model(m,layout,n,sprite,[(80,80,95),tint,(82,82,107)])


def chair(sprite,east=False,stool=False):
    m=Model();frame=material(sprite,(75,80,105));green=material(sprite,(160,255,90) if not stool else (175,140,220))
    m.box((3,13),(5,7),(3,13),green)
    for x in (3,11):
        for z in (3,11):m.box((x,x+2),(0,5),(z,z+2),frame)
    if not stool:m.box((2,4),(7,13),(3,13),frame).box((3,4),(8,12),(4,12),green)
    if east:return m.occ[:,:,::-1].copy(),m.paint[:,:,::-1].copy()
    return m.occ,m.paint


def sofa(sprite,n):
    layout=next(a for a in SOFAS if any(n in row for row in a));m=Model(width=32,depth=32)
    m.box((2,30),(4,6),(3,29),1)
    m.box((4,28),(6,8),(3,25),0)
    m.box((2,30),(6,13),(25,29),1).box((4,28),(8,13),(25,27),0)
    for x in (2,28):m.box((x,x+2),(6,10),(4,25),1)
    for x in (3,26):
        for z in (5,25):m.box((x,x+3),(0,4),(z,z+3),1)
    z,x=next((z,x) for z,line in enumerate(layout) for x,v in enumerate(line) if v==n)
    s=(slice(None),slice(z*16,z*16+16),slice(x*16,x*16+16))
    native=np.asarray([material(sprite,(250,250,245),family='neutral'),material(sprite,(140,140,140),family='neutral')],np.uint8)
    return m.occ[s],native[m.paint[s]]


def china_case(sprite,n):
    layout=next(a for a in CASES if any(n in row for row in a));m=Model(width=32,depth=32)
    # Each source variant is offset differently within its two-cell width.
    left=1 if n in (21,22,26,27) else 8;right=24 if left==1 else 31
    m.box((left,right),(0,15),(10,29),0)
    m.box((left+2,right-2),(6,14),(28,30),1)
    m.box((left,right),(14,16),(9,30),2)
    m.box(((left+right)//2,(left+right)//2+1),(6,14),(29,30),0)
    for y in (2,5):m.box((left+2,right-2),(y,y+1),(29,30),2)
    o,p=slice_model(m,layout,n,sprite,[(145,120,65),(190,185,235),(245,245,195)])
    if n in (26,27,34,35):
        for y in range(6,14):p[y,12:14,:]=np.arange(16,dtype=np.uint8)+(13-y)*16
    return o,p


def bed(sprite,n):
    layout=next(a for a in BEDS if any(n in row for row in a));m=Model(width=48,depth=48)
    m.box((14,34),(4,7),(15,44),0)
    m.box((15,33),(7,10),(18,42),1)
    m.box((15,33),(0,14),(14,17),0)
    m.box((14,34),(6,8),(43,45),0)
    m.box((18,30),(10,12),(19,24),1)
    m.box((15,33),(10,11),(25,28),2)
    for x in (14,32):
        for z in (16,42):m.box((x,x+2),(0,5),(z,z+2),0)
    purple=n in (57,62,63,64,69)
    return slice_model(m,layout,n,sprite,[(105,100,130),(250,250,255),(180,145,220) if purple else (245,180,75)])


def fridge(sprite,n):
    m=Model();body=material(sprite,(205,205,180));white=material(sprite,(255,255,245));edge=material(sprite,(85,85,105))
    m.box((2,14),(0,16),(4,14),body)
    m.box((3,13),(1,15),(13,15),white if n==18 else body)
    m.box((3,5),(5,10),(14,16),edge)
    if n==18:m.box((2,14),(15,16),(4,15),white)
    else:m.box((2,14),(0,1),(4,15),edge)
    return m.occ,m.paint


def wall_decoration(sprite,n):
    """Two source rows become one thin, native-painted 32-high wall panel."""
    m=Model();m.box((0,16),(0,16),(11,15))
    yy,zz,xx=np.indices(m.occ.shape);upper=n in (16,61,36);global_y=yy+(16 if upper else 0)
    if n in (36,39):face=(xx>=1)&(xx<15)&(global_y>=12)&(global_y<20)
    else:face=((xx+.5-8)/5.5)**2+((global_y+.5-17.5)/6.5)**2<=1
    m.put(face&(zz==15))
    m.project(side=material(sprite,(210,210,180),(0,4,2,12)))
    # One source address colors every voxel face. Restore the complete native
    # face after side-edge selection so one-voxel relief edges cannot punch
    # wall-colored holes through the clock/picture artwork.
    native=((15-yy)*16+xx).astype(np.uint8)
    m.paint[m.occ&(zz>=14)]=native[m.occ&(zz>=14)]
    return m.occ,m.paint


def kitchen(sprite,n):
    layout=((19,20),(24,25));m=Model(width=32,depth=32)
    m.box((1,31),(0,7),(12,30),0).box((1,31),(7,9),(11,31),1)
    # Open sink basin in the left countertop and a round plate to the right.
    m.box((4,14),(7,9),(18,27),3).box((5,13),(8,9),(19,26),2)
    m.box((7,9),(9,14),(14,16),2).box((7,12),(13,15),(14,16),2)
    m.cylinder((24,23),4,(9,10),1)
    for x in (4,20):m.box((x,x+7),(2,3),(30,31),2)
    if n in (19,20):
        # The upper source row includes the actual wall/window behind the sink.
        m.box((0,32),(0,16),(0,2),1)
    o,p=slice_model(m,layout,n,sprite,[(160,130,55),(225,225,195),(95,95,110),(60,65,80)])
    if n==20:
        # Native blue window band on the back panel only.
        for y in range(8,14):p[y,1,:]=np.arange(16,dtype=np.uint8)+(13-y)*16
    return o,p


def desk(sprite,n):
    m=Model();wood=material(sprite,(181,156,132));cream=material(sprite,(240,240,195));gray=material(sprite,(82,82,107))
    if n in (37,38):
        m.box((2,14),(0,2),(4,12),cream).box((3,13),(2,3),(5,11),cream)
    elif n in (43,44):
        m.box((0,16),(6,8),(4,16),wood)
        for x in (2,12):
            for z in (6,13):m.box((x,x+2),(0,6),(z,z+2),wood)
        m.box((2,14),(8,16),(4,12),gray)
        m.box((3,13),(10,15),(11,13),material(sprite,(145,150,145)))
        m.box((3,13),(8,9),(12,15),cream)
        for x in (4,7,10):m.box((x,x+1),(9,10),(13,14),gray)
    elif n in (42,45):
        m.box((0,16),(6,8),(4,16),wood)
        for x in (2,12):
            for z in (6,13):m.box((x,x+2),(0,6),(z,z+2),wood)
        for x in (3,7,11):m.box((x,x+3),(8,14),(7,13),cream)
        for x in (6,10):m.box((x,x+1),(8,14),(7,13),gray)
    else:
        if n in (48,78):
            seat=material(sprite,(245,175,65) if n==78 else (175,140,220))
            m.box((4,12),(4,6),(7,14),seat).box((4,12),(6,11),(12,14),seat)
            for x in (4,10):
                for z in (7,12):m.box((x,x+2),(0,4),(z,z+2),gray)
    return m.occ,m.paint


def console(sprite,n):
    m=Model();accent=material(sprite,(245,180,75),family='orange') if n in (41,51) else material(sprite,(105,100,180));dark=material(sprite,(70,70,100))
    if n in (41,49):
        m.box((4,12),(0,6),(10,16),accent).box((6,10),(6,7),(11,15),dark)
        m.box((5,11),(2,4),(15,16),dark)
    else:
        m.box((7,9),(0,1),(0,8),dark)
        m.ellipsoid((8,2,9),(5,2,3),accent)
        m.box((4,7),(3,4),(8,10),dark).box((10,12),(3,4),(8,10),dark)
    return m.occ,m.paint


def floor(sprite):
    m=Model();m.box((0,16),(0,1),(0,16))
    yy,zz,xx=np.indices(m.occ.shape);m.paint[:]=((8+zz%8)*16+xx).astype(np.uint8)
    return m.occ,m.paint


def generate(baseline,art,owned):
    key_at={r['source_ordinal']:r['key'] for r in owned};updates={};ledger=[];amendments=[]
    for r in owned:
        n=r['source_ordinal'];key=r['key'];old=baseline['cells'].get(key,{'class':'voxels'});sprite=art.cell(key,'BAKED')
        if n in RETAIN:
            label=JAMBS[n];family='house architectural background'
            reason=label+'. Source/front/east/top inspection and the complete stair opening show its existing filled wall mass, source-painted trim and recessed post join the neighboring jamb/header correctly. Preserve this architectural part while the central stair/threshold receives its separately evidenced display correction.'
            decision='retained'
        else:
            if n in (6,9):
                if n==6:m=steps(sprite);o,p=m.occ,m.paint
                else:o,p=decode(old)
                label,family,reason='House staircase or dark stair threshold','house stairs','Source 1F stair treads and the 2F dark threshold are walk-through warp114 cells. The rise is authored explicitly and walk:true makes the fitted display visible while leaving warp/navigation unchanged.'
            elif any(n in row for a in TABLES for row in a):
                o,p=dining(sprite,n);label,family,reason='Dining table quarter','house dining table','Each exact 2x2 pink/yellow tabletop is one level rounded surface with four grounded legs and a thin dark rim; native floor/rug pixels are excluded from furniture volume.'
            elif n in (10,13,74,77):
                o,p=chair(sprite,n in (13,77));label,family,reason='Inward-facing green dining chair','house dining chair','Four native green chairs flank the table. Give each a raised cushion, correctly oriented side back and four grounded feet; preserve the original seat interaction.'
            elif any(n in row for a in SOFAS for row in a):
                o,p=sofa(sprite,n);label,family,reason='North-facing living-room sofa section','house sofa','Visual inference from the complete white cushion/back field, gray rear frame, continuing rug and adjacent distinct gray/blue television: this is a two-seat sofa. Author one low seat, rear back, arms and four feet across the exact 2x2 source; omit blue/pink rug from furniture.'
            elif any(n in row for a in CASES for row in a):
                o,p=china_case(sprite,n);label,family,reason='Glazed kitchen cupboard section','house glazed cupboard','The source 2x2 wooden cupboard has pale cornice, two purple glazed doors and drawers below. Build one aligned cabinet within its source-specific horizontal offset; preserve its glass and wood using native colors.'
            elif any(n in row for a in BEDS for row in a):
                o,p=bed(sprite,n);label,family,reason='Single-bed frame or bedding section','house bed','The source 3x3 footprint includes narrow side rails, white mattress, colored top blanket, pillow, headboard and foot. Construct one complete bed then slice by exact source cells so the narrow floor-overlap cells join and all four feet touch the floor.'
            elif n in (18,23):
                o,p=fridge(sprite,n);label,family,reason='Refrigerator upper/lower cabinet','house refrigerator','Preserve the proven original two-row stack and align both full-height cabinet sections on one footprint with pale door, dark handle and base/cap trim; omit projected wall background.'
            elif n in (16,17,61,68,36,39):
                o,p=wall_decoration(sprite,n);label,family,reason='Wall clock/picture panel section','house wall decoration','The native clock/picture spans exactly two SOLID source rows. Add the exact upper-to-lower link and build one shallow32-high wall panel with a raised face; split at16 preserves every source pixel in its own key. The four-block carrier fits the bedroom minimum/measured headroom. This replaces the disconnected horizontal half-disc/half-picture extrusions found during whole-object review.'
            elif n in (19,20,24,25):
                o,p=kitchen(sprite,n);label,family,reason='Kitchen sink-counter section','house sink counter','Exact 2x2 sink/plate counter gets a coherent nine-high wooden cabinet, recessed dark basin, raised faucet and plate. The northern source row includes a narrow backsplash/window wall; source floor is not extruded.'
            elif n in (37,38,42,43,44,45,48,78):
                o,p=desk(sprite,n);label,family,reason='Bedroom computer/book desk or chair section','house computer desk','Source desk has an eight-high continuous top, rear legs, books or monitor and keyboard, plus the front chair/legs in the next floor row. Keep the desk footprint horizontal. The small monitor cap advances one source row onto the exact monitor body, at its matching upper interface.'
            elif n in (47,79):
                o,p=floor(sprite);label,family,reason='Wood floor beneath projected desk legs','house computer desk','The north cell now contains the complete grounded book desk. This WALK source shows its projected thin legs above floorboards, so use the native unobstructed lower floor rows as a one-high floor surface instead of creating another desk section or floating legs.'
            elif n in (41,49,50,51):
                o,p=console(sprite,n);label,family,reason='Compact console or wired controller','house console','Source shows a small orange/purple cube at the wall and a wired controller projected south onto the floor. Build the cube seven-high and controller four-high with connecting cord; these are adjacent floor components, not a controller supporting a two-cell tall console.'
            elif n==40:
                m=Model();m.box((0,16),(0,16),(0,16));yy,zz,xx=np.indices(m.occ.shape);m.paint[:]=((15-yy)*12//16*16+xx).astype(np.uint8);o,p=m.occ,m.paint
                label,family,reason='Wall behind the complete television','house television background','The adjacent shared g3q/1-2-3-4 already supplies the complete authored CRT television. Keep this solid wall cell and sample its own wall/trim rows, excluding the tiny projected gray TV cap that would duplicate it.'
            elif n==46:
                o,p=floor(sprite);label,family,reason='Wood floor beneath projected television foot','house television background','The neighboring preserved shared CRT already has a grounded cabinet and feet. This WALK source is its projected blue foot over wooden floor; replace the separate footprint extrusion with a one-high native wooden surface.'
            else:raise ValueError((n,key))
            new=encode(o,p,old,source='hand-emerald-residential',trim=True)
            changed_meta={}
            if n in REMOVE_ON:
                assert old.get('on')==key_at[REMOVE_ON[n]];changed_meta['on']={'old':new.pop('on'),'new':None}
            if n in ADD_ON:
                assert 'on' not in old;new['on']=key_at[ADD_ON[n]];changed_meta['on']={'old':None,'new':new['on']}
            if n in WALK:
                assert not old.get('seat');new['walk']=True;changed_meta['walk']={'old':old.get('walk'),'new':True}
            if changed_meta:
                places=[]
                for place in art.locations[key]:
                    source=art.maps[place['map']];raw=base64.b64decode(source['cells']);x,y=place['x'],place['y'];grid=art.map_cells(source)
                    places.append(dict(place,collision=raw[(y*source['w']+x)*4+2],below_key=grid[y+1][x] if y+1<len(grid) else None))
                amendments.append({'key':key,'source_ordinal':n,'baseline_absent':key not in baseline['cells'],
                                   'baseline_entry_sha256':entry_hash(old) if key in baseline['cells'] else None,
                                   'proposed_entry_sha256':entry_hash(new),'source_pixel_sha256':hashlib.sha256(sprite.tobytes()).hexdigest(),
                                   'changes':changed_meta,'occurrences':places})
            updates[key]=new;decision='changed'
        ledger.append({'key':key,'kind':'cell','label':label,'family':family,'decision':decision,'reason':reason,'baseline_entry_sha256':entry_hash(old),
                       'occurrences':art.locations[key],'evidence':[str(OUT/'source'/f'page-{(n-1)//32+1:03d}.png'),str(OUT/'source-context'/f'page-{(n-1)//16+1:03d}.png')]})
        if key not in baseline['cells']:
            ledger[-1]['baseline_entry_sha256']=None;ledger[-1]['baseline_absent']=True;ledger[-1]['decision']='added'
            ledger[-1]['proposed_entry_sha256']=entry_hash(new)
            ledger[-1]['source_pixel_sha256']=hashlib.sha256(sprite.tobytes()).hexdigest()
            ledger[-1]['source_pixel_mode']=sprite.mode
    return updates,ledger,amendments


def assemblies(art,owned,baseline,current):
    by_key={r['key']:r['source_ordinal'] for r in owned};found={}
    def add(map_id,x,y,w,h,label):
        source=art.maps[map_id];grid=art.map_cells(source);raw=base64.b64decode(source['cells'])
        reference=[line[x:x+w] for line in grid[y:y+h]]
        layout=[[k if k in by_key or k=='g3q/1-2-3-4' else None for k in line] for line in reference]
        def positions(table):
            at={(xx,zz):k for zz,line in enumerate(layout) for xx,k in enumerate(line) if k};present={}
            for (xx,zz),key in at.items():
                if key not in table['cells']:continue
                collision=raw[((y+zz)*source['w']+x+xx)*4+2];entry=table['cells'][key]
                if collision==7 or entry.get('seat') or entry.get('walk') or entry.get('on'):present[(xx,zz)]=key
            links={p:(p[0],p[1]+1) for p,k in present.items() if table['cells'][k].get('on') is not None
                   and table['cells'][k].get('on')==present.get((p[0],p[1]+1)) and raw[((y+p[1]+1)*source['w']+x+p[0])*4+2]==7}
            placements=[]
            for p in sorted(set(present)-set(links.values())):
                chain=[p]
                while chain[-1] in links:chain.append(links[chain[-1]])
                end=chain[-1]
                if len(chain)==1 and raw[((y+p[1])*source['w']+x+p[0])*4+2]!=7 and not(table['cells'][present[p]].get('seat') or table['cells'][present[p]].get('walk')):continue
                for level,point in enumerate(reversed(chain)):placements.append({'source':list(point),'offset':[end[0],level,end[1]]})
            emitted={tuple(p['source']) for p in placements}
            shown=[[k if (xx,zz) in emitted else None for xx,k in enumerate(line)] for zz,line in enumerate(layout)]
            return placements,shown
        now,shown=positions(current);before,old=positions(baseline)
        assert shown==layout,(label,shown,layout)
        stamp=json.dumps([layout,now,before],separators=(',',':'));identifier='emerald_residential_'+hashlib.sha256(stamp.encode()).hexdigest()[:12]
        item=found.setdefault(identifier,{'id':identifier,'kind':'object (model as one)','note':label,'layout':layout,'reference_layout':reference,
                                         'placements':now,'baseline_layout':old,'baseline_placements':before,'occurrences':[]})
        item['occurrences'].append({'map':map_id,'x':x,'y':y})
    b1='EM_LITTLEROOT_TOWN_BRENDANS_HOUSE_1F';m1='EM_LITTLEROOT_TOWN_MAYS_HOUSE_1F';b2='EM_LITTLEROOT_TOWN_BRENDANS_HOUSE_2F';m2='EM_LITTLEROOT_TOWN_MAYS_HOUSE_2F'
    for m,x in ((b1,2),(m1,5)):add(m,x,6,4,2,'Dining table and all four inward-facing chairs')
    for m,x in ((b1,2),(m1,7)):add(m,x,4,2,2,'Complete north-facing two-seat sofa')
    for m,x in ((b1,0),(m1,6)):add(m,x,1,5,2,'Kitchen refrigerator, sink counter and glazed cupboard')
    for m,x in ((b2,0),(m2,6)):add(m,x,3,3,3,'Complete single bed with side rails and head/foot boards')
    add(b2,0,0,2,3,'Bedroom computer, book desk and orange chair')
    add(m2,7,0,2,3,'Bedroom book desk, computer and purple chair')
    add(b2,3,1,1,2,'Purple compact console and wired controller')
    add(m2,5,1,1,2,'Orange compact console and wired controller')
    for m in (b2,m2):add(m,4,0,1,3,'Preserved complete CRT with its wall and floor context')
    add(b2,5,0,1,2,'Blue wall clock and its architectural source context')
    add(m2,3,0,1,2,'Pink wall clock and its architectural source context')
    add(b2,2,0,1,2,'Framed wall picture and its architectural source context')
    add(b1,7,1,3,2,'First-floor stair opening and three source jambs')
    add(m1,1,1,3,2,'Mirrored first-floor stair opening and three source jambs')
    add(b2,6,0,3,2,'Upper-floor stair threshold and three source jambs')
    add(m2,0,0,3,2,'Mirrored upper-floor stair threshold and three source jambs')
    return [{k:v for k,v in g.items() if k!='occurrences'} for g in found.values()],list(found.values())


def stack_evidence(art,owned):
    key_at={r['source_ordinal']:r['key'] for r in owned};proof=[]
    for upper,lower in ADD_ON.items():
        for p in art.locations[key_at[upper]]:
            m=art.maps[p['map']];raw=base64.b64decode(m['cells']);grid=art.map_cells(m);x,y=p['x'],p['y']
            assert grid[y+1][x]==key_at[lower]
            assert all(raw[((y+z)*m['w']+x)*4+2]==7 for z in (0,1))
            proof.append({'upper_ordinal':upper,'lower_ordinal':lower,'map':p['map'],'x':x,'y':y,
                          'source_keys':[key_at[upper],key_at[lower]],'source_collisions':[7,7],
                          'carrier_height_blocks':4,'actual_geometry_height_voxels':19 if upper in (37,38) else 32,
                          'headroom_blocks_at_least':4,'headroom_rule':'Headroom.MIN=4 for every enclosed room. Both pair carriers are SOLID and source positions advance one row. No extra layer or source cycle is used.'})
    return proof


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=type(OUT),default=OUT);args=ap.parse_args()
    art=EmeraldArt(BUNDLE);baseline=read(BASELINE);owned=rows(art);updates,ledger,amendments=generate(baseline,art,owned)
    current=read(ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json');current['cells'].update(updates)
    entities,places=assemblies(art,owned,baseline,current)
    for name,value in [('updates.json',{'cells':{k:v for k,v in updates.items() if k not in EXTRA},'buildings':{}}),
                       ('additions.json',{'cells':{k:v for k,v in updates.items() if k in EXTRA},'buildings':{}}),
                       ('ledger.json',[r for r in ledger if not r.get('baseline_absent')]),('added-ledger.json',[r for r in ledger if r.get('baseline_absent')]),('metadata-evidence.json',amendments),('entities.json',entities),
                       ('stack-evidence.json',stack_evidence(art,owned)),('assembly-placements.json',places),('ownership.json',{'owner':'emerald_scenery','keys':[r['key'] for r in owned]})]:write_json(args.out/name,value)
    write_json(args.out/'semantic-evidence.json',{'sofa':'Visual inference from white cushion/back field, gray rear frame and adjacent distinct television; no source-specific name is asserted.',
        'television_script_source':'https://raw.githubusercontent.com/pret/pokeemerald/master/data/maps/LittlerootTown_BrendansHouse_1F/scripts.inc',
        'television_script_observation':'Parent inspected MoveMomToTV: Mom stands at(4,5) facing up toward the compact gray/blue object. No source code was copied.',
        'wall_decoration_decision':'Initial local assembled inspection rejected the disconnected baseline half-disc and half-picture extrusion. Exact SOLID/SOLID links and32-high shallow panels preserve native source-pixel placement without new pattern templates.'})
    contact_pages('source',copy.deepcopy(owned[:77]),baseline,art,args.out);contact_pages('current',copy.deepcopy(owned),current,art,args.out/'preview')
    compact_context_pages(owned,art,args.out/'source-context');previews(entities,current,art,args.out/'preview/assemblies')
    print(len(updates),'changed,',len(ledger),'decisions,',len(entities),'whole objects')


if __name__=='__main__':main()
