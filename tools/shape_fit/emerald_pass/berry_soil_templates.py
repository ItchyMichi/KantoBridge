"""Exact source berry beds: shallow soil art over preserved native gameplay carriers."""
from __future__ import annotations
import argparse, copy, hashlib, json
from collections import defaultdict
import numpy as np
from PIL import Image
from tools import model_gallery as gallery
from tools.shape_fit.emerald import building_trials as parts, parts_model
from tools.shape_fit.emerald_pass import review
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters

OUT = review.ROOT / 'local/model-gallery/emerald-pass-study/soil-native-appearance'
PROOF = OUT / 'surface-runtime-proof.json'
BODY = 'g3q/11204-11205-11205-11204'
MIDDLE = 'g3q/11463-11464-11465-11466'
SOUTH = 'g3q/11205-11204-11220-11221'
WEST = 'g3q/11460-11198-11462-11205'
EAST = 'g3q/11197-11467-11204-11207'
# Explicit soil outlines, independent of sprite opacity or a color-derived mask.
# Every part remains in its own exact berry object's source cell.
RECTS = {
    BODY: ((0,16,0,16),),
    MIDDLE: ((0,16,3,16),(3,5,2,3),(10,12,2,3)),
    SOUTH: ((0,16,0,13),(0,4,13,14),(6,10,13,14),(12,16,13,14)),
    WEST: ((9,16,2,4),(6,16,4,6),(4,16,6,10),(3,16,10,14),(2,16,14,16)),
    EAST: ((0,4,2,4),(0,8,4,6),(0,11,6,10),(0,13,10,14),(0,14,14,16)),
}
TOPS = {BODY:(0,0,16,16), MIDDLE:(0,4,16,16), SOUTH:(0,0,16,12),
        WEST:(8,6,16,16), EAST:(0,6,8,16)}
BERRY = {'sprite':'OBJ_EVENT_GFX_BERRY_TREE','stays':True,'hidden':False,'kind':'person'}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',',':')).encode()).hexdigest()


def berry_positions(row):
    result = {}
    for obj in row['scene_objects']:
        if obj['sprite'] != BERRY['sprite']:
            continue
        if any(obj.get(k) != v for k,v in BERRY.items()):
            raise ValueError('Unreviewed berry runtime identity')
        x,z = obj['x']-row['x'], obj['y']-row['y']
        if (x,z) in result or row['layout'][z][x] not in RECTS:
            raise ValueError('Duplicate berry or unreviewed soil source key')
        result[x,z] = obj
    return result


def generate(art=None):
    art = art or gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald')
    proof = review.read(PROOF)
    grouped = defaultdict(list)
    for row in proof['occurrences']:
        grouped[json.dumps(row['layout'],separators=(',',':'))].append(row)
    models, evidence = {}, []
    for stamp, rows in grouped.items():
        row = rows[0]; layout = row['layout']; source = art.drawing(layout,'BAKED')
        owned = set(berry_positions(row)); height = 0
        for witness in rows:
            if set(berry_positions(witness)) != owned:
                raise ValueError('Same source grid has different berry object placement')
            for q in witness['source_surface_columns']:
                if (q['x']//2,q['z']//2) not in owned:
                    continue
                if q['kind'] != 'SOLID' or q['source_tags'] != 0 or q.get('ramp') or q.get('deck'):
                    raise ValueError('Unreviewed berry routing')
                carriers = [b for b in q['after'] if b['y'] >= q['feet']]
                if not carriers or any(b.get('shape_key','').split(':')[0] not in ('cube','roof','cell') for b in carriers):
                    raise ValueError('Unreviewed original carrier type')
                height = max(height, 8*len(carriers))
            if any((s['cell']['x']-witness['x'],s['cell']['y']-witness['y']) in owned for s in witness['actual_story_entries']):
                raise ValueError('Actual story overlap is not part of berry-soil opt-in')
        model_parts = []
        for x,z in sorted(owned):
            key = layout[z][x]; native = np.asarray(art.cell(key,'BAKED').convert('RGB'))
            choices = np.argwhere((native == (156,115,115)).all(axis=2))
            if not len(choices):
                raise ValueError('No native soil material')
            sy,sx = map(int,choices[0]); sample = [x*16+sx,z*16+sy,x*16+sx+1,z*16+sy+1]
            a,b,c,d = TOPS[key]; top = [x*16+a,z*16+b,x*16+c,z*16+d]
            paint = parts.paint(sample, top=top, side=sample)
            for x0,x1,z0,z1 in RECTS[key]:
                model_parts.append(parts.box([x*16+x0,x*16+x1],[z*16+z0,z*16+z1],[0,4],paint))
        terrain = [[x,z] for z,line in enumerate(layout) for x,_ in enumerate(line) if (x,z) not in owned]
        ident = 'emerald_object_berry_soil_'+hashlib.sha256(stamp.encode()).hexdigest()[:12]
        spec = {'id':ident,'atlas':'g3q','tiles':quarters(layout),'height':height,'terrain':terrain,
                'preserveTerrain':True,'preserveCarriers':True,'berrySoil':True,'parts':model_parts}
        places = gallery.building_catalog({'game':'emerald','cells':{},'buildings':[spec]},art)[0]['occurrences']
        expected = {(r['map'],r['x'],r['y']) for r in rows}
        if {(r['map'],r['x'],r['y']) for r in places} != expected:
            raise ValueError('Source pattern has an uninspected occurrence')
        models[ident] = spec
        evidence.append({'id':ident,'layout':layout,'source_pixel_mode':'RGB',
            'source_pixel_sha256':hashlib.sha256(source.convert('RGB').tobytes()).hexdigest(),
            'proposed_entry_sha256':digest(spec),'native_envelope_height':height,'visual_soil_height':4,
            'occupied_cells':[list(p) for p in sorted(owned)],
            'occurrences':[{'map':r['map'],'x':r['x'],'y':r['y'],'native_context_id':r['id'],
                'map_cells_sha256':r['map_cells_sha256'],'source_collisions':r['source_collisions'],
                'scene_objects':r['scene_objects'],'actual_story_entries':r['actual_story_entries'],
                'berry_objects':list(berry_positions(r).values())} for r in rows],
            'reason':'The whole source is a shallow brown soil bed in grass. Actual native CUBE/ROOF rendering grows some berry-body cells into tall terrain towers, while the native floor already has the source soil. This exact-source visual opt-in replaces only the body mesh with a four-voxel soil surface inside the original native height envelope. All collision carriers, floors, berry SceneObjects and actual story state must remain unchanged. The template excludes perimeter grass, neighboring rock/cliff and unrelated story/object positions; ordinary object refusal must remain in force.',
            'runtime_status':'candidate; berrySoil-specific preservation and negative fixtures required',
            'visual_status':'source inspected; Python candidate inspection and actual Minecraft comparison pending'})
    return models,evidence


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');ap.add_argument('--bind-native',action='store_true');args=ap.parse_args()
    if args.bind_native:
        bind_native();return
    art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');models,evidence=generate(art)
    gallery.write_json(OUT/'additions.json',{'cells':{},'buildings':models})
    gallery.write_json(OUT/'source-evidence.json',{'source_runtime_proof_sha256':hashlib.sha256(PROOF.read_bytes()).hexdigest(),
        'original_baseline_sha256':review.read(PROOF)['original_baseline_sha256'],
        'allowed_berry_source_keys':sorted(RECTS),'explicit_soil_rectangles':RECTS,'groups':evidence})
    ledger=[];pins={}
    for row in evidence:
        spec=models[row['id']]
        ledger.append({'key':row['id'],'kind':'building','decision':'added','label':'Shallow berry soil bed '+row['id'].rsplit('_',1)[-1],
            'family':'Source-pinned berry soil appearance','baseline_absent':True,'baseline_entry_sha256':None,
            'proposed_entry_sha256':row['proposed_entry_sha256'],'source_pixel_sha256':row['source_pixel_sha256'],
            'source_pixel_mode':'RGB','occurrences':row['occurrences'],'reason':row['reason'],
            'evidence':[(OUT/'source-evidence.json').relative_to(review.ROOT).as_posix(),PROOF.relative_to(review.ROOT).as_posix()],
            'visual_status':'source and candidate Python front/side/angled inspected; actual Minecraft comparison pending','user_approved':False})
        pins[row['id']]={'metadata':{k:v for k,v in spec.items() if k not in ('height','parts')},
            'source_pixel_sha256':row['source_pixel_sha256'],'source_pixel_mode':'RGB',
            'occurrences':[[o['map'],o['x'],o['y']] for o in row['occurrences']]}
    gallery.write_json(OUT/'added-ledger.json',ledger);gallery.write_json(OUT/'source-amendment-proposal.json',{'added_buildings':pins})
    if args.previews:
        for row in evidence:
            dest=OUT/row['id'];dest.mkdir(exist_ok=True);source=art.drawing(row['layout'],'BAKED')
            source.resize((source.width*4,source.height*4),Image.Resampling.NEAREST).save(dest/'source.png')
            occ,rgb=parts_model.build(models[row['id']],np.asarray(source.convert('RGB')))
            parts_model.picture(occ,rgb,dest/'view.png',4);parts_model.front_view(occ,rgb,dest/'front.png',4)
            parts_model.front_view(np.transpose(occ,(0,2,1))[:,:,::-1],np.transpose(rgb,(0,2,1,3))[:,:,::-1],dest/'side.png',4)
    print(len(models),'exact berry-body visual templates;',sum(len(r['occurrences']) for r in evidence),'occurrences; no live changes')


def bind_native():
    from tools.shape_fit.emerald_pass import terrain_native_contexts as contexts,native_baselines
    from tools.shape_fit.emerald_pass.qa10_wholes import canonical_hash
    path=OUT/'runtime-proof.json';proof=review.read(path);patch=review.read(OUT/'additions.json');snapshot=OUT/'fitted-proof-current.json'
    if proof['candidate_updates_sha256']!=hashlib.sha256((OUT/'additions.json').read_bytes()).hexdigest() or proof['current_table_sha256']!=hashlib.sha256(snapshot.read_bytes()).hexdigest():
        raise ValueError('Berry candidate differs from actual WorldPlan proof')
    table=review.read(snapshot);art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');entities=[]
    for original in proof['occurrences']:
        row=copy.deepcopy(original);row['id']='emerald_berry_soil_native_'+row['map'].lower()+'_'+str(row['x'])+'_'+str(row['y'])
        common={'occurrence':{k:row[k] for k in ('map','x','y')},'source_layout':row['layout']}
        for field,target in [('original_baseline_fragments','original_fitted_lifts'),('corrected_fragments','current_fitted_lifts')]:
            lifts=contexts.lifts(row,field)
            if lifts:row[target]=lifts
        row['current_source_cell_hashes']={k:canonical_hash(table['cells'].get(k)) for k in sorted({k for line in row['layout'] for k in line})}
        row['current_source_template_hashes']=gallery.native_source_template_hashes(table,{'descriptor':common,'record':row},art)
        local=OUT/'native-proofs'/(row['id']+'.json')
        gallery.write_json(local,{'original_baseline_sha256':proof['original_baseline_sha256'],'current_table_sha256':proof['current_table_sha256'],
            'parent_runtime_evidence':{'path':path.relative_to(review.ROOT).as_posix(),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()},'occurrences':[row]})
        common.update(evidence=local.relative_to(review.ROOT).as_posix(),evidence_sha256=hashlib.sha256(local.read_bytes()).hexdigest())
        entities.append({'id':row['id'],'kind':'actual native berry bed and terrain context','layout':row['layout'],
            'baseline_native_fragments':dict(common,baseline_sha256=proof['original_baseline_sha256']),
            'current_native_fragments':dict(common,current_table_sha256=proof['current_table_sha256'],
                source_cell_hashes=row['current_source_cell_hashes'],source_template_hashes=row['current_source_template_hashes']),
            'occurrences':[dict(common['occurrence'],palette='BAKED')],
            'note':'Complete source bed context: unchanged source floors/perimeter/rock/cliff and berry-body art on exactly preserved native collision carriers. Actual WorldPlan confirms unchanged berry objects and story table; those live objects are not part of this geometry gallery. All source feet levels are retained with one scene datum. Minecraft visual QA pending.'})
    gallery.write_json(OUT/'native-entities.json',entities)
    masked=copy.deepcopy(proof);masked['parent_runtime_evidence']={'path':path.relative_to(review.ROOT).as_posix(),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
    for row in masked['occurrences']:
        exclusions={tuple(p) for p in patch['buildings'][row['id']]['terrain']};row.pop('source_floor_columns',None)
        row['source_mask']=[[(x,z) not in exclusions for x in range(len(line))] for z,line in enumerate(row['layout'])]
        for field,target in [('original_baseline_fragments','original_fitted_lifts'),('corrected_fragments','current_fitted_lifts')]:
            row[field]=[f for f in row[field] if (f['x']//2,f['z']//2) not in exclusions]
            lifts=contexts.lifts(row,field)
            if lifts:row[target]=lifts
    gallery.write_json(OUT/'building-baseline-proof.json',masked)
    baselines=native_baselines.building_entities(OUT/'building-baseline-proof.json');gallery.write_json(OUT/'baseline-entities.json',baselines)
    definitions=entities+baselines;ids={e['id'] for e in definitions};baseline=review.read(review.BASELINE)
    now={e['id']:e for subset in ('all','buildings') for e in gallery.catalog(table,definitions,art,subset=subset) if e['id'] in ids}
    old={e['id']:e for subset in ('all','buildings') for e in gallery.catalog(baseline,gallery.baseline_entities(definitions,proof['original_baseline_sha256']),art,subset=subset) if e['id'] in ids}
    if set(now)!=ids or set(old)!=ids:raise ValueError('Incomplete berry building/native comparison coverage')
    gallery.write_json(OUT/'catalog-check.json',{'entries':[{'id':i,'before_bounds':old[i]['bounds'],'current_bounds':now[i]['bounds'],
        'before_fragments':len(old[i]['fragments']),'current_fragments':len(now[i]['fragments'])} for i in sorted(ids)],'visual_approval':False})
    print('Validated',len(entities),'native bed contexts and',len(baselines),'exact original building companions')


if __name__=='__main__':main()
