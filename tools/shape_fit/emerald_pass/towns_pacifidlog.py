"""Pacifidlog platforms, Sky Pillar stone and Seafloor cave source decisions."""
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
from tools.shape_fit.emerald_pass.towns_fortree_ground import fitted_preview
from tools.shape_fit.emerald_pass.environment import horizontal,material_paint

OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-pacifidlog'
GROUP='pacifidlog'
SURFACES={2,3,5,6,8,9,11,12,13,14,15,16,17,25,26,27,28,40,41}
ROCKS={1,4,7,10,33,34,48,54}
COLUMNS={29,30,31,32}
CHANGED=SURFACES|ROCKS|COLUMNS


def decisions():
    labels={1:'blue boulder with small forefoot',2:'platform northwest water projection',3:'platform northeast water projection',4:'blue boulder with split toe',5:'gold platform northwest deck',6:'gold platform northeast deck',7:'four-piece blue stone pile over darker floor',8:'gold platform west edge',9:'gold platform east edge',10:'four-piece blue stone pile',11:'gold platform southwest deck',12:'gold platform middle deck',13:'gold platform southeast deck',14:'platform southwest water projection',15:'platform southern water projection',16:'platform gangway step',17:'platform southeast water projection',18:'portal upper west wall',19:'portal arch crown',20:'portal upper east wall',21:'portal lower west jamb',22:'ascending portal treads',23:'portal lower east jamb',24:'descending portal threshold',25:'floating bridge horizontal west half',26:'floating bridge horizontal east half',27:'floating bridge vertical upper half',28:'floating bridge vertical corner',29:'upper flight west lower pillar',30:'upper flight east lower pillar',31:'upper flight west pillar cap',32:'upper flight east pillar cap',33:'Seafloor four-piece brown stone pile',34:'Seafloor brown boulder with toe',35:'Seafloor upper curved cliff return',36:'Seafloor lower curved waterline cliff',37:'Seafloor cave upper arch',38:'Seafloor cave open warp threshold',39:'Seafloor cave right stone wall',40:'upper flight west tread strip',41:'upper flight east tread strip',42:'tower northwest rib',43:'tower recessed slot window',44:'tower middle masonry',45:'tower northeast rib',46:'tower lower western rib',47:'tower lower eastern rib',48:'Sky Pillar summit blue boulder',49:'tower southwest plinth',50:'tower west sanded lower wall',51:'tower east sanded lower wall',52:'tower southeast plinth',53:'Sky Pillar diagonal cliff rim',54:'Sky Pillar exterior round tan boulder'}
    out={}
    for n,label in labels.items():
        decision='changed' if n in CHANGED else 'retained'
        if n in ROCKS:
            reason='Author the source-specific '+label+' as grounded rounded stone masses with broad stable bases, excluding floor pixels and replacing the pinched side extrusion. Its individual rock count, large body/toe arrangement and native blue or brown material follow the inspected source and actual map neighborhood.'
        elif n in COLUMNS:
            reason='Replace the tapered sprite slab with a straight decorated masonry side pillar and pale upper cap. Preserve the exact existing upper-to-lower on relation; source-native gold panel and brown masonry remain on the front. The actual four-by-two upper stair flight is separately modeled as one exact template to connect both pillars and uninterrupted WALK stair treads.'
        elif n in (40,41):
            reason='Correct upward x/z stair drawing on the existing shallow standalone fallback, preserving its WALK0/elevation3 source behavior and all metadata. A separate exact4x2 whole flight supplies eight continuous treads rather than repeating the stair rise independently in both source rows; its four central cells draw on non-colliding decor.'
        elif n in SURFACES:
            reason='Preserve the inspected shallow occupancy and paint its upward face from the native horizontal x/z deck or water-edge drawing. This keeps the exact gold platform corner, gangway or timber bridge pattern connected across neighboring cells. Actual WALK0/elevation3 deck and bridge cells use native floor routing; WATER41/elevation1 projections remain water. No extra walk flag or support is added.'
        elif n in (18,19,20,21,23):
            reason='Retain the explicit native wall/arch/jamb fallback, inspected in the complete three-by-two portal. Reviewed b111 covers the descending six occurrences; the separate exact ascending portal template covers the other six with its distinct source central treads. Whole geometry keeps the doorway open and preserves warp metadata.'
        elif n==22:
            reason='The existing hand model already has clear ascending gray treads, a grounded stair profile and open space above. Retain this fallback and its source metadata; the exact ascending portal whole template places the same stair meaning inside the full gold masonry arch at all six source warps.'
        elif n==24:
            reason='The descending warp uses an intentionally thin open threshold and lintel, not a raised flight. Retain the existing explicit passable frame and native dark interior; the reviewed b111 whole portal matches all six exact source occurrences.'
        elif n in (35,36):
            reason='The source is a curved cave-bank return beside the Seafloor water and entrance, not a loose rock. Whole Room6(10,6) cliff context confirms the existing14..16-high occupied columns join the neighboring solid rock wall and preserve the irregular curved water boundary. Retain this source-specific cliff mass; actual terrain placement remains a runtime interface QA case.'
        elif n==37:
            reason='Retain the existing full-depth native brown rock arch, inspected with its side wall and lower warp at all three cave entrances. Its carved shallow opening belongs to the surrounding cliff mass and should not be replaced by a freestanding boulder.'
        elif n==38:
            reason='Retain the thin open native cave threshold and existing walk:true metadata. All three source warp cells remain passable; the shallow frame joins the upper rock arch without filling the path.'
        elif n==39:
            reason='Retain the explicit full-depth cave side wall: its native irregular rock face and upper lip continue the adjoining Seafloor entrance and cliff, with no isolated pedestal or broken side interface.'
        elif n==53:
            reason='This small diagonal tan/gray shape is the exposed cliff rim beside the Sky Pillar approach, not a boulder. Preserve its source-specific low stepped footprint and native sloping boundary, inspected against the neighboring cliff and sand.'
        else:
            reason='Retain the native explicit '+label+' fallback. The reviewed tower geometry is reused by the exact exported5x6 tower pattern, whose two doorway cells differ from unmatched original b110. Whole masonry, windows and plinth remain continuous; preserving this fallback also covers any ordinary wall occurrences outside the complete tower.'
        out[n]=(decision,label,reason)
    assert set(out)==set(range(1,55))
    return out


def rock(sprite,n):
    m=Model()
    if n in (7,10,33):
        # Explicit source piles: a taller northeast stone and smaller west/front pieces.
        m.ellipsoid((10.5,7,5.5),(4.8,7.5,4.8));m.ellipsoid((4.5,5,7),(4.4,5.5,4.2))
        m.ellipsoid((10.5,3.5,12),(4.2,4,3.8))
        m.ellipsoid((3.7,2.5,12.3),(3.4,3,3.2))
    elif n==54:
        m.ellipsoid((8,6.7,8),(7.5,7.2,7.5));m.ellipsoid((6,4.5,10),(5.5,5,5))
    else:
        m.ellipsoid((8.5,7,6.5),(6.6,7.6,6.2));m.ellipsoid((4.5,3,11),(4.1,3.6,4))
        m.ellipsoid((12,2.5,11),(3,3,3.5))
    # Source palettes are explicit: the purple room floor and yellow sand are
    # deliberately excluded from the stone, even when they dominate the sprite.
    colors=((66,74,107),(99,99,123),(132,132,140),(156,181,222),(189,206,230))
    if n in (33,34):colors=((66,41,66),(90,74,82),(132,107,107),(206,173,123))
    elif n==54:colors=((99,66,82),(132,90,90),(156,115,115),(189,148,140),(222,181,165),(239,214,206))
    rgb=np.asarray(sprite.convert('RGB'));palette=[]
    for color in colors:
        spots=np.argwhere((rgb==color).all(axis=2));assert len(spots),color
        palette.append(int(spots[0] @ np.array([16,1])))
    y,z,x=np.indices(m.occ.shape);q=np.clip(.70+.028*y-.016*x-.010*z+((x//4+z//4)%3-1)*.065,0,.999)
    m.paint[:]=np.asarray(palette,np.uint8)[(q*len(palette)).astype(int)]
    return m


def pillar(sprite,n):
    upper=n in (31,32);m=Model();brown=texel(sprite,(156,115,107));m.box((0,16),(0,16 if not upper else 14),(3,13),brown)
    if upper:m.box((0,16),(14,16),(2,14),texel(sprite,(197,173,156)))
    y,z,x=np.indices(m.occ.shape);front=m.occ&(z==12);m.paint[front]=((15-y)*16+x).astype(np.uint8)[front]
    return m


def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for(GROUP);out={}
    for n in sorted(CHANGED):
        key=rows[n-1]['key'];old=table['cells'][key]
        if n in SURFACES:out[key]=horizontal(old)
        else:
            sprite=art.cell(key,'BAKED');m=rock(sprite,n) if n in ROCKS else pillar(sprite,n)
            out[key]=m.entry(old,'hand-emerald-pacifidlog-review',trim=True)
    return out


def assemblies(art):
    result=[]
    def add(name,mapid,x,y,w,d,note):
        grid=art.map_cells(art.maps[mapid]);result.append({'id':'emerald_pacifidlog_'+name,'kind':'object (model as one)','note':note,'layout':[r[x:x+w] for r in grid[y:y+d]],'occurrences':[{'map':mapid,'x':x,'y':y,'palette':'BAKED'}]})
    add('house_platform','EM_PACIFIDLOG_TOWN',0,10,5,6,'Whole gold platform with all corners and native water projections; whole b006 house overlay is separately reviewed.')
    add('floating_bridge','EM_PACIFIDLOG_TOWN',3,15,4,3,'Whole vertical-to-horizontal bridge from west platform gangway to east platform; native WALK floor routing.')
    add('blue_rock_group','EM_SKY_PILLAR_1F',2,2,7,3,'Source blue boulders and multi-stone piles arranged on actual interior floor.')
    add('brown_rock_pair','EM_SEAFLOOR_CAVERN_ROOM7',6,23,3,1,'Actual brown four-stone pile and larger single boulder, with native floor between.')
    add('upper_stairs','EM_SKY_PILLAR_TOP',10,13,4,2,'Complete two-pillar stair flight: fallback columns stack; exact added whole template supplies continuous central flight.')
    add('ascending_portal','EM_SKY_PILLAR_1F',9,0,3,2,'Retained per-cell portal fallbacks; exact added whole portal uses native ascending central source variant.')
    add('descending_portal','EM_SKY_PILLAR_2F',9,0,3,2,'Retained descending portal fallbacks; reviewed b111 matches this exact pattern.')
    add('cliff_return','EM_SEAFLOOR_CAVERN_ROOM6',10,6,6,4,'Retained irregular cliff return at actual cave mouth and waterline; terrain renderer provides unmodeled neighbor columns.')
    add('cave_portal','EM_SEAFLOOR_CAVERN_ROOM6',3,0,3,2,'Complete retained cave arch, open warp threshold and both adjacent stone walls.')
    add('sand_cliff_rocks','EM_SKY_PILLAR_OUTSIDE',18,7,4,4,'Diagonal cliff rim and round tan boulders on actual sandy approach.')
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args()
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for(GROUP);updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n,(decision,label,reason) in decisions().items():
        row=rows[n-1];key=row['key'];sprite=art.cell(key,'BAKED');target=OUT/key.split('/')[1];target.mkdir(exist_ok=True);sprite.save(target/'source.png')
        if args.previews and key in updates:render(table['cells'][key],sprite,target/'before');render(updates[key],sprite,target/'after')
        ledger.append({'key':key,'kind':'cell','label':label,'family':'pacifidlog-sky-pillar-seafloor','decision':decision,'reason':reason,'baseline_entry_sha256':digest(table['cells'][key]),'evidence':[{'source_tilesets':row['tileset_membership'],'map_occurrences':witnesses(art,row),'contact_sheet':(INVENTORY/row['contact_page']).relative_to(ROOT).as_posix(),'contact_position':n},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'context_sheet':f'local/model-gallery/emerald-pass-study/towns/general__pacifidlog/contexts/page-{(n-1)//16+1:03}.png'}],'visual_status':'source/context/front/east/top inspected; candidate preview/runtime pending'})
    entities=assemblies(art);live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';current=json.loads(live.read_text())
    if args.previews:
        for item in entities:
            target=OUT/'assemblies'/item['id'];target.mkdir(parents=True,exist_ok=True);layout=item['layout'];art.drawing(layout,'BAKED').resize((len(layout[0])*48,len(layout)*48),Image.Resampling.NEAREST).save(target/'source.png')
            fitted_preview(item,art,table['cells'],target,'before');fitted_preview(item,art,{**current['cells'],**updates},target,'after');preview(item,art,{**current['cells'],**updates},target,'runtime-floor')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'gallery_composition':'before/after standalone fitted cells; runtime-floor uses native floor for unflagged WALK0/WATER41 and direct on links','whole_buildings_simulated':False,'elevation_simulated':False,'whole_templates':'object-sky-pillar-patterns supplies actual tower, ascending portal and complete upper flight'})]:
        (OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'reviewed':54,'changed':len(updates),'retained':54-len(updates),'assemblies':len(entities)}))


if __name__=='__main__':main()
