"""Source-audited Pokemon Center furnishings, native paint and exact assemblies."""
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
from tools.shape_fit.emerald_pass.residential import slice_model
from tools.shape_fit.emerald_pass.ship_beams import previews

OUT=ROOT/'local/model-gallery/emerald-pass-study/center'
GROUP='building__pokemon_center'
PC='g3q/9-10-11-12'
COMPAT_CAPS=('g3q/815-815-916-917','g3q/7313-7313-7384-7385')
PENDING=set()
RETAIN={43,61,63}
COUNTERS={1,13,29,30,34,35,38,39,66,67,68,72}
COUNTER_KIND={1,66,67,68,72}
FLOORS={11,12,16,21,22,64,65,69,70}
WALK=FLOORS|{47,48,50,51,52,53,55,57}
REMOVE_ON={7:11,74:16,37:40,28:33,71:73}
ADD_ON={5:9,6:10,32:37}
GLASS=((23,24),(26,27))
HEAL=((14,15),(18,19))
ESCALATORS=(((47,48),(49,50),(51,52)),((53,48),(54,55),(57,52)))


def rows(art=None):
    art=art or EmeraldArt(BUNDLE)
    result=copy.deepcopy(read(INVENTORY/GROUP/'entries.json'))
    inventory=read(INVENTORY/'inventory.json')['entries']
    result.extend(copy.deepcopy(next(r for r in inventory if r['key']==key)) for key in (PC,*COMPAT_CAPS))
    for n,r in enumerate(result,1):r['source_ordinal']=n
    assert len(result)==76 and len({r['key'] for r in result})==76
    return result


def source_occurrences(art,key):
    result=[]
    for p in art.locations[key]:
        m=art.maps[p['map']];raw=base64.b64decode(m['cells']);g=art.map_cells(m);x,y=p['x'],p['y']
        result.append(dict(p,collision=raw[(y*m['w']+x)*4+2],below_key=g[y+1][x] if y+1<len(g) else None,
                           map_cells_sha256=hashlib.sha256(raw).hexdigest()))
    return result


def floor(sprite,n):
    m=Model();m.box((0,16),(0,1),(0,16));yy,zz,xx=np.indices(m.occ.shape)
    # Projected furniture feet and cast shadows belong to the original 2D
    # view. Resample only the unobstructed native floor band in this cell.
    band=(12,16) if n in (11,12,16,21,22) else (4,8)
    m.paint[:]=((band[0]+zz%(band[1]-band[0]))*16+xx).astype(np.uint8)
    if n==69:
        # The diagonal wall corner is architecture, not the adjacent floor.
        m.paint[:]=material(sprite,(250,245,195),(10,10,16,16))
        m.box((0,2),(0,16),(0,16),material(sprite,(225,120,40),family='orange'))
    return m.occ,m.paint


def counter(sprite,n):
    m=Model();body=material(sprite,(140,130,170));edge=material(sprite,(75,75,105));top=material(sprite,(255,255,205))
    # All pieces share the one-block top plane; COUNTER sources require the
    # planner's separate, tested display path without changing talk-through.
    m.box((0,16),(0,1),(1,15),edge).box((0,16),(1,6),(1,15),body)
    m.box((0,16),(6,7),(0,16),material(sprite,(220,155,100)))
    m.box((0,16),(7,8),(0,16),top)
    if n in (29,30,34,35,38,39):
        # Rounded outside end at source-specific left/right corners.
        left=n in (29,34,38);a=(0,2) if left else (14,16)
        m.box(a,(0,8),(0,2),cut=True)
    if n==1:
        m.box((3,13),(8,9),(6,11),material(sprite,(255,255,255),family='neutral'))
    if n in (34,35):
        # One spherical red/white display ball on the counter, not the old
        # duplicated source-ball image painted across a cabinet face.
        m.ellipsoid((8,11,8),(3.5,3.5,3.5),material(sprite,(255,90,50),family='orange'))
        yy,zz,xx=np.indices(m.occ.shape);ball=((xx+.5-8)/3.5)**2+((yy+.5-11)/3.5)**2+((zz+.5-8)/3.5)**2<=1
        m.put(ball&(yy<10),material(sprite,(250,250,245),family='neutral'))
        m.put(ball&(yy==10),edge);m.box((7,9),(10,12),(11,12),top)
    return m.occ,m.paint


def glass_table(sprite,n):
    m=Model(width=32,depth=32);yy,zz,xx=np.indices(m.occ.shape)
    disk=(xx+.5-16)**2+(zz+.5-16)**2<15**2
    m.put(disk&(yy>=7)&(yy<9),0);m.put(disk&(yy==9),1)
    m.put(disk&((xx+.5-16)**2+(zz+.5-16)**2>13**2)&(yy==9),2)
    m.put(disk&(yy==9)&((abs(xx-zz-11)<=1)|(abs(xx-zz+11)<=1)),2)
    for x,z in ((7,7),(23,7),(7,23),(23,23)):m.box((x,x+2),(0,7),(z,z+2),3)
    return slice_model(m,GLASS,n,sprite,[(60,115,110),(140,200,185),(235,250,225),(82,82,107)])


def stool(sprite,n):
    m=Model();gray=material(sprite,(85,85,110));seat=material(sprite,(245,155,120) if n==17 else (255,225,60))
    m.cylinder((8,8),4.5,(0,2),gray).cylinder((8,8),2,(2,6),gray)
    m.cylinder((8,8),6,(6,8),gray).ellipsoid((8,8,8),(6,2,6),seat)
    m.box((5,7),(9,10),(5,7),material(sprite,(255,255,205)))
    return m.occ,m.paint


def plant(sprite,n):
    m=Model();stem=material(sprite,(95,130,45),family='green')
    upper=n in (2,3,42,58,59);short=n in (2,58,59)
    if upper:
        tones=[material(sprite,c,family='green') for c in ((110,155,50),(155,200,70),(195,225,110))]
        height=6 if short else 14
        m.box((7,9),(0,height-1),(7,9),stem)
        lobes=((5,8,2,4,3,0),(11,8,3,4,3,1),(8,4,3,3,3,1),(8,12,2,3,3,0),(8,8,3,3,3,2)) if short else (
            (4,8,4,4,3,0),(12,8,5,4,3,1),(8,4,6,3,4,1),(8,12,5,3,4,0),
            (5,6,9,3,3,2),(11,10,9,3,3,1),(8,8,11,3,3,2))
        for x,z,cy,rx,rz,tone in lobes:m.ellipsoid((x,cy,z),(rx,3,rz),tones[tone])
    else:
        neutral=material(sprite,(160,155,180),family='neutral');rim=material(sprite,(220,220,235),family='neutral')
        m.cylinder((8,8),3.7,(0,2),neutral).cylinder((8,8),4.8,(2,8),neutral)
        m.cylinder((8,8),5.7,(8,10),rim).cylinder((8,8),4.5,(9,10),material(sprite,(75,75,100)))
        m.box((7,9),(10,16),(7,9),stem)
        if n in (7,60,62):
            for x,z in ((5,8),(11,8),(8,4),(8,12)):
                m.ellipsoid((x,14,z),(3.5,3,3),material(sprite,(165,205,85),family='green'))
    return m.occ,m.paint


def panel(sprite,n):
    m=Model();m.box((0,16),(0,16),(2,5))
    if n in (31,32,36,37):
        xoffset=0 if n in (31,36) else 16;offset=16 if n in (31,32) else 0
        yy,zz,xx=np.indices(m.occ.shape);gx=xx+xoffset;gy=yy+offset
        tv=(gx>=1)&(gx<23)&(gy>=13)&(gy<25)&(zz>=5)&(zz<8)
        m.put(tv)
    else:m.box((1,15),(0,16),(5,6))
    m.project()
    if n==37:
        # The same native cell contains a TV corner and the medical console's
        # cap. Keep its wall pixels with the TV and put its cap at the actual
        # floor console north edge, independently of the TV vertical pair.
        wall=material(sprite,(250,180,110),(7,0,16,8))
        yy,zz,xx=np.indices(m.occ.shape)
        m.paint[m.occ&(yy<7)&(xx>=5)]=wall
        white=material(sprite,(255,255,255));m.box((5,15),(12,16),(12,16),white)
        m.box((8,12),(15,16),(13,15),material(sprite,(250,180,110),(8,10,13,15)))
    return m.occ,m.paint


def medical_console(sprite):
    m=Model();gray=material(sprite,(80,80,105));white=material(sprite,(250,250,240));light=material(sprite,(160,160,175))
    m.box((5,15),(0,2),(0,12),gray).box((6,14),(2,13),(0,11),light)
    m.box((5,15),(12,16),(0,5),white).box((6,14),(10,13),(5,12),gray)
    for x in (6,13):m.box((x,x+1),(3,11),(10,12),white)
    return m.occ,m.paint


def healing(sprite,n):
    m=Model(width=32,depth=32)
    m.box((2,30),(0,3),(3,29),0).box((4,28),(3,7),(5,28),1)
    # Six recovery positions sit in the recessed tray between tall side
    # frames; the rear headboard and feet connect all four source quarters.
    m.box((6,26),(7,8),(8,26),2)
    m.box((2,6),(3,14),(4,28),1).box((26,30),(3,14),(4,28),1)
    m.box((3,29),(10,16),(3,7),1)
    m.box((7,25),(12,15),(6,8),0)
    for x in (10,16,22):
        for z in (13,21):m.cylinder((x,z),2.2,(8,9),1)
    for x in (3,27):m.box((x,x+2),(0,15),(7,10),0)
    return slice_model(m,HEAL,n,sprite,[(75,75,100),(220,220,220),(60,65,90)])


def bookcase(sprite,n):
    m=Model();gray=material(sprite,(90,85,110));white=material(sprite,(250,250,250))
    m.box((0,16),(0,2),(3,15),gray).box((0,16),(14,16),(2,16),white)
    m.box((0,16),(2,14),(3,5),gray)
    for x in (0,15):m.box((x,x+1),(2,14),(4,16),gray)
    for y in (2,8):m.box((0,16),(y,y+1),(4,16),gray)
    for x in (2,5,8,11):
        for y in (3,9):
            color=material(sprite,((170,185,225),(175,205,140),(245,240,185),(150,150,165))[(x//3)%4])
            m.box((x,x+2),(y,y+5),(6,14),color)
    return m.occ,m.paint


def escalator(sprite,n):
    # The shared east ends48/52 are identical in both source variants.
    layout=ESCALATORS[1] if n in (53,54,55,57) else ESCALATORS[0]
    m=Model(width=32,depth=48);ascending=layout==ESCALATORS[1]
    for x in range(32):
        # Both maps share identical flat east landing/rail source cells.
        # Four west treads climb/descend into that common eight-high landing.
        h=(2+2*(x//4) if ascending else 14-2*(x//4)) if x<16 else 8
        m.box((x,x+1),(0,h),(16,32),0)
        if x%4==0:m.box((x,x+1),(h-1,h),(16,32),1)
        # Native balustrades stay in their source rows and never exceed the
        # single decorative cell layer used by WALK/warp rendering.
        rail=h+2 if x<16 else 10
        for z in (13,33):
            m.box((x,x+1),(0,rail-1),(z,z+2),2)
            m.box((x,x+1),(rail-1,rail),(z,z+2),3)
    return slice_model(m,layout,n,sprite,[(90,90,115),(170,170,190),(150,195,180),(250,95,50)])


def divider(sprite,n):
    m=Model();white=material(sprite,(255,255,255));gray=material(sprite,(90,90,115));orange=material(sprite,(250,175,110))
    if n!=73:
        end=12 if n in (56,71) else 16
        m.box((3,13),(0,14),(0,end),orange).box((2,14),(0,2),(0,end),gray)
        m.box((3,13),(14,16),(0,end),white)
        if n==25:
            m.box((0,16),(0,16),(0,2),orange)
            m.box((0,16),(14,16),(0,2),white)
        if n==56:
            m.box((1,15),(13,16),(12,16),gray).box((2,14),(14,16),(12,16),white)
        if n==71:
            m.box((3,13),(9,13),(12,16),gray)
            for z in range(13,16):
                m.paint[12,z,3:13]=np.arange(3,13,dtype=np.uint8)+14*16
    else:
        m.box((4,12),(0,2),(0,11),gray).box((5,11),(2,9),(1,10),material(sprite,(150,150,165)))
        m.box((3,13),(9,13),(0,7),gray)
        for z in range(7):m.paint[12,z,3:13]=np.arange(3,13,dtype=np.uint8)+z*16
    return m.occ,m.paint


def pc_cap(sprite):
    m=Model();white=material(sprite,(245,240,245),(1,9,15,16),family='neutral')
    gray=material(sprite,(150,140,160),(1,9,15,16),family='neutral')
    m.box((1,15),(0,2),(0,12),gray).box((2,14),(2,3),(1,11),white)
    return m.occ,m.paint


def generate(baseline,art,owned):
    key_at={r['source_ordinal']:r['key'] for r in owned};updates={};ledger=[];amendments=[]
    for r in owned:
        n=r['source_ordinal'];key=r['key'];old=baseline['cells'][key];sprite=art.cell(key,'BAKED')
        if n in PENDING:continue
        if n in RETAIN:
            label={43:'Blue upstairs service doorway',61:'Lavaridge pool doorway',63:'Dark Pokemon League doorway'}[n]
            family='Center open doorway';decision='retained'
            reason='Source/context and front/east/top inspection show the existing thin jamb/header frame leaves the central warp passage open. Preserve its original walk flag, dark opening and modest source-painted boundary.'
        else:
            if n==74:
                o,p=decode(old);label='Shared public PC body';family='Center PC'
                reason='All51 source occurrences were inspected in18 distinct neighborhoods, including Contest and Frontier caps. Preserve the existing monitor, keypad and grounded pedestal geometry. Only remove its obsolete link to the Center WALK projected foot: baseline absent foot allowed the valid cap/body pair; the newly visible thin decor foot would otherwise invalidate that pair (EmeraldDecorFootStackTest).'
            elif n in (4,75,76):
                o,p=pc_cap(sprite);label={4:'Center PC upper cap',75:'Battle Factory PC upper cap',76:'Mossdeep Game Corner PC upper cap'}[n];family='Center PC'
                reason='Replace the old wall-background extrusion by the actual shallow gray/pale PC cap, matching the shared body14x12 top footprint. Preserve the existing true link to the shared PC body and every shared-body voxel. Factory and Mossdeep Game Corner variants were transferred after complete PC previews exposed their elevated orange/beige background blocks.'
            elif n in FLOORS:
                o,p=floor(sprite,n);label={11:'Native floor below projected pot foot',12:'Upstairs PC projected-foot floor',16:'First-floor PC projected-foot floor',21:'Left bookcase projected-foot floor',22:'Right bookcase projected-foot floor',64:'Counter rear floor left edge',65:'Counter rear floor right edge',69:'Room-corner floor and narrow wall',70:'Counter rear floor central edge'}[n];family='Center projected floor'
                reason='Exact map collision is WALK. This source contains projected pot/PC/bookcase feet or floor behind the service counter, not an additional vertical furniture layer. Use a one-voxel native floor surface and walk decor; do not duplicate the original cast shadow as a second solid object.'
            elif n in COUNTERS:
                o,p=counter(sprite,n);label={1:'White counter plate',13:'Straight cream service counter',29:'Counter north-left end',30:'Counter north-right end',34:'Left display Pokeball counter',35:'Right display Pokeball counter',38:'Counter southwest corner',39:'Counter southeast corner',66:'Upstairs counter left end',67:'Upstairs counter straight section',68:'Upstairs counter right end',72:'Upstairs central counter section'}[n];family='Center counters'
                reason='Explicit cream top, orange rim, violet front and dark plinth share one eight-voxel counter height. Source-specific outer corners round the perimeter; Pokeball sections contain one raised red/white ball. Five exact COUNTER-kind sources gain counter:true for the separately tested fitted display path, preserving their one-block collision and talk-through behavior; no walk reclassification is used.'
            elif n in (2,3,7,8,42,44,58,59,60,62):
                o,p=plant(sprite,n);label='Potted Center plant '+('crown' if n in (2,3,42,58,59) else 'base');family='Center plants'
                reason='One coherent native-green leafy crown joins the grounded gray pot and narrow stem. Short wall-tip variants use only their green pixels, omitting orange wall/window projection. Preserve true upper links; remove only7-to11 because11 is projected WALK floor, with exact runtime fixture proving the newly displayed foot must not join the vertical plant.'
            elif n in (5,6,9,10,31,32,36,37):
                o,p=panel(sprite,n);label={5:'Wall map upper left',6:'Wall map upper right',9:'Wall map lower left',10:'Wall map lower right',31:'Wall TV upper left',32:'Wall TV upper right',36:'Wall TV lower left',37:'Wall TV lower right and medical console cap'}[n];family='Center wall panels'
                reason='The two native wall rows form one shallow32-high panel. Exact upper/lower links align the left and right source halves. The mixed TV/medical-console cell37 retains the TV corner in the wall and places its own white/orange console cap at the south floor edge, joining the separately grounded console40; it does not stack the TV onto that console.'
            elif n==40:
                o,p=medical_console(sprite);label='Medical control console';family='Center medical console'
                reason='Ground the gray control cabinet on a dark plinth, with white side rails and rear cap interface matching the native cap in the adjacent mixed TV cell37. The full console spans those neighboring floor cells, independently of the wall TV vertical pair.'
            elif n in (14,15,18,19):
                o,p=healing(sprite,n);label='Healing station quarter';family='Center healing station'
                reason='Build the exact2x2 recovery machine as one grounded tray with six circular recovery positions, tall pale side housings and one rear control arch. All four native-painted quarters share aligned frames and base; the source dark center remains recessed.'
            elif n in (17,20):
                o,p=stool(sprite,n);label='Orange stool' if n==17 else 'Yellow stool';family='Center waiting stools'
                reason='Round the native orange/yellow cushion above a short gray stem and grounded circular foot, retaining the exact original seat interaction.'
            elif n in (23,24,26,27):
                o,p=glass_table(sprite,n);label='Teal round table quarter';family='Center waiting table'
                reason='All four source quarters form one circular teal tabletop with pale rim, thin dark edge and four grounded feet; floor colors are excluded from the raised table.'
            elif n in (45,46):
                o,p=bookcase(sprite,n);label='Two-bay waiting room bookcase half';family='Center bookcase'
                reason='The side-by-side source shelf halves share a pale cornice, dark frame, two shelf levels and native colored book spines. The projected lower source row is separately flattened to floor.'
            elif n in (25,28,33,56,71,73):
                o,p=divider(sprite,n);label='Upstairs service divider' if n!=73 else 'Upstairs standalone control terminal';family='Center service dividers'
                reason='Visual inference from the complete2F source: three white-topped orange-sided service barriers run north/south at floor positions, ending beside a PC or control terminal. They are not tall vertical stacks. Remove only28-to33 and71-to73; keep all source collision. The terminal cap and body share one horizontal control board and grounded cabinet; native button colors remain on the board.'
            elif n in {47,48,49,50,51,52,53,54,55,57}:
                o,p=escalator(sprite,n);label='Center escalator section';family='Center escalator'
                reason='The exact2x3 escalator has a continuous stepped gray center flight and two sage side balustrades with red handrails. Preserve the source warp/navigation; exact WALK/warp fragments gain decorative display so the whole flight is visible.'
            elif n==41:
                m=Model();m.box((0,16),(0,8),(5,12),material(sprite,(150,150,165)))
                m.box((0,16),(7,8),(4,13),material(sprite,(255,255,220)));o,p=m.occ,m.paint
                label='Upstairs short service gate';family='Center service gate'
                reason='The source is a short gray gate across the opening between counters, with a pale horizontal top; give it a continuous low grounded bar instead of the old isolated projected slab.'
            else:raise AssertionError(n)
            new=encode(o,p,old,'hand-emerald-center-review');changes={}
            if n in REMOVE_ON:
                assert old.get('on')==key_at[REMOVE_ON[n]],(n,old.get('on'))
                changes['on']={'old':new.pop('on'),'new':None}
            if n in ADD_ON:
                assert 'on' not in old
                new['on']=key_at[ADD_ON[n]];changes['on']={'old':None,'new':new['on']}
            if n in WALK:
                assert not old.get('walk') and not old.get('seat'),n
                new['walk']=True;changes['walk']={'old':None,'new':True}
            if n in COUNTER_KIND:
                assert 'counter' not in old
                new['counter']=True;changes['counter']={'old':None,'new':True}
            if changes:
                amendments.append({'key':key,'source_ordinal':n,'baseline_entry_sha256':entry_hash(old),'proposed_entry_sha256':entry_hash(new),
                    'source_pixel_sha256':hashlib.sha256(sprite.tobytes()).hexdigest(),'source_pixel_mode':sprite.mode,'changes':changes,'occurrences':source_occurrences(art,key)})
            updates[key]=new;decision='changed'
        ledger.append({'key':key,'kind':'cell','label':label,'family':family,'decision':decision,'reason':reason,
                       'baseline_entry_sha256':entry_hash(old),'occurrences':art.locations[key],
                       'evidence':[str(OUT/'source'/f'page-{(n-1)//32+1:03d}.png'),str(OUT/'source-context'/f'page-{(n-1)//16+1:03d}.png')]})
    return updates,ledger,amendments


def assemblies(art,owned,baseline,current):
    by_key={r['key']:r['source_ordinal'] for r in owned};key_at={v:k for k,v in by_key.items()};found={}
    def add(map_id,x,y,w,h,label,include=(),only=None):
        m=art.maps[map_id];grid=art.map_cells(m);raw=base64.b64decode(m['cells'])
        if x<0 or y<0 or x+w>m['w'] or y+h>m['h']:return
        reference=[line[x:x+w] for line in grid[y:y+h]]
        layout=[[k if (k in by_key or k in include) and (only is None or by_key.get(k) in only) else None for k in line] for line in reference]
        def positions(table):
            at={(xx,zz):k for zz,line in enumerate(layout) for xx,k in enumerate(line) if k and k in table['cells']}
            solid=lambda p:raw[((y+p[1])*m['w']+x+p[0])*4+2] in (7,147)
            counter=lambda p:raw[((y+p[1])*m['w']+x+p[0])*4+2]==144
            present={p:k for p,k in at.items() if solid(p) or counter(p) or any(table['cells'][k].get(f) for f in ('on','walk','seat'))}
            links={p:(p[0],p[1]+1) for p,k in present.items() if table['cells'][k].get('on')==present.get((p[0],p[1]+1)) and table['cells'][k].get('on') is not None}
            placements=[]
            for start in sorted(set(present)-set(links.values())):
                chain=[start]
                while chain[-1] in links:chain.append(links[chain[-1]])
                end=chain[-1]
                if len(chain)>1 and solid(end):
                    placements.extend({'source':list(p),'offset':[end[0],level,end[1]]} for level,p in enumerate(reversed(chain)))
                else:
                    for p in chain:
                        entry=table['cells'][present[p]]
                        if solid(p) or counter(p) or entry.get('walk') or entry.get('seat'):
                            placements.append({'source':list(p),'offset':[p[0],0,p[1]]})
            emitted={tuple(p['source']) for p in placements}
            return placements,[[k if (xx,zz) in emitted else None for xx,k in enumerate(line)] for zz,line in enumerate(layout)]
        now,shown=positions(current);before,old=positions(baseline)
        if not now:return
        stamp=json.dumps([shown,now,before],separators=(',',':'));identifier='emerald_center_'+hashlib.sha256(stamp.encode()).hexdigest()[:12]
        item=found.setdefault(identifier,{'id':identifier,'kind':'object (model as one)','note':label,'layout':shown,'reference_layout':reference,
                                         'placements':now,'baseline_layout':old,'baseline_placements':before,'occurrences':[]})
        item['occurrences'].append({'map':map_id,'x':x,'y':y})
    for n in (2,3,42,58,59):
        for p in art.locations[key_at[n]]:add(p['map'],p['x'],p['y'],1,3,'Complete potted plant and source floor projection')
    for p in art.locations[PC]:
        grid=art.map_cells(art.maps[p['map']]);north=grid[p['y']-1][p['x']] if p['y'] else None
        allowed=north and (current['cells'].get(north,{}).get('on')==PC or north==key_at[56])
        add(p['map'],p['x'],p['y']-1 if allowed else p['y'],1,3 if allowed else 2,'Complete shared PC in its exact source cap/foot context',include=(north,) if allowed else ())
    for p in art.locations[key_at[5]]:add(p['map'],p['x'],p['y'],2,2,'Complete native wall map panel')
    for p in art.locations[key_at[31]]:add(p['map'],p['x'],p['y'],2,3,'Complete wall TV with independent adjacent medical console')
    for p in art.locations[key_at[14]]:add(p['map'],p['x']-1,p['y']-1,6,4,'Complete healing station inside its U-shaped service counter and wall TV')
    add('EM_EVER_GRANDE_CITY_POKEMON_LEAGUE_1F',14,1,5,3,'Complete Pokemon League east service counter against the room boundary')
    for p in art.locations[key_at[23]]:add(p['map'],p['x']-1,p['y']-1,4,4,'Complete circular waiting table and surrounding stools',only={17,20,23,24,26,27})
    for p in art.locations[key_at[45]]:add(p['map'],p['x'],p['y'],2,2,'Complete two-bay bookcase with floor projection')
    for n in (47,53):
        for p in art.locations[key_at[n]]:add(p['map'],p['x'],p['y'],2,3,'Complete ascending or descending escalator with both side rails')
    for p in art.locations[key_at[25]]:add(p['map'],p['x'],p['y'],1,5,'Complete floor-depth service divider and terminal')
    for p in art.locations[key_at[66]]:add(p['map'],0,0,13,5,'Complete upstairs service counters, three dividers and terminals')
    # Each retained doorway is already one complete source cell and receives
    # the catalog's individual exhibit; do not invent duplicate one-cell IDs.
    return [{k:v for k,v in g.items() if k!='occurrences'} for g in found.values()],list(found.values())


def semantic_evidence(art,owned,baseline,updates):
    key_at={r['source_ordinal']:r['key'] for r in owned};stacks=[]
    for upper,lower in ADD_ON.items():
        for p in source_occurrences(art,key_at[upper]):
            m=art.maps[p['map']];raw=base64.b64decode(m['cells']);x,y=p['x'],p['y']
            assert p['below_key']==key_at[lower] and p['collision']==7 and raw[((y+1)*m['w']+x)*4+2]==7
            stacks.append(dict(p,upper=key_at[upper],lower=key_at[lower],source_collisions=[7,7],
                               carrier_height_blocks=4,geometry_height_voxels=32,headroom_blocks_at_least=4))
    return {'source_model_count':len(owned),'native_source_mode':'RGBA',
        'sources':[{'ordinal':r['source_ordinal'],'key':r['key'],'baseline_entry_sha256':entry_hash(baseline['cells'][r['key']]),
                    'proposed_entry_sha256':entry_hash(updates.get(r['key'],baseline['cells'][r['key']])),
                    'source_pixel_sha256':hashlib.sha256(art.cell(r['key'],'BAKED').tobytes()).hexdigest(),
                    'occurrences':source_occurrences(art,r['key'])} for r in owned],
        'new_wall_stacks':stacks,
        'pc_body':'All51 occurrences inspected in18 distinct source neighborhoods; v/t/up preserved exactly. The old on-to-WALK foot terminates harmlessly only while that foot has no display unit. Exact EmeraldDecorFootStackTest proves removing the body-to-foot link is required when the foot gains walk decor; upper caps remain linked.',
        'plant_foot':'Same exact runtime fixture covers plant2-to7-to11. Only7-to11 is removed. True crown2-to7 is preserved and the source floor11 emits decor without collision.',
        'dividers':'Visual inference from complete standard2F source, independently confirmed by parent: white-topped orange-sided strips run north/south at floor depth. Before/current complete assemblies expose the old fragmented elevated slabs and validate the continuous horizontal barrier/terminal joins. Only28-to33 and71-to73 links are removed; all collision values stay unchanged.',
        'mixed_tv_console':'Source37 includes the lower-right TV corner and the independent medical console cap. Right TV32-to37 joins the same vertical wall plane as left31-to36. Remove37-to40; its native cap occupies the south floor edge adjoining console40, rather than lifting the TV onto the console.',
        'counter_display':'Baseline Units/ShapePlanner ignores fitted COUNTER entries (EmeraldCounterRoutingTest). The five exact counter:true entries are intended for the separately implemented narrow Emerald counter adapter. Fitted gallery comparisons show table geometry; source collision stays COUNTER144 with one physical block and talk-through. Center counter1 has a9-voxel plate/top; the other four are8voxels.',
        'escalators':'Four west treads climb/descend into the common eight-voxel east landing. The two shared east rail source keys48/52 retain identical ten-high rails in both assemblies. Exact WALK/warp fragments receive only walk decor; map warp behavior remains unchanged.',
        'retained_doorways':'Three complete one-cell open doorway frames are retained after source/front/east/top inspection; their existing walk flag and open passage are preserved. Individual catalog exhibits cover them.',
        'status':'Authored source/geometry evidence; Minecraft capture QA and user review are separate and pending.'}


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=type(OUT),default=OUT);args=ap.parse_args()
    art=EmeraldArt(BUNDLE);baseline=read(BASELINE);owned=rows(art);updates,ledger,amendments=generate(baseline,art,owned)
    current=read(ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json');current['cells'].update(updates)
    entities,places=assemblies(art,owned,baseline,current)
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('metadata-evidence.json',amendments),
                       ('entities.json',entities),('assembly-placements.json',places),
                       ('semantic-evidence.json',semantic_evidence(art,owned,baseline,updates)),
                       ('ownership.json',{'owner':'emerald_scenery','keys':[r['key'] for r in owned]}),('pending.json',[r for r in owned if r['source_ordinal'] in PENDING])]:write_json(args.out/name,value)
    contact_pages('source',copy.deepcopy(owned),baseline,art,args.out);contact_pages('current',copy.deepcopy(owned),current,art,args.out/'preview')
    compact_context_pages(owned,art,args.out/'source-context')
    previews(entities,current,art,args.out/'preview/assemblies')
    before=[dict(e,layout=e['baseline_layout'],placements=e['baseline_placements']) for e in entities if e['baseline_placements']]
    previews(before,baseline,art,args.out/'preview/before-assemblies')
    print(len(updates),'changed,',len(ledger),'decisions;',len(PENDING),'pending;',len(entities),'whole objects')


if __name__=='__main__':main()
