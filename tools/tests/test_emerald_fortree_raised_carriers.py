import copy
import numpy as np
from tools.shape_fit.emerald_pass import fortree_raised_carriers as p,carrier_levels,review
from tools.shape_fit.emerald import parts_model
from tools import model_gallery as g


def test_two_exact_level_witnesses_preserve_source_metadata_and_clear_cross_level_connections():
    patch,rows=p.generate();old=review.read(p.ORIGINAL/'additions.json')['buildings'];art=g.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald')
    assert len(patch)==len(rows)==2
    for row in rows:
        spec=patch[row['id']];assert {k:v for k,v in old[row['id']].items() if k!='parts'}=={k:v for k,v in spec.items() if k not in ('parts','carrierLevels')}
        assert row['original_uniform_refused'] is True
        assert carrier_levels.valid(spec['carrierLevels'],row['actual_source_feet'],spec['parts'])
        o,c=parts_model.build(spec,np.asarray(art.drawing(row['layout'],'BAKED').convert('RGB')))
        levels=spec['carrierLevels']
        for z,line in enumerate(levels):
            for x,value in enumerate(line):
                if value is None:continue
                if x+1<len(line) and line[x+1] is not None and value!=line[x+1]:assert not o[:,z*16:z*16+16,(x+1)*16-1:(x+1)*16+1].any()
                if z+1<len(levels) and levels[z+1][x] is not None and value!=levels[z+1][x]:assert not o[:,(z+1)*16-1:(z+1)*16+1,x*16:x*16+16].any()


def test_level_witness_rejects_mismatch_missing_null_and_parts_crossing_boundary():
    patch,rows=p.generate()
    for row in rows:
        spec=patch[row['id']];actual=row['actual_source_feet'];witness=spec['carrierLevels'];assert not carrier_levels.valid(None,actual,spec['parts'])
        altered=copy.deepcopy(actual);changed=False
        for z,line in enumerate(altered):
            for x,level in enumerate(line):
                if level is not None and not changed:line[x]+=1;changed=True
        assert not carrier_levels.valid(witness,altered,spec['parts'])
        malformed=copy.deepcopy(witness);malformed[0][0]=0;assert not carrier_levels.valid(malformed,actual,spec['parts'])
        crossed=copy.deepcopy(spec['parts']);crossed.append({'shape':'box','x':[0,len(actual[0])*16],'z':[0,len(actual)*16],'y':[0,1]})
        assert not carrier_levels.valid(witness,actual,crossed)
        flat=[[None if level is None else 0 for level in line] for line in actual];assert carrier_levels.valid(witness,flat,spec['parts'])
