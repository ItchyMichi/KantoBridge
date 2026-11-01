"""Mirage Tower on its native lower footprint; upper art remains WALK projection."""
from __future__ import annotations
import argparse,base64,hashlib,json
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.blue_pass.common import Model,render
from tools.shape_fit.emerald import building_trials as p,parts_model
from tools.shape_fit.emerald_pass.buildings_west import box,material,entry_recess
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
OUT=ROOT/'local/model-gallery/emerald-pass-study/building-mirage-tower'
ID='emerald_object_route111_mirage_tower'
SOURCE='78401913ee38117c94ee9b72141a8639ee9058f18411160f97677f76dc219b0c'
OWNED={206,207,208,211,212,213,216,217,218,221,222,223}

def layout_for(art):
    g=art.map_cells(art.maps['EM_ROUTE111']);return [r[18:21] for r in g[53:59]]

def structure():
    lower=p.paint([3,48,45,78],[10,48,38,52],[6,50,42,77],[6,50,42,77]);upper=p.paint([5,17,43,44],[9,3,39,18],[10,20,38,44],[10,20,38,44]);stone=material((10,75,12,77))
    lower['wrap']=False;upper['wrap']=False
    parts=[
      p.round_part('cylinder',[24,80],[23,15],[0,51],lower),
      p.round_part('cylinder',[24,80],[24,16],[0,4],stone),
      p.round_part('cylinder',[24,80],[24,16],[49,55],p.paint([3,43,45,49],[4,43,44,48],[4,44,8,49])),
      p.round_part('cylinder',[24,80],[19,12],[55,82],upper),
      p.round_part('cylinder',[24,80],[18,11],[81,85],p.paint([18,4,19,5],[9,2,39,18],[18,4,19,5])),
    ]
    # A rounded worn drum sits on the wider lower body. Native cracks remain
    # material detail; the source sand surrounding the tower is never a base.
    entry_recess(parts,(17,31),96,87,24,(17,78,31,96))
    parts.append(box((16,32),(92,95),(24,28),material((17,70,31,73))))
    return {'height':88,'parts':parts}

def generate(table=None,art=None):
    art=art or EmeraldArt(BUNDLE);layout=layout_for(art);assert hashlib.sha256(art.drawing(layout,'BAKED').convert('RGB').tobytes()).hexdigest()==SOURCE
    return {ID:{'id':ID,'atlas':'g3q','tiles':quarters(layout),'terrain':[[x,z] for z in range(4) for x in range(3)],**structure()}}

def generate_cells(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('mauville');out={}
    for n in sorted(OWNED):
        k=rows[n-1]['key'];m=Model();m.occ[0]=True;z,x=np.indices((16,16));m.paint[0]=(z*16+x).astype(np.uint8);out[k]=m.entry(table['cells'][k],'hand-emerald-mirage-native-projection',trim=True)
    return out

def evidence(art,entry):
    places=building_catalog({'game':'emerald','cells':{},'buildings':[entry]},art)[0]['occurrences'];assert places==[{'map':'EM_ROUTE111','x':18,'y':53,'palette':'BAKED'}];m=art.maps['EM_ROUTE111'];raw=base64.b64decode(m['cells']);cells=[]
    for dz,row in enumerate(layout_for(art)):
        for dx,k in enumerate(row):
            x,y=18+dx,53+dz;i=(y*m['w']+x)*4;cells.append({'key':k,'x':x,'y':y,'local':[dx,dz],'metatile':int.from_bytes(raw[i:i+2],'little'),'collision':raw[i+2],'elevation':raw[i+3],'terrain_excluded':dz<4})
    warps=[q for q in m['warps'] if 18<=q['x']<21 and 53<=q['y']<59];assert len(warps)==1 and warps[0]['to']=='EM_MIRAGE_TOWER_1F'
    return [{**places[0],'width':3,'depth':6,'cells':cells,'warps':warps}]

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();art=EmeraldArt(BUNDLE);table=json.loads(BASELINE.read_text());entries=generate(table,art);e=entries[ID];cells=generate_cells(table,art);OUT.mkdir(parents=True,exist_ok=True);target=OUT/ID;target.mkdir(exist_ok=True);source=art.drawing(layout_for(art),'BAKED');source.save(target/'source.png');source.resize((288,576),Image.Resampling.NEAREST).save(target/'source-enlarged.png');crop=art.map_image('EM_ROUTE111').crop((16*16,51*16,23*16,61*16));ImageDraw.Draw(crop).rectangle((32,32,79,127),outline='red');crop.resize((448,640),Image.Resampling.NEAREST).save(target/'context.png')
    if args.previews:
        o,c=parts_model.build(e,np.asarray(source.convert('RGB')));parts_model.picture(o,c,target/'view.png',5);parts_model.front_view(o,c,target/'front.png',6);parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(c,(0,2,1,3))[:,:,::-1],target/'side.png',6)
    added=[{'key':ID,'kind':'building','label':'Mirage Tower sandstone drum and entrance','family':'mirage-tower','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(e),'source_pixel_sha256':SOURCE,'source_pixel_mode':'RGB','occurrences':evidence(art,e),'terrain_exclusions':e['terrain'],'reason':'Reconstruct the complete source-specific sandstone tower: wider rounded lower masonry body, worn projecting collar, smaller upper drum, flat pale cap and recessed entrance. Physical volume is confined to the lower two source rows; all twelve northern WALK0/elevation3 projections remain terrain-excluded. The exact one warp and all source quarters remain unchanged.','evidence':[{'source':(target/'source.png').relative_to(ROOT).as_posix(),'context':(target/'context.png').relative_to(ROOT).as_posix(),'python_views':[(target/f'{n}.png').relative_to(ROOT).as_posix() for n in ('front','side','view')]}],'visual_status':'source reviewed; Python/runtime pending','user_approved':False}]
    rows=rows_for('mauville');ledger=[]
    for n in sorted(OWNED):
        r=rows[n-1];k=r['key'];im=art.cell(k,'BAKED');dest=OUT/k[4:];dest.mkdir(exist_ok=True);im.save(dest/'source.png')
        if args.previews:render(table['cells'][k],im,dest/'before');render(cells[k],im,dest/'after')
        dz=[z for z,row in enumerate(layout_for(art)) if k in row][0];dx=layout_for(art)[dz].index(k)
        assert len(art.locations[k])==1 and art.locations[k][0]['x']==18+dx and art.locations[k][0]['y']==53+dz
        assert witnesses(art,r)[0]['collision']==0
        ledger.append({'key':k,'kind':'cell','label':f'Mirage Tower upper source projection row{dz+1} column{dx+1}','family':'mirage-tower-walk-projections','decision':'changed','baseline_entry_sha256':digest(table['cells'][k]),'reason':'The sole source occurrence is an upper tower projection on WALK0/elevation3, above the lower two-row physical tower footprint. Replace the isolated raised fragment with one horizontal source-painted voxel layer. Native map floor routing and all placement metadata stay unchanged; the paired source-exact tower reconstructs the actual height on the lower footprint. No walk decoration, stack alias or terrain collision is introduced.','evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(im.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'whole_building':ID,'local_cell':[dx,dz],'terrain_excluded':True,'context':(target/'context.png').relative_to(ROOT).as_posix()}],'visual_status':'source/original geometry inspected; candidate/runtime pending'})
    for name,value in [('updates.json',{'cells':cells,'buildings':entries}),('added-ledger.json',added),('ledger.json',ledger)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'added_buildings':1,'changed_projections':12}))

if __name__=='__main__':main()
