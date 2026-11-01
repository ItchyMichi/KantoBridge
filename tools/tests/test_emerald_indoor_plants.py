"""Three explicit crown variants keep their real pot link and exclude room art."""
import base64
import numpy as np
from tools.shape_fit.emerald_pass import indoor_plants as p,review
from tools.shape_fit.blue_pass.common import decode


def test_crowns_use_only_leaf_material_and_join_the_existing_stem():
    table=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE)
    patch=p.generate(table,art)
    assert len(patch)==3 and p.BASE not in patch
    occupancy=[]
    for key,entry in patch.items():
        review.validate_cell(key,table['cells'][key],entry)
        occ,paint=decode(entry);occupancy.append(occ)
        colors=np.asarray(art.cell(key,'BAKED').convert('RGB')).reshape(-1,3)[paint][occ].astype(int)
        assert ((colors[:,1]>colors[:,0])&(colors[:,1]>colors[:,2])).all()
        assert occ[:4,7:9,7:9].all()
        assert not occ[:,0].any() and not occ[:,-1].any()
        assert not occ[:,:,0].any() and not occ[:,:,-1].any()
        assert occ[3:12].sum()>900
    assert all(np.array_equal(occupancy[0],o) for o in occupancy[1:])


def test_all_nine_actual_occurrences_end_in_the_same_solid_cream_pot():
    art=p.c.EmeraldArt(p.c.BUNDLE);count=0
    for key,_,_ in p.KEYS:
        for place in art.locations[key]:
            m=art.maps[place['map']];grid=art.map_cells(m);x,y=place['x'],place['y']
            assert grid[y+1][x]==p.BASE
            assert base64.b64decode(m['cells'])[((y+1)*m['w']+x)*4+2]==7
            count+=1
    assert count==9
