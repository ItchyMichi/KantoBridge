"""Exact native floor comparisons for New Mauville's three switch colors.

The source's ten finite story sites remain floor art. No fitted geometry,
collision metadata, runtime behavior or shared entity table is changed here.
"""
from __future__ import annotations
import copy
import hashlib
import json
from tools import model_gallery as gallery
from tools.shape_fit.emerald_pass.towns import ROOT, BUNDLE, BASELINE
from tools.shape_fit.emerald_pass.qa10_wholes import canonical_hash

OUT = ROOT / 'local/model-gallery/emerald-pass-study/new-mauville-switches-native'
GROUPS = (
    ('green', 'g3q/13795-13796-13797-13798', 4, 558),
    ('blue', 'g3q/13812-13813-13814-13815', 5, 566),
    ('red', 'g3q/13825-13826-13827-13828', 1, 574),
)


def generate():
    runtime_path = OUT / 'runtime-proof.json'
    runtime = json.loads(runtime_path.read_text())
    snapshot = OUT / 'fitted-proof-current.json'
    assert hashlib.sha256(snapshot.read_bytes()).hexdigest() == runtime['current_table_sha256']
    audit_path = OUT / 'source-audit.json'
    assert hashlib.sha256(audit_path.read_bytes()).hexdigest() == runtime['source_audit_sha256']
    table = json.loads(snapshot.read_text())
    art = gallery.EmeraldArt(BUNDLE)
    entities = []
    for color, key, count, mid in GROUPS:
        rows = [r for r in runtime['occurrences'] if r['id'] == key]
        assert len(rows) == count
        assert {(r['map'], r['x'], r['y']) for r in rows} == {
            (r['map'], r['x'], r['y']) for r in art.locations[key]}
        for r in rows:
            assert r['layout'] == [[key]] and r['source_collisions'] == [[0]]
            assert not r['original_baseline_fragments'] and not r['corrected_fragments']
            assert len(r['source_floor_columns']) == 4
            assert sorted(c['after_tile'] for c in r['source_floor_columns']) == sorted(map(int, key.split('/')[1].split('-')))
            assert all(c['before_tile'] == c['after_tile'] and c['feet'] == 0 for c in r['source_floor_columns'])
            story = r['story_site']
            if story['type'] == 'CELL':
                assert story['start'] == mid and story['built'] == {'metatile': mid, 'pass': 'GROUND'}
                assert story['states'] == [{'metatile': 591, 'pass': 'GROUND', 'height': 0, 'art': [13850, 13851, 13852, 13853]}]
            else:
                assert story['type'] == 'OBJECT' and story['states'] == []
                assert story['obj'] in {'O2', 'O4', 'O5', 'O6', 'O7', 'O8'}
        row = copy.deepcopy(next(r for r in runtime['comparisons'] if r['id'] == key))
        assert row['layout'] == [[key, 'g3q/13747-13748-13748-13747']]
        common = {'occurrence': {k: row[k] for k in ('map', 'x', 'y')}, 'source_layout': row['layout']}
        row['current_source_cell_hashes'] = {k: canonical_hash(table['cells'].get(k)) for k in row['layout'][0]}
        row['current_source_template_hashes'] = gallery.native_source_template_hashes(table, {'descriptor': common, 'record': row}, art)
        proof = {k: runtime[k] for k in ('original_baseline_sha256', 'current_table_sha256')}
        proof.update(parent_runtime_evidence={'path': runtime_path.relative_to(ROOT).as_posix(), 'sha256': hashlib.sha256(runtime_path.read_bytes()).hexdigest()}, occurrences=[row])
        proof_path = OUT / 'native-proofs' / f'{color}.json'
        gallery.write_json(proof_path, proof)
        common.update(evidence=proof_path.relative_to(ROOT).as_posix(), evidence_sha256=hashlib.sha256(proof_path.read_bytes()).hexdigest())
        entities.append({
            'id': f'emerald_new_mauville_switch_{color}_native', 'kind': 'source-floor',
            'layout': copy.deepcopy(row['layout']), 'occurrences': [dict(common['occurrence'], palette='BAKED')],
            'note': f'New Mauville {color} floor switch; all {count} exact placements verified. Native floor carries the colored circle with zero fitted decoration/collision. The representative has a CELL state591 with gray floor art and zero-height GROUND behavior. Six of the ten total switches instead retain existing item-ball OBJECT priority; this comparison does not change that story routing. No raised model or metadata changes.',
            'baseline_native_fragments': dict(common, baseline_sha256=runtime['original_baseline_sha256']),
            'current_native_fragments': dict(common, current_table_sha256=runtime['current_table_sha256'], source_cell_hashes=row['current_source_cell_hashes'], source_template_hashes=row['current_source_template_hashes']),
        })
    return entities


if __name__ == '__main__':
    result = generate()
    gallery.write_json(OUT / 'entities.json', result)
    print('Three native floor comparisons; ten actual source/story sites; no model changes')
