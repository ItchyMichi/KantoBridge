"""Distinct pink-roof facades with complete exact template coverage."""
import hashlib,json
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass import buildings_mauville_houses,buildings_mauville_gates
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-mauville-house-facades'
LABELS={8:'yellow house arched window crown',9:'Trick House upper gray timber center',10:'Trick House upper gray timber west pilaster',11:'Trick House upper gray timber east pilaster',12:'yellow house arched window sill',18:'pink pitched roof west ridge edge',19:'pink pitched roof center ridge',20:'pink pitched roof east ridge edge',24:'pink paneled house entrance',26:'pink pitched roof west slope border',27:'pink pitched roof repeated slope',28:'pink pitched roof east slope border',32:'yellow house west eave and corner',33:'Route118 plain brick upper facade',34:'yellow house east eave and corner',40:'yellow house southwest base and pilaster',41:'yellow house repeated brick base',42:'yellow house southeast base and pilaster',45:'gray timber facade west eave and gold pilaster',46:'gray timber facade east eave and gold pilaster',47:'gray house blue transom upper strip',48:'pink door gray lintel under pitched eave',51:'gray timber facade southwest base',52:'gray timber facade southeast base',53:'gray timber lower blue transom and wall'}
OWNED=set(LABELS)

def coverage(art,table):
    added={**buildings_mauville_houses.generate(table,art),**buildings_mauville_gates.generate(table,art)};merged={**table,'buildings':table['buildings']+list(added.values())};catalog=building_catalog(merged,art);specs={b['id']:b for b in merged['buildings']};proof={};rows=rows_for('mauville')
    for n in sorted(OWNED):
        k=rows[n-1]['key'];locs=[]
        for loc in art.locations[k]:
            matches=[]
            for b in catalog:
                for pos in b['occurrences']:
                    x,z=loc['x']-pos['x'],loc['y']-pos['y']
                    if loc['map']==pos['map'] and 0<=z<len(b['layout']) and 0<=x<len(b['layout'][z]) and b['layout'][z][x]==k and [x,z] not in specs[b['id']].get('terrain',[]):matches.append({'id':b['id'],'local_cell':[x,z]})
            assert matches,(n,k,loc)
            locs.append({'occurrence':loc,'buildings':matches})
        proof[k]={'fully_covered':True,'occurrences':locs}
    return proof

def main():
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('mauville');proof=coverage(art,table);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n in sorted(OWNED):
        r=rows[n-1];k=r['key'];source=art.cell(k,'BAKED');target=OUT/k[4:];target.mkdir(exist_ok=True);source.save(target/'source.png')
        reason='Preserve this exact isolated architectural fallback together with the reviewed whole buildings. '
        if n in (9,10,11):reason+='The source-specific raised gray wall sits below the Trick House central pink roof, explicitly reconstructed as a supported upper mass. '
        elif n==33:reason+='The sole occurrence is the plain yellow wall beside Route118 house window, with no source door or warp; the new whole preserves that doorless identity. '
        elif n==24:reason+='All nine pink door occurrences have exact building coverage: three existing Mauville houses, five gates/Winstrate entrances and the new Trick House entrance. '
        elif n in (45,46,47,51,52,53):reason+='The gray timber/gold pilaster or blue transom is part of the reviewed gray Mauville house or the new Trick House, with separate wall panels and recessed entrance. '
        else:reason+='Every exported occurrence is claimed by the reviewed Mauville houses/annex or the paired source-exact gate, Winstrate, Trick House and Route118 candidates. '
        reason+='Exact source coverage excludes terrain-release cells. This records the complete-building replacement, not visual approval of the isolated original extrusion. New whole buildings still require native routing proof and Minecraft comparison.'
        ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'mauville-pink-roof-whole-building-covered-fallback','decision':'retained','reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(source.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__mauville/contexts/page-{(n-1)//16+1:03}.png','exact_complete_object_coverage':proof[k]}],'visual_status':'source, original front/east/top and actual context inspected; paired whole candidates require Minecraft comparison'})
    for name,value in [('updates.json',{'cells':{},'buildings':{}}),('ledger.json',ledger),('coverage-proof.json',proof)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'retained':len(ledger),'source_occurrences':sum(len(p['occurrences']) for p in proof.values()),'all_source_occurrences_covered':True}))

if __name__=='__main__':main()
