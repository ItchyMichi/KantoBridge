"""Exact Sky Pillar source variants and the complete upper stair flight."""
from __future__ import annotations
import argparse
import base64
import copy
import hashlib
import json
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald import building_trials as p,parts_model
from tools.shape_fit.emerald_pass import buildings_east
from tools.shape_fit.emerald_pass.buildings_west import box,material
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,digest

OUT=ROOT/'local/model-gallery/emerald-pass-study/object-sky-pillar-patterns'
CASES={
 'tower':('EM_SKY_PILLAR_OUTSIDE',12,0,5,6,'cfda88f30ca3dd57f5ec6969f5c62a9bdfef39f220c89e53c490d25492cc6be8'),
 'ascending_portal':('EM_SKY_PILLAR_1F',9,0,3,2,'a8b23767b02501384b8ee5702c2c3ce5cf4f6510949b97a05b09141bbf68dcdd'),
 'upper_stairs':('EM_SKY_PILLAR_TOP',10,13,4,2,'bd5377a1648ea43db1ef7f379ea1b082fbe65f47716d11502183511457d5cba8'),
}


def layout_for(art,name):
    mapid,x,y,w,d,_=CASES[name];g=art.map_cells(art.maps[mapid]);return [r[x:x+w] for r in g[y:y+d]]


def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);originals={e['id']:e for e in table['buildings']};out={}
    for name in CASES:
        ident='emerald_object_sky_pillar_'+name;layout=layout_for(art,name)
        if name in ('tower','ascending_portal'):
            num=110 if name=='tower' else 111;old=originals['emerald_b'+str(num)+'_EM_SKY_PILLAR_'+('OUTSIDE' if num==110 else 'TOP')]
            entry=copy.deepcopy(buildings_east.revise(num,old));entry['id']=ident;entry['tiles']=quarters(layout)
            if name=='tower':
                # The actual exported source has a closed circular door and
                # SOLID7 on every cell. Preserve its symbol within the reviewed
                # entrance recess instead of painting a closed door as empty air.
                entry['parts'].append(box((32,48),(87,88),(0,18),p.paint([32,80,48,96],top=[35,84,45,89],side=[35,84,45,89])))
                entry['parts'].append({'shape':'barrel','along':'z','x':[32,48],'z':[87,88],'y':[18,26],'paint':p.paint([32,72,48,80],top=[35,84,45,89],side=[35,84,45,89])})
            if name=='ascending_portal':
                # Four low native gray treads sit within the open portal; the
                # original clear arch remains above them, and source warp stays.
                for z,h in ((16,8),(20,6),(24,4),(28,2)):
                    entry['parts'].append(box((16,32),(z,z+4),(0,h),material((20,26,22,27))))
        else:
            # Two decorated side walls frame a full two-cell-wide stair flight.
            # This template covers the repeated two-row source as one object,
            # avoiding a sawtooth stair reset in each repeated individual cell.
            parts=[]
            for x0,x1,rect in ((0,16,[0,0,16,32]),(48,64,[48,0,64,32])):
                parts.append(box((x0,x1),(3,29),(0,30),p.paint(rect,top=[x0,0,x1,3],side=[0,8,6,28])))
                parts.append(box((x0,x1),(2,30),(30,32),material((2,1,3,2))))
            for i,h in enumerate((12,11,9,8,6,5,3,2)):
                parts.append(box((16,48),(i*4,(i+1)*4),(0,h),material((20,3 if i%2 else 7,21,4 if i%2 else 8))))
            entry={'id':ident,'atlas':'g3q','tiles':quarters(layout),'parts':parts,'height':34,'terrain':[]}
        out[ident]=entry
    return out


def evidence(art,entry,name):
    layout=layout_for(art,name);places=building_catalog({'game':'emerald','cells':{},'buildings':[entry]},art)[0]['occurrences'];out=[]
    for loc in places:
        source=art.maps[loc['map']];raw=base64.b64decode(source['cells']);cells=[]
        for dy,row in enumerate(layout):
            for dx,key in enumerate(row):
                x,y=loc['x']+dx,loc['y']+dy;i=(y*source['w']+x)*4
                cells.append({'x':x,'y':y,'local':[dx,dy],'key':key,'metatile':int.from_bytes(raw[i:i+2],'little'),'collision':raw[i+2],'elevation':raw[i+3],'terrain_excluded':[dx,dy] in entry['terrain']})
        out.append({**loc,'width':len(layout[0]),'depth':len(layout),'cells':cells,'warps':[w for w in source['warps'] if loc['x']<=w['x']<loc['x']+len(layout[0]) and loc['y']<=w['y']<loc['y']+len(layout)]})
    assert len(out)==(6 if name=='ascending_portal' else 1)
    return out


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for name in CASES:
        ident='emerald_object_sky_pillar_'+name;entry=updates[ident];target=OUT/name;target.mkdir(exist_ok=True);source=p.drawing(entry,BUNDLE);source_hash=hashlib.sha256(source.tobytes()).hexdigest();assert source_hash==CASES[name][-1]
        assert all(e['id']!=ident and e['tiles']!=entry['tiles'] for e in table['buildings'])
        places=evidence(art,entry,name);source.save(target/'source.png');source.resize((source.width*5,source.height*5),Image.Resampling.NEAREST).save(target/'source-enlarged.png')
        for i,loc in enumerate(places):
            x,y=loc['x']*16,loc['y']*16;crop=art.map_image(loc['map']).crop((x-16,y-16,x+source.width+16,y+source.height+32));ImageDraw.Draw(crop).rectangle((16,16,source.width+15,source.height+15),outline='red');crop.resize((crop.width*3,crop.height*3),Image.Resampling.NEAREST).save(target/f'context-{i:02}.png')
        if args.previews:
            o,c=parts_model.build(entry,np.asarray(source));parts_model.picture(o,c,target/'view.png',3 if name=='tower' else 6);parts_model.front_view(o,c,target/'front.png',3 if name=='tower' else 7);parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(c,(0,2,1,3))[:,:,::-1],target/'side.png',3 if name=='tower' else 7)
        relative=target.relative_to(ROOT).as_posix()
        reasons={'tower':'The reviewed b110 tower has zero exact source matches because two doorway cells differ from the exported map. Preserve b110 and add the actual5x6 source pattern at Sky Pillar Outside(12,0), sharing its reviewed tall masonry, recessed windows and entrance arch. All30 source cells are SOLID7; a shallow recessed closed panel preserves the actual circular door symbol. Warp/collision/elevation unchanged.',
                 'ascending_portal':'The descending portal b111 matches six source places, while the distinct ascending stair drawing appears six more times. Add its exact3x2 source pattern with the same reviewed open arch and four low gray interior treads. Preserve all six warp destinations and the passable center cell.',
                 'upper_stairs':'The exact4x2 flight on Sky Pillar Top has two decorated side pillars and four central WALK0/elevation3 cells. Author the whole continuous eight-tread flight with clear headroom and native stone/gold side walls, avoiding repeated per-cell stair resets. All source movement remains native; WALK cells are included for non-colliding template decoration rather than terrain-clipped out of the flight.'}
        row={'key':ident,'kind':'building','label':'Sky Pillar '+name.replace('_',' '),'family':'sky-pillar-source-pattern','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(entry),'source_pixel_sha256':source_hash,'source_pixel_mode':'RGB','occurrences':places,'reason':reasons[name],'terrain_exclusions':entry['terrain'],'evidence':[{'source':relative+'/source.png','contexts':[relative+f'/context-{i:02}.png' for i in range(len(places))]},{'python_front':relative+'/front.png','python_side':relative+'/side.png','python_angled':relative+'/view.png'}],'visual_status':'source inspected; Python/runtime QA pending','user_approved':False}
        if name in ('tower','ascending_portal'):
            num=110 if name=='tower' else 111;old=next(e for e in table['buildings'] if e['id'].startswith('emerald_b'+str(num)+'_'));reviewed=buildings_east.revise(num,old)
            row['nearest_existing_template']=old['id'];row['reviewed_template_entry_sha256']=digest(reviewed);row['source_quarter_substitutions']=[{'x':x,'y':y,'old':a,'new':b} for y,(ar,br) in enumerate(zip(old['tiles'],entry['tiles'])) for x,(a,b) in enumerate(zip(ar,br)) if a!=b];row['original_preserved']=True
        ledger.append(row);(target/'spec.json').write_text(json.dumps(entry,indent=2)+'\n')
    for name,value in [('updates.json',{'cells':{},'buildings':updates}),('added-ledger.json',ledger)]:
        (OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'added':len(updates),'occurrences':sum(len(r['occurrences']) for r in ledger)}))


if __name__=='__main__':main()
