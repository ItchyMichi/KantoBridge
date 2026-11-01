import unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass.cave_crystal_repairs import BASELINE,BUNDLE,STUDY,read,rows,generate
from tools.shape_fit.emerald_pass.review import GEOMETRY

class EmeraldCaveCrystalRepairsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(BUNDLE);cls.base=read(BASELINE);cls.pre=read(STUDY/'preconditions.json');cls.updates,_=generate(cls.art,cls.base,cls.pre)

    def test_native_metadata_and_connected_grounded_bodies(self):
        for k,v in self.updates.items():
            o,_=decode(v);self.assertTrue(o[0].any());todo=[tuple(np.argwhere(o)[0])];seen=set()
            while todo:
                p=todo.pop()
                if p in seen or not all(0<=p[d]<o.shape[d] for d in range(3)) or not o[p]:continue
                seen.add(p)
                for d in range(3):
                    for s in (-1,1):q=list(p);q[d]+=s;todo.append(tuple(q))
            self.assertEqual(len(seen),int(o.sum()))
            self.assertEqual({f:x for f,x in v.items() if f not in GEOMETRY},{f:x for f,x in self.pre['cells'][k].items() if f not in GEOMETRY})

    def test_crystals_have_continuous_stone_and_boulder_has_free_air(self):
        for r in rows():
            o,_=decode(self.updates[r['key']])
            if r['repair_ordinal']==52:
                self.assertLess(int(o.sum()),1800);self.assertFalse(o[15].any())
            else:self.assertTrue(o[:,:10].all());self.assertTrue(o[:,12:].any())

if __name__=='__main__':unittest.main()
