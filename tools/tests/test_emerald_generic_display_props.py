import unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass.generic_display_props import BASELINE,BUNDLE,read,rows,generate,assemblies,POTS
from tools.shape_fit.emerald_pass.review import GEOMETRY

class EmeraldGenericDisplayPropsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(BUNDLE);cls.baseline=read(BASELINE);cls.rows=rows();cls.at={r['source_ordinal']:r['key'] for r in cls.rows}
        cls.updates,cls.ledger=generate(cls.baseline,cls.art,cls.rows);cls.entities=assemblies(cls.art,cls.rows)

    def test_open_jars_have_grounded_bellies_and_open_thick_rims(self):
        for n in POTS:
            o,_=decode(self.updates[self.at[n]])
            self.assertTrue(o[0].any());self.assertTrue(o[13].any());self.assertFalse(o[7:,6:10,6:10].any())
            self.assertGreater(o[6].sum(),o[0].sum())

    def test_rock_display_is_one_joining_stack_at_both_exact_occurrences(self):
        upper,lower=self.at[194],self.at[199];self.assertEqual(self.updates[upper]['on'],lower)
        a,b=decode(self.updates[upper])[0],decode(self.updates[lower])[0]
        self.assertTrue(np.any(a[0]&b[-1]));self.assertTrue(b[0].any())
        self.assertEqual(len(self.art.locations[upper]),2)
        for p in self.art.locations[upper]:self.assertEqual(self.art.map_cells(self.art.maps[p['map']])[p['y']+1][p['x']],lower)

    def test_all_metadata_and_source_occurrences_preserved(self):
        self.assertEqual(len(self.updates),7);self.assertEqual(len(self.entities),5)
        for k,new in self.updates.items():
            self.assertEqual({f:v for f,v in new.items() if f not in GEOMETRY},{f:v for f,v in self.baseline['cells'][k].items() if f not in GEOMETRY})

    def test_display_excludes_floor_strip_and_fridge_preserves_connected_solid_stack(self):
        o,p=decode(self.updates[self.at[194]])
        self.assertTrue((p[o]//16>=3).all())
        a,b=self.at[105],self.at[111]
        self.assertEqual(self.updates[a]['on'],b)
        self.assertTrue(np.any(decode(self.updates[a])[0][0]&decode(self.updates[b])[0][-1]))

if __name__=='__main__':unittest.main()
