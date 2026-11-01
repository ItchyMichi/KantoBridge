"""Source-audited final shared furniture, machines and connected railings.

Produces proposals only. The large Devon table additionally needs its exact
whole-object template because its dark center is also a Factory wall cell.
"""
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
from tools.shape_fit.emerald_pass.environment import assembly_previews
from tools.shape_fit.emerald_pass.interior_furniture import chair
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.inventory import contact_pages, compact_context_pages
from tools.shape_fit.emerald_pass.review import entry_hash, read

OUT = ROOT/'local/model-gallery/emerald-pass-study/shared-final'
HANDOFF = ROOT/'local/model-gallery/emerald-pass-study/interiors/final-shared-handoff.json'
YELLOW = (('g3q/5447-5448-5449-5450','g3q/5448-5451-5450-5452'),
          ('g3q/5464-5465-5466-5467','g3q/5465-5468-5467-5469'))
EXTRA_RAILS = (210, 213, 236, 342)
PILLAR = (140, 161, 178)
TRUSS = (141, 163, 180, 196, 422)
MACHINE = {310:(0,0),296:(0,0),298:(1,0),308:(0,1),309:(1,1),318:(0,2),319:(1,2)}
PAPER = {447:482,449:483,454:489,455:490}
DEVON = (494,495,496,497,498,499,500,501,502,503,505,508)
BLUE_CAP = {144:False,148:True,445:False,446:True}
RAILS = (210,213,236,305,316,337,342)


def inventory_rows():
    rows = read(HANDOFF)
    allrows = read(INVENTORY/'shared-building/entries.json')
    rows += [dict(allrows[n-1],source_ordinal=n) for n in EXTRA_RAILS]
    all_inventory = read(INVENTORY/'inventory.json')['entries']
    lookup = {r['key']:r for r in all_inventory}
    owned = {r['key'] for r in rows}
    for key in [k for row in YELLOW for k in row]:
        if key not in owned:
            rows.append(dict(lookup[key],source_ordinal=0))
    assert len(rows)==59 and len({r['key'] for r in rows})==59
    return sorted(rows,key=lambda r:(r['source_ordinal']==0,r['source_ordinal'],r['key']))


def palette(sprite, colors):
    return np.asarray([material(sprite,c) for c in colors],np.uint8)


def yellow_table(sprite, key):
    m=Model(width=32,depth=32)
    m.box((2,30),(6,8),(2,29),0)
    m.box((3,29),(8,9),(3,28),1)
    for x in ((3,6),(26,29)):
        for z in ((3,6),(25,28)):m.box(x,(0,6),z,2)
    row,col=next((r,c) for r in range(2) for c in range(2) if YELLOW[r][c]==key)
    s=(slice(None),slice(row*16,row*16+16),slice(col*16,col*16+16))
    return m.occ[s],palette(sprite,[(195,165,65),(255,245,130),(189,173,82)])[m.paint[s]]


def pillar(sprite,n):
    m=Model()
    if n==178:
        stone=material(sprite,(140,140,165));edge=material(sprite,(90,90,115))
        m.box((1,15),(0,4),(1,15),edge).box((2,14),(4,8),(2,14),stone)
        m.box((3,13),(8,12),(3,13),stone).cylinder((8,8),5.5,(12,16),stone)
    else:
        red=material(sprite,(230,75,30),family='orange')
        bright=material(sprite,(255,125,55),family='orange')
        m.cylinder((8,8),4.8,(0,16),red)
        yy,zz,xx=np.indices(m.occ.shape)
        m.paint[m.occ & (xx<7) & (zz>8)]=bright
        if n==161:
            gold=material(sprite,(225,190,65))
            m.cylinder((8,8),5.7,(0,3),gold)
    return m.occ,m.paint


def framed_section(sprite,n):
    """Visible orange posts and diagonal bracing, without background blocks."""
    m=Model();wood=material(sprite,(200,95,55),family='orange')
    dark=material(sprite,(65,70,100));metal=material(sprite,(165,175,165))
    for x in ((1,3),(13,15)):m.box(x,(0,16),(4,12),wood)
    for y in ((0,2),(14,16)):m.box((1,15),y,(4,12),wood)
    for y in range(2,14):
        x=2+(y-2)*10//12
        m.box((x,min(x+3,14)),(y,y+1),(10,12),wood)
    if n not in (141,422):m.ellipsoid((8,7,11.5),(2.5,2.5,1.5),metal)
    return m.occ,m.paint


def blue_table(sprite,n):
    m=Model();top=material(sprite,(215,220,250));edge=material(sprite,(85,90,120))
    leg=material(sprite,(130,140,145));orange=material(sprite,(210,130,105))
    if n in BLUE_CAP:
        x=(0,15) if BLUE_CAP[n] else (1,16)
        m.box(x,(6,8),(12,16),top)
        side=(13,15) if BLUE_CAP[n] else (1,3)
        m.box(side,(0,6),(13,15),leg).box(side,(6,8),(12,16),orange)
    else:
        m.box((1,16),(6,8),(0,14),top)
        m.box((1,3),(4,6),(0,14),orange).box((1,16),(4,6),(12,14),edge)
        m.box((2,4),(0,6),(10,13),leg)
    return m.occ,m.paint


def machine_whole():
    m=Model(height=32,width=32,depth=48)
    m.box((4,30),(0,3),(5,46),2)
    m.cylinder((16,12),9.2,(3,27),0).ellipsoid((16,27,12),(9.2,3,9.2),1)
    m.cylinder((16,12),9.7,(3,5),2)
    m.cylinder((7,27),4.3,(3,19),0).ellipsoid((7,19,27),(4.3,3,4.3),1)
    m.box((5,9),(17,22),(12,27),0)
    m.box((5,9),(20,24),(12,16),1)
    m.box((22,29),(3,15),(20,29),2)
    for z in (21,25):m.box((23,28),(14,16),(z,z+2),1)
    m.box((3,14),(3,12),(35,46),0)
    for y in (5,8):m.box((4,13),(y,y+1),(45,47),2)
    m.box((17,31),(3,16),(31,46),3)
    m.box((18,30),(16,17),(32,44),3)
    for x in (20,26):m.cylinder((x,35),1.6,(17,19),1)
    for x in (19,22,25,28):m.box((x,x+1),(6,10),(45,47),2)
    return m


def machine_slice(sprite,n):
    m=machine_whole();x,z=MACHINE[n]
    s=(slice(None),slice(z*16,z*16+16),slice(x*16,x*16+16))
    return m.occ[s],palette(sprite,[(185,195,230),(245,250,255),(90,100,135),(220,160,150)])[m.paint[s]]


def railing(sprite,n):
    m=Model();metal=material(sprite,(145,150,170));light=material(sprite,(215,220,230))
    paths={210:('w','s'),213:('n','s'),236:('w','e'),305:('e','s'),316:('e','s'),337:('n','s'),342:('w','n')}
    for direction in paths[n]:
        if direction in ('w','e'):
            x=(0,9) if direction=='w' else (7,16)
            m.box(x,(3,5),(7,9),metal).box(x,(11,13),(7,9),light)
            m.box((x[0]+1,x[0]+3),(0,12),(7,9),metal)
        else:
            z=(0,9) if direction=='n' else (7,16)
            m.box((7,9),(3,5),z,metal).box((7,9),(11,13),z,light)
            m.box((7,9),(0,12),(z[0]+1,z[0]+3),metal)
    m.box((7,9),(0,13),(7,9),metal)
    return m.occ,m.paint


def control_counter(sprite,n):
    m=Model();body=material(sprite,(230,230,190));edge=material(sprite,(125,140,100))
    start,end=(1,16) if n==383 else (0,15)
    m.box((start,end),(2,9),(3,13),body).box((start,end),(9,11),(2,14),body)
    m.box((start,end),(2,3),(3,13),edge)
    # Place native buttons on the sloped control face, independent of floor bands.
    yy,zz,xx=np.indices(m.occ.shape)
    mask=m.occ & (yy==10)
    m.paint[mask]=(np.clip(zz,3,12)*16+xx)[mask]
    for x in (max(start+1,2),min(end-3,12)):m.box((x,x+2),(0,2),(4,12),edge)
    return m.occ,m.paint


def device(sprite):
    m=Model();gray=material(sprite,(155,165,175));dark=material(sprite,(65,75,100))
    m.box((2,14),(0,2),(3,14),dark).box((3,13),(2,8),(4,13),gray)
    m.box((2,14),(8,10),(3,14),gray)
    m.box((5,11),(10,13),(4,7),dark)
    m.box((6,10),(11,12),(7,8),material(sprite,(225,235,225)))
    pink=material(sprite,(220,60,120))
    for x in (4,11):m.box((x,x+2),(10,11),(10,12),pink)
    return m.occ,m.paint


def floor_tail(sprite,n):
    m=Model();tone=material(sprite,(180,190,215) if n==245 else (215,150,140),(0,0,16,8))
    dark=material(sprite,(75,85,120),(0,0,16,8))
    m.box((1,15),(0,3),(0,5),tone)
    for x in range(3,14,3):m.box((x,x+1),(1,3),(4,6),dark)
    return m.occ,m.paint


def devon_edge(sprite,n):
    """Eight-high frame sections; a template separately covers the shared center."""
    m=Model();white=material(sprite,(245,245,240));gold=material(sprite,(235,190,95));dark=material(sprite,(80,80,110))
    if n in (494,495,496):
        x=(1,16) if n==494 else (0,15) if n==496 else (0,16)
        m.box(x,(6,8),(14,16),white)
    elif n in (499,500,508):
        return projected_floor(sprite)
    else:
        x=(1,16) if n in (497,501) else (0,15) if n in (498,503) else (0,16)
        m.box(x,(6,8),(0,16),dark)
        if n in (497,501):m.box((1,4),(6,8),(0,16),white).box((4,5),(7,8),(0,16),gold)
        if n in (498,503):m.box((12,15),(6,8),(0,16),white).box((11,12),(7,8),(0,16),gold)
        if n in (501,502,503):m.box(x,(6,8),(0,3),white).box(x,(7,8),(3,4),gold)
        if n==505:m.box(x,(6,8),(12,15),white).box(x,(7,8),(11,12),gold)
        if n==501:m.box((2,4),(0,6),(2,4),white)
        if n==503:m.box((12,14),(0,6),(2,4),white)
    return m.occ,m.paint


def projected_floor(sprite):
    """The native lower half is floor; projected feet are already modeled north."""
    m=Model();m.box((0,16),(0,1),(0,16))
    _,z,x=np.indices(m.occ.shape)
    m.paint=((8+z//2)*16+x).astype(np.uint8)
    return m.occ,m.paint


def generate(table,art,rows):
    allrows=read(INVENTORY/'shared-building/entries.json')
    key_at=lambda n:allrows[n-1]['key']
    updates,ledger={},[]
    for index,row in enumerate(rows):
        n,key=row['source_ordinal'],row['key'];old=table['cells'][key]
        assert entry_hash(old)==row['baseline_entry_sha256'],key
        sprite=art.cell(key,'BAKED')
        assert hashlib.sha256(sprite.tobytes()).hexdigest()==row['sprite_sha256'],key
        metadata={}
        if key in {k for line in YELLOW for k in line}:
            occ,paint=yellow_table(sprite,key)
            label,family='Yellow dining-table quarter','yellow dining table'
            reason='The four source quarters make one Sootopolis dining table. Author its thin level top, darker apron and four grounded legs as one 32x32 object before slicing; keep patterned floor out of its sides.'
        elif n in PILLAR:
            occ,paint=pillar(sprite,n)
            label,family='Battle Palace red pillar section','red pillar'
            reason='Four exact source columns at x2/5/8/11, y0..2 show one red shaft over a gray stepped plinth. Align the circular shaft and base in a true three-section stack; all four placements have 9-block room headroom witnesses.'
            if n!=178:metadata['on']=key_at(PILLAR[PILLAR.index(n)+1])
        elif n in TRUSS:
            occ,paint=framed_section(sprite,n)
            label,family='Orange industrial braced-frame section','industrial braced frame'
            reason='Shipyard source shows orange posts, diagonal bracing and gray inset fixtures. Give the frame real posts, crosspieces and open space instead of a solid silhouette extrusion.'
            if n in (141,163,180):
                reason+=' Semantic inference: the continuous cap/middle/end structure at Shipyard 1F(7,4..6), matching the independently collision-proven 422/196 support family, is one vertical three-section truss. These three source cells are all solid because they stand at the workshop wall; the source-based 9-block room headroom accommodates the coherent 6-block stack.'
                if n!=180:metadata['on']=key_at({141:163,163:180}[n])
            if n==196:
                metadata['on']=key
                reason+=' Source-pinned self continuation follows physical south rows: three/four source rows terminate at solid196 in 1F; 2F walkable endings must remain unstacked. Each room has 9-block measured headroom, exceeding the 8-block four-row run.'
        elif n in BLUE_CAP or n in (166,451):
            occ,paint=blue_table(sprite,n)
            label,family='Blue worktable rim or supported top','blue worktable'
            reason='The source neighborhoods identify a two-cell-wide desk with a projected north rim and lower tabletop. Match the existing eight-high desk convention, add grounded legs only at actual frame corners, and leave the surrounding floor out of the furniture.'
        elif n in MACHINE:
            occ,paint=machine_slice(sprite,n)
            label,family='Industrial tank, pipes and control-unit section','industrial tank'
            reason='Exact 2x3 source layouts show a tall round rear tank, lower pipe chamber, valve block and orange control cabinet. Design one coherent 32x48 volume with connected pipework and raised dials, then slice and paint from each section own native material pixels. Both northern-left source variants share identical geometry.'
        elif n in PAPER:
            donor=table['cells'][key_at(PAPER[n])]
            occ,paint=decode(donor)
            label,family='Space Center paper-covered desk quarter','paper drafting desk'
            reason='The source 2x2 drafting desks use the same footprint and material bands as retained Devon desks 482/483/489/490. Reuse their inspected nine-high supported desktop geometry and native UV positions so each Space Center drawing appears on the flat top; no foreign texture pixels are copied.'
        elif n in RAILS:
            occ,paint=railing(sprite,n)
            label,family='Connected Aqua metal railing section','metal railing'
            reason='Exact Aqua bridge rectangles join horizontal, vertical and corner sections. Normalize these transferred neighbors to the same centered posts and two rails as revised 233. This repairs old mismatched edge positions; all complete loops and both sides of every interface are checked.'
        elif n in (383,384):
            occ,paint=control_counter(sprite,n)
            label,family='Cable-station control counter half','cable controls'
            reason='Both cable stations contain one adjacent two-wide cream control counter. Build a level shared top, pale supported cabinet and native top-facing buttons; omit the yellow safety-line and background railing from its body.'
        elif n==377:
            occ,paint=device(sprite)
            label,family='Contest Lobby control terminal','contest terminal'
            reason='Source context places a freestanding compact gray terminal with upper display and two pink controls. Build a grounded body, inset raised display and individual buttons; exclude the patterned orange lobby floor.'
        elif n in (245,248):
            occ,paint=floor_tail(sprite,n)
            label,family='Projected machine vent-foot continuation','machine projected foot'
            reason='The source keys occur immediately south of industrial equipment and are walkable in all 1F/Aqua uses. Their top rows depict the low vent/foot of the northern cabinet and lower rows are floor. Limit geometry to a north-edge three-high plinth strip, keeping floor clear; do not extrude the complete sprite into a second control tower.'
        elif n in DEVON:
            occ,paint=devon_edge(sprite,n)
            label,family='Devon conference-table edge or floor projection','Devon table'
            reason='Devon 3F has a 5x5 table at(7,3) and a 2x5 table at(15,3). Their shared unique frame cells must join retained 504/506 at height 8. Author level white/gold frame sections and preserve projected floor beneath already-modeled feet. The 5x5 table additionally needs its exact whole-object template to replace its conflicting shared wall-key 275 center, without changing that key globally.'
        elif n in (539,549,545):
            section={539:0,549:1,545:2}[n]
            occ,paint=chair(sprite,'sofa',section,True)
            label,family='Aqua west-facing navy sofa section','navy sofa'
            reason='Aqua B1F(21,8..10) and the unused Ruby counterpart show one west-facing navy sofa. Use the existing coherent three-section sofa geometry, with raised side back, seat cushion, end arms and grounded feet; choose navy source pixels rather than patterned green floor.'
        elif n in (546,548):
            occ,paint=projected_floor(sprite)
            label,family='Floor beneath projected meeting-table feet','projected table floor'
            reason='These walkable cells are south of the complete retained 2x2 meeting tabletop 537/538/504/506. Its legs already reach the ground in those physical cells. Replace the independent floating projected-foot extrusion with a one-voxel native-floor surface sampled from the floor-only lower half.'
        elif n==387:
            label,family='Cable-station repeated crossbeam support','cable crossbeam'
            reason='Source context shows the repeated gray support band spanning the full cable-station width. Existing geometry has a grounded narrow post and continuous thick top crossbeam, matching that structural role with open space beneath; source/front/east/top inspection found no justified geometry change.'
            ledger.append({'key':key,'kind':'cell','label':label,'family':family,'decision':'retained','reason':reason,'baseline_entry_sha256':entry_hash(old),'occurrences':row['occurrences'],'evidence':[str(OUT/'source/page-001.png'),str(OUT/'source-context/page-002.png')]})
            continue
        else:raise ValueError((n,key))
        new=encode(occ,paint,old,source='hand-emerald-shared-final',trim=True)
        for name,value in metadata.items():
            assert name not in old,(key,name)
            new[name]=value
        updates[key]=new
        place=row['occurrences'][0]
        ledger.append({'key':key,'kind':'cell','label':label,'family':family,'decision':'changed','reason':reason+f" Source witness: {place['map']} ({place['x']},{place['y']}); baseline {row['voxels']} voxels.",'baseline_entry_sha256':entry_hash(old),'occurrences':row['occurrences'],'evidence':[str(OUT/'source'/f'page-{index//32+1:03d}.png'),str(OUT/'source-context'/f'page-{index//16+1:03d}.png')]})
    return updates,ledger


def assemblies(art,table,rows):
    key_at=lambda n:read(INVENTORY/'shared-building/entries.json')[n-1]['key']
    found={}
    def add(name,layout,place,stack=False):
        reference=layout
        layout=[[k if k in table['cells'] else None for k in line] for line in layout]
        assert any(k for line in layout for k in line),name
        signature=json.dumps([layout,stack],separators=(',',':'))
        identifier='emerald_shared_final_'+hashlib.sha256(signature.encode()).hexdigest()[:12]
        item=found.setdefault(identifier,{'id':identifier,'kind':'stack (model as one)' if stack else 'object (model as one)','note':name,'layout':layout,'occurrences':[]})
        if layout!=reference:item['reference_layout']=reference
        item['occurrences'].append(place)
    def rectangle(name,map_id,x,y,w,h,stack=False):
        grid=art.map_cells(art.maps[map_id])
        add(name,[r[x:x+w] for r in grid[y:y+h]],{'map':map_id,'x':x,'y':y},stack)
    rectangle('Palace red shaft and stepped plinth','EM_BATTLE_FRONTIER_BATTLE_PALACE_BATTLE_ROOM',2,0,1,3,True)
    rectangle('Shipyard cap, middle and end support truss','EM_SLATEPORT_CITY_STERNS_SHIPYARD_1F',7,4,1,3,True)
    rectangle('Shipyard four-section projected truss','EM_SLATEPORT_CITY_STERNS_SHIPYARD_1F',10,9,1,4,True)
    rectangle('Shipyard three-section projected truss','EM_SLATEPORT_CITY_STERNS_SHIPYARD_1F',14,10,1,3,True)
    rectangle('Plain blue workshop desk','EM_AQUA_HIDEOUT_UNUSED_RUBY_MAP2',37,17,2,2)
    rectangle('Space Center plain worktable','EM_MOSSDEEP_CITY_SPACE_CENTER_2F',8,6,2,2)
    rectangle('Space Center laptop worktable','EM_MOSSDEEP_CITY_SPACE_CENTER_1F',10,4,2,2)
    rectangle('Space Center paper drafting desk','EM_MOSSDEEP_CITY_SPACE_CENTER_1F',2,5,2,2)
    rectangle('Cable-station paired control counter','EM_MT_CHIMNEY_CABLE_CAR_STATION',2,4,2,1)
    rectangle('Devon long narrow table','EM_RUSTBORO_CITY_DEVON_CORP_3F',15,3,2,5)
    # The large table is represented by its exact whole-object template, not
    # a knowingly broken collection of shared-key center wall geometry.
    rectangle('Aqua west-facing sofa','EM_AQUA_HIDEOUT_B1F',21,8,1,3)
    rectangle('Aqua meeting table and native floor below projected feet','EM_AQUA_HIDEOUT_B1F',18,8,2,3)
    families={
        'Yellow dining table':{k for line in YELLOW for k in line},
        'Industrial tank and pipe/control assembly':{key_at(n) for n in MACHINE},
        'Continuous harbor and Aqua metal guardrail':{key_at(n) for n in RAILS}|{key_at(n) for n in (233,426,427,432,433,438,439,440,456,457)},
    }
    for map_id,source in art.maps.items():
        grid=art.map_cells(source)
        for label,keys in families.items():
            unseen={(x,y) for y,line in enumerate(grid) for x,k in enumerate(line) if k in keys}
            while unseen:
                component=set();todo=[min(unseen)]
                while todo:
                    point=todo.pop()
                    if point not in unseen:continue
                    unseen.remove(point);component.add(point);x,y=point
                    todo.extend(((x-1,y),(x+1,y),(x,y-1),(x,y+1)))
                if len(component)<2:continue
                x0,y0=min(x for x,y in component),min(y for x,y in component)
                x1,y1=max(x for x,y in component)+1,max(y for x,y in component)+1
                layout=[[grid[y][x] if (x,y) in component else None for x in range(x0,x1)] for y in range(y0,y1)]
                add(label,layout,{'map':map_id,'x':x0,'y':y0})
    return [{k:v for k,v in item.items() if k!='occurrences'} for item in found.values()],list(found.values())


def stack_evidence(art,rows):
    key_at={r['source_ordinal']:r['key'] for r in rows}
    evidence={}
    for n,target in ((140,161),(161,178),(141,163),(163,180),(196,196)):
        key,on=key_at[n],key_at[target];places=[]
        for p in art.locations[key]:
            source=art.maps[p['map']];grid=art.map_cells(source);x,y=p['x'],p['y']
            cells=base64.b64decode(source['cells']);offset=(y*source['w']+x)*4
            below=grid[y+1][x] if y+1<len(grid) else None
            places.append(dict(p,kind=cells[offset+2],below_key=below,below_kind=cells[offset+source['w']*4+2] if y+1<len(grid) else None))
        evidence[key]={'old_on':None,'on':on,'baseline_entry_sha256':next(r['baseline_entry_sha256'] for r in rows if r['key']==key),'occurrences':places}
        if n in (141,163):evidence[key]['semantic_inference']='Continuous cap/middle/end source structure at Shipyard 1F(7,4..6) matches the collision-proven 422/196 vertical support family; all-solid placement is at workshop wall. Not inferred from collision alone.'
    witnesses=[]
    for map_id,x,y in [('EM_SLATEPORT_CITY_STERNS_SHIPYARD_1F',6,9),('EM_SLATEPORT_CITY_STERNS_SHIPYARD_2F',10,10),('EM_BATTLE_FRONTIER_BATTLE_PALACE_BATTLE_ROOM',5,7)]:
        source=art.maps[map_id];grid=art.map_cells(source);cells=base64.b64decode(source['cells'])
        assert cells[(y*source['w']+x)*4+2]==0
        chain=[]
        for yy in range(y-1,y-6,-1):
            key=grid[yy][x];kind=cells[(yy*source['w']+x)*4+2]
            assert kind==7 and np.asarray(art.cell(key,'BAKED'))[:,:,:3].max()>0
            chain.append({'x':x,'y':yy,'key':key,'kind':kind})
        witnesses.append({'map':map_id,'floor':{'x':x,'y':y,'kind':0},'north_solid_chain':chain,'headroom_blocks':9,'rule':'Headroom.rooms/ceilings: five visible solid cells give ten rows, capped at SCREEN_ROWS_GEN3=9; room flat ceiling uses maximum chain.'})
    return {'amendments':evidence,'headroom_witnesses':witnesses}


def devon_template(art):
    from tools.shape_fit.emerald.building_trials import box,paint
    grid=art.map_cells(art.maps['EM_RUSTBORO_CITY_DEVON_CORP_3F'])
    layout=[line[7:12] for line in grid[3:8]]
    drawing=art.drawing(layout,'BAKED').convert('RGB');pixels=np.asarray(drawing)
    def swatch(target):
        patch=pixels[14:64,1:79].astype(int)
        yy,xx=np.unravel_index(np.argmin(np.sum((patch-np.array(target))**2,axis=2)),patch.shape[:2])
        x,y=int(xx+1),int(yy+14);return [x,y,x+1,y+1]
    white,gold,dark=[swatch(c) for c in ((245,245,240),(235,190,95),(80,80,110))]
    solid=lambda rect:paint(rect,rect,rect,rect)
    parts=[box([1,79],[14,64],[6,8],solid(white)),box([5,75],[18,60],[7,8],solid(dark))]
    for x in ([4,5],[75,76]):parts.append(box(x,[17,61],[7,8],solid(gold)))
    for z in ([17,18],[60,61]):parts.append(box([4,76],z,[7,8],solid(gold)))
    for x in (3,26,51,74):
        for z in (16,59):
            parts.append(box([x,x+3],[z,z+3],[0,6],solid(white)))
            parts.append(box([x-1,x+4],[z-1,z+4],[0,1],solid(gold)))
    parts.append(box([3,77],[61,64],[4,6],solid(gold)))
    tiles=[]
    for line in layout:
        ids=[list(map(int,k.split('/')[1].split('-'))) for k in line]
        tiles.append([v for q in ids for v in q[:2]])
        tiles.append([v for q in ids for v in q[2:]])
    template={'id':'emerald_object_devon_conference_table','atlas':'g3q','tiles':tiles,'height':8,'terrain':[],'parts':parts}
    return template,{'layout':layout,'occurrences':[{'map':'EM_RUSTBORO_CITY_DEVON_CORP_3F','x':7,'y':3}],
        'source_pixel_sha256':hashlib.sha256(drawing.tobytes()).hexdigest(),'proposed_entry_sha256':entry_hash(template),
        'reason':'Exact 5x5 source-only template replaces furniture geometry at this location while preserving shared dark center g3q/840-840-840-840 in Factory walls. Unique edge cells also occur in the independently modeled 2x5 table.'},drawing


def stack_previews(definitions,table,art,out):
    from tools.shape_fit import hand_check
    out.mkdir(parents=True,exist_ok=True)
    for item in definitions:
        if not item['kind'].startswith('stack'):continue
        layout=item['layout'];height=len(layout)*16;width=len(layout[0])*16
        occ=np.zeros((height,16,width),bool);colors=np.zeros((*occ.shape,3),np.uint8)
        for row,line in enumerate(layout):
            for col,key in enumerate(line):
                if not key:continue
                o,p=decode(table['cells'][key]);a=np.asarray(art.cell(key,'BAKED').convert('RGB'))
                y=(len(layout)-row-1)*16;s=(slice(y,y+len(o)),slice(None),slice(col*16,col*16+16))
                occ[s]=o;colors[s]=a[p//16,p%16]
        target=out/item['id'];target.mkdir(exist_ok=True)
        art.drawing(layout,'BAKED').save(target/'source.png')
        hand_check.picture(occ,colors,target/'angled.png',7)
        hand_check.front(occ,colors,target/'front.png',8)
        hand_check.front(occ.transpose(0,2,1),colors.transpose(0,2,1,3),target/'side.png',8)
    from PIL import Image,ImageDraw
    items=[d for d in definitions if d['kind'].startswith('stack')]
    sheet=Image.new('RGB',(600*len(items),1080),'#eeeeee');draw=ImageDraw.Draw(sheet)
    for col,item in enumerate(items):
        draw.text((col*600+10,5),item['note'],fill='black')
        for filename,top,height in [('source.png',30,130),('front.png',175,180),('side.png',365,180),('angled.png',560,500)]:
            picture=Image.open(out/item['id']/filename).convert('RGB')
            factor=min(580/picture.width,height/picture.height)
            picture=picture.resize((max(1,int(picture.width*factor)),max(1,int(picture.height*factor))),Image.Resampling.NEAREST)
            sheet.paste(picture,(col*600+(600-picture.width)//2,top+(height-picture.height)//2))
    sheet.save(out/'all-stacks.png')


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=type(OUT),default=OUT);args=ap.parse_args()
    baseline=read(BASELINE);art=EmeraldArt(BUNDLE);rows=inventory_rows()
    updates,ledger=generate(baseline,art,rows)
    current=read(ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json')
    current['cells'].update(updates)
    definitions,placements=assemblies(art,current,rows)
    # Source placement and the tested Units baseline planner distinguish new
    # three-row stacks from repeated WALK projection rows that used to vanish.
    for entity in definitions:
        identifier=entity['id']
        if identifier in ('emerald_shared_final_9ed67706ed96','emerald_shared_final_2a755e1c94dd'):
            entity['baseline_placements']=[{'source':[0,row],'offset':[0,0,row]} for row in range(3)]
        elif identifier in ('emerald_shared_final_7b67a1543638','emerald_shared_final_2981088d2391'):
            last=len(entity['layout'])-1
            entity['baseline_layout']=[[None] for _ in range(last)]+[entity['layout'][last]]
            entity['baseline_placements']=[{'source':[0,last],'offset':[0,0,0]}]
    for filename,value in [('ownership.json',{'owner':'emerald_scenery','count':len(rows),'keys':[r['key'] for r in rows],'source_ordinals':[r['source_ordinal'] for r in rows]}),
                           ('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',definitions),('assembly-placements.json',placements),('stack-evidence.json',stack_evidence(art,rows))]:
        write_json(args.out/filename,value)
    template,proof,drawing=devon_template(art)
    write_json(args.out/'devon-template.json',template);write_json(args.out/'devon-template-evidence.json',proof);drawing.save(args.out/'devon-template-source.png')
    from tools.shape_fit.emerald.parts_model import build,picture,front_view
    occ,colors=build(template,np.asarray(drawing.convert('RGB')))
    picture(occ,colors,args.out/'devon-template-angled.png')
    front_view(occ,colors,args.out/'devon-template-front.png')
    compact_context_pages(rows,art,args.out/'source-context')
    contact_pages('source',copy.deepcopy(rows),baseline,art,args.out)
    contact_pages('current',copy.deepcopy(rows),current,art,args.out/'preview')
    assembly_previews([d for d in definitions if not d['kind'].startswith('stack')],current,art,args.out/'preview/assemblies')
    stack_previews(definitions,current,art,args.out/'preview/stacks')
    print(len(updates),'changes,',len(ledger),'decisions,',len(definitions),'whole assemblies; Devon whole template remains a separate proposed addition')


if __name__=='__main__':main()


