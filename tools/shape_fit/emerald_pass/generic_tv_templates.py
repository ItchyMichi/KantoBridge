"""Whole CRT and cabinet templates pinned to every exact two-row source pattern."""
from __future__ import annotations
import argparse,base64,hashlib,json
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.emerald.building_trials import box,paint
from tools.shape_fit.emerald.parts_model import build
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE,INVENTORY
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.environment_templates import tiles_for,swatch

OUT=ROOT/'local/model-gallery/emerald-pass-study/generic-tv-templates'
TV='g3q/1-2-3-4';OWNED={19,21,170,225,257}


def generate(art,baseline):
    groups={}
    for p in art.locations[TV]:
        m=art.maps[p['map']];g=art.map_cells(m);raw=base64.b64decode(m['cells']);x,y=p['x'],p['y']
        layout=[[TV],[g[y+1][x]]];collisions=[raw[(y*m['w']+x)*4+2],raw[((y+1)*m['w']+x)*4+2]]
        assert collisions==[7,0],(p,collisions)
        sig=hashlib.sha256(json.dumps(layout,separators=(',',':')).encode()).hexdigest()[:12]
        group=groups.setdefault(sig,{'id':'emerald_object_tv_cabinet_'+sig,'layout':layout,'source_kinds':[[7],[0]],'occurrences':[]})
        group['occurrences'].append(dict(p,map_cells_sha256=hashlib.sha256(raw).hexdigest()))
    templates=[];added=[]
    for g in groups.values():
        drawing=art.drawing(g['layout'],'BAKED').convert('RGB')
        # Match the accepted CRT and support geometry in global coordinates.
        # Both physical objects occupy the northern solid source position; the
        # southern WALK source contains the cabinet's perspective projection.
        metal=swatch(drawing,(85,85,110),(0,0,16,16));top=swatch(drawing,(190,190,180),(0,0,16,16))
        lower=swatch(drawing,(85,85,110),(0,16,16,24))
        parts=[box([1,15],[3,13],[7,16],paint([0,16,16,22],lower,lower,lower)),
            box([1,15],[4,12],[17,31],paint([0,0,16,16],top,metal,metal)),
            box([4,12],[5,11],[16,17],paint(metal,metal,metal,metal))]
        for x in ((2,4),(12,14)):
            for z in ((4,6),(10,12)):parts.append(box(list(x),list(z),[0,7],paint(lower,lower,lower,lower)))
        template={'id':g['id'],'atlas':'g3q','tiles':tiles_for(g['layout']),'height':32,'interior':True,'terrain':[[0,1]],'parts':parts}
        templates.append(template)
        added.append({'key':g['id'],'kind':'building','label':'Complete CRT television on cyan cabinet','family':'Generic television and cabinet',
            'decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':entry_hash(template),
            'source_pixel_mode':'RGB','source_pixel_sha256':hashlib.sha256(drawing.tobytes()).hexdigest(),'occurrences':g['occurrences'],
            'reason':'All43 shared-CRT occurrences have a SOLID northern television cell and a WALK southern cyan-cabinet projection, across ten exact source patterns. The complete template places the accepted CRT geometry on its coherent cabinet within the northern solid footprint. The projected southern row remains empty of physical geometry and keeps WALK collision. Existing CRT/cabinet entries and conditional on metadata remain unchanged as fallbacks. This fixes actual map placement rather than only presenting a vertical gallery stack.',
            'evidence':[str(OUT/'source-evidence.json'),str(OUT/'all-pattern-context.png')]})
    assert len(groups)==10 and sum(len(g['occurrences']) for g in groups.values())==43
    inventory=read(INVENTORY/'building__generic_building/entries.json');ledger=[]
    for n in sorted(OWNED):
        key=inventory[n-1]['key'];covers=[g for g in groups.values() if g['layout'][1][0]==key]
        assert len(covers)==1 and len(covers[0]['occurrences'])==len(art.locations[key])
        ledger.append({'key':key,'kind':'cell','label':'Cyan television cabinet perspective projection','family':'Generic television and cabinet',
            'decision':'retained','baseline_entry_sha256':entry_hash(baseline['cells'][key]),'occurrences':art.locations[key],
            'reason':'Source/context inspection identifies cyan cabinet doors beneath the shared CRT, not a PC foot or unrelated floor prop. Preserve this existing individual fallback because the exact complete CRT/cabinet template covers every occurrence and places both physical objects in the northern SOLID footprint. The lower source remains WALK. Individual retained extrusion is not approved as a standalone complete object.',
            'whole_template':covers[0]['id'],'evidence':[str(OUT/'source-evidence.json'),str(OUT/'inspection/index.json')]})
    return templates,added,ledger,list(groups.values())


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    art=EmeraldArt(BUNDLE);baseline=read(BASELINE);templates,added,ledger,groups=generate(art,baseline)
    write_json(OUT/'additions.json',{'cells':{},'buildings':{t['id']:t for t in templates}})
    write_json(OUT/'added-ledger.json',added);write_json(OUT/'ledger.json',ledger)
    write_json(OUT/'updates.json',{'cells':{},'buildings':{}})
    write_json(OUT/'source-evidence.json',{'groups':groups,'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),
        'sources':{k:{'baseline_entry_sha256':entry_hash(baseline['cells'][k]) if k in baseline['cells'] else None,'occurrences':art.locations[k]} for g in groups for row in g['layout'] for k in row},
        'limits':'Exact runtime headroom, source carrier preservation and original native-baseline fixture required before integration; actual Minecraft QA pending.'})
    if args.previews:
        from tools.shape_fit import hand_check
        from PIL import Image,ImageDraw
        images=[]
        for t,g in zip(templates,groups):
            target=OUT/'inspection'/t['id'];target.mkdir(parents=True,exist_ok=True)
            drawing=art.drawing(g['layout'],'BAKED').convert('RGB');drawing.save(target/'source.png')
            o,c=build(t,np.asarray(drawing));hand_check.picture(o,c,target/'angled.png',6)
            hand_check.front(o,c,target/'front.png',6);hand_check.front(o.transpose(0,2,1),c.transpose(0,2,1,3),target/'side.png',6)
            images.append({'id':t['id'],'source':str(target/'source.png'),'front':str(target/'front.png'),'side':str(target/'side.png'),'angled':str(target/'angled.png')})
        write_json(OUT/'inspection/index.json',images)
        pages=[]
        for start in range(0,len(images),4):
            canvas=Image.new('RGB',(1600,1100),'#eeeeee');draw=ImageDraw.Draw(canvas)
            for j,item in enumerate(images[start:start+4]):
                x=j*400;draw.text((x+8,8),f'{start+j+1} {item["id"][-12:]}',fill='black')
                for view,top,high in [('source',35,180),('front',230,220),('side',465,220),('angled',705,380)]:
                    im=Image.open(item[view]).convert('RGB');scale=min(385/im.width,high/im.height)
                    im=im.resize((max(1,int(im.width*scale)),max(1,int(im.height*scale))),Image.Resampling.NEAREST)
                    canvas.paste(im,(x+(400-im.width)//2,top+(high-im.height)//2))
            path=OUT/'inspection'/f'page-{start//4+1:03d}.png';canvas.save(path)
            pages.append({'path':str(path),'ids':[i['id'] for i in images[start:start+4]]})
        write_json(OUT/'inspection/pages.json',pages)
    print('10 complete CRT/cabinet templates;43 exact occurrences;5 original projected fragments retained under complete coverage')


if __name__=='__main__':main()
