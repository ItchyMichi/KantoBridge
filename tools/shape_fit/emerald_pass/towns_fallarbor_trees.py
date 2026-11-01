"""Three-lobed Fallarbor trees on their native two-by-one solid footprint."""
from __future__ import annotations
import argparse
import hashlib
import json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model,decode,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses,preview
from tools.shape_fit.emerald_pass.towns_fortree_ground import fitted_preview

OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-fallarbor-trees'
UPPER_LEFT={2,4,11,59,66};UPPER_RIGHT={3,5,60,67}
LOWER_LEFT={7,9,64};LOWER_RIGHT={8,10,65}
MIX_LEFT={34,36,40,42,68};MIX_RIGHT={35,37,39,41,69}
BASE_LEFT=LOWER_LEFT|MIX_RIGHT;BASE_RIGHT=LOWER_RIGHT|MIX_LEFT
PROJECTIONS=(UPPER_LEFT|UPPER_RIGHT)-{59,60}
# The two upper orange-cliff variants are separate terrain review cases.
# Their tree is carried entirely by the lower solid halves below them.
OWNED=BASE_LEFT|BASE_RIGHT|PROJECTIONS
CHANGED=OWNED
LEAVES=((74,82,33),(115,132,49),(156,189,99),(197,255,156))
BARK=((58,33,8),(156,107,66),(197,181,115))


def tree():
    m=Model(height=36,width=32,depth=16)
    m.cylinder((16,9),3.1,(0,18),1)
    for x,z in ((13,10),(19,10),(16,13)):m.ellipsoid((x,2,z),(3.2,2.8,2.8),1)
    # Two rising limbs support the paired forward foliage lobes.
    for y in range(9,21):
        spread=(y-9)*.55
        for side in (-1,1):m.cylinder((16+side*spread,8.5),1.8,(y,y+1),1)
    # Three explicit rounded crowns follow the distinctive source tree,
    # rather than substituting the accepted Littleroot's different silhouette.
    for center,radii in [((16,27,5),(10.3,8.4,4.8)),((8.5,22,9.5),(8.1,8.5,6)),((23.5,23,9.5),(8.1,8.3,6)),((16,19,9),(8,6,5))]:m.ellipsoid(center,radii,2)
    return m


def roles(n):
    if n in UPPER_LEFT:return [(0,0)]
    if n in UPPER_RIGHT:return [(1,0)]
    if n in LOWER_LEFT:return [(0,1)]
    if n in LOWER_RIGHT:return [(1,1)]
    if n in MIX_LEFT:return [(1,1),(0,0)]
    if n in MIX_RIGHT:return [(0,1),(1,0)]
    raise KeyError(n)


def address(sprite,color):
    hits=np.argwhere((np.asarray(sprite.convert('RGB'))==color).all(axis=2));assert len(hits),color
    return int(hits[0,0]*16+hits[0,1])


def section(sprite,n,whole):
    if n in PROJECTIONS:
        # A source crown projection is not another tree. The complete crown
        # is sculpted on the solid lower footprint. WALK/WATER cells retain
        # native floor routing; this is their shallow standalone fallback.
        m=Model();m.box((0,16),(0,1),(0,16));y,z,x=np.indices(m.occ.shape);m.paint[:]=(z*16+x).astype(np.uint8);return m
    m=Model(height=36);qx=0 if n in BASE_LEFT else 1
    m.occ[:]=whole.occ[:,:,qx*16:qx*16+16];kind=whole.paint[:,:,qx*16:qx*16+16]
    yy,zz,xx=np.indices(m.occ.shape);gx=xx+qx*16;noise=(gx//2*7+zz//2*11+yy//2*3)%17
    tone=np.where(yy>=25,np.where(noise<10,2,1),np.where(noise<6,2,np.where(noise<15,1,0)))
    # Both physical halves share these three actual foliage colors. The
    # right root source lacks the pale projected tip color, so it is not
    # borrowed from another cell or made into a hard painted seam.
    m.paint[:]=np.asarray([address(sprite,c) for c in LEAVES[:3]],np.uint8)[tone]
    bark=m.occ&(kind==1);browns=np.asarray([address(sprite,c) for c in BARK],np.uint8)
    m.paint[bark]=browns[((gx+zz*2+yy//3)%5>2).astype(int)][bark]
    return m


def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('fallarbor');whole=tree()
    return {rows[n-1]['key']:section(art.cell(rows[n-1]['key'],'BAKED'),n,whole).entry(table['cells'][rows[n-1]['key']],'hand-emerald-fallarbor-tree',trim=True) for n in sorted(OWNED)}


def decisions():
    backgrounds={2:'ash',3:'ash',4:'pink dirt',5:'pink dirt',7:'ash',8:'ash',9:'pink dirt',10:'pink dirt',11:'projected low orange cliff',34:'pink-dirt forest',35:'pink-dirt forest',36:'ash forest',37:'ash forest',39:'pink-dirt overlapping forest',40:'pink-dirt overlapping forest',41:'ash overlapping forest',42:'ash overlapping forest',59:'tall orange cliff',60:'tall orange cliff',64:'orange-cliff/pink-dirt foot',65:'orange-cliff/pink-dirt foot',66:'water',67:'water',68:'cliff and forest',69:'cliff and forest'}
    out={}
    for n in sorted(OWNED):
        role='projected upper crown' if n in PROJECTIONS else 'physical left tree half' if n in BASE_LEFT else 'physical right tree half'
        if n in PROJECTIONS:reason='Exact collision/adjacency review identifies this as the upper crown projected north of the tree\'s solid two-by-one footprint. Author the complete three-lobed tree on its actual lower root cells; this upper source fragment stays a one-high native horizontal projection fallback. WALK0/WATER41 occurrences retain native floor routing, and the seven exceptional SOLID7 crown occurrences retain source metadata without a second floating crown.'
        else:reason='Reconstruct the complete native three-lobed broadleaf tree on its actual two-by-one solid root footprint, then slice this '+role+'. A rooted brown trunk and rising branches support three rounded olive-green crowns, all above the same solid lower row. Use only this source variant\'s native foliage/bark; '+backgrounds[n]+' remains background rather than a painted pedestal.'
        if n in MIX_LEFT|MIX_RIGHT:reason+=' This mixed source contains a real lower trunk plus the next diagonal tree\'s upper projection. Retain the real trunk\'s physical half; that next tree gets its complete crown on its own solid row. No on/alsoOn/self link moves either trunk.'
        if n in (11,64,65,68,69):reason+=' The orange cliff pixels belong to the neighboring background cliff; no cliff pillar is inferred from them, and native cliff carriers remain intact.'
        out[n]=('changed','Fallarbor '+role+' on '+backgrounds[n],reason)
    return out


def assemblies(art):
    cases=[('ash_tree','EM_ROUTE113',76,3,2,2,'Complete ash-ground tree, exact four source quarters.'),('low_cliff_tree','EM_ROUTE114',17,11,2,2,'Complete tree beside the shallow orange cliff projection, with no terrain-colored leaf surfaces.'),('town_cliff_tree','EM_FALLARBOR_TOWN',4,3,2,2,'Complete tree standing before the north town cliff between the buildings.'),('north_cliff_grove','EM_ROUTE114',32,3,8,2,'Four complete neighboring cliff-background trees with all trunks and source seams.'),('staggered_grove','EM_ROUTE114',3,12,5,4,'Actual staggered forest segment: upper trees, diagonally offset middle trees and lower complete trunks; boundary source projections remain explicit.'),('water_forest','EM_ROUTE114',3,18,5,5,'Actual water-edge staggered grove with blue-backed crowns and cliff/forest overlaps; no invented stack relationships.'),('ash_forest','EM_ROUTE113',70,2,6,5,'Actual ash forest segment with repeated overlapping crown and trunk quarters.')]
    result=[]
    for ident,mid,x,y,w,d,note in cases:
        g=art.map_cells(art.maps[mid]);result.append({'id':'emerald_fallarbor_trees_'+ident,'kind':'object (model as one)','note':note,'layout':[r[x:x+w] for r in g[y:y+d]],'occurrences':[{'map':mid,'x':x,'y':y,'palette':'BAKED'}]})
    for ident,mid,x,y in [('ash_physical','EM_ROUTE113',76,4),('pink_physical','EM_ROUTE114',17,12),('cliff_physical','EM_FALLARBOR_TOWN',4,4),('water_physical','EM_ROUTE114',3,19)]:
        g=art.map_cells(art.maps[mid]);result.append({'id':'emerald_fallarbor_trees_'+ident,'kind':'object (model as one)','note':'Complete tree on its actual two-by-one solid root footprint. The north source crown projection is retained only in reference_layout, as in the accepted Littleroot workflow.','layout':[g[y][x:x+2]],'reference_layout':[r[x:x+2] for r in g[y-1:y+1]],'occurrences':[{'map':mid,'x':x,'y':y,'palette':'BAKED'}]})
    return result


def physical_pairs(art):
    rows=rows_for('fallarbor');by={r['key']:i+1 for i,r in enumerate(rows)};result={}
    for n in sorted(BASE_LEFT|BASE_RIGHT):
        delta=1 if n in BASE_LEFT else -1;allowed=BASE_RIGHT if n in BASE_LEFT else BASE_LEFT;places=[]
        for loc in witnesses(art,rows[n-1]):
            g=art.map_cells(art.maps[loc['map']]);x,y=loc['x'],loc['y'];nx=x+delta;neighbor=g[y][nx] if 0<=nx<len(g[0]) else None
            paired=by.get(neighbor) in allowed
            assert paired or neighbor is None or (loc['map']=='EM_ROUTE113' and (x,y) in {(31,2),(36,2)})
            places.append({**loc,'neighbor_key':neighbor,'neighbor_position':[nx,y],'complete_pair':paired,'disposition':'Exact matching solid left/right root halves.' if paired else 'Source boundary clips the other half; no tree outside the map is invented.' if neighbor is None else 'Glass Workshop roof source hides the opposite half; preserve only the visible source tree half beside the exact building.'})
        result[rows[n-1]['key']]=places
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('fallarbor');updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n,(_,label,reason) in decisions().items():
        r=rows[n-1];k=r['key'];source=art.cell(k,'BAKED');target=OUT/k[4:];target.mkdir(exist_ok=True);source.save(target/'source.png')
        if args.previews:render(table['cells'][k],source,target/'before');render(updates[k],source,target/'after')
        ledger.append({'key':k,'kind':'cell','label':label,'family':'fallarbor-three-lobed-tree','decision':'changed','reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix(),'contact_position':n,'authored_quarter_roles':roles(n)},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(source.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'context_sheet':f'local/model-gallery/emerald-pass-study/towns/general__fallarbor/contexts/page-{(n-1)//16+1:03}.png'}],'visual_status':'source/context inspected; Python/runtime pending'})
    live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';current=json.loads(live.read_text());entities=assemblies(art)
    from tools.shape_fit.emerald_pass.towns_fallarbor_cliffs import generate as cliffs
    combined={**current['cells'],**updates,**cliffs(table,art)}
    if args.previews:
        for e in entities:
            target=OUT/'assemblies'/e['id'];target.mkdir(parents=True,exist_ok=True);layout=e.get('reference_layout',e['layout']);art.drawing(layout,'BAKED').resize((len(layout[0])*64,len(layout)*64),Image.Resampling.NEAREST).save(target/'source.png');fitted_preview(e,art,table['cells'],target,'before');fitted_preview(e,art,combined,target,'after');preview(e,art,combined,target,'runtime-floor')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('physical-pair-proof.json',physical_pairs(art)),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'elevation_simulated':False,'metadata_changed':False,'includes_cliff_candidate':True})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'reviewed':len(OWNED),'changed':len(updates),'assemblies':len(entities)}))


if __name__=='__main__':main()
