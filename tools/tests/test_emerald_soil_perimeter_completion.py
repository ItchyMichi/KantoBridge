import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import soil_perimeter_completion as m

def test_soil_completion_preserves_cliff_columns_and_removes_grass_skirts():
    art=EmeraldArt(m.BUNDLE);base=m.read(m.BASELINE);pre=m.read(m.OUT/'preconditions.json');updates,ledger=m.generate(art,base,pre)
    assert set(updates)==set(m.KEYS) and len(ledger)==6
    for key,entry in updates.items():
        old=pre['cells'][key];occ,paint=decode(entry);was,oldpaint=decode(old)
        assert {k:v for k,v in entry.items() if k not in ('v','t','up','from')}=={k:v for k,v in old.items() if k not in ('v','t','up','from')}
        assert not occ[4:].any() and occ[:4].any()
        assert not any(entry.get(flag) for flag in ('walk','on','seat'))
        if key in (m.CLIFF_N,m.CLIFF_M,m.CLIFF_S):
            assert np.array_equal(occ[:,:,:8],was[:,:,:8])
            assert np.array_equal(paint[:4,:,:8],oldpaint[:4,:,:8])
        else:
            assert not occ[:,6:,:].any()
        rgb=np.asarray(art.cell(key,'BAKED').convert('RGB')).reshape(-1,3)
        soil=occ.copy()
        if key.startswith('g3q/17071'):soil[:,:,:8]=False
        assert all(tuple(c) in m.SOIL for c in rgb[paint[soil]])
    assert sum(len(row['occurrences']) for row in ledger)==97
