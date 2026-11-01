"""Rustboro's exact nine-cell octagonal fountain, constructed before slicing."""
from __future__ import annotations
import argparse,hashlib,json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model,decode,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses,preview
from tools.shape_fit.emerald_pass.towns_fortree_ground import fitted_preview

OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-rustboro-fountain'
ROLES={71:(0,0),72:(1,0),73:(2,0),77:(0,1),78:(1,1),79:(2,1),84:(0,2),85:(1,2),86:(2,2)}
OWNED=CHANGED=set(ROLES)
STONE=((66,74,107),(99,99,123),(132,132,140),(222,222,222))
WATER=(132,181,181);JET=(197,222,239)

def fountain():
    m=Model(height=32,width=48,depth=48);y,z,x=np.indices(m.occ.shape);dx=abs(x+.5-24);dz=abs(z+.5-24)
    def octagon(radius):return (dx<radius)&(dz<radius)&(dx+dz<radius*1.42)
    outer=octagon(23);wall=octagon(22);inner=octagon(18.5)
    m.put(outer&(y<2),1);m.put(wall&~inner&(y>=2)&(y<7),1)
    m.put(outer&~octagon(18)&(y>=7)&(y<9),2)
    m.put(inner&(y<3),3)
    m.cylinder((24,24),7,(3,7),1);m.cylinder((24,24),7.6,(7,10),2)
    m.cylinder((24,24),5.7,(9,11),cut=True);m.cylinder((24,24),5.7,(8,9),3)
    m.cylinder((24,24),1.35,(8,25),4)
    # Connected streams bend from the central jet toward the circular bowl.
    for axis in ('x','z'):
        for side in (-1,1):
            for step in range(6):
                yy=23-step*2;distance=24+side*step*.85
                m.cylinder((distance,24) if axis=='x' else (24,distance),1.0,(yy-1,yy+2),4)
    return m

def address(sprite,color):
    hits=np.argwhere((np.asarray(sprite.convert('RGB'))==color).all(axis=2));assert len(hits),color
    return int(hits[0,0]*16+hits[0,1])

def section(sprite,n,whole):
    qx,qz=ROLES[n];m=Model(height=32);s=(slice(None),slice(qz*16,qz*16+16),slice(qx*16,qx*16+16));m.occ[:]=whole.occ[s];kinds=whole.paint[s]
    y,z,x=np.indices(m.occ.shape);dark=(y<2)|((y==5)&(kinds==1));tone=np.where(dark,1,np.where(y>=7,3,2));m.paint[:]=np.asarray([address(sprite,c) for c in STONE],np.uint8)[tone]
    m.paint[kinds==2]=address(sprite,STONE[3]);m.paint[kinds==3]=address(sprite,WATER)
    if (kinds==4).any():m.paint[kinds==4]=address(sprite,JET)
    return m

def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('rustboro');whole=fountain()
    return {rows[n-1]['key']:section(art.cell(rows[n-1]['key'],'BAKED'),n,whole).entry(table['cells'][rows[n-1]['key']],'hand-emerald-rustboro-fountain',trim=True) for n in sorted(OWNED)}

def decisions():
    names={71:'northwest beveled rim',72:'north rim',73:'northeast beveled rim',77:'west basin wall',78:'central bowl and water jet',79:'east basin wall',84:'southwest beveled rim',85:'south rim',86:'southeast beveled rim'}
    return {n:('changed','Rustboro fountain '+names[n],'Reconstruct the exact three-by-three town fountain as one octagonal stone basin before slicing. A grounded stepped foot and continuous pale coping enclose shallow native teal water; the central rounded bowl supports a connected pale water jet. This source cell is the '+names[n]+'. Exclude the surrounding cream pavement from all stone and water surfaces. Preserve all nine SOLID source cells, metadata and their exact town positions.') for n in sorted(OWNED)}

def assemblies(art):
    g=art.map_cells(art.maps['EM_RUSTBORO_CITY']);return [{'id':'emerald_rustboro_octagonal_fountain','kind':'object (model as one)','note':'Exact three-by-three Rustboro fountain at(27,38), allnine SOLID7/elevation0; grounded octagonal basin, low continuous rim and central bowl/jet.','layout':[r[27:30] for r in g[38:41]],'occurrences':[{'map':'EM_RUSTBORO_CITY','x':27,'y':38,'palette':'BAKED'}]}]

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('rustboro');updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n,(_,label,reason) in decisions().items():
        r=rows[n-1];k=r['key'];source=art.cell(k,'BAKED');target=OUT/k[4:];target.mkdir(exist_ok=True);source.save(target/'source.png')
        if args.previews:render(table['cells'][k],source,target/'before');render(updates[k],source,target/'after')
        ledger.append({'key':k,'kind':'cell','label':label,'family':'rustboro-complete-octagonal-fountain','decision':'changed','reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(source.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__rustboro/contexts/page-{(n-1)//16+1:03}.png','whole':'emerald_rustboro_octagonal_fountain','source_footprint':[27,38,3,3]}],'visual_status':'source and existing front/east/top inspected; candidate Python/runtime review pending'})
    entities=assemblies(art);live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';current=json.loads(live.read_text())
    if args.previews:
        for e in entities:
            target=OUT/'assemblies'/e['id'];target.mkdir(parents=True,exist_ok=True);art.drawing(e['layout'],'BAKED').resize((384,384),Image.Resampling.NEAREST).save(target/'source.png');fitted_preview(e,art,table['cells'],target,'before');fitted_preview(e,art,{**current['cells'],**updates},target,'after');preview(e,art,{**current['cells'],**updates},target,'runtime-floor')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'elevation_simulated':False,'metadata_changed':False})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'reviewed':9,'changed':9,'assemblies':1}))

if __name__=='__main__':main()
