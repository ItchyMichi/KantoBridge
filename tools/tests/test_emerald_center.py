import unittest
from collections import deque

import numpy as np

from tools.model_gallery import EmeraldArt, baseline_entities, catalog
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import center as c
from tools.shape_fit.emerald_pass.review import read,entry_hash


class EmeraldCenterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(c.BUNDLE);cls.base=read(c.BASELINE);cls.rows=c.rows(cls.art)
        cls.updates,cls.ledger,cls.amendments=c.generate(cls.base,cls.art,cls.rows)
        cls.current=read(c.ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json');cls.current['cells'].update(cls.updates)
        cls.key={r['source_ordinal']:r['key'] for r in cls.rows}
        cls.entities,cls.places=c.assemblies(cls.art,cls.rows,cls.base,cls.current)

    def test_exact_ownership_and_metadata_changes(self):
        self.assertEqual(76,len(self.ledger));self.assertEqual(73,len(self.updates))
        for n,key in self.key.items():
            row=next(r for r in self.ledger if r['key']==key)
            self.assertEqual(entry_hash(self.base['cells'][key]),row['baseline_entry_sha256'])
            if key not in self.updates:continue
            old={k:v for k,v in self.base['cells'][key].items() if k not in ('v','t','up','from')}
            new={k:v for k,v in self.updates[key].items() if k not in ('v','t','up','from')}
            if n in c.WALK:old['walk']=True
            if n in c.REMOVE_ON:old.pop('on')
            if n in c.ADD_ON:old['on']=self.key[c.ADD_ON[n]]
            if n in c.COUNTER_KIND:old['counter']=True
            self.assertEqual(old,new,n)
        for a in self.amendments:
            if 'walk' in a['changes']:self.assertTrue(all(p['collision'] in (0,114) for p in a['occurrences']))
            if 'counter' in a['changes']:self.assertTrue(all(p['collision']==144 for p in a['occurrences']))

    def test_shared_pc_geometry_and_valid_cap_interfaces(self):
        old=self.base['cells'][c.PC];new=self.updates[c.PC]
        for f in ('v','t','up'):self.assertEqual(old.get(f),new.get(f))
        self.assertEqual(51,len(self.art.locations[c.PC]))
        body,_=decode(new)
        for n in (4,75,76):
            cap,_=decode(self.updates[self.key[n]])
            self.assertTrue((cap[0]&body[15]).any())
            self.assertEqual(2,int(np.argwhere(cap)[:,0].max()))
            self.assertEqual(c.PC,self.updates[self.key[n]]['on'])

    def test_exact_vertical_pairs_and_floor_depth_divider_joins(self):
        for upper,lower in c.ADD_ON.items():
            for p in c.source_occurrences(self.art,self.key[upper]):self.assertEqual(self.key[lower],p['below_key'])
            a,_=decode(self.updates[self.key[upper]]);b,_=decode(self.updates[self.key[lower]])
            self.assertTrue((a[0]&b[15]).any())
        for upper,lower in ((25,28),(28,33),(28,56),(33,71),(71,73)):
            a,_=decode(self.updates[self.key[upper]]);b,_=decode(self.updates[self.key[lower]])
            self.assertTrue((a[:,-1,:]&b[:,0,:]).any(),(upper,lower))
        self.assertNotIn('on',self.updates[self.key[28]])
        self.assertNotIn('on',self.updates[self.key[71]])

    def test_two_escalator_flights_join_shared_landing_and_rails(self):
        for layout in c.ESCALATORS:
            for row in layout:
                left,_=decode(self.updates[self.key[row[0]]]);right,_=decode(self.updates[self.key[row[1]]])
                self.assertTrue((left[:,:,-1]&right[:,:,0]).any(),row)
            for row in range(2):
                for col in range(2):
                    a,_=decode(self.updates[self.key[layout[row][col]]]);b,_=decode(self.updates[self.key[layout[row+1][col]]])
                    # Native rail rows are separated from the flight by air;
                    # their own grounded feet prevent floating sheets.
                    self.assertTrue(a[0].any() and b[0].any())

    def test_every_complete_current_component_is_grounded(self):
        for entity in self.entities:
            filled=set()
            for place in entity['placements']:
                x,z=place['source'];o,_=decode(self.current['cells'][entity['layout'][z][x]])
                ox,oy,oz=[v*16 for v in place['offset']]
                filled.update((int(y)+oy,int(z0)+oz,int(x0)+ox) for y,z0,x0 in np.argwhere(o))
            remaining=set(filled)
            while remaining:
                seed=remaining.pop();todo=deque([seed]);ground=seed[0]==0
                while todo:
                    y,z,x=todo.popleft()
                    for q in ((y-1,z,x),(y+1,z,x),(y,z-1,x),(y,z+1,x),(y,z,x-1),(y,z,x+1)):
                        if q in remaining:remaining.remove(q);todo.append(q);ground|=q[0]==0
                self.assertTrue(ground,entity['id'])

    def test_gallery_before_current_identity_and_complete_cell_coverage(self):
        ids={e['id'] for e in self.entities}
        before={e['id'] for e in catalog(self.base,baseline_entities(self.entities),self.art)}
        after={e['id'] for e in catalog(self.current,self.entities,self.art)}
        self.assertTrue(ids<=before);self.assertTrue(ids<=after)
        covered={k for e in self.entities for line in e['layout'] for k in line if k}
        required={key for n,key in self.key.items() if n not in c.RETAIN}
        self.assertTrue(required<=covered,required-covered)


if __name__=='__main__':unittest.main()
