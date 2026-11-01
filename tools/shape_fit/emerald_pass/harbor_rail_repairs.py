"""Repair seven source-verified island-harbor rail variants after Minecraft QA."""
from __future__ import annotations
import argparse,base64,copy,hashlib,json
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE,INVENTORY
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.inventory import contact_pages
from tools.shape_fit.emerald_pass.ship_beams import previews

OUT=ROOT/'local/model-gallery/emerald-pass-study/harbor-rail-repairs'
STUDY=ROOT/'local/model-gallery/emerald-pass-study/environment-repairs'
ROLES={4:'horizontal',5:'end-east',6:'east-south',7:'horizontal',8:'west-south',10:'east-north',11:'west-north'}

def rows():
    return [dict(r,source_ordinal=n) for n,r in enumerate(read(INVENTORY/'general__island_harbor/entries.json'),1) if n in ROLES]

def rail(sprite,role):
    m=Model();dark=material(sprite,(66,74,107));gray=material(sprite,(140,148,165));light=material(sprite,(222,222,222))
    def horizontal(x0,x1):
        m.box((x0,x1),(11,13),(7,9),light);m.box((x0,x1),(3,5),(7,9),gray)
        for x in range(x0+1,x1,5):m.box((x,min(x+2,x1)),(0,12),(7,9),dark)
    def vertical(z0,z1):
        m.box((7,9),(11,13),(z0,z1),light);m.box((7,9),(3,5),(z0,z1),gray)
        for z in range(z0+1,z1,5):m.box((7,9),(0,12),(z,min(z+2,z1)),dark)
    if role in ('horizontal','end-east'):
        horizontal(0,16)
        if role=='end-east':m.box((14,16),(0,13),(6,10),gray)
    else:
        horizontal(7,16) if role.startswith('east') else horizontal(0,9)
        vertical(8,16) if role.endswith('south') else vertical(0,9)
        m.box((7,9),(0,12),(7,9),dark)
    return m

def generate(art,baseline,preconditions):
    owned=rows();evidence={r['key']:r for r in read(STUDY/'entries.json')};updates={};ledger=[]
    for r in owned:
        k=r['key'];n=r['source_ordinal'];old=baseline['cells'][k];m=rail(art.cell(k,'BAKED'),ROLES[n]);updates[k]=encode(m.occ,m.paint,preconditions['cells'][k],'hand-emerald-island-harbor-rail')
        places=evidence[k]['occurrences'];assert {p['collision'] for p in places}=={7}
        ledger.append({'key':k,'kind':'cell','decision':'changed','label':'Island harbor open guardrail '+ROLES[n],
            'family':'Island harbor guardrails','baseline_entry_sha256':entry_hash(old),'previous_entry_sha256':entry_hash(preconditions['cells'][k]),
            'source_ordinal':n,'occurrences':places,'reason':'Minecraft QA07/08 exposed solid tapered slabs filling the rail openings. Both complete Birth Island and Navel Rock harbor maps show the same thin metal guardrails: two horizontal rails, narrow vertical balusters and exact corner turns around the dock. Build the source-proven rail centerline with grounded posts, open gaps and native metal paint, matching the established shared harbor rail height13 and centerline8. Preserve SOLID7 classification and all entry metadata. Source floor and water pixels do not become metal.',
            'evidence':[str(STUDY/'harbor.png'),str(STUDY/'contexts/page-08.png'),str(STUDY/'contexts/page-11.png'),str(OUT/'source-evidence.json'),str(OUT/'preview/assemblies/index.json')],
            'captured_findings':evidence[k]['captured_findings']})
    return updates,ledger

def assemblies(art):
    keys={r['key'] for r in rows()};found={}
    for name in ('EM_BIRTH_ISLAND_HARBOR','EM_NAVEL_ROCK_HARBOR'):
        m=art.maps[name];g=art.map_cells(m);raw=base64.b64decode(m['cells']);todo={(x,y) for y,row in enumerate(g) for x,k in enumerate(row) if k in keys}
        while todo:
            queue=[min(todo)];component=set()
            while queue:
                x,z=queue.pop()
                if (x,z) not in todo:continue
                todo.remove((x,z));component.add((x,z));queue.extend(((x-1,z),(x+1,z),(x,z-1),(x,z+1)))
            x0=min(x for x,z in component);z0=min(z for x,z in component);x1=max(x for x,z in component)+1;z1=max(z for x,z in component)+1
            layout=[[g[z][x] if (x,z) in component else None for x in range(x0,x1)] for z in range(z0,z1)]
            ident='emerald_harbor_rail_'+hashlib.sha256(json.dumps(layout,separators=(',',':')).encode()).hexdigest()[:12]
            e=found.setdefault(ident,{'id':ident,'kind':'object (model as one)','note':'Complete island harbor guardrail run around dock' if z0<12 else 'Complete southern island harbor guardrail run',
                'layout':layout,'placements':[{'source':[x-x0,z-z0],'offset':[x-x0,0,z-z0]} for x,z in sorted(component)],'occurrences':[]})
            e['occurrences'].append({'map':name,'x':x0,'y':z0,'map_cells_sha256':hashlib.sha256(raw).hexdigest()})
    assert len(found)==3
    return list(found.values())

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    art=EmeraldArt(BUNDLE);baseline=read(BASELINE);pre=read(STUDY/'preconditions.json');updates,ledger=generate(art,baseline,pre);entities=assemblies(art)
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('source-evidence.json',{'original_baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'cells':[{k:v for k,v in r.items() if k in ('key','previous_entry_sha256','occurrences','captured_findings')} for r in ledger],'groups':entities,'limits':'Geometry-only repair preserves original SOLID7 source and metadata. Local previews require fresh Minecraft capture/visual QA before approval.'})]:write_json(OUT/name,value)
    if args.previews:
        current=copy.deepcopy(baseline);current['cells'].update(updates);contact_pages('current',rows(),current,art,OUT/'preview');previews(entities,current,art,OUT/'preview/assemblies')
    print('7 changed rails;3 whole source runs;6 exact occurrences;metadata unchanged')
if __name__=='__main__':main()
