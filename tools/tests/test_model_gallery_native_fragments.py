"""Proof-bound native columns can replace one baseline exhibit without rewriting fitted history."""
import base64
import copy
import hashlib
import json
from pathlib import Path
import pytest
from tools import model_gallery as gallery
from tools.shape_fit.emerald_pass.archive import _combined_gallery


@pytest.fixture
def case(tmp_path,monkeypatch):
    monkeypatch.setattr(gallery,'ROOT',tmp_path)
    top,base='g3q/1-2-3-4','g3q/5-6-7-8'
    voxel={'class':'voxels','v':base64.b64encode(bytes([1])*512).decode()}
    table={'game':'emerald','cells':{top:dict(voxel,on=base),base:dict(voxel)}}
    table_hash=hashlib.sha256(json.dumps(table).encode()).hexdigest()
    raw=bytes([0,0,7,0,1,0,7,0]);source={'w':1,'h':2,'cells':base64.b64encode(raw).decode()}
    class Art:
        maps={'MAP':source};locations={top:[{'map':'MAP','x':0,'y':0,'palette':'BAKED'}],base:[{'map':'MAP','x':0,'y':1,'palette':'BAKED'}]}
        def map_cells(self,source):return [[top],[base]]
    fragments=[{'key':'cube:0|-1,-1,1,2,3,4' if y<2 else 'roof:0|1,2,3|8,16|0,1',
                'palette':'BAKED','x':x,'y':y,'z':z} for x in range(2) for z in range(4) for y in range(3)]
    row={'map':'MAP','x':0,'y':1,'upper_position':[0,0],'upper_key':top,'base_key':base,
         'upper_collision':7,'base_collision':7,'map_cells_sha256':hashlib.sha256(raw).hexdigest(),
         'original_baseline_fragments':fragments}
    proof={'original_baseline_sha256':table_hash,'occurrences':[row]}
    path=tmp_path/'local/proof.json';path.parent.mkdir();path.write_text(json.dumps(proof))
    descriptor={'evidence':'local/proof.json','evidence_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
                'baseline_sha256':table_hash,'occurrence':{'map':'MAP','x':0,'y':0},'source_layout':[[top],[base]]}
    entity={'id':'tree','kind':'stack (model as one)','layout':[[top],[base]],'baseline_layout':[[top],[base]],
            'baseline_placements':[{'source':[0,0],'offset':[0,0,0]},{'source':[0,1],'offset':[0,0,1]}],
            'baseline_native_fragments':descriptor}
    return dict(table=table,hash=table_hash,art=Art(),proof=proof,path=path,entity=entity,top=top,base=base)


def before(case):
    return gallery.catalog(case['table'],gallery.baseline_entities([case['entity']],case['hash']),case['art'])


def rewrite_proof(case):
    case['path'].write_text(json.dumps(case['proof']))
    case['entity']['baseline_native_fragments']['evidence_sha256']=hashlib.sha256(case['path'].read_bytes()).hexdigest()


def test_only_named_baseline_assembly_uses_exact_native_columns_and_archive_preserves_evidence(case):
    untouched=copy.deepcopy(case['table']);entity=copy.deepcopy(case['entity'])
    current=gallery.catalog(case['table'],[case['entity']],case['art']);old=before(case)
    whole=next(e for e in old if e['id']=='tree');now=next(e for e in current if e['id']=='tree' or 'tree' in e.get('aliases',[]))
    assert whole['bounds']==[2,3,4] and len(whole['fragments'])==24 and now['bounds']==[2,4,2]
    assert all(f['key'].startswith(('cube:','roof:')) for f in whole['fragments'])
    assert next(e for e in old if e['kind']=='cell' and e['source_keys']==[case['top']])['fragments']==next(e for e in current if e['kind']=='cell' and e['source_keys']==[case['top']])['fragments']
    assert case['table']==untouched and case['entity']==entity
    assert whole['native_baseline_evidence']['record']['original_baseline_fragments']==case['proof']['occurrences'][0]['original_baseline_fragments']
    # The archive index preserves the descriptor; complete gallery manifests also keep the loaded exact proof record.
    now['native_baseline_evidence']=whole['native_baseline_evidence']
    combined=_combined_gallery([], [{'entries':[now]}],set(case['table']['cells']),set())
    saved=combined['entries'][0]['assembly_layouts']['gallery-0']
    assert saved['baseline_native_fragments']==entity['baseline_native_fragments']
    assert saved['native_baseline_evidence']==whole['native_baseline_evidence']
    assert combined['entries'][0]['native_baselines']['gallery-0']['native_baseline_evidence']==whole['native_baseline_evidence']


def test_exact_existing_voxel_columns_must_match_original_geometry_hash_and_quadrants(case):
    row=case['proof']['occurrences'][0];digest=gallery.model_hash(case['table']['cells'][case['top']])[0]
    for fragment in row['original_baseline_fragments']:
        if fragment['z']<2 and fragment['y']<2:
            fragment['key']=f"cell:0|1,2,3,4|0|{fragment['x']},{fragment['y']},{fragment['z']}|v=g0rt_voxels_k{digest}"
    rewrite_proof(case);assert before(case)
    row['original_baseline_fragments'][0]['key']=row['original_baseline_fragments'][0]['key'][:-12]+'999999999999'
    rewrite_proof(case)
    with pytest.raises(ValueError,match='original baseline'):before(case)


@pytest.mark.parametrize('defect',[None,'support','missing_slice','wrong_source','stale_height'])
def test_proven_native_support_may_lift_complete_fitted_quarters_by_one(case,defect):
    row=case['proof']['occurrences'][0];digest=gallery.model_hash(case['table']['cells'][case['top']])[0]
    for fragment in row['original_baseline_fragments']:
        if fragment['x']==0 and fragment['z']==0 and fragment['y'] in (1,2):
            fragment['key']=f"cell:0|1,2,3,4|0|0,{fragment['y']-1},0|v=g0_voxels_k{digest}"
    row['original_fitted_lifts']=[{'x':0,'z':0,'base_y':1,'key':case['top']}]
    if defect=='support':row['original_baseline_fragments'][0]['key']='slab:0|1|4'
    if defect=='missing_slice':row['original_baseline_fragments'].pop(2)
    if defect=='wrong_source':row['original_fitted_lifts'][0]['key']=case['base']
    if defect=='stale_height':row['original_fitted_lifts'][0]['base_y']=3
    rewrite_proof(case)
    if defect:
        with pytest.raises(ValueError,match='fitted lift'):before(case)
    else:
        whole=next(e for e in before(case) if e['id']=='tree')
        assert next(f for f in whole['fragments'] if (f['x'],f['y'],f['z'])==(0,1,0))['key'].split('|')[3]=='0,0,0'


@pytest.mark.parametrize('mutation,message',[
    ('shape','documented'),('tile','tile id'),('roof','roof height'),('overlap','overlapping'),
    ('gap','gaps'),('source','source identity'),('collision','collision'),('map_hash','source map'),
    ('duplicate','conflicting'),('position','placement')])
def test_malformed_keys_stale_source_and_context_conflicts_are_rejected(case,mutation,message):
    row=case['proof']['occurrences'][0];fragments=row['original_baseline_fragments']
    if mutation=='shape':fragments[0]['key']='tmpl:0|1234567890abcdef|0,0,0'
    elif mutation=='tile':fragments[0]['key']='cube:0|-2,0,0,0,0,0'
    elif mutation=='roof':fragments[2]['key']='roof:0|1,2,3|17,0|0,1'
    elif mutation=='overlap':fragments.append(copy.deepcopy(fragments[0]))
    elif mutation=='gap':fragments.pop(0)
    elif mutation=='source':row['upper_key']=case['base']
    elif mutation=='collision':row['upper_collision']=0
    elif mutation=='map_hash':row['map_cells_sha256']='0'*64
    elif mutation=='duplicate':case['proof']['occurrences'].append(copy.deepcopy(row))
    elif mutation=='position':fragments[0]['x']=True
    rewrite_proof(case)
    with pytest.raises(ValueError,match=message):before(case)


def test_stale_file_wrong_baseline_and_duplicate_exhibit_context_are_rejected(case):
    with pytest.raises(ValueError,match='another baseline table'):gallery.baseline_entities([case['entity']],'0'*64)
    case['path'].write_text(case['path'].read_text()+' ')
    with pytest.raises(ValueError,match='stale'):before(case)
    rewrite_proof(case)
    entities=gallery.baseline_entities([case['entity']],case['hash']);other=copy.deepcopy(entities[0]);other['_baseline_native_fragments']['record']['extra']='conflict'
    with pytest.raises(ValueError,match='conflicting native assembly exhibit'):gallery.catalog(case['table'],entities+[other],case['art'])


def test_new_building_has_original_native_compound_companion_without_production_metadata(case):
    entity=copy.deepcopy(case['entity']);entity['building_baseline']=True
    before_table=copy.deepcopy(case['table']);before_table['buildings']=[]
    after_table=copy.deepcopy(before_table);after_table['buildings']=[{'id':'tree','atlas':'g3q','tiles':[[1,2],[3,4],[5,6],[7,8]],'height':16,'parts':[],'terrain':[]}]
    current=gallery.catalog(after_table,[entity],case['art'],'buildings')
    old=gallery.catalog(before_table,gallery.baseline_entities([entity],case['hash']),case['art'],'buildings')
    assert [(e['id'],e['kind']) for e in current]==[('tree','building')]
    assert [(e['id'],e['kind']) for e in old]==[('tree','compound')]
    assert len(old[0]['fragments'])==24 and old[0]['native_baseline_evidence']['record']
    assert 'baseline_native_fragments' not in after_table['buildings'][0]
    archived=_combined_gallery([], [{'entries':current}],set(after_table['cells']),{'tree'})
    assert archived['entries'][0]['native_baselines']['gallery-0']['baseline_native_fragments']==entity['baseline_native_fragments']
    assert all('tree'!=e['id'] for e in gallery.catalog(after_table,[entity],case['art']))
    with pytest.raises(ValueError,match='absent from the original template'):
        gallery.catalog(after_table,gallery.baseline_entities([entity],case['hash']),case['art'],'buildings')


def test_absent_original_fitted_row_is_rendered_from_proof_without_inventing_entry(case):
    del case['table']['cells'][case['top']]
    whole=next(e for e in before(case) if e['id']=='tree')
    assert len(whole['fragments'])==24 and case['top'] not in case['table']['cells']
    assert whole['model_hashes'][case['top']]=='absent-fitted-entry'


def test_one_source_cell_native_companion_is_validated_while_ordinary_duplicates_stay_hidden(case):
    row=case['proof']['occurrences'][0]
    row.clear();row.update(map='MAP',x=0,y=0,layout=[[case['top']]],source_collisions=[[7]],
        map_cells_sha256=hashlib.sha256(base64.b64decode(case['art'].maps['MAP']['cells'])).hexdigest(),
        original_baseline_fragments=[{'key':'cube:0|-1,-1,1,2,3,4','palette':'BAKED','x':x,'y':y,'z':z}
            for x in range(2) for z in range(2) for y in range(3)])
    entity=case['entity'];entity['layout']=[[case['top']]]
    entity.pop('baseline_layout');entity.pop('baseline_placements')
    entity['baseline_native_fragments']['source_layout']=[[case['top']]]
    rewrite_proof(case)
    assert not any(e['id']=='tree' for e in gallery.catalog(case['table'],[entity],case['art']))
    native=next(e for e in before(case) if e['id']=='tree')
    assert native['bounds']==[2,3,2] and len(native['fragments'])==12
    row['original_baseline_fragments'][0]['key']='not-a-documented-shape'
    rewrite_proof(case)
    with pytest.raises(ValueError,match='documented'):before(case)


def test_two_actual_slateport_one_cell_source_companions_render_all_original_quarters():
    from tools.shape_fit.emerald_pass import review
    path=review.ROOT/'local/model-gallery/emerald-pass-study/slateport-crates/single-cell-adapter-pilot-entities.json'
    if not path.exists():pytest.skip('authorized source proof absent')
    entities=review.read(path);table=review.read(review.BASELINE);art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald')
    ids={e['id'] for e in entities}
    result=[r for r in gallery.catalog(table,gallery.baseline_entities(entities,hashlib.sha256(review.BASELINE.read_bytes()).hexdigest()),art,'buildings') if r['id'] in ids]
    assert {r['id'] for r in result}=={e['id'] for e in entities}
    for row in result:
        assert row['kind']=='compound' and row['bounds']==[2,4,2]
        assert len(row['fragments'])==16


def test_exact_native_unfitted_cell_is_allowed_only_while_source_entry_is_absent(case):
    entry=case['table']['cells'].pop(case['top']);row=case['proof']['occurrences'][0]
    row['original_baseline_fragments']=[f for f in row['original_baseline_fragments'] if f['z']>=2 or f['y']<2]
    for f in row['original_baseline_fragments']:
        if f['z']<2:f['key']=f"cell:0|1,2,3,4|9|{f['x']},{f['y']},{f['z']}|v=g0irt"
    rewrite_proof(case);assert before(case)
    case['table']['cells'][case['top']]=entry
    with pytest.raises(ValueError,match='absent source entry'):before(case)


@pytest.mark.parametrize('collision',[161,163])
def test_exact_native_ledge_slab_keeps_source_collision_and_rejects_other_heights(case,collision):
    row=case['proof']['occurrences'][0]
    raw=bytearray(base64.b64decode(case['art'].maps['MAP']['cells']));raw[2]=collision
    case['art'].maps['MAP']['cells']=base64.b64encode(raw).decode();row['upper_collision']=collision
    row['map_cells_sha256']=hashlib.sha256(raw).hexdigest()
    row['original_baseline_fragments']=[f for f in row['original_baseline_fragments'] if f['z']>=2 or f['y']==0]
    for f in row['original_baseline_fragments']:
        if f['z']<2:f['key']='slab:0|25805|4'
    rewrite_proof(case);assert before(case)
    row['original_baseline_fragments'][0]['key']='slab:0|25805|5';rewrite_proof(case)
    with pytest.raises(ValueError,match='documented'):before(case)
    row['original_baseline_fragments'][0]['key']='slab:0|25805|4';raw[2]=7
    case['art'].maps['MAP']['cells']=base64.b64encode(raw).decode();row['upper_collision']=7;row['map_cells_sha256']=hashlib.sha256(raw).hexdigest();rewrite_proof(case)
    with pytest.raises(ValueError,match='raw163'):before(case)


def test_proven_native_floor_lifts_columns_together_and_keeps_source_art(case):
    row=case['proof']['occurrences'][0]
    row['source_floor_columns']=[{'x':x,'z':z,'feet':0 if z<2 else 2,'before_tile':100+x+z,'after_tile':200+x+z} for z in range(4) for x in range(2)]
    rewrite_proof(case);entry=next(e for e in before(case) if e['id']=='tree')
    assert entry['bounds']==[2,6,4] and len(entry['fragments'])==32
    floors=entry['fragments'][-8:]
    assert {(f['z'],f['y']) for f in floors}=={(0,0),(1,0),(2,2),(3,2)}
    assert all(f['key']==f"cube:0|{','.join([str(100+f['x']+f['z'])]*6)}" for f in floors)
    assert min(f['y'] for f in entry['fragments'][:-8] if f['z']<2)==1
    assert min(f['y'] for f in entry['fragments'][:-8] if f['z']>=2)==3
    row['original_baseline_fragments']=[];rewrite_proof(case)
    assert len(next(e for e in before(case) if e['id']=='tree')['fragments'])==8


@pytest.mark.parametrize('mutation,message',[('duplicate','overlapping'),('missing','coverage'),('height','floor column'),('water','ground kind'),('mask','source mask')])
def test_native_floor_refuses_unbounded_incomplete_or_wrong_source_routes(case,mutation,message):
    row=case['proof']['occurrences'][0];floors=[{'x':x,'z':z,'feet':0,'before_tile':100,'after_tile':100} for z in range(4) for x in range(2)];row['source_floor_columns']=floors
    if mutation=='duplicate':floors[-1]=copy.deepcopy(floors[0])
    elif mutation=='missing':floors.pop()
    elif mutation=='height':floors[0]['feet']=31
    elif mutation=='water':
        raw=bytearray(base64.b64decode(case['art'].maps['MAP']['cells']));raw[2]=41;case['art'].maps['MAP']['cells']=base64.b64encode(raw).decode();row['upper_collision']=41;row['map_cells_sha256']=hashlib.sha256(raw).hexdigest()
    elif mutation=='mask':
        case['entity']['reference_layout']=copy.deepcopy(case['entity']['layout']);case['entity']['layout'][0][0]=None;case['entity']['baseline_layout'][0][0]=None;case['entity']['baseline_placements']=case['entity']['baseline_placements'][1:];row['source_mask']=[[False],[True]]
    rewrite_proof(case)
    with pytest.raises(ValueError,match=message):before(case)


def test_explicit_object_source_mask_excludes_unrelated_wall_columns(case):
    case['entity']['reference_layout']=copy.deepcopy(case['entity']['layout'])
    case['entity']['layout'][0][0]=None;case['entity']['baseline_layout'][0][0]=None
    case['entity']['baseline_placements']=case['entity']['baseline_placements'][1:]
    row=case['proof']['occurrences'][0];row['source_mask']=[[False],[True]]
    row['original_baseline_fragments']=[f for f in row['original_baseline_fragments'] if f['z']>=2]
    rewrite_proof(case);whole=next(e for e in before(case) if e['id']=='tree')
    assert len(whole['fragments'])==12 and all(f['z']>=2 for f in whole['fragments'])
    fragment=copy.deepcopy(row['original_baseline_fragments'][0]);fragment['z']=0;row['original_baseline_fragments'].append(fragment)
    rewrite_proof(case)
    with pytest.raises(ValueError,match='outside source mask'):before(case)


def test_exact_real_fortree_original_baselines_validate_without_changing_current_entities():
    root=Path(__file__).resolve().parents[2];folder=root/'local/model-gallery/emerald-pass-study/tree-followups'
    if not (folder/'runtime-proof.json').exists():pytest.skip('authorized source fixture absent')
    proof=json.loads((folder/'runtime-proof.json').read_text());entities=json.loads((folder/'entities.json').read_text())
    pairs=[e for e in entities if e['id'].startswith('emerald_fortree_hedge_tree_')]
    if not all('baseline_native_fragments' in e for e in pairs):pytest.skip('source descriptors not staged yet')
    table=json.loads((root/'local/model-gallery/emerald-full-pass-baseline.json').read_text())
    art=gallery.EmeraldArt(root/'local/minecraft/kantobridge/export/emerald');saved=copy.deepcopy(pairs)
    entries=gallery.catalog(table,gallery.baseline_entities(pairs,proof['original_baseline_sha256']),art)
    selected=[e for e in entries if e['id'] in {e['id'] for e in pairs}]
    assert len(selected)==9 and all(e['placement_mode']=='source-map-baseline' for e in selected)
    assert pairs==saved


def test_actual_environment_buildings_and_record_console_have_truthful_original_companions():
    from tools.shape_fit.emerald_pass.native_baselines import building_entities
    root=Path(__file__).resolve().parents[2];folder=root/'local/model-gallery/emerald-pass-study'
    path=folder/'environment-templates/baseline-runtime-proof.json'
    if not path.exists():pytest.skip('authorized source fixture absent')
    baseline_path=root/'local/model-gallery/emerald-full-pass-baseline.json'
    baseline=json.loads(baseline_path.read_text());sha=hashlib.sha256(baseline_path.read_bytes()).hexdigest()
    art=gallery.EmeraldArt(root/'local/minecraft/kantobridge/export/emerald')
    entities=building_entities(path)
    assert len(entities)==8
    current=copy.deepcopy(baseline)
    current.setdefault('buildings',[]).extend(json.loads((folder/'environment-templates/additions.json').read_text())['buildings'].values())
    now=gallery.catalog(current,entities,art,'buildings')
    old=gallery.catalog(baseline,gallery.baseline_entities(entities,sha),art,'buildings')
    identifiers={e['id'] for e in entities}
    assert len([e for e in now if e['id'] in identifiers and e['kind']=='building'])==8
    assert len([e for e in old if e['id'] in identifiers and e['kind']=='compound'])==8
    record=json.loads((folder/'cable-palace/entities.json').read_text())
    record=[e for e in record if e['id']=='emerald_cable_palace_record_console']
    entries=gallery.catalog(baseline,gallery.baseline_entities(record,sha),art)
    exhibit=next(e for e in entries if e['id']=='emerald_cable_palace_record_console')
    assert len(exhibit['fragments'])==144 and exhibit['placement_mode']=='source-map-baseline'


def test_exact_built_story_context_is_reconstructed_without_changing_raw_art(case):
    name='EM_EVER_GRANDE_CITY_DRAKES_ROOM'
    case['art'].maps[name]=case['art'].maps.pop('MAP')
    source=case['art'].maps[name];raw=bytes([0,0,7,0])*6;source.update(w=3,h=2,cells=base64.b64encode(raw).decode())
    case['art'].bundle=case['path'].parent
    site={'map':name,'x':1,'y':0,'own':{'metatile':0,'coll':7,'elev':0},'states':[{'metatile':1,'coll':0,'elev':0}]}
    path=case['art'].bundle/'g3sites/all.json';path.parent.mkdir();path.write_text(json.dumps({'sites':[site]}))
    case['art'].map_cells=lambda s:[[case['base'] if base64.b64decode(s['cells'])[(y*3+x)*4]==1 else case['top'] for x in range(3)] for y in range(2)]
    layout=[[case['top'],case['base'],case['top']],[case['top']]*3]
    descriptor={'occurrence':{'map':name,'x':0,'y':0},'source_layout':layout}
    record={'map_cells_sha256':hashlib.sha256(raw).hexdigest(),
            'built_source':{'mode':'elite-open-arch-v1','sites_sha256':hashlib.sha256(path.read_bytes()).hexdigest()}}
    evidence={'descriptor':descriptor,'record':record}
    cooked,data,grid=gallery.native_assembly_source(evidence,case['art'])
    assert grid==layout and data[6]==0 and source['cells']==base64.b64encode(raw).decode()
    path.write_text(path.read_text()+' ')
    with pytest.raises(ValueError,match='story source changed'):gallery.native_assembly_source(evidence,case['art'])
    record['built_source']['sites_sha256']=hashlib.sha256(path.read_bytes()).hexdigest()
    record['built_source']['mode']='all-maps'
    with pytest.raises(ValueError,match='unsupported'):gallery.native_assembly_source(evidence,case['art'])


def test_actual_elite_mossdeep_and_machine_source_baselines_validate():
    root=Path(__file__).resolve().parents[2];study=root/'local/model-gallery/emerald-pass-study'
    if not (study/'elite-arches/baseline-entities.json').exists():pytest.skip('authorized runtime source proofs absent')
    path=root/'local/model-gallery/emerald-full-pass-baseline.json';baseline=json.loads(path.read_text());sha=hashlib.sha256(path.read_bytes()).hexdigest()
    art=gallery.EmeraldArt(root/'local/minecraft/kantobridge/export/emerald')
    for folder,filename,subset,expected in [('elite-arches','baseline-entities.json','buildings',2),('mossdeep-corner','entities.json','all',2),('bike-shop','entities.json','all',5),('day-care','entities.json','all',2),('building-glass-workshop','baseline-entities.json','buildings',1),('generic-tv-templates','baseline-entities.json','buildings',10),('generic-appliance-templates','baseline-entities.json','buildings',14),('seashore-house','entities.json','all',4),('mauville-game-corner','entities.json','all',4),('ship-qa-corrections','baseline-entities.json','buildings',2),('generic-architecture-templates','baseline-entities.json','buildings',14),('buildings-route104','baseline-entities.json','buildings',2),('pretty-petal','entities.json','all',16),('ladder-templates','baseline-entities.json','buildings',8),('buildings-mauville-gates','baseline-entities.json','buildings',3),('mystery-house','entities.json','all',2),('buildings-mauville-houses','baseline-entities.json','buildings',2),('building-trainer-hill','baseline-entities.json','buildings',1),('building-route112-cable-station','baseline-entities.json','buildings',1)]:
        entities=[e for e in json.loads((study/folder/filename).read_text()) if 'baseline_native_fragments' in e]
        assert len(entities)==expected
        entries=gallery.catalog(baseline,gallery.baseline_entities(entities,sha),art,subset)
        ids={e['id'] for e in entities};selected=[e for e in entries if e['id'] in ids]
        assert len(selected)==expected and all(e['placement_mode']=='source-map-baseline' for e in selected)
        if subset=='buildings':
            filename='additions.json' if folder in ('elite-arches','generic-tv-templates','generic-appliance-templates','ship-qa-corrections','generic-architecture-templates','ladder-templates') else 'updates.json'
            current=copy.deepcopy(baseline);current['buildings'].extend(json.loads((study/folder/filename).read_text())['buildings'].values())
            now=[e for e in gallery.catalog(current,entities,art,'buildings') if e['id'] in ids]
            assert len(now)==expected
            if folder=='elite-arches':assert all(e['occurrences'][0]['source_state']=='elite-open-arch-v1' for e in now)


def test_trick_house_normalization_is_limited_to_one_exact_raw_and_open_state(case):
    name='EM_ROUTE110_TRICK_HOUSE_ENTRANCE';art=case['art'];art.maps[name]=art.maps.pop('MAP');source=art.maps[name]
    raw=bytearray(bytes([0,0,7,0])*16);offset=(1*8+5)*4;raw[offset:offset+4]=(539).to_bytes(2,'little')+bytes([7,0])
    source.update(w=8,h=2,cells=base64.b64encode(raw).decode());art.bundle=case['path'].parent
    site={'map':name,'x':5,'y':1,'own':{'metatile':539,'coll':7,'elev':0},'states':[{'metatile':537,'coll':114,'elev':0}]}
    path=art.bundle/'g3sites/all.json';path.parent.mkdir();path.write_text(json.dumps({'sites':[site]}))
    art.map_cells=lambda s:[[case['base'] if int.from_bytes(base64.b64decode(s['cells'])[(y*8+x)*4:(y*8+x)*4+2],'little')==537 else case['top'] for x in range(8)] for y in range(2)]
    descriptor={'occurrence':{'map':name,'x':4,'y':0},'source_layout':[[case['top']]*3,[case['top'],case['base'],case['top']]]}
    record={'map_cells_sha256':hashlib.sha256(raw).hexdigest(),'built_source':{'mode':'generic-interior-arch-v1','sites_sha256':hashlib.sha256(path.read_bytes()).hexdigest()}}
    cooked=bytearray(raw);cooked[offset:offset+4]=(537).to_bytes(2,'little')+bytes([114,0]);record['built_cells_sha256']=hashlib.sha256(cooked).hexdigest()
    _,built,grid=gallery.native_assembly_source({'descriptor':descriptor,'record':record},art)
    assert built[offset+2]==114 and grid[1][5]==case['base'] and base64.b64decode(source['cells'])==raw
    descriptor['occurrence']['x']=3
    with pytest.raises(ValueError,match='unsupported'):gallery.native_assembly_source({'descriptor':descriptor,'record':record},art)
    descriptor['occurrence']['x']=4;site['states'][0]['metatile']=536;path.write_text(json.dumps({'sites':[site]}));record['built_source']['sites_sha256']=hashlib.sha256(path.read_bytes()).hexdigest()
    with pytest.raises(ValueError,match='unsupported Trick House'):gallery.native_assembly_source({'descriptor':descriptor,'record':record},art)


@pytest.fixture
def current_case(case):
    row=case['proof']['occurrences'][0]
    row['corrected_fragments']=copy.deepcopy(row['original_baseline_fragments'])
    row['current_source_cell_hashes']={k:hashlib.sha256(json.dumps(v,sort_keys=True,separators=(',',':')).encode()).hexdigest() for k,v in case['table']['cells'].items()}
    row['current_source_template_hashes']={}
    case['proof']['current_table_sha256']=case['hash']
    rewrite_proof(case)
    old=case['entity']['baseline_native_fragments']
    case['entity']['current_native_fragments']={k:copy.deepcopy(v) for k,v in old.items() if k!='baseline_sha256'}
    case['entity']['current_native_fragments'].update(current_table_sha256=case['hash'],source_cell_hashes=copy.deepcopy(row['current_source_cell_hashes']),source_template_hashes={})
    return case


def test_current_native_occurrence_reuses_actual_shapes_and_preserves_both_archive_sides(current_case):
    c=current_case;table=copy.deepcopy(c['table']);entity=copy.deepcopy(c['entity'])
    now=next(e for e in gallery.catalog(c['table'],[c['entity']],c['art']) if e['id']=='tree')
    old=next(e for e in before(c) if e['id']=='tree')
    assert now['placement_mode']=='source-map-current' and old['placement_mode']=='source-map-baseline'
    assert now['fragments']==old['fragments'] and now['bounds']==[2,3,4]
    assert c['table']==table and c['entity']==entity
    archived=_combined_gallery([], [{'entries':[now]}],set(c['table']['cells']),set())['entries'][0]
    assert archived['assembly_layouts']['gallery-0']['current_native_fragments']==entity['current_native_fragments']
    assert archived['native_baselines']['gallery-0']['native_current_evidence']==now['native_current_evidence']


@pytest.mark.parametrize('field,value',[('on','g3q/9-10-11-12'),('walk',True),('v',base64.b64encode(bytes(512)).decode())])
def test_current_native_refuses_changed_source_geometry_or_metadata(current_case,field,value):
    c=current_case;c['table']['cells'][c['top']][field]=value
    with pytest.raises(ValueError,match='stale native current source cell metadata'):gallery.catalog(c['table'],[c['entity']],c['art'])


def test_current_native_allows_unrelated_table_changes_but_refuses_new_matching_template(current_case):
    c=current_case;c['table']['cells']['g3q/9-10-11-12']=copy.deepcopy(c['table']['cells'][c['base']])
    assert any(e['id']=='tree' for e in gallery.catalog(c['table'],[c['entity']],c['art']))
    c['table']['buildings']=[{'id':'new-whole','atlas':'g3q','tiles':[[1,2],[3,4],[5,6],[7,8]],'height':16,'parts':[],'terrain':[]}]
    with pytest.raises(ValueError,match='stale native current source template metadata'):gallery.catalog(c['table'],[c['entity']],c['art'])


def test_current_native_refuses_stale_proof_context_and_non_emerald(current_case):
    c=current_case;c['entity']['current_native_fragments']['occurrence']['x']=1
    with pytest.raises(ValueError,match='conflicting native assembly context'):gallery.catalog(c['table'],[c['entity']],c['art'])
    c['entity']['current_native_fragments']['occurrence']['x']=0;c['table']['game']='crystal'
    with pytest.raises(ValueError,match='Emerald evidence'):gallery.catalog(c['table'],[c['entity']],c['art'])
    c['table']['game']='emerald';c['path'].write_text(c['path'].read_text()+' ')
    with pytest.raises(ValueError,match='stale native assembly evidence'):gallery.catalog(c['table'],[c['entity']],c['art'])


def test_current_native_fitted_shapes_must_still_match_current_real_geometry(current_case):
    c=current_case;row=c['proof']['occurrences'][0];f=row['corrected_fragments'][0]
    f['key']='cell:0|1,2,3,4|0|0,0,0|v=g0_voxels_k999999999999'
    rewrite_proof(c);c['entity']['current_native_fragments']['evidence_sha256']=hashlib.sha256(c['path'].read_bytes()).hexdigest()
    with pytest.raises(ValueError,match='fitted key differs'):gallery.catalog(c['table'],[c['entity']],c['art'])


@pytest.mark.parametrize('defect',[None,'payload','position','missing','stale_spec'])
def test_current_native_templates_require_exact_source_payload_and_complete_carriers(current_case,defect):
    c=current_case;c['art'].maps['MAP']['mapType']=3
    spec={'id':'source-building','atlas':'g3q','tiles':[[1,2],[3,4]],'height':16,'parts':[],'terrain':[]}
    c['table']['buildings']=[spec];row=c['proof']['occurrences'][0];row['headroom']=6
    pinned={'source-building@0,0':hashlib.sha256(json.dumps(spec,sort_keys=True,separators=(',',':')).encode()).hexdigest()}
    row['current_source_template_hashes']=pinned;c['entity']['current_native_fragments']['source_template_hashes']=copy.deepcopy(pinned)
    box={'roofRows':0,'roof':'flat','parts':[],'height':16,'terrain':[],'bands':[]}
    template={'template':1,'atlas':0,'w':2,'d':2,'perCell':2,'tiles':[1,2,3,4],'doors':[],'ground':0,'outdoor':True,'columns':[[],[],[],[]],'hull':{'kind':'box','height':16,'solid':[2]*4,'air':[0]*4,'box':box}}
    sig=gallery.template_signature(template);row['current_templates']=[{'sig':sig,'x0':0,'y0':0,'entry':template}]
    row['corrected_fragments']=[f for f in row['corrected_fragments'] if f['z']>=2]+[{'key':f'tmpl:0|{sig}|{x},{y},{z}','palette':'BAKED','x':x,'y':y,'z':z} for z in range(2) for x in range(2) for y in range(2)]
    if defect=='payload':template['hull']['solid'][0]=1
    if defect=='position':row['corrected_fragments'][-1]['key']=f'tmpl:0|{sig}|0,1,1'
    if defect=='missing':row['corrected_fragments'].pop()
    if defect=='stale_spec':spec['height']=24
    rewrite_proof(c);c['entity']['current_native_fragments']['evidence_sha256']=hashlib.sha256(c['path'].read_bytes()).hexdigest()
    if defect:
        with pytest.raises(ValueError,match='template'):gallery.catalog(c['table'],[c['entity']],c['art'])
    else:
        entry=next(e for e in gallery.catalog(c['table'],[c['entity']],c['art']) if e['id']=='tree')
        assert len(entry['templates'])==1 and len([f for f in entry['fragments'] if f['key'].startswith('tmpl:')])==8
        assert entry['templates'][0]['sig']==sig


def test_actual_trainer_and_station_native_template_payloads_match_source_and_collision():
    root=Path(__file__).resolve().parents[2];study=root/'local/model-gallery/emerald-pass-study';art=gallery.EmeraldArt(root/'local/minecraft/kantobridge/export/emerald')
    for folder,proof_name in [('building-trainer-hill','runtime-interface-preserved.json'),('building-route112-cable-station','native-context-proof.json')]:
        path=study/folder/proof_name
        if not path.exists():pytest.skip('authorized native template fixture absent')
        row=next(r for r in json.loads(path.read_text())['occurrences'] if 'current_templates' in r)
        table={'buildings':list(json.loads((study/folder/'updates.json').read_text())['buildings'].values())}
        descriptor={'occurrence':{k:row[k] for k in ('map','x','y')},'source_layout':row['layout']};evidence={'descriptor':descriptor,'record':row}
        descriptor['source_template_hashes']=gallery.native_source_template_hashes(table,evidence,art)
        source,raw,grid=gallery.native_assembly_source(evidence,art)
        templates,placements=gallery.native_assembly_templates(row,descriptor,table,source,raw,grid,0)
        assert len(templates)==len(placements)==1


def test_complete_building_contexts_preserve_native_floors_templates_and_archive_evidence():
    from tools.shape_fit.emerald_pass import terrain_native_contexts as p,review
    if not (p.OUT/'entities.json').exists():pytest.skip('authorized complete building contexts absent')
    entities=review.read(p.OUT/'entities.json');table=review.read(p.OUT/'fitted-proof-current.json');art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');ids={e['id'] for e in entities}
    now={e['id']:e for e in gallery.catalog(table,entities,art) if e['id'] in ids};old={e['id']:e for e in gallery.catalog(review.read(review.BASELINE),gallery.baseline_entities(entities,hashlib.sha256(review.BASELINE.read_bytes()).hexdigest()),art) if e['id'] in ids}
    assert len(now)==len(old)==2
    for key,entry in now.items():
        assert len(entry['templates'])==1
        floors=entry['native_current_evidence']['record']['source_floor_columns']
        assert len(floors)==entry['bounds'][0]*entry['bounds'][2]
        assert all(f['feet']==0 for f in floors)
        assert sum(f['y']==0 for f in entry['fragments'])==len(floors)
        assert any(f['key'].startswith('tmpl:') for f in entry['fragments'])
        assert any(f['key'].startswith('roof:') for f in entry['fragments'])
        assert all(not f['key'].startswith('tmpl:') for f in old[key]['fragments'])
        archived=_combined_gallery([], [{'entries':[entry]}],set(table['cells']),{b['id'] for b in table['buildings']})['entries'][0]
        assert archived['native_baselines']['gallery-0']['native_current_evidence']['templates']==entry['templates']

@pytest.mark.parametrize('defect',[None,'height','duplicate','decor','reference_hash'])
def test_fortree_variable_native_profiles_require_complete_source_bound_carriers(defect):
    from tools.shape_fit.emerald_pass import fortree_native_repairs as p,review
    if not (p.OUT/'native-entities.json').exists():pytest.skip('authorized forest source descriptors absent')
    entities=review.read(p.OUT/'native-entities.json');table=review.read(p.OUT/'fitted-proof-current.json');art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald')
    entity=next(e for e in entities if e['id'].endswith('_hedge'));descriptor=entity['current_native_fragments'];evidence=review.read(review.ROOT/descriptor['evidence']);row=evidence['occurrences'][0]
    source,raw,grid=gallery.native_assembly_source({'descriptor':descriptor,'record':row},art)
    if defect=='height':row['current_carrier_reference'][8]['blocks']+=1
    if defect=='duplicate':row['current_carrier_reference'][1]=copy.deepcopy(row['current_carrier_reference'][0])
    if defect=='decor':row['current_carrier_reference'][0]['decor']=1
    if defect=='reference_hash':row.pop('carrier_reference_table_sha256')
    if defect:
        with pytest.raises(ValueError,match='native (carrier|template)'):gallery.native_assembly_templates(row,descriptor,table,source,raw,grid,0)
    else:
        templates,places=gallery.native_assembly_templates(row,descriptor,table,source,raw,grid,0);assert len(templates)==1
        native=next(iter(templates.values()))['hull']['solid'];assert set(native)=={0,4,5}
        entries=[e for e in gallery.catalog(table,entities,art) if e['id'] in {r['id'] for r in entities}];assert len(entries)==5
        assert all(len(e['templates'])==1 for e in entries)


def test_actual_qa10_wholes_preserve_seven_native_contexts_and_nine_occurrences():
    root=Path(__file__).resolve().parents[2];folder=root/'local/model-gallery/emerald-pass-study/qa10-followups/whole'
    if not (folder/'entities.json').exists():pytest.skip('authorized QA10 source proof absent')
    entities=json.loads((folder/'entities.json').read_text());assert len(entities)==7 and sum(len(e['occurrences']) for e in entities)==9
    current=json.loads((root/'local/model-gallery/emerald-batch-02/qa10-wholes-01/fitted-current.json').read_text())
    old_path=root/'local/model-gallery/emerald-full-pass-baseline.json';old=json.loads(old_path.read_text());art=gallery.EmeraldArt(root/'local/minecraft/kantobridge/export/emerald')
    ids={e['id'] for e in entities};now={e['id']:e for e in gallery.catalog(current,entities,art) if e['id'] in ids}
    before={e['id']:e for e in gallery.catalog(old,gallery.baseline_entities(entities,hashlib.sha256(old_path.read_bytes()).hexdigest()),art) if e['id'] in ids}
    assert set(now)==set(before)==ids
    for key in ids:
        assert now[key]['placement_mode']=='source-map-current' and before[key]['placement_mode']=='source-map-baseline'
        assert now[key]['fragments']==before[key]['fragments']
    ship=[e for e in now.values() if 'ship_miniature' in e['id']]
    assert len(ship)==3 and len({json.dumps(e['fragments'],sort_keys=True) for e in ship})==3


def test_actual_fortree_overlays_keep_masks_ids_and_independent_source_proofs():
    root=Path(__file__).resolve().parents[2];folder=root/'local/model-gallery/emerald-pass-study/fortree-assembly-diagnostics'
    if not (folder/'entities.json').exists():pytest.skip('authorized Fortree source proof absent')
    entities=json.loads((folder/'entities.json').read_text());old_entities=json.loads((folder/'preconditions.json').read_text())
    assert len(entities)==len(old_entities)==9
    assert len({e['current_native_fragments']['evidence'] for e in entities})==9
    for e in entities:
        original=old_entities[e['id']]
        for field in ('id','layout','reference_layout','placements','baseline_layout','baseline_placements'):
            assert e.get(field)==original.get(field)
    current=json.loads((folder/'fitted-proof-current.json').read_text());unchanged=copy.deepcopy(current)
    baseline_path=root/'local/model-gallery/emerald-full-pass-baseline.json';baseline=json.loads(baseline_path.read_text());art=gallery.EmeraldArt(root/'local/minecraft/kantobridge/export/emerald')
    ids={e['id'] for e in entities};now={e['id']:e for e in gallery.catalog(current,entities,art) if e['id'] in ids};before={e['id']:e for e in gallery.catalog(baseline,gallery.baseline_entities(entities,hashlib.sha256(baseline_path.read_bytes()).hexdigest()),art) if e['id'] in ids}
    assert set(now)==set(before)==ids and current==unchanged
    for e in entities:
        for version in (now[e['id']],before[e['id']]):
            assert all(e['layout'][f['z']//2][f['x']//2] is not None for f in version['fragments'])
    marker=now['emerald_fortree_foliage_gym_marker']
    assert marker['bounds']==[2,4,4] and all(f['z']>=2 for f in marker['fragments'])
    assert not any(f['z']<2 for f in now['emerald_fortree_foliage_hedge_trees']['fragments'])


def test_actual_rusturf_contexts_keep_native_nine_high_walls_and_ledge_slabs():
    from tools.shape_fit.emerald_pass import rusturf_native_contexts as p,review
    if not (p.OUT/'runtime-proof.json').exists():pytest.skip('authorized Rusturf candidate proof absent')
    entities=p.generate();art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald')
    current=review.read(p.OUT/'fitted-proof-current.json');old=review.read(review.BASELINE);ids={e['id'] for e in entities}
    now={e['id']:e for e in gallery.catalog(current,entities,art) if e['id'] in ids}
    before={e['id']:e for e in gallery.catalog(old,gallery.baseline_entities(entities,hashlib.sha256(review.BASELINE.read_bytes()).hexdigest()),art) if e['id'] in ids}
    assert sorted(e['bounds'][1] for e in now.values())==[2,9]
    for key in ids:
        assert now[key]['bounds']==before[key]['bounds']
        native=lambda entry:[f for f in entry['fragments'] if not f['key'].startswith('cell:')]
        assert native(now[key])==native(before[key])
        assert any(f['key'].startswith('slab:') for f in now[key]['fragments'])


def test_actual_soil_contexts_include_all_proven_floors_and_unchanged_west_ledge_slabs():
    root=Path(__file__).resolve().parents[2];folder=root/'local/model-gallery/emerald-pass-study/soil-perimeter-completion'
    if not (folder/'native-entities.json').exists():pytest.skip('authorized soil source descriptors absent')
    entities=json.loads((folder/'native-entities.json').read_text());assert len(entities)==22
    current=json.loads((folder/'fitted-proof-current.json').read_text());old_path=root/'local/model-gallery/emerald-full-pass-baseline.json';old=json.loads(old_path.read_text());art=gallery.EmeraldArt(root/'local/minecraft/kantobridge/export/emerald');ids={e['id'] for e in entities}
    now={e['id']:e for e in gallery.catalog(current,entities,art) if e['id'] in ids};before={e['id']:e for e in gallery.catalog(old,gallery.baseline_entities(entities,hashlib.sha256(old_path.read_bytes()).hexdigest()),art) if e['id'] in ids}
    assert len(now)==len(before)==22
    assert sum(len(e['native_current_evidence']['record']['source_floor_columns']) for e in now.values())==920
    for key in ids:
        assert now[key]['bounds']==before[key]['bounds']
        floors=now[key]['native_current_evidence']['record']['source_floor_columns'];assert all(c['feet']==0 for c in floors)
        assert sum(f['y']==0 for f in now[key]['fragments'])==len(floors)
        assert [f for f in now[key]['fragments'] if f['key'].startswith('slab:')]==[f for f in before[key]['fragments'] if f['key'].startswith('slab:')]


def test_actual_origin_contexts_keep_source_proven_cave_walk_floors_and_warp_approaches():
    root=Path(__file__).resolve().parents[2];folder=root/'local/model-gallery/emerald-pass-study/origin-exit-orientation'
    if not (folder/'native-entities.json').exists():pytest.skip('authorized Origin source descriptors absent')
    entities=json.loads((folder/'native-entities.json').read_text());assert len(entities)==2
    current=json.loads((folder/'fitted-proof-current.json').read_text());old_path=root/'local/model-gallery/emerald-full-pass-baseline.json';old=json.loads(old_path.read_text());art=gallery.EmeraldArt(root/'local/minecraft/kantobridge/export/emerald');ids={e['id'] for e in entities}
    now={e['id']:e for e in gallery.catalog(current,entities,art) if e['id'] in ids};before={e['id']:e for e in gallery.catalog(old,gallery.baseline_entities(entities,hashlib.sha256(old_path.read_bytes()).hexdigest()),art) if e['id'] in ids}
    assert set(now)==set(before)==ids
    cave_floors=0
    for key in ids:
        record=now[key]['native_current_evidence']['record'];floors=record['source_floor_columns'];assert len(floors)==48
        cave_floors+=sum(record['source_collisions'][f['z']//2][f['x']//2]==43 for f in floors)
        assert any(114 in row for row in record['source_collisions'])
        assert sum(f['y']==0 for f in now[key]['fragments'])==len(floors)
        old_floor={(f['x'],f['y'],f['z']):f['key'] for f in before[key]['fragments'] if f['y']==0}
        assert {(f['x'],f['y'],f['z']):f['key'] for f in now[key]['fragments'] if f['y']==0}==old_floor
    assert cave_floors>0
