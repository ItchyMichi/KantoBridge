"""Three blue-canopy post keys, covered by all six exact complete stalls."""
import hashlib,json
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass import buildings_slateport_market as whole
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-slateport-canopies'
OWNED={17,31,32};LABELS={17:'western blue market canopy west timber post',31:'eastern blue market canopy west timber post',32:'blue market canopy east timber post with paving border'}

def coverage(art,table):
    specs={b['id']:b for b in table['buildings'] if b['id'].startswith(('emerald_b078_','emerald_b079_'))};specs.update(whole.generate(art=art));cat=building_catalog({'game':'emerald','cells':{},'buildings':list(specs.values())},art);rows=rows_for('slateport');index={}
    for e in cat:
        for loc in e['occurrences']:
            for z,r in enumerate(e['layout']):
                for x,k in enumerate(r):
                    if [x,z] not in specs[e['id']].get('terrain',[]):index.setdefault((loc['map'],loc['x']+x,loc['y']+z),[]).append({'id':e['id'],'local_cell':[x,z],'building_entry_sha256':digest(specs[e['id']])})
    out={}
    for n in sorted(OWNED):
        k=rows[n-1]['key'];places=[]
        for loc in art.locations[k]:
            matches=index.get((loc['map'],loc['x'],loc['y']),[]);assert len(matches)==1,(n,loc);places.append({'occurrence':loc,'buildings':matches})
        out[k]={'fully_covered':True,'occurrences':places}
    return out

def main():
    baseline=json.loads(BASELINE.read_text());live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';table=json.loads(live.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('slateport');proof=coverage(art,table);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n in sorted(OWNED):
        r=rows[n-1];k=r['key'];im=art.cell(k,'BAKED');dest=OUT/k[4:];dest.mkdir(exist_ok=True);im.save(dest/'source.png');ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'slateport-complete-blue-market-canopy-posts','decision':'retained','reason':'Preserve this narrow source timber support fallback and its metadata. All exact occurrences are covered by the two reviewed b078/b079 stalls plus four missing complete width/goods variants. The new wholes root every support in its lower SOLID post cell, with the WALK source roof projections and empty display spaces clear below26voxels. Native paving/grass beside the post is excluded from physical timber paint. This is explicit complete-stall coverage, not approval of an isolated extrusion.','baseline_entry_sha256':digest(baseline['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(im.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'exact_complete_object_coverage':proof[k],'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__slateport/contexts/page-{(n-1)//16+1:03}.png'}],'visual_status':'source/original front/east/top and actual whole source inspected; candidate native/Minecraft pending'})
    for name,value in [('updates.json',{'cells':{},'buildings':{}}),('ledger.json',ledger),('coverage-proof.json',proof),('provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'candidate_updates_sha256':hashlib.sha256((whole.OUT/'updates.json').read_bytes()).hexdigest()})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'retained':len(ledger),'source_occurrences':sum(len(v['occurrences']) for v in proof.values())}))

if __name__=='__main__':main()
