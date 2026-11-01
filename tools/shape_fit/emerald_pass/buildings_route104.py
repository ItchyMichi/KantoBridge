"""Source-exact Pretty Petal shop and Briney house missing from the templates."""
from __future__ import annotations
import argparse,base64,hashlib,json
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald import building_trials as p,parts_model
from tools.shape_fit.emerald_pass.buildings_west import box,material,entry_recess
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,digest

OUT=ROOT/'local/model-gallery/emerald-pass-study/buildings-route104'
CASES={
 'emerald_object_route104_flower_shop':{'name':'Pretty Petal Flower Shop','origin':[3,15],'size':[6,4],'source_sha256':'48695d0c5a82194c6a9150707a4c1091aa11ec9b0abb43967bf552973b43a37e','warp':'EM_ROUTE104_PRETTY_PETAL_FLOWER_SHOP'},
 'emerald_object_route104_briney_house':{'name':"Mr. Briney's house",'origin':[15,47],'size':[5,4],'source_sha256':'5338941366efc3c4e0a8712d87f04fd6d6b6db88c4eccd7d7e74e65cb109231d','warp':'EM_ROUTE104_MR_BRINEYS_HOUSE'},
}

def layout_for(art,ident):
    case=CASES[ident];x,y=case['origin'];w,d=case['size'];g=art.map_cells(art.maps['EM_ROUTE104']);return [r[x:x+w] for r in g[y:y+d]]

def flower_shop():
    green=material((73,40,79,54));trim=material((1,34,4,37));roof=p.paint([2,26,94,33],[2,2,94,26],[6,4,10,26]);parts=[
      box((2,94),(16,60),(0,29),green),
      box((0,96),(4,62),(29,33),p.paint([1,26,95,37],[2,26,94,30],[1,30,4,36])),
      {'shape':'gable','x':[0,96],'z':[3,62],'y':[33,51],'ridge':'x','paint':roof},
      box((0,96),(31,35),(50,53),material((7,1,10,4))),
    ]
    for lo in (1,91):
        for z in (16,58):parts.append(box((lo,lo+4),(z,z+4),(0,29),material((lo,38,lo+4,63))))
    entry_recess(parts,(32,48),64,54,25,(32,49,48,64))
    for lo,hi in ((8,16),(64,72),(80,88)):
        parts.append(box((lo,hi),(60,61),(11,28),p.paint([lo,38,hi,53],side=[8,38,10,53])))
    for lo,hi in ((8,24),(56,88)):
        parts.append(box((lo,hi),(60,61),(3,12),p.paint([lo,54,hi,62],side=[8,54,10,62])))
    # The striped shop awning projects over the recessed entrance; its
    # scalloped edge and two pale jambs are physical parts, not roof paint.
    for lo in (26,49):parts.append(box((lo,lo+5),(59,63),(0,25),material((lo,50,lo+4,62))))
    parts.append({'shape':'profile','x':[16,64],'z':[54,64],'y':[24,31],'along':'x','profile':[7,2],'paint':p.paint([16,39,64,49],[16,38,64,47],[16,39,20,48])})
    for lo in range(16,64,8):parts.append(box((lo,lo+4),(63,64),(24,26),material((16,39,19,44))))
    return {'height':56,'parts':parts}

def briney_house():
    timber=[9,49,15,59];parts=[
      box((2,78),(16,61),(0,31),p.paint([2,46,78,64],side=timber,back=timber)),
      box((1,79),(16,62),(0,3),material((2,61,7,63))),
      box((1,79),(59,62),(22,31),p.paint([1,37,79,46],side=[2,38,6,45])),
      box((0,80),(3,63),(31,35),p.paint([0,32,80,38],[0,9,80,15],[1,32,5,38])),
      {'shape':'gable','x':[0,80],'z':[3,63],'y':[35,53],'ridge':'x','paint':p.paint([0,31,80,37],[1,0,79,35],[3,1,7,33])},
      box((0,80),(30,35),(52,55),p.paint([0,8,80,15],[0,8,80,12],[0,8,4,15])),
    ]
    for lo in (1,75):
        for z in (16,58):parts.append(box((lo,lo+4),(z,z+4),(0,31),material((lo,46,lo+4,63))))
    entry_recess(parts,(32,48),64,54,23,(32,47,48,64))
    return {'height':56,'parts':parts}

def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);result={}
    for ident,case in CASES.items():
        layout=layout_for(art,ident);assert hashlib.sha256(art.drawing(layout,'BAKED').convert('RGB').tobytes()).hexdigest()==case['source_sha256']
        spec=flower_shop() if ident.endswith('flower_shop') else briney_house();result[ident]={'id':ident,'atlas':'g3q','tiles':quarters(layout),'terrain':[],**spec}
    return result

def evidence(art,ident,entry):
    case=CASES[ident];x0,y0=case['origin'];w,d=case['size'];places=building_catalog({'game':'emerald','cells':{},'buildings':[entry]},art)[0]['occurrences'];assert places==[{'map':'EM_ROUTE104','x':x0,'y':y0,'palette':'BAKED'}];result=[]
    for loc in places:
        m=art.maps[loc['map']];raw=base64.b64decode(m['cells']);cells=[]
        for dz,row in enumerate(layout_for(art,ident)):
            for dx,k in enumerate(row):
                x,y=loc['x']+dx,loc['y']+dz;i=(y*m['w']+x)*4;cells.append({'key':k,'x':x,'y':y,'local':[dx,dz],'metatile':int.from_bytes(raw[i:i+2],'little'),'collision':raw[i+2],'elevation':raw[i+3],'terrain_excluded':False})
        warps=[q for q in m['warps'] if x0<=q['x']<x0+w and y0<=q['y']<y0+d];assert len(warps)==1 and warps[0]['to']==case['warp']
        result.append({**loc,'width':w,'depth':d,'cells':cells,'warps':warps})
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();art=EmeraldArt(BUNDLE);table=json.loads(BASELINE.read_text());entries=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for ident,e in entries.items():
        case=CASES[ident];target=OUT/ident;target.mkdir(exist_ok=True);assert not any(b['tiles']==e['tiles'] for b in table['buildings']);source=p.drawing(e,BUNDLE);source.save(target/'source.png');source.resize((source.width*6,source.height*6),Image.Resampling.NEAREST).save(target/'source-enlarged.png');x,y=case['origin'];w,d=case['size'];crop=art.map_image('EM_ROUTE104').crop(((x-1)*16,(y-1)*16,(x+w+1)*16,(y+d+1)*16));ImageDraw.Draw(crop).rectangle((16,16,(w+1)*16-1,(d+1)*16-1),outline='red');crop.resize((crop.width*4,crop.height*4),Image.Resampling.NEAREST).save(target/'context.png')
        if args.previews:
            o,c=parts_model.build(e,np.asarray(source));parts_model.picture(o,c,target/'view.png',5);parts_model.front_view(o,c,target/'front.png',6);parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(c,(0,2,1,3))[:,:,::-1],target/'side.png',6)
        why='Source-exact six-cell-wide flower shop with orange ribbed gable, green plank wall, three separate glazed windows, lower painted flower panels and a projecting striped awning over the recessed entrance.' if ident.endswith('flower_shop') else 'Source-exact five-cell-wide Briney house with tan timber gable, raised ridge strips, pale blue transom, yellow slatted front panels, corner posts and recessed open entrance.'
        row={'key':ident,'kind':'building','label':case['name'],'family':'route104-source-exact-exteriors','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(e),'source_pixel_sha256':case['source_sha256'],'source_pixel_mode':'RGB','occurrences':evidence(art,ident,e),'terrain_exclusions':[],'reason':why+' Northern source WALK roof-projection cells contain no geometry below y29; their native movement and elevation remain intact. Exact map door warp and every source quarter remain unchanged. No terrain or neighboring accepted tree is included in the template.','evidence':[{'source':(target/'source.png').relative_to(ROOT).as_posix(),'context':(target/'context.png').relative_to(ROOT).as_posix(),'python_views':[(target/f'{n}.png').relative_to(ROOT).as_posix() for n in ('front','side','view')]}],'visual_status':'source reviewed; Python/runtime pending','user_approved':False};ledger.append(row);(target/'spec.json').write_text(json.dumps(e,indent=2)+'\n')
    for name,value in [('updates.json',{'cells':{},'buildings':entries}),('added-ledger.json',ledger)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'added':list(entries),'source_occurrences':2}))

if __name__=='__main__':main()
