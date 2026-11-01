"""Source-exact lower cable-car station, mirrored from the reviewed upper station."""
from __future__ import annotations
import argparse,base64,copy,hashlib,json
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald import building_trials as p,parts_model
from tools.shape_fit.emerald_pass.buildings_west import box,entry_recess,material
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,digest
OUT=ROOT/'local/model-gallery/emerald-pass-study/building-route112-cable-station'
IDENT='emerald_object_route112_cable_car_station'
UPPER='emerald_b108_EM_MT_CHIMNEY'
SOURCE_SHA256='5f69c6e03756d4db6f02ab1d92b703b3bc349edd997b00db6e86d912a4c5c82a'

def layout_for(art):
    g=art.map_cells(art.maps['EM_ROUTE112']);return [r[27:33] for r in g[23:28]]

def structure(original):
    parts=copy.deepcopy(original['parts'])
    for q in parts:
        assert 'x' in q
        q['x']=[96-q['x'][1],96-q['x'][0]]
        if q['shape']=='profile' and q['along']=='z':q['profile']=q['profile'][::-1]
        for face,rect in q.get('paint',{}).items():
            if isinstance(rect,list) and len(rect)==4:q['paint'][face]=[96-rect[2],rect[1],96-rect[0],rect[3]]
    # The upper station's complete orange roof and asymmetric side wing are
    # the same architecture, now supported on this lower station's own cells.
    # Its projected east roof cell is WALK, so retain actual low headroom.
    parts.append(box((64,80),(16,32),(0,24),carve=True))
    entry_recess(parts,(16,48),80,68,26,(16,64,48,80))
    parts[-1]['paint']=material((16,79,17,80))
    # The lower source's orange east wall has two dark horizontal vents.
    # Place them on the actual outer side, rather than losing them inside a
    # repeated wall swatch or copying their oblique sprite projection.
    for y in (18,32):parts.append(box((87,88),(46,62),(y,y+3),material((65,20,66,21))))
    return {'height':original['height'],'parts':parts}

def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);layout=layout_for(art)
    assert hashlib.sha256(art.drawing(layout,'BAKED').convert('RGB').tobytes()).hexdigest()==SOURCE_SHA256
    upper=next(b for b in table['buildings'] if b['id']==UPPER)
    return {IDENT:{'id':IDENT,'atlas':'g3q','tiles':quarters(layout),'terrain':[[5,0]],**structure(upper)}}

def evidence(art,e):
    places=building_catalog({'game':'emerald','cells':{},'buildings':[e]},art)[0]['occurrences'];assert places==[{'map':'EM_ROUTE112','x':27,'y':23,'palette':'BAKED'}]
    m=art.maps['EM_ROUTE112'];raw=base64.b64decode(m['cells']);cells=[]
    for dz,row in enumerate(layout_for(art)):
        for dx,k in enumerate(row):
            x,y=27+dx,23+dz;i=(y*m['w']+x)*4;cells.append({'key':k,'x':x,'y':y,'local':[dx,dz],'metatile':int.from_bytes(raw[i:i+2],'little'),'collision':raw[i+2],'elevation':raw[i+3],'terrain_excluded':[dx,dz] in e['terrain']})
    warps=[q for q in m['warps'] if 27<=q['x']<33 and 23<=q['y']<28];assert len(warps)==2 and all(q['to']=='EM_ROUTE112_CABLE_CAR_STATION' for q in warps)
    return [{**places[0],'width':6,'depth':5,'cells':cells,'warps':warps}]

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);entries=generate(table,art);e=entries[IDENT];OUT.mkdir(parents=True,exist_ok=True);source=p.drawing(e,BUNDLE);source.save(OUT/'source.png');source.resize((576,480),Image.Resampling.NEAREST).save(OUT/'source-enlarged.png')
    crop=art.map_image('EM_ROUTE112').crop((26*16,22*16,34*16,29*16));ImageDraw.Draw(crop).rectangle((16,16,111,95),outline='red');crop.resize((512,448),Image.Resampling.NEAREST).save(OUT/'context.png')
    if args.previews:
        o,c=parts_model.build(e,np.asarray(source));parts_model.picture(o,c,OUT/'view.png',4);parts_model.front_view(o,c,OUT/'front.png',5);parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(c,(0,2,1,3))[:,:,::-1],OUT/'side.png',5)
    upper=next(b for b in table['buildings'] if b['id']==UPPER)
    row={'key':IDENT,'kind':'building','label':'Route112 lower cable-car station','family':'orange-roof-cable-car-station','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(e),'source_pixel_sha256':SOURCE_SHA256,'source_pixel_mode':'RGB','occurrences':evidence(art,e),'terrain_exclusions':e['terrain'],'nearest_existing_template':UPPER,'reference_entry_sha256':digest(upper),'reason':'The complete source is the mirrored counterpart of the reviewed Mt Chimney station: broad orange curved roof, pale facade, right side wing and two-cell dark cable-car entrance. Mirror the explicit reviewed architecture and select its native lower-station UV rectangles; add a recessed entrance at the exact two lower-station warp cells. Preserve the unrelated northeastern conifer as terrain and keep every source WALK roof projection clear below24 voxels.','evidence':[{'source':(OUT/'source.png').relative_to(ROOT).as_posix(),'context':(OUT/'context.png').relative_to(ROOT).as_posix(),'source_cell_grid':'local/model-gallery/emerald-pass-study/lower-cable-car-source-grid.png'}],'visual_status':'source reviewed; Python and runtime candidate pending','user_approved':False}
    for name,value in [('updates.json',{'cells':{},'buildings':entries}),('added-ledger.json',[row]),('spec.json',e)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'candidate':IDENT,'parts':len(e['parts']),'source_occurrences':1,'warps':2}))

if __name__=='__main__':main()
