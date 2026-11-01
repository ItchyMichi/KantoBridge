import unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import soil_footprint_repairs as s
from tools.shape_fit.emerald_pass.review import GEOMETRY

class EmeraldSoilFootprintRepairsTest(unittest.TestCase):
    def test_soil_height_material_and_whole_bed_connections(self):
        art=EmeraldArt(s.BUNDLE);pre=s.read(s.OUT/'preconditions.json');pre['cells'].update(s.read(s.OUT/'prior-cap-preconditions.json')['cells'])
        updates,ledger=s.generate(art,s.read(s.BASELINE),pre);self.assertEqual(set(updates),set(s.KEYS));self.assertNotIn(s.prior.BODY,updates)
        for k,v in updates.items():
            o,p=decode(v);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB')).reshape(-1,3)
            self.assertFalse(o[4:].any());self.assertTrue(np.array_equal(o[0],o[3]));self.assertFalse(any(v.get(f) for f in ('on','walk','seat')))
            self.assertEqual({f:x for f,x in v.items() if f not in GEOMETRY},{f:x for f,x in pre['cells'][k].items() if f not in GEOMETRY})
            self.assertTrue(set(map(tuple,rgb[p[o]]))<=set(s.SOIL))
        self.assertFalse(decode(updates[s.N])[0][:,:10].any())
        body=next(r for r in s.rows() if r['key']==s.S)
        self.assertTrue(all(p['collision']==7 and any(o['kind']=='berry' for o in p['objects']) for p in body['occurrences']))
        complete=[e for e in s.assemblies(art) if all(k in s.KEYS for row in e['reference_layout'] for k in row)]
        self.assertEqual(len(complete),2)
        for e in complete:
            footprint=np.zeros((len(e['layout'])*16,len(e['layout'][0])*16),bool)
            for z,row in enumerate(e['layout']):
                for x,k in enumerate(row):footprint[z*16:(z+1)*16,x*16:(x+1)*16]=decode(updates[k])[0][0]
            remaining=set(map(tuple,np.argwhere(footprint)));todo=[next(iter(remaining))];seen=set()
            while todo:
                p=todo.pop()
                if p not in remaining:continue
                remaining.remove(p);seen.add(p);z,x=p;todo.extend(((z-1,x),(z+1,x),(z,x-1),(z,x+1)))
            self.assertFalse(remaining)

if __name__=='__main__':unittest.main()
