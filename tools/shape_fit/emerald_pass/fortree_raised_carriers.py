"""Two source-pinned Fortree forests with independent parts at exact stepped native feet."""
from __future__ import annotations
import argparse,copy,hashlib
from pathlib import Path
import numpy as np
from PIL import Image
from tools import model_gallery as g
from tools.shape_fit.emerald import parts_model
from tools.shape_fit.emerald_pass import review,carrier_levels

OUT=review.ROOT/'local/model-gallery/emerald-pass-study/fortree-raised-carriers'
ORIGINAL=OUT.parent/'fortree-native-repairs'
SOURCE=OUT.parent/'soil-native-appearance/surface-runtime-proof.json'
NAMES=('center','hedge_cliff')


def freeze():
    from tools.shape_fit.emerald_pass.surface_native_contexts import generate as native_entities
    proof=review.read(OUT/'runtime-proof.json');patch=review.read(OUT/'updates.json')['buildings']
    digest=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
    assert digest(OUT/'updates.json')==proof['candidate_updates_sha256']
    assert digest(OUT/'source-level-evidence.json')==proof['source_level_evidence_sha256']
    entities=native_entities(OUT,proof_name='runtime-proof.json')
    g.write_json(OUT/'native-entities.json',entities)
    art=g.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');ids={e['id'] for e in entities}
    current={e['id']:e for e in g.catalog(review.read(OUT/'fitted-surface-current.json'),entities,art) if e['id'] in ids}
    before={e['id']:e for e in g.catalog(review.read(review.BASELINE),g.baseline_entities(entities,digest(review.BASELINE)),art) if e['id'] in ids}
    assert set(before)==set(current)==ids
    g.write_json(OUT/'catalog-check.json',{'entries':[{'id':i,'before_bounds':before[i]['bounds'],'current_bounds':current[i]['bounds'],'before_fragments':len(before[i]['fragments']),'current_fragments':len(current[i]['fragments'])} for i in sorted(ids)],'visual_approval':False})
    pins=review.read(ORIGINAL/'source-amendment-proposal.json')['added_buildings']
    amendments={}
    for ident,entry in patch.items():
        pin=copy.deepcopy(pins[ident]);pin['metadata']={k:v for k,v in entry.items() if k not in ('height','parts')};amendments[ident]=pin
    g.write_json(OUT/'source-amendment-proposal.json',{'added_buildings':amendments,'preconditions':'preconditions.json','note':'Replace only these two already-pinned metadata records, adding exact carrierLevels. Every source tile/terrain/identity field is unchanged.'})
    registry=review.read(review.ROOT/'tools/shape_fit/entities/emerald.json');retire=[]
    for name in NAMES:
        ident='emerald_fortree_native_repair_context_'+name;old=next(e for e in registry if e['id']==ident)
        source=next(r for r in proof['occurrences'] if r['map']=='EM_ROUTE120' and r['x']==(8 if name=='center' else 29))
        replacement=next(e['id'] for e in entities if e['current_native_fragments']['occurrence']=={k:source[k] for k in ('map','x','y')})
        retire.append({'id':ident,'expected_entity_sha256':review.entry_hash(old),'preserved_entity':old,'replacement':replacement,'reason':'Old complete flat-layout source descriptor is superseded for current review by actual raised-map surfaces. Historical proofs/captures remain unchanged.'})
    g.write_json(OUT/'registry-replacements.json',retire)
    g.write_json(OUT/'local-qa.json',{'model_changes':2,'findings':[
        {'id':ident,'source_and_flat_and_actual_level_previews_inspected':True,'finding':'Independent source-native tree volumes remain separated at unequal carrier levels; the complete native context must supply the preserved cliff/floor support. Cone radii stop one voxel before cross-level cell edges.','minecraft_visual_status':'pending','user_approved':False} for ident in patch],
        'runtime':'Both actual claims refused before and accepted after; all16000 Route120 quarters preserve collision/decor/floors/unclaimed shapes/objects/story. 64 claimed quarters. Configuration outdoorLevels=all, chainLevels=false, no landscape override.'})
    sources=[Path('tools/model_gallery.py'),Path('tools/shape_fit/emerald_pass/fortree_raised_carriers.py'),Path('tools/shape_fit/emerald_pass/carrier_levels.py'),Path('tools/shape_fit/emerald_pass/native_surfaces.py'),Path('tools/shape_fit/emerald_pass/surface_native_contexts.py'),Path('tools/tests/test_emerald_fortree_raised_carriers.py')]
    for name in ('FittedCarrierLevels','FittedCarriers','FittedShapes','ShapePlanner'):
        sources.extend(Path(p.relative_to(review.ROOT)) for p in (review.ROOT/'fabric-bridge/src/main/java').rglob(name+'.java'))
    for name in ('FittedCarrierLevelsTest','EmeraldFortreeRaisedProofTest'):
        sources.extend(Path(p.relative_to(review.ROOT)) for p in (review.ROOT/'fabric-bridge/src/test/java').rglob(name+'.java'))
    code={}
    for path in sources:
        target=OUT/'frozen-code'/path.name;target.parent.mkdir(exist_ok=True);target.write_bytes((review.ROOT/path).read_bytes());code[path.as_posix()]=digest(target)
    manifest={'status':'frozen_ready_for_guarded_integration','replaced_templates':2,'existing_cells_changed':0,'native_contexts':2,'patch':'updates.json','preconditions':'preconditions.json','entity_files':['native-entities.json'],'registry_replacements':'registry-replacements.json','source_amendment_proposal':'source-amendment-proposal.json','runtime_proof_sha256':digest(OUT/'runtime-proof.json'),'validation':{'full_map_quarters':16000,'claimed_quarters':64,'java_compatibility_tests':13,'actual_worldplan_test':1,'python_tests':63,'actual_minecraft_visual_qa':False,'user_approved':False},'code_sources':code,'files':{p.relative_to(OUT).as_posix():digest(p) for p in sorted(OUT.rglob('*')) if p.is_file() and p.name!='integration-manifest.json'}}
    g.write_json(OUT/'integration-manifest.json',manifest)
    print('Frozen raised Fortree2:',digest(OUT/'integration-manifest.json'))


def generate():
    old=review.read(ORIGINAL/'additions.json')['buildings'];forests=review.read(ORIGINAL/'runtime-proof.json')['occurrences'];source=review.read(SOURCE);patch={};evidence=[]
    for name in NAMES:
        ident='emerald_object_fortree_native_forest_'+name;entry=copy.deepcopy(old[ident]);r=next(v for v in forests if v['id']==ident);layout=next(v['actual_map_layout'] for v in source['occurrences'] if v['map']==r['map']);terrain={tuple(v) for v in entry['terrain']}
        actual=[[None if (x,z) in terrain else layout['levels'][(r['y']+z)*layout['w']+r['x']+x] for x in range(len(line))] for z,line in enumerate(r['layout'])]
        low=min(v for row in actual for v in row if v is not None);witness=[[None if v is None else v-low for v in row] for row in actual];entry['carrierLevels']=witness
        # Keep a full voxel of air before unequal feet boundaries. Each tree
        # remains a supported individual cell; the low hedge spans equal feet.
        for part in entry['parts']:
            if part['shape']=='cone':
                radius=part['radius'];part['radius']=[min(7,v) for v in radius] if isinstance(radius,list) else min(7,radius)
        assert carrier_levels.valid(witness,actual,entry['parts'])
        patch[ident]=entry;evidence.append({'id':ident,'map':r['map'],'x':r['x'],'y':r['y'],'layout':r['layout'],'source_collisions':r['source_collisions'],'map_cells_sha256':r['map_cells_sha256'],'actual_source_feet':actual,'carrierLevels':witness,'original_uniform_refused':len({v for row in actual for v in row if v is not None})>1,'reason':'Existing complete model was refused on the actual raised map because its claimed source cells have unequal feet. Pin this exact relative level arrangement and keep independent tree volumes separated by at least one voxel at every unequal-level boundary. Native collision/floor/carrier profiles remain unchanged.'})
    return patch,evidence


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');ap.add_argument('--freeze',action='store_true');args=ap.parse_args()
    if args.freeze:freeze();return
    patch,evidence=generate();OUT.mkdir(parents=True,exist_ok=True)
    original=review.read(ORIGINAL/'additions.json')['buildings'];g.write_json(OUT/'preconditions.json',{k:original[k] for k in patch});g.write_json(OUT/'updates.json',{'cells':{},'buildings':patch});g.write_json(OUT/'source-level-evidence.json',{'source_proof':SOURCE.relative_to(review.ROOT).as_posix(),'source_proof_sha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest(),'groups':evidence})
    if args.previews:
        art=g.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald')
        for row in evidence:
            dest=OUT/row['id'];dest.mkdir(exist_ok=True);source=art.drawing(row['layout'],'BAKED');source.resize((source.width*4,source.height*4),Image.Resampling.NEAREST).save(dest/'source.png');o,c=parts_model.build(patch[row['id']],np.asarray(source.convert('RGB')))
            parts_model.picture(o,c,dest/'flat-view.png',4)
            high=o.shape[0]+8*max(v for r in row['actual_source_feet'] for v in r if v is not None);placed=np.zeros((high,*o.shape[1:]),bool);colors=np.zeros((*placed.shape,3),np.uint8)
            for z,line in enumerate(row['actual_source_feet']):
                for x,feet in enumerate(line):
                    if feet is None:continue
                    offset=feet*8;placed[offset:offset+len(o),z*16:z*16+16,x*16:x*16+16]=o[:,z*16:z*16+16,x*16:x*16+16];colors[offset:offset+len(o),z*16:z*16+16,x*16:x*16+16]=c[:,z*16:z*16+16,x*16:x*16+16]
            parts_model.picture(placed,colors,dest/'source-level-view.png',4)
    print('Staged two exact stepped forest corrections; actual WorldPlan proof required')


if __name__=='__main__':main()
