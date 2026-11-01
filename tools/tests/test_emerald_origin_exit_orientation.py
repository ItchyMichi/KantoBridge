import numpy as np
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode
from tools.shape_fit.emerald_pass import origin_exit_orientation as m

def test_complete_rim_faces_source_warp_and_keeps_its_solid_carrier_contract():
    art=EmeraldArt(m.BUNDLE);base=m.read(m.BASELINE);pre=m.read(m.OUT/'preconditions.json');updates,ledger=m.generate(art,base,pre)
    assert set(updates)==set(m.KEYS) and len(ledger)==3
    for key in m.KEYS:
        before,paint=decode(pre['cells'][key]);after,current=decode(updates[key])
        assert np.array_equal(before[:,::-1],after) and before.sum()==after.sum()
        assert {k:v for k,v in updates[key].items() if k not in ('v','t','up','from')}=={k:v for k,v in pre['cells'][key].items() if k not in ('v','t','up','from')}
    center,paint=decode(updates[m.KEYS[1]]);rgb=np.asarray(art.cell(m.KEYS[1],'BAKED').convert('RGB')).reshape(-1,3)
    assert not center[:,:8].any()  # Open approach lies in the actual north half.
    assert not (rgb[paint[:,-1]][center[:,-1]].min(axis=1)>220).any()
    assert (rgb[paint[:,14]][center[:,14]].min(axis=1)>220).any()  # Exit light remains recessed.
    for group in m.read(m.OUT/'source-evidence.json')['groups']:
        assert group['layout'][2]==list(m.KEYS) and group['collisions'][1][1]==114
