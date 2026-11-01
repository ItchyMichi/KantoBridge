"""Exact Route121 Safari gate exterior, absent from the original112 templates."""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
from PIL import Image
import numpy as np
from tools.model_gallery import EmeraldArt, building_catalog
from tools.shape_fit.emerald import building_trials as p, parts_model
from tools.shape_fit.emerald_pass.buildings_west import box, material
from tools.shape_fit.emerald_pass.towns import ROOT, BASELINE, BUNDLE, digest

OUT=ROOT/'local/model-gallery/emerald-pass-study/building-safari-route121'
ID='emerald_b123_EM_ROUTE121'
LAYOUT=[
 ['g3q/19447-19448-19447-19448','g3q/19448-19448-19448-19448','g3q/19448-19448-19448-19448','g3q/19448-19448-19448-19448','g3q/19448-19450-19448-19450'],
 ['g3q/19447-19448-19447-19448','g3q/19448-19448-19448-19448','g3q/19448-19448-19448-19448','g3q/19448-19448-19448-19448','g3q/19448-19450-19448-19450'],
 ['g3q/19447-19448-19447-19448','g3q/19448-19448-19448-19448','g3q/19448-19448-19448-19448','g3q/19448-19448-19448-19448','g3q/19448-19450-19448-19450'],
 ['g3q/19461-19462-19463-19464','g3q/19462-19462-19464-19464','g3q/19462-19462-19464-19464','g3q/19462-19462-19464-19464','g3q/19462-19465-19464-19466'],
 ['g3q/19451-19442-19451-19444','g3q/19439-19367-19440-19441','g3q/19367-19367-19368-19368','g3q/19367-19442-19443-19444','g3q/19439-19382-19440-19382'],
 ['g3q/19477-19429-19478-19430','g3q/19429-19452-19430-19454','g3q/19455-19456-19457-19458','g3q/19459-19429-19460-19430','g3q/19429-19479-19430-19480'],
]
SOURCE_RGBA_SHA256='efb8dd43e42bb9dc98828069f7215c6ee3d2bfc8762ddaf67acc1d2d8cceba18'


def tiles():
    result=[]
    for row in LAYOUT:
        q=[list(map(int,k.split('/')[1].split('-'))) for k in row]
        result.extend([[v for cell in q for v in cell[:2]],[v for cell in q for v in cell[2:]]])
    return result


def generate():
    # The north roof runs beyond the map boundary. Preserve its exact clipped
    # source rectangle, with physical parts wholly inside these thirty cells.
    wall=material((6,68,7,69));rim=material((2,55,3,56))
    parts=[
        box((2,78),(0,93),(0,36),wall),
        box((2,78),(92,94),(16,36),p.paint([2,64,78,80],side=[3,65,5,80])),
        box((0,80),(0,96),(36,40),p.paint([0,56,80,64],[0,0,80,56],[2,59,3,60])),
        box((3,77),(0,92),(40,42),p.paint([4,40,76,48],[4,0,76,48],[4,0,6,48])),
        box((0,3),(0,96),(40,45),rim),
        box((77,80),(0,96),(40,45),rim),
        box((0,80),(92,96),(40,45),rim),
        # The central source warp is the only doorway. Its recessed glass
        # and separate canopy avoid painting the door onto a solid facade.
        box((27,53),(85,96),(0,25),carve=True),
        box((27,53),(85,86),(0,25),p.paint([27,81,53,95],side=[27,81,28,95])),
        box((24,56),(86,96),(25,29),p.paint([24,73,56,81],[24,72,56,74],[24,73,26,81])),
        box((27,53),(86,96),(0,2),material((3,94,4,95))),
    ]
    # Native low hedge fringe is landscaping against the two wall ends,
    # kept below windows and completely outside the recessed doorway.
    for lo,hi in ((2,25),(55,78)):
        parts.append(box((lo,hi),(93,96),(0,7),p.paint([lo,84,hi,95],[lo,85,hi,88],[lo,85,lo+1,95])))
    return {ID:{'id':ID,'atlas':'g3q','tiles':tiles(),'parts':parts,'height':48,'terrain':[]}}


def evidence(art,entry):
    item=building_catalog({'game':'emerald','cells':{},'buildings':[entry]},art)[0]
    result=[]
    for location in item['occurrences']:
        m=art.maps[location['map']];raw=base64.b64decode(m['cells']);cells=[]
        for dy,row in enumerate(LAYOUT):
            for dx,key in enumerate(row):
                x,y=location['x']+dx,location['y']+dy;i=(y*m['w']+x)*4
                cells.append({'x':x,'y':y,'local':[dx,dy],'key':key,'metatile':int.from_bytes(raw[i:i+2],'little'),'collision':raw[i+2],'elevation':raw[i+3]})
        result.append({**location,'width':5,'depth':6,'cells':cells,'warps':[w for w in m.get('warps',[]) if location['x']<=w['x']<location['x']+5 and location['y']<=w['y']<location['y']+6]})
    assert len(result)==1 and (result[0]['map'],result[0]['x'],result[0]['y'])==('EM_ROUTE121',35,0)
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args()
    art=EmeraldArt(BUNDLE);table=json.loads(BASELINE.read_text());entry=generate()[ID];source=p.drawing(entry,BUNDLE)
    assert not any(e['id']==ID or e['tiles']==entry['tiles'] for e in table['buildings'])
    assert hashlib.sha256(art.drawing(LAYOUT,'BAKED').tobytes()).hexdigest()==SOURCE_RGBA_SHA256
    OUT.mkdir(parents=True,exist_ok=True);source.save(OUT/'source.png');source.resize((400,480),Image.Resampling.NEAREST).save(OUT/'source-enlarged.png')
    art.map_image('EM_ROUTE121').crop((33*16,0,42*16,9*16)).resize((576,576),Image.Resampling.NEAREST).save(OUT/'context.png')
    occurrences=evidence(art,entry);source_hash=hashlib.sha256(source.tobytes()).hexdigest()
    if args.previews:
        occ,colors=parts_model.build(entry,np.asarray(source));parts_model.picture(occ,colors,OUT/'view.png',5);parts_model.front_view(occ,colors,OUT/'front.png',6)
        parts_model.front_view(np.transpose(occ,(0,2,1))[:,:,::-1],np.transpose(colors,(0,2,1,3))[:,:,::-1],OUT/'side.png',6)
    relative=OUT.relative_to(ROOT).as_posix()
    row={'key':ID,'kind':'building','label':'Route121 Safari entrance exterior','family':'safari-gatehouse','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(entry),'source_pixel_sha256':source_hash,'source_pixel_mode':'RGB','occurrences':occurrences,
         'reason':'Exact narrow5x6 exterior at the north Route121 boundary is missing from the original112 templates. Existing b109 is a different10x5 interior-side gate; its best aligned overlap matches only15/30 source cells. Author the native inset flat roof/parapet, cream siding, short blue windows, low hedge and recessed double doors/canopy in the exact source footprint. All29 non-warp cells are SOLID7; source warp113 stays central.',
         'evidence':[{'source':relative+'/source.png','context':relative+'/context.png'},{'nearest_existing_template':'emerald_b109_EM_SAFARI_ZONE_SOUTH','matching_cells':15,'total_cells':30,'offset_into_candidate':[0,1]},{'python_front':relative+'/front.png','python_side':relative+'/side.png','python_angled':relative+'/view.png'}],
         'visual_status':'source inspected; Python and Minecraft QA pending','user_approved':False}
    for name,value in [('updates.json',{'cells':{},'buildings':{ID:entry}}),('added-ledger.json',[row]),('occurrences.json',occurrences),('spec.json',entry)]:
        (OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'id':ID,'occurrences':len(occurrences),'source_pixel_sha256':source_hash,'proposed_entry_sha256':digest(entry)}))


if __name__=='__main__':main()
