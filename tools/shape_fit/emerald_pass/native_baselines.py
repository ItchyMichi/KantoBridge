"""Bind new building gallery companions to exact original runtime source proofs."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def building_entities(proof_path: Path) -> list[dict]:
    proof_path = proof_path.resolve()
    if not proof_path.is_relative_to((ROOT / 'local').resolve()):
        raise ValueError('runtime proof must stay in local')
    raw = proof_path.read_bytes()
    proof = json.loads(raw)
    grouped = {}
    for row in proof['occurrences']:
        identifier = row['id']
        if identifier in grouped and grouped[identifier]['layout'] != row['layout']:
            raise ValueError('one building identity has conflicting source layouts: ' + identifier)
        grouped.setdefault(identifier, row)
    entities = []
    for identifier, row in sorted(grouped.items()):
        layout = copy.deepcopy(row['layout'])
        entity = {
            'id': identifier,
            'kind': 'source-map original companion for building',
            'building_baseline': True,
            'layout': layout,
            'baseline_native_fragments': {
                'evidence': proof_path.relative_to(ROOT).as_posix(),
                'evidence_sha256': hashlib.sha256(raw).hexdigest(),
                'baseline_sha256': proof['original_baseline_sha256'],
                'occurrence': {field: row[field] for field in ('map', 'x', 'y')},
                'source_layout': copy.deepcopy(layout),
            },
        }
        if 'source_mask' in row:
            entity['reference_layout'] = copy.deepcopy(layout)
            entity['layout'] = [[key if row['source_mask'][z][x] else None for x,key in enumerate(line)] for z,line in enumerate(layout)]
        entities.append(entity)
    return entities


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--proof', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--terrain-exclusions', type=Path, help='Exact candidate updates whose excluded terrain also stays outside this building-only baseline')
    args = parser.parse_args()
    proof_path=args.proof
    if args.terrain_exclusions:
        proof=json.loads(args.proof.read_bytes());updates=json.loads(args.terrain_exclusions.read_bytes());proof=copy.deepcopy(proof)
        proof['parent_runtime_evidence']={'path':args.proof.resolve().relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(args.proof.read_bytes()).hexdigest()}
        proof['candidate_updates_sha256']=hashlib.sha256(args.terrain_exclusions.read_bytes()).hexdigest()
        for row in proof['occurrences']:
            spec=updates['buildings'][row['id']];layout=row['layout'];excluded={tuple(p) for p in spec['terrain']}
            if row.get('candidate_updates_sha256')!=proof['candidate_updates_sha256']:raise ValueError('candidate differs from actual runtime proof')
            if any(not (0<=x<len(layout[0]) and 0<=z<len(layout)) for x,z in excluded):raise ValueError('terrain exclusion outside source')
            row['source_mask']=[[ (x,z) not in excluded for x in range(len(line))] for z,line in enumerate(layout)]
            for field in ('original_baseline_fragments','corrected_fragments'):
                row[field]=[f for f in row[field] if (f['x']//2,f['z']//2) not in excluded]
                lifts={}
                for f in row[field]:
                    match=re.fullmatch(r'cell:0\|([0-9,]+)\|-?\d+\|([01]),([01]),([01])\|v=.+',f['key'])
                    if match and int(match[3])!=f['y']%2:
                        key='g3q/'+match[1].replace(',','-');base=f['y']-int(match[3]);lifts[f['x'],f['z'],key]={'x':f['x'],'z':f['z'],'key':key,'base_y':base}
                if lifts:row['original_fitted_lifts' if field.startswith('original') else 'current_fitted_lifts']=list(lifts.values())
            row['comparison_scope']='Exact building-owned cells only; excluded native terrain remains in the full source image and separate map context.'
        proof_path=args.out.parent/'building-baseline-proof.json'
        proof_path.write_text(json.dumps(proof,indent=2)+'\n',encoding='utf-8')
    entities = building_entities(proof_path)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(entities, indent=2) + '\n', encoding='utf-8')
    print(f'{len(entities)} source-bound building baseline companions: {args.out}')


if __name__ == '__main__':
    main()
