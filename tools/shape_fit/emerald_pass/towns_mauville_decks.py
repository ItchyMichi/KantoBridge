"""Source-specific cycling deck floors; preserve native bridge-floor routing."""
import argparse,hashlib,json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model,decode,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-mauville-decks'
LABELS={63:'northwest scuffed vertical deck panel',64:'northeast scuffed vertical deck panel',66:'northeast scuffed horizontal deck panel',67:'plain horizontal cycling deck',68:'southwest scuffed vertical deck panel',69:'southeast scuffed vertical deck panel',70:'southwest scuffed horizontal deck panel',72:'west bordered vertical cycling deck',73:'east bordered vertical cycling deck',75:'blue transverse deck inset',81:'northwest diagonal corner deck panel',82:'north bordered vertical cycling deck',83:'northeast diagonal corner deck panel',85:'north bordered horizontal cycling deck',88:'southwest blue scuff deck panel',89:'plain vertical cycling deck',90:'southeast blue scuff deck panel'}
OWNED=set(LABELS);CHANGED={72,73,89}
BRIDGE_PROOF=ROOT/'local/model-gallery/emerald-pass-study/towns-mauville/bridge-runtime-proof.json'

def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());rows=rows_for('mauville');out={}
    for n in sorted(CHANGED):
        k=rows[n-1]['key'];o,p=decode(table['cells'][k]);assert np.array_equal(np.where(o.any(axis=(1,2)))[0],[13,14]);m=Model();m.occ[:2]=o[13:15];m.paint[:2]=p[13:15];out[k]=m.entry(table['cells'][k],'hand-emerald-mauville-grounded-deck',trim=True)
    return out

def assemblies(art):
    proof=json.loads(BRIDGE_PROOF.read_text());out=[]
    for row,name,note in zip(proof['occurrences'],('north_junction','water_span','central_bend'),('Northern Cycling Road junction and the full bend into the north/south road, with native solid outer edges and the cropped adjoining gate context.','Representative complete width of the straight water crossing, including both native edges and their variable native support columns.','Central Cycling Road bend, including the complete corner deck and native edge/support transitions.')):
        layout=row['layout'];x,y=row['x'],row['y'];g=art.map_cells(art.maps[row['map']]);assert layout==[r[x:x+len(layout[0])] for r in g[y:y+len(layout)]]
        out.append({'id':'emerald_mauville_native_'+name,'kind':'object (model as one)','note':note+' Actual native original/current fragment descriptors are required before integration; an unbound fitted-grid preview is not representative of this bridge.','layout':layout,'occurrences':[{'map':row['map'],'x':x,'y':y,'palette':'BAKED'}]})
    return out

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('mauville');updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n in sorted(OWNED):
        r=rows[n-1];k=r['key'];im=art.cell(k,'BAKED');dest=OUT/k[4:];dest.mkdir(exist_ok=True);im.save(dest/'source.png');places=witnesses(art,r);assert all(p['collision']==0 for p in places)
        reason='Keep the exact native two-voxel slab and its source-pixel orientation, but ground the isolated fallback at y0..2 instead of leaving it suspended at y13..15. ' if n in CHANGED else 'Retain the existing complete one-voxel horizontal deck slab. Source, front/east/top and actual route context show the correct plank direction, native edge/scuff markings and a grounded shallow surface, with no missing corner or raised vertical extrusion. '
        reason+='Every exported occurrence is WALK0 without walk/on overrides. Actual RaisedRegions/G3Built proof routes these source deck cells through native floor tiles, with zero fitted blocks and decor; native bridge height/elevation is not encoded by this fallback. No collision or placement metadata changes.'
        if args.previews:render(table['cells'][k],im,dest/'before');render(updates.get(k,table['cells'][k]),im,dest/'after')
        ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'mauville-native-cycling-deck-floors','decision':'changed' if n in CHANGED else 'retained','reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':places,'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(im.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__mauville/contexts/page-{(n-1)//16+1:03}.png','native_bridge_diagnostic':BRIDGE_PROOF.relative_to(ROOT).as_posix(),'diagnostic_sha256':hashlib.sha256(BRIDGE_PROOF.read_bytes()).hexdigest()}],'visual_status':'source/original inspected; single candidate comparison and current native whole proof pending'})
    requests=assemblies(art)
    for e in requests:
        target=OUT/'assemblies'/e['id'];target.mkdir(parents=True,exist_ok=True);im=art.drawing(e['layout'],'BAKED');im.resize((im.width*3,im.height*3),Image.Resampling.NEAREST).save(target/'source.png')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('assembly-requests.json',requests)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'reviewed':17,'changed':3,'retained':14,'native_assembly_requests':3}))

if __name__=='__main__':main()
