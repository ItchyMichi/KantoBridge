"""Preserve nine Fortree IDs while binding each layout to its own native source proof."""
from __future__ import annotations
import copy
import hashlib
from pathlib import Path
from tools import model_gallery as gallery
from tools.shape_fit.emerald_pass.qa10_wholes import canonical_hash

ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'local/model-gallery/emerald-pass-study/fortree-assembly-diagnostics'


def generate():
    runtime_path=OUT/'runtime-proof.json';runtime=gallery.read_json(runtime_path)
    snapshot=OUT/'fitted-proof-current.json'
    if hashlib.sha256(snapshot.read_bytes()).hexdigest()!=runtime['current_table_sha256']:
        raise ValueError('Fortree native proof snapshot differs')
    table=gallery.read_json(snapshot);art=gallery.EmeraldArt(ROOT/'local/minecraft/kantobridge/export/emerald')
    originals=gallery.read_json(OUT/'original-entities.json');cases={g['id']:g for g in gallery.read_json(OUT/'source-cases.json')['groups']}
    if len(originals)!=9 or {e['id'] for e in originals}!=set(cases):raise ValueError('Fortree ownership differs')
    result=[];preconditions={}
    for old in originals:
        identifier=old['id'];case=cases[identifier]
        if canonical_hash(old)!=case['source_descriptor_sha256']:raise ValueError('original Fortree descriptor changed')
        preconditions[identifier]=copy.deepcopy(old)
        row=copy.deepcopy(next(r for r in runtime['occurrences'] if r['id']==identifier))
        layout=row['layout'];physical=old['layout'];mask=[[key is not None for key in line] for line in physical]
        if any(not value for line in mask for value in line):
            row['source_mask']=mask
            for field in ('original_baseline_fragments','corrected_fragments'):
                row[field]=[f for f in row[field] if mask[f['z']//2][f['x']//2]]
        row['current_source_cell_hashes']={key:canonical_hash(table['cells'].get(key)) for key in sorted({k for line in layout for k in line})}
        common={'occurrence':{k:row[k] for k in ('map','x','y')},'source_layout':layout}
        row['current_source_template_hashes']=gallery.native_source_template_hashes(table,{'descriptor':common,'record':row},art)
        proof={'original_baseline_sha256':runtime['original_baseline_sha256'],'current_table_sha256':runtime['current_table_sha256'],
               'parent_runtime_evidence':{'path':runtime_path.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(runtime_path.read_bytes()).hexdigest()},
               'occurrences':[row]}
        path=OUT/'native-proofs'/f'{identifier}.json';gallery.write_json(path,proof)
        common.update(evidence=path.relative_to(ROOT).as_posix(),evidence_sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        entity=copy.deepcopy(old)
        entity['baseline_native_fragments']=dict(common,baseline_sha256=runtime['original_baseline_sha256'])
        entity['current_native_fragments']=dict(common,current_table_sha256=runtime['current_table_sha256'],source_cell_hashes=row['current_source_cell_hashes'],source_template_hashes=row['current_source_template_hashes'])
        entity['occurrences']=[dict(common['occurrence'],palette='BAKED')]
        entity['note']=old.get('note','')+' Actual source-map original/current columns now determine gallery placement; source-only masked cells remain excluded. No model geometry or production metadata changes.'
        result.append(entity)
    return result,preconditions


if __name__=='__main__':
    entities,preconditions=generate();gallery.write_json(OUT/'entities.json',entities);gallery.write_json(OUT/'preconditions.json',preconditions)
    print('Nine Fortree native placement overlays; one immutable proof per exact source context')
