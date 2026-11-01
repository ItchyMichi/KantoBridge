"""Safety boundaries of cumulative Emerald batches, using synthetic entries."""
import base64
import copy
import json

import pytest

from tools.shape_fit.emerald_pass import review


def fixture():
    cell = {'class': 'voxels', 'walk': False,
            'v': base64.b64encode(bytes([1]) + bytes(511)).decode(),
            't': base64.b64encode(bytes(4096)).decode()}
    return {'game': 'emerald', 'cells': {'test': cell, 'other': cell.copy()}, 'buildings': []}


def batch(b):
    new = copy.deepcopy(b['cells']['test'])
    new['v'] = base64.b64encode(bytes([3]) + bytes(511)).decode()
    return {'cells': {'test': new}}, [{'kind': 'cell', 'key': 'test', 'label': 'Test solid',
        'family': 'test', 'decision': 'changed', 'reason': 'Supported geometry',
        'evidence': ['synthetic'], 'baseline_entry_sha256': review.entry_hash(b['cells']['test'])}]


def test_partial_review_keeps_pending_and_preserves_unowned_entries():
    b = fixture()
    p, ledger = batch(b)
    result, report = review.merge(b, [p], [ledger])
    assert result['cells']['other'] == b['cells']['other']
    assert report['counts'] == {'changed': 1, 'pending': 1}
    assert not report['visual_review_complete'] and not report['semantic_coverage_complete']
    with pytest.raises(ValueError, match='still need semantic review'):
        review.merge(b, [p], [ledger], require_complete=True)


def test_duplicate_stale_unreviewed_and_interaction_changes_rejected():
    b = fixture()
    p, ledger = batch(b)
    with pytest.raises(ValueError, match='duplicate patch'):
        review.merge(b, [p, p], [ledger])
    with pytest.raises(ValueError, match='patch without review'):
        review.merge(b, [p], [])
    ledger[0]['baseline_entry_sha256'] = 'stale'
    with pytest.raises(ValueError, match='stale source'):
        review.merge(b, [p], [ledger])
    p['cells']['test']['walk'] = True
    with pytest.raises(ValueError, match='metadata changed'):
        review.merge(b, [p], [])


def test_exact_source_bytes_guard_independent_edit(tmp_path):
    b = fixture()
    p, ledger = batch(b)
    result, _ = review.merge(b, [p], [ledger])
    raw = json.dumps(b).encode()
    target = tmp_path / 'table.json'
    target.write_bytes(raw)
    generated = review.write_guarded(target, raw, result)
    assert target.read_bytes() == generated
    target.write_bytes(generated + b' ')
    with pytest.raises(ValueError, match='changed independently'):
        review.write_guarded(target, raw, result)


def test_alias_amendments_require_both_exact_metadata_states():
    key='g3q/7157-7158-7159-7160'
    pin=review.STACK_AMENDMENTS[key]
    old=fixture()['cells']['test'];old.update(pin['before'])
    new=copy.deepcopy(old);new.update(pin['after'])
    review.validate_cell(key,old,new)
    for field,value in [('alsoOn',['unknown']),('on','unknown'),('walk',True)]:
        bad=copy.deepcopy(new);bad[field]=value
        with pytest.raises(ValueError,match='metadata changed'):
            review.validate_cell(key,old,bad)
    stale=copy.deepcopy(old);stale['on']='unknown'
    with pytest.raises(ValueError,match='metadata changed'):
        review.validate_cell(key,stale,new)


def test_new_building_requires_source_pin_and_preserves_existing_order(monkeypatch):
    b=fixture()
    old={'id':'old','atlas':'g3q','tiles':[[1,1],[1,1]],'terrain':[],
         'height':8,'parts':[{'shape':'box','x':[0,16],'z':[0,16],'y':[0,8]}]}
    b['buildings']=[old]
    new=copy.deepcopy(old);new['id']='new';new['tiles']=[[2,2],[2,2]]
    pin={'metadata':{k:v for k,v in new.items() if k not in ('height','parts')},
         'source_pixel_sha256':'pixels','occurrences':[('ROOM',1,2)]}
    monkeypatch.setattr(review,'ADDED_BUILDINGS',{'new':pin})
    row={'kind':'building','key':'new','label':'Complete missing object','family':'test',
         'reason':'Unique source pattern','evidence':['source'],'decision':'added',
         'baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':review.entry_hash(new),
         'source_pixel_sha256':'pixels','occurrences':[{'map':'ROOM','x':1,'y':2}]}
    result,report=review.merge(b,[{'buildings':{'new':new}}],[[row]])
    assert result['buildings']==[old,new]
    assert report['counts']['added']==1 and report['counts']['pending']==3
    bad=copy.deepcopy(new);bad['tiles'][0][0]=99
    with pytest.raises(ValueError,match='metadata changed'):
        review.merge(b,[{'buildings':{'new':bad}}],[[row]])


def test_accepted_pilots_cannot_be_replaced():
    b = fixture()
    key = sorted(review.protected())[0]
    b['cells'][key] = b['cells']['test'].copy()
    p, _ = batch(b)
    with pytest.raises(ValueError, match='accepted pilot changed'):
        review.merge(b, [{'cells': {key: p['cells']['test']}}], [])


def test_building_rejects_source_spill_and_clipped_profiles():
    old = {'id': 'test', 'tiles': [[1, 1], [1, 1]], 'height': 16,
           'parts': [{'shape': 'box', 'x': [0, 16], 'z': [0, 16], 'y': [0, 15]}]}
    new = copy.deepcopy(old)
    new['parts'][0]['x'][1] = 17
    with pytest.raises(ValueError, match='footprint'):
        review.validate_building('test', old, new)
    new = copy.deepcopy(old)
    new['parts'][0].update(shape='profile', profile=[1, 18, 1])
    with pytest.raises(ValueError, match='profile outside'):
        review.validate_building('test', old, new)


def test_addition_requires_exact_scope_source_metadata_and_candidate_hash(monkeypatch):
    b = fixture()
    new = copy.deepcopy(b['cells']['test'])
    key = 'new-cap'
    monkeypatch.setattr(review, 'ADDED_CELLS', {key: {
        'metadata': {'class': 'voxels', 'walk': False},
        'source_pixel_sha256': 'pixels', 'occurrences': [('ROOM', 2, 3)],
    }})
    row = {'kind': 'cell', 'key': key, 'label': 'Missing cap', 'family': 'seat',
           'reason': 'Completes the source seat', 'evidence': ['source crop'],
           'decision': 'added', 'baseline_absent': True, 'baseline_entry_sha256': None,
           'proposed_entry_sha256': review.entry_hash(new), 'source_pixel_sha256': 'pixels',
           'occurrences': [{'map': 'ROOM', 'x': 2, 'y': 3}]}
    patch = {'cells': {key: new}}
    result, report = review.merge(b, [patch], [[row]])
    assert result['cells'][key] == new and report['counts']['added'] == 1
    bad = copy.deepcopy(row); bad['proposed_entry_sha256'] = 'stale'
    with pytest.raises(ValueError, match='addition evidence'):
        review.merge(b, [patch], [[bad]])
    bad = copy.deepcopy(row); bad['occurrences'][0]['x'] = 9
    with pytest.raises(ValueError, match='addition evidence'):
        review.merge(b, [patch], [[bad]])
    bad_patch = copy.deepcopy(patch); bad_patch['cells'][key]['walk'] = True
    with pytest.raises(ValueError, match='metadata changed'):
        review.merge(b, [bad_patch], [[row]])
    with pytest.raises(ValueError, match='scope amendment'):
        review.merge(b, [{'cells': {'unknown': new}}], [[row]])
