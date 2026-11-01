"""Exact complete room arches, floor-depth dividers, Fortree trunk and counters."""
from __future__ import annotations
import argparse,base64,hashlib,json
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.emerald.building_trials import box,paint,round_part
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE,INVENTORY
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.environment_templates import tiles_for,swatch
from tools.shape_fit.emerald_pass import generic_appliance_templates as previews

OUT=ROOT/'local/model-gallery/emerald-pass-study/generic-architecture-templates'
ARCH={7,8,9,11,12,44,164,165,166,167,169,137,138,139,143,144,145}
DIVIDER={131,146,147,150,153};TRUNK={245,246,253,254};COUNTER={255,256,258,259,270,273,274,275};OWNED=ARCH|DIVIDER|TRUNK|COUNTER

def source_groups(art):
    inv=read(INVENTORY/'building__generic_building/entries.json');key=lambda n:inv[n-1]['key'];groups={}
    def add(family,p,w,h):
        m=art.maps[p['map']];grid=art.map_cells(m);x,y=p['x'],p['y'];raw=base64.b64decode(m['cells']);layout=[line[x:x+w] for line in grid[y:y+h]]
        kinds=[[raw[((y+z)*m['w']+x+c)*4+2] for c in range(w)] for z in range(h)];before=json.loads(json.dumps(layout));original_kinds=json.loads(json.dumps(kinds));mode=None
        if family=='green_stair' and p['map']=='EM_ROUTE110_TRICK_HOUSE_ENTRANCE':
            sites_raw=(BUNDLE/'g3sites/all.json').read_bytes();site=next(s for s in json.loads(sites_raw)['sites'] if s['map']==p['map'] and (s['x'],s['y'])==(x+1,y+1))
            assert site['own']=={'coll':7,'elev':3,'metatile':539}
            opened=next(s for s in site['states'] if s['coll']==114);assert opened['metatile']==537
            layout[1][1]='g3q/'+'-'.join(map(str,art.quarters[m['pair']][537]));kinds[1][1]=114
            mode={'mode':'generic-interior-arch-v1','sites_sha256':hashlib.sha256(sites_raw).hexdigest()}
        sig=hashlib.sha256(json.dumps(layout,separators=(',',':')).encode()).hexdigest()[:12];ident='emerald_object_generic_'+family+'_'+sig
        g=groups.setdefault(ident,{'id':ident,'family':family,'layout':layout,'source_kinds':kinds,'occurrences':[]})
        witness=dict(p,map_cells_sha256=hashlib.sha256(raw).hexdigest())
        if mode:witness.update(built_source=mode,raw_source_layout=before,raw_source_kinds=original_kinds)
        g['occurrences'].append(witness)
    for family,n in [('green_stair',7),('yellow_stair',164),('broken_wall',137)]:
        for p in art.locations[key(n)]:add('yellow_double_stair' if p['map']=='EM_RUSTBORO_CITY_FLAT2_2F' else family,p,5 if p['map']=='EM_RUSTBORO_CITY_FLAT2_2F' else 3,2)
    for p in art.locations[key(245)]:
        g=art.map_cells(art.maps[p['map']]);x,y=p['x'],p['y']
        if y==0 or g[y-1][x]!=key(245):add('indoor_trunk',p,2,4)
    for p in art.locations[key(147)]:
        m=art.maps[p['map']];g=art.map_cells(m);x,y=p['x'],p['y']
        if y==0 or g[y-1][x]==key(147):continue
        start=y-1;end=y;raw=base64.b64decode(m['cells'])
        while end<m['h'] and raw[(end*m['w']+x)*4+2]==7:end+=1
        add('divider',dict(p,y=start),1,end-start)
    for x in (0,5):add('shop_counter',{'map':'EM_FORTREE_CITY_DECORATION_SHOP','x':x,'y':2,'palette':'BAKED'},3,2)
    return list(groups.values())

def arch(d,g):
    w=d.width;style=g['family'];yellow=style.startswith('yellow');broken=style=='broken_wall'
    wall=swatch(d,(222,222,74) if yellow else (214,181,132),(0,5,w,12));top=swatch(d,(255,255,255),(0,1,w,6))
    trim=swatch(d,(140,156,49) if yellow else (90,140,41),(14,10,34,28));parts=[]
    for x in range(0,w,32):
        parts.append(box([x,min(x+16,w)],[24,32],[0,32],paint([x,0,min(x+16,w),32],top,wall,wall)))
    for x in range(16,w,32):
        parts.append(box([x,x+16],[24,32],[24 if broken else 16,32],paint([x,0,x+16,8 if broken else 16],top,wall,wall)))
    if broken:
        # Jagged remains border an open warp; never fill the dark source hole.
        parts.extend([box([14,17],[24,32],[3,8],paint(trim,trim,trim,wall)),box([31,34],[24,32],[10,15],paint(trim,trim,trim,wall)),
            box([17,20],[24,32],[22,24],paint(trim,trim,trim,wall)),box([28,31],[24,32],[20,24],paint(trim,trim,trim,wall))])
    parts.append(box([0,w],[24,32],[31,32],paint(top,top,top,top)))
    return 32,[],parts

def trunk(d):
    bark=swatch(d,(181,132,33),(3,0,29,48));light=swatch(d,(222,173,74),(3,0,29,48))
    return 64,[],[round_part('cylinder',[16,56],[14,7],[3,64],paint([2,0,30,60],light,bark,bark)),
        round_part('cylinder',[16,56],[15,8],[0,4],paint([2,53,30,62],bark,bark,bark))]

def divider(d):
    depth=d.height;body=swatch(d,(214,214,132),(3,depth-15,13,depth-3));white=swatch(d,(255,255,255),(4,16,12,32));base=swatch(d,(99,107,123),(2,depth-8,14,depth))
    parts=[]
    for x,z in [([2,14],[0,depth-3]),([3,13],[depth-3,depth-1])]:
        parts +=[box(x,z,[0,14],paint(body,body,body,body)),box(x,z,[14,16],paint(white,white,white,white)),box(x,z,[0,2],paint(base,base,base,base))]
    return 16,[],parts

def counter(d,g):
    left=g['occurrences'][0]['x']==0;x0,x1=(8,48) if left else (0,40)
    gold=swatch(d,(181,165,74),(8,14,40,22));edge=swatch(d,(214,198,107),(8,14,40,22));dark=swatch(d,(99,90,74),(8,16,40,28))
    parts=[box([x0,x1],[20,30],[6,8],paint(edge,gold,edge,edge)),box([x0+1,x1-1],[21,29],[4,6],paint(gold,gold,gold,gold))]
    for x in (x0+2,x1-4):
        for z in (21,27):parts.append(box([x,x+2],[z,z+2],[0,6],paint(dark,dark,dark,dark)))
    # All upper source positions, including mixed wall corners, stay native.
    return 8,[[x,0] for x in range(3)],parts

def generate(art,baseline):
    groups=source_groups(art);templates=[];added=[]
    for g in groups:
        d=art.drawing(g['layout'],'BAKED').convert('RGB');f=g['family']
        h,terrain,parts=trunk(d) if f=='indoor_trunk' else divider(d) if f=='divider' else counter(d,g) if f=='shop_counter' else arch(d,g)
        t={'id':g['id'],'atlas':'g3q','tiles':tiles_for(g['layout']),'height':h,'interior':True,'terrain':terrain,'parts':parts};templates.append(t)
        added.append({'key':g['id'],'kind':'building','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':entry_hash(t),
            'label':'Complete '+f.replace('_',' ')+' source model','family':'Generic complete room architecture','source_pixel_mode':'RGB','source_pixel_sha256':hashlib.sha256(d.tobytes()).hexdigest(),
            'occurrences':g['occurrences'],'reason':'Exact source pattern is authored as one physical object. Stair surrounds and broken openings leave warp centers clear; native closed story leaves remain outside this fitted model. Indoor tree is one vertical trunk, dividers run along floor depth, and shop counters stay eight voxels high to preserve the one-block counter carrier. Existing fitted source entries remain untouched.',
            'evidence':[str(OUT/'source-evidence.json'),str(OUT/'inspection/index.json')]})
    inv=read(INVENTORY/'building__generic_building/entries.json');ledger=[]
    for n in sorted(OWNED):
        key=inv[n-1]['key'];covered=set();ids=[]
        for g in groups:
            for z,line in enumerate(g['layout']):
                for x,k in enumerate(line):
                    if k==key:
                        ids.append(g['id']);covered|={(p['map'],p['x']+x,p['y']+z) for p in g['occurrences']}
        assert covered=={(p['map'],p['x'],p['y']) for p in art.locations[key]},(n,len(covered),len(art.locations[key]))
        ledger.append({'key':key,'kind':'cell','decision':'retained','source_ordinal':n,'label':'Whole-source-covered room architecture fragment','family':'Generic complete room architecture',
            'baseline_entry_sha256':entry_hash(baseline['cells'][key]),'occurrences':art.locations[key],'whole_templates':sorted(set(ids)),
            'reason':'Preserve the original fallback and metadata while exact complete templates cover every source occurrence. Source/context review identifies true whole arches, a vertical trunk, floor-depth dividers or one-block counters. Individual fragmented extrusion is not approved as a standalone whole object. Runtime/baseline and visual QA must be completed separately.',
            'evidence':[str(OUT/'source-evidence.json'),str(OUT/'inspection/index.json')]})
    return templates,added,ledger,groups

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    art=EmeraldArt(BUNDLE);baseline=read(BASELINE);templates,added,ledger,groups=generate(art,baseline)
    for name,value in [('additions.json',{'cells':{},'buildings':{t['id']:t for t in templates}}),('added-ledger.json',added),('ledger.json',ledger),('updates.json',{'cells':{},'buildings':{}}),
        ('source-evidence.json',{'groups':groups,'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'limits':'Source and geometry evidence only. Actual built-state/headroom/carrier proof and manual Minecraft visual QA are separate artifacts; this generator makes no automatic QA claim.'})]:write_json(OUT/name,value)
    if args.previews:
        old=previews.OUT
        try:previews.OUT=OUT;previews.render(art,templates,groups)
        finally:previews.OUT=old
    print(len(templates),'complete templates;',len(ledger),'source-covered original entries;',sum(len(g['occurrences']) for g in groups),'occurrences;runtime review pending')

if __name__=='__main__':main()
