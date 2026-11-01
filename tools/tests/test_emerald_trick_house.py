import base64
import numpy as np
from tools.shape_fit.emerald_pass import trick_house as p,review
from tools.shape_fit.blue_pass.common import decode


def test_every_original_has_a_semantic_decision_and_missing_static_walls_have_exact_solid_sources():
    baseline=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE);patch,rows=p.generate(baseline,art)
    assert len(rows)==61 and len(patch)==54
    assert {r['decision'] for r in rows}=={'changed','retained','added'}
    for row in rows:
        key=row['key'];assert row['reason'] and row['label']
        if row['decision']=='added':
            assert key in p.MISSING_WOOD and key not in baseline['cells']
            assert row['baseline_absent'] is True and row['baseline_entry_sha256'] is None
            for place in row['occurrences']:
                m=art.maps[place['map']];raw=base64.b64decode(m['cells']);assert raw[(place['y']*m['w']+place['x'])*4+2]==7
        elif row['decision']=='changed':review.validate_cell(key,baseline['cells'][key],patch[key])


def test_junctions_reach_only_their_named_edges_and_top_paint_is_not_extruded_on_sides():
    art=p.c.EmeraldArt(p.c.BUNDLE);sources={r['position']:r['key'] for r in review.read(p.STUDY/'source-evidence.json')}
    for n,joins in p.JOINS.items():
        m=p.gold_wall(art.cell(sources[n],'BAKED'),joins)
        for letter,edge in [('W',m.occ[:14,:,0]),('E',m.occ[:14,:,-1]),('N',m.occ[:14,0,:]),('S',m.occ[:14,-1,:])]:assert bool(edge.any())==(letter in joins)
        assert len(np.unique(m.paint[:14][m.occ[:14]]))<=2
        assert np.array_equal(m.occ[0],m.occ[13])


def test_walk_projections_are_exact_horizontal_native_paint_and_story_pairs_are_proven():
    art=p.c.EmeraldArt(p.c.BUNDLE);table=review.read(review.BASELINE);patch,rows=p.generate(table,art)
    for row in rows:
        if row['source_ordinal'] not in p.FLOORS:continue
        entry=patch[row['key']];o,paint=decode(entry)
        assert o[0].all() and not o[1:].any()
        assert np.array_equal(paint[0],np.arange(256,dtype=np.uint8).reshape(16,16))
        assert not entry.get('walk') and not entry.get('on')
    proof=p.story_projection_evidence(art);assert len(proof['rows'])==2
    assert all(len(r['story_sites'])==10 and r['raw_occurrences']==[] for r in proof['rows'])


def test_pillar_pair_and_each_authored_prop_are_connected_without_floor_backings():
    art=p.c.EmeraldArt(p.c.BUNDLE);table=review.read(review.BASELINE);patch,rows=p.generate(table,art)
    for row in rows:
        if row['decision']=='retained' or row['source_ordinal']==3:continue
        o,paint=decode(patch[row['key']]);points=set(map(tuple,np.argwhere(o)));seen={next(iter(points))};todo=list(seen)
        while todo:
            q=todo.pop()
            for a in range(3):
                for step in (-1,1):
                    n=list(q);n[a]+=step;n=tuple(n)
                    if n in points and n not in seen:seen.add(n);todo.append(n)
        assert points==seen,row['label']
    by={r['source_ordinal']:r for r in rows};upper=patch[by[3]['key']];base=patch[by[6]['key']]
    assert upper['on']==by[6]['key'];a,_=decode(upper);b,_=decode(base);assert np.any(a[0]&b[-1])
    for key in p.MISSING_WOOD:
        o,paint=decode(patch[key]);colors=np.asarray(art.cell(key,'BAKED').convert('RGB'))[paint//16,paint%16]
        assert not np.any((colors[...,1]>colors[...,0])&o),key


def test_actual_wholes_keep_true_original_native_walls_and_floor_only_entrance():
    import hashlib
    from tools import model_gallery as gallery
    if not (p.OUT/'runtime-proof.json').exists():return
    entities=review.read(p.OUT/'entities.json');art=p.c.EmeraldArt(p.c.BUNDLE);table=review.read(p.OUT/'fitted-proof-current.json');old=review.read(review.BASELINE);ids={e['id'] for e in entities}
    assert len(ids)==8
    current={e['id']:e for e in gallery.catalog(table,entities,art) if e['id'] in ids};before={e['id']:e for e in gallery.catalog(old,gallery.baseline_entities(entities,hashlib.sha256(review.BASELINE.read_bytes()).hexdigest()),art) if e['id'] in ids}
    assert len(current)==len(before)==8
    entrance=current['emerald_trick_house_entrance'];assert entrance['bounds']==[4,1,2] and len(entrance['fragments'])==8
    assert entrance['fragments']==before['emerald_trick_house_entrance']['fragments']
    wall=current['emerald_trick_house_wood_partition'];assert wall['bounds'][1]==3
    assert before['emerald_trick_house_wood_partition']['bounds']==wall['bounds']
    assert any(f['key'].startswith('cube:') and f['y']>0 for f in before['emerald_trick_house_wood_partition']['fragments'])
    assert all(f['key'].startswith('cell:') for f in wall['fragments'] if f['y']>0)
    assert before['emerald_trick_house_wood_upper_interface']['bounds'][1]==10
    assert current['emerald_trick_house_wood_upper_interface']['bounds'][1]==3
    assert all(e['current_native_fragments']['source_cell_hashes'] for e in entities)
