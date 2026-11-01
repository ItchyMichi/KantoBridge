import json
import numpy as np
import pytest
from tools import model_gallery as gallery
from tools.shape_fit.emerald import parts_model
from tools.shape_fit.emerald_pass import fortree_native_repairs as p,review

@pytest.fixture(scope='module')
def candidates():
    if not p.BASE_PROOF.exists():pytest.skip('authorized source export absent')
    art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald')
    models,evidence=p.generate(art);return art,models,evidence

def test_five_unique_complete_source_patterns_preserve_metadata_and_real_collision_footprints(candidates):
    art,models,evidence=candidates
    assert len(models)==len(evidence)==5 and sum(len(e['occurrences']) for e in evidence)==5
    for e in evidence:
        spec=models[e['id']];review.validate_building(e['id'],spec,spec)
        assert spec['preserveTerrain'] is spec['preserveCarriers'] is True
        assert e['proposed_entry_sha256']==p.digest(spec)
        assert {c['collision'] for v in e['occurrences'] for c in v['cells'] if not c['terrain_excluded']}=={7}

def test_every_visible_part_fits_original_native_carrier_and_all_excluded_cliffs_stay_empty(candidates):
    art,models,evidence=candidates;refs={r['id']:r for r in review.read(p.BASE_PROOF)['occurrences']}
    for e in evidence:
        spec=models[e['id']];occ,_=parts_model.build(spec,np.asarray(art.drawing(e['layout'],'BAKED').convert('RGB')))
        row=refs[e['native_reference_id']]
        for c in row['current_carriers']:
            x,z=c['x']*8,c['z']*8
            assert not occ[c['blocks']*8:,z:z+8,x:x+8].any(),(e['id'],c)
        for x,z in spec['terrain']:assert not occ[:,z*16:z*16+16,x*16:x*16+16].any()
        # Each physical tree/hedge has grounded occupied voxels, never a suspended sprite plane.
        assert occ[0].any()

def test_foliage_swatch_uses_only_native_green_clusters_without_cyan_shadow(candidates):
    art,models,evidence=candidates
    for e in evidence:
        rgb=np.asarray(art.drawing(e['layout'],'BAKED').convert('RGB')).astype(int)
        for part in models[e['id']]['parts']:
            if part['shape']=='cylinder':continue
            for area in part['paint'].values():
                x0,y0,x1,y1=area;pixels=rgb[y0:y1,x0:x1]
                assert np.all(pixels[:,:,1]>pixels[:,:,0]+12)
                assert np.all(pixels[:,:,2]*5<=pixels[:,:,1]*3)

def test_export_regression_covers_both_complete_maps():
    if not (p.OUT/'runtime-proof.json').exists():pytest.skip('runtime proof absent')
    runtime=review.read(p.OUT/'runtime-proof.json')
    assert runtime['full_map_quarters_checked']==38400
    assert [m['new_templates'] for m in runtime['maps']]==[3,2]
