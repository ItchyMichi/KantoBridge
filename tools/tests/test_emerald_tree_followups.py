import unittest
import numpy as np

from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald.littleroot_tree import KEYS, generate as broadleaf
from tools.shape_fit.emerald_pass.tree_followups import (
    BASELINE,BUNDLE,OUT,PARTIAL,UPPERS,BASES,generate,assemblies,read,LEAVES,precondition_table,
)


class EmeraldTreeFollowupsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.baseline=read(BASELINE);cls.live=precondition_table();cls.art=EmeraldArt(BUNDLE)
        cls.updates,cls.ledger,cls.metadata,cls.witnesses=generate(cls.baseline,cls.live,cls.art)

    def test_frozen_before_reproduces_integrated_patch_without_live_input(self):
        self.assertEqual(self.updates,read(OUT/'updates.json')['cells'])
        self.assertEqual(self.metadata,read(OUT/'metadata-evidence.json'))
        self.assertEqual(self.updates,generate(self.baseline,precondition_table(),self.art)[0])

    def test_occluded_canopy_preserves_exact_native_leaf_volume(self):
        for key,mid in PARTIAL.items():
            actual,paint=decode(self.updates[key]);ref,rpaint=decode(broadleaf()[KEYS[mid]])
            palette=np.asarray(self.art.cell(KEYS[mid],'BAKED').convert('RGB'))
            colors=palette[rpaint//16,rpaint%16]
            leaves=np.any(np.all(colors[...,None,:]==np.asarray(LEAVES),axis=-1),axis=-1)
            np.testing.assert_array_equal(actual,ref&leaves)
            pixels=np.asarray(self.art.cell(key,'BAKED').convert('RGB'))
            self.assertTrue(all(tuple(c) in LEAVES for c in pixels[paint//16,paint%16][actual]))
            meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
            self.assertEqual(meta(self.updates[key]),meta(self.baseline['cells'][key]))

    def test_aliases_preserve_geometry_primary_links_and_old_aliases(self):
        for key in UPPERS:
            old,new=self.live['cells'][key],self.updates[key]
            self.assertEqual({k:v for k,v in old.items() if k not in ('alsoOn','stackFit')},
                             {k:v for k,v in new.items() if k not in ('alsoOn','stackFit')})
            self.assertIs(new['stackFit'],True)
            self.assertEqual(set(new['alsoOn'])-set(old.get('alsoOn',[])),set(BASES))
            self.assertTrue(all(p['base_collision']==7 for p in self.witnesses if p['upper_key']==key))

    def test_actual_source_pairs_and_provisional_catalog_placements(self):
        self.assertEqual(len(self.witnesses),19)
        self.assertEqual(sum(p['upper_collision']==0 for p in self.witnesses),4)
        objects=assemblies(self.art,self.witnesses)[:9]
        self.assertEqual(len(objects),9)
        for item in objects:
            key=item['layout'][0][0]
            self.assertEqual(item['placements'][0]['offset'],[0,1,0])
            if key==UPPERS[2]:
                self.assertIsNone(item['baseline_layout'][0][0])
                self.assertEqual(len(item['baseline_placements']),1)
            else:
                self.assertEqual(item['baseline_placements'][0]['offset'],[0,0,0])


if __name__=='__main__':unittest.main()
