"""Bind source-specific native surface proofs without inventing floors or moving elevations."""
from __future__ import annotations
import argparse, copy, hashlib
from tools import model_gallery as gallery
from tools.shape_fit.emerald_pass import review
from tools.shape_fit.emerald_pass.qa10_wholes import canonical_hash

STUDY=review.ROOT/'local/model-gallery/emerald-pass-study'
CONTEXT_NAMES={
    ('bridge-native-surfaces','EM_ROUTE110',18,5):'Route 110 northern bridge junction and ramp approaches',
    ('bridge-native-surfaces','EM_ROUTE110',24,20):'Route 110 straight bridge span with both water-side supports',
    ('bridge-native-surfaces','EM_ROUTE110',8,28):'Route 110 southern bridge junction, rails and water-side supports',
    ('acro-source-study','EM_ROUTE119',3,3):'Route 119 northern Acro beam network with its actual raised banks',
    ('acro-source-study','EM_ROUTE119',17,90):'Route 119 southern Acro zigzag and both shore connections',
    ('acro-source-study','EM_SAFARI_ZONE_SOUTH',16,2):'Safari Zone southern Acro zigzag with native water and banks',
}


def generate(folder, *, proof_name='surface-runtime-proof.json', current_name='fitted-surface-current.json'):
    proof_path=folder/proof_name;proof=review.read(proof_path);snapshot=folder/current_name
    if hashlib.sha256(snapshot.read_bytes()).hexdigest()!=proof['current_table_sha256']:
        raise ValueError('native surface proof snapshot changed')
    table=review.read(snapshot);art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');entities=[]
    for original in proof['occurrences']:
        row=copy.deepcopy(original);layout=row['layout'];occurrence={k:row[k] for k in ('map','x','y')}
        prefix='emerald_native_surface_'+folder.name.replace('-','_')
        ident=prefix+'_'+row['map'].lower()+'_'+str(row['x'])+'_'+str(row['y'])
        row['id']=ident;common={'occurrence':occurrence,'source_layout':layout}
        row['current_source_cell_hashes']={key:canonical_hash(table['cells'].get(key)) for key in sorted({k for line in layout for k in line})}
        row['current_source_template_hashes']=gallery.native_source_template_hashes(table,{'descriptor':common,'record':row},art)
        path=folder/'native-proofs'/(ident+'.json')
        gallery.write_json(path,{'original_baseline_sha256':proof['original_baseline_sha256'],
            'current_table_sha256':proof['current_table_sha256'],'parent_runtime_evidence':{
                'path':proof_path.relative_to(review.ROOT).as_posix(),'sha256':hashlib.sha256(proof_path.read_bytes()).hexdigest()},
            'occurrences':[row]})
        common.update(evidence=path.relative_to(review.ROOT).as_posix(),evidence_sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        entities.append({'id':ident,'kind':'actual native surface context','layout':layout,
            'baseline_native_fragments':dict(common,baseline_sha256=proof['original_baseline_sha256']),
            'current_native_fragments':dict(common,current_table_sha256=proof['current_table_sha256'],
                source_cell_hashes=row['current_source_cell_hashes'],source_template_hashes=row['current_source_template_hashes']),
            'occurrences':[dict(occurrence,palette='BAKED')],
            'note':(CONTEXT_NAMES.get((folder.name,row['map'],row['x'],row['y']),'Actual native surface context')+'. ' if folder.name in ('bridge-native-surfaces','acro-source-study') else '')+'Actual WorldPlan source surfaces and shape carriers, including native floors and water half-blocks; invisible story/collision records are audit-only. Before and current preserve one common map-relative elevation datum. Configuration: outdoorLevels=all, chainLevels=false, no landscape override. This is source evidence, not visual approval.'})
    return entities


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('folder');ap.add_argument('--validate',action='store_true')
    ap.add_argument('--proof-name',default='surface-runtime-proof.json');ap.add_argument('--current-name',default='fitted-surface-current.json');a=ap.parse_args()
    folder=(STUDY/a.folder).resolve()
    if not folder.is_relative_to(STUDY.resolve()):raise ValueError('surface context output must stay within study')
    if any(__import__('pathlib').Path(v).name!=v for v in (a.proof_name,a.current_name)):raise ValueError('proof and snapshot must be local filenames')
    entities=generate(folder,proof_name=a.proof_name,current_name=a.current_name);gallery.write_json(folder/'native-entities.json',entities)
    if a.validate:
        art=gallery.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');current=review.read(folder/a.current_name);baseline=review.read(review.BASELINE);ids={e['id'] for e in entities}
        now={e['id']:e for e in gallery.catalog(current,entities,art) if e['id'] in ids}
        before={e['id']:e for e in gallery.catalog(baseline,gallery.baseline_entities(entities,hashlib.sha256(review.BASELINE.read_bytes()).hexdigest()),art) if e['id'] in ids}
        if set(now)!=set(before) or set(now)!=ids:raise ValueError('incomplete actual surface catalogs')
        gallery.write_json(folder/'catalog-check.json',{'entries':[{'id':i,'before_bounds':before[i]['bounds'],'current_bounds':now[i]['bounds'],'before_fragments':len(before[i]['fragments']),'current_fragments':len(now[i]['fragments'])} for i in sorted(ids)],'visual_approval':False})
    print('Bound',len(entities),'native surface contexts; visual QA remains pending')


if __name__=='__main__':main()
