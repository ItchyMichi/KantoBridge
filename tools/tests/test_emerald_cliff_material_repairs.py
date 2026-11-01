import unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass.cliff_material_repairs import BASELINE,BUNDLE,STUDY,OUT,read,generate,BROWN,CORNER_SIBLINGS
from tools.shape_fit.emerald_pass.review import GEOMETRY

class EmeraldCliffMaterialRepairsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(BUNDLE);cls.base=read(BASELINE);cls.pre=read(STUDY/'preconditions.json');cls.pre['cells'].update(read(OUT/'seam-preconditions.json')['cells']);cls.updates,_=generate(cls.art,cls.base,cls.pre)

    def test_existing_rock_mass_and_metadata_preserved(self):
        self.assertEqual(len(self.updates),24)
        for k,v in self.updates.items():
            o,p=decode(v);original=self.pre['cells'][CORNER_SIBLINGS.get(k,k)]
            np.testing.assert_array_equal(o,decode(original)[0])
            self.assertEqual({f:x for f,x in v.items() if f not in GEOMETRY},{f:x for f,x in self.pre['cells'][k].items() if f not in GEOMETRY})

    def test_only_native_rock_texels_cover_occupied_voxels(self):
        for k,v in self.updates.items():
            o,p=decode(v);a=np.asarray(self.art.cell(k,'BAKED').convert('RGB')).reshape(-1,3)
            self.assertTrue(all(tuple(c) in BROWN for c in a[np.unique(p[o])]))
            before_o,before_p=decode(self.pre['cells'][k])
            if k not in CORNER_SIBLINGS:
                native=np.isin(before_p,[i for i,c in enumerate(a) if tuple(c) in BROWN])
                np.testing.assert_array_equal(a[p[native]],a[before_p[native]])

if __name__=='__main__':unittest.main()
