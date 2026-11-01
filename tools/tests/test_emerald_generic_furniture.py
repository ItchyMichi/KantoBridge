import base64
import unittest
import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass.generic_furniture import (
    BASELINE,BUNDLE,INVENTORY,GROUP,OWNED,TABLE_ROLES,CROWNS,CHAIRS,CUSHIONS,
    rows,generate,assemblies,read,table,
)


class EmeraldGenericFurnitureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.art=EmeraldArt(BUNDLE);cls.baseline=read(BASELINE);cls.rows=rows()
        cls.at={i+1:r['key'] for i,r in enumerate(read(INVENTORY/GROUP/'entries.json'))}
        cls.updates,cls.ledger=generate(cls.baseline,cls.art,cls.rows)
        cls.entities=assemblies(cls.art,cls.rows)

    def test_exact_partial_ownership_and_metadata_preserved(self):
        self.assertEqual(len(self.ledger),77)
        self.assertEqual(set(self.updates),{self.at[n] for n in OWNED})
        for key,new in self.updates.items():
            old=self.baseline['cells'][key]
            meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
            self.assertEqual(meta(old),meta(new))
            self.assertTrue(decode(new)[0].any())

    def test_source_stack_and_floor_topologies(self):
        for upper,lower in CROWNS.items():
            self.assertEqual(self.updates[self.at[upper]]['on'],self.at[lower])
            for p in self.art.locations[self.at[upper]]:
                m=self.art.maps[p['map']];g=self.art.map_cells(m);raw=base64.b64decode(m['cells'])
                self.assertEqual(g[p['y']+1][p['x']],self.at[lower])
                i=(p['y']*m['w']+p['x'])*4
                self.assertEqual(raw[i+2],7 if upper==175 else 0)
                self.assertEqual(raw[i+m['w']*4+2],7)
        for n in set(CHAIRS)|CUSHIONS:self.assertTrue(self.updates[self.at[n]]['seat'])
        for n in TABLE_ROLES:
            for p in self.art.locations[self.at[n]]:
                m=self.art.maps[p['map']];raw=base64.b64decode(m['cells'])
                self.assertEqual(raw[(p['y']*m['w']+p['x'])*4+2],7)

    def test_whole_table_surfaces_touch_and_do_not_include_neighbor_halves(self):
        by_key={v:k for k,v in self.at.items()}
        for e in self.entities:
            if 'table' not in e['note']:continue
            grid=e['layout'];tablecols=[i for i,k in enumerate(grid[0]) if by_key.get(k) in TABLE_ROLES]
            self.assertGreaterEqual(len(tablecols),2)
            self.assertEqual(TABLE_ROLES[by_key[grid[0][tablecols[0]]]][1],'L')
            self.assertEqual(TABLE_ROLES[by_key[grid[0][tablecols[-1]]]][1],'R')
            self.assertTrue(all(TABLE_ROLES[by_key[grid[0][i]]][1]=='M' for i in tablecols[1:-1]))
            for z in range(2):
                for x in tablecols[:-1]:
                    a=decode(self.updates[grid[z][x]])[0];b=decode(self.updates[grid[z][x+1]])[0]
                    self.assertTrue(np.any(a[:,:,-1]&b[:,:,0]))
            for x in tablecols:
                a=decode(self.updates[grid[0][x]])[0];b=decode(self.updates[grid[1][x]])[0]
                self.assertTrue(np.any(a[:,-1,:]&b[:,0,:]))

    def test_shared_lower_table_quarters_never_fabricate_half_cups(self):
        for n in (32,38):
            m=table(self.art.cell(self.at[n],'BAKED'),n)
            height=10 if n==32 else 6
            self.assertFalse(m.occ[height:].any())
        for n in (24,30):
            m=table(self.art.cell(self.at[n],'BAKED'),n)
            height=10 if n==24 else 6
            self.assertTrue(m.occ[height:].any())


if __name__=='__main__':unittest.main()
