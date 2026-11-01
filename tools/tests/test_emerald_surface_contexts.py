"""Actual Route110 witnesses cover complete native surfaces and measured ramps."""
import hashlib
import pytest
from tools import model_gallery as gallery
from tools.shape_fit.emerald_pass import review


def test_route110_native_contexts_keep_every_visible_carrier_at_one_common_datum():
    folder=review.ROOT/'local/model-gallery/emerald-pass-study/bridge-native-surfaces'
    if not (folder/'native-entities.json').exists():pytest.skip('requires authorized local source export')
    proof=review.read(folder/'runtime-proof.json');entities=review.read(folder/'native-entities.json')
    table=review.read(folder/'fitted-current.json');old=review.read(review.BASELINE)
    art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');ids={e['id'] for e in entities}
    before={e['id']:e for e in gallery.catalog(old,gallery.baseline_entities(entities,hashlib.sha256(review.BASELINE.read_bytes()).hexdigest()),art) if e['id'] in ids}
    after={e['id']:e for e in gallery.catalog(table,entities,art) if e['id'] in ids}
    assert set(before)==set(after)==ids and len(ids)==3
    quarters=ramps=water=decks=templates=steps=0
    for ident,current in after.items():
        prior=before[ident];a=current['native_current_evidence'];b=prior['native_baseline_evidence'];row=a['record']
        assert current['bounds']==prior['bounds']
        lift=a['native_surface_audit']['common_vertical_lift'];assert lift==b['native_surface_audit']['common_vertical_lift']
        for q in row['source_surface_columns']:
            quarters+=1;ramps+=bool(q.get('ramp'));decks+=bool(q.get('deck'));water+=q['kind']=='WATER'
        for entry,revision in ((current,'after'),(prior,'before')):
            actual={(f['x'],f['y'],f['z']):f for f in entry['fragments']}
            visible=[(q,v) for q in row['source_surface_columns'] for v in q[revision] if v['visible']]
            assert len(actual)==len(visible)
            for q,v in visible:
                fragment=actual[q['x'],v['y']+lift,q['z']]
                if v['code_kind']==2:assert fragment['key']==f"slab:0|{v['tile']}|8"
                if v['code_kind']==12:
                    steps+=1;assert '_stair_' in fragment['key'];assert q['ramp']['rise'] in (1,2,3)
                if v.get('shape_key','').startswith('tmpl:'):
                    templates+=1;assert fragment['key']==v['shape_key'];assert row['current_templates']
    assert (quarters,ramps,water,decks)==(1388,12,360,0)
    assert steps>0 and templates>0
    assert proof['outdoor_levels']=='all' and proof['chain_levels'] is False


def test_acro_raised_network_keeps_its_high_floors_and_all_source_surfaces():
    folder=review.ROOT/'local/model-gallery/emerald-pass-study/acro-source-study'
    if not (folder/'native-entities.json').exists():pytest.skip('requires authorized local source export')
    entities=review.read(folder/'native-entities.json');ids={e['id'] for e in entities}
    art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');table=review.read(folder/'fitted-surface-current.json')
    entries={e['id']:e for e in gallery.catalog(table,entities,art) if e['id'] in ids}
    assert len(entries)==3;quarters=0;high_floors=0
    for e in entries.values():
        proof=e['native_current_evidence'];row=proof['record'];lift=proof['native_surface_audit']['common_vertical_lift'];assert lift==1
        fragments={(v['x'],v['y'],v['z']):v for v in e['fragments']}
        visible=sum(v['visible'] for q in row['source_surface_columns'] for v in q['after']);assert len(fragments)==visible
        for q in row['source_surface_columns']:
            quarters+=1
            if q['feet']==36:
                high_floors+=1;assert (q['x'],35+lift,q['z']) in fragments
        if row['map']=='EM_ROUTE119' and row['y']==3:assert e['bounds']==[36,41,38]
    assert quarters==1972 and high_floors>0
