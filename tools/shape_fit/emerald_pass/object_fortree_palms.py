"""Exact short/long Fortree palms, complete source context and native fallbacks."""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald import building_trials as p,parts_model
from tools.shape_fit.emerald_pass.buildings_west import box,material
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters
from tools.shape_fit.blue_pass.common import Model,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest,texel
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass.towns_fortree_foliage import leaf_model
from tools.shape_fit.emerald_pass import vegetation

OUT=ROOT/'local/model-gallery/emerald-pass-study/object-fortree-palms'
OWNED={39,44,45,46,49,50,51,55,60}
SOURCES={'short':('0a2ec404d6fa58184624d4ac47edecc1602c65d73b6e629376362e1a13cfd420',1,1,3),
         'long':('d7ebc81743340116db294f56534f63ed2407a784c7441e46cc5e67b75c0d7add',12,6,4)}


def layouts():
    rows=rows_for('fortree');key=lambda n:rows[n-1]['key'];top=[[key(n) for n in (44,45,46)],[key(n) for n in (49,50,51)]];middle='g3q/10875-10876-10877-10878'
    return {'short':top+[[key(41),key(55),key(41)]],
            'long':top+[[middle,key(39),middle],[middle,key(60),middle]]}


def forest_parts(x,z,leaf,upper=False):
    # Explicit neighboring forest foliage; no invented grass plate or building.
    if upper:
        return [p.round_part('dome',[x,z],[6,5.5],[0,10],leaf),p.round_part('cone',[x,z],[4.5,4],[7,16],leaf)]
    return [p.round_part('dome',[x,z],[6.5,5.7],[0,10],leaf),p.round_part('dome',[x,z],[5.7,5],[7,16],leaf)]


def generate_buildings():
    result={}
    for variant,layout in layouts().items():
        tall=variant=='long';stem_height=34 if tall else 18
        leaf=p.paint([0,10,48,27],top=[5,8,43,24],side=[0,16,16,27],back=[16,10,32,26])
        bark=p.paint([20,33,28,48] if tall else [20,32,28,33],top=[22,36,26,37] if tall else [22,32,26,33],side=[20,33,27,46] if tall else [20,32,28,33])
        forest=p.paint([0,0,16,8],top=[0,0,16,8],side=[0,0,16,8])
        parts=[]
        # Background conifers visibly present in the source's northern strip.
        for x in (8,24,40):parts+=forest_parts(x,8,forest)
        # The source stem projects south over WALK terrain in the long variant.
        # Its physical root stays on the solid rear palm footprint, with every
        # claimed foreground conifer separately present in the whole model.
        for z in ([40,56] if tall else [40]):
            for x in (8,40):parts+=forest_parts(x,z,forest)
        parts+=forest_parts(24,56 if tall else 40,forest,upper=True)
        parts += [p.round_part('cylinder',[24,22],4.3,[0,stem_height+4],bark),
                  p.round_part('cylinder',[24,22],5,[0,3],bark),
                  p.round_part('dome',[24,16],[23,15.5],[stem_height,stem_height+18],leaf)]
        # Individual drooping frond tips connect to the broad fan crown.
        for x in (6,15,24,33,42):
            parts.append(p.round_part('dome',[x,25],[5.7,6.5],[stem_height-4,stem_height+6],p.paint([max(0,x-5),12,min(48,x+5),25],top=[max(0,x-5),12,min(48,x+5),24],side=[0,16,16,27])))
        for x in (8,24,40):parts.append(p.round_part('dome',[x,7],[7,6],[stem_height-2,stem_height+8],leaf))
        ident='emerald_object_fortree_palm_'+variant
        result[ident]={'id':ident,'atlas':'g3q','tiles':quarters(layout),'height':stem_height+20,'terrain':[[1,2]] if tall else [],'parts':parts}
    return result


def canopy():
    m=Model(height=24,width=48,depth=32);m.ellipsoid((24,2,16),(23,18,15.5))
    for x in (6,15,24,33,42):m.ellipsoid((x,1,25),(5.7,7,6.5))
    return m.occ


def generate_cells(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('fortree');result={};crown=canopy()
    coordinates={44:(0,0),45:(1,0),46:(2,0),49:(0,1),50:(1,1),51:(2,1)}
    for n in sorted(OWNED):
        key=rows[n-1]['key'];sprite=art.cell(key,'BAKED');old=table['cells'][key]
        if n in coordinates:
            x,z=coordinates[n];m=Model(height=32);piece=crown[:,z*16:(z+1)*16,x*16:(x+1)*16];m.occ[:24]=piece
            yy,zz,xx=np.indices(m.occ.shape);noise=(((xx+x*16)//2*73856093)^((zz+z*16)//2*83492791)^(yy//2*19349663))%100
            palette=np.asarray([vegetation.color_uv(sprite,c) for c in ((58,82,0),(66,156,49),(74,214,74))],np.uint8)
            m.paint[:]=palette[np.where(noise<20,0,np.where(noise<65,1,2))]
        elif n==39:
            m=Model();m.cylinder((8,8),4,(0,16),texel(sprite,(156,165,99)));m.cylinder((8,8),4.6,(0,2),texel(sprite,(66,74,107)))
            yy,zz,xx=np.indices(m.occ.shape);palette=np.asarray([texel(sprite,c) for c in ((99,107,66),(156,165,99),(206,214,165))],np.uint8)
            m.paint[m.occ]=palette[np.clip((2-(xx+zz)/10).astype(int),0,2)[m.occ]]
        else:m=leaf_model(sprite,'crown')
        result[key]=m.entry(old,'hand-emerald-fortree-palm-fallback',trim=True)
    return result


def source_evidence(art,entry,layout):
    places=building_catalog({'game':'emerald','cells':{},'buildings':[entry]},art)[0]['occurrences'];result=[]
    for loc in places:
        source=art.maps[loc['map']];raw=base64.b64decode(source['cells']);cells=[]
        for dy,row in enumerate(layout):
            for dx,key in enumerate(row):
                x,y=loc['x']+dx,loc['y']+dy;i=(y*source['w']+x)*4
                cells.append({'x':x,'y':y,'local':[dx,dy],'key':key,'metatile':int.from_bytes(raw[i:i+2],'little'),'collision':raw[i+2],'elevation':raw[i+3],'terrain_excluded':[dx,dy] in entry['terrain']})
        result.append({**loc,'width':3,'depth':len(layout),'cells':cells,'warps':[v for v in source['warps'] if loc['x']<=v['x']<loc['x']+3 and loc['y']<=v['y']<loc['y']+len(layout)]})
    return result


def decisions():
    out={}
    for n in OWNED:
        if n==39:label='large palm exposed stem projection';reason='Replace the pinched extrusion with a narrow cylindrical native olive stem fallback. Both exact WALK0 source placements are included in the complete long palm template as terrain exclusions; ordinary map planning preserves passable floor rather than adding a second trunk on that projection.'
        elif n in (55,60):label='large palm and foreground conifer overlap';reason='The source displays a small conifer crown in front of the large palm stem. Author that native conifer upper foliage fallback without smearing the palm stem across its side. The complete short/long palm template supplies the continuous large trunk and separately claimed foreground forest.'
        else:label='large palm fan crown section '+str(n);reason='Slice a single broad rounded48x32 fan crown with connected drooping frond tips into the exact six source cells. Preserve native local green pixels and original metadata; remove the striped pedestal profiles. The complete palm template supplies continuous support and source-matched neighboring forest.'
        out[n]=(label,reason)
    return out


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args()
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);buildings=generate_buildings();cells=generate_cells(table,art);rows=rows_for('fortree');OUT.mkdir(parents=True,exist_ok=True);ledger=[];added=[]
    for n,(label,reason) in sorted(decisions().items()):
        row=rows[n-1];key=row['key'];sprite=art.cell(key,'BAKED');target=OUT/key.split('/')[1];target.mkdir(exist_ok=True);sprite.save(target/'source.png')
        if args.previews:render(table['cells'][key],sprite,target/'before');render(cells[key],sprite,target/'after')
        ledger.append({'key':key,'kind':'cell','label':label,'family':'fortree-large-palm','decision':'changed','reason':reason,'baseline_entry_sha256':digest(table['cells'][key]),'evidence':[{'source_tilesets':row['tileset_membership'],'map_occurrences':witnesses(art,row),'contact_sheet':(INVENTORY/row['contact_page']).relative_to(ROOT).as_posix(),'contact_position':n},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'}]})
    from tools.shape_fit.emerald_pass.building_fortree_treehouse import evidence as house_evidence,generate as house_generate
    house_places=house_evidence(art,next(iter(house_generate().values())))
    for variant,layout in layouts().items():
        ident='emerald_object_fortree_palm_'+variant;entry=buildings[ident];target=OUT/variant;target.mkdir(exist_ok=True);source=p.drawing(entry,BUNDLE);source_hash=hashlib.sha256(source.tobytes()).hexdigest();assert source_hash==SOURCES[variant][0]
        source.save(target/'source.png');source.resize((384,len(layout)*128),Image.Resampling.NEAREST).save(target/'source-enlarged.png');places=source_evidence(art,entry,layout)
        assert len(places)==(3 if variant=='short' else 2)
        assert all(not q['warps'] for q in places)
        overlap=[]
        for q in places:
            for h in house_places:
                if q['map']==h['map'] and q['x']<h['x']+3 and h['x']<q['x']+3 and q['y']<h['y']+3 and h['y']<q['y']+len(layout):overlap.append({'palm':q,'house':h})
        assert not overlap
        for i,loc in enumerate(places):
            x,y=loc['x']*16,loc['y']*16;crop=art.map_image(loc['map']).crop((x-16,y-16,x+64,y+len(layout)*16+16));ImageDraw.Draw(crop).rectangle((16,16,63,len(layout)*16+15),outline='red');crop.resize((320,(len(layout)+2)*64),Image.Resampling.NEAREST).save(target/f'context-{i:02}.png')
        if args.previews:
            o,c=parts_model.build(entry,np.asarray(source));parts_model.picture(o,c,target/'view.png',6);parts_model.front_view(o,c,target/'front.png',7);parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(c,(0,2,1,3))[:,:,::-1],target/'side.png',7)
        relative=target.relative_to(ROOT).as_posix();row={'key':ident,'kind':'building','label':'Fortree '+variant+' large palm and surrounding forest','family':'fortree-large-palm','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(entry),'source_pixel_sha256':source_hash,'source_pixel_mode':'RGB','occurrences':places,'reason':'Exact complete source palm pattern absent from original112 building templates. Author a broad native fan canopy with drooping fronds, one continuous olive trunk rooted within the solid rear footprint, and individual conifer masses for every source-visible forest section claimed by the template. The long stem projection is WALK0 and remains terrain-excluded; no giant grass plate or new collision is introduced. The three short and two long occurrences do not overlap any b124 treehouse match.','terrain_exclusions':entry['terrain'],'b124_matching_overlaps':overlap,'claimed_forest_geometry':{'north_strip':[[0,0],[1,0],[2,0]],'south_outer':[[x,z] for z in ([2,3] if variant=='long' else [2]) for x in (0,2)],'foreground_conifer':[1,len(layout)-1]},'evidence':[{'source':relative+'/source.png','contexts':[relative+f'/context-{i:02}.png' for i in range(len(places))]},{'python_front':relative+'/front.png','python_side':relative+'/side.png','python_angled':relative+'/view.png'}],'visual_status':'source inspected; Python/runtime QA pending','user_approved':False}
        added.append(row);(target/'spec.json').write_text(json.dumps(entry,indent=2)+'\n')
    for name,value in [('updates.json',{'cells':cells,'buildings':buildings}),('ledger.json',ledger),('added-ledger.json',added)]:
        (OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'cells':len(cells),'buildings':len(buildings),'source_occurrences':sum(len(a['occurrences']) for a in added)}))


if __name__=='__main__':main()
