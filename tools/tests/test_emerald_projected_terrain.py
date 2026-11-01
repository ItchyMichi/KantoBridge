import numpy as np
import pytest

from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import projected_terrain as p
from tools.shape_fit.emerald_pass.review import read


@pytest.mark.skipif(not p.BASELINE.exists() or not p.BUNDLE.exists(),reason="user's local export and baseline required")
def test_projected_leaf_pixels_do_not_become_terrain_geometry_or_paint():
    table=read(p.BASELINE);art=EmeraldArt(p.BUNDLE);updates,_=p.generate(table,art)
    assert len(updates)==16
    for key,entry in updates.items():
        old=table['cells'][key]
        metadata=lambda e:{k:v for k,v in e.items() if k not in {'v','t','up','from'}}
        assert metadata(entry)==metadata(old)
        occ,paint=decode(entry);source=np.asarray(art.cell(key,'BAKED').convert('RGB'))
        assert occ.any()
        if key in p.SURFACES:
            assert occ[0].all() and not occ[1:].any()
            used={tuple(c) for c in source[paint[occ]//16,paint[occ]%16]}
            assert not used & set(p.LEAVES)
        if key in p.MATCHES:
            assert np.array_equal(occ,decode(table['cells'][p.MATCHES[key]])[0])
    # The formerly empty path entry is now an explicit ground surface.
    assert decode(updates['g3q/10728-10729-19702-11304'])[0].sum()==256
