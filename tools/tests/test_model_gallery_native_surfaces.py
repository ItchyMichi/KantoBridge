import copy
import hashlib
import pytest
from tools import model_gallery as gallery
from tools.shape_fit.emerald_pass import review
from tools.shape_fit.emerald_pass.archive import _combined_gallery


def catalogs():
    folder=review.ROOT/'local/model-gallery/emerald-pass-study/shore-bank-repairs'
    if not (folder/'native-entities.json').exists():pytest.skip('authorized shoreline surface proof absent')
    entities=review.read(folder/'native-entities.json');current=review.read(folder/'fitted-surface-current.json');old=review.read(review.BASELINE)
    art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');ids={e['id'] for e in entities}
    before={e['id']:e for e in gallery.catalog(old,gallery.baseline_entities(entities,hashlib.sha256(review.BASELINE.read_bytes()).hexdigest()),art) if e['id'] in ids}
    after={e['id']:e for e in gallery.catalog(current,entities,art) if e['id'] in ids}
    return entities,current,old,art,before,after


def test_source_bound_surfaces_keep_actual_water_and_one_datum_in_both_catalogs_and_archive():
    entities,current,old,art,before,after=catalogs();assert len(before)==len(after)==3
    quarters=water=0
    for ident,now in after.items():
        prior=before[ident];assert now['bounds']==prior['bounds']
        a=now['native_current_evidence'];b=prior['native_baseline_evidence'];row=a['record'];quarters+=a['native_surface_audit']['source_quarters']
        assert a['native_surface_audit']['common_vertical_lift']==b['native_surface_audit']['common_vertical_lift']
        lift=a['native_surface_audit']['common_vertical_lift']
        for entry,revision in ((now,'after'),(prior,'before')):
            visible=[(q,block) for q in row['source_surface_columns'] for block in q[revision] if block['visible']]
            assert len(entry['fragments'])==len(visible)
            actual={(f['x'],f['y'],f['z']):f for f in entry['fragments']}
            for q,block in visible:
                f=actual[q['x'],block['y']+lift,q['z']]
                if block['code_kind']==2:
                    water+=1;assert f['key']==f"slab:0|{block['tile']}|8"
            invisible=sum(not block['visible'] for q in row['source_surface_columns'] for block in q[revision])
            field='native_current_evidence' if revision=='after' else 'native_baseline_evidence'
            assert len(entry[field]['native_surface_audit']['invisible_carriers'])==invisible
        archived=_combined_gallery([], [{'entries':[now]}],set(current['cells']),{v['id'] for v in current['buildings']})['entries'][0]
        assert archived['native_baselines']['gallery-0']['native_current_evidence']['native_surface_audit']==a['native_surface_audit']
    assert quarters==512 and water>100


def test_surface_mode_cannot_fall_back_to_floor_only_or_accept_incomplete_visible_carriers():
    entities,current,old,art,before,after=catalogs();value=next(iter(after.values()))['native_current_evidence'];row=copy.deepcopy(value['record']);descriptor=value['descriptor']
    source,raw,grid=gallery.native_assembly_source(value,art)
    for defect in ('missing_quarter','invisible_floor','invalid_mode'):
        bad=copy.deepcopy(row)
        if defect=='missing_quarter':bad['source_surface_columns'].pop()
        elif defect=='invalid_mode':bad['surface_evidence_mode']='invented'
        else:
            next(b for q in bad['source_surface_columns'] for b in q['after'] if b['code_kind'] in (1,2))['visible']=False
        with pytest.raises(ValueError,match='native surface'):
            gallery.native_surface_fragments(bad,descriptor,current,source,raw,grid,0,True,{}, {})


def test_exact_acro_raised_network_keeps_all_source_surfaces_above_old_height40_limit():
    folder=review.ROOT/'local/model-gallery/emerald-pass-study/acro-source-study'
    if not (folder/'native-entities.json').exists():pytest.skip('authorized raised Acro proof absent')
    entities=review.read(folder/'native-entities.json');table=review.read(folder/'fitted-surface-current.json');baseline=review.read(review.BASELINE)
    art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');ids={e['id'] for e in entities}
    before={e['id']:e for e in gallery.catalog(baseline,gallery.baseline_entities(entities,hashlib.sha256(review.BASELINE.read_bytes()).hexdigest()),art) if e['id'] in ids}
    current={e['id']:e for e in gallery.catalog(table,entities,art) if e['id'] in ids}
    assert len(before)==len(current)==3
    tall=next(e for e in current.values() if e['bounds'][1]>40)
    assert tall['bounds']==[36,41,38] and before[tall['id']]['bounds']==[36,40,38]
    for e in (tall,before[tall['id']]):
        evidence=e.get('native_current_evidence',e.get('native_baseline_evidence'));revision='after' if 'native_current_evidence' in e else 'before'
        visible=[b for q in evidence['record']['source_surface_columns'] for b in q[revision] if b['visible']]
        assert len(e['fragments'])==len(visible)
        assert evidence['native_surface_audit']['common_vertical_lift']==1
    assert max(f['y'] for f in tall['fragments'])==40
