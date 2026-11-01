import unittest
from collections import deque
import numpy as np

from tools.model_gallery import EmeraldArt
from tools.shape_fit.emerald.parts_model import build
from tools.shape_fit.emerald_pass.environment_templates import (
    BUNDLE,INVENTORY,MINERAL_ORDINALS,FLAG_LEFT,FLAG_RIGHT,FLAG_BASE,generate,read,
)


class EmeraldEnvironmentTemplatesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(BUNDLE);cls.templates,cls.ledger,cls.groups=generate(cls.art)

    def test_exact_patterns_cover_every_original_occurrence(self):
        inv=read(INVENTORY/'general__meteor_falls/entries.json')
        keys={inv[n-1]['key'] for n in MINERAL_ORDINALS}|{FLAG_LEFT,FLAG_RIGHT,FLAG_BASE}
        covered={k:set() for k in keys}
        for g in self.groups:
            for p in g['occurrences']:
                for z,line in enumerate(g['layout']):
                    for x,key in enumerate(line):
                        if key in keys:covered[key].add((p['map'],p['x']+x,p['y']+z))
        for key in keys:
            self.assertEqual(covered[key],{(p['map'],p['x'],p['y']) for p in self.art.locations[key]})

    def test_connected_volumes_and_room_bound(self):
        for t,g in zip(self.templates,self.groups):
            o,c=build(t,np.asarray(self.art.drawing(g['layout'],'BAKED').convert('RGB')))
            self.assertLessEqual(len(o),72)
            self.assertTrue(o.any(axis=(1,2)).all(),t['id'])
            if g['family'].startswith('mineral'):
                self.assertFalse(o[:,:o.shape[1]-16].any(),t['id'])
            points={tuple(p) for p in np.argwhere(o)};seen={next(iter(points))};todo=deque(seen)
            while todo:
                y,z,x=todo.popleft()
                for p in ((y-1,z,x),(y+1,z,x),(y,z-1,x),(y,z+1,x),(y,z,x-1),(y,z,x+1)):
                    if p in points and p not in seen:seen.add(p);todo.append(p)
            self.assertEqual(len(seen),len(points),t['id'])

    def test_all_three_flag_collision_layouts_are_preserved(self):
        flags=[g for g in self.groups if g['family']=='festival_flag']
        self.assertEqual(sorted(len(g['occurrences']) for g in flags),[1,9,22])
        self.assertEqual(sum(len(g['occurrences']) for g in flags if g['source_kinds'][1][1]==0),10)
        for t in self.templates:
            self.assertEqual(t['terrain'],[])
            self.assertEqual(t['atlas'],'g3q')

    def test_only_exact_mineral_patterns_opt_in_to_interiors(self):
        for t,g in zip(self.templates,self.groups):
            if g['family'].startswith('mineral'):
                self.assertIs(t['interior'],True)
            else:
                self.assertNotIn('interior',t)


if __name__=='__main__':unittest.main()
