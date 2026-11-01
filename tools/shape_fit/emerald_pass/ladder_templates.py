"""Exact cave ladder pairs: whole inclined frames preserving native warp cells."""
from __future__ import annotations
import argparse,base64,hashlib,json
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.emerald.building_trials import box,paint
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.environment_templates import tiles_for,swatch
from tools.shape_fit.emerald_pass import generic_appliance_templates as previews

OUT=ROOT/'local/model-gallery/emerald-pass-study/ladder-templates'
STUDY=ROOT/'local/model-gallery/emerald-pass-study/environment-repairs'
FREE_UPPER='g3q/20670-20671-20672-20673'

def source_groups(art):
    out=[]
    for r in read(STUDY/'ladder-pairs.json'):
        layout=[[r['key']],[r['on']]];ident='emerald_object_cave_ladder_'+hashlib.sha256(json.dumps(layout,separators=(',',':')).encode()).hexdigest()[:12]
        places=[]
        for name,x,y,a,lower,b in r['pairs']:
            assert (a,b)==(7,114) and lower==r['on'];m=art.maps[name];g=art.map_cells(m);raw=base64.b64decode(m['cells'])
            assert [[g[y][x]],[g[y+1][x]]]==layout
            places.append({'map':name,'x':x,'y':y,'palette':'BAKED','map_cells_sha256':hashlib.sha256(raw).hexdigest()})
        out.append({'id':ident,'family':'inclined_cave_ladder','layout':layout,'source_kinds':[[7],[114]],'occurrences':places,'wall_backing':r['key']!=FREE_UPPER})
    for key in {k for g in out for row in g['layout'] for k in row}:
        covered={(p['map'],p['x'],p['y']+z) for g in out for p in g['occurrences'] for z,row in enumerate(g['layout']) if row[0]==key}
        assert covered=={(p['map'],p['x'],p['y']) for p in art.locations[key]}
    return out

def geometry(d,g):
    light=swatch(d,(255,255,255),(3,0,14,32));edge=swatch(d,(132,132,140),(3,0,14,32));dark=swatch(d,(66,66,82),(3,0,14,32));parts=[]
    if g['wall_backing']:
        # Only the source's leftmost rock strip supplies backdrop material;
        # the projected ladder itself is never printed a second time on stone.
        strip=[0,1,3,16];top=swatch(d,(173,156,140),(0,0,3,16));side=swatch(d,(99,90,99),(0,0,3,16))
        parts.append(box([0,16],[0,10],[0,24],paint(strip,top,side,side)))
    # Two continuous leaning rails. One-voxel y bands overlap in z when the
    # lean advances, so neither rails nor rungs become detached diagonal cards.
    for y in range(30):
        z=20-(2*y//5)
        for x in (3,11):parts.append(box([x,x+2],[z,z+2],[y,y+1],paint(light,light,edge,dark)))
    for y in range(3,29,4):
        z=20-(2*y//5);parts.append(box([5,11],[z,z+2],[y,y+2],paint(light,light,edge,dark)))
    return parts

def generate(art,baseline):
    groups=source_groups(art);ts=[];added=[]
    for g in groups:
        d=art.drawing(g['layout'],'BAKED').convert('RGB');t={'id':g['id'],'atlas':'g3q','tiles':tiles_for(g['layout']),'height':32,'interior':True,'terrain':[],'parts':geometry(d,g)};ts.append(t)
        added.append({'key':t['id'],'kind':'building','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':entry_hash(t),
            'label':'Complete inclined cave ladder '+('against native stone' if g['wall_backing'] else 'on native yellow floor'),'family':'Complete source cave ladder',
            'source_pixel_mode':'RGB','source_pixel_sha256':hashlib.sha256(d.tobytes()).hexdigest(),'occurrences':g['occurrences'],
            'reason':'The existing on metadata does not stack over a WALK warp: actual source keeps the upper SOLID fitted wall and lower decorative ladder at separate floor positions. Match the exact two-row source as one coherent inclined frame with joined rails/rungs and a grounded foot in the southern noncolliding warp cell. Native stone backing is present only where the upper source shows a cliff face; the plain-floor Mirage variant remains an open frame. Preserve all source kinds, warp tags and old cell entries.',
            'evidence':[str(STUDY/'ladder-runtime-proof.json'),str(OUT/'source-evidence.json'),str(OUT/'inspection/index.json')]})
    ledger=[]
    for key in sorted({k for g in groups for row in g['layout'] for k in row}):
        ledger.append({'key':key,'kind':'cell','decision':'retained','label':'Source-covered cave ladder '+('foot' if baseline['cells'][key].get('walk') else 'upper section'),
            'family':'Complete source cave ladder','baseline_entry_sha256':entry_hash(baseline['cells'][key]),'occurrences':art.locations[key],
            'whole_templates':[g['id'] for g in groups if key in (g['layout'][0][0],g['layout'][1][0])],
            'reason':'Preserve the original fitted entry and interaction metadata. Every occurrence is covered by an exact complete ladder template; existing auto-on catalog placement is not treated as actual source runtime. Original actual-source native/fitted fragments supply the before companion. This retained fallback is not an approval of the detached individual geometry.',
            'evidence':[str(STUDY/'ladder-runtime-proof.json'),str(OUT/'source-evidence.json'),str(OUT/'inspection/index.json')]})
    return ts,added,ledger,groups

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    art=EmeraldArt(BUNDLE);base=read(BASELINE);ts,added,ledger,groups=generate(art,base)
    for n,v in [('updates.json',{'cells':{},'buildings':{}}),('additions.json',{'cells':{},'buildings':{t['id']:t for t in ts}}),('added-ledger.json',added),('ledger.json',ledger),('source-evidence.json',{'groups':groups,'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'diagnostic_sha256':hashlib.sha256((STUDY/'ladder-runtime-proof.json').read_bytes()).hexdigest(),'limits':'Geometry/source candidate. Current actual-source claim/carrier proof and manual Minecraft comparison must be completed separately.'})]:write_json(OUT/n,v)
    if args.previews:
        old=previews.OUT
        try:previews.OUT=OUT;previews.render(art,ts,groups)
        finally:previews.OUT=old
    print(len(ts),'ladder patterns;',len(ledger),'existing cells;',sum(len(g['occurrences']) for g in groups),'placements;runtime proof pending')
if __name__=='__main__':main()
