"""Exact source-ground mask for the Sootopolis marker court gallery."""
import base64,hashlib,json
from tools.model_gallery import EmeraldArt,catalog,select_entries
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,digest
from tools.shape_fit.emerald_pass.towns_sootopolis import assemblies
from tools.shape_fit.emerald_pass.towns_rustboro_rails import render_assembly
OUT=ROOT/'local/model-gallery/emerald-pass-study/sootopolis-marker-gallery'
IDENT='emerald_sootopolis_marker_court'
LIVE=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json'

def proposal(art):
    new=next(e for e in assemblies(art) if e['id']==IDENT);old=next(e for e in json.loads((ROOT/'local/model-gallery/emerald-pass-study/towns-sootopolis/entities.json').read_text()) if e['id']==IDENT)
    assert old['layout']==new['reference_layout'];assert old['id']==new['id'] and old['occurrences']==new['occurrences'];assert all(r[1] is None for r in new['layout'])
    return old,new

def main():
    art=EmeraldArt(BUNDLE);old,new=proposal(art);table=json.loads(LIVE.read_text());baseline=json.loads(BASELINE.read_text());OUT.mkdir(parents=True,exist_ok=True);im=art.drawing(new['reference_layout'],'BAKED');im.resize((384,256)).save(OUT/'source.png');render_assembly(new,art,baseline['cells'],OUT,'before');render_assembly(new,art,table['cells'],OUT,'after')
    m=art.maps['EM_SOOTOPOLIS_CITY'];raw=base64.b64decode(m['cells']);cells=[]
    for z,row in enumerate(old['layout']):
        for x,k in enumerate(row):
            i=((19+z)*m['w']+30+x)*4;cells.append({'map':'EM_SOOTOPOLIS_CITY','x':30+x,'y':19+z,'key':k,'collision':raw[i+2],'elevation':raw[i+3],'included_in_object':x!=1});assert raw[i+2]==(0 if x==1 else 7)
    selected=select_entries(catalog(table,[new],art),[IDENT]);assert len(selected)==1 and selected[0]['bounds']==[6,2,4];assert len(set(selected[0]['source_keys']))==1
    correction={'id':IDENT,'before':old,'after':new,'before_entity_sha256':digest(old),'after_entity_sha256':digest(new),'source_pixel_sha256':hashlib.sha256(im.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB','witnesses':cells,'geometry_changed':False,'metadata_changed':False,'reason':'QA17 whole incorrectly instantiated shared cliff geometry on the two central WALK0 ground positions because the gallery does not derive source collision routing for ordinary compound layouts. Mask only those two center cells in this marker-only exhibit and retain the exact full source in reference_layout. The four SOLID marker-pair positions and shared cliff entry remain unchanged. Actual normal-map native routing requires separate verification and is not inferred from this masked preview.','catalog_proof':{'bounds':selected[0]['bounds'],'source_keys':selected[0]['source_keys'],'placement_mode':selected[0]['placement_mode']},'live_table_sha256':hashlib.sha256(LIVE.read_bytes()).hexdigest(),'source_qa_defect':'local/model-gallery/emerald-batch-02/scenery-17/sheets/emerald_sootopolis_marker_court_detail.png'}
    for name,value in [('entities.json',[new]),('entity-corrections.json',[correction])]: (OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'masked_source_ground_cells':2,'marker_pairs':4,'changed_models':0}))

if __name__=='__main__':main()
