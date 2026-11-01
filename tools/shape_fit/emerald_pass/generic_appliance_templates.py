"""Complete source-pinned kitchens, refrigerators and glazed cabinet variants."""
from __future__ import annotations
import argparse,base64,hashlib,json
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.emerald.building_trials import box,paint,round_part
from tools.shape_fit.emerald.parts_model import build
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE,INVENTORY
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.environment_templates import tiles_for,swatch

OUT=ROOT/'local/model-gallery/emerald-pass-study/generic-appliance-templates'
OWNED={49,50,103,104,192,193,197,198,241,242,247,248,64,71,108,218,226,81,82,114,115,210,211,249,250,148,149,151,152}
GLASS_LEFT='g3q/5762-5763-5419-5420'

def groups(art):
    inv=read(INVENTORY/'building__generic_building/entries.json');key=lambda n:inv[n-1]['key'];out={}
    for family,seed,width in [('kitchen',key(49),2),('kitchen',key(103),2),('kitchen',key(192),2),('kitchen',key(241),2),
        ('fridge',key(64),1),('glazed',GLASS_LEFT,2),('freestanding_glazed',key(148),2)]:
        for p in art.locations[seed]:
            m=art.maps[p['map']];g=art.map_cells(m);raw=base64.b64decode(m['cells']);x,y=p['x'],p['y']
            layout=[line[x:x+width] for line in g[y:y+2]]
            kinds=[[raw[((y+z)*m['w']+x+c)*4+2] for c in range(width)] for z in range(2)]
            expected=[[0]*width,[7]*width] if family=='freestanding_glazed' else [[7]*width,[0]*width]
            assert kinds==expected,(family,p,kinds)
            sig=hashlib.sha256(json.dumps(layout,separators=(',',':')).encode()).hexdigest()[:12]
            row=out.setdefault(sig,{'id':'emerald_object_generic_'+family+'_'+sig,'family':family,'layout':layout,'source_kinds':kinds,'occurrences':[]})
            row['occurrences'].append(dict(p,map_cells_sha256=hashlib.sha256(raw).hexdigest()))
    assert len(out)==14,[(g['family'],len(g['occurrences'])) for g in out.values()]
    return list(out.values())

def kitchen(d):
    wood=swatch(d,(165,132,49),(1,17,30,22));dark=swatch(d,(66,66,90),(2,3,15,12));white=swatch(d,(239,239,214),(1,9,31,15))
    steel=swatch(d,(239,239,247),(6,1,11,8));steel_dark=swatch(d,(99,99,123),(6,1,11,8))
    parts=[box([1,31],[3,14],[3,13],paint(wood,wood,wood,wood)),
        box([1,15],[13,15],[3,13],paint([2,16,16,22],wood,wood,wood)),
        box([16,31],[13,15],[3,13],paint([16,16,30,22],wood,wood,wood)),
        box([0,32],[2,15],[13,16],paint(white,white,white,white)),
        box([3,13],[6,12],[12,17],carve=True),box([3,13],[6,12],[12,13],paint(dark,dark,dark,dark)),
        box([7,9],[4,6],[16,23],paint(steel,steel,steel_dark,steel)),
        box([7,9],[4,9],[22,24],paint(steel,steel,steel_dark,steel)),
        box([5,11],[4,6],[20,21],paint(steel,steel,steel_dark,steel)),
        round_part('cylinder',[24,9],4,[16,17],paint(dark,dark,dark,dark)),
        round_part('cylinder',[24,9],3,[17,18],paint(white,white,white,white))]
    for x in (1,28):
        for z in (4,12):parts.append(box([x,x+3],[z,z+2],[0,3],paint(wood,wood,wood,wood)))
    return 24,parts

def fridge(d):
    shell=swatch(d,(181,181,165),(1,5,15,23));dark=swatch(d,(66,66,90),(1,4,15,23));white=swatch(d,(255,255,255),(1,0,15,5))
    handle=swatch(d,(132,132,156),(1,5,6,23))
    parts=[box([1,15],[3,13],[1,30],paint(shell,shell,shell,shell)),box([1,15],[3,13],[30,32],paint(white,white,white,white)),
        box([1,15],[13,14],[16,17],paint(dark,dark,dark,dark)),box([2,14],[4,12],[0,1],paint(dark,dark,dark,dark))]
    for lo,hi in ((8,13),(22,27)):parts.append(box([3,5],[13,15],[lo,hi],paint(handle,handle,handle,handle)))
    return 32,parts

def glazed(d,freestanding=False):
    shift=16 if freestanding else 0
    wood=swatch(d,(165,132,49),(1,24,31,30) if freestanding else (1,17,31,22))
    frame=swatch(d,(99,74,57),(1,24,31,30) if freestanding else (1,17,31,22))
    gold=swatch(d,(255,214,99),(1,4,31,10) if freestanding else (1,0,31,4))
    glass=[1,12,31,23] if freestanding else [1,4,31,15]
    doors=[1,24,31,30] if freestanding else [1,16,31,22]
    parts=[box([1,31],[shift+3,shift+14],[3,23],paint(wood,wood,wood,wood)),
        box([2,30],[shift+13,shift+15],[10,22],paint(glass,frame,frame,frame)),
        box([2,30],[shift+13,shift+15],[3,10],paint(doors,frame,frame,frame)),
        box([0,32],[shift+2,shift+15],[22,24],paint(gold,gold,gold,gold))]
    for x in (1,28):
        for z in (4,12):parts.append(box([x,x+3],[shift+z,shift+z+2],[0,3],paint(frame,frame,frame,frame)))
    return 24,parts

def generate(art,baseline):
    source=groups(art);templates=[];added=[];ledger=[]
    for g in source:
        d=art.drawing(g['layout'],'BAKED').convert('RGB');f=g['family']
        height,parts=kitchen(d) if f=='kitchen' else fridge(d) if f=='fridge' else glazed(d,f=='freestanding_glazed')
        empty_row=0 if f=='freestanding_glazed' else 1
        template={'id':g['id'],'atlas':'g3q','tiles':tiles_for(g['layout']),'height':height,'interior':True,
            'terrain':[[x,empty_row] for x in range(len(g['layout'][0]))],'parts':parts}
        templates.append(template)
        added.append({'key':g['id'],'kind':'building','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,
            'proposed_entry_sha256':entry_hash(template),'label':'Complete '+f.replace('_',' ')+' source variant','family':'Generic kitchen and glazed appliances',
            'source_pixel_mode':'RGB','source_pixel_sha256':hashlib.sha256(d.tobytes()).hexdigest(),'occurrences':g['occurrences'],
            'reason':'Complete source-specific furniture occupies the actual SOLID row. The other source row is a perspective projection and remains WALK with exact terrain exclusion. Kitchen has cabinet doors, recessed sink, tap and dish; fridge has two doors and raised handles; glazed cases have native reflective panels, drawers and grounded feet. Existing source entries and collision remain untouched.',
            'evidence':[str(OUT/'source-evidence.json'),str(OUT/'inspection/index.json')]})
    inv=read(INVENTORY/'building__generic_building/entries.json')
    for n in sorted(OWNED):
        key=inv[n-1]['key'];covers=[];covered=set()
        for g in source:
            for z,line in enumerate(g['layout']):
                for x,k in enumerate(line):
                    if k==key:
                        covers.append(g['id']);covered|={(p['map'],p['x']+x,p['y']+z) for p in g['occurrences']}
        assert covered=={(p['map'],p['x'],p['y']) for p in art.locations[key]},(n,key,len(covered),len(art.locations[key]))
        ledger.append({'key':key,'kind':'cell','decision':'retained','label':'Source-covered kitchen, fridge or glazed cabinet fragment','family':'Generic kitchen and glazed appliances',
            'baseline_entry_sha256':entry_hash(baseline['cells'][key]),'source_ordinal':n,'occurrences':art.locations[key],
            'reason':'Individual source and map context identify this as part of a complete appliance or glazed cabinet. Preserve the existing fallback and metadata; exact whole templates cover every occurrence, model the actual solid footprint and keep projected WALK source positions empty. Standalone source extrusion is not approved as a separate complete object.',
            'whole_templates':sorted(set(covers)),'evidence':[str(OUT/'source-evidence.json'),str(OUT/'inspection/index.json')]})
    return templates,added,ledger,source

def render(art,templates,groups):
    from tools.shape_fit import hand_check
    from PIL import Image,ImageDraw
    images=[]
    for t,g in zip(templates,groups):
        target=OUT/'inspection'/t['id'];target.mkdir(parents=True,exist_ok=True)
        d=art.drawing(g['layout'],'BAKED').convert('RGB');d.save(target/'source.png');o,c=build(t,np.asarray(d))
        hand_check.picture(o,c,target/'angled.png',6);hand_check.front(o,c,target/'front.png',6)
        hand_check.front(o.transpose(0,2,1),c.transpose(0,2,1,3),target/'side.png',6)
        images.append({'id':t['id'],'family':g['family'],'source':str(target/'source.png'),'front':str(target/'front.png'),'side':str(target/'side.png'),'angled':str(target/'angled.png')})
    write_json(OUT/'inspection/index.json',images)
    for start in range(0,len(images),4):
        canvas=Image.new('RGB',(1600,1100),'#eeeeee');draw=ImageDraw.Draw(canvas)
        for j,item in enumerate(images[start:start+4]):
            x=j*400;draw.text((x+8,8),f'{start+j+1} {item["family"]} {item["id"][-12:]}',fill='black')
            for view,top,high in [('source',35,180),('front',230,220),('side',465,220),('angled',705,380)]:
                im=Image.open(item[view]).convert('RGB');scale=min(385/im.width,high/im.height)
                im=im.resize((max(1,int(im.width*scale)),max(1,int(im.height*scale))),Image.Resampling.NEAREST)
                canvas.paste(im,(x+(400-im.width)//2,top+(high-im.height)//2))
        canvas.save(OUT/'inspection'/f'page-{start//4+1:03d}.png')

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    art=EmeraldArt(BUNDLE);baseline=read(BASELINE);templates,added,ledger,source=generate(art,baseline)
    for name,value in [('additions.json',{'cells':{},'buildings':{t['id']:t for t in templates}}),('added-ledger.json',added),('ledger.json',ledger),('updates.json',{'cells':{},'buildings':{}}),
        ('source-evidence.json',{'groups':source,'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),
            'sources':{k:{'baseline_entry_sha256':entry_hash(baseline['cells'][k]) if k in baseline['cells'] else None,'occurrences':art.locations[k]} for g in source for line in g['layout'] for k in line},
            'limits':'All original entries unchanged. Actual source runtime/native baseline fixtures and Minecraft visual QA pending.'})]:write_json(OUT/name,value)
    if args.previews:render(art,templates,source)
    print(len(templates),'whole templates;',len(ledger),'source-covered originals;',sum(len(g['occurrences']) for g in source),'occurrences')

if __name__=='__main__':main()
