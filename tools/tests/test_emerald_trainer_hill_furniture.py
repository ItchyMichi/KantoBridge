import base64
import numpy as np
from tools.shape_fit.emerald_pass import trainer_hill_furniture as p, review
from tools.shape_fit.blue_pass.common import decode


def context():
    art=p.gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald')
    patch,rows,metadata=p.generate()
    return art,patch,{r['source_ordinal']:r for r in rows},metadata


def connected(o):
    points=set(map(tuple,np.argwhere(o)));seen={next(iter(points))};todo=list(seen)
    while todo:
        q=todo.pop()
        for axis in range(3):
            for step in (-1,1):
                v=list(q);v[axis]+=step;v=tuple(v)
                if v in points and v not in seen:seen.add(v);todo.append(v)
    return points==seen


def test_exact_source_membership_and_four_evidenced_metadata_transitions():
    art,patch,rows,metadata=context();baseline=review.read(review.BASELINE)
    assert set(rows)==p.OWNED and len(rows)==len(patch)==33
    assert {m['key'] for m in metadata}=={rows[n]['key'] for n in (25,60,61,62)}
    for n,row in rows.items():
        old=baseline['cells'][row['key']];new=patch[row['key']]
        a={k:v for k,v in old.items() if k not in review.GEOMETRY};b={k:v for k,v in new.items() if k not in review.GEOMETRY}
        if n in (25,60):a['counter']=True
        if n in (61,62):a['on']=rows[p.UPPER[n]]['key']
        assert a==b and row['label'] and row['reason']
        for q in row['occurrences']:
            raw=art.maps[q['map']];data=base64.b64decode(raw['cells']);collision=data[(q['y']*raw['w']+q['x'])*4+2]
            assert collision==q['collision']
            assert art.map_cells(raw)[q['y']][q['x']]==row['key']
            if n in (25,60):assert collision==144
            if n in (61,62):assert collision==7 and art.map_cells(raw)[q['y']+1][q['x']]==rows[p.UPPER[n]]['key']


def test_devices_and_monitor_form_grounded_connected_wholes_with_exact_slice_seams():
    art,patch,rows,_=context()
    for top,base in ((21,31),(34,41),(42,36)):
        a,_=decode(patch[rows[top]['key']]);b,_=decode(patch[rows[base]['key']]);o=np.concatenate((b,a),axis=0)
        assert connected(o) and o[0].any() and np.any(b[-1]&a[0])
        if top!=42:assert not o[:,0,:].any() and not o[:,-1,:].any()
    monitor=np.zeros((32,16,32),bool)
    for n,x,y in ((61,0,16),(62,16,16),(63,0,0),(64,16,0)):
        o,_=decode(patch[rows[n]['key']]);monitor[y:y+16,:,x:x+16]=o
    assert connected(monitor) and monitor[0].any()
    assert monitor[:,3:10].any() and not monitor[:,:3].any() and not monitor[:,10:].any()
    assert np.any(monitor[:,:,15]&monitor[:,:,16])


def test_counter_tops_share_height_and_recovery_tray_is_open_and_connected():
    art,patch,rows,_=context()
    for n in p.COUNTERS:
        o,_=decode(patch[rows[n]['key']]);assert connected(o) and o[0].any()
        assert o[7,3:13,3:13].all()
        assert not o[9:].any()
        if n not in (25,60):assert not o[8:].any()
    tray=np.zeros((16,32,32),bool)
    for n,x,z in ((22,0,0),(23,16,0),(29,0,16),(30,16,16)):
        o,_=decode(patch[rows[n]['key']]);tray[:,z:z+16,x:x+16]=o
    assert connected(tray) and tray[0].any()
    assert not tray[12:,10:24,6:26].any()
    assert np.any(tray[:,:,15]&tray[:,:,16]) and np.any(tray[:,15]&tray[:,16])


def test_projected_monitor_floor_has_only_source_floor_paint_and_no_new_walk_carrier():
    art,patch,rows,_=context();entry=patch[rows[28]['key']];o,paint=decode(entry)
    assert o[0].all() and not o[1:].any() and not entry.get('walk') and not entry.get('on')
    assert (paint[0]//16).min()==10 and (paint[0]//16).max()==15
    assert len(p.assemblies(art))==9


def test_actual_source_catalogs_include_the_full_monitor_and_native_stair_side_floor():
    import hashlib
    if not (p.OUT/'runtime-proof.json').exists():return
    art,patch,rows,_=context();entities=review.read(p.OUT/'entities.json');runtime=review.read(p.OUT/'runtime-proof.json');table=review.read(p.OUT/'fitted-proof-current.json');ids={e['id'] for e in entities}
    assert len(ids)==9 and runtime['headroom']>=4
    assert runtime['candidate_updates_sha256']==hashlib.sha256((p.OUT/'updates.json').read_bytes()).hexdigest()
    after={e['id']:e for e in p.gallery.catalog(table,entities,art) if e['id'] in ids}
    before={e['id']:e for e in p.gallery.catalog(review.read(review.BASELINE),p.gallery.baseline_entities(entities,runtime['original_baseline_sha256']),art) if e['id'] in ids}
    assert set(before)==set(after)==ids
    west=after['emerald_trainer_hill_furniture_west_counter'];proof=west['native_current_evidence']['record'];layout=proof['layout']
    assert proof['y']==7 and layout[0][4:6]==[rows[61]['key'],rows[62]['key']]
    assert proof['source_collisions'][3][0]==147
    assert sum(v['x']<2 and v['z']>=6 for v in proof['source_floor_columns'])==4
    assert all(any(f['x']==x and f['z']==z and f['y']==0 for f in west['fragments']) for x in (0,1) for z in (6,7))
    monitor=after['emerald_trainer_hill_furniture_wall_monitor']
    assert monitor['bounds']==[4,5,4] and all(f['z']>=2 for f in monitor['fragments'] if f['y']>0)
    assert any(f['y']>=3 for f in monitor['fragments'])
