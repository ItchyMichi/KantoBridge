"""Source-exact Pretty Petal wall panels, plant displays and shop furniture."""
from __future__ import annotations
import argparse,base64,copy,hashlib
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import write_json
from tools.shape_fit.blue_pass.common import Model,encode,decode,render
from tools.shape_fit.emerald_pass import building_interiors as c,review
from tools.shape_fit.emerald_pass.day_care import native

OUT=c.OUT.parent/'pretty-petal';STUDY=c.OUT.parent/'small-facilities-study/pretty_petal_flower_shop'
MAP='EM_ROUTE104_PRETTY_PETAL_FLOWER_SHOP'
ADDED={1001:'g3q/8701-8702-8695-8695',1002:'g3q/8703-8704-8695-8695'}
ROLES={1:'red framed upper wall',2:'green framed upper wall',3:'pink stems in white vase',4:'two blue-yellow flower vases',5:'single vase floor projection',6:'left upper bookcase',7:'right upper bookcase',8:'plant bench north section',9:'double vase floor projection',10:'green stems in white vase',11:'left lower bookcase',12:'right lower bookcase',13:'plant bench middle section',14:'flower crate middle',15:'register counter',16:'left rear plant table',17:'right rear plant table',18:'plant bench south end',19:'flower crate end',20:'paper on counter',21:'left flower-bowl table',22:'right flower-bowl table',23:'round wooden stool',24:'plain counter run',25:'west wooden stool',26:'counter west end',27:'counter beveled corner',1001:'red framed lower wall',1002:'green framed lower wall'}
PAIRS={1:1001,2:1002,6:11,7:12};REMOVE_ON={3,4,10,14};COUNTERS={15,20,24};FLOORS={5,9}


def leaf(m,x,y,z,dx=0,dz=0,label=3):
    for level in range(8):
        xx=x+round(dx*level/7);zz=z+round(dz*level/7);wide=2 if level<6 else 1
        m.box((xx,xx+wide),(y+level,y+level+1),(zz,zz+wide),label)


def pot(m,x,z,y=0,scale=1,white=False,flowers=False):
    body=9 if white else 0;rim=10 if white else 1
    m.cylinder((x,z),3*scale,(y,y+5),body);m.cylinder((x,z),3.6*scale,(y+4,y+6),rim)
    m.cylinder((x,z),2.8*scale,(y+5,y+6),2)
    if flowers:
        for dx,dz in ((-1,-1),(1,1)):
            m.box((x+dx,x+dx+1),(y+5,y+11),(z+dz,z+dz+1),3)
            m.ellipsoid((x+dx+.5,y+11,z+dz+.5),(2,2,2),(10 if x<16 else 11) if white else 4 if dx<0 else 5)
            m.box((x+dx,x+dx+1),(y+12,y+13),(z+dz,z+dz+1),6)
    else:
        for dx,dz in ((0,0),(-4,0),(4,0),(0,-4),(0,4)):leaf(m,x,y+5,z,dx,dz)


def wall():
    m=Model(height=32);m.box((0,16),(0,32),(12,15),0)
    m.box((0,16),(0,6),(11,16),1);m.box((0,16),(30,32),(11,16),2)
    m.box((3,13),(9,25),(14,16),3);m.box((4,12),(10,24),(15,16),4)
    return m


def bookcase():
    m=Model(width=32,height=32)
    for x in (1,28):m.box((x,x+3),(0,32),(5,15),0)
    m.box((2,30),(2,31),(5,7),0)
    for y in (2,14,29):m.box((0,32),(y,y+2),(4,16),1)
    for y in (4,16):
        for x,w,h,label in ((4,3,7,3),(7,5,6,4),(13,2,8,5),(17,4,7,6),(23,4,6,4)):
            m.box((x,x+w),(y,y+h),(9,14),label)
            m.box((x,x+w),(y+h-1,y+h),(9,14),1)
    return m


def vase(double=False,green=False):
    m=Model(height=32)
    for x in ((4,12) if double else (9,)):
        radius=2.7 if double else 3.5
        m.cylinder((x,9),radius,(0,9),0);m.cylinder((x,9),radius+.5,(8,10),1)
        for i,dx in enumerate((-1,1)):
            stem=x+dx;m.box((stem,stem+1),(9,25-i*3),(9,10),2)
            m.ellipsoid((stem+.5,17-i*2,9.5),(2,4,1),2)
            if green:m.ellipsoid((stem+.5,24-i*3,9.5),(1.5,4,1.5),3)
            else:
                m.ellipsoid((stem+.5,25-i*3,9.5),(2,3,2),3 if (i+x)%2 else 4)
                m.box((stem,stem+1),(26-i*3,27-i*3),(9,10),1)
    return m


def bench(role):
    m=Model(height=32);m.box((0,16),(7,9),(0,16),7)
    for x in (1,13):
        for z in ((1,) if role=='north' else (12,) if role=='south' else ()):
            m.box((x,x+2),(0,7),(z,z+3),8)
    m.box((1,3),(5,7),(0,16),8);m.box((13,15),(5,7),(0,16),8)
    pot(m,8,8,9,1,False,False)
    return m


def plant_table():
    m=Model(width=32,depth=32,height=32)
    for x in (1,28):
        for z in (1,28):m.box((x,x+3),(0,8),(z,z+3),8)
    m.box((0,32),(8,10),(0,32),7)
    for x in (5,12,21,28):pot(m,x,7,10,.8,False,False)
    pot(m,8,24,10,1.2,True,True);pot(m,24,24,10,1.2,True,True)
    return m


def crate(end):
    m=Model();m.box((1,15),(0,5),(0,16),0);m.box((2,14),(5,6),(1,15),2)
    for x in (0,14):m.box((x,x+2),(0,9),(0,16),1)
    if end:m.box((0,16),(0,9),(14,16),1)
    else:m.box((0,16),(0,4),(0,2),1)
    for z in (3,10):
        for x in (5,10):
            m.box((x,x+1),(5,12),(z,z+1),3);m.ellipsoid((x+.5,10,z+.5),(3,2,2),3)
            m.ellipsoid((x+.5,13,z+.5),(2.5,2,2.5),4 if (x+z)%2 else 5)
            m.box((x,x+1),(14,15),(z,z+1),6)
    return m


def counter(n):
    m=Model();m.box((0,16),(0,7),(0,16),0);m.box((0,16),(7,9),(0,16),1)
    if n==26:m.box((0,16),(0,7),(0,16),cut=True);m.box((1,4),(0,7),(1,4),0);m.box((1,4),(0,7),(12,15),0)
    if n==27:
        yy,zz,xx=np.indices(m.occ.shape);m.occ[(xx+zz>23)]=False;m.paint[(yy<7)&m.occ]=2
    if n==20:m.box((5,11),(9,10),(6,12),3)
    if n==15:
        m.box((3,14),(9,11),(2,14),4);m.box((6,14),(11,15),(2,8),4)
        m.box((7,13),(12,15),(7,9),3);m.box((4,11),(11,12),(9,13),3)
        for x in (5,7,9):
            for z in (10,12):m.box((x,x+1),(12,13),(z,z+1),4)
    return m


def model(n,sprite):
    if n in (1,2,1001,1002):
        m=wall();start=16 if n<1000 else 0;labels=m.paint[start:start+16];o=m.occ[start:start+16]
        colors=[native(sprite,t) for t in [(231,231,148),(82,140,82),(255,255,189),(156,99,57) if n in (1,1001) else (66,132,74),(255,255,255)]]
        p=np.asarray(colors,np.uint8)[labels];yy,zz,xx=np.indices(o.shape);front=o&(zz>=14);p[front]=((15-yy[front])*16+xx[front]).astype(np.uint8)
        return o,p
    if n in (6,7,11,12):
        m=bookcase();x=16 if n in (7,12) else 0;y=16 if n in (6,7) else 0;o=m.occ[y:y+16,:,x:x+16];labels=m.paint[y:y+16,:,x:x+16]
        colors=[native(sprite,t,(0,4,16,16)) for t in [(66,74,107),(247,247,255),(148,156,173),(239,123,148),(132,156,214),(247,247,57),(239,173,99)]]
        return o,np.asarray(colors,np.uint8)[labels]
    if n in (3,4,10):
        m=vase(n==4,n==10);colors=[native(sprite,t,(0,3,16,16)) for t in [(173,181,198),(255,255,255),(66,132,74),(132,173,82) if n==10 else (115,148,239) if n==4 else (247,140,173),(255,255,82) if n==4 else (255,181,214)]]
        return m.occ,np.asarray(colors,np.uint8)[m.paint]
    if n in FLOORS:
        m=Model();m.box((0,16),(0,1),(0,16),0);p=np.full(m.occ.shape,native(sprite,(222,222,231),(0,12,16,16)),np.uint8)
        p[0,5,:]=native(sprite,(181,173,181),(0,12,16,16));return m.occ,p
    if n in (8,13,18,16,17,21,22,14,19):
        if n in (8,13,18):m=bench({8:'north',13:'middle',18:'south'}[n]);o=m.occ;labels=m.paint
        elif n in (14,19):m=crate(n==19);o=m.occ;labels=m.paint
        else:
            m=plant_table();x=16 if n in (17,22) else 0;z=16 if n in (21,22) else 0;o=m.occ[:,z:z+16,x:x+16];labels=m.paint[:,z:z+16,x:x+16]
        colors=[native(sprite,t) for t in [(173,99,49),(231,165,66),(66,74,107),(66,132,74),(247,156,181),(123,156,247),(255,255,115),(156,206,148),(82,140,82),(173,173,189),(247,247,255),(198,90,99)]]
        return o,np.asarray(colors,np.uint8)[labels]
    if n in (23,25):
        m=Model()
        for x in (3,11):
            for z in (3,11):m.box((x,x+2),(0,7),(z,z+2),0)
        m.cylinder((8,8),6.5,(6,8),0);m.cylinder((8,8),5.5,(8,9),1)
        colors=[native(sprite,t,(0,0,16,12)) for t in [(156,99,49),(231,165,66)]];return m.occ,np.asarray(colors,np.uint8)[m.paint]
    m=counter(n);colors=[native(sprite,t,(0,0,16,12)) for t in [(156,99,57),(231,165,66),(66,132,74),(247,247,255),(132,140,165)]]
    return m.occ,np.asarray(colors,np.uint8)[m.paint]


def reason(n):
    if n in (1,2,1001,1002):return 'Complete source framed wall panel: a shallow yellow wall, green lower rail and attached red/green picture frame across its two exact SOLID rows. Restore the missing lower source model and retain native picture art; the previous upper-only open lintel is incomplete.'
    if n in (3,4,10):return ROLES[n]+': grounded white cylindrical vase, separate stems/leaves and attached flowers; remove the broad extruded yellow wall and green dado. The source lower row is a WALK floor projection, so remove the false vertical link and keep the complete vase over its SOLID carrier.'
    if n in FLOORS:return 'Clean the native gray floor projection below a complete northern vase. A one-voxel walk decoration removes duplicated pot bases without adding collision; the actual upper vase no longer links onto this WALK cell.'
    if n in (6,7,11,12):return 'One complete two-wide bookcase with two loaded shelves, short feet, white boards and supported dark posts. Exact SOLID upper/lower pairs replace four independent deep shelving boxes and preserve native colored book spines.'
    if n in (8,13,18):return ROLES[n]+': a floor-depth green display bench with a thin supported top, brown pot and upright spreading leaves. Preserve the three-row run as physical depth rather than a stacked plant column; no projected floor or wall is carried by the leaves.'
    if n in (16,17,21,22):return ROLES[n]+': one complete two-by-two green table with four legs, four rear brown pots and two forward flower bowls. Upright foliage and supported blooms replace flattened flower mats; all exact source quarters share one geometry.'
    if n in (14,19):return 'A low wooden flower crate with soil, connected stems and pink/blue blooms. The repeated source cells run along the floor; remove the false upper-to-end on relationship and retain equal-height contiguous crates at all short and long display runs.'
    if n in (23,25):return 'Raised round wooden stool with a native ochre seat and four legs; retain seat:true on its WALK source and remove the flattened floor-backed mat.'
    return ROLES[n]+': continuous low ochre counter, supported side/body and native green beveled corner. The paper and register sit above its thin top; exact COUNTER opt-ins retain one collision block and use only decor for the raised register, while SOLID ends keep their source kind.'


def sources(art):
    rows=copy.deepcopy(review.read(STUDY/'source-evidence.json'))
    for n,key in ADDED.items():
        occ=[]
        for value in art.locations[key]:
            s=art.maps[value['map']];data=base64.b64decode(s['cells']);x,y=value['x'],value['y'];collision=data[(y*s['w']+x)*4+2];assert collision==7
            occ.append(dict(value,collision=collision,map_sha256=hashlib.sha256(data).hexdigest()))
        rows.append({'position':n,'key':key,'metadata':{'class':'voxels'},'occurrences':occ,'source_pixel_sha256':hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest()})
    return rows


def generate(table,art):
    source=sources(art);by={s['position']:s['key'] for s in source};patch={};ledger=[];metadata=[]
    for s in source:
        n,key=s['position'],s['key'];added=n in ADDED;assert not added or key not in table['cells'];old=table['cells'].get(key,{'class':'voxels'});sprite=art.cell(key,'BAKED');assert hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest()==s['source_pixel_sha256']
        o,p=model(n,sprite);new=encode(o,p,old,source='hand-emerald-pretty-petal',trim=True)
        if n in PAIRS:new['on']=by[PAIRS[n]]
        if n in REMOVE_ON:new.pop('on',None)
        if n in FLOORS:new['walk']=True
        if n in COUNTERS:new['counter']=True
        patch[key]=new;before={k:v for k,v in old.items() if k not in review.GEOMETRY};after={k:v for k,v in new.items() if k not in review.GEOMETRY}
        if before!=after:
            witnesses=copy.deepcopy(s['occurrences'])
            for witness in witnesses:
                x,y=witness['x'],witness['y'];grid=art.map_cells(art.maps[witness['map']]);witness['below_key']=grid[y+1][x] if y+1<len(grid) else None
                raw=base64.b64decode(art.maps[witness['map']]['cells']);witness['below_collision']=raw[((y+1)*art.maps[witness['map']]['w']+x)*4+2] if y+1<len(grid) else None
                if n in PAIRS:assert witness['below_key']==new['on'] and witness['collision']==witness['below_collision']==7
                if n in COUNTERS:assert witness['collision']==144
                if n in FLOORS:assert witness['collision']==0
            metadata.append({'key':key,'before':before,'after':after,'source_pixel_sha256':s['source_pixel_sha256'],'occurrences':witnesses,'reason':reason(n)})
        row={'key':key,'kind':'cell','group':'building__pretty_petal_flower_shop','source_ordinal':n,'label':ROLES[n],'family':'Pretty Petal complete shop fixtures','decision':'added' if added else 'changed','baseline_entry_sha256':None if added else review.entry_hash(old),'proposed_entry_sha256':review.entry_hash(new),'source_pixel_sha256':s['source_pixel_sha256'],'occurrences':art.locations[key],'reason':reason(n),'evidence':[str(STUDY/'source-evidence.json'),str(OUT/'source-evidence.json'),str(OUT/'inspection')],'visual_status':'Original source/model inspected; proposed geometry local and Minecraft QA pending'}
        if added:row['baseline_absent']=True
        ledger.append(row)
    return patch,ledger,metadata,source


def assemblies(art):
    grid=art.map_cells(art.maps[MAP]);specs=[]
    selections=[('red_picture',[1,0,1,2],True),('green_picture',[4,0,1,2],True),('bookcase',[7,1,2,2],True),('pink_vase',[6,1,1,2],False),('double_vase',[2,1,1,2],False),('green_vase',[9,1,1,2],False),('left_plant_table',[10,1,2,2],False),('right_plant_table',[12,1,2,2],False),('center_plant_benches',[6,5,2,3],False),('east_plant_bench',[14,5,1,3],False),('east_flower_crates',[12,5,2,3],False),('center_west_crates',[5,6,1,2],False),('center_east_crates',[8,6,1,2],False),('west_flower_crates',[0,7,1,2],False),('front_short_crates',[11,3,2,1],False),('counter_and_stools',[0,2,2,4],False)]
    for name,rect,stack in selections:
        x,y,w,d=rect;layout=[r[x:x+w] for r in grid[y:y+d]];physical=copy.deepcopy(layout)
        if name=='counter_and_stools':
            for xx,zz in ((0,0),(0,2)):physical[zz][xx]=None
        places=[{'source':[xx,zz],'offset':[xx,d-1-zz if stack else 0,d-1 if stack else zz]} for zz,row in enumerate(physical) for xx,key in enumerate(row) if key]
        spec={'id':'emerald_pretty_petal_'+name,'kind':'complete actual shop fixture','note':'Source adjacency shared by Pretty Petal and its prototype. Exact supported pairs stand upright; plant/crate rows retain physical floor depth.','map':MAP,'rect':rect,'source_layout':layout,'model_layout':physical,'placements':places}
        if stack:spec['baseline_placements']=[{'source':[xx,zz],'offset':[xx,0,zz]} for zz,row in enumerate(physical) for xx,key in enumerate(row) if key]
        specs.append(spec)
    return specs


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args();art=c.EmeraldArt(c.BUNDLE);table=review.read(review.BASELINE);patch,rows,metadata,source=generate(table,art);current=copy.deepcopy(table);current['cells'].update(patch)
    args.out.mkdir(parents=True,exist_ok=True);write_json(args.out/'updates.json',{'cells':patch,'buildings':{}});write_json(args.out/'ledger.json',rows);write_json(args.out/'metadata-evidence.json',metadata);write_json(args.out/'source-evidence.json',source)
    target=args.out/'assemblies';target.mkdir(exist_ok=True);specs=assemblies(art);entities=[c.assembly_evidence(s,current,art,target) for s in specs]
    from tools.shape_fit import hand_check
    for spec,entity in zip(specs,entities):
        places=spec['placements'];w=max(p['offset'][0]+1 for p in places)*16;d=max(p['offset'][2]+1 for p in places)*16;h=max(p['offset'][1]*16+len(decode(current['cells'][entity['layout'][p['source'][1]][p['source'][0]]])[0]) for p in places)
        occ=np.zeros((h,d,w),bool);rgb=np.zeros((*occ.shape,3),np.uint8)
        for place in places:
            x,z=place['source'];key=entity['layout'][z][x];o,p=decode(current['cells'][key]);xx,yy,zz=[v*16 for v in place['offset']];occ[yy:yy+len(o),zz:zz+16,xx:xx+16]=o;rgb[yy:yy+len(o),zz:zz+16,xx:xx+16]=np.asarray(art.cell(key,'BAKED').convert('RGB'))[p//16,p%16]
        hand_check.picture(occ,rgb,target/(spec['id']+'-angled.png'),5)
    proof_path=args.out/'runtime-proof.json'
    if proof_path.exists():
        proof=review.read(proof_path)
        for entity in entities:
            match=next((r for r in proof['occurrences'] if r['id']==entity['id']),None)
            if match:entity['baseline_native_fragments']={'evidence':proof_path.relative_to(review.ROOT).as_posix(),'evidence_sha256':hashlib.sha256(proof_path.read_bytes()).hexdigest(),'baseline_sha256':proof['original_baseline_sha256'],'occurrence':{k:match[k] for k in ('map','x','y')},'source_layout':match['layout']}
    write_json(args.out/'assemblies.json',specs);write_json(args.out/'entities.json',entities);inspection=args.out/'inspection';inspection.mkdir(exist_ok=True)
    for row in rows:render(current['cells'][row['key']],art.cell(row['key'],'BAKED'),inspection/str(row['source_ordinal']))
    for start in range(0,len(rows),12):
        sheet=Image.new('RGB',(1200,900),'#e8ecef');draw=ImageDraw.Draw(sheet)
        for i,row in enumerate(rows[start:start+12]):
            n=row['source_ordinal'];x=i%4*300;y=i//4*300;draw.text((x+8,y+8),f'{n} {ROLES[n]}',fill='black');sheet.paste(art.cell(row['key'],'BAKED').resize((80,80),Image.Resampling.NEAREST),(x+8,y+33));im=Image.open(inspection/str(n)/'angled.png').convert('RGBA');im.thumbnail((200,220));sheet.paste(im,(x+95,y+50),im)
        sheet.save(inspection/f'contact-{start//12+1:02d}.png')
    print(f'{len(rows)} decisions, {len(ADDED)} source additions, {len(metadata)} exact metadata amendments, {len(entities)} actual assemblies')


if __name__=='__main__':main()
