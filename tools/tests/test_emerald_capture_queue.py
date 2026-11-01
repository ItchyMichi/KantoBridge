"""Native map contexts must not disappear merely because native terrain has no fitted ledger row."""
import copy
from tools.shape_fit.emerald_pass.capture_queue import schedule_entries

def native():
    descriptor={'evidence':'local/exact-proof.json','evidence_sha256':'a'*64}
    return {'id':'origin-context','kind':'compound','source_keys':['g3q/reviewed','g3q/native-floor'],
            'placement_mode':'source-map-current','current_native_fragments':descriptor,
            'native_current_evidence':{'descriptor':copy.deepcopy(descriptor),'record':{'corrected_fragments':[]}}}

def test_validated_native_floor_context_is_scheduled_and_pending_membership_is_explicit():
    entry=native();reviewed={('cell','g3q/reviewed')};original=copy.deepcopy(entry)
    selected,covered,pending=schedule_entries([entry],reviewed,{})
    assert selected==[entry] and covered==[] and entry==original
    assert pending[0]['pending_source_identities']==[{'kind':'cell','key':'g3q/native-floor'}]
    assert 'pending semantic review' in pending[0]['reason']
    selected,covered,pending=schedule_entries([entry],reviewed,{'origin-context':['exact-gallery']})
    assert selected==[] and covered==[{'id':'origin-context','galleries':['exact-gallery']}] and len(pending)==1

def test_unvalidated_or_generic_compounds_still_require_every_ledger_identity():
    for field in ('native_current_evidence','current_native_fragments','placement_mode'):
        entry=native();entry.pop(field);assert schedule_entries([entry],{('cell','g3q/reviewed')},{})==([],[],[])
    entry=native();entry['native_current_evidence']['descriptor']['evidence_sha256']='b'*64
    assert schedule_entries([entry],{('cell','g3q/reviewed')},{})==([],[],[])
    generic={'id':'old-whole','kind':'compound','source_keys':['g3q/reviewed','g3q/pending']}
    assert schedule_entries([generic],{('cell','g3q/reviewed')},{})==([],[],[])
    assert schedule_entries([generic],{('cell','g3q/reviewed'),('cell','g3q/pending')},{})[0]==[generic]
