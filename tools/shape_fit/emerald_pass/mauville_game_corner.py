"""Actual Mauville casino furniture and source-bound complete machine banks."""
from __future__ import annotations
import argparse,copy,hashlib,base64
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import write_json
from tools.shape_fit.blue_pass.common import Model,encode,decode,render
from tools.shape_fit.emerald_pass import building_interiors as c,review
from tools.shape_fit.emerald_pass.day_care import native

OUT=c.OUT.parent/'mauville-game-corner';STUDY=c.OUT.parent/'small-facilities-study/mauville_game_corner';MAP='EM_MAUVILLE_CITY_GAME_CORNER'
CAPS={1001:'g3q/7036-7037-7038-7039',1002:'g3q/7040-7041-7042-7043'}
ROLES={1:'vending machine crown',2:'green vending machine base',3:'blue vending machine base',4:'yellow sofa left',5:'yellow sofa right',6:'roulette wheel table quarter',7:'roulette table northeast',8:'west blue casino chair',9:'east blue casino chair',10:'roulette betting grid west',11:'roulette betting grid east',12:'west slot cabinet',13:'east slot cabinet',14:'west waste bin',15:'plant crown',16:'roulette table southwest',17:'roulette table southeast',18:'west slot bank south end',19:'east slot bank south end',20:'east waste bin',21:'plant pot and stem',22:'counter rear west cap',23:'counter rear east cap',24:'counter northwest curve',25:'counter rear straight',26:'counter northeast curve',27:'counter west return',28:'counter east return',29:'counter southwest curve',30:'counter front highlight',31:'counter front straight',32:'counter southeast curve',1001:'west slot bank north end',1002:'east slot bank north end'}


def vendor():
    m=Model(height=32)
    for x in (2,12):
        for z in (3,12):m.box((x,x+2),(0,3),(z,z+2),0)
    m.box((1,15),(2,31),(3,14),0);m.box((0,16),(30,32),(3,15),1)
    m.box((3,13),(3,8),(14,15),2);m.box((4,12),(4,7),(14,16),cut=True);m.box((4,12),(4,7),(13,14),0)
    m.box((2,14),(8,16),(14,16),3);m.box((2,14),(16,22),(14,16),4)
    m.box((2,14),(23,29),(14,15),1)
    for x in (3,7,11):m.box((x,x+2),(12,14),(15,16),2)
    return m


def sofa():
    m=Model(width=32)
    for x in (2,28):
        for z in (3,12):m.box((x,x+2),(0,4),(z,z+2),0)
    m.box((1,31),(3,7),(2,15),0);m.box((2,30),(7,9),(4,14),1)
    m.box((1,31),(7,15),(2,4),0);m.box((2,30),(9,15),(3,5),1)
    m.box((0,2),(5,11),(3,14),0);m.box((30,32),(5,11),(3,14),0)
    return m


def roulette():
    m=Model(width=32,depth=48)
    for x in (2,27):
        for z in (3,43):m.box((x,x+3),(0,8),(z,z+3),0)
    m.box((1,31),(6,8),(2,46),0);m.box((0,32),(8,10),(0,48),1)
    m.box((2,30),(9,10),(2,46),2)
    m.cylinder((8,8),6,(10,11),3);m.cylinder((8,8),4.8,(11,12),4);m.cylinder((8,8),1.4,(12,14),3)
    yy,zz,xx=np.indices(m.occ.shape);radius=np.sqrt((xx+0.5-8)**2+(zz+0.5-8)**2);angle=np.arctan2(zz+0.5-8,xx+0.5-8)
    m.paint[(yy==11)&(radius>2)&(radius<4.8)&((np.floor(angle*4/np.pi).astype(int)%2)==0)]=5
    return m


def slot(east=False,end=None):
    m=Model()
    m.box((2,15),(0,3),(2,14),0);m.box((3,16),(3,12),(1,15),1);m.box((7,16),(12,16),(1,15),1)
    m.box((2,7),(8,11),(2,14),2);m.box((3,5),(11,15),(3,13),0);m.box((2,4),(11,14),(4,12),3)
    for z in (4,7,10):m.box((1,3),(9,10),(z,z+1),4)
    if end=='south':m.box((10,16),(12,16),(13,16),cut=True);m.box((7,15),(10,12),(13,16),1)
    if end=='north':m.box((10,16),(12,16),(0,3),cut=True);m.box((7,15),(10,12),(0,3),1)
    if east:m.occ=m.occ[:,:,::-1].copy();m.paint=m.paint[:,:,::-1].copy()
    return m


def bin_model():
    m=Model();m.cylinder((8,8),4.5,(0,2),0);m.cylinder((8,8),5.2,(2,12),1);m.cylinder((8,8),6,(11,14),2)
    m.cylinder((8,8),4.5,(7,16),cut=True);m.cylinder((8,8),4,(6,7),0)
    return m


def model(n,sprite):
    if n in (1,2,3):
        m=vendor();start=16 if n==1 else 0;colors=[native(sprite,t) for t in [(66,66,90),(247,239,247),(173,173,189),(99,189,74) if n==2 else (82,123,255),(140,181,230)]]
        labels=m.paint[start:start+16];o=m.occ[start:start+16];p=np.asarray(colors,np.uint8)[labels];yy,zz,xx=np.indices(o.shape)
        front=o&(zz>=14)&((labels==4) if n==1 else (labels==3));p[front]=((15-yy[front])*16+xx[front]).astype(np.uint8)
        return o,p
    if n in (4,5):
        m=sofa();x=0 if n==4 else 16;colors=[native(sprite,t) for t in [(82,82,99),(255,255,57)]];return m.occ[:,:,x:x+16],np.asarray(colors,np.uint8)[m.paint[:,:,x:x+16]]
    if n in (6,7,10,11,16,17):
        m=roulette();x=16 if n in (7,11,17) else 0;z=0 if n in (6,7) else 16 if n in (10,11) else 32
        o=m.occ[:,z:z+16,x:x+16];labels=m.paint[:,z:z+16,x:x+16];colors=[native(sprite,t,(0,2,16,12)) for t in [(189,132,74),(189,132,74),(115,181,107),(255,222,99),(214,189,74),(239,90,66)]]
        p=np.asarray(colors,np.uint8)[labels];yy,zz,xx=np.indices(o.shape);top=(yy==9)&o
        source_y=(zz*12//16 if n in (16,17) else zz);p[top]=(source_y[top]*16+xx[top]).astype(np.uint8)
        if n in (6,7):p[top&(zz<2)]=colors[1]
        return o,p
    if n in (8,9):
        m=Model()
        for x in (3,11):
            for z in (3,11):m.box((x,x+2),(0,7),(z,z+2),0)
        m.box((2,14),(6,8),(2,14),0);m.box((3,13),(8,9),(3,13),1)
        m.box((2,4),(8,16),(2,4),0);m.box((12,14),(8,16),(2,4),0);m.box((4,12),(10,16),(2,4),1)
        turns=1 if n==8 else 3;m.occ=np.rot90(m.occ,turns,axes=(1,2)).copy();m.paint=np.rot90(m.paint,turns,axes=(1,2)).copy()
        colors=[native(sprite,t) for t in [(66,74,107),(140,181,222)]];return m.occ,np.asarray(colors,np.uint8)[m.paint]
    if n in (12,13,18,19,1001,1002):
        m=slot(n in (13,19,1002),'south' if n in (18,19) else 'north' if n>=1000 else None)
        colors=[native(sprite,t) for t in [(66,66,90),(206,148,57),(255,198,90),(189,239,115),(239,99,173)]]
        return m.occ,np.asarray(colors,np.uint8)[m.paint]
    if n in (14,20):
        m=bin_model();colors=[native(sprite,t,(3,0,13,14)) for t in [(66,66,90),(173,173,173),(247,239,247)]];return m.occ,np.asarray(colors,np.uint8)[m.paint]
    if n in (15,21):
        m=c.plant();start=16 if n==15 else 0;colors=[native(sprite,t,(0,3,16,16) if n==15 else None) for t in [(173,173,173),(247,239,247),(66,66,90),(115,165,49),(173,214,90)]]
        return m.occ[start:start+16],np.asarray(colors,np.uint8)[m.paint[start:start+16]]
    m=Model();rear=n in (22,23,24,25,26);colors=[native(sprite,t) for t in ([(41,140,165),(239,74,82),(222,230,247)] if rear else [(132,132,148),(247,82,82),(247,239,247)])]
    m.box((0,16),(0,7),(0,16),0);m.box((0,16),(7,8),(0,16),1);m.box((0,16),(8,9),(0,16),2)
    if n in (24,26,29,32):
        yy,zz,xx=np.indices(m.occ.shape);ax=15-xx if n in (26,32) else xx;az=15-zz if n in (29,32) else zz;m.occ[(ax+az)<4]=False
    return m.occ,np.asarray(colors,np.uint8)[m.paint]


def reason(n):
    if n in (1,2,3):return ROLES[n]+': one supported 32-high vending shell with coin return, colored button panel, glass display and white header. Remove the detached blue room cornice; preserve existing blue-base on and add only the exact green-base alias proven at the adjacent machine.'
    if n in (4,5):return ROLES[n]+': half of a complete yellow two-seat sofa with supported raised cushions, back, arms and four feet; existing WALK seat interaction preserved, red carpet excluded.'
    if n in (6,7,10,11,16,17):return ROLES[n]+': slice of the complete 32x48 roulette table with continuous green betting surface, wood apron, four end legs and a raised alternating-color wheel and spindle. Exclude red carpet and wrapped betting graphics from vertical sides.'
    if n in (8,9):return ROLES[n]+': supported blue seat and back face inward toward the actual slot bank. Four dark legs replace the flattened carpet plate; existing seat flag is unchanged.'
    if n in (12,13,18,19,1001,1002):return ROLES[n]+': explicit cabinet with grounded plinth, gold upper body, sloping control ledge, green display and pink controls. West/east halves face the corresponding chairs; north/south caps finish the full source bank. No red carpet is extruded into its foot.'
    if n in (14,20):return ROLES[n]+': open round metal bin with a thicker pale rim, dark inner cavity and grounded base; remove the sealed dark lid and square carpet fragments.'
    if n in (15,21):return ROLES[n]+': one complete potted plant has a flared gray pot, narrow stem and nine arching green fronds, split across its existing source on pair. Sky-wall cornice and red carpet backing are excluded.'
    return ROLES[n]+': section of the continuous U-shaped reception counter, with a pale top, thin red lip and native blue rear or gray front body. Curved corners meet the source footprint. Only four exact COUNTER144 keys opt into fitted rendering; one-block collision and talk-through remain intact.'


def generate(table,art):
    source=review.read(STUDY/'source-evidence.json');by={r['position']:r['key'] for r in source};assert set(by)==set(range(1,33));patch={};rows=[];metadata=[]
    for s in source:
        n,key=s['position'],s['key'];old=table['cells'][key];sprite=art.cell(key,'BAKED');assert hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest()==s['source_pixel_sha256'];o,p=model(n,sprite);new=encode(o,p,old,source='hand-emerald-mauville-game-corner',trim=True)
        if n==1:new['alsoOn']=[by[2]]
        if n in (27,28,30,31):new['counter']=True
        before={k:v for k,v in old.items() if k not in review.GEOMETRY};after={k:v for k,v in new.items() if k not in review.GEOMETRY}
        if before!=after:
            witnesses=copy.deepcopy(s['occurrences'])
            if n==1:
                grid=art.map_cells(art.maps[MAP])
                for o in witnesses:
                    o['below_key']=grid[o['y']+1][o['x']];o['below_collision']=7;assert o['below_key'] in (by[2],by[3])
            metadata.append({'key':key,'before':before,'after':after,'source_pixel_sha256':s['source_pixel_sha256'],'occurrences':witnesses,'reason':reason(n)})
        patch[key]=new;rows.append({'key':key,'kind':'cell','group':'building__mauville_game_corner','source_ordinal':n,'label':ROLES[n],'family':'Mauville casino fixtures','decision':'changed','baseline_entry_sha256':review.entry_hash(old),'proposed_entry_sha256':review.entry_hash(new),'source_pixel_sha256':s['source_pixel_sha256'],'occurrences':art.locations[key],'reason':reason(n),'evidence':[str(STUDY/'source-evidence.json'),str(STUDY/'before-angles-01.png'),str(STUDY/'before-angles-02.png'),str(STUDY/'before-angles-03.png'),str(OUT/'inspection')],'visual_status':'Source and original inspected; current local/Minecraft review pending'})
    additions={};added=[];evidence=[]
    for n,key in CAPS.items():
        assert key not in table['cells'];sprite=art.cell(key,'BAKED')
        witnesses=[]
        for occurrence in art.locations[key]:
            raw=base64.b64decode(art.maps[occurrence['map']]['cells']);collision=raw[(occurrence['y']*art.maps[occurrence['map']]['w']+occurrence['x'])*4+2];assert occurrence['map']==MAP and occurrence['y']==5 and collision==0
            witnesses.append(dict(occurrence,collision=collision,map_cells_sha256=hashlib.sha256(raw).hexdigest()))
        h=hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest();evidence.append({'key':key,'baseline_absent':True,'decision':'reference_only','source_pixel_sha256':h,'occurrences':witnesses,'reason':'The source upper cabinet hood is projected into WALK0 north of the first SOLID machine row. Preserve the open ground and show this art only in the complete-bank reference; no fitted cell or walk flag is added.'})
    return patch,rows,metadata,additions,added,evidence


def assemblies(art):
    grid=art.map_cells(art.maps[MAP]);specs=[]
    rows=[('vending_pair',[0,1,2,2],'stack'),('plant_row',[5,1,3,2],'stack'),('west_sofa',[18,3,2,1],'floor'),('east_sofa',[20,3,2,1],'floor'),('west_roulette',[14,6,2,3],'floor'),('east_roulette',[18,6,2,3],'floor'),('west_slots',[1,5,4,5],'floor'),('east_slots',[6,5,4,5],'floor'),('counter',[9,0,7,4],'floor')]
    for name,rect,mode in rows:
        x,y,w,d=rect;layout=[r[x:x+w] for r in grid[y:y+d]];physical=copy.deepcopy(layout)
        if 'slots' in name:physical[0]=[None]*4
        if name=='counter':
            for zz in (0,2):
                for xx in range(1,6):physical[zz][xx]=None
        places=[{'source':[xx,zz],'offset':[xx,d-1-zz if mode=='stack' else 0,d-1 if mode=='stack' else zz]} for zz,row in enumerate(physical) for xx,k in enumerate(row) if k]
        specs.append({'id':'emerald_mauville_game_corner_'+name,'kind':'complete source furniture assembly','note':'Actual Mauville Game Corner source adjacency; plain surrounding floor/wall cells are reference only.','map':MAP,'rect':rect,'source_layout':layout,'model_layout':physical,'placements':places})
    return specs


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args();table=review.read(review.BASELINE);art=c.EmeraldArt(c.BUNDLE);patch,rows,metadata,additions,added,evidence=generate(table,art);current=copy.deepcopy(table);current['cells'].update(patch);current['cells'].update(additions)
    args.out.mkdir(exist_ok=True,parents=True)
    for name,value in [('updates.json',{'cells':patch,'buildings':{}}),('ledger.json',rows),('metadata-evidence.json',metadata),('projection-source-evidence.json',evidence)]:write_json(args.out/name,value)
    target=args.out/'assemblies';target.mkdir(exist_ok=True);specs=assemblies(art);entities=[c.assembly_evidence(s,current,art,target) for s in specs]
    from tools.shape_fit import hand_check
    for spec,entity in zip(specs,entities):
        places=spec['placements'];w=max(p['offset'][0]+1 for p in places)*16;d=max(p['offset'][2]+1 for p in places)*16;h=max(p['offset'][1]+1 for p in places)*16;occ=np.zeros((h,d,w),bool);rgb=np.zeros((*occ.shape,3),np.uint8)
        for place in places:
            x,z=place['source'];key=entity['layout'][z][x];o,p=decode(current['cells'][key]);xx,yy,zz=[v*16 for v in place['offset']];occ[yy:yy+len(o),zz:zz+16,xx:xx+16]=o;rgb[yy:yy+len(o),zz:zz+16,xx:xx+16]=np.asarray(art.cell(key,'BAKED').convert('RGB'))[p//16,p%16]
        hand_check.picture(occ,rgb,target/(spec['id']+'-angled.png'),4)
    proofpath=args.out/'runtime-proof.json'
    if proofpath.exists():
        proof=review.read(proofpath)
        for entity in entities:
            record=next((r for r in proof['occurrences'] if r['id']==entity['id']),None)
            if record:entity['baseline_native_fragments']={'evidence':proofpath.relative_to(review.ROOT).as_posix(),'evidence_sha256':hashlib.sha256(proofpath.read_bytes()).hexdigest(),'baseline_sha256':proof['original_baseline_sha256'],'occurrence':{k:record[k] for k in ('map','x','y')},'source_layout':record['layout']}
    write_json(args.out/'assemblies.json',specs);write_json(args.out/'entities.json',entities);inspection=args.out/'inspection';inspection.mkdir(exist_ok=True)
    cards=[*rows,*[dict(r,source_ordinal=n) for n,r in zip(CAPS,added)]]
    for row in cards:render(current['cells'][row['key']],art.cell(row['key'],'BAKED'),inspection/str(row['source_ordinal']))
    for start in range(0,len(cards),12):
        sheet=Image.new('RGB',(1200,900),'#e8ecef');draw=ImageDraw.Draw(sheet)
        for i,row in enumerate(cards[start:start+12]):
            n=row['source_ordinal'];x=i%4*300;y=i//4*300;draw.text((x+8,y+8),f'{n:02d} {ROLES[n]}',fill='black');sheet.paste(art.cell(row['key'],'BAKED').resize((80,80),Image.Resampling.NEAREST),(x+8,y+33));im=Image.open(inspection/str(n)/'angled.png').convert('RGBA');im.thumbnail((200,220));sheet.paste(im,(x+95,y+50),im)
        sheet.save(inspection/f'contact-{start//12+1:02d}.png')
    print(f'{len(rows)} repairs, {len(added)} additions, {len(metadata)} metadata transitions, {len(entities)} actual assemblies')


if __name__=='__main__':main()
