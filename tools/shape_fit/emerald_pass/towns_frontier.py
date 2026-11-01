"""Individually source-reviewed Battle Frontier fragments and complete flags.

All 357 cards and both complete maps were inspected. Six rail material repairs
now belong to frontier_rail_material; this owner retains 351 decisions. Most fragments belong to
native complete-building models; the declared retention set has exact occurrence
proof. Narrow source-proven stack metadata corrections are emitted separately.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw
from tools.shape_fit.emerald_pass.towns import ROOT, BASELINE, BUNDLE, INVENTORY, rows_for, texel, digest
from tools.shape_fit.blue_pass.common import Model, decode, render
from tools.model_gallery import EmeraldArt
from tools.shape_fit.emerald_pass import scenery,vegetation,environment

OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-frontier'
CANONICAL_OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-frontier-current'
TRANSFERRED_WEST=(28,37,58,62,65,72)
FROZEN_LEDGER_SHA256='bd331ba0f6a31ea2d8b25cb9d6160ca0de416200974181e1182f86bd632b9d64'
FROZEN_UPDATES_SHA256='3d8f8f846cbb2ca50bece04cf652e9ae01751ada33e4a6893d39944abd272716'
EAST='battle_frontier_outside_east';WEST='battle_frontier_outside_west'
# Manually accepted after all twelve source/front/east/top contact sheets and
# both complete map drawings were inspected. Coverage is checked, not inferred.
COVERED={
 EAST:(2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,39,42,43,44,45,46,47,48,49,50,51,52,53,54,56,58,59,60,61,62,63,64,65,67,68,69,70,71,72,73,74,75,77,78,79,80,81,82,83,84,85,86,87,88,89,90,91,92,94,95,96,97,98,100,101,102,103,104,105,106,107,108,109,110,111,112,115,116,117,122,123,124,125,126,127,128,129,130,132,137,138,140,141,142,143,144,145,146,147,148,149,150,151,152,153,154,155,156,157,158,159,160,161,162,163,164,165,166,167,168,169,170,171,172,173,174,175,176,177,183,184,185,186),
 WEST:(1,2,3,4,6,7,8,9,10,11,12,13,14,15,18,21,22,23,25,26,27,29,30,31,32,34,35,36,38,39,40,42,43,44,45,46,47,48,49,50,51,52,53,54,56,59,61,63,64,66,67,69,70,71,74,76,77,78,80,81,82,83,84,85,86,87,88,89,90,91,92,93,94,95,96,97,98,99,100,101,102,103,104,105,106,107,113,114,115,118,119,120,121,122,126,127,128,129,130,133,134,135,136,137,138,139,140,141,142,143,144,145,146,147,148,149,150,151),
}
CURBS={187:'N',188:'S',189:'N',190:'N',191:'NW',192:'N',193:'NE',194:'W',195:'S',196:'E',197:'SW',198:'S',199:'SE',206:'N'}
RAILS_WEST={24:'east straight upper',28:'east post',33:'east straight middle',37:'east lower post',41:'east terminal',58:'west post',60:'west straight middle',62:'west upper corner',65:'west lower post',68:'west upper straight',72:'east upper corner',73:'west terminal',75:'west straight upper',79:'east upper straight'}
TOWER_FOOT=(99,113,114,118,119,120,121,131,133,134,135)
WEST_BASES=('g3q/12737-10671-13184-10672','g3q/12737-10671-10672-13183','g3q/12737-10671-10669-10672')
WEST_RIGHT=('g3q/10670-10671-10672-13183','g3q/10670-10671-13184-10672','g3q/10670-12459-10672-10668','g3q/10670-10671-10669-10672')


def key(group,i):return rows_for(group)[i-1]['key']


def decisions():
    proof=json.loads((OUT/'coverage-proof.json').read_text());out={EAST:{},WEST:{}}
    for group,positions in COVERED.items():
        for i in positions:
            p=proof[key(group,i)];assert p['fully_covered']
            ids=sorted({b['id'] for o in p['occurrences'] for b in o['buildings']})
            coords=sorted({tuple(b['local_cell']) for o in p['occurrences'] for b in o['buildings']})
            out[group][i]=('retained','complete-building source fragment '+str(i),
                'Every exported occurrence is supplied by the complete native building model '+', '.join(ids)+
                '; source-local cells '+str(coords)+'. Source art and fallback geometry were inspected; keep this matching fragment while the complete building supplies the live architecture.')
    for i,role in [(1,'Palace rear roof ridge'),(66,'Arena blue roof center'),(76,'Arena blue roof left edge'),(93,'Arena blue roof right edge')]:
        out[EAST][i]=('retained',role,'The same roof artwork appears in SOLID template cells and WALK projected roof cells. Retain the source-local fallback; complete building and explicitly audited overhead footprint handling supply the physical roof. No broad walk flag is introduced.')
    for i,role in [(38,'upper-right stepped Pyramid roof'),(40,'Pyramid right roof band'),(41,'Pyramid left roof band'),(55,'Pyramid top center step'),(57,'upper-left stepped Pyramid roof')]:
        out[EAST][i]=('retained',role,'Hand-authored stepped roof fragment belongs to the complete Pyramid. Source SOLID7 boundary placements were escalated to the building footprint audit; retaining this fallback avoids a conflicting second roof authoring pass.')
    for i in TOWER_FOOT:
        out[EAST][i]=('retained','Battle Tower lower facade segment '+str(i),'The source is the lower Tower facade, window return, red rib or entry column. The building audit owns the source-proven SOLID lower-row claim correction; preserve this matching fallback instead of adding a second independent facade strip.')
    for i,label in [(136,'Pyramid northeast rail terminal'),(139,'Pyramid northwest rail terminal'),(200,'Pyramid southwest rail terminal'),(201,'Pyramid southeast rail terminal')]:
        out[EAST][i]=('retained',label,'Existing hand-authored narrow posts and short return rail correctly close the Pyramid perimeter fence; sandy/grass source ground remains outside the rail geometry.')
    for i in (178,179):out[EAST][i]=('changed','Arena-gate conifer base '+('left' if i==178 else 'right'),'Use the same connected rooted trunk and foliage as the reviewed shared conifer, with only this source variant\'s native colors. Exact shared-crown alternate-base links are separately documented, not generalized to unrelated forest rows.')
    for i,side in [(180,'left'),(181,'middle'),(182,'right')]:out[EAST][i]=('retained',f'cliff/canopy meeting {side}','This hand-authored brown cliff face and orange canopy overlap occurs above both the shop and Center in the source. Keep its existing stepped cliff shell and color boundary; the matching complete building supplies the canopy beneath it.')
    for i,edges in CURBS.items():out[EAST][i]=('changed','Frontier pale stone border '+edges+(' with east flag foot' if i==206 else ''),'Replace the generic pinched extrusion with the same low three-voxel stone border used by the reviewed shared Frontier curbs. Native source markings face upward and the surrounding ground stays one voxel high; no tall pavement card remains.')
    for i,label in [(202,'east flag upper pole and hoist'),(203,'east flag upper cloth'),(204,'east flag lower pole'),(205,'east flag lower cloth')]:out[EAST][i]=('changed',label,'One complete pole and thin folded yellow/white cloth is authored across the exact two-by-three source assembly. A documented on link moves this WALK source projection onto its SOLID curb base; all nine real occurrences are verified.')
    out[WEST][5]=('changed','Dome approach double fence terminal','Two rooted square posts with pale caps and a short connecting rail replace the hourglass extrusion; preserve the narrow actual fence endpoint on the cliff path.')
    for i in (16,17,19,57):out[WEST][i]=('changed','Factory east pale curb overlap '+str(i),'Author only the source right-hand stone curb as a continuous vertical border. The neighboring white/yellow facade is supplied by the complete building, so its projected art is not repeated on a separate curb face.')
    out[WEST][20]=('retained','Dome central blue roof rib','This continuous rib spans SOLID roof and WALK roof projection occurrences. Keep the matching fragment; the complete Dome model supplies the physical curved roof and center rib.')
    out[WEST][55]=('retained','Factory curved northeast water-edge facade','Existing hand-authored quarter-curve follows the white wall edge against blue water at the Factory corner; it avoids filling the outer water half with a new rectangular building wall.')
    for i,label in RAILS_WEST.items():out[WEST][i]=('retained','Dome perimeter rail '+label,'The hand-authored thin rail/post volume follows the exact '+label+' fence position alongside the Dome. Its small occupied section excludes the projected roof, paving and grass background and joins the adjacent fence run.')
    for i,label in [(108,'lower west tail curve'),(109,'tail-to-body joint'),(110,'outer west tail edge'),(111,'upper east tail curve'),(112,'lower east tail curve'),(116,'upper tail tip left'),(117,'upper tail tip right'),(123,'far tail-tip ground fragment')]:
        out[WEST][i]=('retained','Pike projected '+label,'All occurrences are WALK0 source projection around the complete Pike. The existing very shallow hand-authored fragment stays out of the walkable floor volume; the native whole-Pike model supplies the raised serpent silhouette.')
    for i,label in [(124,'west flag upper pole and cloth left'),(125,'west flag upper cloth right'),(131,'west flag lower pole and cloth left'),(132,'west flag lower cloth right')]:out[WEST][i]=('changed',label,'Author the complete west-facing two-column flag above its actual pale curb. Explicit source-proven on/alsoOn links cover all fifteen occurrences and the three narrowly added native curb-base variants; no generic attachment inference is used.')
    assert set(out[EAST])==set(range(1,207));assert set(out[WEST])==set(range(1,152))
    for i in TRANSFERRED_WEST:del out[WEST][i]
    return out


def curb(sprite,edges,pole=None):
    # Same explicit curb cross-section as the adjacent environment-owned pieces.
    m=environment.curb(sprite,{'N':41,'S':51,'W':48,'E':49,'NW':40,'NE':42,'SW':50,'SE':52}[edges])
    gray=texel(sprite,(140,140,148));dark=texel(sprite,(99,99,123))
    if pole is not None:
        m.box((pole,pole+2),(0,16),(3,5),dark);m.box((pole-1,pole+3),(0,2),(2,6),gray)
    return m


def fence_terminal(sprite):
    m=Model();gray=texel(sprite,(99,99,123));white=texel(sprite,(239,247,247))
    for x in (2,10):m.box((x,x+4),(0,11),(6,10),gray);m.box((x,x+4),(11,13),(6,10),white)
    m.box((6,10),(6,8),(7,9),gray);return m


def conifer_base(sprite):
    whole,foliage=scenery.tree();m=Model();m.occ=whole[:16].copy();leaf=foliage[:16]
    m.paint=vegetation.leaf_paint(m.occ.shape,sprite,bright=False);m.paint[m.occ & ~leaf]=vegetation.color_uv(sprite,vegetation.BARK[1]);return m


def flag_shape(west=False):
    """A deliberately drawn thin cloth polygon and cylindrical metal pole.

    Material addresses stay in each source cell after vertical/horizontal slicing.
    The folded cloth projects its native white emblem without water pixels on it.
    """
    m=Model(height=48,width=32);pole=5 if west else 11
    m.box((pole,pole+2),(12,40),(3,5),1)
    # Source drawing polygons, hand-marked after inspecting the full 2x3 art.
    points=([(7,9),(12,10),(16,12),(21,10),(22,11),(21,15),(23,19),(23,21),(19,22),(16,23),(12,22),(7,21)] if west else
            [(13,9),(18,10),(22,12),(28,10),(29,11),(28,15),(30,19),(30,22),(24,22),(22,23),(18,22),(13,21)])
    shape=Image.new('1',(32,32));ImageDraw.Draw(shape).polygon(points,fill=1);mask=np.asarray(shape)
    for sy,sx in np.argwhere(mask):
        y=47-int(sy);x=int(sx);z=4+(1 if (x-pole)//5%2 else 0)
        m.box((x,x+1),(y,y+1),(z,z+2),2)
    m.box((pole,pole+2),(39,41),(3,5),3)
    return m


def flag_piece(sprite,west,column,upper):
    whole=flag_shape(west);start=32 if upper else 16;m=Model();m.occ=whole.occ[start:start+16,:,column*16:column*16+16].copy();codes=whole.paint[start:start+16,:,column*16:column*16+16]
    pole=texel(sprite,(99,99,123));gold=texel(sprite,(239,214,57));m.paint[m.occ]=pole
    yy,zz,xx=np.indices(m.occ.shape);cloth=m.occ & (codes==2);m.paint[cloth]=((15-yy)*16+xx)[cloth];m.paint[m.occ & (codes==3)]=gold
    pixels=np.asarray(sprite.convert('RGB'));painted=pixels[m.paint//16,m.paint%16]
    # These two explicitly identified source materials are background water,
    # never cloth. The hand-drawn fold boundary uses the local dark-gold hem.
    water=np.all(painted==(82,107,214),axis=3)|np.all(painted==(107,132,214),axis=3)
    m.paint[cloth & water]=texel(sprite,(156,140,49))
    # Back/edge still use the same local source pixel as the native front.
    return m


def metadata_corrections(table,art):
    correction={}
    for upper,lower in [(202,204),(203,205),(204,206),(205,192)]:correction[key(EAST,upper)]={'on':key(EAST,lower)}
    correction[key(WEST,124)]={'on':key(WEST,131)};correction[key(WEST,125)]={'on':key(WEST,132)}
    correction[key(WEST,131)]={'on':WEST_BASES[0],'alsoOn':list(WEST_BASES[1:])}
    correction[key(WEST,132)]={'on':WEST_RIGHT[0],'alsoOn':list(WEST_RIGHT[1:])}
    return correction


def generate(table,art):
    updates={};meta=metadata_corrections(table,art)
    for group,items in decisions().items():
        for i,(status,label,reason) in items.items():
            if status!='changed':continue
            k=key(group,i);sprite=art.cell(k,'BAKED')
            if group==EAST:
                if i in CURBS:m=curb(sprite,CURBS[i],11 if i==206 else None)
                elif i in (178,179):m=conifer_base(sprite)
                else:m=flag_piece(sprite,False,0 if i in (202,204) else 1,i in (202,203))
            else:
                if i==5:m=fence_terminal(sprite)
                elif i in (16,17,19,57):m=curb(sprite,'E')
                else:m=flag_piece(sprite,True,0 if i in (124,131) else 1,i in (124,125))
            updates[k]={**m.entry(table['cells'][k],'hand-emerald-frontier-review',trim=True),**meta.get(k,{})}
    additions={}
    for k in WEST_BASES:
        assert k not in table['cells'];sprite=art.cell(k,'BAKED');m=curb(sprite,'N',5)
        additions[k]=m.entry({'class':'voxels'},'hand-emerald-frontier-flag-base')
    return updates,additions


def assemblies():
    e=lambda i:key(EAST,i);w=lambda i:key(WEST,i)
    data=[{'id':'emerald_frontier_east_flag','kind':'stack (model as one)','note':'Complete east yellow/white flag, exact nine placements','layout':[[e(202),e(203)],[e(204),e(205)],[e(206),e(192)]]},
          {'id':'emerald_frontier_north_border','kind':'object (model as one)','note':'North stone boundary beside the east flag','layout':[[e(206),e(192),e(192)]]},
          {'id':'emerald_frontier_west_border','kind':'object (model as one)','note':'West stone boundary ending in a southwest return','layout':[[e(194)],[e(194)],[e(197)]]}]
    for n,(left,right) in enumerate([(WEST_BASES[0],WEST_RIGHT[0]),(WEST_BASES[1],WEST_RIGHT[1]),(WEST_BASES[0],WEST_RIGHT[2]),(WEST_BASES[2],WEST_RIGHT[3])]):
        data.append({'id':f'emerald_frontier_west_flag_{n+1}','kind':'stack (model as one)','note':'Complete west flag source/base variant '+str(n+1),'layout':[[w(124),w(125)],[w(131),w(132)],[left,right]]})
    crown='g3q/10755-10756-10757-10758'
    for i in (178,179):data.append({'id':f'emerald_frontier_gate_conifer_{i}','kind':'stack (model as one)','note':'Complete gate conifer with exact alternate base source','layout':[[crown],[e(i)]]})
    return data


def placement_evidence(art):
    grids={};result=[]
    for item in assemblies():
        layout=item['layout'];found=[]
        for p in art.locations.get(layout[0][0],[]):
            if p['map'] not in grids:grids[p['map']]=art.map_cells(art.maps[p['map']])
            g=grids[p['map']];x,y=p['x'],p['y']
            if all(y+dz<len(g) and x+dx<len(g[y+dz]) and g[y+dz][x+dx]==k for dz,line in enumerate(layout) for dx,k in enumerate(line)):found.append(p)
        result.append({**item,'occurrences':found})
    return result


def assembly_previews(table,updates,additions,art):
    from tools.shape_fit.hand_check import picture,front
    dependency={}
    for family in ('vegetation','environment'):
        dependency.update(json.loads((ROOT/f'local/model-gallery/emerald-pass-study/{family}/updates.json').read_text())['cells'])
    for item in assemblies():
        target=OUT/'assemblies'/item['id'];target.mkdir(parents=True,exist_ok=True);layout=item['layout']
        art.drawing(layout,'BAKED').resize((len(layout[0])*112,len(layout)*112),Image.Resampling.NEAREST).save(target/'source.png')
        for revision,entries in [('before',table['cells']),('after',{**table['cells'],**dependency,**updates,**additions})]:
            # These exact flag/tree variants lack matching baseline links, so
            # their baseline geometry occupies separate map rows. The candidate
            # uses the documented on/alsoOn corrections to stand on the base.
            stack=item['kind'].startswith('stack') and revision=='after'
            masks=[];colors=[]
            for row in (layout[::-1] if stack else layout):
                objs=[];paints=[]
                for k in row:
                    if k in entries:occ,paint=decode(entries[k])
                    else:
                        # Missing original fitted base used the ordinary solid carrier.
                        m=Model();m.box((0,16),(0,16),(0,16));m.project();occ,paint=m.occ,m.paint
                    pixels=np.asarray(art.cell(k,'BAKED').convert('RGB'));objs.append(occ);paints.append(pixels[paint//16,paint%16])
                masks.append(np.concatenate(objs,axis=2));colors.append(np.concatenate(paints,axis=2))
            occ=np.concatenate(masks,axis=0 if stack else 1);paint=np.concatenate(colors,axis=0 if stack else 1)
            picture(occ,paint,target/f'{revision}-angled.png',8);front(occ,paint,target/f'{revision}-front.png',10)


def _historical_full_export():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args()
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);updates,additions=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    proof=json.loads((OUT/'coverage-proof.json').read_text())
    for group,items in decisions().items():
        rows=rows_for(group)
        for i,(status,label,reason) in sorted(items.items()):
            row=rows[i-1];k=row['key'];sprite=art.cell(k,'BAKED');target=OUT/group/k.removeprefix('g3q/');target.mkdir(parents=True,exist_ok=True);sprite.save(target/'source.png')
            evidence=[{'source_tilesets':row['tileset_membership'],'map_occurrences':row['occurrences'],'contact_sheet':str((INVENTORY/row['contact_page']).relative_to(ROOT)),'contact_position':i},
                {'whole_map':str((OUT/group/'whole-map.png').relative_to(ROOT)),'coverage_proof':proof[k]},
                {'source':str((target/'source.png').relative_to(ROOT)),'source_pixels_sha256':hashlib.sha256(sprite.tobytes()).hexdigest()}]
            if args.previews and k in updates:render(updates[k],sprite,target/'after');render(table['cells'][k],sprite,target/'before')
            if k in updates:evidence.append({'candidate_preview':str((target/'after/angled.png').relative_to(ROOT))})
            ledger.append({'key':k,'kind':'cell','label':label,'family':group,'decision':status,'reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':evidence,'visual_status':'source/map/orthographic inspection; candidate Minecraft capture pending'})
    (OUT/'updates.json').write_text(json.dumps({'cells':updates,'buildings':{}},indent=2)+'\n');(OUT/'ledger.json').write_text(json.dumps(ledger,indent=2)+'\n')
    (OUT/'additions.json').write_text(json.dumps({'cells':additions,'buildings':{}},indent=2)+'\n')
    added=[]
    for k,entry in additions.items():
        source=art.cell(k,'BAKED');target=OUT/'added-bases'/k.removeprefix('g3q/');target.mkdir(parents=True,exist_ok=True);source.save(target/'source.png')
        if args.previews:render(entry,source,target/'after')
        added.append({'key':k,'kind':'cell','decision':'added','family':'frontier_flag_base_addition','label':'west flag narrow pale curb and pole foot','reason':'Exact missing fitted base from fifteen source-proven west flag assemblies; narrow stone border and pole foot support the vertically linked cloth.','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(entry),'source_pixel_sha256':hashlib.sha256(source.tobytes()).hexdigest(),'occurrences':art.locations[k],'exact_occurrences':art.locations[k],'evidence':[{'source':str((target/'source.png').relative_to(ROOT)),'candidate_preview':str((target/'after/angled.png').relative_to(ROOT))}]})
    (OUT/'added-ledger.json').write_text(json.dumps(added,indent=2)+'\n')
    corrections=[]
    for k,fields in metadata_corrections(table,art).items():
        corrections.append({'key':k,'baseline_entry_sha256':digest(table['cells'][k]),'before':{f:table['cells'][k].get(f) for f in fields},'after':fields,'occurrences':art.locations[k],'reason':'Exact complete flag stack; actual matching lower sources verified in assembly-placements.json and also-on-source-proof.json'})
    (OUT/'metadata-corrections.json').write_text(json.dumps(corrections,indent=2)+'\n')
    (OUT/'entities.json').write_text(json.dumps(assemblies(),indent=2)+'\n');(OUT/'assembly-placements.json').write_text(json.dumps(placement_evidence(art),indent=2)+'\n')
    if args.previews:assembly_previews(table,updates,additions,art)
    print(json.dumps({'reviewed':len(ledger),'changed':len(updates),'retained':len(ledger)-len(updates),'added':len(additions)}))


def main():
    """Reproduce active ownership without changing frozen historical evidence."""
    parser=argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
    assert sha(OUT/'ledger.json')==FROZEN_LEDGER_SHA256
    assert sha(OUT/'updates.json')==FROZEN_UPDATES_SHA256
    old=json.loads((OUT/'ledger.json').read_text());removed={key(WEST,i) for i in TRANSFERRED_WEST}
    kept=[r for r in old if r['key'] not in removed]
    assert len(old)==357 and len(kept)==351
    assert all(r['decision']=='retained' for r in old if r['key'] in removed)
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);updates,additions=generate(table,art)
    frozen=json.loads((OUT/'updates.json').read_text())
    assert {'cells':updates,'buildings':{}}==frozen
    assert additions==json.loads((OUT/'additions.json').read_text())['cells']
    active={key(group,i) for group,items in decisions().items() for i in items}
    assert active=={r['key'] for r in kept}
    CANONICAL_OUT.mkdir(parents=True,exist_ok=True)
    (CANONICAL_OUT/'ledger.json').write_text(json.dumps(kept,indent=2)+'\n')
    (CANONICAL_OUT/'updates.json').write_bytes((OUT/'updates.json').read_bytes())
    provenance={'status':'Six previously retained rail cells transferred to source-specific material repair owner; all other decisions and the29 changed entries preserved.',
        'source_ledger':str((OUT/'ledger.json').relative_to(ROOT)),'source_ledger_sha256':FROZEN_LEDGER_SHA256,
        'source_updates_sha256':FROZEN_UPDATES_SHA256,'transferred_keys':sorted(removed),'new_owner':'frontier_rail_material',
        'active_ledger_sha256':sha(CANONICAL_OUT/'ledger.json'),'active_updates_sha256':sha(CANONICAL_OUT/'updates.json'),
        'unchanged_auxiliary_inputs':{name:sha(OUT/name) for name in ('additions.json','added-ledger.json','entities.json','metadata-corrections.json')}}
    (CANONICAL_OUT/'ownership-transfer.json').write_text(json.dumps(provenance,indent=2)+'\n')
    print(json.dumps({'reviewed':len(kept),'changed':len(updates),'retained':len(kept)-len(updates),'transferred':6,'ledger_sha256':provenance['active_ledger_sha256'],'updates_sha256':provenance['active_updates_sha256']}))


if __name__=='__main__':main()
