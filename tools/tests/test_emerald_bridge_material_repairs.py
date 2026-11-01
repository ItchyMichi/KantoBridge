import unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass.bridge_material_repairs import OUT,STUDY,BASELINE,BUNDLE,WOOD,read,generate,assemblies
from tools.shape_fit.emerald_pass.review import GEOMETRY

class EmeraldBridgeMaterialRepairsTest(unittest.TestCase):
    def test_only_background_material_changes_and_complete_spans_cover_every_source(self):
        art=EmeraldArt(BUNDLE);pre=read(OUT/'preconditions.json');updates,ledger=generate(art,read(BASELINE),pre)
        self.assertEqual(len(updates),31)
        for k,v in updates.items():
            old=pre['cells'][k];o,p=decode(v);before,oldp=decode(old);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB')).reshape(-1,3)
            self.assertTrue(np.array_equal(o,before));self.assertEqual(v['v'],old['v'])
            self.assertEqual({f:x for f,x in v.items() if f not in GEOMETRY},{f:x for f,x in old.items() if f not in GEOMETRY})
            painted=rgb[p[o]];prior=rgb[oldp[o]];wood=np.isin(prior.dot(np.array([65536,256,1])),np.asarray(WOOD).dot(np.array([65536,256,1])))
            self.assertTrue(np.array_equal(painted[wood],prior[wood]))
            self.assertTrue(set(map(tuple,painted))<=set(WOOD))
        entities=assemblies(art);seen=set()
        for e in entities:
            self.assertEqual(len(e['layout']),2)
            for loc in e['occurrences']:
                grid=art.map_cells(art.maps[loc['map']]);x,z=loc['x'],loc['y']
                for dz,row in enumerate(e['layout']):
                    self.assertEqual(grid[z+dz][x:x+len(row)],row)
                    seen.update((loc['map'],x+dx,z+dz,key) for dx,key in enumerate(row))
        expected={(p['map'],p['x'],p['y'],r['key']) for r in ledger for p in r['occurrences']}
        self.assertEqual(seen,expected);self.assertEqual(len(entities),19)

if __name__=='__main__':unittest.main()
