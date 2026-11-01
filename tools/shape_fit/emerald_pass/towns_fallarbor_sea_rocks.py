"""One coherent four-cell Route115 sea outcrop, native source variants only."""
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

OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-fallarbor-sea-rocks'
OWNED=CHANGED={96,97,99,100}
ROLES={96:(0,0),97:(1,0),99:(0,1),100:(1,1)}
COMMON=((90,90,58),(115,115,74),(148,140,115))


def whole():
    m=Model(height=32,width=32,depth=32);y,z,x=np.indices(m.occ.shape)
    # The source is one pointed olive outcrop, not four unrelated pedestals.
    # A broad elliptical foot and a gently faceted rising cone stay connected
    # along both source-cell seams, with no hollow lower shell.
    radius=np.sqrt(((x+.5-16)/15.5)**2+((z+.5-16)/14.7)**2)
    height=4+24*np.maximum(0,1-radius)**.75
    m.occ[:]=(radius<=1)&(y+.5<height)
    return m


def address(sprite,color):
    hits=np.argwhere((np.asarray(sprite.convert('RGB'))==color).all(axis=2));assert len(hits),color
    return int(hits[0,0]*16+hits[0,1])


def section(sprite,n,model):
    qx,qz=ROLES[n];m=Model(height=32);m.occ[:]=model.occ[:,qz*16:qz*16+16,qx*16:qx*16+16]
    y,z,x=np.indices(m.occ.shape);gx,gz=x+qx*16,z+qz*16;pattern=(gx//3*7+gz//4*5+y//3)%11
    tone=np.clip((.7+y*.06+(31-gx)*.035-gz*.012+(pattern-5)*.045).astype(int),0,2)
    m.paint[:]=np.asarray([address(sprite,c) for c in COMMON],np.uint8)[tone]
    if qz==0:
        bright=address(sprite,(206,206,165));pale=address(sprite,(173,173,140))
        # North-source highlight fades out before the south-source boundary;
        # no blue water or gray floor is stretched onto the stone sides.
        m.paint[(y>8)&(gz<14)&(pattern<7)]=pale
        m.paint[(y>12)&(gz<12)&(gx<22)&(pattern<4)]=bright
    return m


def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('fallarbor');model=whole()
    return {rows[n-1]['key']:section(art.cell(rows[n-1]['key'],'BAKED'),n,model).entry(table['cells'][rows[n-1]['key']],'hand-emerald-route115-sea-outcrop',trim=True) for n in sorted(OWNED)}


def assemblies(art):
    rows=rows_for('fallarbor');layout=[[rows[95]['key'],rows[96]['key']],[rows[98]['key'],rows[99]['key']]];locations=art.locations[rows[95]['key']]
    for loc in locations:
        g=art.map_cells(art.maps[loc['map']]);x,y=loc['x'],loc['y'];assert [r[x:x+2] for r in g[y:y+2]]==layout
    assert len(locations)==15
    result=[{'id':'emerald_fallarbor_sea_outcrop','kind':'object (model as one)','note':'Complete four-cell pointed olive sea outcrop; exact same pattern at15 Route115 locations. Native water floor and collisions unchanged.','layout':layout,'occurrences':locations}]
    g=art.map_cells(art.maps['EM_ROUTE115']);result.append({'id':'emerald_fallarbor_sea_outcrop_pair','kind':'object (model as one)','note':'Actual diagonally adjacent pair at Route115; inspect interfaces between both complete outcrops.','layout':[r[2:5] for r in g[31:35]],'occurrences':[{'map':'EM_ROUTE115','x':2,'y':31,'palette':'BAKED'}]})
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('fallarbor');updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n in sorted(OWNED):
        row=rows[n-1];k=row['key'];source=art.cell(k,'BAKED');target=OUT/k[4:];target.mkdir(exist_ok=True);source.save(target/'source.png')
        if args.previews:render(table['cells'][k],source,target/'before');render(updates[k],source,target/'after')
        label='Route115 olive sea outcrop '+{96:'northwest shoulder',97:'northeast shoulder',99:'southwest foot',100:'southeast foot'}[n]
        ledger.append({'key':k,'kind':'cell','label':label,'family':'route115-complete-olive-sea-outcrop','decision':'changed','reason':'All15 source occurrences form the same complete2x2 pointed olive sea rock. Author one connected broad-footed cone before slicing this exact quarter; remove the old central trench and thin hourglass walls. Paint only native olive stone, with the northern highlights fading before the south-source seam. Blue water and gray ground pixels never become stone sides; source collision/elevation and metadata remain unchanged.','baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':row['tileset_membership'],'map_occurrences':witnesses(art,row),'contact_sheet':(INVENTORY/row['contact_page']).relative_to(ROOT).as_posix(),'contact_position':n},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(source.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'context_sheet':f'local/model-gallery/emerald-pass-study/towns/general__fallarbor/contexts/page-{(n-1)//16+1:03}.png'}],'visual_status':'source and exact whole occurrences reviewed; Python/runtime pending'})
    live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';current=json.loads(live.read_text());entities=assemblies(art)
    if args.previews:
        for e in entities:
            target=OUT/'assemblies'/e['id'];target.mkdir(parents=True,exist_ok=True);layout=e['layout'];art.drawing(layout,'BAKED').resize((len(layout[0])*64,len(layout)*64),Image.Resampling.NEAREST).save(target/'source.png');fitted_preview(e,art,table['cells'],target,'before');fitted_preview(e,art,{**current['cells'],**updates},target,'after');preview(e,art,{**current['cells'],**updates},target,'runtime-floor')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'elevation_simulated':False})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'reviewed':4,'changed':4,'complete_occurrences':15,'assemblies':2}))


if __name__=='__main__':main()
