import copy
import hashlib
import json
from tools import model_gallery as gallery
from tools.shape_fit.emerald_pass import island_interior_optins as p,review


def test_only_four_exact_interior_flags_change_and_five_native_companions_validate():
    before=review.read(p.OUT/'preconditions.json');patch=review.read(p.OUT/'updates.json')['buildings']
    assert set(before)==set(patch)==p.IDS
    for key,new in patch.items():
        expected=copy.deepcopy(before[key]);expected['interior']=True
        assert new==expected
    root=review.ROOT;baseline_path=review.BASELINE;baseline=review.read(baseline_path)
    current=review.read(p.OUT/'fitted-proof-current.json');entities=review.read(p.OUT.parent/'building-native-followups/baseline-entities.json')
    assert len(entities)==5
    proof=review.read(p.OUT.parent/'building-native-followups/runtime-proof.json')
    assert len(proof['occurrences'])==6 and all(x['matching_templates']==1 for x in proof['occurrences'])
    assert all(x['headroom']>=6 for x in proof['occurrences'])
    art=gallery.EmeraldArt(root/'local/minecraft/kantobridge/export/emerald');ids={e['id'] for e in entities}
    now=[e for e in gallery.catalog(current,entities,art,'buildings') if e['id'] in ids]
    old=[e for e in gallery.catalog(baseline,gallery.baseline_entities(entities,hashlib.sha256(baseline_path.read_bytes()).hexdigest()),art,'buildings') if e['id'] in ids]
    assert len(now)==len(old)==5 and all(e['placement_mode']=='source-map-baseline' for e in old)
