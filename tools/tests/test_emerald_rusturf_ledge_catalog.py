import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import rusturf_ledge_catalog as m

def test_catalog_ledge_matches_proven_native_slab_height_without_new_flags():
    base=m.read(m.BASELINE);old=m.read(m.OUT/'preconditions.json')['cells'][m.KEY];entry,row,source=m.generate(EmeraldArt(m.BUNDLE),base,old);occ,paint=decode(entry)
    assert occ[:4].all() and not occ[4:].any()
    assert np.array_equal(paint[3],np.arange(256,dtype=np.uint8).reshape(16,16))
    assert not any(entry.get(f) for f in ('on','walk','seat'))
    assert {k:v for k,v in entry.items() if k not in ('v','t','up','from')}=={k:v for k,v in old.items() if k not in ('v','t','up','from')}
    assert len(source['occurrences'])==11 and all(p['collision']==163 for p in source['occurrences'])
