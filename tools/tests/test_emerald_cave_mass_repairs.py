import unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass.cave_mass_repairs import BASELINE,BUNDLE,OUT,read,rows,generate,assemblies
from tools.shape_fit.emerald_pass.review import GEOMETRY

class EmeraldCaveMassRepairsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(BUNDLE);cls.base=read(BASELINE);cls.pre=read(OUT/'preconditions.json');cls.rows=rows(cls.art);cls.updates,_=generate(cls.art,cls.base,cls.pre,cls.rows)

    def test_stone_body_has_unbroken_ground_support_and_no_internal_gaps(self):
        for k,v in self.updates.items():
            o,p=decode(v);self.assertTrue(o[:,2:14,2:14].all())
            np.testing.assert_array_equal(o,np.broadcast_to(o[0],o.shape))
            self.assertEqual({f:x for f,x in v.items() if f not in GEOMETRY},{f:x for f,x in self.pre['cells'][k].items() if f not in GEOMETRY})

    def test_inscriptions_remain_native_and_join_end_caps(self):
        for e in assemblies(self.art):
            g=e['layout']
            for z,row in enumerate(g):
                for x,k in enumerate(row):
                    a=decode(self.updates.get(k,self.base['cells'][k]))[0]
                    if x+1<len(row):
                        b=decode(self.updates.get(row[x+1],self.base['cells'][row[x+1]]))[0]
                        self.assertTrue(np.any(a[:,:,-1]&b[:,:,0]))
            for k in g[-1][1:-1]:
                o,p=decode(self.updates[k]);yy,xx=np.indices((16,16))
                np.testing.assert_array_equal(p[1:15,15,1:15],((15-yy)*16+xx)[1:15,1:15])

if __name__=='__main__':unittest.main()
