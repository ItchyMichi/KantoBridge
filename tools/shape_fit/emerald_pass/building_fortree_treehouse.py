"""One exact Fortree leaf-roof house pattern, six source warp destinations."""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald import building_trials as p,parts_model
from tools.shape_fit.emerald_pass.buildings_west import box,material
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,digest
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters

OUT=ROOT/'local/model-gallery/emerald-pass-study/building-fortree-treehouse'
ID='emerald_b124_EM_FORTREE_CITY'
SOURCE_SHA256='87540919da8adc9f38eaf8989f7e9a79d99e268961b14b0f217505c2f89298dc'
LAYOUT=[
 ['g3q/17268-17269-17270-17271','g3q/17272-17273-17274-17275','g3q/17276-17277-17278-17279'],
 ['g3q/17289-17290-17291-17292','g3q/17293-17294-17295-17295','g3q/17296-17297-17298-17299'],
 ['g3q/17309-17292-17310-17311','g3q/13203-13203-17312-17312','g3q/17298-17313-17314-17315'],
]


def generate():
    # The adjoining golden deck, suspension bridges and ladders are independent
    # source cells. This exact three-square core covers only the actual house.
    wood=material((10,29,11,30));leaf=p.paint([0,1,48,16],[0,0,48,16],[2,1,14,16],[0,0,48,16])
    parts=[
        box((4,44),(4,44),(0,28),p.paint([4,20,44,48],side=[10,24,14,45],back=[10,24,14,45])),
        box((10,15),(42,46),(0,28),p.paint([10,20,15,48],side=[10,24,12,44])),
        box((33,38),(42,46),(0,28),p.paint([33,20,38,48],side=[34,24,36,44])),
        box((15,33),(34,48),(0,25),carve=True),
        box((15,33),(34,35),(0,25),p.paint([16,29,32,46],side=[16,29,17,46])),
        box((13,35),(35,46),(0,2),material((20,47,21,48))),
        box((13,35),(42,46),(25,29),wood),
        p.round_part('dome',[24,24],[24,24],[28,50],leaf),
    ]
    # The source canopy has individually hanging frond ends around its eave.
    # These short lobes meet the main canopy, never become separate floating
    # leaves or carry orange doorway pixels into the roof.
    for x in (7,16,25,34,41):
        parts.append(p.round_part('dome',[x,40],[6,6],[25,32],p.paint([max(0,x-5),4,min(48,x+5),16],top=[max(0,x-5),3,min(48,x+5),14],side=[3,4,10,16])))
    for x in (8,40):
        parts.append(p.round_part('dome',[x,8],[8,8],[27,34],leaf))
    return {ID:{'id':ID,'atlas':'g3q','tiles':quarters(LAYOUT),'parts':parts,'height':52,'terrain':[]}}


def evidence(art,entry):
    places=building_catalog({'game':'emerald','cells':{},'buildings':[entry]},art)[0]['occurrences'];result=[]
    for loc in places:
        source=art.maps[loc['map']];raw=base64.b64decode(source['cells']);cells=[]
        for dy,row in enumerate(LAYOUT):
            for dx,key in enumerate(row):
                x,y=loc['x']+dx,loc['y']+dy;i=(y*source['w']+x)*4
                cells.append({'x':x,'y':y,'local':[dx,dy],'key':key,'metatile':int.from_bytes(raw[i:i+2],'little'),'collision':raw[i+2],'elevation':raw[i+3]})
        result.append({**loc,'width':3,'depth':3,'cells':cells,'warps':[v for v in source['warps'] if loc['x']<=v['x']<loc['x']+3 and loc['y']<=v['y']<loc['y']+3]})
    assert len(result)==6
    assert all(p['cells'][7]['collision']==113 and len(p['warps'])==1 for p in result)
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args()
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);entry=generate()[ID];source=p.drawing(entry,BUNDLE)
    assert hashlib.sha256(source.tobytes()).hexdigest()==SOURCE_SHA256
    assert all(v['id']!=ID and v['tiles']!=entry['tiles'] for v in table['buildings'])
    OUT.mkdir(parents=True,exist_ok=True);source.save(OUT/'source.png');source.resize((384,384),Image.Resampling.NEAREST).save(OUT/'source-enlarged.png');occurrences=evidence(art,entry)
    for i,loc in enumerate(occurrences):
        x,y=loc['x']*16,loc['y']*16;crop=art.map_image(loc['map']).crop((x-16,y-16,x+64,y+96));ImageDraw.Draw(crop).rectangle((16,16,63,63),outline='red');crop.resize((320,448),Image.Resampling.NEAREST).save(OUT/f'context-{i:02}.png')
    if args.previews:
        o,c=parts_model.build(entry,np.asarray(source));parts_model.picture(o,c,OUT/'view.png',6);parts_model.front_view(o,c,OUT/'front.png',7)
        parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(c,(0,2,1,3))[:,:,::-1],OUT/'side.png',7)
    relative=OUT.relative_to(ROOT).as_posix();ledger={'key':ID,'kind':'building','label':'Fortree leaf-roof tree house','family':'fortree-treehouse','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(entry),'source_pixel_sha256':SOURCE_SHA256,'source_pixel_mode':'RGB','occurrences':occurrences,'reason':'The exact3x3 leaf-roof timber house appears six times, including five homes and the decoration shop, and is absent from all original112 templates. Add one native pattern with its rounded palm-frond canopy, orange timber walls, paired door posts, recessed opening and threshold. Each original source warp destination stays in map data. Golden platforms, forest, bridges and ladders remain independently authored adjacent objects. All eight non-warp cells are SOLID7.','evidence':[{'source':relative+'/source.png','contexts':[relative+f'/context-{i:02}.png' for i in range(6)]},{'source_proof':'local/model-gallery/emerald-pass-study/towns-fortree/treehouse-source-proof.json'},{'python_front':relative+'/front.png','python_side':relative+'/side.png','python_angled':relative+'/view.png'}],'visual_status':'source inspected; Python and runtime QA pending','user_approved':False}
    for name,value in [('updates.json',{'cells':{},'buildings':{ID:entry}}),('added-ledger.json',[ledger]),('occurrences.json',occurrences),('spec.json',entry)]:
        (OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'id':ID,'occurrences':len(occurrences),'proposed_entry_sha256':digest(entry)}))


if __name__=='__main__':main()
