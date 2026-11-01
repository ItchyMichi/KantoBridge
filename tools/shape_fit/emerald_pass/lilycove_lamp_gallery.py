"""Source-proven entity-only correction for the Lilycove lamp gallery stack."""
import hashlib,json
from tools.model_gallery import EmeraldArt,catalog,select_entries
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,digest,rows_for
from tools.shape_fit.emerald_pass.towns_lilycove import assemblies,witnesses
from tools.shape_fit.emerald_pass.towns_rustboro_props import render_assembly
OUT=ROOT/'local/model-gallery/emerald-pass-study/lilycove-lamp-gallery'
IDENT='emerald_lilycove_streetlamp'
LIVE=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json'

def proposal(art):
    entity=next(e for e in assemblies(art) if e['id']==IDENT);assert entity['kind']=='stack (model as one)'
    old=next(e for e in json.loads((ROOT/'local/model-gallery/emerald-pass-study/towns-lilycove/entities.json').read_text()) if e['id']==IDENT)
    assert {**old,'kind':entity['kind']}==entity
    return old,entity

def main():
    art=EmeraldArt(BUNDLE);old,e=proposal(art);table=json.loads(LIVE.read_text());baseline=json.loads(BASELINE.read_text());rows=rows_for('lilycove');top,base=e['layout'][0][0],e['layout'][1][0];assert table['cells'][top]['on']==base
    OUT.mkdir(parents=True,exist_ok=True);art.drawing(e['layout'],'BAKED').resize((160,320)).save(OUT/'source.png');render_assembly(e,art,baseline['cells'],OUT,'before');render_assembly(e,art,table['cells'],OUT,'after')
    selected=select_entries(catalog(table,[e],art),[IDENT]);assert len(selected)==1 and selected[0]['placement_mode']=='stack' and selected[0]['bounds']==[2,4,2]
    correction={'id':IDENT,'before':old,'after':e,'before_entity_sha256':digest(old),'after_entity_sha256':digest(e),'source_pixel_sha256':hashlib.sha256(art.drawing(e['layout'],'BAKED').convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB','witnesses':{k:witnesses(art,rows[n-1]) for n,k in ((3,top),(14,base))},'geometry_changed':False,'metadata_changed':False,'reason':'QA17 captured both16-high lamp slices on separate floor cells because the explicit entity was classified object. The existing on relation and exact upper/base source prove one vertical32-high lamp. Classify only this gallery entity as stack, leaving all live model geometry and metadata unchanged. Actual source runtime routing requires a separate fixture.','catalog_proof':{'entry_id':selected[0]['id'],'aliases':selected[0].get('aliases',[]),'placement_mode':selected[0]['placement_mode'],'bounds':selected[0]['bounds'],'model_hashes':selected[0]['model_hashes']},'live_table_sha256':hashlib.sha256(LIVE.read_bytes()).hexdigest(),'source_qa_defect':'local/model-gallery/emerald-batch-02/scenery-17/sheets/emerald_lilycove_streetlamp_detail.png'}
    for name,value in [('entities.json',[e]),('entity-corrections.json',[correction])]: (OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'bounds':selected[0]['bounds'],'changed_gallery_entities':1,'changed_models':0}))

if __name__=='__main__':main()
