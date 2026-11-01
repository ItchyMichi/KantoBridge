"""Proof-bound native companions for narrow cave orientation/ledge corrections."""
import argparse,copy,hashlib
from tools import model_gallery as gallery
from tools.shape_fit.emerald_pass import review
from tools.shape_fit.emerald_pass.qa10_wholes import canonical_hash
FOLDERS={'rusturf-ledge-catalog':'Exact original/current Rusturf native wall and ledge context. The fitted standalone ledge now matches slab4, but every real source ledge remains the unchanged native SLAB route; five previously corrected cliff pieces and native wall heights are preserved.',
         'origin-exit-orientation':'Exact Cave of Origin exit context including the north WALK114 warp approach, source-native floors, three SOLID rim cells and adjoining southern native wall. The rim opens north; an actual north-approach Minecraft view is still required before visual approval.'}

def generate(folder):
    out=review.ROOT/'local/model-gallery/emerald-pass-study'/folder;proof=out/'runtime-proof.json';runtime=review.read(proof);snapshot=out/'fitted-proof-current.json'
    if hashlib.sha256(snapshot.read_bytes()).hexdigest()!=runtime['current_table_sha256']:raise ValueError('Cave source snapshot differs')
    if hashlib.sha256((out/'updates.json').read_bytes()).hexdigest()!=runtime['candidate_updates_sha256']:raise ValueError('Cave candidate differs from runtime proof')
    table=review.read(snapshot);art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');result=[]
    for original in runtime['occurrences']:
        row=copy.deepcopy(original);layout=row['layout'];common={'occurrence':{k:row[k] for k in ('map','x','y')},'source_layout':layout}
        row['current_source_cell_hashes']={key:canonical_hash(table['cells'].get(key)) for key in sorted({k for line in layout for k in line})}
        row['current_source_template_hashes']=gallery.native_source_template_hashes(table,{'descriptor':common,'record':row},art)
        path=out/'native-proofs'/(row['id']+'.json');gallery.write_json(path,{'original_baseline_sha256':runtime['original_baseline_sha256'],'current_table_sha256':runtime['current_table_sha256'],'parent_runtime_evidence':{'path':proof.relative_to(review.ROOT).as_posix(),'sha256':hashlib.sha256(proof.read_bytes()).hexdigest()},'occurrences':[row]})
        common.update(evidence=path.relative_to(review.ROOT).as_posix(),evidence_sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        result.append({'id':row['id'],'kind':'actual native cave source context','layout':layout,'baseline_native_fragments':dict(common,baseline_sha256=runtime['original_baseline_sha256']),
            'current_native_fragments':dict(common,current_table_sha256=runtime['current_table_sha256'],source_cell_hashes=row['current_source_cell_hashes'],source_template_hashes=row['current_source_template_hashes']),
            'occurrences':[dict(common['occurrence'],palette='BAKED')],'note':FOLDERS[folder]})
    return result

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('folder',choices=tuple(FOLDERS));args=ap.parse_args();entities=generate(args.folder);out=review.ROOT/'local/model-gallery/emerald-pass-study'/args.folder;gallery.write_json(out/'native-entities.json',entities);print(len(entities),'native cave contexts')
if __name__=='__main__':main()
