import unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.emerald.parts_model import build
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass.elite_four import BASELINE,BUNDLE,read,rows,generate,PITS,KEEP,WALK,REMOVE_ON
from tools.shape_fit.emerald_pass.elite_arches import generate as arches


class EmeraldEliteFourTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(BUNDLE);cls.baseline=read(BASELINE);cls.rows=rows()
        cls.updates,cls.ledger,cls.meta=generate(cls.baseline,cls.art,cls.rows)
        cls.templates,cls.added,cls.groups=arches(cls.art)

    def test_complete_arches_cover_every_fragment_occurrence(self):
        keys={self.rows[n-1]['key'] for n in (1,2,3,4,70,71,72,73,83)}
        covered={k:set() for k in keys}
        for g in self.groups:
            for p in g['occurrences']:
                for z,line in enumerate(g['layout']):
                    for x,k in enumerate(line):
                        if k in covered:covered[k].add((p['map'],p['x']+x,p['y']+z))
        for k in keys:
            expected={(p['map'],p['x'],p['y']) for p in self.art.locations[k]}
            if k==self.rows[82]['key']:
                expected|={('EM_EVER_GRANDE_CITY_'+room+'_ROOM',6,1) for room in ('SIDNEYS','PHOEBES','GLACIAS','DRAKES')}
            self.assertEqual(covered[k],expected,k)

    def test_arches_have_true_open_passage_and_preserve_native_story_state(self):
        self.assertEqual(len(self.templates),2)
        self.assertEqual(sorted(len(g['occurrences']) for g in self.groups),[1,9])
        for t,g in zip(self.templates,self.groups):
            o,c=build(t,np.asarray(self.art.drawing(g['layout'],'BAKED').convert('RGB')))
            self.assertEqual(o.shape,(32,32,48));self.assertTrue(t['interior'])
            self.assertFalse(o[:,:24].any())
            self.assertFalse(o[:24,:,16:32].any())
            self.assertTrue(o[24:,24:32,16:32].all())
            for p in g['occurrences']:
                self.assertEqual(p['source_kinds'][1],[7,114,7])
                self.assertEqual(p['source_kinds'][0],[7,7 if '_HALL' in p['map'] else 0,7])
                self.assertEqual(p['raw_source_kinds'][1],[7,114 if '_HALL' in p['map'] else 7,7])

    def test_source_metadata_changes_are_exact_and_no_retained_geometry_changes(self):
        self.assertEqual(len(self.ledger),85);self.assertEqual(len(self.updates),85-len(KEEP))
        for r in self.rows:
            n=r['source_ordinal'];key=r['key'];old=self.baseline['cells'][key]
            if n in KEEP:self.assertNotIn(key,self.updates);continue
            new=self.updates[key]
            fields={k for k in old.keys()|new.keys() if k not in ('v','t','up','from') and old.get(k)!=new.get(k)}
            self.assertEqual(fields,({'walk'} if n in WALK else set())|({'on'} if n in REMOVE_ON else set()))
            o,p=decode(new);self.assertTrue(o.any());self.assertTrue(o[0].any())
            if n in PITS:self.assertTrue(o[:2].all());self.assertFalse(o[7:].any())

    def test_rail_start_and_repeat_have_exact_touching_interfaces(self):
        for upper,lower in REMOVE_ON.items():
            a,_=decode(self.updates[self.rows[upper-1]['key']]);b,_=decode(self.updates[self.rows[lower-1]['key']])
            self.assertTrue(np.array_equal(a[:,-1,:],b[:,0,:]))
            self.assertFalse(a[:,:8].any());self.assertTrue(b[:,0].any())


if __name__=='__main__':unittest.main()
