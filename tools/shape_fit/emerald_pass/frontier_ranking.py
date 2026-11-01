"""Ranking Hall: supported information terminals, green chairs and one complete trophy case."""
from __future__ import annotations
import argparse,base64,copy,hashlib
import numpy as np
from PIL import Image,ImageDraw
from tools import model_gallery as g
from tools.shape_fit.blue_pass.common import Model,encode,render
from tools.shape_fit.emerald import building_trials as b,parts_model
from tools.shape_fit.emerald_pass import review
from tools.shape_fit.emerald_pass.trainer_hill_furniture import native
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters

OUT=review.ROOT/'local/model-gallery/emerald-pass-study/frontier-ranking'
GROUP='building__battle_frontier_ranking_hall'
MAP='EM_BATTLE_FRONTIER_RANKING_HALL'
RETAINED={13,14,18,19}
CHAIR_TOP={2,3,4,8};CHAIR_BOTTOM={5,6,7,9}
CASE={10:(0,0),11:(1,0),12:(2,0),15:(0,1),16:(1,1),17:(2,1),20:(0,2),21:(1,2),22:(2,2)}
LABELS={1:'blue ranking information terminal',2:'west green chair upper back',3:'pale-floor green chair upper back',4:'short green chair upper frame',5:'west green chair seat and legs',6:'pale-floor green chair seat and legs',7:'short green chair seat and legs',8:'northern green chair upper back',9:'northwest green chair seat and legs',10:'trophy case projected northwest cap',11:'trophy case projected top cap',12:'trophy case projected northeast cap',13:'west direction plaque upper wall',14:'east direction plaque upper wall',15:'trophy case west glass frame',16:'trophy case central glass and shelves',17:'trophy case east glass frame',18:'west direction plaque lower wall',19:'east direction plaque lower wall',20:'trophy case projected southwest foot',21:'trophy case projected front foot',22:'trophy case projected southeast foot'}

def rows():return review.read(OUT.parent/'inventory'/GROUP/'entries.json')

def chair(short=False):
    # One complete chair before slicing its existing source stack. Material
    # indices are remapped to each source drawing after slicing, never to floor.
    m=Model(height=32)
    for x in (3,11):
        for z in (3,11):m.box((x,x+2),(0,9),(z,z+2),0)
    m.box((2,14),(7,10),(2,14),0).box((3,13),(9,12),(3,13),1)
    top=16 if short else 27
    m.box((2,4),(10,top),(2,5),0).box((12,14),(10,top),(2,5),0)
    m.box((3,13),(13,top),(2,5),1).box((4,12),(14,top),(4,6),2)
    m.box((3,13),(top,top+1 if short else top+2),(2,5),1 if short else 0)
    return m

def terminal(sprite):
    m=Model();gray=native(sprite,(148,156,189));dark=native(sprite,(74,82,123));white=native(sprite,(239,247,255))
    m.box((2,14),(0,2),(3,14),dark).box((4,12),(2,5),(5,11),gray)
    m.box((2,14),(4,15),(4,10),gray).box((3,13),(7,14),(10,11),dark)
    m.box((4,12),(8,13),(11,12),white).box((2,14),(4,6),(9,14),gray)
    yy,zz,xx=np.indices(m.occ.shape);face=m.occ&(zz==11)&(yy>=8)&(yy<13)
    m.paint[face]=((2+(12-yy)*7//5)*16+np.clip(xx,4,11)).astype(np.uint8)[face]
    for x in (4,7,10):m.box((x,x+1),(6,7),(11,12),dark)
    return m

def swatch(source,color):
    a=np.asarray(source.convert('RGB')).astype(int);y,x=np.unravel_index(((a-np.array(color))**2).sum(axis=2).argmin(),a.shape[:2]);return b.paint([int(x),int(y),int(x)+1,int(y)+1])

def case_parts(source):
    gray=swatch(source,(148,156,189));dark=swatch(source,(74,82,123));pale=swatch(source,(222,231,255));blue=swatch(source,(115,198,247))
    # Only the central source row is SOLID. WALK projection rows retain their
    # actual marble floors. All physical parts stay on the three real carriers.
    parts=[b.box([3,45],[17,31],[0,2],dark),b.box([4,44],[18,30],[2,5],gray),b.box([3,45],[17,31],[14,16],pale)]
    for x in (3,43):parts.append(b.box([x,x+2],[17,31],[4,14],dark))
    parts += [b.box([5,43],[17,19],[5,14],gray),b.box([5,43],[29,30],[5,14],blue),b.box([5,43],[19,29],[5,6],pale),b.box([5,43],[19,29],[9,10],pale)]
    # A recessed blue display face carries the case's native two-pane details;
    # physical shelf rails and mullions prevent an opaque full-depth extrusion.
    parts += [b.box([16,32],[30,31],[6,13],b.paint([16,16,32,32],side=[19,20,20,21],top=[19,20,20,21])),b.box([23,25],[30,32],[5,14],dark),b.box([5,43],[30,32],[9,10],pale)]
    return parts

def component(sprite,n):
    if n==1:return terminal(sprite)
    if n in CHAIR_TOP|CHAIR_BOTTOM:
        full=chair(n in (4,7));start=16 if n in CHAIR_TOP else 0;m=Model();m.occ=full.occ[start:start+16].copy();kinds=full.paint[start:start+16]
        # The short upper sprite is floor/frame only; its back cushion lies
        # entirely in the lower sprite, so its upper physical frame stays gray.
        shades=[native(sprite,(74,82,107)),native(sprite,(74,173,99)),native(sprite,(99,222,107))]
        for i,c in enumerate(shades):m.paint[kinds==i]=c
        return m
    m=Model();gray=native(sprite,(148,156,189));dark=native(sprite,(74,82,123))
    if n in (10,11,12,20,21,22):
        # Diagnostic only: the actual WALK route renders its source floor.
        m.box((0,16),(0,1),(0,16));_,z,x=np.indices(m.occ.shape);m.paint[m.occ]=(z*16+x).astype(np.uint8)[m.occ]
    else:
        m.box((0,16),(0,3),(1,15),dark).box((0,16),(3,13),(2,14),gray).box((0,16),(13,16),(1,15),gray)
        yy,zz,xx=np.indices(m.occ.shape);front=m.occ&(zz==13)&(yy>=3)&(yy<13);m.paint[front]=(np.clip(15-yy,2,14)*16+xx).astype(np.uint8)[front]
    return m

def generate():
    art=g.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');base=review.read(review.BASELINE);rr=rows();patch={};ledger=[];audit=[]
    for n,row in enumerate(rr,1):
        key=row['key'];sprite=art.cell(key,'BAKED');places=[]
        for loc in row['occurrences']:
            source=art.maps[loc['map']];raw=base64.b64decode(source['cells']);i=(loc['y']*source['w']+loc['x'])*4;places.append({**loc,'collision':raw[i+2],'elevation':raw[i+3]})
        old=base['cells'][key];new=old if n in RETAINED else component(sprite,n).entry(old,'hand-emerald-ranking-hall',trim=True)
        if n==8:
            lower=rr[8]['key'];assert art.map_cells(art.maps[MAP])[5][21]==lower
            new['alsoOn']=[lower]
        if n not in RETAINED:patch[key]=new
        reason=('Retain this source red wall and its directional plaque as architectural wall material, including the exact arrow and lettering across the two adjacent source rows. It is not a freestanding piece of furniture.' if n in RETAINED else 'Author one connected green chair with four grounded legs, a supported seat and framed back before slicing its existing stack; choose green upholstery and neutral frame pixels only from this exact source. Preserve all existing seat/on routing.' if n in CHAIR_TOP|CHAIR_BOTTOM else 'Build a grounded slim information terminal with recessed blue screen, separate control ledge and supported foot; keep the native display pixels on its face.' if n==1 else 'The complete three-by-three source case is modeled once on its central SOLID row; the top and lower rows are WALK projections whose native floors remain unchanged. This source-local component is only its diagnostic fallback.')
        ledger.append(dict(key=key,kind='cell',group=GROUP,source_ordinal=n,label=LABELS[n],family='frontier-ranking-hall',decision='retained' if n in RETAINED else 'changed',baseline_entry_sha256=review.entry_hash(old),proposed_entry_sha256=review.entry_hash(new),source_pixel_sha256=hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest(),occurrences=places,reason=reason,evidence=['local/model-gallery/emerald-pass-study/frontier-root-study/ranking-map.png'],visual_status='source inspected; candidate local and runtime review pending'))
        if n==8:reason+=' The same north upper back has two observed lower variants; add only the exact northwest lower-key alias to its existing on target.';ledger[-1]['reason']=reason
        audit.append(dict(source_ordinal=n,key=key,occurrences=places))
    grid=art.map_cells(art.maps[MAP]);layout=[r[25:28] for r in grid[8:11]];source=art.drawing(layout,'BAKED');ident='emerald_object_frontier_ranking_trophy_case'
    entry=dict(id=ident,atlas='g3q',tiles=quarters(layout),height=16,interior=True,terrain=[[x,z] for z in (0,2) for x in range(3)],parts=case_parts(source))
    contexts=[]
    for name,x,y,w,d in [('trophy_case',25,8,3,3),('terminal',26,4,1,2),('west_chair',3,7,1,2),('pale_chair',5,12,1,2),('short_chair',6,12,1,2),('northwest_chair',21,4,1,2),('northeast_chair',31,4,1,2),('west_direction',16,7,1,2),('east_direction',36,7,1,2)]:
        contexts.append(dict(id='emerald_ranking_'+name,map=MAP,x=x,y=y,w=w,d=d,layout=[r[x:x+w] for r in grid[y:y+d]]))
    return patch,ledger,entry,contexts,audit

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--previews',action='store_true');args=ap.parse_args();patch,ledger,entry,contexts,audit=generate();OUT.mkdir(parents=True,exist_ok=True)
    for name,data in [('updates.json',{'cells':patch,'buildings':{entry['id']:entry}}),('ledger.json',ledger),('assembly-requests.json',contexts),('source-audit.json',audit)]:g.write_json(OUT/name,data)
    if args.previews:
        art=g.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald')
        for row in ledger:
            if row['key'] not in patch:continue
            dest=OUT/'cells'/str(row['source_ordinal']);dest.mkdir(parents=True,exist_ok=True);sprite=art.cell(row['key'],'BAKED');sprite.save(dest/'source.png');render(patch[row['key']],sprite,dest/'after')
        source=art.drawing(contexts[0]['layout'],'BAKED');source.resize((384,384),Image.Resampling.NEAREST).save(OUT/'case-source.png');o,c=parts_model.build(entry,np.asarray(source.convert('RGB')));parts_model.picture(o,c,OUT/'case-view.png',6);parts_model.front_view(o,c,OUT/'case-front.png',6)
        for start in range(0,len(ledger),12):
            sheet=Image.new('RGB',(1200,900),'#e8ecef');draw=ImageDraw.Draw(sheet)
            for i,row in enumerate(ledger[start:start+12]):
                x=i%4*300;y=i//4*300;n=row['source_ordinal'];draw.text((x+5,y+6),str(n)+' '+row['label'],fill='black');sprite=art.cell(row['key'],'BAKED');sheet.paste(sprite.resize((90,90),Image.Resampling.NEAREST),(x+5,y+35))
                if row['key'] in patch:
                    im=Image.open(OUT/'cells'/str(n)/'after'/'angled.png').convert('RGBA');im.thumbnail((200,235));sheet.paste(im,(x+96,y+48),im)
                else:draw.text((x+110,y+100),'Retained wall',fill='black')
            sheet.save(OUT/f'contact-{start//12+1:02}.png')
    print('Ranking22 staged:18 changed,4 retained; one complete case;9 native context requests; no live mutation')

if __name__=='__main__':main()
