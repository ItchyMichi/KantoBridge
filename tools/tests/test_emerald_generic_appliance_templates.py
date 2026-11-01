import copy,unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.emerald.parts_model import build
from tools.shape_fit.emerald_pass.generic_appliance_templates import BASELINE,BUNDLE,read,generate,OWNED

class EmeraldGenericAppliancesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(BUNDLE);cls.baseline=read(BASELINE);cls.original=copy.deepcopy(cls.baseline)
        cls.templates,cls.added,cls.ledger,cls.groups=generate(cls.art,cls.baseline)

    def test_exact_source_coverage_and_native_fallbacks_untouched(self):
        self.assertEqual(len(self.templates),14);self.assertEqual(len(self.ledger),29)
        self.assertEqual(sum(len(g['occurrences']) for g in self.groups),84);self.assertEqual(self.baseline,self.original)
        self.assertEqual({r['source_ordinal'] for r in self.ledger},OWNED)
        for r in self.ledger:
            self.assertEqual(r['decision'],'retained');self.assertTrue(r['whole_templates'])

    def test_geometry_uses_only_solid_row_with_exact_walk_exclusions(self):
        for t,g in zip(self.templates,self.groups):
            o,_=build(t,np.asarray(self.art.drawing(g['layout'],'BAKED').convert('RGB')))
            row=0 if g['family']=='freestanding_glazed' else 1
            self.assertEqual(t['terrain'],[[x,row] for x in range(len(g['layout'][0]))]);self.assertTrue(t['interior'])
            self.assertFalse(o[:,row*16:row*16+16].any());self.assertTrue(o[0].any())
            self.assertTrue(all(k==0 for k in g['source_kinds'][row]));self.assertTrue(all(k==7 for k in g['source_kinds'][1-row]))

    def test_kitchen_has_open_sink_and_connected_faucet(self):
        for t,g in zip(self.templates,self.groups):
            if g['family']!='kitchen':continue
            o,_=build(t,np.asarray(self.art.drawing(g['layout'],'BAKED').convert('RGB')))
            self.assertFalse(o[14:16,6:12,3:13].any());self.assertTrue(o[12,6:12,3:13].all())
            self.assertTrue(o[16:23,4:6,7:9].all());self.assertTrue(np.any(o[15]&o[16]))

if __name__=='__main__':unittest.main()
