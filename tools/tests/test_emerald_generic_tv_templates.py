import copy
import unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.emerald.parts_model import build
from tools.shape_fit.emerald_pass.generic_tv_templates import BASELINE,BUNDLE,read,generate,TV


class EmeraldGenericTelevisionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(BUNDLE);cls.baseline=read(BASELINE);cls.original=copy.deepcopy(cls.baseline)
        cls.templates,cls.added,cls.ledger,cls.groups=generate(cls.art,cls.baseline)

    def test_every_shared_tv_occurrence_has_one_exact_complete_pattern(self):
        self.assertEqual(len(self.templates),10)
        actual=[(p['map'],p['x'],p['y']) for g in self.groups for p in g['occurrences']]
        expected=[(p['map'],p['x'],p['y']) for p in self.art.locations[TV]]
        self.assertEqual(len(actual),43);self.assertEqual(len(actual),len(set(actual)));self.assertEqual(set(actual),set(expected))
        for g in self.groups:self.assertEqual(g['source_kinds'],[[7],[0]])

    def test_physical_geometry_is_grounded_in_northern_solid_source_cell(self):
        for t,g in zip(self.templates,self.groups):
            o,c=build(t,np.asarray(self.art.drawing(g['layout'],'BAKED').convert('RGB')))
            self.assertEqual(o.shape,(32,32,16));self.assertTrue(t['interior'])
            self.assertEqual(t['terrain'],[[0,1]])
            self.assertFalse(o[:,16:].any());self.assertTrue(o[0].any())
            self.assertTrue(o[15].any());self.assertTrue(o[16].any());self.assertTrue(o[17].any())
            self.assertTrue(np.any(o[15]&o[16]));self.assertTrue(np.any(o[16]&o[17]))

    def test_native_cell_entries_are_untouched_and_retained_projection_coverage_is_exact(self):
        self.assertEqual(self.baseline,self.original);self.assertEqual(len(self.ledger),5)
        for row in self.ledger:
            self.assertEqual(row['decision'],'retained')
            group=next(g for g in self.groups if g['id']==row['whole_template'])
            expected={(p['map'],p['x'],p['y']) for p in row['occurrences']}
            self.assertEqual({(p['map'],p['x'],p['y']+1) for p in group['occurrences']},expected)
        for row in self.added:
            self.assertEqual(row['decision'],'added');self.assertTrue(row['baseline_absent'])
            self.assertIsNone(row['baseline_entry_sha256']);self.assertEqual(row['source_pixel_mode'],'RGB')


if __name__=='__main__':unittest.main()
