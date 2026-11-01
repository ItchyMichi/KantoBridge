import unittest
from collections import deque

import numpy as np

from tools.model_gallery import EmeraldArt,catalog,baseline_entities
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import school as s
from tools.shape_fit.emerald_pass.review import read,entry_hash


class EmeraldSchoolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(s.BUNDLE);cls.base=read(s.BASELINE);cls.rows=s.rows(cls.art)
        cls.updates,cls.ledger,cls.meta=s.generate(cls.base,cls.art,cls.rows)
        cls.current=read(s.ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json');cls.current['cells'].update(cls.updates)
        cls.entities,cls.places=s.assemblies(cls.art,cls.rows,cls.base,cls.current)
        cls.key={r['source_ordinal']:r['key'] for r in cls.rows}

    def test_exact_originals_additions_and_metadata(self):
        self.assertEqual(19,len(self.ledger));self.assertEqual(19,len(self.updates))
        self.assertEqual(set(s.EXTRA),{r['key'] for r in self.ledger if r.get('baseline_absent')})
        self.assertTrue(all(k not in self.base['cells'] for k in s.EXTRA))
        for row in self.ledger:
            if row.get('baseline_absent'):
                self.assertEqual('added',row['decision']);self.assertIsNone(row['baseline_entry_sha256'])
                self.assertEqual('RGBA',row['source_pixel_mode']);self.assertEqual(64,len(row['source_pixel_sha256']))
        for n,key in self.key.items():
            old=self.base['cells'].get(key,{'class':'voxels'})
            oldmeta={k:v for k,v in old.items() if k not in ('v','t','up','from')}
            newmeta={k:v for k,v in self.updates[key].items() if k not in ('v','t','up','from')}
            if n in s.WALK:oldmeta['walk']=True
            if n in s.REMOVE_ON:oldmeta.pop('on')
            if n in s.ADD_ON:oldmeta['on']=self.key[s.ADD_ON[n]]
            self.assertEqual(oldmeta,newmeta,n)
        for item in self.meta:
            if 'walk' in item['changes']:self.assertTrue(all(p['collision']==0 for p in item['occurrences']))

    def test_complete_board_stack_and_native_paint(self):
        proof=s.source_evidence(self.art,self.rows,self.base,self.updates)
        self.assertEqual(4,len(proof['new_wall_stacks']))
        for upper,lower in s.ADD_ON.items():
            a,ap=decode(self.updates[self.key[upper]]);b,bp=decode(self.updates[self.key[lower]])
            self.assertTrue((a[0]&b[15]).any())
            for y in range(16):
                for x in range(16):
                    self.assertEqual((15-y)*16+x,int(ap[y,13,x]))
                    self.assertEqual((15-y)*16+x,int(bp[y,13,x]))
        for a,b in ((17,18),(18,19),(19,13),(14,15),(15,16)):
            left,_=decode(self.updates[self.key[a]]);right,_=decode(self.updates[self.key[b]])
            self.assertTrue((left[:,:,-1]&right[:,:,0]).any())

    def test_columns_preserve_notice_scale_and_touch_walk_foot(self):
        foot,_=decode(self.updates[self.key[8]])
        for n in (1,5):
            o,p=decode(self.updates[self.key[n]])
            self.assertEqual(32,len(o));self.assertNotIn('on',self.updates[self.key[n]])
            self.assertTrue((o[:16,-1,:]&foot[:,0,:]).any())
            for y in range(16,32):
                for x in range(3,13):self.assertEqual((31-y)*16+x,int(p[y,15,x]))
        self.assertTrue(self.updates[self.key[8]]['walk'])

    def test_all_nine_complete_objects_have_grounded_components(self):
        self.assertEqual(9,len(self.entities))
        for entity in self.entities:
            remaining=set()
            for p in entity['placements']:
                col,row=p['source'];o,_=decode(self.current['cells'][entity['layout'][row][col]])
                ox,oy,oz=[v*16 for v in p['offset']]
                remaining.update((int(y)+oy,int(z)+oz,int(x)+ox) for y,z,x in np.argwhere(o))
            while remaining:
                seed=remaining.pop();todo=deque([seed]);ground=seed[0]==0
                while todo:
                    y,z,x=todo.popleft()
                    for q in ((y-1,z,x),(y+1,z,x),(y,z-1,x),(y,z+1,x),(y,z,x-1),(y,z,x+1)):
                        if q in remaining:remaining.remove(q);todo.append(q);ground|=q[0]==0
                self.assertTrue(ground,entity['id'])

    def test_absent_solid_baseline_remains_explicit_not_omitted(self):
        missing=[e for e in self.entities if e.get('baseline_native_cells')]
        self.assertEqual(2,len(missing))
        for e in missing:
            self.assertEqual(set(s.EXTRA),set(e['baseline_native_cells']))
            self.assertTrue(set(s.EXTRA)<={k for row in e['baseline_layout'] for k in row if k})
        ids={e['id'] for e in self.entities};after={e['id'] for e in catalog(self.current,self.entities,self.art)}
        self.assertTrue(ids<=after)
        before=catalog(self.base,baseline_entities(self.entities),self.art)
        self.assertTrue(ids<={e['id'] for e in before})
        native=[e for e in before if e['kind']=='cell' and e.get('native_baseline_cells')]
        self.assertEqual(3,len(native))
        for e in native:
            self.assertEqual([2,6,2],e['bounds']);self.assertEqual(24,len(e['fragments']))
            self.assertTrue(all(p['key'].startswith('cube:') for p in e['fragments']))


if __name__=='__main__':unittest.main()
