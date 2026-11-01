"""The inspected Mossdeep game-room tables, stools, cabinets and counter banks."""
from __future__ import annotations
import argparse
import copy
import hashlib
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw
from tools.model_gallery import write_json
from tools.shape_fit.blue_pass.common import Model, encode, decode, render
from tools.shape_fit.emerald_pass import building_interiors as c, review

OUT=c.OUT.parent/'mossdeep-corner'
STUDY=c.OUT.parent/'small-facilities-study/mossdeep_game_corner'
GROUP='building__mossdeep_game_corner'
MAP='EM_MOSSDEEP_CITY_GAME_CORNER_1F'
ROLES={1:'green stool north',2:'peach stool north',3:'left cabinet crown',4:'green stool side',5:'peach stool side',
       6:'right cabinet crown',7:'table northwest',8:'table northeast',9:'left cabinet foot',
       10:'left counter rear middle',11:'left counter rear right',12:'right counter rear left',13:'right counter rear middle',
       14:'right cabinet foot',15:'table southwest',16:'table southeast',17:'left counter front left',
       18:'counter front middle',19:'left counter front right',20:'right counter front left',21:'right counter front right',
       23:'rear open doorway'}


def native(sprite,target,lower=0):
    pixels=np.asarray(sprite.convert('RGB')).astype(int)
    distance=((pixels-np.asarray(target))**2).sum(axis=2).astype(float)
    distance[:lower]=np.inf
    return int(distance.argmin())


def stool():
    m=Model()
    for x in (4,10):
        for z in (4,10):m.box((x,x+2),(0,5),(z,z+2),0)
    m.box((3,13),(4,6),(3,13),0)
    m.box((2,14),(6,7),(3,13),1);m.box((3,13),(6,7),(2,14),1)
    m.box((3,13),(7,8),(3,13),2)
    m.box((5,11),(8,9),(5,11),3)
    return m


def game_table():
    """One continuous rounded 32-square tabletop, apron and four legs."""
    m=Model(width=32,depth=32)
    for x in (3,26):
        for z in (3,26):m.box((x,x+3),(0,7),(z,z+3),0)
    m.box((1,31),(5,7),(1,31),0)
    m.box((0,32),(7,9),(2,30),1);m.box((2,30),(7,9),(0,32),1)
    m.box((1,31),(8,9),(3,29),2);m.box((3,29),(8,9),(1,31),2)
    m.box((4,28),(8,9),(4,28),3)
    return m


def cabinet():
    """A complete slatted cupboard; source yellow room cornice is excluded."""
    m=Model(height=32)
    m.box((0,16),(0,2),(3,15),0)
    m.box((1,15),(2,28),(4,13),1)
    m.box((2,14),(3,27),(12,14),2)
    for x in (2,5,8,11):m.box((x,x+1),(3,27),(13,15),3)
    m.box((1,15),(2,4),(12,15),3)
    m.box((0,16),(27,29),(3,15),0)
    m.box((1,15),(29,30),(4,14),3)
    return m


def counter():
    """One three-cell-wide, two-cell-deep wooden counter, never a tall wall."""
    m=Model(width=48,depth=32)
    for x in (2,43):
        for z in (3,25):m.box((x,x+3),(0,6),(z,z+3),0)
    m.box((0,48),(4,7),(27,30),1)
    m.box((1,3),(4,7),(0,30),1);m.box((45,47),(4,7),(0,30),1)
    m.box((0,48),(7,8),(0,30),2)
    for z in (3,11,19,27):m.box((0,48),(7,8),(z,z+1),3)
    return m


def model(position,sprite):
    if position in (1,2,4,5):
        m=stool();targets=[(66,74,107),(230,214,173),(181,222,189) if position in (1,4) else (247,222,189),
                           (123,189,165) if position in (1,4) else (214,156,107)]
        colors=[native(sprite,rgb) for rgb in targets];return m.occ,np.asarray(colors,np.uint8)[m.paint]
    if position in (3,6,9,14):
        m=cabinet();layer=16 if position in (3,6) else 0
        colors=[native(sprite,rgb,8 if layer else 0) for rgb in [(66,74,107),(214,181,115),(214,181,115),(239,206,165)]]
        return m.occ[layer:layer+16],np.asarray(colors,np.uint8)[m.paint[layer:layer+16]]
    if position in (7,8,15,16):
        m=game_table();x=16 if position in (8,16) else 0;z=16 if position in (15,16) else 0
        colors=[native(sprite,rgb) for rgb in [(66,74,107),(239,239,247),(107,132,206),(156,173,222)]]
        return m.occ[:,z:z+16,x:x+16],np.asarray(colors,np.uint8)[m.paint[:,z:z+16,x:x+16]]
    slots={10:(1,0),11:(2,0),12:(0,0),13:(1,0),17:(0,1),18:(1,1),19:(2,1),20:(0,1),21:(2,1)}
    x,z=(n*16 for n in slots[position]);m=counter()
    colors=[native(sprite,rgb) for rgb in [(66,74,107),(173,140,82),(222,189,123),(247,214,165)]]
    return m.occ[:,z:z+16,x:x+16],np.asarray(colors,np.uint8)[m.paint[:,z:z+16,x:x+16]]


def reason(position):
    role=ROLES[position]
    if position in (1,2,4,5):return f'{role}: source cushion and dark frame now form a raised seat on four legs, with the native green/peach color and existing seat interaction. Remove the old flat dark backing plate; two exact source occurrences remain unchanged.'
    if position in (3,6,9,14):return f'{role}: one grounded 30-voxel slatted cupboard is divided at the existing vertical pair. Continuous cabinet sides, recessed slats and attached cap replace the chunky folded panel. Material comes only from the lower source wood region, excluding the room-yellow cornice.'
    if position in (7,8,15,16):return f'{role}: quarter of a complete rounded 32x32 blue game table, with a continuous pale border, thin blue inset, apron and four corner legs. Both actual two-by-two table placements join exactly; no stretched upright tabletop or floor backing.'
    if position==23:return 'Retain the existing rear doorway: two dark side posts and an attached lintel leave a clear central opening over the low tan threshold. The source is WARP114 and existing walk:true keeps the arch decorative; source/map and all three geometry projections show no surrounding wall extrusion.'
    return f'{role}: source position is a depth section of the continuous wooden service counter. Joined eight-voxel planked top, front apron and end supports replace detached strips and irregular legs. Raw COUNTER144 pieces retain one-block talk-through collision through the explicit fitted-counter opt-in; SOLID pieces retain normal source kinds.'


def generate(table,art):
    evidence=review.read(STUDY/'source-evidence.json');rows=[];patch={};metadata=[]
    assert {r['position'] for r in evidence}==set(ROLES)
    for source in evidence:
        key,n=source['key'],source['position'];old=table['cells'][key]
        sprite=art.cell(key,'BAKED')
        assert hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest()==source['source_pixel_sha256']
        if n!=23:
            occ,paint=model(n,sprite);new=encode(occ,paint,old,source='hand-emerald-mossdeep-corner',trim=True)
            if n in (18,19,20):
                assert all(o['collision']==144 for o in source['occurrences'])
                new['counter']=True
                metadata.append({'key':key,'field':'counter','before':{k:v for k,v in old.items() if k not in review.GEOMETRY},
                    'after':{k:v for k,v in new.items() if k not in review.GEOMETRY},
                    'reason':'Exact source COUNTER144 requires the opt-in for the reviewed <=8-voxel counter to appear; native collision/talk-through remains one block.',
                    'occurrences':source['occurrences'],'source_pixel_sha256':source['source_pixel_sha256']})
            patch[key]=new
        rows.append({'key':key,'kind':'cell','group':GROUP,'source_ordinal':n,'label':ROLES[n],
                     'family':'game-room furniture','decision':'changed' if n!=23 else 'retained',
                     'baseline_entry_sha256':review.entry_hash(old),'proposed_entry_sha256':review.entry_hash(patch.get(key,old)),
                     'source_pixel_sha256':source['source_pixel_sha256'],'occurrences':art.locations[key],
                     'reason':reason(n),'evidence':[str(STUDY/'source-evidence.json'),str(STUDY/'before-angles-01.png'),str(STUDY/'before-angles-02.png'),str(OUT/'inspection')],
                     'visual_status':'Source, actual map and local complete objects inspected; Minecraft comparison pending'})
    return patch,rows,metadata


def assemblies(art):
    grid=art.map_cells(art.maps[MAP]);result=[]
    specs=[('left_table',[1,5,3,3],[(0,0)]),('right_table',[7,5,3,3],[(2,0)]),
           ('left_counter',[2,1,3,3],[(1,0),(2,0)]),('right_counter',[6,1,3,3],[(0,0),(1,0)])]
    for name,(x,y,w,d),empty in specs:
        layout=[row[x:x+w] for row in grid[y:y+d]];physical=copy.deepcopy(layout)
        for xx,zz in empty:physical[zz][xx]=None
        placements=[]
        for zz,row in enumerate(physical):
            for xx,key in enumerate(row):
                if not key:continue
                offset=[xx,0,zz]
                if 'counter' in name and zz==0:offset=[xx,1,1]
                placements.append({'source':[xx,zz],'offset':offset})
        result.append({'id':'emerald_mossdeep_corner_'+name,'kind':'complete source furniture assembly','note':'Actual Mossdeep game-room source adjacency; native walls outside the furniture are reference only.',
                       'map':MAP,'rect':[x,y,w,d],'source_layout':layout,'model_layout':physical,'placements':placements})
    return result


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args()
    table=review.read(review.BASELINE);art=c.EmeraldArt(c.BUNDLE);patch,rows,metadata=generate(table,art)
    current=copy.deepcopy(table);current['cells'].update(patch);args.out.mkdir(parents=True,exist_ok=True)
    write_json(args.out/'updates.json',{'cells':patch,'buildings':{}});write_json(args.out/'ledger.json',rows);write_json(args.out/'metadata-evidence.json',metadata)
    target=args.out/'assemblies';target.mkdir(exist_ok=True)
    specs=assemblies(art);entities=[c.assembly_evidence(s,current,art,target) for s in specs]
    proof_path=args.out/'runtime-proof.json'
    if proof_path.exists():
        proof=review.read(proof_path)
        for entity in entities:
            matches=[r for r in proof['occurrences'] if r['id']==entity['id']]
            if not matches:continue
            assert len(matches)==1
            row=matches[0]
            entity['baseline_native_fragments']={'evidence':proof_path.resolve().relative_to(review.ROOT).as_posix(),
                'evidence_sha256':hashlib.sha256(proof_path.read_bytes()).hexdigest(),'baseline_sha256':proof['original_baseline_sha256'],
                'occurrence':{k:row[k] for k in ('map','x','y')},'source_layout':row['layout']}
    from tools.shape_fit import hand_check
    for spec,entity in zip(specs,entities):
        occ=np.zeros((32,48,48),bool);rgb=np.zeros((*occ.shape,3),np.uint8)
        for place in spec['placements']:
            x,z=place['source'];key=entity['layout'][z][x];o,p=decode(current['cells'][key]);xx,yy,zz=[n*16 for n in place['offset']]
            occ[yy:yy+len(o),zz:zz+16,xx:xx+16]=o
            rgb[yy:yy+len(o),zz:zz+16,xx:xx+16]=np.asarray(art.cell(key,'BAKED').convert('RGB'))[p//16,p%16]
        hand_check.picture(occ,rgb,target/(spec['id']+'-angled.png'),6)
    write_json(args.out/'entities.json',entities);write_json(args.out/'assemblies.json',specs)
    inspection=args.out/'inspection';inspection.mkdir(exist_ok=True)
    for row in rows:render(current['cells'][row['key']],art.cell(row['key'],'BAKED'),inspection/str(row['source_ordinal']))
    images=[]
    for row in rows:
        n=row['source_ordinal'];images.append((n,art.cell(row['key'],'BAKED'),Image.open(inspection/str(n)/'angled.png')))
    for start in range(0,len(images),12):
        sheet=Image.new('RGB',(1200,900),'#e8ecef');draw=ImageDraw.Draw(sheet)
        for i,(n,sprite,angled) in enumerate(images[start:start+12]):
            x=i%4*300;y=i//4*300;draw.text((x+8,y+8),f'{n:02d} {ROLES[n]}',fill='black');sheet.paste(sprite.resize((80,80),Image.Resampling.NEAREST),(x+8,y+33))
            angled=angled.convert('RGBA');angled.thumbnail((200,220));sheet.paste(angled,(x+95,y+50),angled)
        sheet.save(inspection/f'contact-{start//12+1:02d}.png')
    print(f'{len(rows)} decisions; {len(patch)} changes; {len(metadata)} exact counter flags; {len(entities)} source assemblies')


if __name__=='__main__':main()
