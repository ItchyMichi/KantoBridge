"""Exact missing Slateport Harbor and Route109 Seashore House templates."""
import argparse,base64,hashlib,json,math
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald import building_trials as p,parts_model
from tools.shape_fit.emerald_pass.buildings_west import box,material,entry_recess
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,digest
OUT=ROOT/'local/model-gallery/emerald-pass-study/buildings-slateport-coast'
CASES={
 'emerald_object_slateport_harbor':{'name':'Slateport Harbor terminal','map':'EM_SLATEPORT_CITY','x':25,'y':7,'w':7,'d':6,'to':'EM_SLATEPORT_CITY_HARBOR','source_sha256':'484caa389eb0d28b8d30fd5b877e06b9939042d14f93cff20447e2d675130e54','family':'orange-barrel-roof-nautical-terminal'},
 'emerald_object_route109_seashore_house':{'name':'Route109 Seashore House','map':'EM_ROUTE109','x':10,'y':2,'w':5,'d':4,'to':'EM_ROUTE109_SEASHORE_HOUSE','source_sha256':'5080dce42561415d401eb8f9a49f4009e18eca0606280c088ac8b31785ebf15a','family':'purple-diamond-roof-beach-house'},
}

def layout_for(art,c):
    g=art.map_cells(art.maps[c['map']]);return [r[c['x']:c['x']+c['w']] for r in g[c['y']:c['y']+c['d']]]

def harbor():
    blue=p.paint([5,64,13,80],[5,64,6,65],[5,64,13,80]);dark=material((0,60,1,61));parts=[box((2,110),(8,92),(0,48),blue),box((1,111),(8,93),(0,4),material((2,93,3,94)))]
    # The repeated nautical frieze is a low front band. It must not stretch
    # one emblem over the whole facade or repeat the door on the side.
    parts.append(box((1,111),(91,93),(4,18),p.paint([1,80,111,94],side=[2,84,3,85])))
    parts.append(box((0,112),(5,94),(47,50),dark))
    parts.append({'shape':'profile','x':[0,112],'z':[3,94],'y':[50,66],'along':'x','profile':[3,8,12,14,15,15,14,12,8,3],'paint':p.paint([0,56,112,62],[2,8,110,62],[4,52,8,62])})
    # Round porthole frames remain circular in geometry instead of being
    # vertically stretched by a taller facade UV.
    for center,sourcex in ((32,24),(80,72)):
        for yy in range(16):
            inset=8-math.sqrt(max(0,8**2-(yy+.5-8)**2));lo=int(math.ceil(inset));hi=16-lo
            if hi>lo:parts.append(box((center-8+lo,center-8+hi),(92,94),(26+yy,27+yy),p.paint([sourcex+lo,80-yy,sourcex+hi,81-yy],side=[25,66,26,67])))
    # Separate projecting doorway frame with its localized nautical badge.
    frame=p.paint([40,73,72,96],[40,73,72,76],[40,78,43,94]);parts.append(box((40,72),(90,96),(0,32),frame))
    entry_recess(parts,(48,64),96,84,26,(48,82,64,96));parts[-1]['paint']=material((42,94,43,95))
    parts.append(box((49,63),(95,96),(27,35),p.paint([49,73,63,82],side=[49,73,50,74])))
    return {'height':72,'parts':parts}

def seashore():
    gold=material((11,58,12,59));trim=material((2,34,3,35));parts=[box((3,77),(17,61),(0,30),gold)]
    for x,uv in ((1,[1,36,7,64]),(73,[73,36,79,64])):parts.append(box((x,x+6),(59,63),(0,31),p.paint(uv,side=[3,42,4,43])))
    parts.append(box((0,80),(1,64),(30,33),trim))
    parts.append({'shape':'gable','ridge':'x','x':[0,80],'z':[1,64],'y':[33,56],'paint':p.paint([0,31,80,37],[0,1,80,36],[10,20,11,21])})
    parts.append(box((0,80),(30,35),(54,58),p.paint([0,0,80,6],[0,0,80,6],[0,0,4,6])))
    for lo in (16,52):parts.append(box((lo,lo+14),(61,63),(9,26),p.paint([lo,39,lo+14,55],side=[23,42,24,43])))
    entry_recess(parts,(32,48),64,55,27,(32,43,48,64));parts[-1]['paint']=material((34,62,35,63))
    # Native north roof projection is WALK0; its low column remains clear.
    parts.append(box((0,80),(0,16),(0,24),carve=True))
    return {'height':64,'parts':parts}

def generate(table=None,art=None):
    art=art or EmeraldArt(BUNDLE);out={}
    for ident,c in CASES.items():
        layout=layout_for(art,c);source=art.drawing(layout,'BAKED').convert('RGB');assert hashlib.sha256(source.tobytes()).hexdigest()==c['source_sha256']
        out[ident]={'id':ident,'atlas':'g3q','tiles':quarters(layout),'terrain':[],**(harbor() if ident.endswith('_harbor') else seashore())}
    return out

def evidence(art,ident,e):
    c=CASES[ident];places=building_catalog({'game':'emerald','cells':{},'buildings':[e]},art)[0]['occurrences'];assert places==[{'map':c['map'],'x':c['x'],'y':c['y'],'palette':'BAKED'}]
    m=art.maps[c['map']];raw=base64.b64decode(m['cells']);cells=[]
    for dz,row in enumerate(layout_for(art,c)):
        for dx,k in enumerate(row):
            x,y=c['x']+dx,c['y']+dz;i=(y*m['w']+x)*4;cells.append({'key':k,'x':x,'y':y,'local':[dx,dz],'metatile':int.from_bytes(raw[i:i+2],'little'),'collision':raw[i+2],'elevation':raw[i+3],'terrain_excluded':False})
    warps=[q for q in m['warps'] if c['x']<=q['x']<c['x']+c['w'] and c['y']<=q['y']<c['y']+c['d']];assert len(warps)==1 and warps[0]['to']==c['to'];return [{**places[0],'width':c['w'],'depth':c['d'],'cells':cells,'warps':warps}]

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();art=EmeraldArt(BUNDLE);entries=generate(art=art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for ident,e in entries.items():
        c=CASES[ident];dest=OUT/ident;dest.mkdir(exist_ok=True);source=art.drawing(layout_for(art,c),'BAKED').convert('RGB');source.save(dest/'source.png');source.resize((source.width*4,source.height*4),Image.Resampling.NEAREST).save(dest/'source-enlarged.png');im=art.map_image(c['map']).crop(((c['x']-1)*16,(c['y']-1)*16,(c['x']+c['w']+1)*16,(c['y']+c['d']+1)*16));ImageDraw.Draw(im).rectangle((16,16,(c['w']+1)*16-1,(c['d']+1)*16-1),outline='red');im.resize((im.width*3,im.height*3),Image.Resampling.NEAREST).save(dest/'context.png')
        if args.previews:
            o,colors=parts_model.build(e,np.asarray(source));parts_model.picture(o,colors,dest/'view.png',4);parts_model.front_view(o,colors,dest/'front.png',5);parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(colors,(0,2,1,3))[:,:,::-1],dest/'side.png',5)
        reason='Reconstruct the exact Slateport Harbor terminal with a broad orange rounded ribbed roof, blue horizontal cladding, repeated low nautical frieze, two circular portholes and a separate recessed central entry with a localized life-ring badge. This unique7×6 source is not the reviewed Lilycove terminal pattern; preserve its actual Harbor warp and native forest artwork outside physical roof materials.' if ident.endswith('_harbor') else 'Reconstruct the exact five-cell-wide Seashore House with purple diamond-pattern gabled roof, raised pale ridge cap, two yellow front pilasters, two square blue windows and an open central entrance at the true Route109 warp. Keep the entire north WALK roof-projection row empty below24voxels; its roof may overhang above that clear space. Existing four- and six-cell Slateport houses have different source patterns and widths.'
        ledger.append({'key':ident,'kind':'building','label':c['name'],'family':c['family'],'decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(e),'source_pixel_sha256':c['source_sha256'],'source_pixel_mode':'RGB','occurrences':evidence(art,ident,e),'terrain_exclusions':e['terrain'],'reason':reason,'evidence':[{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'context':(dest/'context.png').relative_to(ROOT).as_posix(),'source_coverage_audit':'local/model-gallery/emerald-pass-study/towns-slateport/source-audit.json'}],'visual_status':'source/context inspected; Python and runtime candidate pending','user_approved':False});(dest/'spec.json').write_text(json.dumps(e,indent=2)+'\n')
    for name,value in [('updates.json',{'cells':{},'buildings':entries}),('added-ledger.json',ledger)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'new_buildings':2,'parts':{k:len(e['parts']) for k,e in entries.items()}}))

if __name__=='__main__':main()
