import copy,unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.emerald.parts_model import build
from tools.shape_fit.emerald_pass.ladder_templates import BASELINE,BUNDLE,read,generate,FREE_UPPER

class EmeraldLadderTemplatesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(BUNDLE);cls.base=read(BASELINE);cls.old=copy.deepcopy(cls.base);cls.templates,cls.added,cls.ledger,cls.groups=generate(cls.art,cls.base)

    def test_exact_all_source_coverage_and_original_entries_unchanged(self):
        self.assertEqual(len(self.templates),8);self.assertEqual(len(self.ledger),14);self.assertEqual(sum(len(g['occurrences']) for g in self.groups),21)
        self.assertEqual(self.old,self.base)
        for g in self.groups:self.assertEqual(g['source_kinds'],[[7],[114]])

    def test_ladder_rails_rungs_and_foot_are_connected(self):
        for t,g in zip(self.templates,self.groups):
            o,_=build(t,np.asarray(self.art.drawing(g['layout'],'BAKED').convert('RGB')))
            self.assertTrue(o[0,20:22,3:5].all());self.assertTrue(o[0,20:22,11:13].all())
            for y in range(29):self.assertTrue(np.any(o[y]&o[y+1]))
            for y in range(3,29,4):
                z=20-2*y//5;self.assertTrue(o[y,z:z+2,3:13].all())
            self.assertFalse(o[:,24:].any())
            if g['layout'][0][0]==FREE_UPPER:self.assertFalse(o[:,:8].any())
            else:self.assertTrue(o[:24,:10].all())

if __name__=='__main__':unittest.main()
