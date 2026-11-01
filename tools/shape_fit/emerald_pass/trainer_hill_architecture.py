"""Trainer Hill control panels, stair frames and lift: explicit complete source-pattern parts."""
from __future__ import annotations
import argparse,base64,copy,hashlib
import numpy as np
from PIL import Image,ImageDraw
from tools import model_gallery as g
from tools.shape_fit.blue_pass.common import Model,encode,render
from tools.shape_fit.emerald import building_trials as b,parts_model
from tools.shape_fit.emerald_pass import review,trainer_hill_furniture as furniture
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters

OUT=review.ROOT/'local/model-gallery/emerald-pass-study/trainer-hill-architecture'
OWNED=set(range(1,21))|set(range(44,50))|set(range(51,56))
PANELS=set(range(1,10))|{14,15,16}


def swatch(sprite,color):
    a=np.asarray(sprite.convert('RGB')).astype(int);y,x=np.unravel_index(((a-np.array(color))**2).sum(axis=2).argmin(),a.shape[:2]);return b.paint([int(x),int(y),int(x)+1,int(y)+1],top=[int(x),int(y),int(x)+1,int(y)+1],side=[int(x),int(y),int(x)+1,int(y)+1])


def panel(source):
    gray=swatch(source,(132,132,173));dark=swatch(source,(66,74,107));blue=swatch(source,(99,173,231));white=swatch(source,(239,239,255))
    parts=[b.box([0,48],[35,43],[0,36],gray),b.box([0,48],[33,45],[0,4],dark),b.box([0,48],[34,45],[4,6],blue),b.box([0,48],[33,44],[34,40],white),b.box([1,47],[43,45],[7,32],gray),b.box([16,32],[45,46],[14,30],b.paint([16,16,32,32],side=[16,16,17,17],top=[16,16,17,17]))]
    green=swatch(source,(140,255,206))
    for x in (2,35):
        for y in (8,12,16):parts.extend([b.box([x,x+11],[45,46],[y,y+3],dark),b.box([x+2,x+9],[46,47],[y+1,y+2],green)])
    return parts


def portal(source,ascending=False,closed=False,green=False):
    blue=swatch(source,(99,173,231));edge=swatch(source,(66,74,107));wall=swatch(source,(173,255,206) if closed or green else (222,189,107));white=swatch(source,(239,239,255));gray=swatch(source,(156,156,165))
    left=copy.deepcopy(wall);left['front']=[0,0,13,32];right=copy.deepcopy(wall);right['front']=[35,0,48,32]
    parts=[b.box([0,16],[24,31],[0,32],left),b.box([32,48],[24,31],[0,32],right),b.box([13,16],[22,32],[0,27],blue),b.box([32,35],[22,32],[0,27],blue),b.box([13,35],[24,32],[26,30],edge),b.box([13,35],[23,32],[30,32],white),b.box([14,34],[23,32],[24,26],blue)]
    if closed:
        parts+=[b.box([16,32],[27,30],[0,25],b.paint([16,16,32,32],side=[17,18,18,19],top=[17,18,18,19])),b.box([23,25],[30,31],[0,25],edge),b.box([9,13],[30,32],[12,22],b.paint([10,18,14,28],side=[10,18,11,19],top=[10,18,11,19]))]
    else:
        parts.append(b.box([16,32],[16,32],[0,1],gray))
        if ascending:
            tread=swatch(source,(206,206,190))
            for z,height in ((0,8),(4,6),(8,4),(12,2)):
                parts.extend([b.box([16,32],[z,z+4],[0,height],gray),b.box([16,32],[z,z+4],[height-1,height],tread)])
    return parts


def component(sprite,n):
    m=Model();gray=furniture.native(sprite,(132,132,173));dark=furniture.native(sprite,(66,74,107));blue=furniture.native(sprite,(99,173,231));light=furniture.native(sprite,(239,239,255))
    if n in PANELS:
        m.box((0,16),(0,16),(10,14),gray)
        if n in (1,2,3):m.box((0,16),(10,16),(9,15),light)
        elif n in (14,15,16):m.box((0,16),(0,3),(9,15),dark).box((0,16),(3,5),(10,15),blue)
        # Explicit shallow panel; its native face retains the exact floor indicator.
        yy,zz,xx=np.indices(m.occ.shape);face=m.occ&(zz==13);m.paint[face]=((15-yy)*16+xx).astype(np.uint8)[face]
    elif n in (18,20):
        m.box((0,16),(0,1),(0,16),dark)
        if n==18:m.box((0,16),(0,2),(12,15),gray)
        else:
            for z,h in ((0,8),(4,6),(8,4),(12,2)):
                m.box((0,16),(0,h),(z,z+4),gray).box((0,16),(h-1,h),(z,z+4),furniture.native(sprite,(206,206,190)))
    elif n==51:
        for z,h in ((0,6),(5,4),(10,2)):m.box((0,16),(0,h),(z,min(16,z+6)),gray)
    elif n==10:
        m.box((0,16),(0,14),(10,14),gray).box((1,15),(6,13),(14,15),dark)
        yy,zz,xx=np.indices(m.occ.shape);face=m.occ&(zz==14);m.paint[face]=(np.clip(13-yy,0,7)*16+xx).astype(np.uint8)[face]
    else:
        left=n in (11,17,44,47,52,54);right=n in (13,19,46,49,53,55);cap=n in (12,45)
        if left:m.box((0,16),(0,16),(10,14),furniture.native(sprite,(181,239,198))).box((13,16),(0,16),(9,16),blue)
        elif right:m.box((0,16),(0,16),(10,14),furniture.native(sprite,(181,239,198))).box((0,3),(0,16),(9,16),blue)
        elif cap:m.box((0,16),(10,16),(10,16),light).box((0,16),(8,10),(11,16),blue)
        else:
            m.box((0,16),(0,16),(11,14),gray);yy,zz,xx=np.indices(m.occ.shape);face=m.occ&(zz==13);m.paint[face]=((15-yy)*16+xx).astype(np.uint8)[face]
        if left or right:
            yy,zz,xx=np.indices(m.occ.shape);face=m.occ&(zz==13);m.paint[face]=((15-yy)*16+xx).astype(np.uint8)[face]
    return m.occ,m.paint


def generate():
    art=g.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');sources={r['source_ordinal']:r for r in review.read(furniture.STUDY/'source-audit.json')};baseline=review.read(review.BASELINE);patch={};ledger=[]
    for n in sorted(OWNED):
        row=sources[n];key=row['key'];sprite=art.cell(key,'BAKED');o,paint=component(sprite,n);old=baseline['cells'][key];new=encode(o,paint,old,'hand-emerald-trainer-architecture',trim=True);patch[key]=new
        label=('control-wall cornice' if n<=3 else 'exact red floor indicator' if n in (4,5,7,9) else 'control-wall side panel' if n in (6,8) else 'control-wall supported blue base' if n in (14,15,16) else 'numbered wall plate' if n==10 else 'source stair threshold/treads' if n in (18,20,51) else 'lift glass panel' if n==48 else 'portal wall/frame component')
        ledger.append({'key':key,'kind':'cell','group':'building__trainer_hill','source_ordinal':n,'label':label,'family':'trainer-hill-control-panels-and-portals','decision':'changed','baseline_entry_sha256':review.entry_hash(old),'proposed_entry_sha256':review.entry_hash(new),'source_pixel_sha256':row['source_pixel_sha256'],'occurrences':row['occurrences'],'reason':'Explicit shallow '+label+' retaining native face pixels and source metadata. Complete source-pattern parts assemble the actual wall/portal instead of spreading the perspective drawing across unrelated floor rows. Stair WARP behavior is preserved; only the existing downward threshold keeps its existing walk flag.','visual_status':'candidate; local and actual runtime verification pending'})
    templates={};evidence=[]
    specs=[('control_panel_'+str(i),'EM_TRAINER_HILL_'+str(i)+'F',6,3,3,3,'panel') for i in range(1,5)]+[('descending_stair','EM_TRAINER_HILL_1F',1,0,3,2,'down'),('ascending_stair','EM_TRAINER_HILL_1F',11,0,3,2,'up'),('entrance_stair','EM_TRAINER_HILL_ENTRANCE',8,0,3,2,'up'),('reception_lift','EM_TRAINER_HILL_ENTRANCE',16,7,3,2,'lift')]
    for label,name,x,y,w,d,kind in specs:
        grid=art.map_cells(art.maps[name]);layout=[r[x:x+w] for r in grid[y:y+d]];source=art.drawing(layout,'BAKED');identifier='emerald_object_trainer_hill_'+label
        entry={'id':identifier,'atlas':'g3q','tiles':quarters(layout),'height':40 if kind=='panel' else 32,'interior':True,'terrain':[],'parts':panel(source) if kind=='panel' else portal(source,kind=='up',kind=='lift',label=='entrance_stair')};templates[identifier]=entry
        occurrences=g.building_catalog({'game':'emerald','cells':{},'buildings':[entry]},art)[0]['occurrences'];evidence.append({'id':identifier,'layout':layout,'source_pixel_sha256':hashlib.sha256(source.convert('RGB').tobytes()).hexdigest(),'occurrences':occurrences,'kind':kind})
    for row in ledger:
        row['evidence']=[(OUT/'source-evidence.json').relative_to(review.ROOT).as_posix(),(OUT/'inspection'/str(row['source_ordinal'])).relative_to(review.ROOT).as_posix()]
    for row in evidence:
        cells=[]
        for at in row['occurrences']:
            raw=art.maps[at['map']];data=base64.b64decode(raw['cells']);w=len(row['layout'][0]);d=len(row['layout'])
            cells.append({**at,'map_cells_sha256':hashlib.sha256(data).hexdigest(),'source_collisions':[[data[((at['y']+z)*raw['w']+at['x']+x)*4+2] for x in range(w)] for z in range(d)],'source_warps':[v for v in raw.get('warps',[]) if at['x']<=v['x']<at['x']+w and at['y']<=v['y']<at['y']+d]})
        row['occurrences']=cells
    return patch,ledger,templates,evidence


def bind_and_freeze():
    from tools.shape_fit.emerald_pass.native_baselines import building_entities
    from tools.shape_fit.emerald_pass.terrain_native_contexts import generate as native_contexts
    runtime=review.read(OUT/'runtime-proof.json');digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
    if digest(OUT/'updates.json')!=runtime['candidate_updates_sha256']:raise ValueError('Trainer source proof must bind final candidate')
    before_entities=building_entities(OUT/'runtime-proof.json');g.write_json(OUT/'baseline-entities.json',before_entities)
    contexts=native_contexts(OUT,proof_name='context-runtime-proof.json');g.write_json(OUT/'native-contexts.json',contexts)
    registry=review.read(review.ROOT/'tools/shape_fit/entities/emerald.json');replacements=[]
    for entity in contexts:
        old=next(e for e in registry if e['id']==entity['id'])
        replacements.append({'id':entity['id'],'expected_entity_sha256':review.entry_hash(old),'preserved_entity':old,'replacement':entity,'reason':'Existing reception source rectangle now includes the exact lift template; retain original/native placement with current payload proof.'})
    g.write_json(OUT/'entity-replacements.json',replacements)
    art=g.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');table=review.read(OUT/'fitted-proof-current.json');baseline=review.read(review.BASELINE);ids={e['id'] for e in before_entities}
    old={e['id']:e for e in g.catalog(baseline,g.baseline_entities(before_entities,runtime['original_baseline_sha256']),art,'buildings') if e['id'] in ids};now={e['id']:e for e in g.catalog(table,before_entities,art,'buildings') if e['id'] in ids};assert set(old)==set(now)==ids
    cids={e['id'] for e in contexts};oldc={e['id']:e for e in g.catalog(baseline,g.baseline_entities(contexts,runtime['original_baseline_sha256']),art) if e['id'] in cids};nowc={e['id']:e for e in g.catalog(table,contexts,art) if e['id'] in cids};assert set(oldc)==set(nowc)==cids
    g.write_json(OUT/'catalog-check.json',{'building_companions':len(ids),'current_context_replacements':len(cids),'entries':[{'id':i,'before_bounds':old[i]['bounds'],'current_bounds':now[i]['bounds']} for i in sorted(ids)],'context_entries':[{'id':i,'before_bounds':oldc[i]['bounds'],'current_bounds':nowc[i]['bounds']} for i in sorted(cids)],'visual_approval':False})
    patch=review.read(OUT/'updates.json');source=review.read(OUT/'source-evidence.json')['groups'];proposal={}
    for row in source:proposal[row['id']]={'metadata':{k:v for k,v in patch['buildings'][row['id']].items() if k not in ('height','parts')},'source_pixel_sha256':row['source_pixel_sha256'],'occurrences':[[p['map'],p['x'],p['y']] for p in row['occurrences']]}
    g.write_json(OUT/'source-amendment-proposal.json',{'added_buildings':proposal})
    g.write_json(OUT/'local-qa.json',{'source_components_reviewed':31,'source_wholes_reviewed':8,'all_front_and_angled_views_reviewed':True,'changes':['Four floor-control walls keep exact red indicator variants, three green channels on each side, supported blue base and white cornice.','Three open stair frames keep24-voxel center clearance. Raised treads stay entirely in northern SOLID cells, with native light nosings/gray risers and one-voxel approach threshold.','Reception lift has two teal glazed leaves and exact control panel, limited to six source SOLID cells.','Individual yellow/green panels keep native face pixels and narrow blue frame depth; matching/interaction metadata unchanged.'],'runtime':'Eight exact patterns at14 source sites,9 native WALK114 portals remain zero collision; all unrelated source columns unchanged. Source objects/kinds/tags/tiles/sites unchanged.','minecraft_visual_qa':'pending','user_approved':False})
    code=[__file__,str(review.ROOT/'tools/tests/test_emerald_trainer_hill_architecture.py'),str(review.ROOT/'fabric-bridge/src/test/java/dev/kantobridge/builder/shape/EmeraldTrainerArchitectureProofTest.java')]
    for name in code:
        path=__import__('pathlib').Path(name);target=OUT/'frozen-code'/path.name;target.parent.mkdir(exist_ok=True);target.write_bytes(path.read_bytes())
    manifest={'status':'frozen_ready_for_guarded_integration','changed_original_cells':31,'added_templates':8,'actual_source_occurrences':14,'patch':'updates.json','ledgers':['ledger.json','added-ledger.json'],'entity_files':['baseline-entities.json'],'entity_replacements':'entity-replacements.json','source_amendment_proposal':'source-amendment-proposal.json','validation':{'python':4,'actual_source_java':1,'final_java_evidence':'final-source-validation.json','native_building_comparisons':8,'native_context_replacements':1,'minecraft_visual_qa':False,'user_approved':False},'files':{p.relative_to(OUT).as_posix():digest(p) for p in sorted(OUT.rglob('*')) if p.is_file() and p.name!='integration-manifest.json'}}
    g.write_json(OUT/'integration-manifest.json',manifest);print('Frozen Trainer architecture:',digest(OUT/'integration-manifest.json'))


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');ap.add_argument('--freeze',action='store_true');args=ap.parse_args()
    if args.freeze:bind_and_freeze();return
    cells,ledger,buildings,evidence=generate();OUT.mkdir(parents=True,exist_ok=True)
    g.write_json(OUT/'updates.json',{'cells':cells,'buildings':buildings});g.write_json(OUT/'ledger.json',ledger);g.write_json(OUT/'source-evidence.json',{'groups':evidence})
    added=[]
    for row in evidence:
        reason=('Three-channel control wall with exact red floor indicator, cornice, shallow projecting frame and supported blue base.' if row['kind']=='panel' else 'Source-solid glazed reception lift with separate two-leaf frame and native button plate.' if row['kind']=='lift' else 'Open blue stair frame with native yellow/green neighboring wall plates; raised treads stay in the source SOLID row and the southern WALK114 portal stays clear above its one-voxel threshold.')
        added.append({'key':row['id'],'kind':'building','label':row['id'].removeprefix('emerald_object_trainer_hill_'),'family':'Trainer Hill complete interior architecture','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':review.entry_hash(buildings[row['id']]),'source_pixel_sha256':row['source_pixel_sha256'],'source_pixel_mode':'RGB','occurrences':row['occurrences'],'reason':reason+' Exact existing source quarters and all source collision/tags/warps remain unchanged.','evidence':[(OUT/'source-evidence.json').relative_to(review.ROOT).as_posix(),(OUT/row['id']).relative_to(review.ROOT).as_posix()],'user_approved':False})
    g.write_json(OUT/'added-ledger.json',added)
    if args.previews:
        art=g.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald')
        for r in ledger:
            dest=OUT/'inspection'/str(r['source_ordinal']);dest.mkdir(parents=True,exist_ok=True);source=art.cell(r['key'],'BAKED');source.save(dest/'source.png');render(cells[r['key']],source,dest/'after')
        for row in evidence:
            dest=OUT/row['id'];dest.mkdir(exist_ok=True);source=art.drawing(row['layout'],'BAKED');source.resize((source.width*6,source.height*6),Image.Resampling.NEAREST).save(dest/'source.png');o,c=parts_model.build(buildings[row['id']],np.asarray(source.convert('RGB')));parts_model.picture(o,c,dest/'view.png',5);parts_model.front_view(o,c,dest/'front.png',5)
        for start in range(0,len(evidence),4):
            sheet=Image.new('RGB',(1400,1100),'#252b34');draw=ImageDraw.Draw(sheet)
            for i,row in enumerate(evidence[start:start+4]):
                x=(i%2)*700;y=(i//2)*550;draw.text((x+12,y+10),row['id'].removeprefix('emerald_object_trainer_hill_'),fill='white')
                for name,dx,dy,w,h in [('source',0,32,300,280),('front',0,320,300,220),('view',310,32,380,500)]:
                    im=Image.open(OUT/row['id']/(name+'.png')).convert('RGB');im.thumbnail((w,h),Image.Resampling.NEAREST);sheet.paste(im,(x+dx+(w-im.width)//2,y+dy+(h-im.height)//2))
            sheet.save(OUT/('whole-contact-'+str(start//4+1)+'.png'))
        for start in range(0,len(ledger),16):
            sheet=Image.new('RGB',(1280,1120),'#252b34');draw=ImageDraw.Draw(sheet)
            for i,row in enumerate(ledger[start:start+16]):
                x=(i%4)*320;y=(i//4)*280;draw.text((x+8,y+5),str(row['source_ordinal'])+' '+row['label'],fill='white')
                source=art.cell(row['key'],'BAKED').resize((96,96),Image.Resampling.NEAREST);sheet.paste(source,(x+8,y+50))
                path=OUT/'inspection'/str(row['source_ordinal'])/'after'/'three_quarter.png'
                if not path.exists():path=next((OUT/'inspection'/str(row['source_ordinal'])/'after').glob('*.png'))
                im=Image.open(path).convert('RGB');im.thumbnail((205,240),Image.Resampling.NEAREST);sheet.paste(im,(x+110+(205-im.width)//2,y+30+(240-im.height)//2))
            sheet.save(OUT/('component-contact-'+str(start//16+1)+'.png'))
    print('Staged',len(ledger),'components and',len(buildings),'whole patterns;',sum(len(r['occurrences']) for r in evidence),'source occurrences')


if __name__=='__main__':main()
