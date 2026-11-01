import unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass.generic_cabinets import (
    BASELINE,BUNDLE,read,rows,generate,assemblies,occurrences,SMALL,WALK,OWNED,PATTERNS,
)


class EmeraldGenericCabinetsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(BUNDLE);cls.baseline=read(BASELINE);cls.rows=rows()
        cls.at={r['source_ordinal']:r['key'] for r in cls.rows}
        cls.updates,cls.ledger,cls.meta=generate(cls.baseline,cls.art,cls.rows)
        cls.entities=assemblies(cls.art,cls.rows,cls.baseline)

    def test_exact_metadata_only_for_documented_projection_and_false_links(self):
        self.assertEqual(len(self.updates),48);self.assertEqual(len(self.meta),34)
        for n in OWNED:
            key=self.at[n];old=self.baseline['cells'][key];new=self.updates[key]
            changes={f for f in old.keys()|new.keys() if f not in ('v','t','up','from') and old.get(f)!=new.get(f)}
            self.assertEqual(changes,({'walk'} if n in WALK else set())|({'on'} if n in SMALL else set()))
            kinds={p['collision'] for p in occurrences(self.art,key)}
            self.assertEqual(kinds,{0} if n in WALK else {7})
        self.assertFalse(self.updates[self.at[268]].get('walk'))
        self.assertFalse(self.updates[self.at[269]].get('walk'))

    def test_all_nine_old_links_target_exact_walk_fronts(self):
        for upper,lower in SMALL.items():
            a,b=self.at[upper],self.at[lower]
            self.assertEqual(self.baseline['cells'][a]['on'],b)
            self.assertNotIn('on',self.updates[a]);self.assertIn(lower,WALK)
            for p in self.art.locations[a]:
                g=self.art.map_cells(self.art.maps[p['map']]);self.assertEqual(g[p['y']+1][p['x']],b)

    def test_cabinet_interfaces_connect_and_actual_source_coverage_is_complete(self):
        coverage={self.at[n]:set() for n in OWNED}
        for e in self.entities:
            for z,line in enumerate(e['layout']):
                for x,k in enumerate(line):
                    coverage[k]|={(p['map'],p['x']+x,p['y']+z) for p in e['occurrences']}
                    a=decode(self.updates[k])[0]
                    if x+1<len(line):
                        b=decode(self.updates[line[x+1]])[0];self.assertTrue(np.any(a[:,:,-1]&b[:,:,0]))
                    if z+1<len(e['layout']):
                        b=decode(self.updates[e['layout'][z+1][x]])[0];self.assertTrue(np.any(a[:,-1,:]&b[:,0,:]))
        for k,places in coverage.items():self.assertEqual(places,{(p['map'],p['x'],p['y']) for p in self.art.locations[k]})

    def test_baseline_does_not_invent_walk_fronts_or_suppress_fortree_solid_lower_row(self):
        walkkeys={self.at[n] for n in WALK}
        for e in self.entities:
            for z,line in enumerate(e['layout']):
                for x,k in enumerate(line):
                    self.assertEqual(e['baseline_layout'][z][x],None if k in walkkeys else k)
                    self.assertEqual(any(p['source']==[x,z] for p in e['baseline_placements']),k not in walkkeys)


if __name__=='__main__':unittest.main()
