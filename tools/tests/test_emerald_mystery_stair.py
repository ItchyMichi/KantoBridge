import numpy as np
from tools.shape_fit.emerald_pass import mystery_stair as p,review
from tools.shape_fit.blue_pass.common import decode


def test_stair_keeps_warp_metadata_and_clears_the_source_confirmed_black_backplane():
    table=review.read(review.BASELINE);art=p.c.EmeraldArt(p.c.BUNDLE);entry,row=p.generate(table,art)
    review.validate_cell(p.KEY,table['cells'][p.KEY],entry)
    assert entry['walk'] is True and entry['from']=='warp'
    o,paint=decode(entry)
    assert not o[:13,:3,2:14].any()
    assert o[13:16,:3,:].all()
    assert [int(np.flatnonzero(o[:,z,8])[-1])+1 for z in (4,7,10,14)]==[8,6,4,2]
    assert o[0,15,8] and not o[8,15,8]
    assert row['occurrences']==art.locations[p.KEY] and len(row['occurrences'])==1


def test_four_treads_and_jambs_form_one_connected_grounded_structure():
    art=p.c.EmeraldArt(p.c.BUNDLE);m=p.model(art.cell(p.KEY,'BAKED'));o=m.occ
    points=set(map(tuple,np.argwhere(o)));seen={next(iter(points))};todo=list(seen)
    while todo:
        q=todo.pop()
        for axis in range(3):
            for step in (-1,1):
                n=list(q);n[axis]+=step;n=tuple(n)
                if n in points and n not in seen:seen.add(n);todo.append(n)
    assert seen==points and o[0].any()


def test_repaired_actual_whole_preserves_original_baseline_and_changes_only_center_shapes():
    import hashlib
    from tools import model_gallery as gallery
    if not (p.OUT/'entities.json').exists():return
    old=review.read(p.OUT/'original-entity.json');entity=review.read(p.OUT/'entities.json')[0]
    assert entity['baseline_native_fragments']==old['baseline_native_fragments']
    table=review.read(p.OUT/'fitted-proof-current.json');art=p.c.EmeraldArt(p.c.BUNDLE)
    now=next(e for e in gallery.catalog(table,[entity],art) if e['id']==entity['id'])
    before=next(e for e in gallery.catalog(review.read(review.BASELINE),gallery.baseline_entities([entity],hashlib.sha256(review.BASELINE.read_bytes()).hexdigest()),art) if e['id']==entity['id'])
    assert len(now['fragments'])==len(before['fragments'])==40
    changed=[b for a,b in zip(before['fragments'],now['fragments']) if a!=b]
    assert len(changed)==8 and all(f['x'] in (2,3) and f['z'] in (2,3) for f in changed)


def test_binding_is_reproducible_and_rejects_an_unproved_candidate(tmp_path,monkeypatch):
    import shutil
    import pytest
    out=tmp_path/'mystery-stair-binding'
    out.mkdir(parents=True)
    for name in ('runtime-proof.json','updates.json','fitted-proof-current.json','original-entity.json'):
        shutil.copyfile(p.OUT/name,out/name)
    first=p.bind_actual_whole(out)
    assert p.bind_actual_whole(out)==first
    update=p.review.read(out/'updates.json');update['cells'][p.KEY]['walk']=False
    p.write_json(out/'updates.json',update)
    with pytest.raises(ValueError,match='different candidate'):p.bind_actual_whole(out)
