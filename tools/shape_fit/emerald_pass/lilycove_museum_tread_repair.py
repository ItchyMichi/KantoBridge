"""Put the Museum2F descending tread art on its existing walkable floor.

The narrow repair keeps the original rear portal occupancy and interaction
metadata. A one-voxel horizontal facing carries the source's four tread bands;
it is decorative, not a new staircase collider or a replacement native floor.
"""
from __future__ import annotations
import copy
import hashlib
import json
import numpy as np
from tools import model_gallery as gallery
from tools.shape_fit.blue_pass.common import decode, encode, render
from tools.shape_fit.emerald_pass.towns import ROOT, BUNDLE, BASELINE, digest
from tools.shape_fit.emerald_pass.towns_lilycove import preview
from tools.shape_fit.emerald_pass.qa10_wholes import canonical_hash

OUT = ROOT / 'local/model-gallery/emerald-pass-study/lilycove-museum-tread-repair'
KEY = 'g3q/3656-3656-6946-6947'
IDENT = 'emerald_lilycove_museum_stairs2'


def generate(base=None):
    base = base or json.loads(BASELINE.read_text())
    old = base['cells'][KEY]
    occ, paint = decode(old)
    # The original thin rear portal wall remains in place. Its stair pixels
    # were wrongly wrapped onto vertical edges; its native dark recess is the
    # appropriate material there.
    paint[occ] = 0
    # A flush-looking, noncolliding facing covers only the existing warp cell.
    # Source rows8,10,12..13,15 are its four orange tread bands; intervening
    # native dark rows show the descending recess. No source silhouette drives
    # the occupancy and nothing is added above one voxel in the passage.
    occ[0, 3:16, :] = True
    zz, xx = np.indices((13, 16))
    paint[0, 3:16, :] = (zz + 3) * 16 + xx
    return {KEY: encode(occ, paint, old, 'hand-emerald-museum-descending-tread-facing')}


def native_entities():
    runtime_path = OUT / 'runtime-proof.json'
    runtime = json.loads(runtime_path.read_text())
    assert hashlib.sha256((OUT / 'updates.json').read_bytes()).hexdigest() == runtime['candidate_updates_sha256']
    snapshot = OUT / 'fitted-proof-current.json'
    assert hashlib.sha256(snapshot.read_bytes()).hexdigest() == runtime['current_table_sha256']
    table = json.loads(snapshot.read_text())
    assert table['cells'][KEY] == generate()[KEY]
    original = json.loads((OUT / 'original-entities.json').read_text())[0]
    assert original['id'] == IDENT
    art = gallery.EmeraldArt(BUNDLE)
    row = copy.deepcopy(runtime['occurrences'][0])
    assert row['layout'] == original['layout']
    common = {'occurrence': {k: row[k] for k in ('map', 'x', 'y')}, 'source_layout': row['layout']}
    row['current_source_cell_hashes'] = {k: canonical_hash(table['cells'].get(k)) for line in row['layout'] for k in line}
    row['current_source_template_hashes'] = gallery.native_source_template_hashes(table, {'descriptor': common, 'record': row}, art)
    proof = {k: runtime[k] for k in ('original_baseline_sha256', 'current_table_sha256')}
    proof.update(parent_runtime_evidence={'path': runtime_path.relative_to(ROOT).as_posix(), 'sha256': hashlib.sha256(runtime_path.read_bytes()).hexdigest()}, occurrences=[row])
    path = OUT / 'native-proof.json'
    gallery.write_json(path, proof)
    common.update(evidence=path.relative_to(ROOT).as_posix(), evidence_sha256=hashlib.sha256(path.read_bytes()).hexdigest())
    entity = copy.deepcopy(original)
    entity['baseline_native_fragments'] = dict(common, baseline_sha256=runtime['original_baseline_sha256'])
    entity['current_native_fragments'] = dict(common, current_table_sha256=runtime['current_table_sha256'], source_cell_hashes=row['current_source_cell_hashes'], source_template_hashes=row['current_source_template_hashes'])
    entity['note'] = 'Museum2F descending stair, exact source-native original/current columns. The current orange tread facing lies across the existing WALK114 floor, with the original rear portal occupancy retained. Collision, native gray floor and warp destination are unchanged. Previous failed captures remain preserved; this corrected appearance awaits Minecraft comparison.'
    return [entity], {IDENT: original}


def main():
    base = json.loads(BASELINE.read_text())
    live_path = ROOT / 'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json'
    live = json.loads(live_path.read_text())
    assert live['cells'][KEY] == base['cells'][KEY], 'Reconcile any later stair edits before applying this repair'
    art = gallery.EmeraldArt(BUNDLE)
    patch = generate(base)
    sprite = art.cell(KEY, 'BAKED')
    location = art.locations[KEY]
    assert location == [{'map': 'EM_LILYCOVE_CITY_LILYCOVE_MUSEUM_2F', 'x': 13, 'y': 1, 'palette': 'BAKED'}]
    original = next(e for e in json.loads((ROOT / 'tools/shape_fit/entities/emerald.json').read_text()) if e['id'] == IDENT)
    gallery.write_json(OUT / 'original-entities.json', [original])
    gallery.write_json(OUT / 'updates.json', {'cells': patch, 'buildings': {}})
    gallery.write_json(OUT / 'ledger.json', [{
        'key': KEY, 'kind': 'cell', 'decision': 'changed',
        'label': 'Museum descending orange tread floor facing', 'family': 'museum-descending-warp',
        'baseline_entry_sha256': digest(base['cells'][KEY]),
        'reason': 'Both the fitted comparison and the subsequent actual-native-floor comparison omit readable orange treads. The source has four orange horizontal bands within this WALK114 warp. Preserve the original rear-wall occupancy and walk metadata, clean accidental stair pixels from vertical wall edges, and carry those exact native bands across a one-voxel decorative horizontal facing. Source floor, zero collision and warp destination remain unchanged.',
        'occurrences': location,
        'evidence': [{'source_pixel_sha256': hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest(), 'source_pixel_mode': 'RGB', 'map': location[0]['map'], 'x': 13, 'y': 1, 'collision': 114, 'warp_target': 'EM_LILYCOVE_CITY_LILYCOVE_MUSEUM_1F'}, {'failed_native_capture': 'local/model-gallery/emerald-batch-02/museum-native01', 'prior_native_proof': 'local/model-gallery/emerald-pass-study/lilycove-museum-stairs-native/runtime-proof.json'}],
        'visual_status': 'Local authoring preview; actual source route and Minecraft recapture pending',
    }])
    for label, entry in [('before', base['cells'][KEY]), ('after', patch[KEY])]:
        render(entry, sprite, OUT / label)
    # The source-sized whole preview is authoring evidence. The separate native
    # descriptor is refreshed from the Java proof before a Minecraft comparison.
    plain = {k: v for k, v in original.items() if k not in ('baseline_native_fragments', 'current_native_fragments')}
    (OUT / 'whole').mkdir(parents=True, exist_ok=True)
    preview(plain, art, live['cells'], OUT / 'whole', 'before')
    preview(plain, art, {**live['cells'], **patch}, OUT / 'whole', 'after')
    gallery.write_json(OUT / 'preview-provenance.json', {'table_sha256': hashlib.sha256(live_path.read_bytes()).hexdigest(), 'source_pixel_sha256': hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest(), 'metadata_unchanged': True, 'minecraft_verified': False})
    if (OUT / 'runtime-proof.json').exists():
        entities, preconditions = native_entities()
        gallery.write_json(OUT / 'entities.json', entities)
        gallery.write_json(OUT / 'preconditions.json', preconditions)
    print('One exact Museum2F warp tread facing; no metadata or source floor changes')


if __name__ == '__main__':
    main()
