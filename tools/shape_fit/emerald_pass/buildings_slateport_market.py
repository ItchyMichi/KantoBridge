"""Four exact blue market canopy variants absent from the original templates."""
import argparse,base64,hashlib,json
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald import parts_model
from tools.shape_fit.emerald_pass.buildings_west import box,material
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters
from tools.shape_fit.emerald_pass.buildings_slateport_objects import color
from tools.shape_fit.emerald_pass import towns_slateport_market as goods
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,digest
OUT=ROOT/'local/model-gallery/emerald-pass-study/buildings-slateport-market'
CASES={
 'emerald_object_slateport_canopy_west_single':{'x':2,'y':45,'w':5,'items':[(1,'bottles')]},
 'emerald_object_slateport_canopy_west_pair':{'x':2,'y':49,'w':5,'items':[(1,'bottles'),(2,'bottles')]},
 'emerald_object_slateport_canopy_east_empty':{'x':10,'y':45,'w':4,'items':[]},
 'emerald_object_slateport_canopy_east_green':{'x':10,'y':49,'w':4,'items':[(2,'green')]},
}
MAP='EM_SLATEPORT_CITY'
SOURCE_HASHES={(2,45):'0bb1a78353786ccf605b31e3da9c3c77057bcd25837d8569cbeabb9c516a4ef4',(2,49):'576205f74017140b1f7c480278af8c1b90d60063f91168f0bcbdc24e22d5d63a',(10,45):'3d992911453d2988f6addc8b240f9dee2b7fd3834e70d6d1755f0e521c18203e',(10,49):'950e2e87ad53474c1cc5af25cd19beaa014d1c417fea22c4042f26a7be8d84e9'}

def layout_for(art,c):
    g=art.map_cells(art.maps[MAP]);return [r[c['x']:c['x']+c['w']] for r in g[c['y']:c['y']+3]]

def goods_parts(model,x0,z0,source_x,source_y):
    # Lossless x-runs of the already reviewed explicit tray/tub sculpture,
    # with every local material address translated to this whole's own art.
    out=[]
    for y in range(model.occ.shape[0]):
        for z in range(model.occ.shape[1]):
            x=0
            while x<model.occ.shape[2]:
                if not model.occ[y,z,x]:x+=1;continue
                a=x;uv=int(model.paint[y,z,x]);x+=1
                while x<model.occ.shape[2] and model.occ[y,z,x] and int(model.paint[y,z,x])==uv:x+=1
                sx=source_x+uv%16;sy=source_y+uv//16;out.append(box((x0+a,x0+x),(z0+z,z0+z+1),(y,y+1),material((sx,sy,sx+1,sy+1))))
    return out

def geometry(draw,c):
    width=c['w']*16;dark=color(draw,(66,74,107));wood=color(draw,(197,132,66));shade=color(draw,(123,99,66));blue=color(draw,(123,148,247));parts=[]
    # The tall source roof is perspective projection. The actual four posts
    # all sit within the two lower SOLID support cells; projected WALK rows
    # have only an overhead roof and stay open below it.
    for x in (9,width-13):
        for z in (34,43):
            parts.append(box((x,x+4),(z,z+4),(0,29),shade));parts.append(box((x+1,x+3),(z+3,z+4),(2,28),wood));parts.append(box((x-1,x+5),(z-1,z+5),(0,2),dark))
        parts.append(box((x,x+4),(34,47),(25,29),shade))
    parts.append(box((8,width-8),(24,48),(26,29),{'front':[8,25,width-8,29],'side':[9,26,15,28],'back':[9,26,width-9,28]}));parts.append(box((10,width-10),(26,46),(26,28),carve=True))
    parts.append({'shape':'hip','x':[8,width-8],'z':[24,48],'y':[28,40],'paint':{'top':[9,1,width-9,25],'front':[9,18,width-9,25],'side':[width-16,8,width-9,24],'back':[9,2,width-9,4]}})
    for x,mode in c['items']:
        sprite=Image.fromarray(draw[32:48,x*16:(x+1)*16]);model=goods.tray(sprite,True) if mode=='bottles' else goods.vessel(sprite,'green');parts.extend(goods_parts(model,x*16,32,x*16,32))
    return {'height':40,'parts':parts}

def generate(table=None,art=None):
    art=art or EmeraldArt(BUNDLE);out={}
    for ident,c in CASES.items():
        layout=layout_for(art,c);draw=np.asarray(art.drawing(layout,'BAKED').convert('RGB'));assert hashlib.sha256(draw.tobytes()).hexdigest()==SOURCE_HASHES[c['x'],c['y']];out[ident]={'id':ident,'atlas':'g3q','tiles':quarters(layout),'terrain':[],**geometry(draw,c)}
    return out

def evidence(art,ident,e):
    c=CASES[ident];places=building_catalog({'game':'emerald','cells':{},'buildings':[e]},art)[0]['occurrences'];assert places==[{'map':MAP,'x':c['x'],'y':c['y'],'palette':'BAKED'}];m=art.maps[MAP];raw=base64.b64decode(m['cells']);cells=[]
    for dz,row in enumerate(layout_for(art,c)):
        for dx,k in enumerate(row):
            x,y=c['x']+dx,c['y']+dz;i=(y*m['w']+x)*4;cells.append({'key':k,'x':x,'y':y,'local':[dx,dz],'metatile':int.from_bytes(raw[i:i+2],'little'),'collision':raw[i+2],'elevation':raw[i+3],'terrain_excluded':False})
    assert not [q for q in m['warps'] if c['x']<=q['x']<c['x']+c['w'] and c['y']<=q['y']<c['y']+3];return [{**places[0],'width':c['w'],'depth':3,'cells':cells,'warps':[]}]

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();art=EmeraldArt(BUNDLE);entries=generate(art=art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for ident,e in entries.items():
        c=CASES[ident];dest=OUT/ident;dest.mkdir(exist_ok=True);source=art.drawing(layout_for(art,c),'BAKED').convert('RGB');source.save(dest/'source.png');source.resize((source.width*6,source.height*6),Image.Resampling.NEAREST).save(dest/'source-enlarged.png');im=art.map_image(MAP).crop(((c['x']-1)*16,(c['y']-1)*16,(c['x']+c['w']+1)*16,(c['y']+4)*16));ImageDraw.Draw(im).rectangle((16,16,(c['w']+1)*16-1,63),outline='red');im.resize((im.width*4,im.height*4),Image.Resampling.NEAREST).save(dest/'context.png')
        if args.previews:
            o,colors=parts_model.build(e,np.asarray(source));parts_model.picture(o,colors,dest/'view.png',5);parts_model.front_view(o,colors,dest/'front.png',6);parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(colors,(0,2,1,3))[:,:,::-1],dest/'side.png',6)
        reason='Add this exact missing '+str(c['w'])+'-cell-wide blue canopy variant, preserving its '+('empty display space' if not c['items'] else 'single green-filled tub' if c['items'][0][1]=='green' else str(len(c['items']))+' bottle-and-bundle trays')+'. Four narrow timber posts stand only in the two lower source SOLID support cells. The roof is supported above an open space, all source WALK columns are clear below26voxels, and source roof projection does not become extra ground-level posts. Exact goods reuse the separately reviewed explicit tray/tub sculpture with this source pattern own pixels; no ground/shadow material is extruded.'
        ledger.append({'key':ident,'kind':'building','label':ident.replace('emerald_object_','').replace('_',' '),'family':'slateport-missing-blue-market-canopies','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(e),'source_pixel_sha256':hashlib.sha256(source.tobytes()).hexdigest(),'source_pixel_mode':'RGB','occurrences':evidence(art,ident,e),'terrain_exclusions':[],'reason':reason,'evidence':[{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'context':(dest/'context.png').relative_to(ROOT).as_posix(),'source_cases':(OUT/'source-cases.json').relative_to(ROOT).as_posix(),'related_reviewed_templates':['emerald_b078_EM_SLATEPORT_CITY','emerald_b079_EM_SLATEPORT_CITY']}],'visual_status':'source/context inspected; Python/runtime pending','user_approved':False});(dest/'spec.json').write_text(json.dumps(e,indent=2)+'\n')
    for name,value in [('updates.json',{'cells':{},'buildings':entries}),('added-ledger.json',ledger)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'new_objects':4,'parts':{k:len(e['parts']) for k,e in entries.items()}}))

if __name__=='__main__':main()
