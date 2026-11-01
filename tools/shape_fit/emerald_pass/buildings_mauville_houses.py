"""Exact Trick House and Route118 doorless house exteriors from exported art."""
from __future__ import annotations
import argparse,base64,hashlib,json
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald import building_trials as p,parts_model
from tools.shape_fit.emerald_pass.buildings_west import box,material,entry_recess
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,digest
OUT=ROOT/'local/model-gallery/emerald-pass-study/buildings-mauville-houses'
CASES={
 'emerald_object_route110_trick_house':{'name':'Trick House raised central roof','map':'EM_ROUTE110','origin':[9,61],'size':[5,6],'source_sha256':'670665dd350ced0548cbb08a5eca6b093365c205003525d54c177e3c3c46243c','warp':'EM_ROUTE110_TRICK_HOUSE_ENTRANCE','terrain':[[0,0],[4,0]]},
 'emerald_object_route118_doorless_house':{'name':'Route118 doorless yellow-brick house','map':'EM_ROUTE118','origin':[0,14],'size':[4,4],'source_sha256':'1a2ffe9a4c24e0a93cc00dbba115a851feb036e7661f3c17e4a0b746b00cee19','warp':None,'terrain':[]},
}

def layout_for(art,ident):
    case=CASES[ident];x,y=case['origin'];w,d=case['size'];g=art.map_cells(art.maps[case['map']]);return [r[x:x+w] for r in g[y:y+d]]

def trick_house():
    gray=material((17,83,24,94));pink=p.paint([18,52,62,55],[18,14,62,48],[18,16,24,48])
    parts=[box((2,78),(17,94),(0,29),gray),
      box((1,79),(17,95),(0,2),material((19,94,23,95))),
      box((0,80),(16,95),(29,32),material((1,68,3,71))),
      {'shape':'gable','x':[0,80],'z':[16,95],'y':[32,48],'ridge':'x','paint':pink},
      # The raised three-cell central roof has its own supported wall mass.
      # It alone occupies the northern SOLID roof cells; WATER corners stay out.
      box((18,62),(2,69),(0,49),gray),
      box((17,63),(68,70),(40,49),p.paint([17,55,63,63],side=[17,55,20,63])),
      box((16,64),(0,72),(49,52),material((17,53,20,55))),
      {'shape':'gable','x':[16,64],'z':[0,72],'y':[52,71],'ridge':'x','paint':pink},
      box((16,64),(33,38),(70,73),p.paint([17,8,63,14],[17,1,63,7],[17,8,21,14])),
    ]
    # Gold side pilasters and separate blue glazed transoms frame the lower
    # entrance; neither the roof nor side wall samples these facade features.
    for lo in (9,65):parts.append(box((lo,lo+6),(91,95),(0,29),material((lo,74,lo+6,94))))
    for lo in (16,48):
        parts.append(box((lo,lo+16),(94,95),(0,16),p.paint([lo,84,lo+16,96],side=[17,83,24,94])))
        parts.append(box((lo,lo+16),(94,95),(16,24),p.paint([lo,77,lo+16,84],side=[lo,77,lo+2,84])))
    for lo in (2,75):parts.append(box((lo,lo+3),(17,94),(0,29),gray))
    entry_recess(parts,(33,47),96,87,25,(33,80,47,96))
    parts.append(box((32,48),(94,96),(25,30),material((32,69,48,76))))
    return {'height':76,'parts':parts}

def doorless_house():
    brick=material((17,43,30,50));parts=[]
    for bottom in range(0,29,7):
        for left in range(2,62,13):parts.append(box((left,min(62,left+13)),(16,61),(bottom,min(29,bottom+7)),brick))
    parts.extend([
      box((1,63),(16,62),(0,2),material((17,61,30,63))),
      box((0,64),(3,63),(29,32),material((1,34,3,37))),
      {'shape':'gable','x':[0,64],'z':[3,63],'y':[32,50],'ridge':'x','paint':p.paint([1,33,63,38],[1,12,63,37],[3,14,7,36])},
      box((0,64),(30,35),(49,52),p.paint([1,8,63,14],[1,1,63,8],[1,8,5,14])),
      box((33,49),(61,62),(7,25),p.paint([33,40,49,59],side=[33,40,35,59])),
    ])
    for lo in (9,53):
        for z in (16,58):parts.append(box((lo,lo+6),(z,z+4),(0,29),material((lo,40,lo+6,62))))
    return {'height':56,'parts':parts}

def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);out={}
    for ident,case in CASES.items():
        layout=layout_for(art,ident);assert hashlib.sha256(art.drawing(layout,'BAKED').convert('RGB').tobytes()).hexdigest()==case['source_sha256'];spec=trick_house() if case['warp'] else doorless_house()
        out[ident]={'id':ident,'atlas':'g3q','tiles':quarters(layout),'terrain':case['terrain'],**spec}
    return out

def evidence(art,ident,entry):
    case=CASES[ident];x0,y0=case['origin'];w,d=case['size'];places=building_catalog({'game':'emerald','cells':{},'buildings':[entry]},art)[0]['occurrences'];assert places==[{'map':case['map'],'x':x0,'y':y0,'palette':'BAKED'}];result=[]
    for loc in places:
        m=art.maps[loc['map']];raw=base64.b64decode(m['cells']);cells=[]
        for dz,row in enumerate(layout_for(art,ident)):
            for dx,k in enumerate(row):
                x,y=loc['x']+dx,loc['y']+dz;i=(y*m['w']+x)*4;cells.append({'key':k,'x':x,'y':y,'local':[dx,dz],'metatile':int.from_bytes(raw[i:i+2],'little'),'collision':raw[i+2],'elevation':raw[i+3],'terrain_excluded':[dx,dz] in entry['terrain']})
        warps=[q for q in m['warps'] if x0<=q['x']<x0+w and y0<=q['y']<y0+d];assert (len(warps)==1 and warps[0]['to']==case['warp']) if case['warp'] else not warps
        result.append({**loc,'width':w,'depth':d,'cells':cells,'warps':warps})
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();art=EmeraldArt(BUNDLE);table=json.loads(BASELINE.read_text());entries=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for ident,e in entries.items():
        case=CASES[ident];target=OUT/ident;target.mkdir(exist_ok=True);assert not any(b['tiles']==e['tiles'] for b in table['buildings']);source=p.drawing(e,BUNDLE);source.save(target/'source.png');source.resize((source.width*6,source.height*6),Image.Resampling.NEAREST).save(target/'source-enlarged.png');x,y=case['origin'];w,d=case['size'];left=max(0,x-1);top=max(0,y-1);crop=art.map_image(case['map']).crop((left*16,top*16,(x+w+1)*16,(y+d+1)*16));ImageDraw.Draw(crop).rectangle(((x-left)*16,(y-top)*16,(x+w-left)*16-1,(y+d-top)*16-1),outline='red');crop.resize((crop.width*4,crop.height*4),Image.Resampling.NEAREST).save(target/'context.png')
        if args.previews:
            o,c=parts_model.build(e,np.asarray(source));parts_model.picture(o,c,target/'view.png',5);parts_model.front_view(o,c,target/'front.png',6);parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(c,(0,2,1,3))[:,:,::-1],target/'side.png',6)
        reason='Source-exact Trick House with broad lower pink roof and a supported raised three-cell central roof, gray timber facade, gold pilasters, separate blue transoms and the one recessed pink entrance. Both northern WATER corners remain excluded; no water source pixel becomes architectural material.' if case['warp'] else 'Source-exact doorless Route118 house with yellow brick courses, pink pitched roof, gold corner pilasters and one separate arched window. No door or warp is invented. All four northern WALK source projections contain only overhead roof above29 voxels; low walls begin at the SOLID second row.'
        ledger.append({'key':ident,'kind':'building','label':case['name'],'family':'mauville-distinct-pink-roof-houses','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(e),'source_pixel_sha256':case['source_sha256'],'source_pixel_mode':'RGB','occurrences':evidence(art,ident,e),'terrain_exclusions':e['terrain'],'reason':reason,'evidence':[{'source':(target/'source.png').relative_to(ROOT).as_posix(),'context':(target/'context.png').relative_to(ROOT).as_posix(),'python_views':[(target/f'{n}.png').relative_to(ROOT).as_posix() for n in ('front','side','view')]}],'visual_status':'source reviewed; Python/runtime candidate pending','user_approved':False});(target/'spec.json').write_text(json.dumps(e,indent=2)+'\n')
    for name,value in [('updates.json',{'cells':{},'buildings':entries}),('added-ledger.json',ledger)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'added':list(entries),'source_occurrences':2,'warp_count':1}))

if __name__=='__main__':main()
