import unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass.floating_bridge_repairs import OUT,BASELINE,BUNDLE,KEYS,WOOD,read,generate,assemblies
from tools.shape_fit.emerald_pass.review import GEOMETRY

class EmeraldFloatingBridgeRepairsTest(unittest.TestCase):
    def test_source_gaps_stay_empty_and_native_walk_metadata_is_unchanged(self):
        art=EmeraldArt(BUNDLE);pre=read(OUT/'preconditions.json');updates,ledger=generate(art,read(BASELINE),pre)
        for k,v in updates.items():
            o,p=decode(v);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB')).reshape(-1,3)
            self.assertFalse(o[4:].any());self.assertTrue(np.array_equal(o[0],o[3]));self.assertFalse(any(v.get(f) for f in ('on','walk','seat')))
            self.assertEqual({f:x for f,x in v.items() if f not in GEOMETRY},{f:x for f,x in pre['cells'][k].items() if f not in GEOMETRY})
            if k in KEYS[:2]:self.assertFalse(o[:,6:11,:].any())
            else:self.assertFalse(o[:,:,6:10].any())
            self.assertTrue(set(map(tuple,rgb[p[o]]))<=set(WOOD))
        seen=set()
        for e in assemblies(art):
            for loc in e['occurrences']:
                grid=art.map_cells(art.maps[loc['map']])
                for dz,row in enumerate(e['layout']):
                    for dx,key in enumerate(row):
                        if key:
                            self.assertEqual(key,grid[loc['y']+dz][loc['x']+dx]);seen.add((loc['map'],loc['x']+dx,loc['y']+dz,key))
        self.assertEqual(seen,{(p['map'],p['x'],p['y'],r['key']) for r in ledger for p in r['occurrences']});self.assertEqual(len(seen),38)

if __name__=='__main__':unittest.main()
