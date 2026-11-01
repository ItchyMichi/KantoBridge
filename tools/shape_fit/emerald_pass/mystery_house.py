"""Mystery Events House furniture; native concealed-stair story cells are preserved."""
from __future__ import annotations
import argparse,copy,hashlib
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import write_json
from tools.shape_fit.blue_pass.common import Model,encode,decode,render
from tools.shape_fit.emerald_pass import building_interiors as c,review,shared_final
from tools.shape_fit.emerald_pass.day_care import native

OUT=c.OUT.parent/'mystery-house';STUDY=c.OUT.parent/'small-facilities-study/mystery_events_house'
ROLES={1:'west native stair wall',2:'east native stair wall',3:'west orange chair',4:'east orange chair',5:'basement plant crown',7:'yellow table northwest',8:'yellow table northeast',10:'basement plant pot',11:'yellow table southwest',12:'glazed case west projection',13:'glazed case east projection',14:'concealed stair west crown',16:'concealed stair east crown'}
RETAIN={1,2,12,13,14,16}


def plant():
    m=Model(height=32);m.cylinder((8,8),4.2,(0,7),0);m.cylinder((8,8),5,(6,9),1);m.cylinder((8,8),3.6,(8,9),2)
    m.cylinder((8,8),1.4,(8,21),3);m.ellipsoid((8,24,8),(6.5,7,6.5),4)
    for x,z in ((4,5),(11,5),(4,11),(11,11)):m.ellipsoid((x,24,z),(3,4,3),5)
    m.ellipsoid((7,28,7),(4,3,4),6)
    return m


def model(n,sprite,old):
    if n in (7,8,11):return shared_final.yellow_table(sprite,{7:shared_final.YELLOW[0][0],8:shared_final.YELLOW[0][1],11:shared_final.YELLOW[1][0]}[n])
    if n in (5,10):
        m=plant();start=16 if n==5 else 0;colors=[native(sprite,t) for t in [(173,173,156),(239,239,214),(66,74,107),(148,132,49),(140,173,74),(173,214,115),(214,247,173)]]
        return m.occ[start:start+16],np.asarray(colors,np.uint8)[m.paint[start:start+16]]
    m=Model()
    for x in (3,11):
        for z in (3,11):m.box((x,x+2),(0,7),(z,z+2),0)
    m.box((2,14),(6,8),(2,14),0);m.box((3,13),(8,9),(3,13),1)
    m.box((2,4),(8,16),(2,4),0);m.box((12,14),(8,16),(2,4),0);m.box((4,12),(11,16),(2,4),1)
    turns=1 if n==3 else 3;m.occ=np.rot90(m.occ,turns,axes=(1,2)).copy();m.paint=np.rot90(m.paint,turns,axes=(1,2)).copy();colors=[native(sprite,t,(0,0,16,14)) for t in [(189,90,49),(255,173,115)]]
    return m.occ,np.asarray(colors,np.uint8)[m.paint]


def reason(n):
    if n in (1,2):return 'Retain the native basement stair-side wall: the exact COUNTER144 cell intentionally keeps one collision block and its original pale wall/yellow jamb art. The existing simple wall volume is coherent at this boundary; do not turn it into a plant, enable a fitted counter flag, or change stair collision.'
    if n in (12,13):return 'Retain this WALK lower glazed-case source projection as a reference component: no walk/on flag means it is not an independent upright unit. Exact complete template emerald_object_generic_glazed_f1eab86bb210 at1F(9,1) renders the grounded cabinet from its northern SOLID row and excludes this native floor row; appliance runtime proof covers the whole object.'
    if n in (14,16):return 'Retain the source-only rounded green crown over the concealed stair wall. Actual G3Built keeps this native one-block COUNTER144; exported same-pass jamb alternatives are not emitted as G3StoryCells overlays. The center and lower plant states use the existing native story route. This clean leaf-colored reference crown has no floor backing; preserve all actual behavior without a fitted-counter or stack amendment.'
    if n in (3,4):return ROLES[n]+': a raised orange cushion, upright back and four wooden legs facing the actual dining table. Preserve seat:true and WALK collision; remove the flattened floor-backed chair mat.'
    if n in (5,10):return 'One complete basement potted shrub: rounded lobed native-green crown, connected thin ochre trunk, soil and white-rimmed gray pot. Preserve the actual WALK upper on→SOLID lower pair; remove the old broad carpet-backed box and stacked gray strips.'
    return ROLES[n]+': reproduce the already reviewed shared yellow-table geometry for the three local source variants. A thin yellow top and four end legs join the shared southeast quarter exactly, replacing three independent solid cubes and false stairs.'


def generate(table,art):
    source=[r for r in review.read(STUDY/'source-evidence.json') if r['position']!=15];assert {r['position'] for r in source}==set(ROLES);patch={};rows=[]
    for s in source:
        n,key=s['position'],s['key'];old=table['cells'][key];sprite=art.cell(key,'BAKED');assert hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest()==s['source_pixel_sha256']
        if n not in RETAIN:
            o,p=model(n,sprite,old);patch[key]=encode(o,p,old,source='hand-emerald-mystery-house',trim=True)
        rows.append({'key':key,'kind':'cell','group':'building__mystery_events_house','source_ordinal':n,'label':ROLES[n],'family':'Mystery Events House source furniture and story boundary','decision':'retained' if n in RETAIN else 'changed','baseline_entry_sha256':review.entry_hash(old),'proposed_entry_sha256':review.entry_hash(patch.get(key,old)),'source_pixel_sha256':s['source_pixel_sha256'],'occurrences':art.locations[key],'reason':reason(n),'evidence':[str(STUDY/'source-evidence.json'),str(STUDY/'story-runtime-diagnostic.json'),str(OUT/'inspection')],'visual_status':'Source and original model inspected; proposed runtime and local review pending'})
    return patch,rows


def assemblies(art):
    result=[]
    for label,name,rect,stack in [('dining','EM_SOOTOPOLIS_CITY_MYSTERY_EVENTS_HOUSE_1F',[6,4,4,2],False),('basement_plant','EM_SOOTOPOLIS_CITY_MYSTERY_EVENTS_HOUSE_B1F',[11,7,1,2],True),('basement_stair','EM_SOOTOPOLIS_CITY_MYSTERY_EVENTS_HOUSE_B1F',[2,0,3,2],False),('concealed_stair','EM_SOOTOPOLIS_CITY_MYSTERY_EVENTS_HOUSE_1F',[2,0,3,3],False),('glazed_case','EM_SOOTOPOLIS_CITY_MYSTERY_EVENTS_HOUSE_1F',[9,1,2,2],False)]:
        grid=art.map_cells(art.maps[name]);x,y,w,d=rect;layout=[r[x:x+w] for r in grid[y:y+d]];places=[{'source':[xx,zz],'offset':[xx,d-1-zz if stack else 0,d-1 if stack else zz]} for zz,row in enumerate(layout) for xx,key in enumerate(row)]
        result.append({'id':'emerald_mystery_house_'+label,'kind':'actual source furniture interface','map':name,'rect':rect,'source_layout':layout,'model_layout':layout,'placements':places,'note':'Exact source context; concealed-stair and glazed-case contexts require actual native/story/template proof, not automatic visual stack inference.'})
    return result


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args();art=c.EmeraldArt(c.BUNDLE);table=review.read(review.BASELINE);patch,rows=generate(table,art);current=review.read(review.ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json');current['cells'].update(patch);args.out.mkdir(exist_ok=True,parents=True);write_json(args.out/'updates.json',{'cells':patch,'buildings':{}});write_json(args.out/'ledger.json',rows);write_json(args.out/'source-evidence.json',review.read(STUDY/'source-evidence.json'))
    target=args.out/'assemblies';target.mkdir(exist_ok=True);specs=assemblies(art);write_json(args.out/'assembly-source-contexts.json',specs)
    # Story-state and exact whole-template interfaces are reviewed separately; do
    # not fabricate an isolated-cell gallery assembly for their inactive raw art.
    registered=[s for s in specs if s['id'].endswith(('dining','basement_plant'))];entities=[c.assembly_evidence(s,current,art,target) for s in registered]
    from tools.shape_fit import hand_check
    for spec,entity in zip(registered,entities):
        places=spec['placements'];w=max(p['offset'][0]+1 for p in places)*16;d=max(p['offset'][2]+1 for p in places)*16;h=max(p['offset'][1]+1 for p in places)*16;occ=np.zeros((h,d,w),bool);rgb=np.zeros((*occ.shape,3),np.uint8)
        for place in places:
            x,z=place['source'];key=entity['layout'][z][x];o,p=decode(current['cells'][key]);xx,yy,zz=[v*16 for v in place['offset']];occ[yy:yy+len(o),zz:zz+16,xx:xx+16]=o;rgb[yy:yy+len(o),zz:zz+16,xx:xx+16]=np.asarray(art.cell(key,'BAKED').convert('RGB'))[p//16,p%16]
        hand_check.picture(occ,rgb,target/(spec['id']+'-angled.png'),5)
    proof_path=args.out/'runtime-proof.json'
    if proof_path.exists():
        proof=review.read(proof_path)
        for entity in entities:
            match=next((r for r in proof['occurrences'] if r['id']==entity['id']),None)
            if match:entity['baseline_native_fragments']={'evidence':proof_path.relative_to(review.ROOT).as_posix(),'evidence_sha256':hashlib.sha256(proof_path.read_bytes()).hexdigest(),'baseline_sha256':proof['original_baseline_sha256'],'occurrence':{k:match[k] for k in ('map','x','y')},'source_layout':match['layout']}
    write_json(args.out/'entities.json',entities);inspection=args.out/'inspection';inspection.mkdir(exist_ok=True)
    for r in rows:render(current['cells'][r['key']],art.cell(r['key'],'BAKED'),inspection/str(r['source_ordinal']))
    for start in range(0,len(rows),12):
        sheet=Image.new('RGB',(1200,900),'#e8ecef');draw=ImageDraw.Draw(sheet)
        for i,r in enumerate(rows[start:start+12]):
            n=r['source_ordinal'];x=i%4*300;y=i//4*300;draw.text((x+8,y+8),f'{n} {ROLES[n]} ({r["decision"]})',fill='black');sheet.paste(art.cell(r['key'],'BAKED').resize((80,80),Image.Resampling.NEAREST),(x+8,y+33));im=Image.open(inspection/str(n)/'angled.png').convert('RGBA');im.thumbnail((200,220));sheet.paste(im,(x+95,y+50),im)
        sheet.save(inspection/f'contact-{start//12+1:02}.png')
    print(f'{len(rows)} decisions, {len(patch)} changes; native story diagnostic pending')


if __name__=='__main__':main()
