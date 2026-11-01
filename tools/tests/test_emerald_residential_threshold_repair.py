import numpy as np
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import residential_threshold_repair as m

def test_source_treads_are_grounded_shallow_and_leave_passage_free():
    old=m.read(m.OUT/'preconditions.json')['cells'][m.KEY];entry,row=m.generate(m.read(m.BASELINE),old);occ,paint=decode(entry)
    assert not occ[2:].any() and not occ[:,:11].any() and not occ[:,15:].any()
    assert occ[0,11:15].all() and occ[1,13:15].all() and not occ[1,:13].any()
    assert np.array_equal(paint[0],np.arange(256,dtype=np.uint8).reshape(16,16))
    assert {k:v for k,v in entry.items() if k not in ('v','t','up','from')}=={k:v for k,v in old.items() if k not in ('v','t','up','from')}
    assert entry['walk'] is True and len(row['occurrences'])==2
