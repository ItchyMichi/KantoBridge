"""Source-exact Route113 glass workshop, missing from the original templates."""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald import building_trials as p,parts_model
from tools.shape_fit.emerald_pass.buildings_west import house,box
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,digest

OUT=ROOT/'local/model-gallery/emerald-pass-study/building-glass-workshop'
IDENT='emerald_object_route113_glass_workshop'
SOURCE_SHA256='7b9751d54dc95aa656be462cfb5e6df4bf6168cc23cd70dce8c04cf984365dcf'


def layout_for(art):
    g=art.map_cells(art.maps['EM_ROUTE113']);return [r[32:36] for r in g[2:6]]


def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);layout=layout_for(art)
    assert hashlib.sha256(art.drawing(layout,'BAKED').convert('RGB').tobytes()).hexdigest()==SOURCE_SHA256
    spec=house(46)
    # Same native green plank-wall/window architecture as the reviewed
    # Fallarbor residences, with its distinct gray/olive source roof.
    # Only the overhead roof enters the source's northern WALK projection.
    spec['parts'][0]['z'][0]=16
    # The upper window drawing at local(2,2) is also WALK0. Preserve that
    # source column below the eaves, instead of turning it into a low wall.
    spec['parts'].append(box((32,48),(32,48),(0,28),carve=True))
    return {IDENT:{'id':IDENT,'atlas':'g3q','tiles':quarters(layout),'terrain':[],**spec}}


def evidence(art,entry):
    places=building_catalog({'game':'emerald','cells':{},'buildings':[entry]},art)[0]['occurrences'];assert places==[{'map':'EM_ROUTE113','x':32,'y':2,'palette':'BAKED'}]
    result=[]
    for loc in places:
        m=art.maps[loc['map']];raw=base64.b64decode(m['cells']);cells=[]
        for dz,row in enumerate(layout_for(art)):
            for dx,k in enumerate(row):
                x,y=loc['x']+dx,loc['y']+dz;i=(y*m['w']+x)*4;cells.append({'key':k,'x':x,'y':y,'local':[dx,dz],'metatile':int.from_bytes(raw[i:i+2],'little'),'collision':raw[i+2],'elevation':raw[i+3],'terrain_excluded':False})
        result.append({**loc,'width':4,'depth':4,'cells':cells,'warps':[q for q in m['warps'] if 32<=q['x']<36 and 2<=q['y']<6]})
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args()
    art=EmeraldArt(BUNDLE);table=json.loads(BASELINE.read_text());entries=generate(table,art);e=entries[IDENT];OUT.mkdir(parents=True,exist_ok=True)
    assert not any(b['tiles']==e['tiles'] for b in table['buildings']);source=p.drawing(e,BUNDLE);source.save(OUT/'source.png');source.resize((512,512),Image.Resampling.NEAREST).save(OUT/'source-enlarged.png')
    crop=art.map_image('EM_ROUTE113').crop((31*16,1*16,37*16,7*16));ImageDraw.Draw(crop).rectangle((16,16,79,79),outline='red');crop.resize((576,576),Image.Resampling.NEAREST).save(OUT/'context.png')
    if args.previews:
        o,c=parts_model.build(e,np.asarray(source));parts_model.picture(o,c,OUT/'view.png',5);parts_model.front_view(o,c,OUT/'front.png',6);parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(c,(0,2,1,3))[:,:,::-1],OUT/'side.png',6)
    nearest=min((b for b in table['buildings'] if len(b['tiles'])==8 and len(b['tiles'][0])==8),key=lambda b:sum(a!=c for r,s in zip(b['tiles'],e['tiles']) for a,c in zip(r,s)))
    row={'key':IDENT,'kind':'building','label':'Route113 glass workshop','family':'fallarbor-green-plank-house','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(e),'source_pixel_sha256':SOURCE_SHA256,'source_pixel_mode':'RGB','occurrences':evidence(art,e),'terrain_exclusions':[],'nearest_existing_template':nearest['id'],'source_quarter_substitutions':[{'x':x,'y':y,'old':a,'new':b} for y,(rr,ss) in enumerate(zip(nearest['tiles'],e['tiles'])) for x,(a,b) in enumerate(zip(rr,ss)) if a!=b],'reason':'Exact4x4 workshop at Route113(32,2), with its native gray/olive ribbed roof, green plank walls, left door and separate blue right window. The same architecture as the reviewed Fallarbor houses is reconstructed as one continuous roof/wall object. Northern WALK roof cells and the interior WALK window-projection column stay empty below y28; the physical facade occupies the front SOLID/warp row. Source collision/elevation and the Glass Workshop warp remain unchanged.','evidence':[{'source':(OUT/'source.png').relative_to(ROOT).as_posix(),'context':(OUT/'context.png').relative_to(ROOT).as_posix(),'python_views':[(OUT/f'{n}.png').relative_to(ROOT).as_posix() for n in ('front','side','view')]}],'visual_status':'source reviewed; Python/runtime pending','user_approved':False}
    for name,value in [('updates.json',{'cells':{},'buildings':entries}),('added-ledger.json',[row]),('spec.json',e)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'added':IDENT,'occurrences':1,'nearest_existing_template':nearest['id'],'source_quarter_substitutions':len(row['source_quarter_substitutions'])}))


if __name__=='__main__':main()
