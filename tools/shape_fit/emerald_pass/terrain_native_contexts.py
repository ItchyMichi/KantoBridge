"""Bind complete Trainer Hill/station contexts to actual native floors and fitted template carriers."""
from __future__ import annotations
import copy,hashlib,re
from tools import model_gallery as gallery
from tools.shape_fit.emerald_pass import review
from tools.shape_fit.emerald_pass.qa10_wholes import canonical_hash

OUT=review.ROOT/'local/model-gallery/emerald-pass-study/terrain-building-contexts'

def lifts(row,field):
    found={}
    for f in row[field]:
        m=re.fullmatch(r'cell:0\|([0-9,]+)\|-?\d+\|([01]),([01]),([01])\|v=.+',f['key'])
        if m and int(m[3])!=f['y']%2:
            key='g3q/'+m[1].replace(',','-');found[f['x'],f['z'],key]={'x':f['x'],'z':f['z'],'base_y':f['y']-int(m[3]),'key':key}
    return list(found.values())

def generate(out=OUT,names=None,proof_name='runtime-proof.json'):
    proof=out/proof_name;runtime=review.read(proof);snapshot=out/'fitted-proof-current.json'
    if hashlib.sha256(snapshot.read_bytes()).hexdigest()!=runtime['current_table_sha256']:raise ValueError('Native context table differs from actual fixture')
    table=review.read(snapshot);art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');entities=[]
    for original in runtime['occurrences']:
        row=copy.deepcopy(original)
        if names:row['id']=names[row['id']]
        layout=row['layout'];common={'occurrence':{k:row[k] for k in ('map','x','y')},'source_layout':layout}
        for field,target in [('original_baseline_fragments','original_fitted_lifts'),('corrected_fragments','current_fitted_lifts')]:
            value=lifts(row,field)
            if value:row[target]=value
        row['current_source_cell_hashes']={k:canonical_hash(table['cells'].get(k)) for k in sorted({k for line in layout for k in line})}
        row['current_source_template_hashes']=gallery.native_source_template_hashes(table,{'descriptor':common,'record':row},art)
        path=out/'native-proofs'/(row['id']+'.json');gallery.write_json(path,{'original_baseline_sha256':runtime['original_baseline_sha256'],'current_table_sha256':runtime['current_table_sha256'],'parent_runtime_evidence':{'path':proof.relative_to(review.ROOT).as_posix(),'sha256':hashlib.sha256(proof.read_bytes()).hexdigest()},'occurrences':[row]})
        common.update(evidence=path.relative_to(review.ROOT).as_posix(),evidence_sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        entities.append({'id':row['id'],'kind':'actual native building and terrain context','layout':layout,
            'baseline_native_fragments':dict(common,baseline_sha256=runtime['original_baseline_sha256']),
            'current_native_fragments':dict(common,current_table_sha256=runtime['current_table_sha256'],source_cell_hashes=row['current_source_cell_hashes'],source_template_hashes=row['current_source_template_hashes']),
            'occurrences':[dict(common['occurrence'],palette='BAKED')],
            'note':'Complete source-bound building interface: original native columns versus current exact template carriers, unchanged excluded native terrain, and all actual native floors. Actual Minecraft review remains required.'})
    return entities

if __name__=='__main__':
    entities=generate();gallery.write_json(OUT/'entities.json',entities)
    art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');table=review.read(OUT/'fitted-proof-current.json');baseline=review.read(review.BASELINE);ids={e['id'] for e in entities}
    now={e['id']:e for e in gallery.catalog(table,entities,art) if e['id'] in ids};before={e['id']:e for e in gallery.catalog(baseline,gallery.baseline_entities(entities,hashlib.sha256(review.BASELINE.read_bytes()).hexdigest()),art) if e['id'] in ids}
    assert set(now)==set(before)==ids
    gallery.write_json(OUT/'catalog-check.json',{'entities_sha256':hashlib.sha256((OUT/'entities.json').read_bytes()).hexdigest(),'entries':[{'id':i,'before_bounds':before[i]['bounds'],'current_bounds':now[i]['bounds'],'before_fragments':len(before[i]['fragments']),'current_fragments':len(now[i]['fragments']),'templates':len(now[i].get('templates',[]))} for i in sorted(ids)],'visual_approval':False})
    print('Validated',len(entities),'actual source contexts')
