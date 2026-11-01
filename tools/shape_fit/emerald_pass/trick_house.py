"""Source-reviewed Trick House wall kits, scroll stands and native puzzle projections."""
from __future__ import annotations
import hashlib
import copy
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import write_json
from tools.shape_fit.blue_pass.common import Model,encode,decode,render
from tools.shape_fit.emerald_pass import building_interiors as c,review
from tools.shape_fit.emerald_pass.day_care import native
from tools import model_gallery as gallery
from tools.shape_fit.emerald_pass.qa10_wholes import canonical_hash

OUT=c.OUT.parent/'trick-house'
STUDY=c.OUT.parent/'small-facilities-study/trick_house_puzzle'
RETAIN={1,2,4,5,28,29,36}
FLOORS={7,8,18,19,20,21,22,23,24,25,26,46,47,48,49}
JOINS={31:'S',32:'E',33:'EW',34:'W',35:'',37:'NS',38:'ES',39:'SW',40:'NES',41:'N',42:'NE',43:'NW',44:'ESW',45:'NEW'}
MISSING_WOOD={
 'g3q/10242-10242-10249-10249':('straight board front',17),
 'g3q/10246-10244-10243-10245':('cream T-junction',12),
 'g3q/10246-10245-10243-10245':('cream side junction',12),
 'g3q/10243-10245-10243-10245':('cream north-south run',12),
 'g3q/10243-10245-10253-10252':('cream cross junction',12),
 'g3q/10243-10245-10243-10252':('cream returned edge',12),
 'g3q/10237-10238-10243-10245':('cream north cap',12),
 'g3q/10257-10258-10247-10250':('lower board front',17),
 'g3q/10243-10241-10239-10242':('cream west lower corner',12),
 'g3q/10241-10245-10242-10240':('cream east lower corner',12),
 'g3q/10257-10259-10247-10249':('lower right knob board',13),
 'g3q/10259-10258-10249-10250':('lower left knob board',14),
}
LABELS={1:'west exit lintel wall',2:'east exit lintel wall',3:'upper gold wall pillar',4:'west exit jamb',5:'east exit jamb',6:'lower gold wall pillar',7:'west entrance warp tread',8:'east entrance warp tread',9:'code-scroll lectern',10:'round gray boulder',11:'lit gray boulder',12:'wood partition cap',13:'right-hand knob wall',14:'left-hand knob wall',15:'wood wall front',16:'wood partition rear cap',17:'wood wall edge',18:'red gate west top projection',19:'red gate east top projection',20:'blue gate west top projection',21:'blue gate east top projection',22:'open red gate west story projection',23:'open red gate east story projection',24:'blue gate west lower projection',25:'blue gate east lower projection',26:'round gold puzzle floor button',27:'gold-floor gray boulder',28:'center exit lintel',29:'native locked story door',30:'alternate code-scroll lectern',36:'native south ledge floor',46:'yellow puzzle floor button',47:'blue puzzle floor button',48:'green puzzle floor button',49:'pink puzzle floor button'}
LABELS.update({n:'gold maze '+(joins+' junction' if joins else 'isolated post') for n,joins in JOINS.items()})


def plane(sprite):
    m=Model();m.occ[0]=True;m.paint[0]=np.arange(256,dtype=np.uint8).reshape(16,16);return m


def gold_wall(sprite,joins):
    """Explicit supported junction body and chamfered cap; top art stays on top."""
    m=Model();dark=native(sprite,(173,132,49),(2,2,14,14));gold=native(sprite,(222,181,82),(2,2,14,14))
    x0=0 if 'W' in joins else 2;x1=16 if 'E' in joins else 14
    z0=0 if 'N' in joins else 2;z1=16 if 'S' in joins else 14
    m.box((x0,x1),(0,14),(z0,z1),gold)
    m.box((x0,x1),(0,2),(z0,z1),dark)
    m.box((max(0,x0-1),min(16,x1+1)),(14,16),(max(0,z0-1),min(16,z1+1)),gold)
    _,zz,xx=np.indices(m.occ.shape);top=m.occ[15];m.paint[15][top]=(zz[15]*16+xx[15])[top]
    return m


def wooden_wall(sprite,n):
    m=Model();wood=native(sprite,(181,132,49));light=native(sprite,(255,239,165));dark=native(sprite,(132,90,24));gray=native(sprite,(132,132,148))
    colors=np.asarray(sprite.convert('RGB'))
    # Pure cream-cap variants contain tiny green floor corners, not wood swatches.
    for role in ('wood','dark'):
        address=wood if role=='wood' else dark;rgb=colors[address//16,address%16]
        if rgb[1]>rgb[0] or rgb[2]>rgb[0]:
            if role=='wood':wood=light
            else:dark=light
    m.box((0,16),(0,13),(0,15),wood);m.box((0,16),(13,16),(0,16),light);m.box((0,16),(0,2),(0,15),dark)
    # Board stiles are actual wood, not extruded sprite-outline steps.
    for x in (1,5,9,13):m.box((x,x+1),(2,12),(14,15),native(sprite,(214,165,74)))
    if n in (13,14):
        x=11 if n==13 else 4;m.ellipsoid((x,6,15),(2,2,1),gray);m.ellipsoid((x,6,15.5),(.8,.8,.5),dark)
    if n in (12,16):m.box((0,16),(2,13),(14,15),light)
    return m


def pillar(sprite,n):
    m=Model(height=32);gold=native(sprite,(173,132,49),(0,0,7,16));light=native(sprite,(222,198,82),(0,0,7,16));wall=native(sprite,(222,206,132),(8,6,16,15))
    m.box((6,16),(0,32),(2,8),wall);m.box((1,6),(0,32),(1,10),gold);m.box((2,5),(2,30),(9,10),light)
    m.box((0,7),(0,3),(0,11),light);m.box((0,7),(30,32),(0,11),light)
    start=16 if n==3 else 0;return m.occ[start:start+16],m.paint[start:start+16]


def boulder(sprite):
    m=Model();gray=native(sprite,(140,140,140),(4,3,12,13));dark=native(sprite,(74,82,115),(4,2,12,8));light=native(sprite,(222,222,206),(2,4,8,13))
    # Deliberate rounded tiers form a faceted stone, with no grass/soil backing.
    m.ellipsoid((8,6.5,8),(6.6,7,6.3),gray);m.box((4,12),(0,3),(4,12),gray)
    yy,zz,xx=np.indices(m.occ.shape);m.paint[m.occ&(yy>9)&(xx+zz>15)]=dark;m.paint[m.occ&(yy>5)&(xx<5)&(zz>6)]=light
    return m


def lectern(sprite):
    m=Model();gray=native(sprite,(140,140,140));dark=native(sprite,(66,74,107));white=native(sprite,(255,255,255),(2,2,13,14))
    for x in (3,11):
        for z in (3,11):m.box((x,x+2),(0,5),(z,z+2),dark)
    m.box((2,14),(4,6),(2,14),gray);m.box((3,13),(6,10),(4,12),dark)
    m.box((4,12),(10,11),(3,13),gray);m.box((5,10),(11,12),(4,12),white)
    m.box((4,11),(11,13),(3,5),white);m.box((4,11),(11,13),(11,13),white)
    for z in (6,8,10):m.box((6,9),(11,12),(z,z+1),gray)
    return m


def reason(n):
    if n in (1,2,4,5,28):return 'Retain this continuous source exit-wall section: its existing full supported wall mass carries the native white cornice, checker wall and dark/gold jamb. Source maps place these five pieces together around the locked exit; they are structural boundaries rather than freestanding furniture.'
    if n==29:return 'Retain the gray locked exit leaf as native story art. G3StoryCells controls its pass/height/full-slot state; changing this fitted reference alone would not replace the live closed leaf. Existing grounded panel remains a truthful source reference.'
    if n==36:return 'Retain the existing supported low ledge slab. Raw163 is a native ledge route, and the horizontal green/ochre edge belongs to the source floor rather than a tall furniture face.'
    if n in (3,6):return 'One explicit32-high gold wall pillar with connected shaft, narrow highlight, capital and grounded foot plus its adjacent wall strip; slice the true upper-on-lower pair. Remove the old stepped source extrusion while preserving the exact on link.'
    if n in (9,30):return 'A supported code-scroll stand: four short gray feet, broad tray, dark support, paper sheet and two raised rolled edges. Native white/gray paper materials exclude the green carpet formerly wrapped around the cube.'
    if n in (10,11,27):return 'Explicit rounded gray rock with a grounded central foot, dark upper facet and small native highlight. Remove the repeated horizontal sprite-extrusion shelves and surrounding floor pixels; keep each native gray palette variant.'
    if n in JOINS:return 'Grounded gold maze '+(JOINS[n]+' junction' if JOINS[n] else 'isolated post')+' with supported body, low base and continuous cap. Open source-neighbor edges reach the cell boundary; closed edges are inset. Keep diagonal source gold pattern on the top and use native gold material on vertical faces instead of extruding its outline into steps.'
    if n in FLOORS:
        if n in (22,23):return 'Exact open story-state side projection (metatile592/593), ten normalized Puzzle3 placements and no raw static occurrence. Keep source metadata untouched and represent its walkable source art as a one-voxel horizontal reference. Native story state rendering/collision remain authoritative; no invented standing door is added.'
        return LABELS[n]+': native horizontal one-voxel source paint. Exact source cells are WALK0 or entranceWARP114 and have no fitted walk flag; retain their existing native floor/story routing. Remove thick catalog floor blocks or detached upright sprite fragments without claiming a runtime standing gate.'
    return LABELS[n]+': coherent supported wood partition body, cream cap, continuous board stiles and source-positioned knob where present. Remove floating side posts, false stairs and sprite-outline shelves. Preserve all source matching/collision fields.'


def generate(table,art):
    source=review.read(STUDY/'source-evidence.json');assert {r['position'] for r in source}==set(range(1,50));patch={};rows=[]
    for s in source:
        n,key=s['position'],s['key'];old=table['cells'][key];sprite=art.cell(key,'BAKED');assert hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest()==s['source_pixel_sha256']
        if n not in RETAIN:
            if n in (3,6):o,p=pillar(sprite,n)
            else:
                m=plane(sprite) if n in FLOORS else gold_wall(sprite,JOINS[n]) if n in JOINS else boulder(sprite) if n in (10,11,27) else lectern(sprite) if n in (9,30) else wooden_wall(sprite,n)
                o,p=m.occ,m.paint
            patch[key]=encode(o,p,old,source=old.get('from','hand-emerald-trick-house'),trim=True)
        rows.append({'key':key,'kind':'cell','group':'building__trick_house_puzzle','source_ordinal':n,'label':LABELS[n],'family':'Trick House authored source wall kit and puzzle props','decision':'retained' if n in RETAIN else 'changed','baseline_entry_sha256':review.entry_hash(old),'proposed_entry_sha256':review.entry_hash(patch.get(key,old)),'source_pixel_sha256':s['source_pixel_sha256'],'occurrences':art.locations[key],'reason':reason(n),'evidence':[str(STUDY/'source-evidence.json'),str(OUT/'inspection')],'visual_status':'All original source/model contacts and eight whole puzzle maps inspected; proposed local and actual-map review pending'})
    for i,(key,(label,recipe)) in enumerate(MISSING_WOOD.items(),1001):
        import base64
        assert key not in table['cells'];sprite=art.cell(key,'BAKED');places=art.locations[key];assert places
        for place in places:
            source=art.maps[place['map']];raw=base64.b64decode(source['cells']);assert raw[(place['y']*source['w']+place['x'])*4+2]==7
        m=wooden_wall(sprite,recipe);entry=encode(m.occ,m.paint,{'class':'voxels'},source='hand-emerald-trick-house',trim=True);patch[key]=entry
        rows.append({'key':key,'kind':'cell','group':'building__trick_house_puzzle','source_ordinal':i,'label':label,'family':'missing static wood maze-wall connection','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':review.entry_hash(entry),'source_pixel_sha256':hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest(),'occurrences':places,'reason':'The complete source wood maze has this exact static SOLID7 '+label+' between the reviewed wall ends. No fitted baseline entry exists. Add a supported board/cream-wall piece matching the authored wall kit, preserve source SOLID behavior, and supply actual native original columns for before review; dynamic story door leaves are excluded.','evidence':[str(OUT/'missing-wall-source.png'),str(OUT/'assembly-source-contexts.json')],'visual_status':'Source absence and all raw SOLID occurrences checked; actual runtime/native-before proof pending'})
    return patch,rows


def assemblies(table,art):
    by={r['key']:r['position'] for r in review.read(STUDY/'source-evidence.json')};specs=[]
    cases=[('pillar',2,(3,0,1,2),'pillar'),('entrance',1,(0,21,2,1),'owned'),('gold_upper_turn',2,(3,3,4,3),'maze'),('gold_lower_junctions',2,(0,16,13,5),'maze'),('gold_button_maze',7,(5,5,6,4),'maze'),('wood_partition',1,(1,6,12,2),'wood'),('wood_upper_interface',1,(0,2,12,4),'wood'),('boulder_run',8,(2,2,3,2),'owned')]
    for name,number,rect,mode in cases:
        map_name='EM_ROUTE110_TRICK_HOUSE_PUZZLE'+str(number);source=art.maps[map_name];grid=art.map_cells(source);x,y,w,d=rect;layout=[line[x:x+w] for line in grid[y:y+d]]
        import base64
        raw=base64.b64decode(source['cells']);physical=[]
        for z,row in enumerate(layout):
            physical.append([key if key in table['cells'] and (mode=='wood' and raw[((y+z)*source['w']+x+dx)*4+2]==7 or mode=='maze' and by.get(key) in set(JOINS)|{26,46,47,48,49} or mode in ('owned','pillar') and key in by) else None for dx,key in enumerate(row)])
        places=[{'source':[dx,z],'offset':[dx,(1-z) if mode=='pillar' else 0,1 if mode=='pillar' else z]} for z,row in enumerate(physical) for dx,key in enumerate(row) if key]
        specs.append({'id':'emerald_trick_house_'+name,'kind':'source wall pillar stack' if mode=='pillar' else 'source puzzle wall and prop interface','map':map_name,'rect':list(rect),'source_layout':layout,'model_layout':physical,'placements':places,'note':'Actual exported source arrangement. The explicit pillar on pair is vertical; maze and partition pieces occupy source SOLID footprint. Walkable projection/empty floor cells outside the named components are reference-only. Native story gate leaves are not replaced by this compound.'})
    return specs


def story_projection_evidence(art):
    path=art.bundle/'g3sites/all.json';sites=review.read(path)['sites'];rows=[]
    for n,mid in ((22,592),(23,593)):
        witnesses=[s for s in sites if s['map']=='EM_ROUTE110_TRICK_HOUSE_PUZZLE3' and any(v['metatile']==mid and v['coll']==0 for v in s['states'])]
        assert len(witnesses)==10 and all(s['own']['coll']==7 for s in witnesses)
        rows.append({'source_ordinal':n,'built_metatile':mid,'raw_occurrences':[],'story_sites':witnesses,'finding':'Exact own SOLID leaf has a WALK0 open-state alternative; G3Built selects that walkable source state, while native story overlays preserve the closed state.'})
    return {'sites_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'rows':rows}


def bind_native_wholes(entities,art):
    path=OUT/'runtime-proof.json';runtime=review.read(path);snapshot=OUT/'fitted-proof-current.json'
    if hashlib.sha256((OUT/'updates.json').read_bytes()).hexdigest()!=runtime['candidate_updates_sha256']:raise ValueError('Trick House candidate differs from source proof')
    if hashlib.sha256(snapshot.read_bytes()).hexdigest()!=runtime['current_table_sha256']:raise ValueError('Trick House source snapshot differs')
    table=review.read(snapshot);result=[]
    for old in entities:
        row=copy.deepcopy(next(r for r in runtime['occurrences'] if r['id']==old['id']))
        if 'source_floor_columns' not in row:raise ValueError('Trick House native whole needs proven floor columns')
        layout=row['layout'];common={'occurrence':{k:row[k] for k in ('map','x','y')},'source_layout':layout}
        row['current_source_cell_hashes']={key:canonical_hash(table['cells'].get(key)) for key in sorted({k for line in layout for k in line})}
        row['current_source_template_hashes']=gallery.native_source_template_hashes(table,{'descriptor':common,'record':row},art)
        proof={'original_baseline_sha256':runtime['original_baseline_sha256'],'current_table_sha256':runtime['current_table_sha256'],'parent_runtime_evidence':{'path':path.relative_to(review.ROOT).as_posix(),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()},'occurrences':[row]}
        evidence=OUT/'native-proofs'/(old['id']+'.json');write_json(evidence,proof);common.update(evidence=evidence.relative_to(review.ROOT).as_posix(),evidence_sha256=hashlib.sha256(evidence.read_bytes()).hexdigest())
        entity=copy.deepcopy(old);entity['baseline_native_fragments']=dict(common,baseline_sha256=runtime['original_baseline_sha256']);entity['current_native_fragments']=dict(common,current_table_sha256=runtime['current_table_sha256'],source_cell_hashes=row['current_source_cell_hashes'],source_template_hashes=row['current_source_template_hashes']);entity['note']+=' Gallery placement and floors use exact original/current planner fragments; absent original walls retain their true native columns.';result.append(entity)
    return result


def main():
    art=c.EmeraldArt(c.BUNDLE);table=review.read(review.BASELINE);patch,rows=generate(table,art);OUT.mkdir(exist_ok=True,parents=True)
    write_json(OUT/'updates.json',{'cells':patch,'buildings':{}});write_json(OUT/'ledger.json',rows);write_json(OUT/'source-evidence.json',review.read(STUDY/'source-evidence.json'))
    write_json(OUT/'story-state-source-evidence.json',story_projection_evidence(art))
    current=review.read(review.TABLE);current['cells'].update(patch);specs=assemblies(current,art);directory=OUT/'assemblies';directory.mkdir(exist_ok=True);entities=[]
    from tools.shape_fit import hand_check
    for spec in specs:
        entity=c.assembly_evidence(spec,current,art,directory);entity['occurrences']=[{'map':spec['map'],'x':spec['rect'][0],'y':spec['rect'][1],'palette':'BAKED'}];entities.append(entity)
        places=spec['placements'];w=max(p['offset'][0]+1 for p in places)*16;d=max(p['offset'][2]+1 for p in places)*16;h=max(p['offset'][1]+1 for p in places)*16;occ=np.zeros((h,d,w),bool);rgb=np.zeros((*occ.shape,3),np.uint8)
        for place in places:
            sx,sz=place['source'];key=entity['layout'][sz][sx];o,paint=decode(current['cells'][key]);xx,yy,zz=[v*16 for v in place['offset']];occ[yy:yy+len(o),zz:zz+16,xx:xx+16]=o;rgb[yy:yy+len(o),zz:zz+16,xx:xx+16]=np.asarray(art.cell(key,'BAKED').convert('RGB'))[paint//16,paint%16]
        hand_check.picture(occ,rgb,directory/(spec['id']+'-angled.png'),3)
    if (OUT/'runtime-proof.json').exists() and all('source_floor_columns' in r for r in review.read(OUT/'runtime-proof.json')['occurrences']) and {r['id'] for r in review.read(OUT/'runtime-proof.json')['occurrences']}=={e['id'] for e in entities}:entities=bind_native_wholes(entities,art)
    write_json(OUT/'entities.json',entities);write_json(OUT/'assembly-source-contexts.json',specs)
    inspect=OUT/'inspection';inspect.mkdir(exist_ok=True)
    for r in rows:render(patch[r['key']] if r['key'] in patch else table['cells'][r['key']],art.cell(r['key'],'BAKED'),inspect/str(r['source_ordinal']))
    for start in range(0,len(rows),12):
        sheet=Image.new('RGB',(1200,900),'#e8ecef');draw=ImageDraw.Draw(sheet)
        for i,r in enumerate(rows[start:start+12]):
            n=r['source_ordinal'];x=i%4*300;y=i//4*300;draw.text((x+8,y+8),f'{n} {r["label"]}',fill='black');sheet.paste(art.cell(r['key'],'BAKED').resize((80,80),Image.Resampling.NEAREST),(x+8,y+33));im=Image.open(inspect/str(n)/'angled.png').convert('RGBA');im.thumbnail((200,220));sheet.paste(im,(x+95,y+50),im)
        sheet.save(inspect/f'contact-{start//12+1:02}.png')
    print(f'{len(rows)} decisions, {len(patch)} changes; source-exact whole registration pending')


if __name__=='__main__':main()
