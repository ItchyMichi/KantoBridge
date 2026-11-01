"""All56 Lilycove/Safari source cells, reviewed in actual map assemblies."""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model,decode,encode,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,texel,digest

OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-lilycove'
GROUP='lilycove'
SEAMS={1:'blue house west roof rib',2:'blue house middle roof rib',18:'gold roof and facade seam',
 24:'Safari central entrance lintel',32:'Safari hedge wall east continuation',33:'Safari hedge wall west continuation',
 37:'Safari west window and lintel',38:'Safari east window and lintel',40:'Safari roof rear trim',41:'Safari interior gate lower entrance return',
 42:'Safari roof west side',43:'Safari roof center',44:'Safari exterior roof east side',45:'Safari west outer window corner',46:'Safari exterior east window corner',
 47:'Safari interior east window',48:'Safari interior west window',49:'Safari front roof west corner',50:'Safari front roof fascia',51:'Safari exterior front roof east corner',
 53:'Safari lower west wall and hedge',54:'Safari exterior lower east wall and hedge',55:'Safari doorway lower west jamb',56:'Safari exterior doorway lower east jamb'}
CHANGED={3,4,5,6,7,8,9,10,11,12,13,14,21,22,23,25,27,28,29,30,31,34,36,39,52}


def decisions():
    out={n:('retained',label,'Native '+label+' is part of a reviewed complete building (Lilycove houses/b034, Safari interior b109, or the exact Route121 exterior b123). Preserve the source-specific fallback and metadata; use whole-building geometry for continuous roof, walls and doorway.') for n,label in SEAMS.items()}
    for n,side in [(15,'upper west'),(16,'arch crown'),(17,'upper east'),(19,'lower west'),(20,'lower east')]:
        out[n]=('retained','Aqua Hideout cliff portal '+side,'Existing hand-authored stone '+side+' joins the actual3x2 cliff portal at Lilycove(69,4), including its separate central open warp. It represents a solid cliff with a shallow carved arch, not a freestanding slab; the full native cliff mass and gray stone frame remain coherent in whole context.')
    out[26]=('retained','Safari bike beam with grounded supports','Two narrow rooted metal posts hold the white horizontal Acro Bike beam. Existing explicit geometry matches the source and joins the adjoining beam/corner without filling the grass beneath it.')
    out[35]=('retained','Safari horizontal bike beam','Existing white beam at y14..15 spans the gap between neighboring supports. Its empty lower section is intentional; retain this supported overhead continuation.')
    labels={3:'Lilycove yellow lamp crown',14:'Lilycove pale lamp pedestal',4:'blue fence west end',5:'blue fence middle',6:'blue fence east end',7:'blue fence west-edge return',8:'blue fence east-edge return',52:'blue fence T junction',9:'dirt bed northwest cap',10:'dirt bed northeast cap',11:'dirt bed southwest fringe',12:'dirt bed southeast fringe',13:'conifer crown beside Route123 cliff',21:'west reentrant cliff corner',22:'east reentrant cliff corner',23:'cliff corner above forest crown',25:'Safari feeding station',27:'walkable Safari bike beam turn',28:'Safari jump path upper rock projection',29:'feeding station projected feet over grass',30:'feeding station projected feet over water',31:'Safari round stone against cliff',34:'Safari jump path paired rock projection',36:'Safari jump path lower rock projection',39:'Safari jump path front rock projection'}
    for n in CHANGED:
        if n in (3,14):reason='Sculpt one connected32-high lamp, then slice at the existing on link: rounded pale foot and pedestal, slim central neck, broad white head and small native yellow light. Remove the old pinched extrusion and all ground-colored side pixels.'
        elif n in (4,5,6,7,8,52):reason='Replace the pinched silhouette extrusion with narrow blue metal rails, open pickets and grounded posts. Explicit orientation and endpoint/return/T arms follow the source fence and join adjacent actual-map segments; native grass remains outside the geometry.'
        elif n in (9,10):reason='This rounded northern dirt-bed cap is SOLID7 at all seven source placements. Preserve the four-voxel height of its adjoining reviewed middle bed, with a rounded outer end and correct horizontal native soil paint; do not flatten the solid bed or interpret it as a walkable path.'
        elif n in (11,12):reason='The southern dirt-bed fringe is WALK0 at all seven source placements and depicts the bed projected over grass. Keep a one-voxel native horizontal surface without a walk flag; it remains passable floor rather than a second raised bed row.'
        elif n==13:reason='The unique Route123 source is a conifer crown directly south of an already solid cliff and beside dense forest middle cells. Replace the grass/brown-sided pedestal with the reviewed conifer upper foliage sculpture, painted only with its native green pixels. Preserve the absent on relation: the repeated forest column is not proof of a finite tree stack, and the neighboring cliff carrier remains untouched.'
        elif n in (21,22):reason='This reentrant cliff edge joins shallow projected ledge rims above and to the outside. Preserve the inspected four-voxel source occupancy and correct its upward native x/z paint; a full-height cube would create an isolated pillar between those adjoining low rims. Native terrain elevation and movement remain unchanged.'
        elif n==23:reason='The lower-right green pixels are the neighboring forest crown projected onto a solid cliff corner. Use the exact pure sibling corner geometry and own native cliff materials, retaining the cliff profile instead of extruding the green sprite edge as a pedestal.'
        elif n==25:reason='All11 upper source cells are SOLID7 and form the physical feeding station; ten lower foot projections are WALK0 grass and one is WATER41. Remove the ineffective on-to-water relation and author the complete grounded metal feeder with four short legs, capped rectangular case and dark feed openings entirely on the upper solid carrier.'
        elif n==27:reason='Preserve the existing coherent white beam turn geometry. Add only walk:true for its four exact WALK0 Acro Bike path placements, so runtime draws the intended narrow beam above the passable floor; source movement and elevation remain unchanged.'
        elif n in (29,30):reason='The station stands on its upper SOLID source cell. These lower pixels are the projected feet over passable grass/water; remove the old extruded gray lower case and keep only a one-voxel native ground-colored surface. No new support or walk flag is introduced.'
        elif n==31:reason='Author a rounded faceted tan boulder against the Safari cliff with a broad grounded base and native stone colors; exclude the green corner background and remove the hourglass depth profile.'
        else:reason='These WALK0 source cells form the four-cell rock-marked jump path through the Safari ledge. Keep the rock and grass as a native horizontal surface instead of standing sprite-colored slabs; preserve exact movement/elevation and add no walk-decoration flag.'
        out[n]=('changed',labels[n],reason)
    assert set(out)==set(range(1,57))
    return out


def fence(sprite,n):
    m=Model();dark=texel(sprite,(66,74,107));blue=texel(sprite,(90,115,189));pale=blue
    def horizontal(x0=0,x1=16,z=1):
        for y in (2,13):m.box((x0,x1),(y,y+2),(z-1,z+1),dark)
        m.box((x0,x1),(14,15),(z-1,z+1),blue)
        for x in range(x0+2,x1-1,4):m.box((x,x+1),(4,13),(z-1,z+1),pale)
    def vertical(x,z0=0,z1=16):
        for y in (2,13):m.box((x-1,x+1),(y,y+2),(z0,z1),dark)
        m.box((x-1,x+1),(14,15),(z0,z1),blue)
        for z in range(z0+2,z1-1,4):m.box((x-1,x+1),(4,13),(z,z+1),pale)
    def post(x,z):m.box((x-1,x+2),(0,16),(z-1,z+2),dark);m.box((x-1,x+2),(14,16),(z-1,z+2),blue)
    if n==4:horizontal(3,16);post(3,1)
    elif n==6:horizontal(0,13);post(12,1)
    elif n==7:vertical(3);post(3,1)
    elif n==8:vertical(13);post(12,1)
    elif n==52:horizontal();vertical(13,0,16);post(12,1)
    else:horizontal();post(0,1)
    return m


def lamp(sprite,upper):
    m=Model(height=32);white=texel(sprite,(255,255,255));gray=texel(sprite,(156,165,189));edge=texel(sprite,(66,66,99));yellow=texel(sprite,(255,255,82))
    m.cylinder((8,8),4,(0,2),edge);m.ellipsoid((8,6,8),(6,5.5,5.5),white)
    m.cylinder((8,8),4.5,(2,4),gray);m.cylinder((8,8),2,(9,19),edge)
    m.ellipsoid((8,20,8),(7,4,6.5),white);m.cylinder((8,8),5.5,(17,19),gray)
    m.cylinder((8,8),3,(22,25),gray);m.ellipsoid((8,27,8),(3,4,3),yellow)
    start=16 if upper else 0;s=Model();s.occ[:]=m.occ[start:start+16];s.paint[:]=m.paint[start:start+16];return s


def plane(sprite,ground_only=False):
    m=Model();m.box((0,16),(0,1),(0,16));y,z,x=np.indices(m.occ.shape)
    m.paint[:]=(((12+z%4) if ground_only else z)*16+x).astype(np.uint8);return m


def feeder(sprite):
    m=Model(height=32);edge=texel(sprite,(66,66,99));gray=texel(sprite,(132,140,165));pale=texel(sprite,(222,230,239));blue=texel(sprite,(148,173,222))
    for x in (2,12):
        for z in (4,11):m.box((x,x+2),(0,7),(z,z+2),edge)
    m.box((1,15),(6,21),(3,14),gray);m.box((0,16),(20,23),(2,15),pale)
    m.box((2,14),(13,20),(13,15),pale);m.box((4,12),(14,18),(14,15),edge)
    m.box((2,14),(7,11),(13,15),edge);m.box((3,13),(7,8),(14,16),blue)
    m.box((1,15),(11,13),(13,15),blue);return m


def cliff(sprite,original,n,table):
    if n==23:
        o,p=decode(table['cells']['g3q/10697-10714-10715-10716']);m=Model(height=o.shape[0]);m.occ[:]=o
    else:m=Model();m.box((0,16),(0,16),(0,16))
    # No forest green on the cliff's own exposed sides or top.
    rgb=np.asarray(sprite.convert('RGB'));y,z,x=np.indices(m.occ.shape)
    addresses=((15-y)%16*16+x).astype(np.uint8);colors=rgb[addresses//16,addresses%16]
    green=(colors[...,1]>colors[...,0])&(colors[...,1]>colors[...,2]);addresses[green]=texel(sprite,(132,90,90))
    m.paint[:]=addresses;top=m.occ&~np.concatenate((m.occ[1:],np.zeros_like(m.occ[:1])),axis=0)
    m.paint[top]=texel(sprite,(181,132,123));return m


def generate(table=None,art=None):
    from tools.shape_fit.emerald_pass import vegetation,scenery,environment
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for(GROUP);out={}
    for n in sorted(CHANGED):
        key=rows[n-1]['key'];old=table['cells'][key];sprite=art.cell(key,'BAKED')
        if n==27:out[key]={**old,'walk':True};continue
        if n in (3,14):m=lamp(sprite,n==3)
        elif n in (4,5,6,7,8,52):m=fence(sprite,n)
        elif n in (9,10):
            m=plane(sprite);m.box((0,16),(0,4),(0,16));y,z,x=np.indices(m.occ.shape)
            # Gentle outer corner bevel; inner edge stays full-height against
            # the unchanged four-high center bed.
            if n==9:m.occ[:4,:2,:2]=False
            else:m.occ[:4,:2,14:]=False
            m.paint[:]=(z*16+x).astype(np.uint8)
        elif n in (11,12,28,29,30,34,36,39):m=plane(sprite,n in (29,30))
        elif n==13:
            o,_=scenery.tree();m=Model();m.occ[:]=o[16:32];m.paint[:]=vegetation.leaf_paint(m.occ.shape,sprite,(16,0,0))
        elif n in (21,22):out[key]=environment.horizontal(old);continue
        elif n==23:m=cliff(sprite,old,n,table)
        elif n==25:m=feeder(sprite)
        elif n==31:
            m=Model();m.ellipsoid((8,7,8),(7.7,7.5,7.5));m.ellipsoid((6,5,9),(6,5.5,6));y,z,x=np.indices(m.occ.shape)
            palette=np.asarray([texel(sprite,c) for c in ((107,74,82),(165,115,107),(206,165,148),(239,206,181))],np.uint8)
            tone=np.clip(((y+3)/4-(x+z)/32).astype(int),0,3);m.paint[:]=palette[tone]
        out[key]=m.entry(old,'hand-emerald-lilycove-review',trim=True)
        if n==25:out[key].pop('on',None)
    return out


def witnesses(art,row):
    result=[]
    for p in row['occurrences']:
        m=art.maps[p['map']];raw=base64.b64decode(m['cells']);i=(p['y']*m['w']+p['x'])*4
        result.append({**p,'collision':raw[i+2],'elevation':raw[i+3]})
    return result


def assemblies(art):
    rows=rows_for(GROUP);result=[]
    def add(name,mapid,x,y,w,d,note):
        grid=art.map_cells(art.maps[mapid]);result.append({'id':'emerald_lilycove_'+name,'kind':'object (model as one)','note':note,'layout':[r[x:x+w] for r in grid[y:y+d]],'occurrences':[{'map':mapid,'x':x,'y':y,'palette':'BAKED'}]})
    add('streetlamp','EM_LILYCOVE_CITY',61,33,1,2,'Existing vertical lamp stack; upper shape placed over pedestal through unchanged on link.')
    result[-1]['kind']='stack (model as one)'
    add('north_fence','EM_LILYCOVE_CITY',15,14,8,1,'Complete actual fence run including both endpoints.')
    add('west_fence_return','EM_LILYCOVE_CITY',30,13,1,2,'Vertical return joins west horizontal fence endpoint below.')
    add('east_fence_return','EM_LILYCOVE_CITY',33,13,4,2,'Opposite vertical return and its connected horizontal rail.')
    add('fence_junction','EM_ROUTE121',72,3,3,3,'Actual blue fence T junction and neighboring arms.')
    add('dirt_path','EM_ROUTE121',14,2,4,2,'Whole rounded dirt bed: northern SOLID row at matching four-high profile and southern WALK fringe projection.')
    add('cliff_forest','EM_ROUTE123',58,4,3,4,'Mixed crown at59,5 between intact north cliff and dense forest column; no invented stack relation.')
    add('hideout_portal','EM_LILYCOVE_CITY',69,4,3,2,'Complete retained cliff entrance and central warp.')
    add('cliff_return','EM_LILYCOVE_CITY',46,10,3,2,'Reentrant cliff corner and adjoining native cliff.')
    add('forest_cliff_corner','EM_LILYCOVE_CITY',2,9,2,2,'Cliff corner above neighboring forest crown.')
    add('feeding_station_grass','EM_SAFARI_ZONE_NORTH',32,26,1,2,'Feeder uses north SOLID carrier; lower WALK feet are projection only.')
    add('feeding_station_water','EM_SAFARI_ZONE_SOUTHWEST',18,16,1,2,'Water variant uses the same upper SOLID carrier; no support on water.')
    add('bike_beam','EM_SAFARI_ZONE_SOUTH',20,3,6,6,'Complete winding Acro Bike beam, with four passable turn cells and grounded support endpoints.')
    add('jump_path','EM_SAFARI_ZONE_NORTH',22,21,1,4,'Four-cell walkable rock-marked jump path; original elevations retained.')
    add('cliff_boulder','EM_SAFARI_ZONE_NORTH',34,29,1,2,'Rounded boulder at foot of actual cliff.')
    return result


def preview(item,art,entries,target,label):
    from tools.shape_fit.hand_check import picture,front
    layout=item['layout'];h,d,w=64,len(layout)*16,len(layout[0])*16;o=np.zeros((h,d,w),bool);c=np.zeros((h,d,w,3),np.uint8)
    location=item['occurrences'][0];source=art.maps[location['map']];raw=base64.b64decode(source['cells'])
    for rz,row in enumerate(layout):
        for rx,key in enumerate(row):
            entry=entries.get(key,{});i=((location['y']+rz)*source['w']+location['x']+rx)*4;coll=raw[i+2];rgb=np.asarray(art.cell(key,'BAKED').convert('RGB'))
            ybase=0;destz=rz*16
            if entry.get('on') and rz+1<len(layout) and layout[rz+1][rx] in [entry['on'],*entry.get('alsoOn',[])]:ybase=16;destz+=16
            if not entry or (coll in (0,41) and not any(entry.get(f) for f in ('walk','seat','on'))):
                oo=np.zeros((16,16,16),bool);oo[0]=True;pp=np.broadcast_to(np.arange(256,dtype=np.uint8).reshape(1,16,16),(16,16,16))
            else:oo,pp=decode(entry)
            count=min(oo.shape[0],h-ybase);s=(slice(ybase,ybase+count),slice(destz,destz+16),slice(rx*16,rx*16+16));view=o[s];colors=c[s]
            view|=oo[:count];colors[oo[:count]]=rgb[pp[:count]//16,pp[:count]%16][oo[:count]]
    picture(o,c,target/f'{label}-angled.png',5);front(o,c,target/f'{label}-front.png',6)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args()
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for(GROUP);updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n,(decision,label,reason) in sorted(decisions().items()):
        row=rows[n-1];key=row['key'];sprite=art.cell(key,'BAKED');target=OUT/key.split('/')[1];target.mkdir(exist_ok=True);sprite.save(target/'source.png')
        if args.previews and key in updates:render(table['cells'][key],sprite,target/'before');render(updates[key],sprite,target/'after')
        ledger.append({'key':key,'kind':'cell','label':label,'family':'lilycove-safari-town','decision':decision,'reason':reason,'baseline_entry_sha256':digest(table['cells'][key]),'evidence':[{'source_tilesets':row['tileset_membership'],'map_occurrences':witnesses(art,row),'contact_sheet':(INVENTORY/row['contact_page']).relative_to(ROOT).as_posix(),'contact_position':n},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'context_sheet':f'local/model-gallery/emerald-pass-study/towns/general__lilycove/contexts/page-{(n-1)//16+1:03}.png'}],'visual_status':'source/context/front/east/top inspected; candidate preview/runtime pending'})
    entities=assemblies(art);live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';current=json.loads(live.read_text())
    if args.previews:
        for item in entities:
            target=OUT/'assemblies'/item['id'];target.mkdir(parents=True,exist_ok=True);layout=item['layout'];art.drawing(layout,'BAKED').resize((len(layout[0])*64,len(layout)*64),Image.Resampling.NEAREST).save(target/'source.png')
            preview(item,art,table['cells'],target,'before');preview(item,art,{**current['cells'],**updates},target,'after')
    corrections=[]
    for n in (25,27):
        row=rows[n-1];key=row['key'];meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
        corrections.append({'key':key,'baseline_entry_sha256':digest(table['cells'][key]),'before':meta(table['cells'][key]),'after':meta(updates[key]),'witnesses':witnesses(art,row),'occurrences':witnesses(art,row),'source_pixel_sha256':hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB','geometry_unchanged':n==27,'reason':decisions()[n][2]})
        if n==25:
            pairs=[]
            for place in row['occurrences']:
                source=art.maps[place['map']];grid=art.map_cells(source);raw=base64.b64decode(source['cells']);x,y=place['x'],place['y']
                upper_i=(y*source['w']+x)*4;lower_i=((y+1)*source['w']+x)*4
                pairs.append({**place,'upper':key,'upper_collision':raw[upper_i+2],'lower':grid[y+1][x],'lower_collision':raw[lower_i+2],'lower_x':x,'lower_y':y+1})
            corrections[-1]['adjacent_cells']=pairs
            (OUT/'feeding-station-pairs.json').write_text(json.dumps(pairs,indent=2)+'\n')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('metadata-corrections.json',corrections),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'floor_policy':'WALK0/WATER41 unflagged cells use native floor; exact on pair stacks vertically; terrain elevation not simulated'})]:
        (OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'reviewed':56,'changed':len(updates),'retained':56-len(updates),'assemblies':len(entities)}))


if __name__=='__main__':main()
