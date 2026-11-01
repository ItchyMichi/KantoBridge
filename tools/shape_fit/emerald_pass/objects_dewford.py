"""Exact missing Dewford house, island ferry and three Birth Island trees.

Source layouts, source colors, collisions, warps and occurrences remain explicit.
No resource table is written by this authoring module.
"""
from __future__ import annotations
import argparse
import base64
import copy
import hashlib
import json
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald import building_trials as p,parts_model
from tools.shape_fit.emerald_pass.buildings_west import box,material,house
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,digest

OUT=ROOT/'local/model-gallery/emerald-pass-study/objects-dewford'
CASES={
 'dewford_house1':('EM_DEWFORD_TOWN',16,11,4,4,1),
 'island_ferry':('EM_BIRTH_ISLAND_EXTERIOR',12,24,7,5,2),
 'birth_island_tree_north':('EM_BIRTH_ISLAND_EXTERIOR',14,3,3,3,1),
 'birth_island_tree_west':('EM_BIRTH_ISLAND_EXTERIOR',7,14,4,3,1),
 'birth_island_tree_east':('EM_BIRTH_ISLAND_EXTERIOR',20,14,4,3,1),
}
SOURCE_SHA256={
 'dewford_house1':'f72d97231d488a012e93ec395b22bb1fc8f923b33d33bd9fa44b186296c8433d',
 'island_ferry':'4b79406b1a8957b6b2321941ed3ac5143bb222440ad59d258426bba8825d52bc',
 'birth_island_tree_north':'1025d22650cbbae2c555bc05919b0ad1e6a851577eb2f0c17160849bb2434e7c',
 'birth_island_tree_west':'cd0cf23972f35f25d2d5d3b3347a99d411e047768b0ab8ae817eb247979a98e9',
 'birth_island_tree_east':'5401cacd8f7c726130650c688dd82577be345ba3d9001a906f7153f9d06a0ef3',
}


def layout_for(art,name):
    mid,x,y,w,d,_=CASES[name];g=art.map_cells(art.maps[mid]);return [r[x:x+w] for r in g[y:y+d]]


def color(source,rgb):
    choices=np.argwhere((np.asarray(source.convert('RGB'))==rgb).all(axis=2));assert len(choices),rgb
    y,x=map(int,choices[0]);return material((x,y,x+1,y+1))


def tree(source,north):
    cx=24 if north else 32;cz=24 if north else 28;radius=21.8 if north else 25
    dark=color(source,(58,140,49));mid=color(source,(132,197,99));light=color(source,(181,255,132))
    bark=color(source,(107,90,90));bark_light=color(source,(165,99,90))
    parts=[p.round_part('cylinder',[cx,cz+6],4.5,[0,23],bark),p.round_part('cylinder',[cx,cz+6],6,[0,3],bark_light)]
    # Root flares stay strictly on the source's central solid footprint.
    for dx,dz in ((-4,2),(4,2),(0,10)):
        parts.append(p.round_part('cone',[cx+dx,cz+dz],[4,3],[0,7],bark))
    # Broad, layered leaf clusters follow the round source canopy. Lowest
    # overhanging foliage is eighteen voxels up, leaving WALK cells clear.
    # The hanging central foliage can descend farther because it remains
    # wholly over the two-by-two solid trunk footprint.
    for low,high,rad in ((8,11,8),(11,14,11.5),(14,18,14),(18,23,15.5)):
        # The side trees have a three-row source depth. Keep the last lower
        # leaf ring inside its front boundary rather than relying on preview
        # clipping of a radius extending past z48.
        parts.append(p.round_part('cylinder',[cx,cz+6],[rad*(1.16 if north else 1),rad if north else min(rad,14)],[low,high],dark))
    parts.append(p.round_part('dome',[cx,cz],[radius,18.5],[18,32],dark))
    for dx,dz,r in ((-13,-3,10),(0,-8,12),(13,-3,10),(-12,8,10),(0,10,12),(12,8,10)):
        parts.append(p.round_part('dome',[cx+dx,cz+dz],[r,9],[21,34],mid))
    for dx,dz in ((-9,0),(9,0),(0,-6),(0,8)):
        parts.append(p.round_part('dome',[cx+dx,cz+dz],[10,8],[28,39],light))
    parts.append(p.round_part('dome',[cx,cz],[9,8],[35,43],mid))
    return {'height':44,'terrain':[] if north else [[0,2],[3,2]],'parts':parts}


def ferry(source):
    pale=color(source,(214,206,222));gray=color(source,(140,140,148))
    dark=color(source,(66,74,107));orange=color(source,(239,148,16))
    brown=color(source,(173,82,66));cream=color(source,(255,255,148))
    parts=[box((18,94),(16,78),(0,21),p.paint([18,62,94,80],top=[20,70,26,74],side=[20,65,27,77],back=[18,62,94,80])),
           box((16,96),(15,80),(0,4),pale),box((17,95),(16,79),(20,24),gray)]
    # A rounded orange roof, ribs and a gray cabin with three dark windows.
    parts.append({'shape':'barrel','along':'z','x':[16,96],'z':[16,67],'y':[24,43],
                  'paint':p.paint([16,16,96,56],top=[16,16,96,56],side=[16,16,24,54],back=[16,16,96,56])})
    for x in range(20,96,8):
        # Thin raised longitudinal ribs preserve the source's repeated roof bands.
        rel=(x-56)/40;h=24+19*max(0,1-rel*rel)**.5
        parts.append(box((x,x+1),(17,66),(h-.5,h+1),cream))
    parts.append(box((16,96),(66,69),(22,25),brown))
    for x in (23,47,71):
        parts.append(box((x,x+18),(78,79),(13,21),dark))
    # The native triangular stern insignia stays on its own pale band.
    parts.append(box((17,95),(78,80),(4,13),pale))
    parts.append(box((48,64),(79,80),(5,13),p.paint([48,66,64,78],side=[48,66,64,78])))
    # Upper gangway is native WALK/warp terrain. The actual cabin doorway opens
    # immediately south of it, with no new low solid across that source path.
    parts.append(box((48,64),(15,26),(0,20),carve=True))
    parts.append(box((48,64),(25,26),(0,20),dark))
    # Four source side fixtures are small hull pods with circular orange
    # grilles. Open rings/crossbars preserve their dark cutouts as actual air.
    for x in (8,104):
        for z in (28,60):
            parts.append(box((x-5,x+5),(z-9,z+17),(0,7),pale))
            parts.append(box((x-5,x+5),(z-9,z+13),(6,10),brown))
            parts.append(box((x-6,x+6),(z+9,z+17),(7,11),gray))
            parts.append(p.round_part('cylinder',[x,z],7,[10,13],orange))
            parts.append(p.round_part('cylinder',[x,z],5,[10,14],carve=True))
            parts.append(box((x-1,x+1),(z-6,z+6),(11,14),orange))
            parts.append(box((x-6,x+6),(z-1,z+1),(11,14),orange))
            parts.append(box((12,20) if x==8 else (92,100),(z-2,z+2),(7,10),gray))
    return {'height':45,'terrain':[[0,0],[2,0],[3,0],[4,0],[6,0]],'parts':parts}


def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);out={}
    for name in CASES:
        layout=layout_for(art,name);source=art.drawing(layout,'BAKED').convert('RGB')
        assert hashlib.sha256(source.tobytes()).hexdigest()==SOURCE_SHA256[name]
        if name=='dewford_house1':
            spec=house(37);spec['terrain']=[]
            # Keep the native WALK rear-roof projection free of a ground-level
            # wall. The reviewed roof still overhangs this row above y28.
            spec['parts'][0]['z'][0]=16
        elif name=='island_ferry':spec=ferry(source)
        else:spec=tree(source,name.endswith('_north'))
        ident='emerald_object_'+name;out[ident]={'id':ident,'atlas':'g3q','tiles':quarters(layout),**spec}
    return out


def evidence(art,entry,name):
    layout=layout_for(art,name);w=len(layout[0]);d=len(layout)
    places=building_catalog({'game':'emerald','cells':{},'buildings':[entry]},art)[0]['occurrences'];out=[]
    for loc in places:
        source=art.maps[loc['map']];raw=base64.b64decode(source['cells']);cells=[]
        for dy,row in enumerate(layout):
            for dx,key in enumerate(row):
                x,y=loc['x']+dx,loc['y']+dy;i=(y*source['w']+x)*4
                cells.append({'x':x,'y':y,'local':[dx,dy],'key':key,'metatile':int.from_bytes(raw[i:i+2],'little'),'collision':raw[i+2],'elevation':raw[i+3],'terrain_excluded':[dx,dy] in entry['terrain']})
        out.append({**loc,'width':w,'depth':d,'cells':cells,'warps':[q for q in source['warps'] if loc['x']<=q['x']<loc['x']+w and loc['y']<=q['y']<loc['y']+d]})
    assert len(out)==CASES[name][-1],(name,len(out));return out


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args()
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);out=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for name in CASES:
        ident='emerald_object_'+name;entry=out[ident];source=p.drawing(entry,BUNDLE);target=OUT/name;target.mkdir(exist_ok=True);places=evidence(art,entry,name)
        assert all(e['id']!=ident and e['tiles']!=entry['tiles'] for e in table['buildings'])
        source.save(target/'source.png');source.resize((source.width*5,source.height*5),Image.Resampling.NEAREST).save(target/'source-enlarged.png')
        for i,loc in enumerate(places):
            x,y=loc['x']*16,loc['y']*16;crop=art.map_image(loc['map']).crop((x-16,y-16,x+source.width+16,y+source.height+16));ImageDraw.Draw(crop).rectangle((16,16,source.width+15,source.height+15),outline='red');crop.resize((crop.width*3,crop.height*3),Image.Resampling.NEAREST).save(target/f'context-{i:02}.png')
        if args.previews:
            o,c=parts_model.build(entry,np.asarray(source));parts_model.picture(o,c,target/'view.png',4);parts_model.front_view(o,c,target/'front.png',5);parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(c,(0,2,1,3))[:,:,::-1],target/'side.png',5)
        reason=('Exact third Dewford blue-roof house source variant reuses reviewed b037 architecture and native new doorway/facade pixels, with its rear wall starting at z16 to respect the top WALK row. Only the roof overhang enters those columns above y28; native movement and warp are preserved.' if name=='dewford_house1' else
                'Exact seven-by-five island ferry appears at Birth Island and Navel Rock. Reconstruct one continuous striped roof and gray cabin/hull with four orange side grilles; preserve the three-cell native gangway and warp, and exclude both upper water/boundary corners.' if name=='island_ferry' else
                'Exact large Birth Island broadleaf tree source pattern: a rooted brown trunk and flared roots support a layered rounded crown using only native leaf colors. Pure foreground grass corners remain terrain. Where source canopy projects onto WALK columns, foliage starts at least eighteen voxels above the floor and the roots remain on the central solid cells.')
        row={'key':ident,'kind':'building','label':name.replace('_',' '),'family':'dewford-source-complete-objects','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(entry),'source_pixel_sha256':hashlib.sha256(source.tobytes()).hexdigest(),'source_pixel_mode':'RGB','occurrences':places,'terrain_exclusions':entry['terrain'],'reason':reason,'evidence':[{'source':(target/'source.png').relative_to(ROOT).as_posix(),'contexts':[(target/f'context-{i:02}.png').relative_to(ROOT).as_posix() for i in range(len(places))]},{'python_front':(target/'front.png').relative_to(ROOT).as_posix(),'python_side':(target/'side.png').relative_to(ROOT).as_posix(),'python_angled':(target/'view.png').relative_to(ROOT).as_posix()}],'visual_status':'source inspected; candidate Python/runtime review pending','user_approved':False}
        if name=='dewford_house1':
            old=next(e for e in table['buildings'] if e['id'].startswith('emerald_b037_'));row['nearest_existing_template']=old['id'];row['source_quarter_substitutions']=[{'x':x,'y':y,'old':a,'new':b} for y,(ar,br) in enumerate(zip(old['tiles'],entry['tiles'])) for x,(a,b) in enumerate(zip(ar,br)) if a!=b]
        ledger.append(row);(target/'spec.json').write_text(json.dumps(entry,indent=2)+'\n')
    (OUT/'updates.json').write_text(json.dumps({'cells':{},'buildings':out},indent=2)+'\n');(OUT/'added-ledger.json').write_text(json.dumps(ledger,indent=2)+'\n')
    print(json.dumps({'added':len(out),'occurrences':sum(len(r['occurrences']) for r in ledger)}))


if __name__=='__main__':main()
