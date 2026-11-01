"""Exact whole mineral formations and wider festival flags from source layouts.

Preserves all cell entries. Whole patterns resolve vertical source projection
and mixed WALK/SOLID supports without false generic per-cell stack links.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
from collections import Counter

import numpy as np
from PIL import Image, ImageDraw

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.emerald.building_trials import box,paint
from tools.shape_fit.emerald.parts_model import build
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE,INVENTORY
from tools.shape_fit.emerald_pass.review import read,entry_hash

OUT=ROOT/'local/model-gallery/emerald-pass-study/environment-templates'
LIVE=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json'
MINERAL_ORDINALS=(12,16,20,27,28,29,30,33,34,35,36,37,38,39,40)
FLAG_LEFT='g3q/13175-12715-13180-12725'
FLAG_RIGHT='g3q/12730-10669-12735-10672'
FLAG_BASE='g3q/12736-10669-12737-10671'
OTHER_LEFT='g3q/12710-12715-12720-12725'


def tiles_for(layout):
    result=[]
    for line in layout:
        q=[list(map(int,k.split('/')[1].split('-'))) for k in line]
        result.extend(([v for p in q for v in p[:2]], [v for p in q for v in p[2:]]))
    return result


def sources(art):
    rows=read(INVENTORY/'general__meteor_falls/entries.json')
    key=lambda n:rows[n-1]['key'];groups={}
    def add(family,layout,p):
        sig=hashlib.sha256(json.dumps(layout,separators=(',',':')).encode()).hexdigest()[:12]
        identifier='emerald_object_'+family+'_'+sig
        m=art.maps[p['map']];raw=base64.b64decode(m['cells']);x,y=p['x'],p['y']
        kinds=[[raw[((y+z)*m['w']+x+c)*4+2] for c in range(len(layout[0]))] for z in range(len(layout))]
        found=groups.setdefault(identifier,{'id':identifier,'family':family,'layout':layout,'source_kinds':kinds,'occurrences':[]})
        assert found['source_kinds']==kinds
        found['occurrences'].append(dict(p,map_cells_sha256=hashlib.sha256(raw).hexdigest()))
    for cap,mid,foot,width,family in [(12,16,20,1,'mineral_narrow'),(27,33,37,2,'mineral_wide'),(29,35,39,2,'mineral_tall')]:
        for p in art.locations[key(cap)]:
            g=art.map_cells(art.maps[p['map']]);x,y=p['x'],p['y'];end=y+1
            while g[end][x]==key(mid):end+=1
            assert g[end][x]==key(foot)
            layout=[line[x:x+width] for line in g[y:end+1]]
            if width==2:
                assert layout[0][1]==key(cap+1) and layout[-1][1]==key(foot+1)
                assert all(line[1]==key(mid+1) for line in layout[1:-1])
            add(family,layout,p)
    for p in art.locations[FLAG_RIGHT]:
        g=art.map_cells(art.maps[p['map']]);x,y=p['x']-1,p['y'];layout=[line[x:x+2] for line in g[y:y+2]]
        assert layout[0][0] in (FLAG_LEFT,OTHER_LEFT)
        add('festival_flag',layout,dict(p,x=x))
    result=list(groups.values())
    assert len(result)==8
    assert sum(len(g['occurrences']) for g in result if g['family'].startswith('mineral'))==14
    assert sum(len(g['occurrences']) for g in result if g['family']=='festival_flag')==32
    return result


def swatch(drawing,target,rect=None):
    a=np.asarray(drawing.convert('RGB'));x0,y0,x1,y1=rect or (0,0,a.shape[1],a.shape[0])
    p=a[y0:y1,x0:x1].astype(int);y,x=np.unravel_index(np.argmin(((p-np.asarray(target))**2).sum(axis=-1)),p.shape[:2])
    x,y=int(x+x0),int(y+y0);return [x,y,x+1,y+1]


def mineral_parts(group,drawing):
    width,depth=drawing.size;h=min(72,depth);cx=width/2;cz=depth-8
    # A continuous body rises at the terminal source position. Three/four/five
    # projected map rows become 6/8/9 blocks, never a long floor-depth wall.
    gold=swatch(drawing,(181,165,115),(0,16,width,depth))
    dark=swatch(drawing,(148,132,107),(0,16,width,depth))
    gray=swatch(drawing,(99,82,107),(0,0,width,16))
    topgray=swatch(drawing,(156,140,156),(0,0,width,16))
    native_body=[6,16,11,max(17,depth-12)] if width==16 else [5,16,width-5,max(17,depth-12)]
    bodypaint=paint(native_body,gold,dark,dark)
    footpaint=paint([0,depth-16,width,depth],gold,dark,dark)
    parts=[]
    def cylinder(center,radius,y,material):
        parts.append({'shape':'cylinder','center':list(center),'radius':list(radius),'y':list(y),'paint':{**material,'wrap':False}})
    if width==16:
        cylinder((cx,cz),(6,6),(0,3),footpaint)
        cylinder((cx,cz),(4.8,4.8),(3,7),footpaint)
        cylinder((cx,cz),(3.3,3.8),(7,h-9),bodypaint)
    else:
        cylinder((cx,cz),(14.5,7),(0,4),footpaint)
        cylinder((cx,cz),(12.5,6),(4,9),footpaint)
        for x,rx in ((7,4.5),(16,5.5),(25,4.5)):
            rect=[max(0,int(x-2)),16,min(width,int(x+3)),max(17,depth-12)]
            cylinder((x,cz),(rx,4.8),(7,h-7),paint(rect,gold,dark,dark))
    # The purple native cave cap is attached to the top of the formation,
    # not left as a detached low rock at its projected northern map row.
    capfront=[0,0,width,16]
    parts.append({'shape':'dome','center':[cx,cz],'radius':[width/2-1,7],
                  'y':[h-11,h],'paint':paint(capfront,topgray,gray,gray)})
    return h,parts


def flag_parts(group,drawing):
    # Exact wider 2x2 source: pole is in the left cell, cloth crosses the right
    # WALK cell, and the terminal curb may itself remain WALK.
    pixels=np.asarray(drawing.convert('RGB'));parts=[]
    gray=swatch(drawing,(99,99,123),(0,16,16,32));light=swatch(drawing,(230,230,239),(0,16,32,32))
    gold=swatch(drawing,(239,214,57),(0,0,24,16))
    # Keep each native paving/curb pixel horizontal; only the explicitly drawn
    # white curb band is raised. No source collision classification is changed.
    parts.append(box([0,32],[16,32],[0,1],paint([0,16,32,32],[0,16,32,32],gray,gray)))
    if group['layout'][0][0]==OTHER_LEFT:
        parts.append(box([0,7],[16,32],[1,3],paint(gray,gray,gray,gray)))
        parts.append(box([1,4],[16,31],[3,4],paint(light,light,gray,gray)))
    else:
        end=32 if group['source_kinds'][1][1]==7 else 16
        parts.append(box([0,end],[24,32],[1,3],paint(gray,gray,gray,gray)))
        parts.append(box([1,15],[25,30],[3,4],paint(light,light,gray,gray)))
        if end==32:parts.append(box([17,31],[25,30],[3,4],paint(light,light,gray,gray)))
    parts.append(box([2,6],[23,29],[0,4],paint(gray,gray,gray,gray)))
    parts.append(box([3,5],[25,27],[3,32],paint(gray,gray,gray,gray)))
    parts.append(box([3,5],[25,27],[31,33],paint(gold,gold,gold,gold)))
    # Source-position polygon has the original folds and narrow trailing tip.
    points=[(5,1),(9,2),(13,3),(15,3),(17,2),(20,2),(20,4),(20,9),(21,10),(21,13),(18,14),(17,14),(17,15),(12,15),(12,14),(9,13),(5,13)]
    mask=Image.new('1',(32,16));ImageDraw.Draw(mask).polygon(points,fill=1)
    for sy,sx in np.argwhere(np.asarray(mask)):
        x,y=int(sx),31-int(sy);z=25+(x//5%2)
        rect=[x,int(sy),x+1,int(sy)+1]
        # The polygon is explicit; replace only blue-white ground colors at
        # its fold edge with this drawing's native gold hem.
        c=pixels[sy,sx]
        if tuple(c) in ((222,230,239),(189,206,230)):rect=gold
        parts.append(box([x,x+1],[z,z+2],[y,y+1],paint(rect,rect,rect,rect)))
    return 33,parts


def generate(art):
    groups=sources(art);templates=[];ledger=[]
    for group in groups:
        drawing=art.drawing(group['layout'],'BAKED').convert('RGB')
        h,parts=flag_parts(group,drawing) if group['family']=='festival_flag' else mineral_parts(group,drawing)
        template={'id':group['id'],'atlas':'g3q','tiles':tiles_for(group['layout']),'height':h,'terrain':[],'parts':parts}
        if group['family'].startswith('mineral'):
            template['interior']=True
        templates.append(template)
        reason=('Source-exact whole festival flag joins its thin patterned cloth to one continuous pole and native curb. All32 wider occurrences are covered by three exact patterns, including ten WALK right curb cells; preserve cell kinds and the separate coherent standard single-width flag stack.' if group['family']=='festival_flag' else
                'Source-exact mineral formation rises as one connected fluted gold body with a shaped foot and attached native purple cave cap. The five-row variant is compressed to72voxels rather than an invalid ten-block stack; three/four-row variants are48/64voxels. All14 formations are covered by five exact patterns; preserve source collision and existing cell entries.')
        ledger.append({'key':group['id'],'kind':'building','label':group['family'].replace('_',' ')+' complete source variant',
                       'family':group['family'],'decision':'added','baseline_absent':True,'baseline_entry_sha256':None,
                       'proposed_entry_sha256':entry_hash(template),'source_pixel_mode':'RGB',
                       'source_pixel_sha256':hashlib.sha256(drawing.tobytes()).hexdigest(),'occurrences':group['occurrences'],
                       'reason':reason,'evidence':[str(OUT/'source-evidence.json'),str(OUT/'inspection/index.json')]})
    return templates,ledger,groups


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    art=EmeraldArt(BUNDLE);templates,ledger,groups=generate(art);live=read(LIVE)
    assert not ({t['id'] for t in templates}&{t['id'] for t in live['buildings']})
    OUT.mkdir(parents=True,exist_ok=True)
    write_json(OUT/'additions.json',{'cells':{},'buildings':{t['id']:t for t in templates}})
    write_json(OUT/'added-ledger.json',ledger)
    write_json(OUT/'source-evidence.json',{'groups':groups,'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),
        'preserved_cell_hashes':{k:entry_hash(live['cells'].get(k)) for g in groups for line in g['layout'] for k in line},
        'limits':'Actual headroom/planner fixtures and Minecraft visual QA pending. New templates only; no source-cell metadata or geometry edits.'})
    replacements={'emerald_environment_038959886fff':'mineral_narrow','emerald_environment_b00acee3a0e5':'mineral_wide',
                  'emerald_environment_ac7f620d95f6':'mineral_tall','emerald_environment_f278b044fee3':'festival_flag'}
    write_json(OUT/'replacement-map.json',{'superseded_incorrect_whole_exhibits':[
        {'id':identifier,'replacement_template_ids':[g['id'] for g in groups if g['family']==family]}
        for identifier,family in replacements.items()],
        'preserve_standard_stack':'emerald_compound_bb9a525af59b',
        'baseline_requirement':'Capture true baseline source-unit placement, not the previous incorrect floor gallery arrangement as evidence of runtime. For minerals all original SOLID cells remain at source floor positions. For flags omit unmatched WALK cloth; the nine OTHER_LEFT caps retain their existing valid on link to their own lower SOLID pole. Actual planner fixture remains required.'})
    if args.previews:
        from tools.shape_fit import hand_check
        images=[]
        for template,group in zip(templates,groups):
            target=OUT/'inspection'/template['id'];target.mkdir(parents=True,exist_ok=True)
            drawing=art.drawing(group['layout'],'BAKED').convert('RGB');drawing.save(target/'source.png')
            o,c=build(template,np.asarray(drawing));hand_check.picture(o,c,target/'angled.png',6)
            hand_check.front(o,c,target/'front.png',6);hand_check.front(o.transpose(0,2,1),c.transpose(0,2,1,3),target/'side.png',6)
            images.append({'id':template['id'],'source':str(target/'source.png'),'front':str(target/'front.png'),'side':str(target/'side.png'),'angled':str(target/'angled.png')})
        write_json(OUT/'inspection/index.json',images)
    print(Counter(g['family'] for g in groups))


if __name__=='__main__':main()
