"""Source-reviewed S.S. Tidal and Abandoned Ship furniture and cargo.

All 108 source cells were inspected in front/east/top and in their actual map
neighborhoods. Explicit roles below describe objects, not inferred silhouettes.
Complete beds, tables, desks and cargo are authored before per-cell slicing.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw
from tools.shape_fit.emerald_pass.towns import ROOT, BASELINE, BUNDLE, INVENTORY, rows_for, texel, digest
from tools.shape_fit.blue_pass.common import Model, decode, render
from tools.model_gallery import EmeraldArt

OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-ship'
GROUP='inside_ship'
# Explicitly selected from four source/orthographic and seven context sheets.
CHAIRS={12:'left',48:'left',49:'right',52:'left',53:'left',66:'right'}
BINS={27,50,54,61,68}
TABLES={5:('left',0),10:('right',0),40:('left',0),41:('right',0),
        46:('left',1),47:('right',1),55:('middle',0),62:('middle',1)}
BEDS={36:('left',0,0),37:('left',1,0),42:('left',0,1),43:('left',1,1),
      38:('right',0,0),39:('right',1,0),44:('right',0,1),45:('right',1,1),
      56:('blue',0,0),57:('blue',1,0),63:('blue',0,1),64:('blue',1,1)}
DESKS={71:(0,0),72:(1,0),76:(0,1),77:(0,1),78:(1,1)}
HOLES={81:'left carpet edge',82:'right carpet edge',83:'left grated deck edge',84:'right grated deck edge'}
CARGO={90:('gold','left','lid'),91:('gold','middle','lid'),92:('gold','right','lid'),
       93:('olive','left','lid'),94:('olive','middle','lid'),95:('olive','right','lid'),
       96:('gold','left','lid'),97:('gold','middle','lid'),98:('gold','right','lid'),
       99:('olive','left','lid'),100:('olive','middle','lid'),101:('olive','right','lid'),
       102:('gold','left','front'),103:('gold','middle','front'),104:('gold','right','front'),
       105:('olive','left','front'),106:('olive','middle','front'),107:('olive','right','front')}
RETAIN={
 1:('upper stair landing left','The authored narrow gray stair landing borders the grated deck; the orange landing is a surface, not an upright sign.'),
 2:('upper stair rail right','Its stepped white uprights and continuous handrail follow the actual stair head at the corridor corner.'),
 3:('lower stair silhouette against void','The occupied gray steps follow the diagonal stair edge and exclude the black exterior corner.'),
 4:('hold upper stair rail','Existing descending metal rail profile meets the lower rail beside the hold wall; its source-facing corners remain readable.'),
 6:('stair landing lower railing','The hand-authored thin railing occupies only the orange/gray rail; grated floor remains outside the upright pieces.'),
 7:('lower stair railing over void','The metal supports descend toward the ship exit while the black outer half stays empty.'),
 8:('hold grated stair warp','Existing walkable stepped landing preserves the actual deck stair direction and warp metadata.'),
 9:('hold lower stair rail','The gray descending support joins the upper rail in the two-cell hold stair assembly.'),
 11:('open corridor floor warp','Shallow boundary frame follows the opening at the interior corridor bend; its walkable center must remain open.'),
 13:('corridor upper striped wall center','Thin slatted upper wall and continuous cap join the striped corridor wall rather than a separate freestanding object.'),
 14:('corridor upper striped wall right','Solid hand-authored wall return keeps the upper cap and red/gold slats aligned at the corridor corner.'),
 15:('corridor door upper border','Existing upper door cap preserves the gold portal border and source wall top.'),
 16:('corridor upper striped wall left','Mirrored hand-authored return retains the cap and slats at the left doorway edge.'),
 17:('corridor outside wall upper left corner','The block wall follows the left bend around the white unmapped interior.'),
 18:('corridor outside wall upper right corner','The opposite wall bend preserves its thin outside edge and continuous upper trim.'),
 19:('corridor lower striped wall center','Existing lower panel and gray horizontal rail join the striped upper row and adjacent corner panels.'),
 20:('corridor lower striped wall right','Solid right return joins the center wall while preserving its gray rail and gold lower trim.'),
 22:('corridor lower striped wall left','Existing half-width side wall continues the horizontal rail at the broken-door junction.'),
 23:('corridor lower outside corner left','Full wall corner has clean front and side surfaces and meets the upper wall bend.'),
 24:('corridor lower outside corner right','Opposite lower wall corner retains its projecting gray rail and source-side edge.'),
 25:('corridor void boundary left','Thin vertical boundary keeps the white unmapped interior separate from the corridor without inventing a prop.'),
 26:('corridor void boundary right','Opposite thin boundary preserves the continuous empty interior edge.'),
 28:('cabin lifebuoy upper-left wall fragment','Source quarter of the wall-mounted ring crosses a four-cell wall/rug junction. Existing fragment keeps that exact local artwork and wall continuation.'),
 29:('cabin lifebuoy upper-right wall fragment','Matching upper ring quarter continues the neighboring wood panel without changing its map-row placement.'),
 30:('cabin left wall and rug junction','Existing wall return separates the brown vertical panel, pale rug and small lower orange trim in the actual bed alcove.'),
 31:('cabin lifebuoy lower-left with rug','Ring corner and wood trim remain registered with the upper wall quarter and adjoining bed rug.'),
 32:('cabin lifebuoy lower-left panel','Existing source-facing ring fragment sits on the low brown wall molding; no separate floor object is implied.'),
 33:('cabin lifebuoy lower-right panel','Complementary ring fragment continues the same wood molding beside the cabin rug.'),
 34:('cabin lifebuoy lower-right with rug','Existing ring fragment and orange bed-alcove trim retain their distinct surfaces at the wall/rug intersection.'),
 35:('cabin right wall and rug junction','The wood return, pale rug corner and orange trim remain aligned with the adjoining bed alcove.'),
 58:('cabin left alcove wall trim','This source alias keeps the small orange wall trim over the left-facing bed rug; existing shallow geometry follows that wall boundary.'),
 59:('cabin right alcove wall trim','Mirrored shallow wall/trim junction closes the right-facing bed alcove.'),
 65:('corridor left notice panel','Hand-authored solid wall carries the white notice plate on the striped dado with continuous side and top faces.'),
 69:('cabin door upper-left jamb','Existing vertical wood jamb follows the left edge of the gold cabin door and joins its lower jamb.'),
 70:('cabin door upper-right jamb','Right jamb retains the source wood molding and thin gray door edge.'),
 75:('lower corridor stair upper rail','The gray posts and orange top railing follow the actual stepped landing, joining the lower rail cell.'),
 79:('lower corridor stair warp landing','Existing diagonal walkable stair volume has the correct rising direction across the grated deck.'),
 80:('lower corridor descending stair rail','The metal stair handrail follows the same descending profile as the neighboring warp stair.'),
 85:('broken cabin door crown','The on-linked broken upper door retains the irregular gold fragments and dark opening; filling it would close the source breach.'),
 86:('S.S. Tidal stair warp landing','The hand-authored walkable stair has a clear diagonal rise and preserves the purple/gray landing.'),
 87:('broken cabin door opening','Thin gold fragments surround an open warp frame. Its existing walkable center and support relation remain unchanged.'),
 88:('S.S. Tidal lower stair railing','Thin gray/red rail pieces lie along the actual stair boundary above the purple deck.'),
 89:('underwater ship doorway warp','The thin dark doorway is the actual underwater passage; retain its walkable warp frame rather than make a raised floor prop.'),
 108:('corridor right notice panel','The mirrored hand-authored notice wall has continuous top and side faces and follows the corridor bend.'),
}


def decisions():
    out={i:('retained',label,reason) for i,(label,reason) in RETAIN.items()}
    for i,side in CHAIRS.items():out[i]=('changed',f'{side}-back wooden cabin chair','Author four feet, a raised seat, separate back posts and wood slats; replace the almost-flat chair footprint while preserving its seat flag.')
    for i in BINS:out[i]=('changed','round gray cabin waste bin','Round fluted metal body with a real open mouth and inner floor; discard the orange carpet or grated deck from the physical bin surfaces.')
    for i,(side,row) in TABLES.items():out[i]=('changed',f'green dining table {side} '+('front apron' if row else 'rear tabletop'),'Slice the complete rounded green dining table: pale rim, flat raised top, dark apron and four corner legs. Repeating middle tiles extend the actual long table without interior legs or seams.')
    for i,(orientation,col,row) in BEDS.items():out[i]=('changed',f'{orientation} cabin bed '+('foot' if row else 'head')+f' piece {col+1}','Author the complete two-by-two cabin bed and rug: metal legs/frame, raised colored mattress, separate pale pillow and head rail. Slice the shared geometry at the actual map-cell boundaries.')
    for i,(col,row) in DESKS.items():out[i]=('changed','cabin writing desk '+('and chair lower ' if row else 'upper ')+('left' if col==0 else 'right'),'Model the actual two-by-two writing-desk assembly with a flat pale top, four legs and centered wooden chair; existing floor-colored fragments are excluded from furniture.')
    for i,side in HOLES.items():out[i]=('changed',f'broken deck opening {side}','This source is a hole in the deck, not a raised boulder. Keep a shallow dark bottom with the source jagged floor edge painted horizontally; preserve the map collision metadata.')
    for i,(color,side,part) in CARGO.items():out[i]=('changed',f'{color} hold cargo {side} {part}','Join rectangular cargo pieces into the actual two/three-cell-wide hold stacks. Continuous solid sides and dark front ribs replace tapered, punctured extrusions; upper cargo boards are painted horizontally on the lid.')
    for i,upper in [(21,False),(51,True)]:out[i]=('changed','gold cabin door '+('crown' if upper else 'lower panel'),'Straight recessed gold door panel and narrow wood backing join through the existing crown on-link; the lower door no longer narrows to a pedestal.')
    for i,upper in [(60,True),(67,False)]:out[i]=('changed','cabin lifebuoy '+('upper wall' if upper else 'lower wall'),'The complete on-linked wall panel carries one raised circular red/white lifebuoy with an open center revealing the wood backing, rather than a tapered pedestal.')
    for i,side in [(73,'left'),(74,'right')]:out[i]=('changed',f'cabin lower {side} door jamb','A straight wood wall return preserves the door-edge molding and dark foot trim while removing accidental pinholes in the old solid panel.')
    assert set(out)==set(range(1,109))
    return out


def slice_model(model,x,z):
    out=Model(height=model.occ.shape[0]);out.occ=model.occ[:,z:z+16,x:x+16].copy();out.paint=model.paint[:,z:z+16,x:x+16].copy();return out


def materials(model,sprite,palette):
    old=model.paint.copy()
    for code,(color,rect) in palette.items():model.paint[model.occ & (old==code)]=texel(sprite,color,rect)
    return model


def wooden_chair(sprite,side):
    m=Model();wood=texel(sprite,(189,140,49),(1,1,15,15));dark=texel(sprite,(82,82,90),(1,1,15,15))
    for x in (3,11):
        for z in (3,11):m.box((x,x+2),(0,7),(z,z+2),dark)
    m.box((3,13),(6,8),(2,14),wood)
    x=2 if side=='left' else 12
    for z in (2,12):m.box((x,x+2),(7,15),(z,z+2),dark)
    m.box((x,x+2),(13,15),(2,14),wood)
    for z in (4,7,10):m.box((x,x+2),(9,13),(z,z+1),wood)
    return m


def waste_bin(sprite):
    m=Model();metal=texel(sprite,(165,173,198),(2,1,14,15));light=texel(sprite,(239,247,255),(2,1,14,15));dark=texel(sprite,(74,82,123),(2,1,14,15))
    m.cylinder((8,8),5,(0,11),metal);m.cylinder((8,8),5.5,(10,13),light);m.cylinder((8,8),3.5,(9,14),cut=True)
    m.cylinder((8,8),3.5,(8,9),dark)
    y,z,x=np.indices(m.occ.shape);m.paint[m.occ & (y<10)&((x%4==0)|(z%4==0))]=dark
    return m


def dining_table():
    m=Model(width=64,depth=32)
    # Rounded rectangle top, represented by overlapping boxes and end cylinders.
    m.box((6,58),(10,13),(2,29),1);m.box((2,62),(10,13),(6,25),1)
    for x in (6,58):
        for z in (6,25):m.cylinder((x,z),4,(10,13),1)
    m.box((6,58),(12,13),(4,27),2);m.box((4,60),(12,13),(6,25),2)
    for x in (6,58):
        for z in (6,25):m.cylinder((x,z),2,(12,13),2)
    m.box((4,60),(8,10),(4,27),3)
    for x in (4,57):
        for z in (4,24):m.box((x,x+3),(0,10),(z,z+3),4)
    return m


def bed(orientation):
    m=Model(width=32,depth=32);left=orientation=='left';x0,x1=(8,31) if left else (1,24)
    m.box((0,32),(0,1),(0,32),9) # Source rug remains a horizontal surface.
    m.box((x0,x1),(0,1),(3,30),cut=True)
    for x in (x0+1,x1-3):
        for z in (4,26):m.box((x,x+2),(0,6),(z,z+2),3)
    m.box((x0,x1),(4,6),(3,29),3);m.box((x0+1,x1-1),(6,9),(4,28),2)
    m.box((16,27) if left else (5,16),(9,11),(5,10),1)
    for x in (x0,x1-2):m.box((x,x+2),(6,13),(3,5),3)
    m.box((x0,x1),(12,14),(3,5),3);m.box((x0,x1),(7,9),(28,30),3)
    return m


def desk():
    m=Model(width=32,depth=32)
    for x in (2,27):
        for z in (3,12):m.box((x,x+3),(0,12),(z,z+3),3)
    m.box((1,31),(11,14),(2,16),1);m.box((2,30),(10,11),(3,15),3)
    for x in (10,21):
        for z in (20,28):m.box((x,x+2),(0,7),(z,z+2),3)
    m.box((9,24),(6,8),(19,31),4)
    m.box((9,11),(8,15),(29,31),3);m.box((22,24),(8,15),(29,31),3)
    m.box((9,24),(13,15),(29,31),4)
    for x in (12,15,18,21):m.box((x,x+1),(8,13),(29,31),4)
    return m


def floor_hole(sprite):
    m=Model();m.box((0,16),(0,1),(0,16))
    y,z,x=np.indices(m.occ.shape);m.paint[m.occ]=(z*16+x)[m.occ]
    return m


def cargo(sprite,side,part,color):
    m=Model();m.box((0,16),(0,16),(0,16))
    # Solid local boxes join into a complete multi-cell crate. The narrow outer
    # frame belongs only to true left/right cells, never repeating middle cells.
    dark=texel(sprite,(74,74,99));wood=texel(sprite,(165,165,90) if color=='olive' else (189,140,57))
    m.project(side=wood,top=wood,back=wood)
    y,z,x=np.indices(m.occ.shape)
    if part=='lid':m.paint[y==15]=(z*16+x)[y==15]
    else:
        # Dark ribs on the lower front are source-local, not silhouette holes.
        m.paint[z==15]=((15-y)*16+x)[z==15]
        m.paint[y==15]=(np.minimum(15,z)*16+x)[y==15]
    if side=='left':m.paint[x==0]=dark
    if side=='right':m.paint[x==15]=dark
    return m


def door(sprite,upper):
    m=Model();wood=texel(sprite,(189,140,57));gold=texel(sprite,(239,222,99));dark=texel(sprite,(82,82,115))
    m.box((0,16),(0,8 if upper else 16),(4,8),wood)
    m.box((2,14),(0,5 if upper else 16),(8,10),gold)
    m.box((3,13),(0,4 if upper else 15),(9,10),dark)
    m.project(side=wood,back=wood)
    if upper:m.paint[m.occ & (np.indices(m.occ.shape)[0]==7)]=wood
    return m


def lower_jamb(sprite):
    m=Model();wood=texel(sprite,(189,140,57));dark=texel(sprite,(74,74,99))
    m.box((0,16),(0,16),(4,9),wood);m.box((0,16),(0,2),(3,10),dark)
    m.project(side=wood,back=wood)
    return m


def life_ring(sprite,upper):
    m=Model(height=32);wood=texel(sprite,(189,140,57));red=texel(sprite,(222,74,33));white=texel(sprite,(247,247,247));dark=texel(sprite,(74,74,99))
    m.box((0,16),(0,25),(4,7),wood)
    y,z,x=np.indices(m.occ.shape);r=((x+.5-8)**2+(y+.5-16)**2)**.5
    ring=(r>=3.2)&(r<6.2)&(z>=7)&(z<10);m.put(ring,white)
    m.paint[ring & (((x<6)&(y>18))|((x>9)&(y<13))|((x<6)&(y<13))|((x>9)&(y>18)))]=red
    s=slice(16,32) if upper else slice(0,16);out=Model();out.occ=m.occ[s].copy();out.paint=m.paint[s].copy();return out


def generate(table,art):
    rows=rows_for(GROUP);updates={}
    for i,(status,label,reason) in decisions().items():
        if status!='changed':continue
        key=rows[i-1]['key'];sprite=art.cell(key,'BAKED')
        if i in CHAIRS:m=wooden_chair(sprite,CHAIRS[i])
        elif i in BINS:m=waste_bin(sprite)
        elif i in TABLES:
            side,row=TABLES[i];m=slice_model(dining_table(),{'left':0,'middle':16,'right':48}[side],row*16)
            materials(m,sprite,{1:((247,247,222),(0,0,16,16)),2:((181,230,132),(0,0,16,16)),3:((74,74,99),(0,0,16,16)),4:((74,74,99),(0,0,16,16))})
        elif i in BEDS:
            orient,col,row=BEDS[i];m=slice_model(bed(orient),col*16,row*16)
            materials(m,sprite,{1:((247,247,247),(0,0,16,16)),2:(((148,165,206) if orient=='blue' else (181,230,140)),(0,0,16,16)),3:((82,82,107),(0,0,16,16))})
            # Only rug pixels are projected horizontally; furniture has its own materials.
            yy,zz,xx=np.indices(m.occ.shape);rug=m.occ & (yy==0)
            m.paint[rug]=(zz*16+xx)[rug]
        elif i in DESKS:
            col,row=DESKS[i];m=slice_model(desk(),col*16,row*16)
            materials(m,sprite,{1:((222,230,173),(0,0,16,16)),3:((82,82,107),(0,0,16,16)),4:((189,140,49),(0,0,16,16))})
        elif i in HOLES:m=floor_hole(sprite)
        elif i in CARGO:color,side,part=CARGO[i];m=cargo(sprite,side,part,color)
        elif i in (21,51):m=door(sprite,i==51)
        elif i in (60,67):m=life_ring(sprite,i==60)
        elif i in (73,74):m=lower_jamb(sprite)
        else:raise ValueError(i)
        updates[key]=m.entry(table['cells'][key],'hand-emerald-ship-review',trim=True)
    return updates


ASSEMBLY_POSITIONS=[
 ('ship_dining_table_small',[[5,10],[46,47]]),('ship_dining_table_small_alt',[[40,41],[46,47]]),
 ('ship_dining_table_long',[[40,55,55,41],[46,62,62,47]]),
 ('ship_cabin_bed_left',[[36,37],[42,43]]),('ship_cabin_bed_right',[[38,39],[44,45]]),
 ('ship_cabin_bed_blue',[[56,57],[63,64]]),('ship_writing_desk',[[71,72],[76,78]]),
 ('ship_writing_desk_tidal',[[71,72],[77,78]]),
 ('ship_cargo_gold_2',[[96,98],[96,98],[102,104]]),
 ('ship_cargo_olive_3',[[99,100,101],[99,100,101],[105,106,107]]),
 ('ship_cargo_gold_3',[[90,91,92],[96,97,98],[102,103,104]]),
 ('ship_cargo_mixed_3',[[90,91,92],[99,100,101],[105,106,107]]),
 ('ship_cargo_mixed_2',[[93,95],[96,98],[102,104]]),
 ('ship_cargo_pale_olive_3',[[93,94,95],[99,100,101],[105,106,107]]),
 ('ship_gold_door',[[51],[21]]),('ship_wall_lifebuoy',[[60],[67]])]


def assemblies():
    rows=rows_for(GROUP)
    return [{'id':'emerald_'+name,'kind':'stack (model as one)' if name in ('ship_gold_door','ship_wall_lifebuoy') else 'object (model as one)',
             'note':name.replace('_',' '),'layout':[[rows[i-1]['key'] for i in line] for line in layout]} for name,layout in ASSEMBLY_POSITIONS]


def assembly_previews(table,updates,art):
    from tools.shape_fit.hand_check import front,picture
    evidence=[]
    for item in assemblies():
        target=OUT/'assemblies'/item['id'];target.mkdir(parents=True,exist_ok=True)
        layout=item['layout'];art.drawing(layout,'BAKED').resize((len(layout[0])*96,len(layout)*96),Image.Resampling.NEAREST).save(target/'source.png')
        stack=item['kind'].startswith('stack')
        for label,cells in [('before',table['cells']),('after',{**table['cells'],**updates})]:
            slabs=[];colors=[]
            for row in (layout[::-1] if stack else layout):
                objects=[];paints=[]
                for key in row:
                    occ,paint=decode(cells[key]);pixels=np.asarray(art.cell(key,'BAKED').convert('RGB'));objects.append(occ);paints.append(pixels[paint//16,paint%16])
                slabs.append(np.concatenate(objects,axis=2));colors.append(np.concatenate(paints,axis=2))
            occ=np.concatenate(slabs,axis=0 if stack else 1);paint=np.concatenate(colors,axis=0 if stack else 1)
            picture(occ,paint,target/f'{label}-angled.png',8);front(occ,paint,target/f'{label}-front.png',10)
        found=[]
        for p in art.locations.get(layout[0][0],[]):
            g=art.map_cells(art.maps[p['map']]);x,y=p['x'],p['y']
            if all(y+dz<len(g) and x+dx<len(g[y+dz]) and g[y+dz][x+dx]==k for dz,line in enumerate(layout) for dx,k in enumerate(line)):found.append(p)
        evidence.append({'id':item['id'],'layout':layout,'occurrences':found})
    (OUT/'assembly-placements.json').write_text(json.dumps(evidence,indent=2)+'\n')


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args()
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);updates=generate(table,art);rows=rows_for(GROUP);ledger=[];OUT.mkdir(parents=True,exist_ok=True)
    for i,(status,label,reason) in sorted(decisions().items()):
        row=rows[i-1];key=row['key'];target=OUT/key.removeprefix('g3q/');target.mkdir(parents=True,exist_ok=True);sprite=art.cell(key,'BAKED');sprite.save(target/'source.png')
        evidence=[{'source_tilesets':row['tileset_membership'],'map_occurrences':row['occurrences'],'contact_sheet':str((INVENTORY/row['contact_page']).relative_to(ROOT)),'contact_position':i},
          {'map_context_sheet':f'local/model-gallery/emerald-pass-study/towns/general__inside_ship/contexts/page-{(i-1)//16+1:03d}.png','context_position':i},
          {'source':str((target/'source.png').relative_to(ROOT)),'source_pixels_sha256':hashlib.sha256(sprite.tobytes()).hexdigest()}]
        if args.previews and key in updates:render(updates[key],sprite,target/'after');render(table['cells'][key],sprite,target/'before')
        if key in updates:evidence.append({'candidate_preview':str((target/'after/angled.png').relative_to(ROOT))})
        ledger.append({'key':key,'kind':'cell','label':label,'family':GROUP,'decision':status,'reason':reason,'baseline_entry_sha256':digest(table['cells'][key]),'evidence':evidence,'visual_status':'source/map/orthographic inspection; changed candidate Minecraft review pending'})
    (OUT/'updates.json').write_text(json.dumps({'cells':updates,'buildings':{}},indent=2)+'\n');(OUT/'ledger.json').write_text(json.dumps(ledger,indent=2)+'\n');(OUT/'entities.json').write_text(json.dumps(assemblies(),indent=2)+'\n')
    if args.previews:assembly_previews(table,updates,art)
    print(json.dumps({'reviewed':len(ledger),'changed':len(updates),'retained':len(ledger)-len(updates)}))


if __name__=='__main__':main()
