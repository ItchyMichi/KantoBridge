"""Explicit ship wall and open broken-door repairs from scenery-08 inspection.

The frozen 108-key ship batch remains available. This replacement owns exactly
the same original cells; whole doorway additions cover all five source warps.
"""
from __future__ import annotations
import argparse,base64,copy,hashlib,json
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,decode,render
from tools.shape_fit.emerald_pass import towns_ship as ship
from tools.shape_fit.emerald_pass.review import ROOT,BASELINE,read,entry_hash
from tools.shape_fit.emerald_pass.scenery import BUNDLE
from tools.shape_fit.emerald_pass.environment_templates import tiles_for,swatch
from tools.shape_fit.emerald.building_trials import box,paint
from tools.shape_fit.emerald.parts_model import build

OUT=ROOT/'local/model-gallery/emerald-pass-study/ship-qa-corrections'
REPAIRED={13,19,30,31,32,33,34,35,58,59,69,70,75,85,87}


def striped_wall(sprite,source_top=4):
    wood=ship.texel(sprite,(189,140,57),(0,4,16,16))
    m=Model();m.box((0,16),(0,16),(0,16),wood)
    m.project((0,source_top,16,16),side=wood,top=wood,back=wood)
    return m


def stair_landing_wall():
    """Whole two-cell rear panel with continuous rail and southern return."""
    m=Model(width=32);m.box((0,32),(0,16),(0,8),1)
    m.box((0,32),(8,10),(7,11),2)
    m.box((28,31),(8,10),(8,16),2)
    m.box((28,31),(0,8),(13,16),2)
    return m


def stair_wall(sprite,col):
    """Continuous striped rear wall and supported turning landing handrail."""
    wood=ship.texel(sprite,(189,140,57),(0,0,16,8))
    gray=ship.texel(sprite,(107,107,115))
    m=ship.slice_model(stair_landing_wall(),col*16,0);old=m.paint.copy()
    m.paint[m.occ]=wood
    y,z,x=np.indices(m.occ.shape);face=m.occ&(old==1)&(z==7)
    if col:face&=y>=8
    m.paint[face]=((15-y)*16+x)[face]
    m.paint[m.occ&(old==2)]=gray
    return m


def alcove():
    """One complete rear panel, rug and small attached bed-alcove trim."""
    m=Model(width=32)
    m.box((0,32),(0,16),(0,8),1)
    m.box((0,32),(0,1),(8,16),2)
    m.box((12,20),(1,5),(7,11),3)
    m.box((13,19),(2,4),(10,11),4)
    return m


def alcove_slice(sprite,col):
    m=ship.slice_model(alcove(),col*16,0);old=m.paint.copy()
    wood=ship.texel(sprite,(181,132,49),(0,0,16,8))
    orange=ship.texel(sprite,(239,173,74),(0,8,16,16))
    gray=ship.texel(sprite,(165,173,181),(0,8,16,16))
    y,z,x=np.indices(m.occ.shape);m.paint[m.occ]=wood
    # The upper half of each native cell is vertical panel. Rug pixels remain
    # horizontal; neither checkerboard rug nor trim becomes a wall silhouette.
    face=m.occ&(old==1)&(z==7)
    upper_face=face&(y>=8)
    m.paint[upper_face]=((15-y)*16+x)[upper_face]
    floor=m.occ&(old==2);floor_uv=z*16+x
    rgb=np.asarray(sprite.convert('RGB'));sample=rgb[z,x].astype(int)
    rug_pixel=(sample[...,0]>180)&(sample[...,1]>180)&(sample[...,2]<sample[...,0]-15)&(np.abs(sample[...,0]-sample[...,1])<35)
    pale=ship.texel(sprite,(239,239,189),(0,8,16,16));shade=ship.texel(sprite,(206,214,156),(0,8,16,16))
    replacement=np.where((x+z)%4<2,pale,shade)
    floor_uv=np.where(rug_pixel,floor_uv,replacement)
    m.paint[floor]=floor_uv[floor]
    m.paint[m.occ&(old==3)]=orange;m.paint[m.occ&(old==4)]=gray
    return m


def ring_wall(sprite,col):
    # Construct the complete two-cell lower wooden panel before local slicing.
    whole=Model(width=32);whole.box((0,32),(0,16),(0,8),1)
    m=ship.slice_model(whole,col*16,0);wood=ship.texel(sprite,(181,132,49),(0,0,16,8))
    m.paint[m.occ]=wood
    y,z,x=np.indices(m.occ.shape);front=m.occ&(z==7)
    m.paint[front]=((15-y)*16+x)[front]
    return m


def broken_frame():
    """Supported timber frame and attached gold remnants; center stays open."""
    m=Model(height=32)
    for x in ((0,2),(14,16)):m.box(x,(0,32),(6,10),1)
    m.box((0,16),(27,32),(6,10),1)
    # Broken leaf pieces are attached to the jambs, never floating fragments.
    m.box((12,14),(20,27),(7,10),2)
    m.box((11,14),(23,26),(7,10),2)
    m.box((2,4),(2,8),(7,10),2)
    m.box((2,5),(2,5),(7,10),2)
    m.box((12,14),(2,7),(7,10),2)
    m.box((11,14),(2,4),(7,10),2)
    return m


def broken_slice(sprite,upper):
    whole=broken_frame();s=slice(16,32) if upper else slice(0,16)
    m=Model();m.occ=whole.occ[s].copy();m.paint=whole.paint[s].copy()
    wood=ship.texel(sprite,(189,140,57));gold=ship.texel(sprite,(247,230,115))
    old=m.paint.copy();m.paint[m.occ&(old==1)]=wood;m.paint[m.occ&(old==2)]=gold
    return m


def cell_repairs(baseline,art):
    rows=ship.rows_for(ship.GROUP);updates={}
    for n in sorted(REPAIRED):
        key=rows[n-1]['key'];sprite=art.cell(key,'BAKED')
        if n in (13,69,70):m=striped_wall(sprite,4 if n==13 else 5)
        elif n in (19,75):m=stair_wall(sprite,0 if n==19 else 1)
        elif n in (32,33):m=ring_wall(sprite,n-32)
        elif n in (85,87):m=broken_slice(sprite,n==85)
        else:m=alcove_slice(sprite,0 if n in (30,59,34) else 1)
        updates[key]=m.entry(baseline['cells'][key],'hand-emerald-ship-qa-correction',trim=True)
    return updates


def doorway_templates(art):
    rows=ship.rows_for(ship.GROUP);lower=rows[86]['key'];groups={}
    for p in art.locations[lower]:
        scene=art.maps[p['map']];grid=art.map_cells(scene);raw=base64.b64decode(scene['cells']);x,y=p['x'],p['y']
        layout=[[grid[y-1][x]],[lower]]
        kinds=[[raw[((y-1)*scene['w']+x)*4+2]],[raw[(y*scene['w']+x)*4+2]]]
        assert kinds==[[0],[114]],(p,kinds)
        sig=hashlib.sha256(json.dumps(layout,separators=(',',':')).encode()).hexdigest()[:12]
        g=groups.setdefault(sig,dict(id='emerald_object_ship_broken_door_'+sig,layout=layout,source_kinds=kinds,occurrences=[]))
        g['occurrences'].append(dict(p,y=y-1,map_cells_sha256=hashlib.sha256(raw).hexdigest()))
    assert len(groups)==2 and sum(len(g['occurrences']) for g in groups.values())==5
    templates=[];ledger=[]
    for g in groups.values():
        drawing=art.drawing(g['layout'],'BAKED').convert('RGB')
        wood=swatch(drawing,(189,140,57));gold=swatch(drawing,(247,230,115));parts=[]
        # Exact source rows include an upper WALK projection. The entire actual
        # frame belongs to the southern WARP cell; source collision is retained.
        for x,y,material in [([0,2],[0,32],wood),([14,16],[0,32],wood),([0,16],[27,32],wood),
            ([12,14],[20,27],gold),([11,14],[23,26],gold),([2,4],[2,8],gold),([2,5],[2,5],gold),([12,14],[2,7],gold),([11,14],[2,4],gold)]:
            parts.append(box(x,[22,26],y,paint(material,material,material,material)))
        t=dict(id=g['id'],atlas='g3q',tiles=tiles_for(g['layout']),interior=True,terrain=[[0,0]],height=32,parts=parts)
        templates.append(t)
        ledger.append(dict(key=t['id'],kind='building',label='Open broken Abandoned Ship cabin frame',family='Ship QA correction',decision='added',baseline_absent=True,baseline_entry_sha256=None,proposed_entry_sha256=entry_hash(t),source_pixel_mode='RGB',source_pixel_sha256=hashlib.sha256(drawing.tobytes()).hexdigest(),occurrences=g['occurrences'],reason='All five actual broken-door warps use one of two exact source patterns. Keep the upper WALK projection empty, place the continuous open frame on the southern WARP cell, retain both original source collision kinds, and attach the jagged gold remnants to grounded jambs. The central passage is open through the full source doorway.',evidence=[str(OUT/'source-evidence.json'),str(ROOT/'local/model-gallery/emerald-batch-02/scenery-08/ship-followups.json')]))
    return templates,ledger,list(groups.values())


def wall_assemblies(art):
    rows=ship.rows_for(ship.GROUP);result=[]
    for name,ordinals in [('left_alcove',[30,58]),('left_ring_alcove',[30,31,32]),('right_alcove',[59,35]),('right_ring_alcove',[33,34,35]),('stair_landing_wall',[19,75])]:
        layout=[[rows[n-1]['key'] for n in ordinals]];found=[]
        for p in art.locations[layout[0][0]]:
            grid=art.map_cells(art.maps[p['map']]);x,y=p['x'],p['y']
            if grid[y][x:x+len(ordinals)]==layout[0]:found.append(p)
        assert found,name
        result.append(dict(id='emerald_ship_qa_'+name,kind='wall components (model as one)',layout=layout,occurrences=found,note='Exact source-adjacent lower cabin panels and alcove trim. Ring-bearing variants are structural interface comparisons; the northern ring crown remains separate context.'))
    return result


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    baseline=read(BASELINE);art=EmeraldArt(BUNDLE);original=read(ship.OUT/'updates.json');ledger=read(ship.OUT/'ledger.json')
    updates=cell_repairs(baseline,art);original['cells'].update(updates)
    for r in ledger:
        if r['key'] not in updates:continue
        r.update(decision='changed',visual_status='Minecraft defect identified; explicit repair locally authored; recapture pending',reason='Scenery-08/09 actual front/side/angled inspection exposed an hourglass, comb-like wall foot, extruded white void or disconnected broken-frame profile. Replace it with an explicit continuous native-painted wall, horizontal alcove rug and supported trim, supported landing rail, or the shared open broken-door frame as appropriate. Preserve source metadata and every actual occurrence.',proposed_entry_sha256=entry_hash(updates[r['key']]))
        r['evidence'].append({'qa_followups':'local/model-gallery/emerald-batch-02/scenery-08/ship-followups.json','repair_generator':'tools/shape_fit/emerald_pass/ship_qa_corrections.py'})
    templates,added,groups=doorway_templates(art)
    entities=read(ship.OUT/'entities.json')+wall_assemblies(art)
    write_json(OUT/'updates.json',original);write_json(OUT/'ledger.json',ledger);write_json(OUT/'entities.json',entities)
    write_json(OUT/'additions.json',{'cells':{},'buildings':{t['id']:t for t in templates}});write_json(OUT/'added-ledger.json',added)
    write_json(OUT/'source-evidence.json',{'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'groups':groups,'repaired_cells':{key:{'occurrences':art.locations[key],'source_rgb_sha256':hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest()} for key in updates}})
    write_json(OUT/'source-amendment-proposals.json',{'added_buildings':{t['id']:{'metadata':{k:v for k,v in t.items() if k not in ('height','parts')},'source_pixel_mode':'RGB','source_pixel_sha256':l['source_pixel_sha256'],'occurrences':[[p['map'],p['x'],p['y']] for p in l['occurrences']],'evidence':'ship-qa-corrections/added-ledger.json'} for t,l in zip(templates,added)}})
    if args.previews:
        from tools.shape_fit import hand_check
        cards=[]
        for key,entry in updates.items():
            target=OUT/'inspection'/key.removeprefix('g3q/');target.mkdir(parents=True,exist_ok=True)
            sprite=art.cell(key,'BAKED');sprite.save(target/'source.png');render(entry,sprite,target/'after');render(baseline['cells'][key],sprite,target/'before')
            cards.append({'id':key,'source':str(target/'source.png'),**{view:str(target/'after'/f'{view}.png') for view in ('front','side','angled')}})
        for t,g in zip(templates,groups):
            target=OUT/'inspection'/t['id'];target.mkdir(parents=True,exist_ok=True);drawing=art.drawing(g['layout'],'BAKED').convert('RGB');drawing.save(target/'source.png')
            o,c=build(t,np.asarray(drawing));hand_check.picture(o,c,target/'angled.png',8);hand_check.front(o,c,target/'front.png',8);hand_check.front(o.transpose(0,2,1),c.transpose(0,2,1,3),target/'side.png',8)
            cards.append({'id':t['id'],**{v:str(target/f'{v}.png') for v in ('source','front','side','angled')}})
        for e in wall_assemblies(art):
            target=OUT/'inspection'/e['id'];target.mkdir(parents=True,exist_ok=True);art.drawing(e['layout'],'BAKED').save(target/'source.png')
            objects=[];colors=[]
            for key in e['layout'][0]:
                o,p=decode(updates[key]);rgb=np.asarray(art.cell(key,'BAKED').convert('RGB'));objects.append(o);colors.append(rgb[p//16,p%16])
            o=np.concatenate(objects,axis=2);c=np.concatenate(colors,axis=2)
            hand_check.picture(o,c,target/'angled.png',8);hand_check.front(o,c,target/'front.png',8);hand_check.front(o.transpose(0,2,1),c.transpose(0,2,1,3),target/'side.png',8)
            cards.append({'id':e['id'],**{v:str(target/f'{v}.png') for v in ('source','front','side','angled')}})
        write_json(OUT/'inspection/index.json',cards)
        for start in range(0,len(cards),4):
            page=Image.new('RGB',(1600,1000),'#eeeeee');d=ImageDraw.Draw(page)
            for col,card in enumerate(cards[start:start+4]):
                d.text((col*400+5,5),f'{start+col+1} '+card['id'][-36:],fill='black')
                for v,y,h in [('source',30,180),('front',220,220),('side',450,220),('angled',680,310)]:
                    im=Image.open(card[v]).convert('RGB');scale=min(390/im.width,h/im.height);im=im.resize((max(1,int(im.width*scale)),max(1,int(im.height*scale))),Image.Resampling.NEAREST);page.paste(im,(col*400+(400-im.width)//2,y+(h-im.height)//2))
            page.save(OUT/'inspection'/f'page-{start//4+1:03}.png')
    print(f'{len(updates)} repaired originals;108-key replacement owner;{len(templates)} exact broken-door templates,5 source warps')


if __name__=='__main__':main()
