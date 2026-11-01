"""Native floor/warp comparison for the retained Lilycove Museum2F stair."""
import copy,hashlib,json
from tools import model_gallery as gallery
from tools.shape_fit.emerald_pass.towns import ROOT,BUNDLE,BASELINE
from tools.shape_fit.emerald_pass.qa10_wholes import canonical_hash
OUT=ROOT/'local/model-gallery/emerald-pass-study/lilycove-museum-stairs-native'
IDENT='emerald_lilycove_museum_stairs2'

def generate():
    runtime_path=OUT/'runtime-proof.json';runtime=json.loads(runtime_path.read_text());snapshot=OUT/'fitted-proof-current.json';assert hashlib.sha256(snapshot.read_bytes()).hexdigest()==runtime['current_table_sha256'];table=json.loads(snapshot.read_text());art=gallery.EmeraldArt(BUNDLE);originals=json.loads((OUT/'original-entities.json').read_text());assert len(originals)==1 and originals[0]['id']==IDENT;old=originals[0];case=json.loads((OUT/'source-cases.json').read_text());assert canonical_hash(old)==case['original_descriptor_sha256'];row=copy.deepcopy(runtime['occurrences'][0]);assert row['id']==IDENT and row['layout']==old['layout'];assert [row['map'],row['x'],row['y']]==[case['map'],case['x'],case['y']]
    common={'occurrence':{k:row[k] for k in ('map','x','y')},'source_layout':row['layout']};row['current_source_cell_hashes']={key:canonical_hash(table['cells'].get(key)) for key in sorted({k for line in row['layout'] for k in line})};row['current_source_template_hashes']=gallery.native_source_template_hashes(table,{'descriptor':common,'record':row},art)
    path=OUT/'native-proof.json';proof={'original_baseline_sha256':runtime['original_baseline_sha256'],'current_table_sha256':runtime['current_table_sha256'],'parent_runtime_evidence':{'path':runtime_path.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(runtime_path.read_bytes()).hexdigest()},'occurrences':[row]};gallery.write_json(path,proof);common.update(evidence=path.relative_to(ROOT).as_posix(),evidence_sha256=hashlib.sha256(path.read_bytes()).hexdigest());entity=copy.deepcopy(old);entity['baseline_native_fragments']=dict(common,baseline_sha256=runtime['original_baseline_sha256']);entity['current_native_fragments']=dict(common,current_table_sha256=runtime['current_table_sha256'],source_cell_hashes=row['current_source_cell_hashes'],source_template_hashes=row['current_source_template_hashes']);entity['occurrences']=[dict(common['occurrence'],palette='BAKED')];entity['note']=old.get('note','')+' Source-proven native floors and actual retained fitted carriers now determine both gallery revisions. Center WALK114 warp stays zero collision. Its native floor is tile6936, not orange tread art: this descriptor makes that limitation visible and does not claim the missing tread appearance is repaired.'
    return [entity],{IDENT:old}

def main():
    entities,preconditions=generate();gallery.write_json(OUT/'entities.json',entities);gallery.write_json(OUT/'preconditions.json',preconditions);print('One exact source-native museum stair comparison; no model/table changes')

if __name__=='__main__':main()
