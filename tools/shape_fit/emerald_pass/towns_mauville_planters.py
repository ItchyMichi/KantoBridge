"""Verdanturf low flower boxes, including the source two-cell-deep planter."""
import argparse,hashlib,json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model,render,encode
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass.towns_rustboro_props import address,render_assembly
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-mauville-planters'
OWNED={56,57,61}
LABELS={56:'Verdanturf short flower box',57:'Verdanturf long planter northern soil and foliage',61:'Verdanturf long planter southern wooden front'}
COLORS={1:(107,90,90),2:(222,148,115),3:(255,197,148),4:(66,58,49),5:(58,140,49),6:(132,197,99),7:(181,255,132),8:(206,66,82),9:(222,107,99)}

def planter(depth):
    m=Model(depth=depth);m.box((1,15),(0,6),(1,depth-1),2);m.box((2,14),(4,7),(2,depth-2),cut=True);m.box((2,14),(4,5),(2,depth-2),4)
    for lo in (1,13):
        m.box((lo,lo+2),(0,7),(1,depth-1),1);m.box((lo,lo+1),(6,7),(1,depth-1),3)
    for z in (1,depth-3):
        m.box((1,15),(0,6),(z,z+2),2);m.box((1,15),(3,4),(z,z+2),1);m.box((1,15),(5,7),(z,z+2),3)
    centers=[(4,4),(10,6),(6,11),(11,13)] if depth==16 else [(4,4),(10,6),(6,11),(11,14),(4,18),(10,21),(6,25),(11,28)]
    for i,(x,z) in enumerate(centers):
        m.box((x,x+1),(5,10),(z,z+1),5)
        m.ellipsoid((x,7,z),(2.7,2,2.5),5);m.ellipsoid((x-1,8,z+.5),(2.2,1.7,2),6);m.ellipsoid((x-1,9,z),(1.5,1,1.6),7)
        if i%2==0:
            m.ellipsoid((x+.5,10.2,z+.2),(2.3,1.2,2.3),8);m.box((x,x+1),(11,12),(z,z+1),9)
        else:
            m.ellipsoid((x+.3,10,z),(2,1.1,2),9);m.box((x,x+1),(10,11),(z,z+1),3)
    return m

def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('mauville');long=planter(32);short=planter(16);out={}
    for n in sorted(OWNED):
        k=rows[n-1]['key'];sprite=art.cell(k,'BAKED');model=short if n==56 else long;start=16 if n==61 else 0;o=model.occ[:,start:start+16];kind=model.paint[:,start:start+16];paint=np.zeros(kind.shape,np.uint8)
        for index,color in COLORS.items():paint[kind==index]=address(sprite,color)
        e=encode(o,paint,table['cells'][k],'hand-emerald-verdanturf-planter',trim=True)
        if n==57:e.pop('on')
        out[k]=e
    return out

def assemblies(art):
    rows=rows_for('mauville');g=art.map_cells(art.maps['EM_VERDANTURF_TOWN']);long=[[rows[56]['key']],[rows[60]['key']]];assert [r[:1] for r in g[15:17]]==long;short=[[rows[55]['key']]*3];assert g[18][15:18]==short[0]
    return [{'id':'emerald_verdanturf_long_flower_planter','kind':'object (model as one)','note':'Exact north/south two-cell planter at the west map edge. Source is one long soil tray with its wooden front at the southern cell; the obsolete vertical on relation is removed.','layout':long,'occurrences':[{'map':'EM_VERDANTURF_TOWN','x':0,'y':15,'palette':'BAKED'}]}, {'id':'emerald_verdanturf_three_flower_boxes','kind':'object (model as one)','note':'Three separate short wooden flower boxes across the southern garden boundary, each retaining its own wooden side ends.','layout':short,'occurrences':[{'map':'EM_VERDANTURF_TOWN','x':15,'y':18,'palette':'BAKED'}]}]

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('mauville');updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n in sorted(OWNED):
        r=rows[n-1];k=r['key'];im=art.cell(k,'BAKED');dest=OUT/k[4:];dest.mkdir(exist_ok=True);im.save(dest/'source.png')
        if args.previews:render(table['cells'][k],im,dest/'before');render(updates[k],im,dest/'after')
        reason='Author a grounded low wooden flower box with distinct rim and soil recess, narrow green stems, overlapping leaf clusters and separate coral blossoms. Source grass and shadow are excluded from its surfaces. '
        if n==56:reason+='The three south-edge source boxes are separate complete one-cell planters, not tall hourglass pedestals.'
        else:reason+='Author the complete16×32 tray before slicing into adjacent northern and southern source cells. Its sole map occurrence is a two-cell-deep planter, not a vertical stack; remove only the northern cell obsolete on relation. The wooden front remains on the southern end and both halves share a continuous soil/side rim interface.'
        ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'verdanturf-flower-planters','decision':'changed','baseline_entry_sha256':digest(table['cells'][k]),'reason':reason,'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(im.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'source_context':'local/model-gallery/emerald-pass-study/verdanturf-planter-source.png','exact_layout_evidence':(OUT/'entities.json').relative_to(ROOT).as_posix()}],'visual_status':'source/context inspected; candidate/runtime pending'})
    entities=assemblies(art);live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';current=json.loads(live.read_text())
    if args.previews:
        for e in entities:
            target=OUT/'assemblies'/e['id'];target.mkdir(parents=True,exist_ok=True);im=art.drawing(e['layout'],'BAKED');im.resize((im.width*8,im.height*8),Image.Resampling.NEAREST).save(target/'source.png');render_assembly(e,art,table['cells'],target,'before');render_assembly(e,art,{**current['cells'],**updates},target,'after')
    key=rows[56]['key'];geometry={'v','t','up','from'};correction={'key':key,'before':{k:v for k,v in table['cells'][key].items() if k not in geometry},'after':{k:v for k,v in updates[key].items() if k not in geometry},'source_pixel_sha256':hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB','witnesses':witnesses(art,rows[56]),'reason':'Remove false vertical on link: exact single source assembly at Verdanturf(0,15)1×2 has one continuous soil tray with only its southern cell bearing the wooden front. Both source cells stay SOLID7/elevation0 with no new placement flags.'}
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('metadata-corrections.json',[correction]),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'metadata_change_keys':[key],'runtime_verified':False})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'changed':3,'whole_objects':2,'metadata_corrections':1}))

if __name__=='__main__':main()
