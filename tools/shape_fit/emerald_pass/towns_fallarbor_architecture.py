"""All22 source facade fragments covered by exact complete house templates."""
from __future__ import annotations
import hashlib
import json
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass import building_glass_workshop

OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-fallarbor-architecture'
OWNED=set(range(43,54))|set(range(72,83))
LABELS={43:'orange house west ribbed roof',44:'orange house middle ribbed roof',45:'orange house east ribbed roof',46:'house west roof and plank-wall seam',47:'house door upper lintel',48:'house right window upper orange lintel',49:'house eastern roof and plank-wall seam',50:'house lower west plank wall',51:'house lower east plank wall',52:'green house recessed door face',53:'green house blue window lower face',72:'glass workshop olive west roof',73:'glass workshop olive middle roof',74:'glass workshop west eave and wall seam',75:'glass workshop central eave and door lintel',76:'glass workshop west lower wall',77:'glass workshop blue window upper lintel',78:'glass workshop olive east roof',79:'glass workshop east eave and wall seam',80:'orange house raised west roof crest',81:'orange house raised middle roof crest',82:'orange house raised east roof crest'}


def coverage(art,table):
    merged={**table,'buildings':table['buildings']+list(building_glass_workshop.generate(table,art).values())};catalog=building_catalog(merged,art);entries={b['id']:b for b in merged['buildings']};proof={};rows=rows_for('fallarbor')
    for n in sorted(OWNED):
        k=rows[n-1]['key'];locs=[]
        for loc in art.locations[k]:
            matches=[]
            for b in catalog:
                for pos in b['occurrences']:
                    x,z=loc['x']-pos['x'],loc['y']-pos['y']
                    if loc['map']==pos['map'] and 0<=z<len(b['layout']) and 0<=x<len(b['layout'][z]) and b['layout'][z][x]==k and [x,z] not in entries[b['id']].get('terrain',[]):matches.append({'id':b['id'],'local_cell':[x,z]})
            assert matches,(n,k,loc);locs.append({'occurrence':loc,'buildings':matches})
        proof[k]={'fully_covered':True,'occurrences':locs}
    return proof


def main():
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('fallarbor');proof=coverage(art,table);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n in sorted(OWNED):
        r=rows[n-1];k=r['key'];source=art.cell(k,'BAKED');target=OUT/k[4:];target.mkdir(exist_ok=True);source.save(target/'source.png')
        reason='Preserve this exact native facade fragment as a covered fallback. Every exported occurrence is claimed by the reviewed complete Fallarbor house templates or the source-exact new Route113 glass workshop, which supply continuous roofs, green plank walls, recessed doors and separate blue windows. This is source-proven whole-template coverage, not approval of the isolated old facade extrusion.'
        ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'fallarbor-whole-house-covered-fallback','decision':'retained','reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(source.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__fallarbor/contexts/page-{(n-1)//16+1:03}.png','exact_complete_object_coverage':proof[k]}],'visual_status':'source, old front/east/top and actual map inspected; covered whole building source reviewed; new workshop runtime pending'})
    for name,value in [('updates.json',{'cells':{},'buildings':{}}),('ledger.json',ledger),('coverage-proof.json',proof)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'retained':len(ledger),'all_source_occurrences_covered':True}))


if __name__=='__main__':main()
