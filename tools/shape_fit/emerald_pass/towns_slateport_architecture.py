"""Thirty whole-covered Slateport/Seashore facade and canopy source decisions."""
import hashlib,json
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass import buildings_slateport_coast as whole
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-slateport-architecture'
HARBOR={29,30,36,37,38,39,40,41,42,43,44,45,46,47,48,49,54,55,56,57,65}
SEASHORE={59,60,61,63,64,66,67};OWNED=HARBOR|SEASHORE|{27,28}
LABELS={27:'orange canopy southwest support',28:'orange canopy southeast support',29:'Harbor western porthole half',30:'Harbor eastern porthole half',36:'Harbor northwest roof with forest projection',37:'Harbor north roof hem with forest projection',38:'Harbor northeast roof with forest projection',39:'Harbor west orange barrel roof edge',40:'Harbor repeated orange roof ribs',41:'Harbor east orange barrel roof edge',42:'Harbor southwest red roof hem',43:'Harbor repeated red lower roof hem',44:'Harbor southeast red roof hem',45:'Harbor west blue facade edge',46:'Harbor east blue facade edge',47:'Harbor lower west nautical frieze',48:'Harbor repeating nautical frieze',49:'Harbor lower east nautical frieze',54:'Harbor east porthole and entry corner',55:'Harbor central nautical entrance badge',56:'Harbor west porthole and entry corner',57:'Harbor west lower door jamb',59:'Seashore/Slateport west purple diamond roof',60:'Seashore/Slateport purple diamond roof middle',61:'Seashore/Slateport east purple diamond roof',63:'Seashore/Slateport yellow window facade',64:'Seashore central doorway lintel',65:'Harbor east lower door jamb',66:'Seashore west yellow pilaster',67:'Seashore east yellow pilaster'}

def coverage(art,table):
    candidate=whole.generate(art=art);all_specs=[*table['buildings'],*candidate.values()];specs={e['id']:e for e in all_specs};cat=building_catalog({'game':'emerald','cells':{},'buildings':all_specs},art);rows=rows_for('slateport');keys={rows[n-1]['key'] for n in OWNED};index={}
    for e in cat:
        for loc in e['occurrences']:
            for z,r in enumerate(e['layout']):
                for x,k in enumerate(r):
                    if k in keys and [x,z] not in specs[e['id']].get('terrain',[]):index.setdefault((loc['map'],loc['x']+x,loc['y']+z),[]).append({'id':e['id'],'local_cell':[x,z],'building_entry_sha256':digest(specs[e['id']])})
    out={}
    for n in sorted(OWNED):
        k=rows[n-1]['key'];places=[]
        for loc in art.locations[k]:
            matches=index.get((loc['map'],loc['x'],loc['y']),[]);assert matches,(n,loc);places.append({'occurrence':loc,'buildings':matches})
        out[k]={'fully_covered':True,'occurrences':places}
    return out

def main():
    baseline=json.loads(BASELINE.read_text());live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';table=json.loads(live.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('slateport');proof=coverage(art,table);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n in sorted(OWNED):
        r=rows[n-1];k=r['key'];im=art.cell(k,'BAKED');dest=OUT/k[4:];dest.mkdir(exist_ok=True);im.save(dest/'source.png')
        if n in HARBOR:reason='Keep this exact source fallback while the complete newly reconstructed Slateport Harbor supplies the orange rounded roof, blue cladding, circular portholes, low nautical frieze and central true warp. '+('The small green source pixels are background forest projected above the roof; they are excluded from physical roof paint.' if n in (36,37,38) else 'Its localized roof/facade feature is represented in the continuous whole rather than as a deep isolated slab.')
        elif n in SEASHORE:reason='Keep this exact source fallback under the reviewed four/six-cell Slateport houses and the new five-cell Route109 Seashore House. The unique source doorway/pilasters and actual warp belong to the new complete beach-house pattern; the north WALK roof projection has explicit low clearance.'
        else:reason='Keep this narrow support fallback: all exact source occurrences belong to the reviewed complete orange market canopies b080/b081, whose whole roofs and grounded posts supply the actual architecture.'
        ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'slateport-complete-architecture-covered-facades','decision':'retained','reason':reason+' Every exported occurrence has exact complete-template coverage outside terrain exclusions. Source identity and metadata stay unchanged; this is not isolated-extrusion visual approval. New whole source runtime and Minecraft comparison remain pending.','baseline_entry_sha256':digest(baseline['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(im.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__slateport/contexts/page-{(n-1)//16+1:03}.png','exact_complete_object_coverage':proof[k]}],'visual_status':'source/original front/east/top and actual context inspected; new whole runtime/Minecraft pending'})
    for name,value in [('updates.json',{'cells':{},'buildings':{}}),('ledger.json',ledger),('coverage-proof.json',proof),('provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'candidate_updates_sha256':hashlib.sha256((whole.OUT/'updates.json').read_bytes()).hexdigest()})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'retained':len(ledger),'exact_source_occurrences':sum(len(v['occurrences']) for v in proof.values())}))

if __name__=='__main__':main()
