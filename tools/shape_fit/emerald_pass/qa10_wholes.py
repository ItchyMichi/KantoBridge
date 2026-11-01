"""Bind five reviewed QA10 source contexts to their actual old and current planner columns."""
from __future__ import annotations
import copy
import hashlib
import json
from pathlib import Path
from tools import model_gallery as gallery

ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'local/model-gallery/emerald-pass-study/qa10-followups/whole'
SNAPSHOT=ROOT/'local/model-gallery/emerald-batch-02/scenery-24/fitted-current.json'


def canonical_hash(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest() if value is not None else None


def generate(proof_path=OUT/'runtime-proof.json',snapshot=SNAPSHOT):
    proof=gallery.read_json(proof_path)
    if hashlib.sha256(snapshot.read_bytes()).hexdigest()!=proof['current_table_sha256']:
        raise ValueError('QA10 source proof and exact captured current table differ')
    table=gallery.read_json(snapshot);art=gallery.EmeraldArt(ROOT/'local/minecraft/kantobridge/export/emerald')
    proof['parent_runtime_evidence']={'path':proof_path.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(proof_path.read_bytes()).hexdigest()}
    proof['current_table_snapshot']=snapshot.relative_to(ROOT).as_posix()
    groups={}
    for row in proof['occurrences']:
        keys={key for line in row['layout'] for key in line}
        row['current_source_cell_hashes']={k:canonical_hash(table['cells'].get(k)) for k in sorted(keys)}
        descriptor={'occurrence':{k:row[k] for k in ('map','x','y')},'source_layout':row['layout']}
        row['current_source_template_hashes']=gallery.native_source_template_hashes(table,{'descriptor':descriptor,'record':row},art)
        if row['current_source_template_hashes']:
            raise ValueError('QA10 contexts were diagnosed as native cell columns; matching whole template now requires a refreshed fixture')
        groups.setdefault(row['id'],[]).append(row)
    target=OUT/'runtime-current-provenance.json';gallery.write_json(target,proof)
    evidence=target.relative_to(ROOT).as_posix();evidence_hash=hashlib.sha256(target.read_bytes()).hexdigest();entities=[]
    variants={}
    for name,rows in groups.items():
        by_shapes={}
        for row in rows:by_shapes.setdefault(canonical_hash(row['corrected_fragments']),[]).append(row)
        for i,variant in enumerate(by_shapes.values()):
            suffix='' if i==0 else '_aqua' if 'AQUA' in variant[0]['map'] else '_shipyard'
            variants[name+suffix]=variant
    for name,rows in variants.items():
        row=rows[0];layout=copy.deepcopy(row['layout'])
        if any(r['layout']!=layout or r['corrected_fragments']!=row['corrected_fragments'] for r in rows):
            raise ValueError('same QA10 whole has different source routing across occurrences: '+name)
        common={'evidence':evidence,'evidence_sha256':evidence_hash,'occurrence':{k:row[k] for k in ('map','x','y')},'source_layout':layout}
        entities.append({'id':'emerald_qa10_'+name.replace('-','_'),'kind':'actual source-map whole context','layout':layout,
                         'baseline_native_fragments':dict(common,baseline_sha256=proof['original_baseline_sha256']),
                         'current_native_fragments':dict(common,current_table_sha256=proof['current_table_sha256'],source_cell_hashes=row['current_source_cell_hashes'],source_template_hashes=row['current_source_template_hashes']),
                         'occurrences':[{k:r[k] for k in ('map','x','y')}|{'palette':'BAKED'} for r in rows],
                         'note':'Original and current actual source-map planner columns. Whole-context inspection is pending; isolated support findings are not treated as geometry approval.'})
    return entities


if __name__=='__main__':
    entities=generate();gallery.write_json(OUT/'entities.json',entities)
    print(f'{len(entities)} actual source-map QA10 comparisons across five semantic groups; all9 source occurrences retained')
