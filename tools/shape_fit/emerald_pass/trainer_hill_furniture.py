"""Trainer Hill reception furniture: explicit counters, recovery tray and supported devices."""
from __future__ import annotations
import argparse,copy,hashlib
import numpy as np
from PIL import Image,ImageDraw
from tools import model_gallery as gallery
from tools.shape_fit.blue_pass.common import Model,encode,render
from tools.shape_fit.emerald_pass import review,center

OUT=review.ROOT/'local/model-gallery/emerald-pass-study/trainer-hill-furniture'
STUDY=OUT.parent/'trainer-hill-interior'
OWNED=set(range(21,44))|{50}|set(range(56,65))
HEAL={22:14,23:15,29:18,30:19}
UPPER={21:31,34:41,42:36,61:63,62:64}
COUNTERS={24,25,26,32,33,35,37,38,39,40,43,50,56,57,58,59,60}
LABELS={21:'medical console upper cross panel',22:'recovery tray northwest',23:'recovery tray northeast',24:'teal counter front',25:'counter white document plate',26:'counter north rounded end',27:'round reception stool',28:'native floor below projected monitor',29:'recovery tray southwest',30:'recovery tray southeast',31:'medical console grounded lower body',32:'counter west rim',33:'counter east rim',34:'terminal display and control panel',35:'counter inner tabletop',36:'upright scanner base',37:'counter east inner bend',38:'counter west inner bend',39:'counter west outside curve',40:'counter east outside curve',41:'terminal pedestal and lower controls',42:'upright scanner upper mast',43:'counter front below scanner',50:'counter south rounded end',56:'counter inside elbow east',57:'counter inside elbow west',58:'counter west return',59:'counter east return',60:'counter registration paper',61:'wide wall monitor upper left',62:'wide wall monitor upper right',63:'wide wall monitor lower left',64:'wide wall monitor lower right'}

def native(sprite,color,area=(0,0,16,16)):
    a=np.asarray(sprite.convert('RGB')).astype(int);d=((a-np.asarray(color))**2).sum(axis=2).astype(float);x0,y0,x1,y1=area;allowed=np.zeros((16,16),bool);allowed[y0:y1,x0:x1]=True;d[~allowed]=np.inf;return int(d.argmin())

def counter(sprite,n):
    m=Model();teal=native(sprite,(82,181,165));edge=native(sprite,(74,82,107));body=native(sprite,(140,140,140))
    m.box((0,16),(0,1),(1,15),edge).box((0,16),(1,6),(1,15),body).box((0,16),(6,7),(0,16),edge).box((0,16),(7,8),(0,16),teal)
    if n in (26,50):
        # Source-specific rounded outer end, with supported apron beneath its top.
        for a in (0,1):
            m.box((0,2-a),(0,8),(0,2-a),cut=True);m.box((14+a,16),(0,8),(0,2-a),cut=True)
        if n==50:m.occ=np.flip(m.occ,axis=1).copy();m.paint=np.flip(m.paint,axis=1).copy()
    if n in (39,40):
        x=(0,2) if n==39 else (14,16);m.box(x,(0,8),(14,16),cut=True)
    if n in (25,60):m.box((3,13),(8,9),(5,11),native(sprite,(255,255,255)))
    return m

def stool(sprite):
    m=Model();dark=native(sprite,(74,82,107));white=native(sprite,(239,247,247));orange=native(sprite,(222,156,107))
    m.cylinder((8,8),4.4,(0,2),dark).cylinder((8,8),2.4,(2,6),dark).cylinder((8,8),6,(6,8),orange).ellipsoid((8,8.3,8),(6,1.8,6),white)
    return m

def equipment(sprite,n):
    m=Model(height=32);white=native(sprite,(239,247,247));gray=native(sprite,(156,156,165));dark=native(sprite,(66,74,99));teal=native(sprite,(82,181,165))
    if n in (21,31):
        m.box((4,15),(0,3),(3,14),dark).box((5,14),(3,24),(4,12),gray).box((5,15),(22,27),(3,13),white)
        m.box((6,14),(14,24),(11,13),white).box((5,15),(10,14),(11,15),dark)
        yy,zz,xx=np.indices(m.occ.shape);face=m.occ&(zz==12)&(yy>=16)&(yy<24)
        m.paint[face]=(np.clip(31-yy,6,15)*16+np.clip(xx+3,8,15)).astype(np.uint8)[face]
        if n==31:
            front=m.occ&(zz==11)&(yy>=4)&(yy<12);m.paint[front]=((15-yy)*16+xx).astype(np.uint8)[front]
    elif n in (34,41):
        m.cylinder((8,8),5,(0,3),dark).cylinder((8,8),3,(3,8),gray)
        m.box((2,14),(7,22),(3,12),gray).box((1,15),(20,27),(3,14),dark).box((2,14),(14,25),(12,14),dark)
        m.box((3,13),(10,14),(11,15),gray)
        yy,zz,xx=np.indices(m.occ.shape)
        if n==34:
            screen=m.occ&(zz==13)&(yy>=16)&(yy<25);m.paint[screen]=(np.clip(28-yy,3,12)*16+np.clip(xx,2,14)).astype(np.uint8)[screen]
        else:
            face=m.occ&(zz>=11)&(yy>=10)&(yy<14);m.paint[face]=((13-yy)*16+xx).astype(np.uint8)[face]
    else:
        # A thin upright reader over its own counter base, not a room-wall slab.
        m.box((0,16),(0,7),(1,15),gray).box((0,16),(7,8),(0,16),teal)
        m.box((5,11),(8,10),(5,12),dark).box((6,10),(9,29),(6,10),gray).box((7,9),(11,30),(9,11),white)
    start=16 if n in (21,34,42) else 0;return m.occ[start:start+16],m.paint[start:start+16]

def wall_monitor(sprite,n):
    m=Model(width=32,height=32);gray=native(sprite,(123,132,140),(0,8,16,16) if n in (61,62) else (0,0,16,9));dark=native(sprite,(66,74,99));light=native(sprite,(189,198,206))
    m.box((5,27),(0,23),(3,5),gray).box((1,31),(6,24),(5,8),dark).box((2,30),(7,23),(8,9),light).box((3,29),(8,22),(9,10),gray)
    x=16 if n in (62,64) else 0;y=16 if n in (61,62) else 0
    return m.occ[y:y+16,:,x:x+16],m.paint[y:y+16,:,x:x+16]

def model(sprite,n):
    if n in COUNTERS:m=counter(sprite,n);return m.occ,m.paint
    if n in HEAL:return center.healing(sprite,HEAL[n])
    if n==27:m=stool(sprite);return m.occ,m.paint
    if n==28:
        m=Model();m.occ[0]=True;_,zz,xx=np.indices(m.occ.shape);m.paint[:]=((10+zz*6//16)*16+xx).astype(np.uint8);return m.occ,m.paint
    if n in (61,62,63,64):return wall_monitor(sprite,n)
    return equipment(sprite,n)

def generate():
    art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');baseline=review.read(review.BASELINE);sources={r['source_ordinal']:r for r in review.read(STUDY/'source-audit.json')};patch={};ledger=[];metadata=[]
    for n in sorted(OWNED):
        row=sources[n];key=row['key'];sprite=art.cell(key,'BAKED');old=baseline['cells'][key];o,p=model(sprite,n);new=encode(o,p,old,'hand-emerald-trainer-hill-furniture',trim=True)
        if n in (25,60):
            assert all(v['collision']==144 for v in row['occurrences']);new['counter']=True
        if n in (61,62):
            lower=sources[UPPER[n]]['key'];assert all(art.map_cells(art.maps[v['map']])[v['y']+1][v['x']]==lower for v in row['occurrences']);new['on']=lower
        if {k:v for k,v in old.items() if k not in review.GEOMETRY}!={k:v for k,v in new.items() if k not in review.GEOMETRY}:
            metadata.append({'key':key,'before':{k:v for k,v in old.items() if k not in review.GEOMETRY},'after':{k:v for k,v in new.items() if k not in review.GEOMETRY},'source_pixel_sha256':row['source_pixel_sha256'],'occurrences':row['occurrences'],'reason':'Exact raw COUNTER144 fitted display opt-in retaining one-block collision/talk-through.' if n in (25,60) else 'Exact SOLID upper/lower source adjacency forms one shallow two-row monitor; existing source wall cornice is omitted from the object.'})
        patch[key]=new
        reason=('One continuous teal reception counter with supported gray apron, a shared one-block top plane and the source-specific rounded return or paper plate.' if n in COUNTERS else 'Slice the complete four-quarter recovery tray, with six recessed positions, supported rails and native gray material.' if n in HEAL else 'Ground the rounded reception seat on a narrow stem and foot; remove the source floor around it.' if n==27 else 'This WALK source is projected monitor/floor; preserve native floor-only routing and use only the unobstructed bottom floor band in its standalone fallback.' if n==28 else 'Slice one shallow supported wide monitor across two existing SOLID columns and two source rows; omit the unrelated upper wall cornice.' if n>=61 else 'Build one supported device across its exact existing upper-to-lower source relation, removing room/floor backing and preserving native control-panel pixels.')
        ledger.append({'key':key,'kind':'cell','group':'building__trainer_hill','source_ordinal':n,'label':LABELS[n],'family':'trainer-hill-reception-furniture','decision':'changed','baseline_entry_sha256':review.entry_hash(old),'proposed_entry_sha256':review.entry_hash(new),'source_pixel_sha256':row['source_pixel_sha256'],'occurrences':row['occurrences'],'reason':reason,'evidence':[str((STUDY/'source-audit.json').relative_to(review.ROOT)),str((STUDY/'EM_TRAINER_HILL_ENTRANCE.png').relative_to(review.ROOT))],'visual_status':'candidate: source reviewed, new local/runtime review pending'})
    return patch,ledger,metadata

def assemblies(art):
    grid=art.map_cells(art.maps['EM_TRAINER_HILL_ENTRANCE']);requests=[]
    for name,rect in [('recovery_tray',(2,8,2,2)),('medical_console',(6,8,1,2)),('terminal',(8,9,1,2)),('scanner',(15,8,1,2)),('wall_monitor',(4,7,2,2)),('west_stools',(1,15,3,1)),('east_stools',(15,15,3,1)),('west_counter',(0,7,9,4)),('east_counter',(10,4,9,7))]:
        x,y,w,d=rect;requests.append({'id':'emerald_trainer_hill_furniture_'+name,'map':'EM_TRAINER_HILL_ENTRANCE','x':x,'y':y,'w':w,'d':d,'layout':[r[x:x+w] for r in grid[y:y+d]],'note':'Exact source-map furniture context; runtime native original/current placement proof is required before registering the comparison.'})
    return requests

def preview_assemblies(patch,art):
    from tools.shape_fit.blue_pass.common import decode
    from tools.shape_fit import hand_check
    requests=assemblies(art);placed=[]
    for row in requests:
        layout=row['layout'];h=64;w=row['w']*16;d=row['d']*16;occ=np.zeros((h,d,w),bool);rgb=np.zeros((h,d,w,3),np.uint8);placements=[]
        for z,line in enumerate(layout):
            for x,key in enumerate(line):
                if key not in patch:continue
                entry=patch[key];yy=0;zz=z
                if entry.get('on') and z+1<len(layout) and layout[z+1][x]==entry['on']:yy=16;zz=z+1
                o,paint=decode(entry);x0=x*16;z0=zz*16;visible=o
                occ[yy:yy+len(o),z0:z0+16,x0:x0+16]|=visible
                block=rgb[yy:yy+len(o),z0:z0+16,x0:x0+16];colors=np.asarray(art.cell(key,'BAKED').convert('RGB'))[paint//16,paint%16];block[visible]=colors[visible]
                placements.append({'source':[x,z],'offset':[x,yy//16,zz]})
        dest=OUT/'assemblies'/row['id'];dest.mkdir(parents=True,exist_ok=True);art.drawing(layout,'BAKED').resize((w*4,d*4),Image.Resampling.NEAREST).save(dest/'source.png');hand_check.picture(occ,rgb,dest/'angled.png',4)
        placed.append(dict(row,authoring_placements=placements,authoring_only=True))
    gallery.write_json(OUT/'assembly-requests.json',placed)

def bind_native():
    from tools.shape_fit.emerald_pass.terrain_native_contexts import generate as native_contexts
    runtime=review.read(OUT/'runtime-proof.json')
    if runtime['candidate_updates_sha256']!=hashlib.sha256((OUT/'updates.json').read_bytes()).hexdigest():raise ValueError('Stale Trainer furniture runtime candidate')
    entities=native_contexts(OUT)
    for entity in entities:
        entity['kind']='actual native Trainer Hill reception furniture context'
        entity['note']='Exact Trainer Hill entrance source rectangle with actual original/current furniture placement, native floors and surrounding columns. Current counter source144 keeps one-block collision and talk-through; the wide monitor is joined by its two exact source on links. Individual and complete-object Minecraft review remains pending.'
    gallery.write_json(OUT/'entities.json',entities)
    art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');table=review.read(OUT/'fitted-proof-current.json');old=review.read(review.BASELINE);ids={e['id'] for e in entities}
    before={e['id']:e for e in gallery.catalog(old,gallery.baseline_entities(entities,runtime['original_baseline_sha256']),art) if e['id'] in ids}
    after={e['id']:e for e in gallery.catalog(table,entities,art) if e['id'] in ids}
    if set(before)!=ids or set(after)!=ids:raise ValueError('Missing actual Trainer furniture context')
    gallery.write_json(OUT/'catalog-check.json',{'entries':[{'id':k,'before_bounds':before[k]['bounds'],'current_bounds':after[k]['bounds'],'before_fragments':len(before[k]['fragments']),'current_fragments':len(after[k]['fragments'])} for k in sorted(ids)],'visual_approval':False})
    return entities

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');ap.add_argument('--bind-native',action='store_true');args=ap.parse_args()
    if args.bind_native:print('Bound',len(bind_native()),'actual original/current furniture contexts');return
    patch,ledger,metadata=generate();OUT.mkdir(exist_ok=True)
    for file,value in [('updates.json',{'cells':patch,'buildings':{}}),('ledger.json',ledger),('metadata-evidence.json',metadata)]:gallery.write_json(OUT/file,value)
    if args.previews:
        art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald')
        for r in ledger:
            folder=OUT/'inspection'/str(r['source_ordinal']);folder.mkdir(parents=True,exist_ok=True);sprite=art.cell(r['key'],'BAKED');sprite.save(folder/'source.png');render(patch[r['key']],sprite,folder/'after')
        for start in range(0,len(ledger),12):
            sheet=Image.new('RGB',(1200,900),'#e8ecef');draw=ImageDraw.Draw(sheet)
            for i,r in enumerate(ledger[start:start+12]):
                x=i%4*300;y=i//4*300;folder=OUT/'inspection'/str(r['source_ordinal']);draw.text((x+8,y+8),f'{r["source_ordinal"]} {r["label"]}',fill='black');sheet.paste(Image.open(folder/'source.png').resize((80,80),Image.Resampling.NEAREST),(x+8,y+35));im=Image.open(folder/'after/angled.png').convert('RGBA');im.thumbnail((205,220));sheet.paste(im,(x+90,y+60),im)
            sheet.save(OUT/f'contact-{start//12+1:02}.png')
        preview_assemblies(patch,art)
    print(len(ledger),'candidate decisions;',len(metadata),'exact proposed metadata transitions; not frozen')

if __name__=='__main__':main()
