import unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.emerald.parts_model import build
from tools.shape_fit.emerald_pass.interior_stair_repairs import BUNDLE,generate

class EmeraldInteriorStairRepairsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(BUNDLE);cls.updates,cls.ledger,cls.groups,cls.old=generate(cls.art)

    def test_only_parts_change_with_supported_shallow_treads(self):
        for g in self.groups:
            t=self.updates[g['id']];old=self.old[g['id']]
            self.assertEqual({k:v for k,v in t.items() if k!='parts'},{k:v for k,v in old.items() if k!='parts'})
            self.assertEqual(t['parts'][:-4],old['parts'])
            o,_=build(t,np.asarray(self.art.drawing(g['layout'],'BAKED').convert('RGB')))
            for z,h in ((20,2),(16,4),(12,6),(8,8)):
                self.assertTrue(o[:h,z:z+4,16:32].all())
            self.assertFalse(o[:16,24:32,16:32].any())
            if o.shape[2]==80:self.assertFalse(o[:16,24:32,48:64].any())

    def test_exact_five_source_occurrences_keep_warp_and_ceiling(self):
        self.assertEqual(sum(len(g['occurrences']) for g in self.groups),5)
        for g in self.groups:
            for p in g['occurrences']:
                grid=self.art.map_cells(self.art.maps[p['map']]);x,y=p['x'],p['y']
                self.assertEqual([r[x:x+len(g['layout'][0])] for r in grid[y:y+2]],g['layout'])
            self.assertEqual(g['source_kinds'][1][1],114);self.assertEqual(self.updates[g['id']]['height'],32)

if __name__=='__main__':unittest.main()
