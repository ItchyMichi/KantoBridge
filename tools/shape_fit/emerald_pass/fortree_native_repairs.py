"""Source-matched conifer woods and low hedge meshes over unchanged native carriers."""
from __future__ import annotations
import argparse,base64,copy,hashlib,json
import numpy as np
from PIL import Image
from tools import model_gallery as gallery
from tools.shape_fit.emerald import building_trials as p,parts_model
from tools.shape_fit.emerald_pass import review
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters

OUT=review.ROOT/'local/model-gallery/emerald-pass-study/fortree-native-repairs'
BASE_PROOF=OUT/'full-forest-routing-proof.json'

def digest(value):return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest()

def texel(source,target,green=False):
    rgb=np.asarray(source.convert('RGB')).astype(int);distance=((rgb-np.asarray(target))**2).sum(axis=2).astype(float)
    if green:
        allowed=(rgb[:,:,1]>rgb[:,:,0]+12)&(rgb[:,:,2]*5<=rgb[:,:,1]*3)
        # Keep actual small native leaf clusters, excluding every grass/shadow/cliff texel.
        # A varied swatch prevents each branch tier becoming one untextured color band.
        for size in (4,3,2):
            patches=[]
            for y in range(rgb.shape[0]-size+1):
                for x in range(rgb.shape[1]-size+1):
                    pixels=rgb[y:y+size,x:x+size]
                    if allowed[y:y+size,x:x+size].all() and len(np.unique(pixels.reshape(-1,3),axis=0))>=3:
                        patches.append((float(((pixels.mean(axis=(0,1))-target)**2).sum()),x,y))
            if patches:
                _,x,y=min(patches);area=[x,y,x+size,y+size]
                return p.paint(area,top=area,side=area)
        distance[~allowed]=np.inf
    if not np.isfinite(distance).any():raise ValueError('source has no native foliage material')
    y,x=np.unravel_index(distance.argmin(),distance.shape);return p.paint([int(x),int(y),int(x)+1,int(y)+1],top=[int(x),int(y),int(x)+1,int(y)+1],side=[int(x),int(y),int(x)+1,int(y)+1])

def conifer(x,z,height,colors):
    """Grounded bole, flared roots and overlapping conical branch skirts; no source floor backing."""
    leaf,dark,light,bark=colors;cx=x+8;cz=z+8;stem=max(3,round(height*.23))
    result=[p.round_part('cylinder',[cx,cz],1.7,[0,stem+3],bark),p.round_part('cylinder',[cx,cz],2.5,[0,2],bark)]
    for low,high,radius,paint in [(stem-1,height*.57,7.6,dark),(height*.32,height*.79,6.7,leaf),(height*.55,height,5.2,light)]:
        result.append(p.round_part('cone',[cx,cz],[radius,radius*.94],[round(low),round(high)],paint))
    # Individual branch lobes interrupt straight cone seams, all within this exact source cell.
    for dx,dz in [(-3,-1),(3,1),(-1,3),(1,-3)]:
        result.append(p.round_part('dome',[cx+dx,cz+dz],[3.5,3.3],[stem,round(height*.52)],leaf if dx<0 else dark))
    return result

def hedge(width,z,colors):
    leaf,dark,light,_=colors
    parts=[p.box([0,width],[z+2,z+14],[0,6],dark),p.box([1,width-1],[z+3,z+13],[6,9],leaf)]
    for x in range(8,width,16):parts.append(p.round_part('dome',[x,z+8],[8,7.5],[4,12],light))
    return parts

def generate(art=None):
    art=art or gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');proof=review.read(BASE_PROOF);result={};evidence=[]
    for row in proof['occurrences']:
        name=row['id'];layout=row['layout'];source=art.drawing(layout,'BAKED');width=len(layout[0])*16;depth=len(layout)*16
        colors=[texel(source,c,green=True) for c in [(66,156,49),(58,82,0),(99,214,74)]]+[texel(source,(107,90,58))]
        if name in ('west','east','center'):
            terrain=[[x,0] for x in range(len(layout[0]))]
            if name=='center':terrain.append([0,6])
            positions=[(x,z) for z in range(1,len(layout)) for x in range(len(layout[0])) if [x,z] not in terrain]
            parts=[]
            for x,z in positions:
                h=min(c['blocks'] for c in row['current_carriers'] if c['x']//2==x and c['z']//2==z)*8
                parts+=conifer(x*16,z*16,min(h,36),colors)
        elif name=='hedge':
            terrain=[];parts=[]
            # Native alias stacking moves the northern source row onto the four bases.
            for x in range(4):parts+=conifer(x*16,16,30,colors)
            parts+=hedge(64,32,colors)
        else:
            owned={(0,1),(0,2),(0,3),(0,4),(1,4)}
            terrain=[[x,z] for z in range(5) for x in range(3) if (x,z) not in owned]
            parts=conifer(0,16,36,colors)+conifer(0,48,30,colors)+hedge(32,64,colors)
        height=max(c['blocks']*8 for c in row['current_carriers'] if [c['x']//2,c['z']//2] not in terrain)
        identifier='emerald_object_fortree_native_forest_'+name
        spec={'id':identifier,'atlas':'g3q','tiles':quarters(layout),'height':height,'terrain':terrain,'preserveTerrain':True,'preserveCarriers':True,'parts':parts}
        result[identifier]=spec
        places=gallery.building_catalog({'game':'emerald','cells':{},'buildings':[spec]},art)[0]['occurrences']
        cells=[];maps=[]
        for place in places:
            raw=base64.b64decode(art.maps[place['map']]['cells']);mapwidth=art.maps[place['map']]['w'];witness=[]
            for z,line in enumerate(layout):
                for x,key in enumerate(line):
                    i=((place['y']+z)*mapwidth+place['x']+x)*4
                    witness.append({'local':[x,z],'key':key,'collision':raw[i+2],'elevation':raw[i+3],'terrain_excluded':[x,z] in terrain})
            maps.append({**place,'cells':witness})
        evidence.append({'id':identifier,'layout':layout,'source_pixel_sha256':hashlib.sha256(source.convert('RGB').tobytes()).hexdigest(),'proposed_entry_sha256':digest(spec),'occurrences':maps,'native_reference_id':name,'native_reference_proof_sha256':hashlib.sha256(BASE_PROOF.read_bytes()).hexdigest(),'reason':'Native Tier-A CUBE/ROOF columns bypassed the reviewed fitted conifer/hedge shapes. This complete source-matched forest uses grounded boles and layered branch skirts, separate low hedges, and source-local foliage paint while preserving every native collision/floor carrier. Pure cliff and real WALK terrain are excluded.'})
    return result,evidence

def bind_native():
    from tools.shape_fit.emerald_pass import terrain_native_contexts as contexts,native_baselines
    proof=review.read(OUT/'runtime-proof.json');patch=review.read(OUT/'additions.json')
    if proof['candidate_updates_sha256']!=hashlib.sha256((OUT/'additions.json').read_bytes()).hexdigest():raise ValueError('final Fortree model differs from actual runtime proof')
    names={key:key.replace('emerald_object_fortree_native_forest_','emerald_fortree_native_repair_context_') for key in patch['buildings']}
    entities=contexts.generate(OUT,names);gallery.write_json(OUT/'native-entities.json',entities)
    # The building-only companion excludes pure cliff/floor. Full contexts above retain them.
    masked=copy.deepcopy(proof);masked['parent_runtime_evidence']={'path':str((OUT/'runtime-proof.json').relative_to(review.ROOT)),'sha256':hashlib.sha256((OUT/'runtime-proof.json').read_bytes()).hexdigest()}
    for row in masked['occurrences']:
        exclusions={tuple(p) for p in patch['buildings'][row['id']]['terrain']};layout=row['layout']
        row.pop('source_floor_columns',None)
        row['source_mask']=[[(x,z) not in exclusions for x in range(len(line))] for z,line in enumerate(layout)]
        for field,target in [('original_baseline_fragments','original_fitted_lifts'),('corrected_fragments','current_fitted_lifts')]:
            row[field]=[f for f in row[field] if (f['x']//2,f['z']//2) not in exclusions]
            found=contexts.lifts(row,field)
            if found:row[target]=found
    gallery.write_json(OUT/'building-baseline-proof.json',masked)
    baselines=native_baselines.building_entities(OUT/'building-baseline-proof.json');gallery.write_json(OUT/'baseline-entities.json',baselines)
    art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');current=review.read(OUT/'fitted-proof-current.json');original=review.read(review.BASELINE);all_entities=entities+baselines;ids=set(names)|set(names.values())
    now={e['id']:e for subset in ('all','buildings') for e in gallery.catalog(current,all_entities,art,subset=subset) if e['id'] in ids};old={e['id']:e for subset in ('all','buildings') for e in gallery.catalog(original,gallery.baseline_entities(all_entities,proof['original_baseline_sha256']),art,subset=subset) if e['id'] in ids}
    if set(now)!=ids or set(old)!=ids:raise ValueError('incomplete forest current/original native catalog')
    gallery.write_json(OUT/'catalog-check.json',{'entries':[{'id':i,'before_bounds':old[i]['bounds'],'current_bounds':now[i]['bounds'],'before_fragments':len(old[i]['fragments']),'current_fragments':len(now[i]['fragments'])} for i in sorted(ids)],'visual_approval':False})
    print('Validated',len(ids),'building/context comparisons with source-bound original companions')

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');parser.add_argument('--bind-native',action='store_true');args=parser.parse_args()
    if args.bind_native:bind_native();return
    art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');models,evidence=generate(art)
    gallery.write_json(OUT/'additions.json',{'cells':{},'buildings':models});gallery.write_json(OUT/'source-evidence.json',{'groups':evidence,'native_proof':str(BASE_PROOF.relative_to(review.ROOT))})
    ledger=[];pins={}
    for row in evidence:
        identifier=row['id'];spec=models[identifier]
        ledger.append({'key':identifier,'kind':'building','label':identifier.replace('emerald_object_fortree_native_forest_','Fortree native forest: '),'family':'fortree-source-matched-forest-and-hedge','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':row['proposed_entry_sha256'],'source_pixel_sha256':row['source_pixel_sha256'],'source_pixel_mode':'RGB','occurrences':row['occurrences'],'reason':row['reason'],'evidence':[{'source':str((OUT/identifier/'source.png').relative_to(review.ROOT)),'source_context_proof':str(BASE_PROOF.relative_to(review.ROOT)),'native_reference_proof_sha256':row['native_reference_proof_sha256']}],'visual_status':'source and candidate Python front/side/angled inspected; Minecraft review pending','user_approved':False})
        pins[identifier]={'metadata':{k:v for k,v in spec.items() if k not in ('height','parts')},'source_pixel_sha256':row['source_pixel_sha256'],'source_pixel_mode':'RGB','occurrences':[[v['map'],v['x'],v['y']] for v in row['occurrences']]}
    gallery.write_json(OUT/'added-ledger.json',ledger);gallery.write_json(OUT/'source-amendment-proposal.json',{'added_buildings':pins})
    if args.previews:
        for row in evidence:
            spec=models[row['id']];dest=OUT/row['id'];dest.mkdir(exist_ok=True);source=art.drawing(row['layout'],'BAKED');source.resize((source.width*4,source.height*4),Image.Resampling.NEAREST).save(dest/'source.png')
            o,c=parts_model.build(spec,np.asarray(source.convert('RGB')));parts_model.picture(o,c,dest/'view.png',4);parts_model.front_view(o,c,dest/'front.png',4);parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(c,(0,2,1,3))[:,:,::-1],dest/'side.png',4)
    print('Authored',len(models),'exact forest interfaces;',sum(len(r['occurrences']) for r in evidence),'source occurrences; runtime/visual review pending')

if __name__=='__main__':main()
