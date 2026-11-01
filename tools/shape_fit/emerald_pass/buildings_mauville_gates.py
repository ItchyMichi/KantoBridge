"""Source-exact cycling gates and Winstrate house, with native path clearance."""
from __future__ import annotations
import argparse,base64,hashlib,json
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald import building_trials as p,parts_model
from tools.shape_fit.emerald_pass.buildings_west import box,material,entry_recess
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,digest
OUT=ROOT/'local/model-gallery/emerald-pass-study/buildings-mauville-gates'
CASES={
 'emerald_object_route110_cycling_gate_north':{'name':'Seaside Cycling Road north gate','map':'EM_ROUTE110','origin':[14,12],'size':[6,5],'source_sha256':'78cbeef078f030db7558511944672e6eb8c16f0a9da2c8cca6b0d248d8df816b','warp':'EM_ROUTE110_SEASIDE_CYCLING_ROAD_NORTH_ENTRANCE','doors':[1,4],'windows':[2,3],'water_roof':False},
 'emerald_object_route110_cycling_gate_south':{'name':'Seaside Cycling Road south gate','map':'EM_ROUTE110','origin':[15,84],'size':[6,5],'source_sha256':'0f41e3763580c2ab6322d93fa0b57853e81155224c6780adc2fc36cda6ef4dec','warp':'EM_ROUTE110_SEASIDE_CYCLING_ROAD_SOUTH_ENTRANCE','doors':[1,4],'windows':[2,3],'water_roof':True},
 'emerald_object_route111_winstrate_house':{'name':'Winstrate family house','map':'EM_ROUTE111','origin':[11,109],'size':[5,5],'source_sha256':'6ef454768033157790244bc8227f4627f263c5537362b2456177e367ed6d4b4f','warp':'EM_ROUTE111_WINSTRATE_FAMILYS_HOUSE','doors':[2],'windows':[1,3],'water_roof':False},
}

def layout_for(art,ident):
    case=CASES[ident];x,y=case['origin'];w,d=case['size'];g=art.map_cells(art.maps[case['map']]);return [r[x:x+w] for r in g[y:y+d]]

def structure(case):
    width=case['size'][0]*16;north=16 if case['water_roof'] else 1
    bx=34 if len(case['doors'])==2 else 18;brick=material((bx,69,bx+12,76));stone=material((10,2,11,3));light_stone=material((2,1,3,2))
    # Native brick courses repeat at their own scale on every wall, instead
    # of stretching a seven-pixel material patch up the entire facade.
    parts=[]
    for bottom in range(0,30,7):
        for left in range(2,width-2,12):parts.append(box((left,min(width-2,left+12)),(16,78),(bottom,min(30,bottom+7)),brick))
    parts.extend([
      box((1,width-1),(16,79),(0,3),material((3,76,7,79))),
      box((0,width),(north,80),(30,34),stone),
      box((3,width-3),(north+3,77),(34,35),p.paint([16,17,width-16,32],[16,17,width-16,32],[17,17,19,32])),
    ])
    # Low parapet rims sit around a genuinely recessed gravel roof.
    for xr,zr in (((0,3),(north,80)),((width-3,width),(north,80)),((3,width-3),(north,north+3)),((3,width-3),(77,80))):parts.append(box(xr,zr,(34,39),stone))
    for left in range(0,width,8):
        for depth in (north,77):parts.append(box((left,min(width,left+7)),(depth,depth+3),(39,40),light_stone))
    for depth in range(north,80,8):
        for left in (0,width-3):parts.append(box((left,left+3),(depth,min(80,depth+7)),(39,40),light_stone))
    for lo in (1,width-5):
        parts.append(box((lo,lo+4),(76,79),(3,30),material((lo,50,lo+4,76))))
    for col in case['windows']:
        lo=col*16;parts.append(box((lo,lo+16),(78,79),(10,28),p.paint([lo,48,lo+16,64],side=[lo+2,48,lo+4,63])))
    for col in case['doors']:
        lo=col*16
        # The source cell above each real warp is WALK, not wall. Its low
        # column must remain empty even though the overhead roof is claimed.
        parts.append(box((lo,lo+16),(48,64),(0,24),carve=True))
        entry_recess(parts,(lo+1,lo+15),80,71,24,(lo,64,lo+16,80))
        parts.append(box((lo,lo+16),(78,80),(24,29),material((lo,55,lo+16,61))))
    return {'height':40,'parts':parts}

def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);out={}
    for ident,case in CASES.items():
        layout=layout_for(art,ident);assert hashlib.sha256(art.drawing(layout,'BAKED').convert('RGB').tobytes()).hexdigest()==case['source_sha256']
        out[ident]={'id':ident,'atlas':'g3q','tiles':quarters(layout),'terrain':[[x,0] for x in range(case['size'][0])] if case['water_roof'] else [],**structure(case)}
    return out

def evidence(art,ident,entry):
    case=CASES[ident];x0,y0=case['origin'];w,d=case['size'];places=building_catalog({'game':'emerald','cells':{},'buildings':[entry]},art)[0]['occurrences'];assert places==[{'map':case['map'],'x':x0,'y':y0,'palette':'BAKED'}];result=[]
    for loc in places:
        m=art.maps[loc['map']];raw=base64.b64decode(m['cells']);cells=[]
        for dz,row in enumerate(layout_for(art,ident)):
            for dx,k in enumerate(row):
                x,y=loc['x']+dx,loc['y']+dz;i=(y*m['w']+x)*4;cells.append({'key':k,'x':x,'y':y,'local':[dx,dz],'metatile':int.from_bytes(raw[i:i+2],'little'),'collision':raw[i+2],'elevation':raw[i+3],'terrain_excluded':[dx,dz] in entry['terrain']})
        warps=[q for q in m['warps'] if x0<=q['x']<x0+w and y0<=q['y']<y0+d];assert len(warps)==len(case['doors']) and all(p['to']==case['warp'] for p in warps)
        result.append({**loc,'width':w,'depth':d,'cells':cells,'warps':warps})
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();art=EmeraldArt(BUNDLE);table=json.loads(BASELINE.read_text());entries=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for ident,e in entries.items():
        case=CASES[ident];target=OUT/ident;target.mkdir(exist_ok=True);assert not any(b['tiles']==e['tiles'] for b in table['buildings']);source=p.drawing(e,BUNDLE);source.save(target/'source.png');source.resize((source.width*6,source.height*6),Image.Resampling.NEAREST).save(target/'source-enlarged.png');x,y=case['origin'];w,d=case['size'];crop=art.map_image(case['map']).crop(((x-1)*16,(y-1)*16,(x+w+1)*16,(y+d+1)*16));ImageDraw.Draw(crop).rectangle((16,16,(w+1)*16-1,(d+1)*16-1),outline='red');crop.resize((crop.width*4,crop.height*4),Image.Resampling.NEAREST).save(target/'context.png')
        if args.previews:
            o,c=parts_model.build(e,np.asarray(source));parts_model.picture(o,c,target/'view.png',5);parts_model.front_view(o,c,target/'front.png',6);parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(c,(0,2,1,3))[:,:,::-1],target/'side.png',6)
        reason='Source-exact yellow-brick '+('two-door cycling gate' if len(case['doors'])==2 else 'Winstrate residence')+' with recessed gravel roof, low stone parapet, separate arched glazing, side pilasters and recessed pink entrances. Source WALK cells above the real warps stay clear below24 voxels; claimed northern WALK roof projections contain only overhead roof at30 voxels or higher.'
        if case['water_roof']:reason+=' All six northern WATER roof-projection cells are terrain-excluded and the physical roof starts at row1, preserving native water rendering and avoiding unsupported carriers.'
        ledger.append({'key':ident,'kind':'building','label':case['name'],'family':'mauville-yellow-brick-flat-roof','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(e),'source_pixel_sha256':case['source_sha256'],'source_pixel_mode':'RGB','occurrences':evidence(art,ident,e),'terrain_exclusions':e['terrain'],'reason':reason,'evidence':[{'source':(target/'source.png').relative_to(ROOT).as_posix(),'context':(target/'context.png').relative_to(ROOT).as_posix(),'python_views':[(target/f'{n}.png').relative_to(ROOT).as_posix() for n in ('front','side','view')]}],'visual_status':'source reviewed; Python/runtime candidate pending','user_approved':False});(target/'spec.json').write_text(json.dumps(e,indent=2)+'\n')
    for name,value in [('updates.json',{'cells':{},'buildings':entries}),('added-ledger.json',ledger)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'added':list(entries),'source_occurrences':3,'warp_count':5}))

if __name__=='__main__':main()
