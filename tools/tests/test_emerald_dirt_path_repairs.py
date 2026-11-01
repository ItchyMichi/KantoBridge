import unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass.dirt_path_repairs import OUT,BASELINE,BUNDLE,MIDDLE,BODY,CAPS,KEYS,read,generate,assemblies
from tools.shape_fit.emerald_pass.review import GEOMETRY

class EmeraldDirtPathRepairsTest(unittest.TestCase):
    def test_flat_surface_native_top_and_unchanged_walk_route(self):
        art=EmeraldArt(BUNDLE);pre=read(OUT/'preconditions.json');updates,ledger,rr=generate(art,read(BASELINE),pre)
        for k,v in updates.items():
            o,p=decode(v);self.assertTrue(o[:4].all());self.assertFalse(o[4:].any());self.assertTrue(np.array_equal(p[3],np.arange(256,dtype=np.uint8).reshape(16,16)))
            self.assertEqual({f:x for f,x in v.items() if f not in GEOMETRY},{f:x for f,x in pre['cells'][k].items() if f not in GEOMETRY})
            self.assertFalse(any(v.get(f) for f in ('walk','seat','on')))
        self.assertEqual(sum(len(e['occurrences']) for e in assemblies(art)),18)
        self.assertEqual(set(updates),set(KEYS))
        self.assertTrue(all(p['collision']==(7 if r['key'] in (MIDDLE,BODY,CAPS[2],CAPS[3]) else 0) for r in rr for p in r['occurrences']))
        self.assertEqual(sum('berry_object' in p for r in rr for p in r['occurrences']),61)

if __name__=='__main__':unittest.main()
