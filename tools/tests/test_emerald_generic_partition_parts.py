import unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass.generic_partition_parts import BASELINE,BUNDLE,read,rows,generate,assemblies,STACKS,FLOORS,RETAIN
from tools.shape_fit.emerald_pass.review import GEOMETRY

class EmeraldGenericPartitionPartsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(BUNDLE);cls.baseline=read(BASELINE);cls.rows=rows();cls.at={r['source_ordinal']:r['key'] for r in cls.rows}
        cls.updates,cls.ledger=generate(cls.baseline,cls.art,cls.rows);cls.entities=assemblies(cls.art,cls.rows)

    def test_existing_source_stacks_keep_connected_full_height(self):
        for top,base in STACKS.items():
            a,b=self.at[top],self.at[base]
            self.assertEqual(self.updates[a]['on'],b)
            self.assertTrue(np.any(decode(self.updates[a])[0][0]&decode(self.updates[b])[0][-1]))
            for p in self.art.locations[a]:self.assertEqual(self.art.map_cells(self.art.maps[p['map']])[p['y']+1][p['x']],b)
        self.assertEqual(sum(len(e['occurrences']) for e in self.entities[:2]),30)

    def test_floor_and_counter_source_routes_are_not_enabled_as_new_units(self):
        self.assertEqual(len(self.updates),6);self.assertEqual(len(self.ledger),8)
        for k,v in self.updates.items():self.assertEqual({f:x for f,x in v.items() if f not in GEOMETRY},{f:x for f,x in self.baseline['cells'][k].items() if f not in GEOMETRY})
        for n in FLOORS:
            self.assertFalse(any(self.updates[self.at[n]].get(f) for f in ('walk','seat','on')))
        for n in RETAIN:self.assertNotIn(self.at[n],self.updates)

if __name__=='__main__':unittest.main()
