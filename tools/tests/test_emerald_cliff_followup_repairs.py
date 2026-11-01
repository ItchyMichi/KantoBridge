import unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass.review import read,GEOMETRY
from tools.shape_fit.emerald_pass import mixed_cave_corner_repair as mixed,rusturf_plateau_repairs as rusturf

class EmeraldCliffFollowupRepairsTest(unittest.TestCase):
    def check_mass(self,updates,pre):
        for k,v in updates.items():
            o,p=decode(v);self.assertEqual(o.shape[0],16);self.assertTrue(np.array_equal(o[0],o[15]));self.assertTrue(np.all(o==o[0]))
            self.assertGreater(int(o.sum()),3900)
            self.assertEqual({f:x for f,x in v.items() if f not in GEOMETRY},{f:x for f,x in pre['cells'][k].items() if f not in GEOMETRY})
    def test_mixed_cliff_is_grounded_without_enabling_walk_obstacles(self):
        art=EmeraldArt(mixed.BUNDLE);pre=read(mixed.OUT/'preconditions.json');updates,ledger,rr=mixed.generate(art,read(mixed.BASELINE),pre);self.check_mass(updates,pre)
        self.assertEqual(sorted(p['collision'] for p in ledger[0]['occurrences']),[0,7,7,7])
        self.assertTrue(all(p['collision']==7 for p in ledger[1]['occurrences']))
        self.assertFalse(any(updates[mixed.KEY].get(f) for f in ('walk','seat','on')))
    def test_rusturf_only_solid_cells_get_continuous_masses(self):
        art=EmeraldArt(rusturf.BUNDLE);pre=read(rusturf.OUT/'preconditions.json');updates,ledger=rusturf.generate(art,read(rusturf.BASELINE),pre);self.check_mass(updates,pre)
        self.assertEqual(len(updates),5);self.assertNotIn(rusturf.LEDGE,updates)
        self.assertTrue(all(p['collision']==7 for r in ledger for p in r['occurrences']))

if __name__=='__main__':unittest.main()
