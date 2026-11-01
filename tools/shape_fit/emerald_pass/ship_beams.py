"""Three source-proven paired timber sections and their actual map assemblies.

Proposals only. Terminal foot sections end each column; adjacent repeated feet
must never be joined into one oversized support. Materials are native texels.
"""
from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import json
from collections import Counter

import numpy as np
from PIL import Image, ImageDraw

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import Model, decode
from tools.shape_fit.emerald_pass.coastal import ROOT, BASELINE, BUNDLE, INVENTORY
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.inventory import contact_pages
from tools.shape_fit.emerald_pass.review import entry_hash, read

OUT = ROOT/'local/model-gallery/emerald-pass-study/ship-beams'
TOP = 'g3q/4700-4700-4701-4701'
MIDDLE = 'g3q/4701-4701-4701-4701'
BASE = 'g3q/4701-4701-4724-4724'
KEYS = (TOP, MIDDLE, BASE)


def section(sprite, key):
    """Paired orange timbers, open central gap, native end cross-ties."""
    m = Model()
    wood = material(sprite, (190, 85, 60), family='orange')
    light = material(sprite, (230, 160, 90), family='orange')
    shadow = material(sprite, (65, 70, 100))
    for x in (2, 10):
        m.box((x, x+4), (0, 16), (5, 11), wood)
        m.box((x, x+1), (0, 16), (10, 11), light)
        m.box((x+3, x+4), (0, 16), (5, 6), shadow)
    if key == TOP:
        m.box((1, 15), (15, 16), (4, 12), material(sprite, (65, 70, 100), (1, 0, 15, 1)))
    elif key == BASE:
        # Three horizontal binding bands are explicit in the bottom source
        # quarter. No diagonal brace or background floor is invented.
        for y in (1, 4, 7):
            m.box((1, 15), (y, y+1), (10, 12), shadow)
            m.box((2, 14), (y+1, y+2), (10, 11), wood)
        for x in (1, 9):
            m.box((x, x+6), (0, 1), (4, 12), shadow)
    return m


def generate(baseline, art):
    updates, ledger = {}, []
    labels = {TOP:'Capped paired timber upper section', MIDDLE:'Paired timber repeated middle section', BASE:'Paired timber foot and binding bands'}
    for key in KEYS:
        old = baseline['cells'][key]
        new = section(art.cell(key, 'BAKED'), key).entry(old, source='hand-emerald-ship-beams')
        if key != BASE:
            assert old.get('on') == BASE and not old.get('alsoOn')
            new['alsoOn'] = [MIDDLE]
        updates[key] = new
        reason = ('The Abandoned Ship, Aqua Hideout and Shipyard source shows two continuous orange timbers with a central dark gap. '
                  'Build aligned narrow full-height posts in all sections; the cap and three low horizontal binding bands belong only to their own source sections. '
                  'The terminal foot remains a full-height support, repairing the interim one-high floor that left the existing stack floating. ')
        if key != BASE:
            reason += ('Preserve its original on=foot and add only source-proven alsoOn=middle. Physical source chains terminate at their first foot; a following middle/foot is a separate object. ')
        else:
            reason += 'No on/alsoOn metadata is added to the terminal foot. '
        ledger.append({'key':key,'kind':'cell','label':labels[key],'family':'paired timber supports','decision':'changed',
                       'reason':reason,'baseline_entry_sha256':entry_hash(old),'occurrences':art.locations[key],
                       'evidence':[str(OUT/'source/page-001.png'),str(OUT/'source-evidence.json'),str(OUT/'source-components/index.json') ]})
    return updates, ledger


def placements(layout, kinds, aliases):
    """Source position calculation matching explicit links, not sprite inference."""
    at = {(x,z):key for z,line in enumerate(layout) for x,key in enumerate(line) if key}
    links = {}
    for (x,z),key in at.items():
        if key in (TOP,MIDDLE) and at.get((x,z+1)) in ({BASE,MIDDLE} if aliases else {BASE}):
            links[(x,z)] = (x,z+1)
    roots = sorted(set(at)-set(links.values()),key=lambda p:(p[1],p[0]))
    out, omitted, chains = [], [], []
    for root in roots:
        chain=[root]
        while chain[-1] in links:chain.append(links[chain[-1]])
        base=chain[-1]
        if len(chain)==1 and kinds[root[1]][root[0]]==0:
            omitted.append(root);continue
        assert kinds[base[1]][base[0]]==7
        assert len(chain)<=4,chain
        for level,point in enumerate(reversed(chain)):
            out.append({'source':list(point),'offset':[base[0],level,base[1]]})
        chains.append([list(p) for p in chain])
    return sorted(out,key=lambda p:(p['source'][1],p['source'][0])),omitted,chains


def assemblies(art):
    found = {}
    for map_id,source in art.maps.items():
        grid=art.map_cells(source);raw=base64.b64decode(source['cells'])
        unseen={(x,y) for y,line in enumerate(grid) for x,key in enumerate(line) if key in KEYS}
        while unseen:
            todo=[min(unseen)];component=set()
            while todo:
                p=todo.pop()
                if p not in unseen:continue
                unseen.remove(p);component.add(p);x,y=p
                todo.extend(((x-1,y),(x+1,y),(x,y-1),(x,y+1)))
            x0=min(p[0] for p in component);y0=min(p[1] for p in component)
            x1=max(p[0] for p in component)+1;y1=max(p[1] for p in component)+1
            layout=[[grid[y][x] if (x,y) in component else None for x in range(x0,x1)] for y in range(y0,y1)]
            kinds=[[raw[(y*source['w']+x)*4+2] if (x,y) in component else None for x in range(x0,x1)] for y in range(y0,y1)]
            current,missing,chains=placements(layout,kinds,True)
            assert not missing
            before,omitted,old_chains=placements(layout,kinds,False)
            old_layout=[[None if (x,z) in omitted else key for x,key in enumerate(line)] for z,line in enumerate(layout)]
            signature=json.dumps([layout,kinds,current,before],separators=(',',':'))
            identifier='emerald_ship_beams_'+hashlib.sha256(signature.encode()).hexdigest()[:12]
            item=found.setdefault(identifier,{'id':identifier,'kind':'object (model as one)',
                'note':'Source timber support group; distinct foot sections end distinct columns',
                'layout':layout,'placements':current,'baseline_layout':old_layout,'baseline_placements':before,
                'source_kinds':kinds,'chains':chains,'baseline_chains':old_chains,'occurrences':[]})
            item['occurrences'].append({'map':map_id,'x':x0,'y':y0})
    definitions=[{k:v for k,v in item.items() if k not in ('source_kinds','chains','baseline_chains','occurrences')} for item in found.values()]
    return definitions,list(found.values())


def source_evidence(art,baseline,definitions,groups):
    amendments={};maps={p['map'] for key in KEYS for p in art.locations[key]}
    for key in (TOP,MIDDLE):
        rows=[]
        for p in art.locations[key]:
            source=art.maps[p['map']];grid=art.map_cells(source);x,y=p['x'],p['y'];raw=base64.b64decode(source['cells'])
            rows.append(dict(p,kind=raw[(y*source['w']+x)*4+2],below_key=grid[y+1][x] if y+1<len(grid) else None,
                             below_kind=raw[((y+1)*source['w']+x)*4+2] if y+1<len(grid) else None))
        amendments[key]={'old_on':BASE,'on':BASE,'old_alsoOn':None,'alsoOn':[MIDDLE],
                         'baseline_entry_sha256':entry_hash(baseline['cells'][key]),'occurrences':rows,
                         'alias_witnesses':[r for r in rows if r['below_key']==MIDDLE]}
    witnesses=[]
    for map_id in sorted(maps):
        source=art.maps[map_id];grid=art.map_cells(source);raw=base64.b64decode(source['cells']);best=[];floor=None
        # Explicit solid/nonblank chain is a sufficient lower bound on actual
        # Headroom.rooms. Do not treat arbitrary export byte values as SOLID.
        for y,line in enumerate(grid):
            for x,key in enumerate(line):
                if raw[(y*source['w']+x)*4+2]!=0:continue
                chain=[]
                for yy in range(y-1,max(-1,y-6),-1):
                    k=grid[yy][x]
                    if raw[(yy*source['w']+x)*4+2]!=7 or np.asarray(art.cell(k,'BAKED'))[:,:,:3].max()==0:break
                    chain.append({'x':x,'y':yy,'key':k,'kind':7})
                if len(chain)>len(best):best=chain;floor={'x':x,'y':y,'kind':0}
        room=max(4,min(9,len(best)*2))
        heights=[len(c)*2 for g in groups if any(p['map']==map_id for p in g['occurrences']) for c in g['chains']]
        assert max(heights)<=room,(map_id,max(heights),room)
        witnesses.append({'map':map_id,'floor':floor,'north_solid_chain':best,'headroom_blocks_at_least':room,
                          'tallest_authored_chain_blocks':max(heights)})
    return {'amendments':amendments,'headroom_witnesses':witnesses,
            'terminal_rule':'BASE has no on/alsoOn; no chain traverses a foot, even when another middle or foot is immediately south.',
            'counts':{key:len(art.locations[key]) for key in KEYS},'source_groups':groups}


def previews(definitions,table,art,out):
    from tools.shape_fit import hand_check
    out.mkdir(parents=True,exist_ok=True)
    for item in definitions:
        points=item['placements']
        h=max(p['offset'][1]*16+len(decode(table['cells'][item['layout'][p['source'][1]][p['source'][0]]])[0]) for p in points)
        w=(max(p['offset'][0] for p in points)+1)*16;d=(max(p['offset'][2] for p in points)+1)*16
        occ=np.zeros((h,d,w),bool);colors=np.zeros((*occ.shape,3),np.uint8)
        for p in points:
            col,row=p['source'];x,y,z=[n*16 for n in p['offset']];key=item['layout'][row][col]
            o,t=decode(table['cells'][key]);a=np.asarray(art.cell(key,'BAKED').convert('RGB'))
            s=(slice(y,y+len(o)),slice(z,z+16),slice(x,x+16));occ[s]=o;colors[s]=a[t//16,t%16]
        target=out/item['id'];target.mkdir(exist_ok=True)
        art.drawing(item.get('reference_layout',item['layout']),'BAKED').save(target/'source.png')
        hand_check.picture(occ,colors,target/'angled.png',5)
        hand_check.front(occ,colors,target/'front.png',5)
        hand_check.front(occ.transpose(0,2,1),colors.transpose(0,2,1,3),target/'side.png',5)
    pages=[]
    for start in range(0,len(definitions),4):
        canvas=Image.new('RGB',(1600,1100),'#eeeeee');draw=ImageDraw.Draw(canvas)
        for j,item in enumerate(definitions[start:start+4]):
            x=j*400;draw.text((x+8,8),f'{start+j+1:02d} {item["id"][-12:]}',fill='black')
            for filename,top,high in [('source.png',35,180),('front.png',230,220),('side.png',465,220),('angled.png',705,380)]:
                picture=Image.open(out/item['id']/filename).convert('RGB');scale=min(385/picture.width,high/picture.height)
                picture=picture.resize((max(1,int(picture.width*scale)),max(1,int(picture.height*scale))),Image.Resampling.NEAREST)
                canvas.paste(picture,(x+(400-picture.width)//2,top+(high-picture.height)//2))
        path=out/f'page-{start//4+1:03d}.png';canvas.save(path)
        pages.append({'path':str(path),'ids':[x['id'] for x in definitions[start:start+4]]})
    write_json(out/'index.json',{'pages':pages,'entities':definitions})


def source_components(groups,art,out):
    out.mkdir(parents=True,exist_ok=True);pages=[]
    for start in range(0,len(groups),8):
        canvas=Image.new('RGB',(1600,900),'#eeeeee');draw=ImageDraw.Draw(canvas)
        for j,g in enumerate(groups[start:start+8]):
            x=(j%4)*400;y=(j//4)*450;p=g['occurrences'][0]
            draw.text((x+5,y+5),f'{start+j+1:02d} {g["id"][-12:]} / {len(g["occurrences"])} placements',fill='black')
            draw.text((x+5,y+23),p['map'].replace('EM_','')[:46],fill='black')
            draw.text((x+5,y+40),f'({p["x"]},{p["y"]}); collision '+str(g['source_kinds']),fill='black')
            im=art.drawing(g['layout'],'BAKED').convert('RGB');scale=min(350/im.width,345/im.height)
            im=im.resize((max(1,int(im.width*scale)),max(1,int(im.height*scale))),Image.Resampling.NEAREST)
            canvas.paste(im,(x+(400-im.width)//2,y+80))
        path=out/f'page-{start//8+1:03d}.png';canvas.save(path);pages.append(str(path))
    write_json(out/'index.json',{'pages':pages,'groups':groups})


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=type(OUT),default=OUT);args=ap.parse_args()
    art=EmeraldArt(BUNDLE);baseline=read(BASELINE)
    updates,ledger=generate(baseline,art);definitions,groups=assemblies(art)
    evidence=source_evidence(art,baseline,definitions,groups)
    rows=[r for r in read(INVENTORY/'inventory.json')['entries'] if r['key'] in KEYS]
    current=copy.deepcopy(baseline);current['cells'].update(updates)
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',definitions),
                       ('source-evidence.json',evidence),('ownership.json',{'owner':'emerald_scenery','keys':KEYS})]:write_json(args.out/name,value)
    contact_pages('source',copy.deepcopy(rows),baseline,art,args.out)
    contact_pages('current',copy.deepcopy(rows),current,art,args.out/'preview')
    source_components(groups,art,args.out/'source-components');previews(definitions,current,art,args.out/'preview/assemblies')
    print('3 changed keys;',len(definitions),'exact source assembly variants;',sum(len(g['occurrences']) for g in groups),'source groups')


if __name__=='__main__':main()
