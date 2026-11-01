"""Bind complete berry soil contexts to actual floor and native column evidence."""
import copy,hashlib
from tools import model_gallery as gallery
from tools.shape_fit.emerald_pass import review
from tools.shape_fit.emerald_pass.qa10_wholes import canonical_hash
from tools.shape_fit.emerald_pass.soil_perimeter_completion import OUT

def generate():
    proof=OUT/'runtime-proof.json';runtime=review.read(proof);snapshot=OUT/'fitted-proof-current.json'
    if hashlib.sha256(snapshot.read_bytes()).hexdigest()!=runtime['current_table_sha256']:raise ValueError('Soil source snapshot differs')
    if hashlib.sha256((OUT/'updates.json').read_bytes()).hexdigest()!=runtime['candidate_updates_sha256']:raise ValueError('Soil candidate differs from runtime proof')
    table=review.read(snapshot);art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');result=[]
    for original in runtime['occurrences']:
        row=copy.deepcopy(original);layout=row['layout'];common={'occurrence':{k:row[k] for k in ('map','x','y')},'source_layout':layout}
        row['current_source_cell_hashes']={key:canonical_hash(table['cells'].get(key)) for key in sorted({k for line in layout for k in line})}
        row['current_source_template_hashes']=gallery.native_source_template_hashes(table,{'descriptor':common,'record':row},art)
        path=OUT/'native-proofs'/(row['id']+'.json');gallery.write_json(path,{'original_baseline_sha256':runtime['original_baseline_sha256'],'current_table_sha256':runtime['current_table_sha256'],'parent_runtime_evidence':{'path':proof.relative_to(review.ROOT).as_posix(),'sha256':hashlib.sha256(proof.read_bytes()).hexdigest()},'occurrences':[row]})
        common.update(evidence=path.relative_to(review.ROOT).as_posix(),evidence_sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        result.append({'id':row['id'],'kind':'actual native berry soil context','layout':layout,
            'baseline_native_fragments':dict(common,baseline_sha256=runtime['original_baseline_sha256']),
            'current_native_fragments':dict(common,current_table_sha256=runtime['current_table_sha256'],source_cell_hashes=row['current_source_cell_hashes'],source_template_hashes=row['current_source_template_hashes']),
            'occurrences':[dict(common['occurrence'],palette='BAKED')],
            'note':'Exact source bed with all native grass/soil floor quarters, original/current terrain columns and any adjoining source cliff/tree projection. Floor at actual feet-1 and carriers at actual feet are shifted together only for gallery framing. No native cells omitted or replaced with invented fitted geometry; native SOLID berry heights remain visible and are not claimed flattened.'})
    return result

if __name__=='__main__':
    result=generate();gallery.write_json(OUT/'native-entities.json',result);print(len(result),'actual native floor and column contexts')
