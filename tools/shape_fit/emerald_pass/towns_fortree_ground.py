"""Fortree/Routes119-120 bridge floors and individually inspected cliff edges."""
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
from tools.shape_fit.emerald_pass.environment import horizontal

OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-fortree-ground'
OWNED=set(range(1,19))|{30,31,32,37,38,48,54}|set(range(66,73))
RETAINED={30,31,32,37,38}
CHANGED=OWNED-RETAINED
BRIDGES=set(range(7,19))|{48,54}|set(range(66,71))


def decisions():
    labels={1:'eastern ledge side rim',2:'ledge southwest rounded foot',3:'ledge southeast rounded foot',4:'ledge middle southern rim',5:'ledge east lower return',6:'ledge eastern concave corner',30:'northwest sloping cliff cap',31:'pointed pale cliff cap',32:'northeast sloping cliff cap',37:'eastern sloping cliff wall',38:'southwest curved cliff return',48:'two suspension planks above grass',54:'two suspension planks above forest',66:'water bridge west upper landing',67:'water bridge east upper landing',68:'water bridge west lower landing',69:'water bridge east lower landing',70:'water bridge southern deck',71:'raised soil bed southern edge',72:'soil bed projected eastern fringe'}
    for n,label in {7:'upper deck on grass',8:'upper deck over cut grass',9:'upper deck above forest',10:'upper deck at east cliff',11:'upper west river landing',12:'upper east river landing',13:'lower deck on grass',14:'lower deck over cut grass',15:'lower deck above forest',16:'lower deck at east cliff',17:'lower west river landing',18:'lower east river landing'}.items():labels[n]='timber bridge '+label
    out={}
    for n in sorted(OWNED):
        if n in RETAINED:
            reason={30:'The diagonal western cap retains its graduated solid cliff profile and native pale stone face; it joins the exact adjoining cap and southern wall in Route120(6,52).',31:'The unique center cap at Route120(7,52) has a broad solid stone face and stepped pointed crest matching the western/eastern cap pair; its existing explicit hand geometry remains coherent.',32:'The diagonal eastern cap is the mirrored end of the Route120 stone ridge, with a graduated upper ledge and intact front face. Retain this source-specific profile alongside the center and western cap.',37:'The long eastern wall uses an explicit sloping stone side and low inner edge. Its repeated sections join continuously along the Route120 cliff; replacing it with a full cube would erase that authored slope.',38:'The curved lower-left return closes the cliff beneath its northern cap; the existing stone mass, exposed side and rounded boundary are coherent in the actual Route120 corner.'}[n]
            decision='retained'
        elif n in BRIDGES:
            decision='changed'
            if n in (48,54):reason='The source shows two separate pale suspension planks, joined by narrow dark ropes across the gaps. Author that open plank structure and paint upward faces from exact native x/z pixels, excluding the grass/forest background from side faces. Preserve metadata: actual map routing already draws this WALK0 bridge as its native floor at its original elevation; adding walk decor would duplicate that floor.'
            else:reason='Keep the four-voxel standalone deck footprint that joins the adjacent bridge row, and correct its top UV to the native horizontal x/z plank art. This preserves each landing/forest/water variant without turning projected background into new supports. Actual WALK0 bridge cells use WorldPlan native bridge floors at unchanged source elevations; no walk flag or new pillar is introduced.'
        elif n==71:decision='changed';reason='All eight exact source placements are SOLID7 soil beds. Preserve the adjoining bed\'s shallow four-high occupancy and correct the horizontal soil paint; do not interpret the southern grass fringe as a vertical wall or walkable path.'
        elif n==72:decision='changed';reason='All three source placements are WALK0 beside the solid soil bed. Preserve its shallow fringe footprint and correct upward native ground paint; the actual map remains native walkable floor without a decorative walk flag.'
        else:decision='changed';reason='This source is a low projected ledge rim, adjoining shallow edge profiles. Preserve its inspected four-voxel occupancy and exact movement flags, and place the original grass/cliff drawing on the upward x/z face instead of stretching it vertically across the top.'
        out[n]=(decision,labels[n],reason)
    return out


def planks(sprite):
    m=Model();dark=texel(sprite,(66,74,107));m.paint[:]=dark
    for x in (1,9):
        m.box((x,x+6),(0,4),(1,14),dark)
        m.box((x+1,x+5),(0,4),(14,15),dark)
    for z in (4,10):m.box((0,16),(1,2),(z,z+1),dark)
    y,z,x=np.indices(m.occ.shape);above=np.concatenate((m.occ[1:],np.zeros_like(m.occ[:1])))
    top=m.occ&~above;m.paint[top]=(z*16+x).astype(np.uint8)[top]
    # Only board tops read the source texture. Rope tops stay native dark metal.
    rope=top&~(((x>=1)&(x<7))|((x>=9)&(x<15)))
    m.paint[rope]=dark
    return m


def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('fortree');out={}
    for n in sorted(CHANGED):
        key=rows[n-1]['key'];old=table['cells'][key]
        out[key]=planks(art.cell(key,'BAKED')).entry(old,'hand-emerald-fortree-planks',trim=True) if n in (48,54) else horizontal(old)
    return out


def assemblies(art):
    result=[]
    def add(name,mapid,x,y,w,d,note):
        grid=art.map_cells(art.maps[mapid]);result.append({'id':'emerald_fortree_ground_'+name,'kind':'object (model as one)','note':note,'layout':[r[x:x+w] for r in grid[y:y+d]],'occurrences':[{'map':mapid,'x':x,'y':y,'palette':'BAKED'}]})
    add('river_north','EM_ROUTE119',14,33,9,2,'Complete nine-cell-wide bridge including both grass landings and river center; map uses native bridge-floor rendering.')
    add('river_south','EM_ROUTE119',7,84,14,2,'Complete western forest bridge with all landing/forest source variants; elevations remain native.')
    add('forest_south','EM_ROUTE119',24,84,10,2,'Complete eastern forest bridge with distinct cut-grass and cliff endpoints.')
    add('lake_north','EM_ROUTE120',8,15,20,2,'Complete twenty-cell-wide lake bridge, including grass approach, mixed water landings and shared upper center.')
    add('lake_south','EM_ROUTE120',23,67,10,2,'Complete ten-cell-wide second lake bridge with the same exact water variants.')
    add('suspension_north','EM_FORTREE_CITY',13,4,2,1,'Actual two-cell forest suspension span between treehouse platforms; source-native floor in map.')
    add('suspension_south','EM_FORTREE_CITY',15,14,20,1,'Actual long suspension bridge including forest and open-grass deck variants; no invented pillars.')
    add('ledge_corner','EM_ROUTE120',20,59,4,5,'Concave ledge rim and east-side/lower corner source variants.')
    add('ridge_cap','EM_ROUTE120',6,52,3,3,'Three-piece retained pointed stone ridge and adjacent sloping side.')
    add('cliff_return','EM_ROUTE120',3,50,2,2,'Retained opposing cliff caps and southern curved return in actual source context.')
    add('soil_bed','EM_ROUTE119',7,22,4,2,'Raised soil bed southern edge with passable projected eastern fringe.')
    return result


def fitted_preview(item,art,entries,target,label):
    """Gallery composition of fitted pieces, separate from runtime floor preview."""
    from tools.shape_fit.hand_check import picture,front
    layout=item['layout'];o=np.zeros((64,len(layout)*16,len(layout[0])*16),bool);c=np.zeros((*o.shape,3),np.uint8)
    for rz,row in enumerate(layout):
        for rx,key in enumerate(row):
            rgb=np.asarray(art.cell(key,'BAKED').convert('RGB'));entry=entries.get(key)
            if entry:oo,pp=decode(entry)
            else:oo=np.zeros((16,16,16),bool);oo[0]=True;pp=np.broadcast_to(np.arange(256,dtype=np.uint8).reshape(1,16,16),oo.shape)
            h=min(64,len(oo));s=(slice(0,h),slice(rz*16,rz*16+16),slice(rx*16,rx*16+16));o[s]|=oo[:h];cc=c[s];cc[oo[:h]]=rgb[pp[:h][oo[:h]]//16,pp[:h][oo[:h]]%16]
    picture(o,c,target/f'{label}-angled.png',3);front(o,c,target/f'{label}-front.png',4)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args()
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('fortree');updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n,(decision,label,reason) in decisions().items():
        row=rows[n-1];key=row['key'];sprite=art.cell(key,'BAKED');target=OUT/key.split('/')[1];target.mkdir(exist_ok=True);sprite.save(target/'source.png')
        if args.previews and key in updates:render(table['cells'][key],sprite,target/'before');render(updates[key],sprite,target/'after')
        ledger.append({'key':key,'kind':'cell','label':label,'family':'fortree-route-bridge-terrain','decision':decision,'reason':reason,'baseline_entry_sha256':digest(table['cells'][key]),'evidence':[{'source_tilesets':row['tileset_membership'],'map_occurrences':witnesses(art,row),'contact_sheet':(INVENTORY/row['contact_page']).relative_to(ROOT).as_posix(),'contact_position':n},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'context_sheet':f'local/model-gallery/emerald-pass-study/towns/general__fortree/contexts/page-{(n-1)//16+1:03}.png'}],'visual_status':'source/context/front/east/top inspected; candidate preview/runtime pending'})
    entities=assemblies(art);live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';current=json.loads(live.read_text())
    if args.previews:
        for item in entities:
            target=OUT/'assemblies'/item['id'];target.mkdir(parents=True,exist_ok=True);layout=item['layout'];art.drawing(layout,'BAKED').resize((len(layout[0])*48,len(layout)*48),Image.Resampling.NEAREST).save(target/'source.png')
            fitted_preview(item,art,table['cells'],target,'before');fitted_preview(item,art,{**current['cells'],**updates},target,'after')
            preview(item,art,{**current['cells'],**updates},target,'runtime-floor')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'gallery_composition':'before/after show standalone fitted cells; runtime-floor uses native floor for unflagged WALK0/WATER41','bridge_policy':'WorldPlan native bridge floors remain authoritative; no walk metadata added','elevation_simulated':False})]:
        (OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'reviewed':len(OWNED),'changed':len(updates),'retained':len(RETAINED),'assemblies':len(entities)}))


if __name__=='__main__':main()
