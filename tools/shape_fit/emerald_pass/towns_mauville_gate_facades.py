"""Source-covered gate, Winstrate, annex and Game Corner facade fallbacks."""
import hashlib,json
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass import buildings_mauville_gates
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-mauville-gate-facades'
LABELS={7:'south cycling gate east roof border',13:'flat stone roof northwest corner',14:'flat stone roof northeast corner',16:'pink entrance lintel on WALK projection',21:'flat stone roof west border',22:'flat stone roof east border',29:'stone roof southwest edge and yellow wall',30:'stone roof southeast edge and yellow wall',35:'yellow facade west corner pilaster',36:'yellow facade east corner pilaster',43:'yellow facade southwest base',44:'yellow facade southeast base',49:'pink-framed arched blue window',50:'flat stone roof north coping',54:'flat roof recessed gray gravel',58:'flat roof south coping and brick wall',71:'Mauville Game Corner facade fragment'}
OWNED=set(LABELS)

def coverage(art,table):
    merged={**table,'buildings':table['buildings']+list(buildings_mauville_gates.generate(table,art).values())}
    catalog=building_catalog(merged,art);specs={b['id']:b for b in merged['buildings']};proof={};rows=rows_for('mauville')
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
        reason='Preserve this source-specific isolated facade as a covered fallback. '
        if n==71:reason+='Its sole exported occurrence is claimed by the reviewed complete Mauville Game Corner b020. '
        elif n==7:reason+='Its sole occurrence is the exact east edge of the source-specific south cycling gate, whose WATER roof projection is separately excluded. '
        elif n==16:reason+='All five WALK entrance projections belong to the two cycling gates or Winstrate house. The whole hull keeps these columns clear below24 voxels and carries only the upper lintel/roof there. '
        else:reason+='Every exported occurrence is claimed by the reviewed Mauville annex b024 or one of the three paired source-exact cycling-gate/Winstrate candidates, which supply continuous roof and wall geometry. '
        reason+='This is exact complete-building coverage, not visual approval of the old isolated extrusion. New whole candidates require native routing and Minecraft comparison before final readiness.'
        ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'mauville-flat-roof-whole-building-covered-fallback','decision':'retained','reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(source.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__mauville/contexts/page-{(n-1)//16+1:03}.png','exact_complete_object_coverage':proof[k]}],'visual_status':'source, original front/east/top and actual context inspected; paired whole candidates require Minecraft comparison'})
    for name,value in [('updates.json',{'cells':{},'buildings':{}}),('ledger.json',ledger),('coverage-proof.json',proof)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'retained':len(ledger),'source_occurrences':sum(len(p['occurrences']) for p in proof.values()),'all_source_occurrences_covered':True}))

if __name__=='__main__':main()
