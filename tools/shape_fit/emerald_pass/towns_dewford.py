"""All59 individually inspected Dewford/island source cells and exact wholes."""
from __future__ import annotations
import argparse
import hashlib
import json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.blue_pass.common import Model,decode,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,texel,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses,preview
from tools.shape_fit.emerald_pass import environment,vegetation,objects_dewford

OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-dewford'
CHANGED={1,2,4,5,6,7,8,9,10,11,12,13,14,15,35,36,37,38,39,40}
TREE_COLORS=((58,82,0),(58,140,49),(132,197,99),(181,255,132))


def key(n):return rows_for('dewford')[n-1]['key']


def small_tree(sprite,n):
    whole=Model(height=32);whole.cylinder((8,8),2.6,(0,12),1)
    for x,z in ((5,9),(10,9),(8,12)):whole.ellipsoid((x,2,z),(2.4,2.4,2),1)
    whole.ellipsoid((8,18,8),(7.6,11.2,7.4),2)
    for x,z in ((4,5),(12,5),(4,11),(12,11)):whole.ellipsoid((x,17,z),(3.7,6,3.5),2)
    start=0 if n==10 else (8 if n==12 else 16)
    m=Model();m.occ[:]=whole.occ[start:start+16];leaf=whole.paint[start:start+16]==2
    if n==12:
        # A dense repeated middle must join the broad lower canopy, rather
        # than introducing a narrow waist between two foliage sections.
        m.cylinder((8,8),(7.3,7.0),(0,11))
    rgb=np.asarray(sprite.convert('RGB'));palette=[]
    for color in TREE_COLORS:
        spots=np.argwhere((rgb==color).all(axis=2))
        if len(spots):palette.append(int(spots[0] @ np.array([16,1])))
    yy,zz,xx=np.indices(m.occ.shape);pattern=((xx//2*7+zz//2*11+yy//2*3)%13)
    q=np.clip((yy+start)/9+(pattern-6)/10,0,len(palette)-1).astype(int)
    m.paint[:]=np.asarray(palette,dtype=np.uint8)[q]
    if n==10:
        bark=texel(sprite,(107,90,90));light=texel(sprite,(165,99,90));m.paint[m.occ&~leaf]=bark
        m.paint[m.occ&~leaf&(xx<7)]=light
    return m


def gym_sign(sprite,upper):
    m=Model(height=32);gray=texel(sprite,(99,99,123));pale=texel(sprite,(222,230,239));dark=texel(sprite,(41,49,90))
    m.box((6,10),(0,7),(7,10),gray);m.box((4,12),(0,2),(5,12),dark)
    m.box((1,15),(6,23),(6,11),gray);m.box((2,14),(7,22),(10,12),pale)
    m.ellipsoid((8,19,8.5),(7,5,2.5),gray)
    start=16 if upper else 0;o=m.occ[start:start+16].copy();q=Model();q.occ[:]=o
    yy,zz,xx=np.indices(q.occ.shape);q.paint[:]=gray
    # Each half uses its own native face rectangle, including the red GYM text.
    q.paint[o&(zz>=9)]=((15-yy)*16+xx)[o&(zz>=9)]
    colors=np.asarray(sprite.convert('RGB'))[q.paint//16,q.paint%16]
    sand=np.any(np.all(colors[...,None,:]==np.asarray(((222,206,132),(214,181,107),(239,230,165))),axis=-1),axis=-1)
    q.paint[o & sand]=pale
    return q


def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);out={}
    for n in sorted(CHANGED):
        k=key(n);old=table['cells'][k];sprite=art.cell(k,'BAKED')
        if n in (1,2):out[k]=gym_sign(sprite,n==1).entry(old,'hand-emerald-dewford-sign');continue
        if n in (5,6,7,8,35,36,37,38,39,40):out[k]=environment.horizontal(old);continue
        out[k]=small_tree(sprite,n).entry(old,'hand-emerald-dewford-broadleaf')
        if n==9:
            out[k]['alsoOn']=[key(10)]
            out[k]['stackFit']=True
    return out


def decisions():
    labels={1:'Dewford gym sign rounded cap',2:'Dewford gym sign body and foot',3:'Dewford blue-roof east tile seam',4:'coastal broadleaf crown over water',5:'Dewford pier west planks',6:'Dewford pier middle planks',7:'Dewford pier west lower water-edge projection',8:'Dewford pier lower water-edge projection',9:'sandy coastal broadleaf crown',10:'coastal broadleaf trunk and roots',11:'coastal broadleaf crown against straight cliff',12:'dense coastal broadleaf middle',13:'coastal broadleaf crown at cliff turn',14:'coastal broadleaf crown beside west cliff',15:'coastal broadleaf crown beside east cliff',16:'north island tree northwest crown',17:'north island tree crown center',18:'north island tree northeast crown',19:'west island tree outer northwest projection',20:'island tree northwest crown',21:'island tree northeast crown',22:'west island tree outer northeast projection',23:'north island tree west canopy',24:'north island tree middle canopy',25:'north island tree east canopy',26:'west island tree outer west leaf',27:'island tree inner west canopy',28:'island tree inner east canopy',29:'west island tree outer east leaf',30:'north island tree western root shadow',31:'north island tree trunk',32:'north island tree eastern root shadow',33:'island tree western root half',34:'island tree eastern root half',35:'island gangway northwest plank edge',36:'island gangway upper planks',37:'island gangway northeast plank edge',38:'island gangway west plank edge',39:'island gangway middle planks',40:'island gangway east plank edge',41:'ferry rear west roof projection',42:'ferry rear east roof projection',43:'ferry port orange grille',44:'ferry west striped roof edge',45:'ferry repeated striped roof',46:'ferry east striped roof edge',47:'ferry starboard orange grille',48:'ferry port side pod foot',49:'ferry starboard side pod foot',50:'ferry west roof and cabin seam',51:'ferry west cabin window band',52:'ferry central cabin window band',53:'ferry east cabin window band',54:'ferry east roof and cabin seam',55:'ferry west hull corner',56:'ferry west hull band',57:'ferry triangular stern insignia',58:'ferry east hull band',59:'ferry east hull corner'}
    out={}
    for n,label in labels.items():
        if n in (1,2):reason='Author the two existing on-linked sign halves as one narrow rooted metal sign, with a rounded cap, bounded face and the native red GYM lettering; remove surrounding sand and the pinched support profile.'
        elif n in (5,6,7,8,35,36,37,38,39,40):reason='Retain the inspected shallow deck occupancy and place native plank/water-edge art on upward x/z faces. Actual WALK gangways continue to use native floor routing; no additional walk decoration or collision is introduced.'
        elif n in (4,9,10,11,12,13,14,15):reason='Replace the pinched or almost-empty foliage extrusion with a connected native broadleaf sculpture: a rooted brown base, dense continuous middle and rounded crown. Only this source variant\'s green/bark pixels paint the tree; surrounding sand, water and cliff pixels remain outside its surfaces. Preserve exact existing stack metadata while recording unmatched source adjacency separately.'
        elif n==3:reason='Preserve this source-specific roof seam as a fallback: existing b036/b037 and the exact new DewfordHouse1 template cover its three actual source placements. Complete blue tiled roofs supply the live architecture.'
        elif n<=34:reason='Preserve this exact matching source fragment under the new complete Birth Island tree templates. Every occurrence is claimed by an exact reviewed north/west/east source pattern; the whole rooted tree replaces the former low disconnected canopy slabs. This is a covered fallback retention, not approval of the isolated old extrusion.'
        else:reason='Preserve the explicit native '+label+' fallback while the exact seven-by-five ferry template supplies its continuous cabin, roof, hull and side fixtures at both island harbors. Every occurrence lies in those two proven complete source patterns.'
        if n==9:reason=reason.replace('Preserve exact existing stack metadata while recording unmatched source adjacency separately.','Preserve primary on and add only the exact trunk-base alternate observed at nine source placements. Set stackFit only on this crown so the two source-proven roof-inferred bases use the fitted tree stack; all nine actual-export placements pass, and other unmatched boundary/house adjacency stays separate.')
        out[n]=('changed' if n in CHANGED else 'retained',label,reason)
    assert set(out)==set(range(1,60));return out


def assemblies(art):
    result=[]
    for ident,mid,x,y,w,h,note in [('gym_sign','EM_DEWFORD_TOWN',11,15,1,2,'Exact connected gym sign above its solid base.'),('pier','EM_DEWFORD_TOWN',11,9,3,2,'Actual pier with native walkable planks and lower water-edge projections.'),('island_gangway','EM_BIRTH_ISLAND_EXTERIOR',14,20,3,4,'Complete native island gangway leading to the ferry warp.'),('sandy_tree','EM_DEWFORD_TOWN',15,0,1,2,'Two-row coastal tree with middle over trunk.'),('water_trees','EM_ROUTE105',14,42,1,4,'Actual four-row coastal forest column: crown/middle rear tree and middle/base front tree; no invented self-link.'),('cliff_forest','EM_ROUTE106',43,17,1,3,'Actual cliff-background crown and adjoining forest middle cells at the map boundary; no invented out-of-map trunk.')]:
        g=art.map_cells(art.maps[mid]);layout=[r[x:x+w] for r in g[y:y+h]]
        result.append({'id':'emerald_dewford_'+ident,'kind':'stack (model as one)' if w==1 else 'object (model as one)','note':note,'layout':layout,'occurrences':[{'map':mid,'x':x,'y':y,'palette':'BAKED'}]})
    pairs=alias_witnesses(art)
    loc=pairs[0];result.append({'id':'emerald_dewford_direct_crown_base','kind':'stack (model as one)','note':'Exact source crown directly over the native trunk base, using the separately evidenced alternate base alias.','layout':[[key(9)],[key(10)]],'occurrences':[{k:loc[k] for k in ('map','x','y','palette')}]})
    return result


def alias_witnesses(art):
    result=[];bases={(r['map'],r['x'],r['y']):r for r in witnesses(art,rows_for('dewford')[9])}
    for loc in witnesses(art,rows_for('dewford')[8]):
        g=art.map_cells(art.maps[loc['map']]);y=loc['y']+1
        if y<len(g) and g[y][loc['x']]==key(10):
            base=bases[(loc['map'],loc['x'],y)]
            result.append({**loc,'below_key':key(10),'below_x':loc['x'],'below_y':y,'below_collision':base['collision'],'below_elevation':base['elevation']})
    assert len(result)==9;return result


def coverage(art,table):
    merged={**table,'buildings':table['buildings']+list(objects_dewford.generate(table,art).values())};catalog=building_catalog(merged,art);proof={}
    for n in set(range(1,60))-CHANGED:
        k=key(n);covered=[]
        for loc in art.locations[k]:
            matches=[]
            for b in catalog:
                for place in b['occurrences']:
                    x,z=loc['x']-place['x'],loc['y']-place['y']
                    if loc['map']==place['map'] and 0<=z<len(b['layout']) and 0<=x<len(b['layout'][z]) and b['layout'][z][x]==k:
                        entry=next(e for e in merged['buildings'] if e['id']==b['id'])
                        if [x,z] not in entry.get('terrain',[]):matches.append({'id':b['id'],'local_cell':[x,z]})
            assert matches,(n,k,loc);covered.append({'occurrence':loc,'buildings':matches})
        proof[k]={'fully_covered':True,'occurrences':covered}
    return proof


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args()
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);rows=rows_for('dewford');proof=coverage(art,table);ledger=[]
    for n,(decision,label,reason) in decisions().items():
        row=rows[n-1];k=row['key'];sprite=art.cell(k,'BAKED');target=OUT/k[4:];target.mkdir(exist_ok=True);sprite.save(target/'source.png')
        if args.previews and k in updates:render(table['cells'][k],sprite,target/'before');render(updates[k],sprite,target/'after')
        evidence=[{'source_tilesets':row['tileset_membership'],'map_occurrences':witnesses(art,row),'contact_sheet':(INVENTORY/row['contact_page']).relative_to(ROOT).as_posix(),'contact_position':n},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__dewford/contexts/page-{(n-1)//16+1:03}.png'}]
        if k in proof:evidence.append({'exact_complete_object_coverage':proof[k]})
        if n==9:evidence.append({'exact_alternate_base_proof':'local/model-gallery/emerald-pass-study/towns-dewford/metadata-corrections.json','preserved_primary_on':table['cells'][k]['on'],'sole_added_alias':key(10),'stackFit':True,'actual_export_runtime_proof':'local/model-gallery/emerald-pass-study/towns-dewford/runtime-proof.json'})
        ledger.append({'key':k,'kind':'cell','label':label,'family':'dewford-coast-island','decision':decision,'reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':evidence,'visual_status':'source and map reviewed; final preview/runtime pending'})
    entities=assemblies(art);live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';current=json.loads(live.read_text())
    if args.previews:
        for item in entities:
            target=OUT/'assemblies'/item['id'];target.mkdir(parents=True,exist_ok=True);layout=item['layout'];art.drawing(layout,'BAKED').resize((len(layout[0])*96,len(layout)*96),Image.Resampling.NEAREST).save(target/'source.png')
            preview(item,art,table['cells'],target,'before');preview(item,art,{**current['cells'],**updates},target,'after')
    adjacency=[]
    for n in (4,9,11,12,13,14,15):
        k=key(n);entry=updates.get(k,table['cells'][k]);on=entry.get('on');allowed=[on,*entry.get('alsoOn',[])]
        for loc in art.locations[k]:
            g=art.map_cells(art.maps[loc['map']]);below=g[loc['y']+1][loc['x']] if loc['y']+1<len(g) else None
            if below not in allowed:
                disposition=('Source map boundary: no exported lower carrier; do not invent an out-of-map trunk.' if below is None else
                             'Repeated solid forest middle stays separate grounded foliage; this is dense forest, not proof of a vertical self-link.' if n==12 and below==key(12) else
                             'Crown beside a native house roof: preserve existing relationship; actual-map interface remains a specific runtime review case.')
                adjacency.append({**loc,'key':k,'on':on,'alsoOn':entry.get('alsoOn',[]),'below':below,'disposition':disposition})
    meta=lambda e:{k:v for k,v in e.items() if k not in ('v','t','up','from')}
    alias={'key':key(9),'before':meta(table['cells'][key(9)]),'after':meta(updates[key(9)]),'source_pixel_sha256':hashlib.sha256(art.cell(key(9),'BAKED').convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB','witnesses':alias_witnesses(art),'occurrences':alias_witnesses(art),'reason':'Exactly nine native sandy crowns stand directly above this solid trunk base instead of the usual middle. Preserve primary on, add this sole alternate and set stackFit on this crown. Actual-export runtime evidence shows Dewford (16,4) and Route107 (5,4) otherwise infer roof carriers and omit the WALK crown; the exact flag restores the fitted stack at all nine witnesses. Boundary/cliff/house contexts remain unchanged.','runtime_proof':'local/model-gallery/emerald-pass-study/towns-dewford/runtime-proof.json','pre_correction_runtime_proof':'local/model-gallery/emerald-pass-study/towns-dewford/pre-stack-fit-runtime-proof.json'}
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('coverage-proof.json',proof),('unmatched-stack-adjacency.json',adjacency),('metadata-corrections.json',[alias]),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'native_walk_floor_routing_preserved':True})]:
        (OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'reviewed':59,'changed':len(updates),'retained':59-len(updates),'entities':len(entities),'unmatched_stack_occurrences':len(adjacency)}))


if __name__=='__main__':main()
