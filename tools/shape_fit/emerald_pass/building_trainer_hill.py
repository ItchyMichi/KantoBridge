"""Trainer Hill's upper hall and lower entrance with exact native cliff interfaces."""
from __future__ import annotations
import argparse,base64,hashlib,json,re
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald import building_trials as p,parts_model
from tools.shape_fit.emerald_pass.buildings_west import box,material,entry_recess
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,digest
OUT=ROOT/'local/model-gallery/emerald-pass-study/building-trainer-hill'
PROOF=ROOT/'local/model-gallery/emerald-pass-study/towns-mauville/trainer-hill-runtime-proof.json'
PROOF_SHA256='53a0f50b0dddba02a72c0cfdac24dcfcf22a72982243bd84b8151329d6ed367e'
IDENT='emerald_object_route111_trainer_hill'
SOURCE_SHA256='8084aa3705c19ddf3ce5ed91e8ae57f87f42fede9e479f5685edac518e2adf80'
ORIGIN=(29,105);SIZE=(5,9)
TERRAIN=[[x,z] for z in (0,6) for x in range(5)]+[[x,z] for z in (7,8) for x in (0,1,3,4)]

def layout_for(art):
    g=art.map_cells(art.maps['EM_ROUTE111']);return [r[29:34] for r in g[105:114]]

def roof_heights(h0,h1,west,east):
    """Exact KeyGeometry top triangles sampled at eight author-voxel centres.

    Native MC16 becomes author8. Nonplanar quads split NW->SE, never
    bilinear interpolation; double hips have the explicit middle ridge.
    """
    z,x=np.indices((8,8));u=(x+.5)/8;v=(z+.5)/8
    def quad(a,b,c,d,uu):
        return np.where(v>=uu,a+(c-b)*uu+(b-a)*v,a+(d-a)*uu+(c-d)*v)
    if west and east:
        left=quad(0,0,h0,h1,u*2);right=quad(h1,h0,0,0,(u-.5)*2)
        return np.where(u<.5,left,right)/2
    return quad(0 if west else h1,0 if west else h0,0 if east else h0,0 if east else h1,u)/2

def native_foundation():
    payload=PROOF.read_bytes();assert hashlib.sha256(payload).hexdigest()==PROOF_SHA256
    proof=json.loads(payload)['occurrences'][0];h=np.zeros((144,80),float)
    for c in proof['source_column_audit']:
        cx,cz=c['x']-29,c['y']-105
        if not (0<=cx<5 and 0<=cz<9) or [cx,cz] in TERRAIN:continue
        for q in c['columns']:
            keys=re.split(r', (?=[a-z_]+:)',q['before_keys'][1:-1]) if q['before_blocks'] else []
            assert len(keys)==q['before_blocks']
            top=np.zeros((8,8))
            for layer,k in enumerate(keys):
                if k.startswith('cube:'):yy=np.full((8,8),(layer+1)*8.)
                elif k.startswith('roof:'):
                    fields=k.split('|');h0,h1=map(int,fields[2].split(','));w,e=map(int,fields[3].split(','));yy=layer*8+roof_heights(h0,h1,w,e)
                else:raise AssertionError(k)
                top=np.maximum(top,yy)
            x,z=cx*16+q['dx']*8,cz*16+q['dz']*8;h[z:z+8,x:x+8]=top
    return h

def foundation_parts():
    h=native_foundation();parts=[];cliff=p.paint([9,112,10,113],[3,112,4,113],[0,112,1,113])
    # These four rows are entirely hall art, not exposed cliff. The native
    # inferred roof is replaced by the hall's level 40-voxel cliff platform,
    # matching the five-block native front ledge. WALK columns remain empty.
    h[32:96]=np.where(h[32:96]>0,40,0)
    heights=np.floor(h+.5).astype(int);used=np.zeros(h.shape,bool)
    # Preserve the actual solid profile at every claimed cliff interface.
    # Adjacent equal heights merge only along each row; no silhouette guess.
    for z,line in enumerate(heights):
        x=0
        while x<len(line):
            if used[z,x] or not line[x]:x+=1;continue
            end=x+1
            while end<len(line) and line[end]==line[x] and not used[z,end]:end+=1
            south=z+1
            while south<len(heights) and (heights[south,x:end]==line[x]).all() and not used[south,x:end].any():south+=1
            used[z:south,x:end]=True
            parts.append(box((x,end),(z,south),(0,int(line[x])),cliff))
            x=end
    return parts

def roof_band(parts,y0,y1,inset,paint):
    # The source has broad rectangular wings and a pointed centre at both
    # ends, so a normal rectangular roof would lose its defining silhouette.
    for z in range(18+inset,94-inset):
        cut=max(0,(30-z)*2,(z-80)*2)
        lo=max(inset,cut);hi=80-lo
        if hi>lo:parts.append(box((lo,hi),(z,z+1),(y0,y1),paint))

def structure():
    parts=foundation_parts();teal=material((24,70,25,71));dark=material((53,84,54,85));white=material((1,26,2,27));gray=material((0,24,1,25));peach=material((8,17,9,18))
    cliff=p.paint([9,112,10,113],[3,112,4,113],[0,112,1,113])
    parts.append(box((2,78),(18,80),(32,40),cliff))
    upper_start=len(parts)
    parts.append(box((2,78),(18,80),(32,80),teal))
    # Forward central faceted bay, with the two source glazed faces.
    for z in range(80,94):
        inset=(z-80)*1.5;lo=16+int(inset);hi=64-int(inset)
        if hi>lo:
            parts.append(box((lo,40),(z,z+1),(32,80),p.paint([lo,64,40,96],side=[lo,64,lo+1,96])))
            parts.append(box((40,hi),(z,z+1),(32,80),p.paint([40,64,hi,96],side=[hi-1,64,hi,96])))
    for lo in (2,64):
        parts.append(box((lo,lo+14),(78,87),(32,80),p.paint([lo,64,lo+14,96],side=[lo,65,lo+3,96])))
    # Solid corner supports stay inside the physical building cells; low
    # source WALK columns remain empty, including the two front projections.
    for x,z in ((4,3),(2,5),(3,5)):parts.append(box((x*16,x*16+16),(z*16,z*16+16),(0,24),carve=True))
    roof_band(parts,80,82,0,gray);roof_band(parts,82,85,1,white);roof_band(parts,85,86,4,peach)
    for x in (7,57):parts.append(box((x,x+16),(39,70),(86,87),p.paint([x,33,x+16,54],[x,33,x+16,54],[x,33,x+1,34])))
    # Central hexagonal glazed crown: vertical faceted rim and low crystal
    # roof with distinct native turquoise/light/dark faces.
    for z in range(29,80):
        cut=max(0,39-z,z-69);parts.append(box((24+cut,56-cut),(z,z+1),(86,90),dark))
    parts.append({'shape':'profile','x':[25,55],'z':[30,79],'y':[90,103],'along':'z','profile':[0,13,0],'cross':[0,13,0],'paint':p.paint([33,28,34,29],[25,33,26,34],[40,28,41,29])})
    for part in parts[upper_start:]:
        if not part.get('carve'):part['y']=[v+8 for v in part['y']]
    # The actual warp is a separate teal stone arch below the cliff ledge.
    # Keep its low doorway clear and recess the dark opening in the solid
    # cell immediately north of the warp; all original native kinds persist.
    arch=material((34,120,35,121));parts.append(box((32,48),(122,144),(0,25),arch))
    for y,left in ((25,32),(26,33),(27,34),(28,35),(29,36),(30,38)):parts.append(box((left,80-left),(122,144),(y,y+1),arch))
    entry_recess(parts,(34,46),144,126,23,(34,128,46,144))
    parts.append(box((34,46),(143,144),(24,29),p.paint([34,117,46,125],side=[34,117,35,118])))
    # Outer source-cliff cells are left to their original native rendering.
    return {'height':112,'parts':parts}

def generate(table=None,art=None):
    art=art or EmeraldArt(BUNDLE);layout=layout_for(art)
    assert hashlib.sha256(art.drawing(layout,'BAKED').convert('RGB').tobytes()).hexdigest()==SOURCE_SHA256
    return {IDENT:{'id':IDENT,'atlas':'g3q','tiles':quarters(layout),'terrain':TERRAIN,'preserveTerrain':True,**structure()}}

def evidence(art,e):
    places=building_catalog({'game':'emerald','cells':{},'buildings':[e]},art)[0]['occurrences'];assert places==[{'map':'EM_ROUTE111','x':29,'y':105,'palette':'BAKED'}]
    m=art.maps['EM_ROUTE111'];raw=base64.b64decode(m['cells']);cells=[]
    for dz,row in enumerate(layout_for(art)):
        for dx,k in enumerate(row):
            x,y=29+dx,105+dz;i=(y*m['w']+x)*4;cells.append({'key':k,'x':x,'y':y,'local':[dx,dz],'metatile':int.from_bytes(raw[i:i+2],'little'),'collision':raw[i+2],'elevation':raw[i+3],'terrain_excluded':[dx,dz] in TERRAIN})
    warps=[q for q in m['warps'] if 29<=q['x']<34 and 105<=q['y']<114];assert len(warps)==1 and warps[0]['x']==31 and warps[0]['y']==113
    return [{**places[0],'width':5,'depth':9,'cells':cells,'warps':warps}]

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();art=EmeraldArt(BUNDLE);entries=generate(art=art);e=entries[IDENT];OUT.mkdir(parents=True,exist_ok=True);source=p.drawing(e,BUNDLE);source.save(OUT/'source.png');source.resize((480,864),Image.Resampling.NEAREST).save(OUT/'source-enlarged.png')
    crop=art.map_image('EM_ROUTE111').crop((28*16,104*16,35*16,115*16));ImageDraw.Draw(crop).rectangle((16,16,95,159),outline='red');crop.resize((448,704),Image.Resampling.NEAREST).save(OUT/'context.png')
    if args.previews:
        o,c=parts_model.build(e,np.asarray(source));parts_model.picture(o,c,OUT/'view.png',3);parts_model.front_view(o,c,OUT/'front.png',4);parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(c,(0,2,1,3))[:,:,::-1],OUT/'side.png',4)
    row={'key':IDENT,'kind':'building','label':'Trainer Hill upper hall and lower cliff entrance','family':'trainer-hill-turquoise-hall','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(e),'source_pixel_sha256':SOURCE_SHA256,'source_pixel_mode':'RGB','occurrences':evidence(art,e),'terrain_exclusions':TERRAIN,'reason':'Explicit upper teal hall, projecting faceted window bay, broad pointed white roof rim and central glazed crystal crown, with a separate lower teal warp arch. Exposed mixed cliff boundaries use the original native CUBE/ROOF triangle profile. Native roof inference under pure facade art is replaced by the hall on a level40-voxel platform matching the five-block front cliff ledge. All18 surrounding cliff cells remain terrain exclusions; the three source WALK projections stay empty below24 voxels.','evidence':[{'source':(OUT/'source.png').relative_to(ROOT).as_posix(),'context':(OUT/'context.png').relative_to(ROOT).as_posix(),'native_profile_proof':PROOF.relative_to(ROOT).as_posix(),'native_profile_sha256':PROOF_SHA256}],'visual_status':'source inspected; candidate Python/runtime inspection pending','user_approved':False}
    for name,value in [('updates.json',{'cells':{},'buildings':entries}),('added-ledger.json',[row]),('spec.json',e)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'candidate':IDENT,'parts':len(e['parts']),'terrain_exclusions':len(TERRAIN)}))

if __name__=='__main__':main()
