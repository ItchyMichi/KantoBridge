"""Exact Route109 striped umbrellas, folding chair and rolled beach mat."""
import argparse,base64,hashlib,json,math
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald import parts_model
from tools.shape_fit.emerald_pass.buildings_west import box
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters
from tools.shape_fit.emerald_pass.buildings_slateport_objects import color
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,digest
OUT=ROOT/'local/model-gallery/emerald-pass-study/buildings-route109-beach'
CASES={
 'emerald_object_route109_umbrella_blue':{'name':'Route109 blue striped beach umbrella','map':'EM_ROUTE109','x':24,'y':3,'w':2,'d':3,'count':4,'color':[90,197,239]},
 'emerald_object_route109_umbrella_orange':{'name':'Route109 orange striped beach umbrella','map':'EM_ROUTE109','x':9,'y':6,'w':2,'d':3,'count':7,'color':[247,115,74]},
 'emerald_object_route109_umbrella_green':{'name':'Route109 green striped beach umbrella','map':'EM_ROUTE109','x':13,'y':14,'w':2,'d':3,'count':3,'color':[148,230,115]},
 'emerald_object_route109_folding_chair':{'name':'Route109 blue striped folding chair','map':'EM_ROUTE109','x':17,'y':8,'w':1,'d':3,'count':17},
 'emerald_object_route109_rolled_mat':{'name':'Route109 green and blue striped rolled mat','map':'EM_ROUTE109','x':9,'y':9,'w':1,'d':3,'count':7},
}
SOURCE_HASHES={
 'emerald_object_route109_umbrella_blue':'4087c5823335f8708b58789b523002a49ee4982b4158cba2f82a1b2340ce34fd',
 'emerald_object_route109_umbrella_orange':'1da8be7b5518d7567769473872d43bd46876015222e4859e3d92734f5e6ad68a',
 'emerald_object_route109_umbrella_green':'e9242e47ac5813aa8857a1e3afd0608716707db9286f0b6cd45569e234f7dc9c',
 'emerald_object_route109_folding_chair':'4720a63207e70fc7a7fee5bead169b174f8e49b4fa20e4a2e2ce25f3923e5df1',
 'emerald_object_route109_rolled_mat':'d6d789b8a674d645fc1bd2b2a999ea0121b0f2296bee3a4e4d57cda84852f56f',
}

def layout_for(art,c):
    g=art.map_cells(art.maps[c['map']]);return [r[c['x']:c['x']+c['w']] for r in g[c['y']:c['y']+c['d']]]

def umbrella(draw,accent):
    white=color(draw,(255,255,255));stripe=color(draw,accent);pole=color(draw,(99,165,173));shade=color(draw,(189,206,230));edge=color(draw,(66,74,107));parts=[]
    # The source's upper row is canopy projection. The physical pole and thin
    # radial canopy stand over the lower two cells, without an extruded shadow.
    parts.append(box((15,17),(31,33),(0,35),pole));parts.append(box((15,16),(31,32),(0,34),edge))
    for z in range(17,47):
        for x in range(1,31):
            dx=x+.5-16;dz=z+.5-32;r=math.hypot(dx,dz)
            if r>15:continue
            top=int(round(35-8*r/15));sector=int(((math.atan2(dz,dx)+math.pi/8)%(2*math.pi))/(math.pi/4));paint=white if sector%2==0 else stripe
            parts.append(box((x,x+1),(z,z+1),(top-2,top),shade if r>14.3 else paint))
    parts.append({'shape':'dome','center':[16,32],'radius':2,'y':[34,37],'paint':white})
    return {'height':40,'parts':parts}

def folding_chair(draw):
    dark=color(draw,(66,74,107));frame=color(draw,(222,230,239));blue=color(draw,(156,181,222));white=color(draw,(255,255,255));teal=color(draw,(99,165,173));parts=[]
    # Four narrow grounded feet, two low rails, a canvas seat and an inclined
    # back. Metal side braces cross below the seat instead of forming a slab.
    for x in (2,12):
        parts.append(box((x,x+2),(17,20),(0,7),frame));parts.append(box((x,x+2),(36,39),(0,7),frame));parts.append(box((x,x+2),(19,39),(6,8),dark))
        for z in range(19,36):
            y=int(round(1+5*(z-19)/16));parts.append(box((x,x+2),(z,z+1),(y,y+2),frame));parts.append(box((x,x+2),(z,z+1),(7-y,9-y),frame))
    for x in range(4,12):parts.append(box((x,x+1),(25,39),(7,9),white if x%4<2 else blue))
    parts.append(box((2,14),(38,40),(7,10),teal))
    for z in range(15,26):
        y=8+int(round((25-z)*.9));parts.append(box((2,4),(z,z+1),(y,y+3),frame));parts.append(box((12,14),(z,z+1),(y,y+3),frame))
        for x in range(4,12):parts.append(box((x,x+1),(z,z+1),(y+1,y+3),white if x%4<2 else blue))
    parts.append(box((2,14),(14,16),(18,20),teal))
    return {'height':24,'parts':parts}

def rolled_mat(draw):
    dark=color(draw,(66,74,107));blue=color(draw,(90,197,239));teal=color(draw,(99,165,173));light=color(draw,(222,230,239));green=color(draw,(8,140,66));pillow=color(draw,(156,181,222));parts=[]
    parts.append(box((2,14),(14,39),(0,1),dark));stripes=[teal,blue,blue,light,green,green,light,blue,blue,teal]
    for x,paint in enumerate(stripes,3):parts.append(box((x,x+1),(15,38),(1,2),paint))
    # Rolled/padded head edge is a low connected cylinder across the mat.
    for z,h in ((11,2),(12,4),(13,5),(14,5),(15,4),(16,2)):
        parts.append(box((2,14),(z,z+1),(0,h),pillow if 12<=z<=15 else teal))
    parts.append(box((3,13),(12,14),(4,5),light))
    return {'height':8,'parts':parts}

def generate(table=None,art=None):
    art=art or EmeraldArt(BUNDLE);out={}
    for ident,c in CASES.items():
        layout=layout_for(art,c);draw=np.asarray(art.drawing(layout,'BAKED').convert('RGB'));assert hashlib.sha256(draw.tobytes()).hexdigest()==SOURCE_HASHES[ident];model=umbrella(draw,c['color']) if 'umbrella' in ident else folding_chair(draw) if ident.endswith('_chair') else rolled_mat(draw);out[ident]={'id':ident,'atlas':'g3q','tiles':quarters(layout),'terrain':[],**model}
    return out

def evidence(art,ident,e):
    c=CASES[ident];places=building_catalog({'game':'emerald','cells':{},'buildings':[e]},art)[0]['occurrences'];assert len(places)==c['count'];out=[]
    for loc in places:
        m=art.maps[loc['map']];g=art.map_cells(m);raw=base64.b64decode(m['cells']);cells=[]
        for dz in range(c['d']):
            for dx in range(c['w']):
                x,y=loc['x']+dx,loc['y']+dz;i=(y*m['w']+x)*4;assert raw[i+2]==0;cells.append({'key':g[y][x],'x':x,'y':y,'local':[dx,dz],'metatile':int.from_bytes(raw[i:i+2],'little'),'collision':raw[i+2],'elevation':raw[i+3],'terrain_excluded':False})
        warps=[q for q in m['warps'] if loc['x']<=q['x']<loc['x']+c['w'] and loc['y']<=q['y']<loc['y']+c['d']];assert not warps;out.append({**loc,'width':c['w'],'depth':c['d'],'cells':cells,'warps':[]})
    return out

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();art=EmeraldArt(BUNDLE);entries=generate(art=art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for ident,e in entries.items():
        c=CASES[ident];dest=OUT/ident;dest.mkdir(exist_ok=True);source=art.drawing(layout_for(art,c),'BAKED').convert('RGB');source.save(dest/'source.png');source.resize((source.width*8,source.height*8),Image.Resampling.NEAREST).save(dest/'source-enlarged.png');im=art.map_image(c['map']).crop(((c['x']-1)*16,(c['y']-1)*16,(c['x']+c['w']+1)*16,(c['y']+c['d']+1)*16));ImageDraw.Draw(im).rectangle((16,16,(c['w']+1)*16-1,(c['d']+1)*16-1),outline='red');im.resize((im.width*4,im.height*4),Image.Resampling.NEAREST).save(dest/'context.png')
        if args.previews:
            o,colors=parts_model.build(e,np.asarray(source));parts_model.picture(o,colors,dest/'view.png',6);parts_model.front_view(o,colors,dest/'front.png',8);parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(colors,(0,2,1,3))[:,:,::-1],dest/'side.png',8)
        reason='The exact native two-by-three umbrella source becomes one thin eight-panel canopy in its source-specific color, supported by the small blue-gray pole. Canopy geometry stays overhead; the broad brown sand shadow is reference art only and has no physical mass.' if 'umbrella' in ident else 'The exact one-by-three folding chair source becomes a supported blue/white striped canvas seat and inclined back, narrow metal side rails, crossed lower braces and four grounded feet. No sand becomes upholstery or a deep pedestal.' if ident.endswith('_chair') else 'The exact one-by-three rolled mat source becomes a two-voxel thin green/blue striped textile with a low connected padded roll at its head. It stays grounded, without the old disconnected source slices or a sand-colored base.'
        ledger.append({'key':ident,'kind':'building','label':c['name'],'family':'route109-complete-beach-furniture','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(e),'source_pixel_sha256':hashlib.sha256(source.tobytes()).hexdigest(),'source_pixel_mode':'RGB','occurrences':evidence(art,ident,e),'terrain_exclusions':[],'reason':reason+' Every exact source cell is WALK0; retain native noncollision and use template decoration only. No new source flags, stack links or collision metadata. Exact all-occurrence runtime checks remain required.','evidence':[{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'context':(dest/'context.png').relative_to(ROOT).as_posix(),'source_coverage_audit':'local/model-gallery/emerald-pass-study/towns-slateport/source-audit.json'}],'visual_status':'source/context inspected; Python/runtime review pending','user_approved':False});(dest/'spec.json').write_text(json.dumps(e,indent=2)+'\n')
    for name,value in [('updates.json',{'cells':{},'buildings':entries}),('added-ledger.json',ledger)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'new_objects':5,'source_occurrences':sum(c['count'] for c in CASES.values()),'parts':{k:len(e['parts']) for k,e in entries.items()}}))

if __name__=='__main__':main()
