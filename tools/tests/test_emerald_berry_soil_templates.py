import copy
import json
import unittest
from unittest.mock import patch
import numpy as np
from tools import model_gallery
from tools.shape_fit.emerald import parts_model
from tools.shape_fit.emerald_pass import berry_soil_templates as subject


@unittest.skipUnless(subject.PROOF.exists(), 'requires authorized local Emerald proof and source art')
class BerrySoilTemplateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art = model_gallery.EmeraldArt(subject.review.ROOT/'local/minecraft/kantobridge/export/emerald')
        cls.models, cls.evidence = subject.generate(cls.art)

    def test_exact_source_masks_keep_only_shallow_native_soil_on_berry_bodies(self):
        self.assertEqual(11, len(self.models))
        self.assertEqual(22, sum(len(r['occurrences']) for r in self.evidence))
        self.assertEqual(67, sum(len(o['berry_objects']) for r in self.evidence for o in r['occurrences']))
        for row in self.evidence:
            model = self.models[row['id']]
            source = np.asarray(self.art.drawing(row['layout'],'BAKED').convert('RGB'))
            occupied, colors = parts_model.build(model, source)
            self.assertFalse(occupied[4:].any())
            self.assertTrue(occupied[:4].any())
            self.assertTrue(all(model[k] is True for k in ('preserveTerrain','preserveCarriers','berrySoil')))
            owned = {tuple(p) for p in row['occupied_cells']}
            for z,line in enumerate(row['layout']):
                for x,_ in enumerate(line):
                    self.assertEqual((x,z) in owned, occupied[:,z*16:(z+1)*16,x*16:(x+1)*16].any())
            # No grass, water, or source-shadow color becomes soil material.
            soil = {(132,90,90),(156,115,115),(189,148,140)}
            self.assertLessEqual({tuple(c) for c in colors[occupied].tolist()}, soil)

    def test_changed_object_identity_or_source_key_is_refused(self):
        row = json.loads(subject.PROOF.read_text())['occurrences'][0]
        altered = copy.deepcopy(row)
        altered['scene_objects'][0]['hidden'] = True
        with self.assertRaisesRegex(ValueError, 'identity'):
            subject.berry_positions(altered)
        altered = copy.deepcopy(row)
        obj = altered['scene_objects'][0]
        altered['layout'][obj['y']-altered['y']][obj['x']-altered['x']] = 'g3q/1-2-3-4'
        with self.assertRaisesRegex(ValueError, 'source key'):
            subject.berry_positions(altered)

    def test_uninspected_source_pattern_occurrence_is_refused(self):
        original = model_gallery.building_catalog
        def extra(*args, **kwargs):
            result = original(*args, **kwargs)
            result[0]['occurrences'].append({'map':'UNREVIEWED','x':0,'y':0})
            return result
        with patch.object(model_gallery, 'building_catalog', side_effect=extra):
            with self.assertRaisesRegex(ValueError, 'uninspected occurrence'):
                subject.generate(self.art)


if __name__ == '__main__':
    unittest.main()
