import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import shore_bank_repairs as m

def test_bank_repair_has_no_water_material_or_suspended_geometry():
    art=EmeraldArt(m.BUNDLE);pre=m.read(m.OUT/'preconditions.json');updates,ledger=m.generate(art,m.read(m.BASELINE),pre)
    assert len(ledger)==3
    for key,entry in updates.items():
        occ,paint=decode(entry);old=pre['cells'][key]
        assert {k:v for k,v in entry.items() if k not in ('v','t','up','from')}=={k:v for k,v in old.items() if k not in ('v','t','up','from')}
        assert np.all(occ[:16]==occ[0]) and not occ[16:].any()
        rgb=np.asarray(art.cell(key,'BAKED').convert('RGB')).reshape(-1,3)
        assert all(tuple(c) in m.BROWN for c in rgb[paint[occ]])
        if key==m.NORTH:assert occ[:,0,:].all() and not occ[:,3:,:].any()
        if key==m.WEST:assert occ[:,:,0].all() and not occ[:,:,3:].any()
        if key==m.TIP:assert not occ[:,4:,:].any() and not occ[:,:,4:].any()

def test_exact_target_worldplan_floor_collision_and_story_codes_are_preserved():
    proof=m.read(m.OUT/'surface-runtime-proof.json');base=m.read(m.BASELINE);pre=m.read(m.OUT/'preconditions.json');checked=0
    for key in m.KEYS:assert base['cells'][key]==pre['cells'][key]
    for row in proof['occurrences']:
        for q in row['source_surface_columns']:
            if row['layout'][q['z']//2][q['x']//2] not in m.KEYS:continue
            def semantics(revision):
                return [(v['y'],1 if v['code_kind'] in (1,7) else v['code_kind'],v['code_value']) for v in q[revision]]
            assert semantics('before')==semantics('after')
            assert q['before_effective_floor']==q['after_effective_floor']
            checked+=1
    assert checked>=60
