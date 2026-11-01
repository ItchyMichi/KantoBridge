import base64
import numpy as np
from tools.shape_fit.emerald_pass import trainer_hill_architecture as p,review
from tools.shape_fit.emerald import parts_model
from tools.shape_fit.blue_pass.common import decode
from tools.tests.test_emerald_trainer_hill_furniture import connected


def test_all31_source_cells_preserve_every_matching_interaction_field():
    cells,rows,buildings,evidence=p.generate();baseline=review.read(review.BASELINE)
    assert len(cells)==len(rows)==31 and {r['source_ordinal'] for r in rows}==p.OWNED
    for row in rows:
        key=row['key'];old=baseline['cells'][key];new=cells[key]
        assert {k:v for k,v in old.items() if k not in review.GEOMETRY}=={k:v for k,v in new.items() if k not in review.GEOMETRY}
        review.validate_cell(key,old,new)
        o,paint=decode(new);assert o.any() and paint.shape==o.shape


def test_all_eight_wholes_match_exact_source_and_keep_every_warp_approach_clear():
    cells,rows,buildings,evidence=p.generate();art=p.g.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald')
    assert len(buildings)==8 and sum(len(r['occurrences']) for r in evidence)==14
    for row in evidence:
        spec=buildings[row['id']];o,c=parts_model.build(spec,np.asarray(art.drawing(row['layout'],'BAKED').convert('RGB')))
        assert o[0].any() and connected(o)
        assert spec['interior'] is True and spec['terrain']==[]
        for at in row['occurrences']:
            raw=art.maps[at['map']];grid=art.map_cells(raw);data=base64.b64decode(raw['cells']);w=len(row['layout'][0]);d=len(row['layout'])
            assert [r[at['x']:at['x']+w] for r in grid[at['y']:at['y']+d]]==row['layout']
            for z in range(d):
                for x in range(w):
                    collision=data[((at['y']+z)*raw['w']+at['x']+x)*4+2]
                    assert collision in (7,114)
                    if collision==114:assert not o[1:24,z*16:z*16+16,x*16:x*16+16].any()
        if row['kind']=='panel':
            assert not o[:,:33].any() and o[:40,33:47].any()
            for y in (9,13,17):assert o[y,46,4:11].all() and o[y,46,37:44].all()
        elif row['kind']=='up':
            assert all(o[h-1,z: z+4,16:32].all() for z,h in ((0,8),(4,6),(8,4),(12,2)))


def test_components_do_not_enable_unrelated_warp_decor_and_closed_lift_is_source_solid_only():
    cells,rows,buildings,evidence=p.generate();by={r['source_ordinal']:r for r in rows}
    assert cells[by[18]['key']]['walk'] is True and not cells[by[20]['key']].get('walk') and not cells[by[48]['key']].get('walk')
    threshold,_=decode(cells[by[18]['key']]);assert not threshold[2:].any()
    lift=next(r for r in evidence if r['kind']=='lift');assert [{k:p[k] for k in ('map','x','y','palette')} for p in lift['occurrences']]==[{'map':'EM_TRAINER_HILL_ENTRANCE','x':16,'y':7,'palette':'BAKED'}]


def test_all_new_templates_satisfy_real_merger_bounds_and_exact_source_metadata():
    cells,rows,buildings,evidence=p.generate()
    for spec in buildings.values():
        metadata={k:v for k,v in spec.items() if k not in ('height','parts')}
        review.validate_building(spec['id'],metadata,spec)
    assert len({r['key'] for r in rows})==31 and all(r['evidence'] for r in rows)
    assert sum(sum(v==114 for line in at['source_collisions'] for v in line) for row in evidence for at in row['occurrences'])==9
