"""Fortree's reviewed conifer seams, hedges, Gym marker and house fallbacks."""
from __future__ import annotations
import argparse
import hashlib
import json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model,decode,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,texel,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses,preview
from tools.shape_fit.emerald_pass import vegetation,scenery

OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-fortree-foliage'
RETAINED={21,22,23,25,26,27,28,29,33,34,35,36}
CROWNS={19,20,24,47,65,127,130}
MIDDLES={40,41,42,43}
BASES={52,108,111,116}
CHANGED=CROWNS|MIDDLES|BASES|{53,56,57,58,59,61,62,63,64}
OWNED=RETAINED|CHANGED


def decisions():
    labels={19:'conifer crown below west cliff return',20:'conifer crown below east cliff return',21:'treehouse western leaf roof',22:'treehouse central leaf roof',23:'treehouse eastern leaf roof',24:'conifer crown below cliff center',25:'western platform and forest upper seam',26:'treehouse west upper doorway post',27:'treehouse upper doorway recess',28:'treehouse east upper doorway post',29:'eastern platform and forest upper seam',33:'western platform and forest lower seam',34:'treehouse west lower doorway post',35:'treehouse east lower doorway post',36:'eastern platform and forest lower seam',40:'forest below west platform corner',41:'forest below palm crown and deck',42:'forest below full platform edge',43:'forest below east platform corner',47:'conifer crown projected over grass',52:'conifer foot on patterned Fortree ground',53:'Gym marker rounded crown',56:'Gym marker face and pedestal',57:'hedge western rounded end',58:'hedge middle foliage',59:'hedge eastern rounded end',61:'hedge crown corner projected over grass',62:'long grass and western hedge tip',63:'long grass and central hedge tip',64:'long grass and eastern hedge tip',65:'conifer crown below horizontal cliff rim',108:'conifer base behind west hedge corner',111:'conifer base hidden behind hedge center',116:'conifer base behind east hedge corner',127:'conifer crown at Weather Institute west side',130:'conifer crown at Weather Institute southwest corner'}
    out={}
    for n in sorted(OWNED):
        if n in RETAINED:
            if n in (25,29,33,36):reason='Existing hand-authored mixed cell keeps the explicit narrow golden platform edge beside a separate green forest profile. Its source-specific orientation joins the actual treehouse deck, which is outside the new b124 house core. Retain the shaped fallback and original collision rather than stretching house geometry into the neighboring forest.'
            else:reason='This exact native leaf-roof/doorpost cell is covered by the complete b124 treehouse template at all six occurrences. Preserve its existing explicit hand-authored fallback, identity and collision while the whole template supplies continuous canopy, timber walls and doorway geometry.'
            decision='retained'
        elif n in CROWNS:
            decision='changed'
            reason='Replace the pinched sprite extrusion with the reviewed conifer upper foliage section, using only native green material. The neighboring cliff, platform or Weather Institute owns the non-tree edge projection; do not carry those pixels through the crown or invent a tree stack in the repeated forest column. Exact source collision/elevation and absent on relation remain unchanged.'
            if n==47:reason+=' All26 source placements are WALK0 grass projections; normal map planning keeps native floor here, while the neighboring solid forest supplies the tree mass.'
        elif n in MIDDLES:decision='changed';reason='This source is dense conifer forest immediately south of a palm canopy or golden platform. Replace the hourglass pedestal with the same connected middle foliage section used by adjoining reviewed forest cells. Paint only native foliage: the thin deck boundary belongs to the separate northern platform carrier, not a golden bar through the tree. Preserve all stack metadata and elevations.'
        elif n in BASES:
            decision='changed';reason='Use the reviewed grounded conifer base section with narrow roots and a broad lower foliage lobe, painted from this source only. Remove the old pinched pedestal and surrounding ground-colored side pixels.'
            if n in (108,111,116):reason+=' Add the source-proven low foreground hedge lip in the south of the cell; the center variant entirely hides the trunk and therefore uses foliage material only. Keep existing stack metadata; exact northern adjacency evidence is emitted separately for the shared middle owner.'
        elif n in (53,56):decision='changed';reason='Author one connected21-high gray Gym marker with a rounded framed face, readable native GYM lettering, shallow thickness and short grounded pedestal. Slice through the existing upper-to-lower on relation; exclude the patterned grass above the marker from all physical surfaces.'
        elif n in (57,58,59):decision='changed';reason='Author the actual low hedge as a grounded rounded continuous leaf mass with distinct western/middle/eastern profiles and native dark/light foliage, replacing the stepped sprite extrusion and grass-colored side stripe. Neighboring pieces meet across their shared cell edge.'
        else:decision='changed';reason='The source is a WALK0 projection above the solid hedge. Give the standalone model a low source-positioned hedge tip; the long-grass variants also have separate rooted grass blades behind it. Exclude the surrounding flat grass from raised geometry and preserve native runtime floor/tuft routing without adding walk or on metadata.'
        out[n]=(decision,labels[n],reason)
    return out


def leaf_model(sprite,section):
    whole,foliage=scenery.tree();start={'crown':16,'middle':8,'base':0}[section];m=Model();m.occ[:]=whole[start:start+16]
    m.paint[:]=vegetation.leaf_paint(m.occ.shape,sprite,(start,0,0),bright=section!='base')
    if section=='base':
        bark=np.any(np.all(np.asarray(sprite.convert('RGB'))==np.array([107,90,90]),axis=2))
        if bark:m.paint[m.occ&~foliage[:16]]=vegetation.color_uv(sprite,(107,90,90))
        else:m.occ[:]=foliage[:16]  # source hedge fully occludes the stem
    return m


def hedge(sprite,side='middle',lip=False):
    m=Model();cx={'west':12,'middle':8,'east':4}[side]
    m.ellipsoid((cx,6.5 if lip else 7.5,15 if lip else 8),(12,7 if lip else 8.5,7 if lip else 7.6))
    if lip:m.occ[:,:9]=False
    m.paint[:]=vegetation.leaf_paint(m.occ.shape,sprite,(12,0,0))
    return m


def marker(sprite,upper):
    m=Model(height=32);dark=texel(sprite,(41,49,90));rim=texel(sprite,(189,206,230));gray=texel(sprite,(99,99,123))
    m.box((5,11),(0,2),(6,11),dark);m.box((7,9),(1,5),(7,10),gray)
    m.box((2,14),(4,18),(5,11),rim);m.box((4,12),(2,20),(5,11),rim);m.box((6,10),(19,21),(6,10),gray)
    # Source front rows remain in their original 16-pixel sections.
    y,z,x=np.indices(m.occ.shape);face=m.occ&(z==10)&(x>=2)&(x<14)&(y>=3)&(y<20)
    m.paint[face]=((31-y if upper else 15-y)%16*16+x).astype(np.uint8)[face]
    rgb=np.asarray(sprite.convert('RGB'));color=rgb[m.paint//16,m.paint%16]
    ground=face&(color[...,1]>color[...,0])&(color[...,1]>color[...,2]);m.paint[ground]=gray
    start=16 if upper else 0;s=Model();s.occ[:]=m.occ[start:start+16];s.paint[:]=m.paint[start:start+16]
    return s


def grass_tip(sprite,n):
    m=Model();dark=vegetation.color_uv(sprite,(58,82,0));green=vegetation.color_uv(sprite,(58,140,49))
    if n!=61:
        for x in (2,6,10,14):
            for z in (2,6):
                h=11+(x+z)%4;m.box((x-1,x+1),(0,h),(z-1,z+1),green)
                m.box((max(0,x-2),min(16,x+2)),(4,7),(z-1,z+1),dark)
    side={61:'east',62:'west',63:'middle',64:'east'}[n]
    cx={'west':12,'middle':8,'east':4}[side]
    tip=Model();tip.ellipsoid((cx,2,16),(11,5,5));tip.paint[:]=vegetation.leaf_paint(tip.occ.shape,sprite,(20,0,0))
    m.paint[tip.occ]=tip.paint[tip.occ];m.occ|=tip.occ
    return m


def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('fortree');out={}
    for n in sorted(CHANGED):
        key=rows[n-1]['key'];sprite=art.cell(key,'BAKED');old=table['cells'][key]
        if n in CROWNS:m=leaf_model(sprite,'crown')
        elif n in MIDDLES:m=leaf_model(sprite,'middle')
        elif n in BASES:
            m=leaf_model(sprite,'base')
            if n in (108,111,116):
                h=hedge(sprite,{108:'west',111:'middle',116:'east'}[n],True);m.paint[h.occ]=h.paint[h.occ];m.occ|=h.occ
        elif n in (53,56):m=marker(sprite,n==53)
        elif n in (57,58,59):m=hedge(sprite,{57:'west',58:'middle',59:'east'}[n])
        else:m=grass_tip(sprite,n)
        out[key]=m.entry(old,'hand-emerald-fortree-foliage',trim=True)
    return out


def assemblies(art):
    result=[]
    def add(name,mapid,x,y,w,d,note):
        grid=art.map_cells(art.maps[mapid]);result.append({'id':'emerald_fortree_foliage_'+name,'kind':'object (model as one)','note':note,'layout':[r[x:x+w] for r in grid[y:y+d]],'occurrences':[{'map':mapid,'x':x,'y':y,'palette':'BAKED'}]})
    add('gym_marker','EM_FORTREE_CITY',26,9,1,2,'Complete Gym sign, existing upper on relation and exact base; patterned grass excluded from marker surfaces.')
    add('hedge_trees','EM_ROUTE119',4,0,4,3,'Actual four-wide hedge and conifer bases: source-specific left/center/right lower overlap.')
    add('hedge_long_grass','EM_ROUTE119',33,22,3,3,'Three-wide hedge with actual long-grass background and source-projected crown tips.')
    add('hedge_cliff','EM_ROUTE120',29,83,3,3,'Passable projected hedge corner below cliff and above the solid hedge end.')
    add('west_cliff_crown','EM_ROUTE119',0,37,2,3,'Native cliff above conifer crown and repeated forest column; no invented finite stack.')
    add('east_cliff_crown','EM_ROUTE119',5,40,2,3,'Opposite source cliff corner adjoining forest crown.')
    add('center_cliff_crown','EM_ROUTE120',8,19,1,3,'Source cliff face and crown directly below it.')
    add('rim_crown','EM_ROUTE120',24,21,3,3,'Horizontal cliff rim and two native crown variants in adjacent forest.')
    add('weather_crowns','EM_ROUTE119',0,30,4,4,'Two mixed Weather Institute conifers: neighboring building is reconstructed by b130; source seam cells remain independently reviewed.')
    add('patterned_base','EM_FORTREE_CITY',8,5,1,4,'Forest middle and exposed conifer base at patterned ground; source metadata retained.')
    add('platform_edge','EM_FORTREE_CITY',8,4,5,3,'Actual western treehouse platform and forest edge. Golden deck is separate; lower forest is middle foliage.')
    add('projected_crown','EM_FORTREE_CITY',10,10,1,4,'WALK grass crown projection above solid platform/forest seam; unchanged native floor routing.')
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args()
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('fortree');updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n,(decision,label,reason) in decisions().items():
        row=rows[n-1];key=row['key'];sprite=art.cell(key,'BAKED');target=OUT/key.split('/')[1];target.mkdir(exist_ok=True);sprite.save(target/'source.png')
        if args.previews and key in updates:render(table['cells'][key],sprite,target/'before');render(updates[key],sprite,target/'after')
        ledger.append({'key':key,'kind':'cell','label':label,'family':'fortree-forest-hedge-house-seam','decision':decision,'reason':reason,'baseline_entry_sha256':digest(table['cells'][key]),'evidence':[{'source_tilesets':row['tileset_membership'],'map_occurrences':witnesses(art,row),'contact_sheet':(INVENTORY/row['contact_page']).relative_to(ROOT).as_posix(),'contact_position':n},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'context_sheet':f'local/model-gallery/emerald-pass-study/towns/general__fortree/contexts/page-{(n-1)//16+1:03}.png'}],'visual_status':'source/context/front/east/top inspected; candidate preview/runtime pending'})
    entities=assemblies(art);live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';current=json.loads(live.read_text())
    if args.previews:
        for item in entities:
            target=OUT/'assemblies'/item['id'];target.mkdir(parents=True,exist_ok=True);layout=item['layout'];art.drawing(layout,'BAKED').resize((len(layout[0])*64,len(layout)*64),Image.Resampling.NEAREST).save(target/'source.png')
            preview(item,art,table['cells'],target,'before');preview(item,art,{**current['cells'],**updates},target,'after')
    adj=[]
    for n in (108,111,116):
        row=rows[n-1];places=[]
        for p in witnesses(art,row):
            grid=art.map_cells(art.maps[p['map']]);places.append({**p,'north':grid[p['y']-1][p['x']] if p['y'] else None,'south':grid[p['y']+1][p['x']]})
        adj.append({'key':row['key'],'source_pixel_sha256':hashlib.sha256(art.cell(row['key'],'BAKED').convert('RGB').tobytes()).hexdigest(),'occurrences':places,'proposal':'Review exact northern MIDDLE alias separately with its existing owner; no alias or metadata amendment included here.'})
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('base-adjacency.json',adj),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'floor_policy':'Native WALK floor and existing direct on stacking only; no terrain elevation or whole-building overlay simulated'})]:
        (OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'reviewed':len(OWNED),'changed':len(updates),'retained':len(RETAINED),'assemblies':len(entities)}))


if __name__=='__main__':main()
