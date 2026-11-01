"""Source-inspected Day Care furniture, counter, potted shrubs and pen rails."""
from __future__ import annotations
import argparse,copy,hashlib,base64
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import write_json
from tools.shape_fit.blue_pass.common import Model,encode,decode,render
from tools.shape_fit.emerald_pass import building_interiors as c,review,mossdeep_corner as furniture

OUT=c.OUT.parent/'day-care'
STUDY=c.OUT.parent/'small-facilities-study/pokemon_day_care'
MAP='EM_ROUTE117_POKEMON_DAY_CARE'
CAP='g3q/8347-8347-8350-8351'
PC='g3q/9-10-11-12'
ROLES={1:'PC projected blue floor',2:'counter north end',3:'counter depth section',4:'plain bookcase crown',5:'potted shrub crown',
6:'counter front section',7:'counter bowl section',8:'counter southeast corner',9:'bookcase picture crown',10:'shrub pot and stem',
11:'blue-floor wooden stool',12:'wooden table northwest',13:'wooden table northeast',14:'green-rug wooden stool',
15:'wooden table southwest',16:'wooden table southeast',17:'bookcase shelves and cupboard',18:'pen southwest corner',19:'pen front pickets',20:'pen west return'}


def native(sprite,target,rect=None):
    rgb=np.asarray(sprite.convert('RGB')).astype(int);distance=((rgb-target)**2).sum(2).astype(float)
    if rect:
        x0,y0,x1,y1=rect;mask=np.zeros((16,16),bool);mask[y0:y1,x0:x1]=True;distance[~mask]=np.inf
    return int(distance.argmin())


def table():
    m=Model(width=32,depth=32)
    for x in (2,27):
        for z in (2,27):m.box((x,x+3),(0,8),(z,z+3),0)
    m.box((1,31),(6,8),(2,30),1);m.box((0,32),(8,10),(0,32),2)
    for z in (7,15,23):m.box((0,32),(9,10),(z,z+1),1)
    for x in (2,29):
        for z in (2,10,18,29):m.box((x,x+1),(9,10),(z,z+1),3)
    return m


def shrub():
    m=Model(height=32)
    m.cylinder((8,8),4,(0,2),0);m.cylinder((8,8),5,(2,7),1);m.cylinder((8,8),6,(7,9),2)
    m.cylinder((8,8),4,(8,9),0);m.box((7,9),(8,17),(7,9),3)
    m.ellipsoid((8,17,8),(7,7,6),4);m.ellipsoid((8,24,8),(5,7,5),5)
    m.ellipsoid((5,17,11),(3,5,3),6);m.ellipsoid((11,18,10),(3,5,3),6)
    return m


def bookshelf(picture=False):
    m=Model(height=32)
    m.box((1,15),(0,16),(5,15),0);m.box((2,14),(1,6),(13,16),1)
    m.box((0,2),(0,16),(4,16),1);m.box((14,16),(0,16),(4,16),1)
    m.box((2,14),(6,15),(7,15),cut=True);m.box((2,14),(7,8),(7,15),1)
    for x,h in [(2,7),(4,6),(6,8),(9,7),(11,6),(13,8)]:
        m.box((x,x+1),(8,8+h),(10,15),2 if x%3 else 3)
    m.box((0,16),(16,18),(4,16),1)
    if picture:
        m.box((3,13),(18,30),(12,15),1)
        m.box((4,12),(20,29),(14,15),4)
        m.box((5,7),(22,27),(14,15),5)
    return m


def fence(role):
    m=Model()
    if role in ('front','corner'):
        m.box((0 if role=='front' else 2,16),(3,5),(11,13),0);m.box((0 if role=='front' else 2,16),(8,10),(11,13),0)
        for x in ((3,11) if role=='front' else (2,11)):
            m.box((x,x+3),(0,13),(10,14),1);m.box((x+1,x+2),(13,15),(11,13),2)
    if role in ('return','corner'):
        m.box((2,4),(3,5),(0,16),0);m.box((2,4),(8,10),(0,16),0)
        for z in ((3,11) if role=='return' else (3,11)):
            m.box((1,5),(0,13),(z,z+3),1);m.box((2,4),(13,15),(z+1,z+2),2)
    return m


def model(n,sprite):
    if n==1:
        m=Model();m.box((0,16),(0,1),(0,16));y,z,x=np.indices(m.occ.shape)
        return m.occ,((8+z//2)*16+x).astype(np.uint8)
    if n in (2,3,6,7,8):
        m=Model();m.box((0,16),(0,7),(1,15),0);m.box((0,16),(7,8),(0,16),1)
        if n==7:
            m.cylinder((8,8),4,(8,10),2);m.cylinder((8,8),3,(9,10),cut=True)
        if n==8:
            for i in range(4):m.box((12+i,16),(0,16),(12+3-i,16),cut=True)
        colors=[native(sprite,t,(0,8,16,16) if n==2 else None) for t in [(247,189,132),(255,255,181),(181,214,107)]]
        return m.occ,np.asarray(colors,np.uint8)[m.paint]
    if n in (4,9,17):
        m=bookshelf(n==9);start=0 if n==17 else 16
        colors=[native(sprite,t,(0,8,16,16) if n in (4,9) else None) for t in [(66,74,107),(222,173,74),(82,115,181),(140,165,214),(181,214,239),(255,255,255)]]
        if n==9:
            colors[4]=native(sprite,(181,214,239),(3,0,13,9));colors[5]=native(sprite,(255,255,255),(3,0,13,9))
        return m.occ[start:start+16],np.asarray(colors,np.uint8)[m.paint[start:start+16]]
    if n in (5,10):
        m=shrub();start=16 if n==5 else 0
        colors=[native(sprite,t) for t in [(82,74,41),(181,140,74),(239,198,115),(123,107,49),(90,148,41),(148,189,74),(181,214,107)]]
        return m.occ[start:start+16],np.asarray(colors,np.uint8)[m.paint[start:start+16]]
    if n in (11,14):
        m=Model()
        for x in (3,11):
            for z in (3,11):m.box((x,x+2),(0,7),(z,z+2),0)
        m.box((2,14),(6,8),(2,14),1);m.box((3,13),(8,9),(3,13),2)
        for z in (5,9):m.box((3,13),(8,9),(z,z+1),1)
        colors=[native(sprite,t) for t in [(66,74,107),(181,140,66),(239,198,99)]]
        return m.occ,np.asarray(colors,np.uint8)[m.paint]
    if n in (12,13,15,16):
        m=table();x=16 if n in (13,16) else 0;z=16 if n in (15,16) else 0
        colors=[native(sprite,t) for t in [(66,74,107),(189,148,66),(239,198,99),(255,230,181)]]
        return m.occ[:,z:z+16,x:x+16],np.asarray(colors,np.uint8)[m.paint[:,z:z+16,x:x+16]]
    m=fence({18:'corner',19:'front',20:'return'}[n]);colors=[native(sprite,t) for t in [(165,123,49),(214,165,66),(247,206,115)]]
    return m.occ,np.asarray(colors,np.uint8)[m.paint]


def reason(n):
    if n==1:return 'The gray PC foot is projected onto a WALK blue-carpet cell below the shared complete terminal. Replace its unused gray box with one-voxel native blue floor paint and explicit decor-only walk; preserve the shared PC while the missing native source cap is added separately.'
    if n in (2,3,6,7,8):return f'{ROLES[n]}: actual L-shaped reception counter, with a continuous shallow yellow top and peach carcass; native source walls and carpet are excluded. The bowl is a shallow hollow green rim on the worktop. Only the exact raw144 source keys opt into fitted counter rendering, preserving one-block collision/talk-through.'
    if n in (4,9,17):return f'{ROLES[n]}: complete native wood bookcase with open shelves, individual blue book spines and lower cupboard; retain both existing upper-on17 source pairs. Plain crown is an attached wood cap; the picture crown additionally has a shallow framed pale-blue picture. Remove cloud-wall and carpet extrusion.'
    if n in (5,10):return f'{ROLES[n]}: two actual WALK-crown/SOLID-pot placements form one rounded shrub on a narrow stem and flared tan pot. Remove all blue carpet backing; retain existing crown-on-pot pairing and source collision.'
    if n in (11,14):return f'{ROLES[n]}: a raised slatted wood seat on four supported legs replaces the flattened dark floor plate. Existing seat:true remains; neither blue carpet nor green rug becomes stool material.'
    if n in (12,13,15,16):return f'{ROLES[n]}: quarter of one complete 32-square plank table, with a thin golden top, native pale pegs, apron and four legs. Preserve actual two-by-two adjacency while removing green-rug extrusion and upright tabletop folds.'
    return f'{ROLES[n]}: open wooden pickets and two rails join in the actual seven-cell L-shaped pen boundary, leaving gaps instead of blue floor-filled panels. The west return is a floor-depth continuation above the corner in source coordinates; remove its false vertical on link only.'


def generate(table,art):
    evidence=review.read(STUDY/'source-evidence.json');assert {r['position'] for r in evidence}==set(ROLES)
    patch={};rows=[];metadata=[]
    for source in evidence:
        key,n=source['key'],source['position'];old=table['cells'][key];sprite=art.cell(key,'BAKED')
        assert hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest()==source['source_pixel_sha256']
        occ,paint=model(n,sprite);new=encode(occ,paint,old,source='hand-emerald-day-care',trim=True)
        if n==1:new['walk']=True
        if n in (3,6,7):new['counter']=True
        if n==20:new.pop('on')
        patch[key]=new
        before={k:v for k,v in old.items() if k not in review.GEOMETRY};after={k:v for k,v in new.items() if k not in review.GEOMETRY}
        if before!=after:metadata.append({'key':key,'before':before,'after':after,'source_pixel_sha256':source['source_pixel_sha256'],'occurrences':source['occurrences'],'reason':reason(n)})
        rows.append({'key':key,'kind':'cell','group':'building__pokemon_day_care','source_ordinal':n,'label':ROLES[n],
            'family':'Day Care furniture and pen','decision':'changed','baseline_entry_sha256':review.entry_hash(old),
            'proposed_entry_sha256':review.entry_hash(new),'source_pixel_sha256':source['source_pixel_sha256'],'occurrences':art.locations[key],
            'reason':reason(n),'evidence':[str(STUDY/'source-evidence.json'),str(STUDY/'before-angles-01.png'),str(STUDY/'before-angles-02.png'),str(OUT/'inspection')],
            'visual_status':'Source, actual map and complete local assemblies inspected; Minecraft comparison pending'})
    return patch,rows,metadata


def added_cap(table,art):
    assert CAP not in table['cells']
    sprite=art.cell(CAP,'BAKED');m=Model()
    m.box((1,15),(0,3),(4,15),0);m.box((2,14),(0,4),(5,14),1)
    m.box((3,13),(4,5),(5,13),1);m.box((4,12),(5,6),(5,12),2)
    colors=[native(sprite,t,(0,8,16,16)) for t in [(99,99,115),(255,255,255),(189,189,173)]]
    entry=encode(m.occ,np.asarray(colors,np.uint8)[m.paint],{'class':'voxels','on':PC},source='hand-emerald-day-care',trim=True)
    places=art.locations[CAP];assert [(p['map'],p['x'],p['y']) for p in places]==[(MAP,10,0)]
    raw=base64.b64decode(art.maps[MAP]['cells']);assert raw[(10)*4+2]==7
    source_hash=hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest()
    row={'key':CAP,'kind':'cell','label':'Day Care terminal white cap','family':'Day Care terminal','decision':'added',
         'baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':review.entry_hash(entry),
         'source_pixel_sha256':source_hash,'source_pixel_mode':'RGB','occurrences':places,
         'reason':'Missing SOLID cap above the shared PC formerly used native wall fallback. A six-voxel white housing and gray rim join the existing complete PC through an exact on link; surrounding blue wall remains unmodeled source background.',
         'evidence':[str(OUT/'cap-source-evidence.json'),str(OUT/'assemblies/emerald_day_care_terminal.png')]}
    evidence={'key':CAP,'metadata':{'class':'voxels','on':PC},'source_pixel_sha256':source_hash,
        'occurrences':[dict(places[0],collision=7,below_key=PC,below_collision=147,map_cells_sha256=hashlib.sha256(raw).hexdigest())]}
    return entry,row,evidence


def assemblies(art):
    grid=art.map_cells(art.maps[MAP]);result=[]
    for name,rect,kind,mask in [('plant',[0,5,1,2],'stack (model as one)',[]),('plain_bookshelf',[5,1,1,2],'stack (model as one)',[]),
        ('picture_bookshelf',[6,1,1,2],'stack (model as one)',[]),('wood_table',[9,4,3,3],'object (model as one)',[(0,0),(1,0),(0,1)]),
        ('counter',[0,1,5,3],'object (model as one)',[(x,z) for z in (0,1) for x in range(4)]),
        ('pen',[6,7,6,2],'object (model as one)',[(x,0) for x in range(1,6)])]:
        x,y,w,d=rect;layout=[row[x:x+w] for row in grid[y:y+d]];physical=copy.deepcopy(layout)
        for xx,zz in mask:physical[zz][xx]=None
        spec={'id':'emerald_day_care_'+name,'kind':kind,'note':'Actual Day Care source layout; surrounding wall and floor cells remain reference only.',
              'map':MAP,'rect':rect,'source_layout':layout,'model_layout':physical}
        if name=='pen':
            spec['baseline_placements']=[{'source':[xx,zz],'offset':[xx,1 if zz==0 else 0,1 if xx==0 else zz]} for zz,row in enumerate(physical) for xx,k in enumerate(row) if k]
        result.append(spec)
    layout=[[grid[y][10]] for y in range(3)]
    result.append({'id':'emerald_day_care_terminal','kind':'complete source furniture assembly',
        'note':'Source-missing cap now joins the unchanged shared PC; projected foot becomes native blue floor only.',
        'map':MAP,'rect':[10,0,1,3],'source_layout':layout,
        'placements':[{'source':[0,0],'offset':[0,1,1]},{'source':[0,1],'offset':[0,0,1]},{'source':[0,2],'offset':[0,0,2]}],
        'baseline_layout':[layout[0],layout[1],[None]],
        'baseline_placements':[{'source':[0,0],'offset':[0,1,1]},{'source':[0,1],'offset':[0,0,1]}]})
    return result


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args()
    table=review.read(review.BASELINE);art=c.EmeraldArt(c.BUNDLE);patch,rows,metadata=generate(table,art)
    cap,added,evidence=added_cap(table,art)
    current=review.read(review.ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json');current['cells'].update(patch);current['cells'][CAP]=cap
    args.out.mkdir(exist_ok=True,parents=True);write_json(args.out/'updates.json',{'cells':patch,'buildings':{}});write_json(args.out/'ledger.json',rows);write_json(args.out/'metadata-evidence.json',metadata)
    write_json(args.out/'additions.json',{'cells':{CAP:cap},'buildings':{}});write_json(args.out/'added-ledger.json',[added]);write_json(args.out/'cap-source-evidence.json',evidence)
    target=args.out/'assemblies';target.mkdir(exist_ok=True);specs=assemblies(art)
    entities=[c.assembly_evidence(s,current,art,target) for s in specs]
    proof_path=args.out/'runtime-proof.json'
    if proof_path.exists():
        proof=review.read(proof_path)
        for entity in entities:
            matches=[r for r in proof['occurrences'] if r['id']==entity['id']]
            if not matches:continue
            row=matches[0];entity['baseline_native_fragments']={'evidence':proof_path.resolve().relative_to(review.ROOT).as_posix(),
                'evidence_sha256':hashlib.sha256(proof_path.read_bytes()).hexdigest(),'baseline_sha256':proof['original_baseline_sha256'],
                'occurrence':{k:row[k] for k in ('map','x','y')},'source_layout':row['layout']}
    write_json(args.out/'entities.json',entities);write_json(args.out/'assemblies.json',specs)
    from tools.shape_fit import hand_check
    for spec,entity in zip(specs,entities):
        layout=entity['layout'];vertical=spec['kind'].startswith('stack');custom=spec.get('placements')
        places=custom or [{'source':[x,z],'offset':[x,len(layout)-1-z if vertical else 0,0 if vertical else z]} for z,row in enumerate(layout) for x,key in enumerate(row) if key]
        width=max(p['offset'][0]+1 for p in places)*16;depth=max(p['offset'][2]+1 for p in places)*16;height=max(p['offset'][1]+1 for p in places)*16
        occ=np.zeros((height,depth,width),bool);rgb=np.zeros((*occ.shape,3),np.uint8)
        for place in places:
            x,z=place['source'];key=layout[z][x];o,p=decode(current['cells'][key]);xx,yy,zz=[v*16 for v in place['offset']]
            occ[yy:yy+len(o),zz:zz+16,xx:xx+16]=o;rgb[yy:yy+len(o),zz:zz+16,xx:xx+16]=np.asarray(art.cell(key,'BAKED').convert('RGB'))[p//16,p%16]
        hand_check.picture(occ,rgb,target/(spec['id']+'-angled.png'),6)
    inspection=args.out/'inspection';inspection.mkdir(exist_ok=True)
    for row in rows:render(patch[row['key']],art.cell(row['key'],'BAKED'),inspection/str(row['source_ordinal']))
    render(cap,art.cell(CAP,'BAKED'),inspection/'added-cap')
    for start in range(0,20,12):
        sheet=Image.new('RGB',(1200,900),'#e8ecef');draw=ImageDraw.Draw(sheet)
        for i,row in enumerate(rows[start:start+12]):
            n=row['source_ordinal'];x=i%4*300;y=i//4*300;draw.text((x+8,y+8),f'{n:02d} {ROLES[n]}',fill='black')
            sheet.paste(art.cell(row['key'],'BAKED').resize((80,80),Image.Resampling.NEAREST),(x+8,y+33))
            im=Image.open(inspection/str(n)/'angled.png').convert('RGBA');im.thumbnail((200,220));sheet.paste(im,(x+95,y+50),im)
        sheet.save(inspection/f'contact-{start//12+1:02d}.png')
    print(f'{len(rows)} repairs, {len(metadata)} exact metadata transitions, {len(entities)} source assemblies')


if __name__=='__main__':main()
