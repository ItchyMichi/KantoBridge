"""Continuous berry-bed WALK caps matching the reviewed four-voxel soil bed."""
from __future__ import annotations
import argparse,base64,copy,hashlib,json
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE,INVENTORY
from tools.shape_fit.emerald_pass.review import read,entry_hash,TABLE
from tools.shape_fit.emerald_pass.inventory import contact_pages,compact_context_pages
from tools.shape_fit.emerald_pass.ship_beams import previews

OUT=ROOT/'local/model-gallery/emerald-pass-study/dirt-path-repairs'
STUDY=ROOT/'local/model-gallery/emerald-pass-study/environment-repairs'
LEFT='g3q/11459-11460-11461-11462';MIDDLE='g3q/11463-11464-11465-11466';RIGHT='g3q/11467-11461-11207-11459'
BODY='g3q/11204-11205-11205-11204'
CAPS=('g3q/10658-11460-10659-11462','g3q/11467-10659-11207-10658','g3q/11460-11198-11462-11205','g3q/11197-11467-11204-11207')
KEYS=(LEFT,RIGHT,BODY)
CONTEXT_KEYS=(LEFT,MIDDLE,RIGHT,*CAPS,BODY)

def rows(art):
    inventory={r['key']:r for p in INVENTORY.glob('*/entries.json') for r in read(p)}
    findings={r['key']:r['captured_findings'] for r in read(STUDY/'entries.json')};result=[]
    for k in CONTEXT_KEYS:
        r=copy.deepcopy(inventory[k]);r['captured_findings']=findings.get(k,[]);r['occurrences']=[]
        for p in art.locations[k]:
            m=art.maps[p['map']];raw=base64.b64decode(m['cells']);collision=raw[(p['y']*m['w']+p['x'])*4+2]
            assert collision==(7 if k in (MIDDLE,BODY,CAPS[2],CAPS[3]) else 0),(k,p,collision)
            q=dict(p,collision=collision,map_cells_sha256=hashlib.sha256(raw).hexdigest())
            if k in (MIDDLE,BODY):
                obj=[o for o in m['objects'] if (o['x'],o['y'])==(p['x'],p['y']) and o['kind']=='berry']
                assert len(obj)==1
                q.update(berry_object=obj[0],map_json_sha256=hashlib.sha256((BUNDLE/'g3map'/f"{p['map']}.json").read_bytes()).hexdigest())
            r['occurrences'].append(q)
        result.append(r)
    return result

def assemblies(art):
    result={};fitted=read(BASELINE)['cells']
    starts=[dict(p,vertical=False) for p in art.locations[LEFT]]
    for p in art.locations[BODY]:
        g=art.map_cells(art.maps[p['map']]);x,y=p['x'],p['y']
        if g[y-1][x] not in (BODY,MIDDLE):starts.append(dict(p,x=x-1,y=y-1,vertical=True))
    for p in starts:
        g=art.map_cells(art.maps[p['map']]);x,y=p['x'],p['y'];end=x+1
        if p['vertical']:end=x+2
        else:
            while end<len(g[y]) and g[y][end]==MIDDLE:end+=1
            assert g[y][x]==LEFT and g[y][end]==RIGHT
        bottom=y+1
        while bottom<len(g) and g[bottom][x+1]==BODY:bottom+=1
        source=[r[x:end+1] for r in g[y:bottom+1]];i='emerald_berry_bed_'+hashlib.sha256(json.dumps(source,separators=(',',':')).encode()).hexdigest()[:12]
        layout=[[k if k in fitted else None for k in row] for row in source]
        if p['map']=='EM_ROUTE119':layout[1][0]=None # neighboring conifer projection, outside the soil object
        raw=base64.b64decode(art.maps[p['map']]['cells']);mw=art.maps[p['map']]['w']
        assert all(raw[((y+z)*mw+x+xx)*4+2]==0 for z,row in enumerate(layout) for xx,k in enumerate(row) if k is None)
        row=result.setdefault(i,{'id':i,'kind':'object (model as one)','note':'Fitted soil pieces of complete two-row berry bed; native unfitted floor and berry objects remain separate','layout':layout,'reference_layout':source,
            'placements':[{'source':[xx,z],'offset':[xx,0,z]} for z,row in enumerate(layout) for xx,k in enumerate(row) if k is not None],'occurrences':[]})
        row['occurrences'].append({a:b for a,b in p.items() if a!='vertical'})
    assert sum(len(r['occurrences']) for r in result.values())==18
    return list(result.values())

def generate(art,base,pre):
    rr=rows(art);updates={};ledger=[]
    for r in rr:
        if r['key'] not in KEYS:continue
        k=r['key'];old=pre['cells'][k];assert not any(old.get(f) for f in ('walk','seat','on'))
        m=Model(height=4);m.occ[:]=True;m.paint[:]=np.arange(256,dtype=np.uint8).reshape(16,16)
        updates[k]=encode(m.occ,m.paint,old,'hand-emerald-berry-soil-cap-repair')
        ledger.append({'key':k,'kind':'cell','decision':'changed','label':'Continuous berry soil '+('vertical bed body' if k==BODY else 'western cap' if k==LEFT else 'eastern cap'),
            'family':'Native berry soil bed','baseline_entry_sha256':entry_hash(base['cells'][k]),'previous_entry_sha256':entry_hash(old),'occurrences':r['occurrences'],'captured_findings':r['captured_findings'],
            'reason':'Complete source strips identify a soil bed in grass, not separate ledge strips. All53 horizontal middle positions and8 vertical body positions coincide exactly with exported kind:berry objects. The two selected caps are WALK0 at all32 positions; vertical bodies retain rawSOLID7. Fill the disconnected cap/body gaps with continuous native-painted surfaces at the existing four-voxel height of the reviewed adjoining horizontal middle and alternate cap models. Keep those prior middle and Lilycove cap geometries untouched. Preserve absent walk/seat/on flags and every source/object record. This corrects fitted catalog soil geometry; native source routes, including native roof columns at berry witnesses, are not claimed corrected by this geometry-only change.',
            'evidence':[str(OUT/'source-evidence.json'),str(OUT/'preview/contexts/index.json'),str(OUT/'preview/assemblies/index.json'),'fabric-bridge/src/main/java/dev/kantobridge/builder/shape/Units.java']})
    return updates,ledger,rr

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args();art=EmeraldArt(BUNDLE);base=read(BASELINE)
    if not (OUT/'preconditions.json').exists():write_json(OUT/'preconditions.json',{'cells':{k:read(TABLE)['cells'][k] for k in CONTEXT_KEYS}})
    pre=read(OUT/'preconditions.json');updates,ledger,rr=generate(art,base,pre);entities=assemblies(art)
    if not (OUT/'context-preconditions.json').exists():
        live=read(TABLE);write_json(OUT/'context-preconditions.json',{'cells':{k:live['cells'][k] for e in entities for row in e['layout'] for k in row if k is not None}})
    for n,v in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('source-evidence.json',{'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'sources':rr,'groups':entities,'limits':'Source/plain WALK route and local catalog geometry inspected. Actual corrected Minecraft catalog QA remains pending; native source floor is not replaced.'})]:write_json(OUT/n,v)
    if args.previews:
        cur=copy.deepcopy(base);cur['cells'].update(read(OUT/'context-preconditions.json')['cells']);cur['cells'].update(pre['cells']);cur['cells'].update(updates);contact_pages('current',copy.deepcopy(rr),cur,art,OUT/'preview');compact_context_pages(rr,art,OUT/'preview/contexts');previews(entities,cur,art,OUT/'preview/assemblies')
    print('3 continuous berry soil pieces;',len(entities),'complete bed context variants; native source/object routes unchanged')
if __name__=='__main__':main()
