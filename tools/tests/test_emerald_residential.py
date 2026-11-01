import copy
import unittest
from collections import deque

import numpy as np

from tools.model_gallery import EmeraldArt, baseline_entities, catalog
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import residential as r
from tools.shape_fit.emerald_pass.review import read


class EmeraldResidentialTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(r.BUNDLE);cls.base=read(r.BASELINE);cls.rows=r.rows(cls.art)
        cls.updates,cls.ledger,cls.amendments=r.generate(cls.base,cls.art,cls.rows)
        cls.current=read(r.ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json')
        cls.current['cells'].update(cls.updates)
        cls.entities,cls.placements=r.assemblies(cls.art,cls.rows,cls.base,cls.current)
        cls.key={row['source_ordinal']:row['key'] for row in cls.rows}

    def test_ownership_baseline_and_metadata_are_explicit(self):
        self.assertEqual(79,len(self.ledger));self.assertEqual(70,len(self.updates))
        self.assertEqual(set(r.EXTRA),{x['key'] for x in self.ledger if x.get('baseline_absent')})
        self.assertTrue(all(k not in self.base['cells'] for k in r.EXTRA))
        for n,key in self.key.items():
            if key not in self.updates:continue
            new=self.updates[key];old=self.base['cells'].get(key,{'class':'voxels'})
            oldmeta={k:v for k,v in old.items() if k not in ('v','t','up','from')}
            newmeta={k:v for k,v in new.items() if k not in ('v','t','up','from')}
            if n in r.WALK:oldmeta['walk']=True
            if n in r.REMOVE_ON:oldmeta.pop('on')
            if n in r.ADD_ON:oldmeta['on']=self.key[r.ADD_ON[n]]
            self.assertEqual(oldmeta,newmeta)
        for change in self.amendments:
            if 'walk' in change['changes']:
                self.assertTrue(all(p['collision'] in (0,114) for p in change['occurrences']))

    def test_new_stack_faces_align_and_preserve_native_art(self):
        proof=r.stack_evidence(self.art,self.rows);self.assertEqual(6,len(proof))
        for upper,lower in r.ADD_ON.items():
            a,pa=decode(self.updates[self.key[upper]]);b,pb=decode(self.updates[self.key[lower]])
            self.assertTrue((a[0]&b[15]).any())
            if upper in (16,61,36):
                for local_y in range(16):
                    for x in range(16):
                        self.assertEqual((15-local_y)*16+x,int(pa[local_y,14,x]))
                        self.assertEqual((15-local_y)*16+x,int(pb[local_y,14,x]))

    def test_desktop_and_bed_slice_interfaces_touch(self):
        for a,b in ((44,45),(42,43)):
            left,_=decode(self.updates[self.key[a]]);right,_=decode(self.updates[self.key[b]])
            self.assertTrue((left[:,:,-1]&right[:,:,0])[6:8].any())
        for layout in r.BEDS:
            for row in range(2):
                for col in range(3):
                    a,_=decode(self.updates[self.key[layout[row][col]]]);b,_=decode(self.updates[self.key[layout[row+1][col]]])
                    self.assertTrue((a[:,-1,:]&b[:,0,:]).any())

    def test_sofas_exclude_rug_colors_and_tables_have_common_leg_material(self):
        for n in (32,33,70,71):
            o,p=decode(self.updates[self.key[n]]);sprite=np.asarray(self.art.cell(self.key[n],'BAKED').convert('RGB'))
            rgb=sprite[p[o]//16,p[o]%16].astype(int)
            self.assertTrue((abs(rgb[:,0]-rgb[:,1])<20).all())
        for layout in r.TABLES:
            shades=[]
            for line in layout:
                for n in line:
                    o,p=decode(self.updates[self.key[n]]);sprite=np.asarray(self.art.cell(self.key[n],'BAKED').convert('RGB'))
                    t=int(p[0][o[0]][0]);shades.append(tuple(sprite[t//16,t%16]))
            self.assertEqual(1,len(set(shades)))

    def test_every_complete_object_component_reaches_ground(self):
        for entity in self.entities:
            filled=set()
            for place in entity['placements']:
                x,z=place['source'];key=entity['layout'][z][x];o,_=decode(self.current['cells'][key])
                ox,oy,oz=[v*16 for v in place['offset']]
                filled.update((int(y)+oy,int(z0)+oz,int(x0)+ox) for y,z0,x0 in np.argwhere(o))
            remaining=set(filled)
            while remaining:
                seed=remaining.pop();todo=deque([seed]);ground=seed[0]==0
                while todo:
                    y,z,x=todo.popleft()
                    for q in ((y-1,z,x),(y+1,z,x),(y,z-1,x),(y,z+1,x),(y,z,x-1),(y,z,x+1)):
                        if q in remaining:
                            remaining.remove(q);todo.append(q);ground|=q[0]==0
                self.assertTrue(ground,entity['id'])

    def test_whole_gallery_before_current_ids_and_missing_walk_sources(self):
        ids={x['id'] for x in self.entities}
        before={x['id'] for x in catalog(self.base,baseline_entities(self.entities),self.art)}
        after={x['id'] for x in catalog(self.current,self.entities,self.art)}
        self.assertTrue(ids<=before);self.assertTrue(ids<=after)
        for entity in self.entities:
            for row in entity['baseline_layout']:
                self.assertTrue(all(k not in r.EXTRA for k in row if k))


if __name__=='__main__':unittest.main()
