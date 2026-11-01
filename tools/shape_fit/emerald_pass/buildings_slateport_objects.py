"""Complete source-specific dry display boat and Slateport lighthouse."""
import argparse,base64,hashlib,json
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald import parts_model
from tools.shape_fit.emerald_pass.buildings_west import box,material
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,digest
OUT=ROOT/'local/model-gallery/emerald-pass-study/buildings-slateport-objects'
CASES={
 'emerald_object_slateport_display_boat':{'name':'Slateport dry display boat','map':'EM_SLATEPORT_CITY','x':21,'y':35,'w':3,'d':2,'source_sha256':'e7875a9883cce73c0cbb71190afcb195c75acbe3364063416517cd6472f9909e'},
 'emerald_object_slateport_lighthouse':{'name':'Slateport lighthouse','map':'EM_SLATEPORT_CITY','x':32,'y':51,'w':2,'d':4,'source_sha256':'197fc8c1cfb068972bfcb4530904cfe9534abc79a96350555200874144990e88'},
}

def layout_for(art,c):
    g=art.map_cells(art.maps[c['map']]);return [r[c['x']:c['x']+c['w']] for r in g[c['y']:c['y']+c['d']]]

def color(draw,rgb):
    hits=np.argwhere((draw==rgb).all(axis=2));assert len(hits),rgb;y,x=map(int,hits[0]);return material((x,y,x+1,y+1))

def boat(draw):
    dark=color(draw,(66,74,107));hull=color(draw,(165,156,132));deck=color(draw,(115,115,123));rim=color(draw,(230,230,206));pale=color(draw,(189,189,156));parts=[]
    # Explicit fore/aft outline: narrow rounded west bow, broad middle and
    # curved east stern. Each section flares from a grounded narrow keel.
    controls=[(2,2),(5,7),(10,11),(16,12),(36,12),(41,11),(45,7),(47,2)]
    radii={x:float(np.interp(x+.5,[q[0] for q in controls],[q[1] for q in controls])) for x in range(2,47)}
    for x,r in radii.items():
        for y,factor in ((0,.45),(2,.62),(4,.82)):
            lo=int(np.ceil(16-r*factor));hi=int(np.floor(16+r*factor));parts.append(box((x,x+1),(lo,hi),(y,y+2),dark if y==0 else hull))
        lo=int(np.ceil(16-r));hi=int(np.floor(16+r));parts.append(box((x,x+1),(lo,hi),(6,12),hull))
        if hi-lo>4:
            parts.append(box((x,x+1),(lo+2,hi-2),(7,13),carve=True))
            parts.append(box((x,x+1),(lo+2,hi-2),(6,7),deck))
        parts.append(box((x,x+1),(lo,lo+2),(11,13),rim));parts.append(box((x,x+1),(hi-2,hi),(11,13),rim))
    # Source closed bow slats, two transverse seats and the thin near-side
    # projecting oar rest on the hull rather than forming separate walls.
    for x in range(5,15):
        r=radii[x];lo=int(np.ceil(16-r))+2;hi=int(np.floor(16+r))-2
        if hi>lo:parts.append(box((x,x+1),(lo,hi),(10,12),pale if x%3==0 else deck))
    for x in (18,34):parts.append(box((x,x+3),(5,27),(9,12),pale))
    parts.append(box((35,36),(19,31),(12,14),rim));parts.append(box((34,37),(28,32),(11,13),hull))
    return {'height':16,'parts':parts}

def lighthouse(draw):
    white=color(draw,(255,255,255));shade=color(draw,(197,181,222));edge=color(draw,(123,132,156));blue=color(draw,(41,49,90));parts=[]
    def cylinder(r,y,paint):parts.append({'shape':'cylinder','center':[16,48],'radius':r,'y':list(y),'paint':paint})
    cylinder(14,(0,3),edge);cylinder(14,(3,5),white);parts.append({'shape':'cone','center':[16,48],'radius':14,'y':[5,13],'paint':white});cylinder(6.5,(8,34),white)
    # A small source dark slit on the front of the solid tower shaft.
    parts.append(box((14,18),(53,56),(17,23),carve=True));parts.append(box((14,18),(53,54),(17,23),blue))
    cylinder(13,(31,33),shade);cylinder(15,(33,36),white);cylinder(12.5,(36,49),white);cylinder(10.5,(37,48),blue)
    for x,z in [((11,21),(35,39)),((11,21),(57,61)),((3,7),(43,53)),((25,29),(43,53))]:parts.append(box(x,z,(39,47),carve=True))
    # Recessed dark panes sit behind four real white corner/pier sections.
    for x,z in [((11,21),(38,39)),((11,21),(57,58)),((6,7),(43,53)),((25,26),(43,53))]:parts.append(box(x,z,(39,47),blue))
    cylinder(13,(48,50),shade);cylinder(14.5,(50,52),white);parts.append({'shape':'cone','center':[16,48],'radius':14.5,'y':[52,63],'paint':white});cylinder(5.8,(59,63),shade);parts.append({'shape':'dome','center':[16,48],'radius':5.8,'y':[63,69],'paint':white})
    return {'height':72,'parts':parts}

def generate(table=None,art=None):
    art=art or EmeraldArt(BUNDLE);out={}
    for ident,c in CASES.items():
        layout=layout_for(art,c);draw=np.asarray(art.drawing(layout,'BAKED').convert('RGB'));assert hashlib.sha256(draw.tobytes()).hexdigest()==c['source_sha256'];out[ident]={'id':ident,'atlas':'g3q','tiles':quarters(layout),'terrain':[],**(boat(draw) if ident.endswith('_boat') else lighthouse(draw))}
    return out

def evidence(art,ident,e):
    c=CASES[ident];places=building_catalog({'game':'emerald','cells':{},'buildings':[e]},art)[0]['occurrences'];assert places==[{'map':c['map'],'x':c['x'],'y':c['y'],'palette':'BAKED'}];m=art.maps[c['map']];raw=base64.b64decode(m['cells']);cells=[]
    for dz,row in enumerate(layout_for(art,c)):
        for dx,k in enumerate(row):
            x,y=c['x']+dx,c['y']+dz;i=(y*m['w']+x)*4;cells.append({'key':k,'x':x,'y':y,'local':[dx,dz],'metatile':int.from_bytes(raw[i:i+2],'little'),'collision':raw[i+2],'elevation':raw[i+3],'terrain_excluded':False})
    warps=[q for q in m['warps'] if c['x']<=q['x']<c['x']+c['w'] and c['y']<=q['y']<c['y']+c['d']];assert not warps;return [{**places[0],'width':c['w'],'depth':c['d'],'cells':cells,'warps':[]}]

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();art=EmeraldArt(BUNDLE);entries=generate(art=art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for ident,e in entries.items():
        c=CASES[ident];dest=OUT/ident;dest.mkdir(exist_ok=True);source=art.drawing(layout_for(art,c),'BAKED').convert('RGB');source.save(dest/'source.png');source.resize((source.width*6,source.height*6),Image.Resampling.NEAREST).save(dest/'source-enlarged.png');im=art.map_image(c['map']).crop(((c['x']-1)*16,(c['y']-1)*16,(c['x']+c['w']+1)*16,(c['y']+c['d']+1)*16));ImageDraw.Draw(im).rectangle((16,16,(c['w']+1)*16-1,(c['d']+1)*16-1),outline='red');im.resize((im.width*4,im.height*4),Image.Resampling.NEAREST).save(dest/'context.png')
        if args.previews:
            o,colors=parts_model.build(e,np.asarray(source));parts_model.picture(o,colors,dest/'view.png',5);parts_model.front_view(o,colors,dest/'front.png',6);parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(colors,(0,2,1,3))[:,:,::-1],dest/'side.png',6)
        reason='Model the complete dry display boat on its six actual SOLID grass-side cells: a grounded flaring hull, open gray interior, pale continuous gunwale, covered bow slats, two supported cross seats and the source near-side projecting oar. Paint only native boat materials; grass does not become hull or a giant base plate.' if ident.endswith('_boat') else 'Model the complete lighthouse over its four lower SOLID base cells: a rounded stepped white foot, narrow supported shaft, tiny dark shaft slit, broad lantern gallery with recessed dark panes, white piers and ring, conical white roof and small raised cap. The four upper source WALK cells are projection only; all physical geometry lies in the lower32×32 footprint and the upper source rows remain entirely empty. No invented warp or stack metadata.'
        ledger.append({'key':ident,'kind':'building','label':c['name'],'family':'slateport-complete-source-objects','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(e),'source_pixel_sha256':c['source_sha256'],'source_pixel_mode':'RGB','occurrences':evidence(art,ident,e),'terrain_exclusions':[],'reason':reason,'evidence':[{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'context':(dest/'context.png').relative_to(ROOT).as_posix(),'source_coverage_audit':'local/model-gallery/emerald-pass-study/towns-slateport/source-audit.json'}],'visual_status':'source/context inspected; Python and runtime pending','user_approved':False});(dest/'spec.json').write_text(json.dumps(e,indent=2)+'\n')
    for name,value in [('updates.json',{'cells':{},'buildings':entries}),('added-ledger.json',ledger)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'new_objects':2,'parts':{k:len(e['parts']) for k,e in entries.items()}}))

if __name__=='__main__':main()
