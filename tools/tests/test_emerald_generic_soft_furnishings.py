import unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass.generic_soft_furnishings import (
    BASELINE,BUNDLE,read,rows,generate,assemblies,occurrences,WINDOWS,ADDED,WALK,BEDS,SOFAS,
)


class EmeraldGenericSoftFurnishingsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(BUNDLE);cls.baseline=read(BASELINE);cls.rows=rows()
        cls.at={r['source_ordinal']:r['key'] for r in cls.rows}
        cls.updates,cls.added,cls.ledger,cls.added_ledger,cls.meta=generate(cls.baseline,cls.art,cls.rows)
        cls.current=dict(cls.baseline,cells=cls.baseline['cells']|cls.updates|cls.added)
        cls.entities=assemblies(cls.art,cls.rows,cls.baseline,cls.current)

    def test_exact_source_metadata_and_missing_entry_identity(self):
        self.assertEqual(len(self.updates),32);self.assertEqual(set(self.added),set(ADDED.values()))
        self.assertEqual(len(self.meta),14)
        for r in self.rows:
            n=r['source_ordinal'];k=r['key'];new=self.current['cells'][k]
            if n in ADDED:
                self.assertNotIn(k,self.baseline['cells']);continue
            old=self.baseline['cells'][k]
            changed={f for f in old.keys()|new.keys() if f not in ('v','t','up','from') and old.get(f)!=new.get(f)}
            self.assertEqual(changed,({'walk'} if n in WALK else set())|({'on'} if n in (172,173) else set()))
        for n in WALK:self.assertEqual({p['collision'] for p in occurrences(self.art,self.at[n])},{0})
        for n in set(WINDOWS)|set(WINDOWS.values()):
            self.assertEqual({p['collision'] for p in occurrences(self.art,self.at[n])},{7})

    def test_every_window_pair_is_exact_and_has_touching_vertical_layers(self):
        for upper,lower in WINDOWS.items():
            a,b=self.at[upper],self.at[lower]
            self.assertEqual(self.current['cells'][a]['on'],b)
            for p in self.art.locations[a]:
                g=self.art.map_cells(self.art.maps[p['map']]);self.assertEqual(g[p['y']+1][p['x']],b)
            o1,_=decode(self.current['cells'][a]);o2,_=decode(self.current['cells'][b])
            self.assertTrue(np.any(o1[0]&o2[-1]))
        for row in self.added_ledger:
            self.assertEqual(row['decision'],'added');self.assertTrue(row['baseline_absent'])
            self.assertIsNone(row['baseline_entry_sha256']);self.assertEqual(row['source_pixel_mode'],'RGBA')

    def test_complete_floor_furniture_has_connected_seams_and_all_occurrences(self):
        for pattern in BEDS+SOFAS:
            for z,line in enumerate(pattern):
                for x,n in enumerate(line):
                    a=decode(self.current['cells'][self.at[n]])[0]
                    if x+1<len(line):
                        b=decode(self.current['cells'][self.at[line[x+1]]])[0]
                        self.assertTrue(np.any(a[:,:,-1]&b[:,:,0]),(n,line[x+1]))
                    if z+1<len(pattern):
                        b=decode(self.current['cells'][self.at[pattern[z+1][x]]])[0]
                        self.assertTrue(np.any(a[:,-1,:]&b[:,0,:]),(n,pattern[z+1][x]))
        for n in {n for a in BEDS+SOFAS for line in a for n in line}:
            key=self.at[n];actual=set()
            for e in self.entities:
                for z,line in enumerate(e['layout']):
                    for x,k in enumerate(line):
                        if k==key:
                            actual|={(p['map'],p['x']+x,p['y']+z) for p in e['occurrences']}
            self.assertEqual(actual,{(p['map'],p['x'],p['y']) for p in self.art.locations[key]})

    def test_baseline_walk_projections_are_omitted_without_hiding_missing_solids(self):
        for e in self.entities:
            self.assertNotIn('baseline_native_required',e)
            if 'baseline_native_fragments' in e:
                self.assertEqual(e['baseline_native_fragments']['source_layout'],e['layout'])
                self.assertIn(e['layout'][1][0],set(ADDED.values()))
                continue
            before={tuple(p['source']) for p in e['baseline_placements']}
            for z,line in enumerate(e['layout']):
                for x,k in enumerate(line):
                    if k in {self.at[n] for n in WALK}:
                        self.assertNotIn((x,z),before);self.assertIsNone(e['baseline_layout'][z][x])


if __name__=='__main__':unittest.main()
