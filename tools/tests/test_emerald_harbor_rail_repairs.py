import unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass.harbor_rail_repairs import BASELINE,BUNDLE,STUDY,read,rows,generate,assemblies
from tools.shape_fit.emerald_pass.review import GEOMETRY

class EmeraldHarborRailRepairsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(BUNDLE);cls.base=read(BASELINE);cls.pre=read(STUDY/'preconditions.json');cls.updates,cls.ledger=generate(cls.art,cls.base,cls.pre);cls.entities=assemblies(cls.art)

    def test_rails_are_open_grounded_and_keep_metadata(self):
        self.assertEqual(len(self.updates),7)
        for k,v in self.updates.items():
            o,_=decode(v)
            self.assertLess(int(o.sum()),700)
            self.assertTrue(o[0].any());self.assertFalse(o[13:].any())
            self.assertEqual({f:x for f,x in v.items() if f not in GEOMETRY},{f:x for f,x in self.pre['cells'][k].items() if f not in GEOMETRY})

    def test_each_complete_run_has_touching_interfaces(self):
        self.assertEqual(sum(len(e['occurrences']) for e in self.entities),6)
        for e in self.entities:
            g=e['layout']
            for z,row in enumerate(g):
                for x,k in enumerate(row):
                    if not k:continue
                    a=decode(self.updates[k])[0]
                    if x+1<len(row) and row[x+1]:
                        b=decode(self.updates[row[x+1]])[0];self.assertTrue(np.any(a[:,:,-1]&b[:,:,0]))
                    if z+1<len(g) and g[z+1][x]:
                        b=decode(self.updates[g[z+1][x]])[0];self.assertTrue(np.any(a[:,-1,:]&b[:,0,:]))
        covered={(p['map'],p['x']+x,p['y']+z) for e in self.entities for p in e['occurrences'] for z,row in enumerate(e['layout']) for x,k in enumerate(row) if k}
        expected={(p['map'],p['x'],p['y']) for k in self.updates for p in self.art.locations[k]}
        self.assertEqual(covered,expected)

if __name__=='__main__':unittest.main()
