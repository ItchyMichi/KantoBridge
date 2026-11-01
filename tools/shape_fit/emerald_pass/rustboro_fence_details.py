"""Overlapping close sections of the two long Rustboro fence components."""
import copy,hashlib
from tools.model_gallery import write_json
from tools.shape_fit.emerald_pass.review import ROOT,read,entry_hash
OUT=ROOT/'local/model-gallery/emerald-pass-study/rustboro-fence-details'
PARENTS={'emerald_rustboro_ornamental_fence_01':((0,31),(30,52)),
         'emerald_rustboro_ornamental_fence_03':((0,30),(29,47))}

def generate(parents):
    entities=[];evidence=[]
    for parent in parents:
        ident=parent['id'];covered=set();parts=[]
        membership={(x,z) for z,row in enumerate(parent['layout']) for x,key in enumerate(row) if key is not None}
        for number,(start,end) in enumerate(PARENTS[ident],1):
            section=copy.deepcopy(parent);section['id']=ident+f'_detail_{number}';section['layout']=copy.deepcopy(parent['layout'][start:end]);section.pop('placements',None)
            if 'reference_layout' in parent:section['reference_layout']=copy.deepcopy(parent['reference_layout'][start:end])
            section['occurrences']=[dict(p,y=p['y']+start) for p in parent.get('occurrences',[])]
            section['kind']='source component detail section';section['note']=f'Overlapping close detail {number}/2 of complete fence {ident}; parent source rows {start}..{end-1}. The full component remains registered. This section is not a separate whole fence.'
            section['component_parent']=ident;section['component_source_row_range']=[start,end]
            own={(x,z+start) for z,row in enumerate(section['layout']) for x,key in enumerate(row) if key is not None};covered.update(own);parts.append(own);entities.append(section)
        assert covered==membership and len(parts[0]&parts[1])==1
        evidence.append({'parent_id':ident,'parent_definition_sha256':entry_hash(parent),'parent_source_membership':sorted(map(list,membership)),
            'section_ids':[ident+'_detail_1',ident+'_detail_2'],'shared_source_cells':sorted(map(list,parts[0]&parts[1])),
            'coverage':'Union equals the exact complete parent membership. One straight fence source cell overlaps; no source member is omitted or added.'})
    return entities,evidence

def main():
    OUT.mkdir(exist_ok=True)
    if not (OUT/'parent-preconditions.json').exists():
        definitions=read(ROOT/'tools/shape_fit/entities/emerald.json');write_json(OUT/'parent-preconditions.json',[e for e in definitions if e['id'] in PARENTS])
    parents=read(OUT/'parent-preconditions.json');assert len(parents)==2;entities,evidence=generate(parents)
    write_json(OUT/'entities.json',entities);write_json(OUT/'source-evidence.json',evidence)
    write_json(OUT/'integration-manifest.json',{'additive_entities':4,'removed_entities':0,'geometry_changes':0,'purpose':'Complementary overlapping detail views; both complete parent fence models remain active.',
        'files':{name:hashlib.sha256((OUT/name).read_bytes()).hexdigest() for name in ('parent-preconditions.json','entities.json','source-evidence.json')},'actual_minecraft_visual_qa':'pending for four detail sections; full parent capture remains separate'})
    print(hashlib.sha256((OUT/'integration-manifest.json').read_bytes()).hexdigest())
if __name__=='__main__':main()
