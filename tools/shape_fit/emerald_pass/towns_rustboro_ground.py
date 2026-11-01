"""Route104 docks and Faraway wharf: source orientation, native floor routing."""
from __future__ import annotations
import argparse,hashlib,json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model,decode,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses,preview
from tools.shape_fit.emerald_pass.towns_fortree_ground import fitted_preview
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-rustboro-ground'
WARM={22,23,67,69,74,81};WATER={27,68,88};COOL={80,82,83,87,89,90,91,92,97,98}
CHANGED=WARM|WATER|COOL;RETAINED={35,94,95,96,99,100};OWNED=CHANGED|RETAINED
LABELS={22:'Briney dock south plank pair',23:'Briney dock north plank pair',27:'Briney dock water-shadow and pile projection',35:'Faraway wharf inset northwest curb corner',67:'Briney dock north bank connection',68:'Route104 north jetty shallow water shadow',69:'Briney dock south bank connection',74:'Briney dock northwest half-plank end',80:'Route104 jetty northern east-west deck',81:'Briney dock southwest half-plank end',82:'Route104 jetty southwest elbow',83:'Route104 jetty southeast elbow',87:'Route104 jetty southern east-west deck',88:'Route104 jetty pile and water projection',89:'Route104 jetty west north-south deck',90:'Route104 jetty east north-south deck',91:'Route104 jetty west bank connection',92:'Route104 jetty east bank connection',94:'Faraway wharf west stepped front curb',95:'Faraway wharf middle stepped front curb',96:'Faraway wharf east stepped front curb',97:'Faraway landing west timber opening',98:'Faraway landing east timber opening',99:'Faraway wharf west projected foot',100:'Faraway wharf east projected foot'}

def address(sprite,color):
    hits=np.argwhere((np.asarray(sprite.convert('RGB'))==color).all(axis=2));assert len(hits),color
    return int(hits[0,0]*16+hits[0,1])

def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('rustboro');result={}
    for n in sorted(CHANGED):
        k=rows[n-1]['key'];sprite=art.cell(k,'BAKED');o,p=decode(table['cells'][k]);m=Model(height=len(o));m.occ[:]=o
        color=(214,181,107) if n in WARM else (189,173,107) if n in COOL else (107,132,214) if n==27 else (74,115,173)
        m.paint[:]=address(sprite,color);y,z,x=np.indices(o.shape);above=np.zeros_like(o);above[:-1]=o[1:];top=o&~above;m.paint[top]=(z*16+x)[top].astype(np.uint8)
        result[k]=m.entry(table['cells'][k],'hand-emerald-rustboro-dock-ground',trim=True)
    return result

def decisions():
    result={}
    for n,label in LABELS.items():
        if n in WATER:reason='Keep the existing shallow geometry and native WATER41 routing. Project the exact blue water/shadow/pile-tip drawing across its horizontal fallback top instead of stretching it into vertical bands. The source pile tip is a projection beneath the deck; no new water-block obstruction, pedestal or stack relation is added.'
        elif n in CHANGED:reason='Keep the existing low deck footprint and height, including source half-width ends where present. Place the exact native plank, seam and nail drawing across x/z on each upward face; use only this source timber for vertical board edges. Actual WALK0 occurrences retain native floor routing; the two SOLID half-width end cells keep their current shape and source collision. No walk/decor flags or new supports are invented.'
        elif n==35:reason='Retain the shallow quarter-square white curb at the inset corner of the Faraway landing. Source context and front/east/top views show the correct small corner footprint adjoining its northern and western coping, with the surrounding walkable plaza outside the model.'
        elif n in (94,95,96):reason='Retain the existing two-level low wharf edge: raised pale coping above a lower dark/olive front fascia. Complete Faraway platform context confirms this is its shallow front edge, not a freestanding railing or tall wall. Neighboring middle/corner segments share the same height and clean native curb materials.'
        else:reason='Retain the small four-high projected wharf foot at its exact outer corner. Its seven-by-seven native pale support footprint stays connected to the platform edge to the north, excludes blue water, and matches the opposite corner without creating an unsupported tall pier.'
        result[n]=('changed' if n in CHANGED else 'retained',label,reason)
    return result

def assemblies(art):
    result=[]
    for name,mid,x,y,w,d,note in [('briney_dock','EM_ROUTE104',11,51,4,3,'Complete Briney dock: half-width west ends, two central plank pairs, east bank connections and the southern pile/water source projections.'),('north_jetty','EM_ROUTE104',24,9,8,13,'Complete bent north Route104 jetty: all38 connected timber source cells between the north bank and ferry approach, both elbows and the water-shadow row; source WALK routing stays native.'),('faraway_wharf','EM_FARAWAY_ISLAND_ENTRANCE',10,35,8,6,'Faraway landing platform with its inset northwest coping, stepped front edge, timber boarding gap and two projected corner feet; surrounding native cliff retained.')]:
        g=art.map_cells(art.maps[mid]);result.append({'id':'emerald_rustboro_ground_'+name,'kind':'object (model as one)','note':note,'layout':[r[x:x+w] for r in g[y:y+d]],'occurrences':[{'map':mid,'x':x,'y':y,'palette':'BAKED'}]})
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('rustboro');updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n,(decision,label,reason) in decisions().items():
        r=rows[n-1];k=r['key'];source=art.cell(k,'BAKED');target=OUT/k[4:];target.mkdir(exist_ok=True);source.save(target/'source.png')
        if args.previews:render(table['cells'][k],source,target/'before');render(updates.get(k,table['cells'][k]),source,target/'after')
        ledger.append({'key':k,'kind':'cell','label':label,'family':'rustboro-route104-native-docks-wharf','decision':decision,'reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(source.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__rustboro/contexts/page-{(n-1)//16+1:03}.png'}],'visual_status':'source and original front/east/top reviewed; candidate Python/runtime pending'})
    live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';current=json.loads(live.read_text());entities=assemblies(art)
    if args.previews:
        for e in entities:
            target=OUT/'assemblies'/e['id'];target.mkdir(parents=True,exist_ok=True);layout=e['layout'];art.drawing(layout,'BAKED').resize((len(layout[0])*48,len(layout)*48),Image.Resampling.NEAREST).save(target/'source.png');fitted_preview(e,art,table['cells'],target,'before');fitted_preview(e,art,{**current['cells'],**updates},target,'after');preview(e,art,{**current['cells'],**updates},target,'runtime-floor')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'elevation_simulated':False,'metadata_changed':False,'geometry_changed':False,'native_walk_water_floor_preserved':True})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'reviewed':25,'changed':19,'retained':6,'assemblies':3}))

if __name__=='__main__':main()
