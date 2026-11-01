"""Source-inspected Seashore House walls, drink cabinets and dining furniture."""
from __future__ import annotations
import argparse,copy,hashlib
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import write_json
from tools.shape_fit.blue_pass.common import Model,encode,decode,render
from tools.shape_fit.emerald_pass import building_interiors as c,review
from tools.shape_fit.emerald_pass.day_care import native

OUT=c.OUT.parent/'seashore-house'
STUDY=c.OUT.parent/'small-facilities-study/seashore_house'
MAP='EM_ROUTE109_SEASHORE_HOUSE'
ROLES={1:'plain wall cornice',2:'pink float upper wall',3:'left drink cabinet crown',4:'right drink cabinet crown',
5:'plain lower wall',6:'pink float lower wall',7:'left drink cabinet base',8:'right drink cabinet base',
9:'dining table middle',10:'green dining chair',11:'dining table left end',12:'dining table right end',
13:'kettle on wooden stand',14:'west boundary table end',15:'east boundary table end',16:'red-white display on wooden stand',
17:'pink and blue float upper wall',18:'yellow hanging gear upper wall',19:'pink and blue float lower wall',20:'yellow hanging gear lower wall'}
PAIRS={1:5,2:6,3:7,4:8,17:19,18:20}
RETAIN={13,16}


def wall(style):
    m=Model(height=32)
    m.box((0,16),(0,30),(12,15),0)
    for x in (0,4,8,12):m.box((x,x+1),(1,29),(15,16),1)
    m.box((0,16),(0,2),(11,16),1);m.box((0,16),(28,30),(11,16),1)
    m.box((0,16),(30,32),(11,16),2)
    if style in ('pink','double'):
        m.ellipsoid((9,15,14.5),(4.5,7,1.5),3)
        m.ellipsoid((9,15,15),(2,4,3),cut=True)
        for y in (10,18):m.box((7,11),(y,y+2),(14,16),4)
    if style=='double':
        m.ellipsoid((3.5,15,14.5),(3,6.5,1.5),5)
        m.ellipsoid((3.5,15,15),(1,3.5,3),cut=True)
        m.box((2,5),(10,12),(14,16),4);m.box((2,5),(18,20),(14,16),4)
    if style=='yellow':
        m.box((3,8),(10,24),(14,16),6);m.box((5,9),(11,16),(14,16),6)
        m.box((4,6),(23,26),(14,16),1);m.box((4,7),(16,18),(15,16),4)
    # The hole belongs to each hanging float, not to the wall behind it.
    m.box((0,16),(2,28),(12,14),0)
    return m


def cabinet():
    m=Model(height=32)
    for x in (1,12):
        for z in (4,12):m.box((x,x+3),(0,3),(z,z+3),0)
    m.box((1,15),(2,31),(4,15),1)
    m.box((0,16),(2,4),(3,16),2);m.box((0,16),(30,32),(3,16),1)
    m.box((1,15),(7,21),(14,16),0)
    m.box((2,14),(8,20),(15,16),3)
    m.box((1,15),(5,7),(15,16),4)
    m.box((2,14),(21,30),(15,16),1)
    return m


def dining(role):
    m=Model();left=role in ('left','west');right=role in ('right','east')
    m.box((0,16),(8,10),(1,15),1);m.box((0,16),(6,8),(2,4),0);m.box((0,16),(6,8),(12,14),0)
    for x in ([2] if left else [11] if right else []):
        for z in (3,11):m.box((x,x+3),(0,8),(z,z+3),0)
    for z in (5,10):m.box((0,16),(9,10),(z,z+1),2)
    for x,z in ((4,3),(9,8),(2,12)):m.box((x,x+2),(9,10),(z,z+1),2)
    return m


def model(n,sprite):
    if n in (1,2,5,6,17,18,19,20):
        style='plain' if n in (1,5) else 'pink' if n in (2,6) else 'double' if n in (17,19) else 'yellow'
        m=wall(style);start=16 if n in (1,2,17,18) else 0
        colors=[native(sprite,t) for t in [(231,198,99),(173,148,66),(173,214,247),(206,115,140),(255,230,222),(49,156,247),(255,222,66)]]
        return m.occ[start:start+16],np.asarray(colors,np.uint8)[m.paint[start:start+16]]
    if n in (3,4,7,8):
        m=cabinet();start=16 if n in (3,4) else 0
        colors=[native(sprite,t) for t in [(66,74,107),(255,255,255),(165,165,99),(140,189,173),(255,230,99)]]
        o=m.occ[start:start+16];labels=m.paint[start:start+16];paint=np.asarray(colors,np.uint8)[labels]
        y,z,x=np.indices(o.shape);window=(labels==3)&(z==15)
        paint[window]=((15-y[window])*16+x[window]).astype(np.uint8)
        return o,paint
    if n==10:
        m=Model()
        for x in (3,11):
            for z in (3,11):m.box((x,x+2),(0,7),(z,z+2),0)
        m.box((2,14),(6,8),(2,14),0);m.box((3,13),(8,9),(3,13),1)
        m.box((2,4),(8,16),(2,4),0);m.box((12,14),(8,16),(2,4),0);m.box((4,12),(11,16),(2,4),1)
        colors=[native(sprite,t) for t in [(148,115,66),(33,189,24)]]
        return m.occ,np.asarray(colors,np.uint8)[m.paint]
    m=dining({9:'middle',11:'left',12:'right',14:'west',15:'east'}[n]);colors=[native(sprite,t,(0,0,16,12)) for t in [(156,115,57),(214,165,90),(189,140,66)]]
    return m.occ,np.asarray(colors,np.uint8)[m.paint]


def reason(n):
    if n in RETAIN:return ('Retain the complete source '+ROLES[n]+': the native wood stand has a thin top and visible supporting legs, with a compact white kettle and attached handle' if n==13 else 'Retain the complete source red-white display on its wooden stand: a thin top and four legs support the separate small rounded display')+'; all front, side, top and angled projections match the actual tea-service corner without floor or wall backing.'
    if n in (1,2,5,6,17,18,19,20):return ROLES[n]+': one shallow slatted wall panel is assembled across its two source rows, with supported blue cornice and attached native-colored hanging gear. Exclude the broad projected floor and remove the old two parallel wall fragments; exact SOLID-over-SOLID on links place the upper part over its actual lower mate.'
    if n in (3,4,7,8):return ROLES[n]+': one grounded 32-voxel drink cabinet has four short feet, a white housing, recessed product/display face and attached yellow trim. Both actual two-wide machines assemble from exact paired rows; remove detached bulky white caps and duplicated lower supports.'
    if n==10:return 'The source green chair becomes a raised fabric seat, supported wood back and four legs. Preserve seat:true and WALK source collision; remove its flattened dark base plate.'
    return ROLES[n]+': one section of a continuous narrow dining table, with a ten-voxel plank top, apron and legs only at real ends. Use native tan wood and small grain marks rather than the old dark ceiling and solid box; green floor and turquoise walkway borders are excluded.'


def generate(table,art):
    source=review.read(STUDY/'source-evidence.json');by={r['position']:r['key'] for r in source};assert set(by)==set(ROLES)
    patch={};rows=[];metadata=[]
    for s in source:
        n,key=s['position'],s['key'];old=table['cells'][key];sprite=art.cell(key,'BAKED');assert hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest()==s['source_pixel_sha256']
        if n not in RETAIN:
            o,p=model(n,sprite);new=encode(o,p,old,source='hand-emerald-seashore-house',trim=True)
            if n in PAIRS:
                new['on']=by[PAIRS[n]];witnesses=[]
                for occurrence in s['occurrences']:
                    x,y=occurrence['x'],occurrence['y'];grid=art.map_cells(art.maps[occurrence['map']]);assert grid[y+1][x]==new['on']
                    target=next(r for r in source if r['position']==PAIRS[n]);assert any(o['x']==x and o['y']==y+1 and o['collision']==7 for o in target['occurrences']);assert occurrence['collision']==7
                    witnesses.append(dict(occurrence,below_key=new['on'],below_collision=7))
                metadata.append({'key':key,'before':{k:v for k,v in old.items() if k not in review.GEOMETRY},'after':{k:v for k,v in new.items() if k not in review.GEOMETRY},'source_pixel_sha256':s['source_pixel_sha256'],'occurrences':witnesses,'reason':reason(n)})
            patch[key]=new
        rows.append({'key':key,'kind':'cell','group':'building__seashore_house','source_ordinal':n,'label':ROLES[n],'family':'Seashore House furniture and wall fixtures','decision':'retained' if n in RETAIN else 'changed',
            'baseline_entry_sha256':review.entry_hash(old),'proposed_entry_sha256':review.entry_hash(patch.get(key,old)),'source_pixel_sha256':s['source_pixel_sha256'],'occurrences':art.locations[key],
            'reason':reason(n),'evidence':[str(STUDY/'source-evidence.json'),str(STUDY/'before-angles-01.png'),str(STUDY/'before-angles-02.png'),str(OUT/'inspection')],
            'visual_status':'Source and original model inspected; current local and Minecraft review pending'})
    return patch,rows,metadata


def assemblies(art):
    grid=art.map_cells(art.maps[MAP]);result=[]
    specs=[('wall_gear',[3,0,4,2],True),('wall_pair',[10,0,2,2],True),('left_drinks',[1,1,2,2],True),('right_drinks',[12,1,2,2],True)]
    for y in (4,6):
        for x,w in ((0,2),(3,3),(8,3),(13,2)):specs.append((f'table_{x}_{y}',[x,y,w,1],False))
    for x in (0,13):specs.append((f'table_{x}_8',[x,8,2,1],False))
    specs.append(('tea_service',[6,1,2,2],False))
    for name,rect,stack in specs:
        x,y,w,d=rect;layout=[r[x:x+w] for r in grid[y:y+d]];physical=copy.deepcopy(layout)
        if name=='tea_service':physical[0][0]=None
        places=[{'source':[xx,zz],'offset':[xx,d-1-zz if stack else 0,d-1 if stack else zz]} for zz,row in enumerate(physical) for xx,k in enumerate(row) if k]
        spec={'id':'emerald_seashore_'+name,'kind':'complete source furniture assembly','note':'Actual Seashore House adjacency; vertical pairs use only exact source-proven on relationships.',
              'map':MAP,'rect':rect,'source_layout':layout,'model_layout':physical,'placements':places}
        if stack:spec['baseline_placements']=[{'source':[xx,zz],'offset':[xx,0,zz]} for zz,row in enumerate(physical) for xx,k in enumerate(row) if k]
        result.append(spec)
    return result


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args()
    table=review.read(review.BASELINE);art=c.EmeraldArt(c.BUNDLE);patch,rows,metadata=generate(table,art);current=copy.deepcopy(table);current['cells'].update(patch)
    args.out.mkdir(exist_ok=True,parents=True);write_json(args.out/'updates.json',{'cells':patch,'buildings':{}});write_json(args.out/'ledger.json',rows);write_json(args.out/'metadata-evidence.json',metadata)
    target=args.out/'assemblies';target.mkdir(exist_ok=True);specs=assemblies(art);entities=[c.assembly_evidence(s,current,art,target) for s in specs]
    from tools.shape_fit import hand_check
    for spec,entity in zip(specs,entities):
        places=spec['placements'];w=max(p['offset'][0]+1 for p in places)*16;d=max(p['offset'][2]+1 for p in places)*16;h=max(p['offset'][1]+1 for p in places)*16
        occ=np.zeros((h,d,w),bool);rgb=np.zeros((*occ.shape,3),np.uint8)
        for place in places:
            x,z=place['source'];key=entity['layout'][z][x];o,p=decode(current['cells'][key]);xx,yy,zz=[v*16 for v in place['offset']]
            occ[yy:yy+len(o),zz:zz+16,xx:xx+16]=o;rgb[yy:yy+len(o),zz:zz+16,xx:xx+16]=np.asarray(art.cell(key,'BAKED').convert('RGB'))[p//16,p%16]
        hand_check.picture(occ,rgb,target/(spec['id']+'-angled.png'),5)
    proof_path=args.out/'runtime-proof.json'
    if proof_path.exists():
        proof=review.read(proof_path)
        for entity in entities:
            match=next((r for r in proof['occurrences'] if r['id']==entity['id']),None)
            if match:entity['baseline_native_fragments']={'evidence':proof_path.relative_to(review.ROOT).as_posix(),'evidence_sha256':hashlib.sha256(proof_path.read_bytes()).hexdigest(),'baseline_sha256':proof['original_baseline_sha256'],'occurrence':{k:match[k] for k in ('map','x','y')},'source_layout':match['layout']}
    write_json(args.out/'assemblies.json',specs);write_json(args.out/'entities.json',entities)
    inspection=args.out/'inspection';inspection.mkdir(exist_ok=True)
    for row in rows:render(current['cells'][row['key']],art.cell(row['key'],'BAKED'),inspection/str(row['source_ordinal']))
    for start in range(0,20,12):
        sheet=Image.new('RGB',(1200,900),'#e8ecef');draw=ImageDraw.Draw(sheet)
        for i,row in enumerate(rows[start:start+12]):
            n=row['source_ordinal'];x=i%4*300;y=i//4*300;draw.text((x+8,y+8),f'{n:02d} {ROLES[n]}',fill='black');sheet.paste(art.cell(row['key'],'BAKED').resize((80,80),Image.Resampling.NEAREST),(x+8,y+33))
            im=Image.open(inspection/str(n)/'angled.png').convert('RGBA');im.thumbnail((200,220));sheet.paste(im,(x+95,y+50),im)
        sheet.save(inspection/f'contact-{start//12+1:02d}.png')
    print(f'{len(rows)} decisions, {len(patch)} changes, {len(metadata)} exact on links, {len(entities)} source assemblies')


if __name__=='__main__':main()
