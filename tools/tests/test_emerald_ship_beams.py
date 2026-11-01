import copy
import unittest
from collections import Counter

import numpy as np

from tools.model_gallery import EmeraldArt, baseline_entities, catalog
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import ship_beams as beams
from tools.shape_fit.emerald_pass.review import read


class EmeraldShipBeamsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art = EmeraldArt(beams.BUNDLE)
        cls.baseline = read(beams.BASELINE)
        cls.updates, cls.ledger = beams.generate(cls.baseline, cls.art)
        cls.entities, cls.groups = beams.assemblies(cls.art)

    def test_metadata_preserves_old_bases_and_only_adds_proven_middle_aliases(self):
        self.assertEqual(set(beams.KEYS), set(self.updates))
        for key, new in self.updates.items():
            old = self.baseline['cells'][key]
            self.assertEqual({k:v for k,v in old.items() if k not in ('v','t','up','from')},
                             {k:v for k,v in new.items() if k not in ('v','t','up','from','alsoOn')})
            self.assertEqual([beams.MIDDLE] if key != beams.BASE else None,new.get('alsoOn'))
        proof=beams.source_evidence(self.art,self.baseline,self.entities,self.groups)
        for amendment in proof['amendments'].values():
            self.assertTrue(amendment['alias_witnesses'])
            self.assertTrue(all(p['below_key']==beams.MIDDLE for p in amendment['alias_witnesses']))

    def test_every_source_occurrence_belongs_to_an_exact_whole_group(self):
        expected=Counter((k,p['map'],p['x'],p['y']) for k in beams.KEYS for p in self.art.locations[k])
        actual=Counter()
        for group in self.groups:
            for place in group['occurrences']:
                for y,line in enumerate(group['layout']):
                    for x,key in enumerate(line):
                        if key:actual[(key,place['map'],place['x']+x,place['y']+y)]+=1
        self.assertEqual(expected,actual)
        self.assertEqual(125,sum(actual.values()))

    def test_continuous_posts_grounded_foot_open_gap_and_native_cap(self):
        for key,entry in self.updates.items():
            occ,paint=decode(entry)
            self.assertEqual(16,len(occ))
            for x in (2,10):self.assertTrue(occ[:,5:11,x:x+4].all())
            self.assertFalse(occ[9:14,5:11,6:10].any())
            pixels=np.asarray(self.art.cell(key,'BAKED').convert('RGB'))
            color=pixels[paint[15,5,2]//16,paint[15,5,2]%16]
            if key==beams.TOP:self.assertGreater(int(color[2]),int(color[0]))
        foot,_=decode(self.updates[beams.BASE])
        self.assertTrue(foot[0].any())
        self.assertTrue(foot[15].any())
        for upper in (beams.TOP,beams.MIDDLE):
            occ,_=decode(self.updates[upper]);self.assertGreater(np.count_nonzero(occ[0]&foot[15]),0)

    def test_base_ends_each_column_and_walk_caps_have_honest_baseline(self):
        layout=[[beams.TOP],[beams.MIDDLE],[beams.BASE],[beams.MIDDLE],[beams.BASE]]
        p,missing,chains=beams.placements(layout,[[7]]*5,True)
        self.assertEqual([3,2],[len(c) for c in chains]);self.assertFalse(missing)
        self.assertEqual([0,2,2],next(q['offset'] for q in p if q['source']==[0,0]))
        old,missing,_=beams.placements([[beams.TOP],[beams.MIDDLE],[beams.MIDDLE]],[[0],[7],[7]],False)
        self.assertEqual([(0,0)],missing);self.assertEqual(2,len(old))
        new,missing,_=beams.placements([[beams.TOP],[beams.MIDDLE],[beams.MIDDLE]],[[0],[7],[7]],True)
        self.assertFalse(missing);self.assertEqual([0,2,2],new[0]['offset'])

    def test_headroom_and_gallery_before_current_identity(self):
        evidence=beams.source_evidence(self.art,self.baseline,self.entities,self.groups)
        self.assertTrue(all(r['tallest_authored_chain_blocks']<=r['headroom_blocks_at_least'] for r in evidence['headroom_witnesses']))
        current=copy.deepcopy(self.baseline);current['cells'].update(self.updates)
        ids={e['id'] for e in self.entities}
        before={e['id'] for e in catalog(self.baseline,baseline_entities(self.entities),self.art)}
        after={e['id'] for e in catalog(current,self.entities,self.art)}
        self.assertTrue(ids<=before);self.assertTrue(ids<=after)


if __name__=='__main__':unittest.main()
