import copy,unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.emerald.parts_model import build
from tools.shape_fit.emerald_pass.generic_architecture_templates import BASELINE,BUNDLE,read,generate,OWNED

class EmeraldGenericArchitectureTemplatesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(BUNDLE);cls.baseline=read(BASELINE);cls.original=copy.deepcopy(cls.baseline)
        cls.templates,cls.added,cls.ledger,cls.groups=generate(cls.art,cls.baseline)

    def test_all_original_source_occurrences_have_complete_pattern_coverage(self):
        self.assertEqual(self.baseline,self.original);self.assertEqual(len(self.templates),14)
        self.assertEqual({r['source_ordinal'] for r in self.ledger},OWNED)
        self.assertEqual(sum(len(g['occurrences']) for g in self.groups),24)

    def test_warp_openings_and_counter_height_are_preserved(self):
        for t,g in zip(self.templates,self.groups):
            o,_=build(t,np.asarray(self.art.drawing(g['layout'],'BAKED').convert('RGB')))
            if 'stair' in g['family']:
                for x,k in enumerate(g['source_kinds'][1]):
                    if k==114:self.assertFalse(o[:16,16:,x*16:(x+1)*16].any())
            if g['family']=='broken_wall':self.assertFalse(o[:16,24:,20:28].any())
            if g['family']=='shop_counter':
                self.assertEqual(t['height'],8);self.assertEqual(t['terrain'],[[0,0],[1,0],[2,0]])
                self.assertFalse(o[:,:16].any());self.assertTrue(o[0].any())

    def test_trunk_is_vertical_and_dividers_are_floor_depth(self):
        for t,g in zip(self.templates,self.groups):
            o,_=build(t,np.asarray(self.art.drawing(g['layout'],'BAKED').convert('RGB')))
            if g['family']=='indoor_trunk':
                self.assertEqual(o.shape,(64,64,32));self.assertFalse(o[:,:48].any());self.assertTrue(o[:,52:60,12:20].all())
            if g['family']=='divider':
                self.assertEqual(t['height'],16);self.assertTrue(o[:,2:-4,4:12].all())

if __name__=='__main__':unittest.main()
