"""Continuous source-verified cave cliff bodies, replacing disconnected cuts."""
from __future__ import annotations
import argparse,base64,copy,hashlib,json
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE,INVENTORY
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.inventory import contact_pages,compact_context_pages
from tools.shape_fit.emerald_pass.ship_beams import previews

OUT=ROOT/'local/model-gallery/emerald-pass-study/cave-mass-repairs'
STUDY=ROOT/'local/model-gallery/emerald-pass-study/environment-repairs'
# Source-inspected continuous cliff/cap/end variants; no doorway, ladder,
# floor, crystal face or free-standing rock is included in this registry.
ROLES={36:'northwest',37:'northeast',38:'southwest',39:'inscription',40:'inscription',
       41:'northeast',42:'west',43:'east',44:'end',45:'southeast',46:'southwest',
       47:'west',49:'southwest',69:'southeast',70:'southeast',
       72:'northwest',73:'northeast',80:'northwest',81:'northeast'}
EXTRA={'g3q/14070-14071-14072-14073':'north','g3q/14125-14126-14127-14128':'southeast',
       'g3q/15086-15087-15088-15089':'north','g3q/15102-15103-15104-15105':'southwest'}
MIXED_CAPS={'g3q/14070-14071-14072-14073':{0,7},'g3q/15086-15087-15088-15089':{7,43,114}}

def rows(art):
    source=read(STUDY/'entries.json');rr=[dict(r,role=ROLES[r['repair_ordinal']]) for r in source if r['repair_ordinal'] in ROLES]
    inv={r['key']:r for p in INVENTORY.glob('*/entries.json') for r in read(p)}
    for k,role in EXTRA.items():
        r=copy.deepcopy(inv[k]);r.update(role=role,repair_ordinal=None,captured_findings=[]);r['occurrences']=[]
        for p in art.locations[k]:
            m=art.maps[p['map']];raw=base64.b64decode(m['cells']);r['occurrences'].append(dict(p,collision=raw[(p['y']*m['w']+p['x'])*4+2],map_cells_sha256=hashlib.sha256(raw).hexdigest()))
        rr.append(r)
    return rr

def mass(sprite,role,key):
    m=Model();m.box((0,16),(0,16),(0,16));yy,zz,xx=np.indices(m.occ.shape)
    # Small, explicitly authored plan chamfers soften exposed cliff ends;
    # broad centers and both adjoining boundary faces remain solid throughout.
    cuts={'northwest':xx+zz<3,'northeast':15-xx+zz<3,
          'southwest':xx+15-zz<3,'southeast':30-xx-zz<3,
          'west':(xx<1)&((zz<2)|(zz>13)),'east':(xx>14)&((zz<2)|(zz>13)),
          'end':((xx<1)|(xx>14))&((zz<2)|(zz>13))}
    if role in cuts:m.put(cuts[role],cut=True)
    # Source-local stone shades add broad facets to continuous side/top faces.
    # The registry's game palettes are explicit; no texture-derived silhouette.
    first=int(key.split('/')[1].split('-')[0])
    if first>=27000:tones=((74,41,156),(107,74,197),(156,132,239))
    elif first>=23000:tones=((33,41,0),(82,90,33),(132,140,66))
    elif first>=20000:tones=((82,74,49),(123,115,66),(165,156,74))
    elif first in (15168,15196):tones=((115,82,74),(156,123,115),(189,156,148))
    elif first>=15000 or first==14212:tones=((57,41,66),(82,66,90),(123,107,115))
    else:tones=((82,41,49),(123,82,66),(173,140,99))
    uv=np.asarray([material(sprite,c) for c in tones],np.uint8)
    facets=((xx//3*73856093)^(zz//4*19349663)^(yy//4*83492791))%100
    shade=np.where(facets<6,0,np.where(facets<78,1,2))
    shade=np.where((yy>=14)&(facets>=25),2,shade)
    m.paint[:]=uv[shade]
    m.paint[:,15,:]=((15-yy[:,15,:])*16+xx[:,15,:]).astype(np.uint8)
    return m

def generate(art,base,pre,owned):
    updates={};ledger=[]
    for r in owned:
        k=r['key'];old=pre['cells'][k];m=mass(art.cell(k,'BAKED'),r['role'],k);updates[k]=encode(m.occ,m.paint,old,'hand-emerald-cave-mass-repair')
        assert {p['collision'] for p in r['occurrences']}==MIXED_CAPS.get(k,{7}),(k,{p['collision'] for p in r['occurrences']})
        reason='Exact map neighborhoods identify a continuous solid cave cliff body, cap or return. The old sprite-shaped occupancy cuts holes through stone or leaves suspended slabs. Author one grounded16-high stone mass with a small source-oriented plan chamfer on exposed corners and uninterrupted broad adjoining faces. Keep the native cliff detail on its front and plain native stone on side/back/top; no texture pixel determines occupancy. Preserve every source collision and entry flag.'
        if r['role']=='inscription':reason+=' The Sealed Chamber marks are lettering on the cliff face, not holes in its supporting rock; keep the native glyphs readable on a continuous supported front.'
        if k in EXTRA:reason+=' This adjoining cap/end was included after reviewing complete inscribed outcrops so the repaired face joins a coherent stone body.'
        if k in MIXED_CAPS:
            assert not any(old.get(f) for f in ('walk','seat','on'))
            reason+=' This key also has native WALK/warp uses; they keep their existing non-fitted route because no walk, seat or stack flag is enabled. The fitted stone body is its SOLID use, not a new obstacle over the WALK/warp source.'
        ledger.append({'key':k,'kind':'cell','decision':'changed','label':'Continuous cave '+r['role']+' stone section','family':'Cave cliff mass repair','baseline_entry_sha256':entry_hash(base['cells'][k]),
            'previous_entry_sha256':entry_hash(old),'reason':reason,'occurrences':r['occurrences'],'captured_findings':r['captured_findings'],
            'evidence':[str(OUT/'source-evidence.json'),str(OUT/'preview/contexts/index.json'),str(OUT/'preview/assemblies/index.json')]})
    return updates,ledger

def assemblies(art):
    found={}
    for text in ('g3q/14199-14208-14201-14210','g3q/14212-14213-14214-14215'):
        for p in art.locations[text]:
            name=p['map'];g=art.map_cells(art.maps[name]);x,z=p['x'],p['y']
            if x>0 and g[z][x-1]==text:continue
            end=x
            while end<len(g[z]) and g[z][end]==text:end+=1
            # Both exact room drawings have solid native cap/end cells around
            # each inscribed run. The two-row comparison exposes all seams.
            x0=x-1;layout=[row[x0:end+1] for row in g[z-1:z+1]]
            ident='emerald_cave_inscribed_'+hashlib.sha256(json.dumps(layout,separators=(',',':')).encode()).hexdigest()[:12]
            e=found.setdefault(ident,{'id':ident,'kind':'object (model as one)','note':'Complete source inscribed cliff face with native cap and end sections',
                'layout':layout,'placements':[{'source':[xx,zz],'offset':[xx,0,zz]} for zz,row in enumerate(layout) for xx,k in enumerate(row)],'occurrences':[]})
            e['occurrences'].append({'map':name,'x':x0,'y':z-1})
    return list(found.values())

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    art=EmeraldArt(BUNDLE);base=read(BASELINE);owned=rows(art);pre=read(OUT/'preconditions.json');updates,ledger=generate(art,base,pre,owned);entities=assemblies(art)
    for n,v in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('source-evidence.json',{'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'sources':owned,'groups':entities,'limits':'Local geometry and source study. Native runtime placement and fresh Minecraft visual QA remain separate.'})]:write_json(OUT/n,v)
    if args.previews:
        cur=copy.deepcopy(base);cur['cells'].update(updates);contact_pages('current',copy.deepcopy(owned),cur,art,OUT/'preview');compact_context_pages(owned,art,OUT/'preview/contexts');previews(entities,cur,art,OUT/'preview/assemblies')
    print(len(updates),'stone sections;',len(entities),'inscribed source comparisons;not yet frozen')
if __name__=='__main__':main()
