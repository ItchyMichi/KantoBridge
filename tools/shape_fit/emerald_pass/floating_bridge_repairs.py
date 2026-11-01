"""Pacifidlog's paired floating timbers, excluding the surrounding water artwork."""
from __future__ import annotations
import argparse,copy,hashlib,json
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.inventory import contact_pages
from tools.shape_fit.emerald_pass.ship_beams import previews
OUT=ROOT/'local/model-gallery/emerald-pass-study/floating-bridge-repairs'
KEYS=('g3q/24332-24342-24352-24362','g3q/24373-24383-24394-24404','g3q/24561-24572-24578-24585','g3q/24681-24689-24701-24711')
WOOD=((82,49,66),(123,82,82),(156,115,115),(189,148,140),(222,181,165))

def model(k,sprite):
    m=Model(height=4)
    if k in KEYS[:2]:
        for z in (3,11):m.box((0,16),(0,4),(z,z+3),0)
    else:
        for x in (2,10):m.box((x,x+4),(0,4),(1,16) if k==KEYS[2] else (0,14),0)
    rgb=np.asarray(sprite.convert('RGB')).reshape(-1,3)
    palette=np.asarray([np.flatnonzero((rgb==c).all(axis=1))[0] for c in WOOD],np.uint8)
    # Explicit timber geometry; colors do not determine the occupied mask.
    native=np.arange(256,dtype=np.uint8).reshape(16,16)
    distance=((rgb[:,None,:].astype(int)-rgb[palette][None,:,:].astype(int))**2).sum(axis=2)
    m.paint[:]=palette[distance.argmin(axis=1)][native]
    return m

def generate(art,base,pre):
    updates={};ledger=[];rows=read(OUT/'source-evidence.json')['sources']
    for r in rows:
        k=r['key'];old=pre['cells'][k];m=model(k,art.cell(k,'BAKED'));updates[k]=encode(m.occ,m.paint,old,'hand-emerald-floating-timber-repair')
        assert all(p['collision']==0 and p['elevation']==3 for p in r['occurrences'])
        ledger.append({'key':k,'kind':'cell','decision':'changed','label':'Paired floating timbers '+('horizontal' if k in KEYS[:2] else 'northern ends' if k==KEYS[2] else 'southern ends'),
            'family':'Pacifidlog floating timber bridge','baseline_entry_sha256':entry_hash(base['cells'][k]),'previous_entry_sha256':entry_hash(old),'occurrences':r['occurrences'],
            'reason':'The exact source drawing and map contain two narrow parallel wooden floats, separated and surrounded by water/foam. Minecraft QA exposed the old full shallow slab wrapping that water across the top and sides. Author two explicit timber prisms at the inspected native positions, retaining four-voxel height and local wood paint. Water/foam stays empty source background. Preserve all metadata and the native WALK0/elevation3 floor route; these fitted catalog models do not replace the runtime floor or claim a collision change.',
            'evidence':[str(OUT/'source-evidence.json'),str(OUT/'source-whole.png'),str(OUT/'preview/assemblies/index.json')]})
    return updates,ledger

def assemblies(art):
    name='EM_PACIFIDLOG_TOWN';m=art.maps[name];grid=art.map_cells(m);unseen={(x,z) for z,row in enumerate(grid) for x,k in enumerate(row) if k in KEYS};found={}
    while unseen:
        todo=[min(unseen)];component=set()
        while todo:
            x,z=todo.pop()
            if (x,z) not in unseen:continue
            unseen.remove((x,z));component.add((x,z));todo.extend(((x-1,z),(x+1,z),(x,z-1),(x,z+1)))
        x0=min(x for x,z in component);x1=max(x for x,z in component)+1;z0=min(z for x,z in component);z1=max(z for x,z in component)+1
        reference=[row[x0:x1] for row in grid[z0:z1]];layout=[[k if k in KEYS else None for k in row] for row in reference]
        ident='emerald_floating_timbers_'+hashlib.sha256(json.dumps(layout,separators=(',',':')).encode()).hexdigest()[:12]
        e=found.setdefault(ident,{'id':ident,'kind':'object (model as one)','note':'Complete floating timber run between native platforms; intervening water is source-only, native floor routing is separate','layout':layout,'reference_layout':reference,
            'placements':[{'source':[x,z],'offset':[x,0,z]} for z,row in enumerate(layout) for x,k in enumerate(row) if k is not None],'occurrences':[]})
        e['occurrences'].append({'map':name,'x':x0,'y':z0})
    return list(found.values())

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');a=ap.parse_args();art=EmeraldArt(BUNDLE);base=read(BASELINE);pre=read(OUT/'preconditions.json')
    updates,ledger=generate(art,base,pre);entities=assemblies(art)
    for n,v in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities)]:write_json(OUT/n,v)
    if a.previews:
        cur=copy.deepcopy(base);cur['cells'].update(updates);contact_pages('current',copy.deepcopy(read(OUT/'source-evidence.json')['sources']),cur,art,OUT/'preview');previews(entities,cur,art,OUT/'preview/assemblies')
    print(len(updates),'timber models',len(entities),'whole source variants',sum(len(e['occurrences']) for e in entities),'runs')
if __name__=='__main__':main()
