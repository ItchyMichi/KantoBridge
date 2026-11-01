"""Trainer Hill's 33 source cells, preserving the native cliff around its hall."""
import hashlib,json
from tools.model_gallery import EmeraldArt
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass import building_trainer_hill as building
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-mauville-trainer-hill'
LABELS={181:'northwest roof rim over cliff',182:'northeast roof rim over cliff',183:'cliff above northern roof tip',184:'west roof emblem panel and white rim',185:'east roof emblem panel and white rim',189:'lower entrance teal emblem and crown',190:'southwest outer white roof rim',193:'cliff beside upper west portal curve',194:'northwest diagonal roof border',195:'northern crystal and roof peak',196:'cliff beside west portal jamb',197:'west crystal facet and roof emblem',198:'central teal crystal face',199:'cliff beside upper east portal curve',200:'southwest diagonal roof border',201:'southern crystal point and peach inlay',202:'cliff beside east portal jamb',203:'west upper faceted glazed wall',204:'central glazed wall below roof point',205:'east glazed wall and blue pilaster',209:'western cliff ledge beneath hall',210:'eastern cliff ledge beneath hall',214:'northeast diagonal roof border',215:'west pale pilaster and upper blue glazing',219:'east crystal facet and roof emblem',220:'east pale pilaster and upper blue glazing',224:'southeast diagonal roof border',225:'west inner cliff ledge beneath hall',226:'central cliff ledge above lower portal',227:'east inner cliff ledge beneath hall',228:'west lower blue glazing and teal pilaster',229:'east lower blue glazing and teal pilaster',230:'west lower faceted glazed wall'}
OWNED=set(LABELS)
NATIVE={183,193,196,199,202,209,210,225,226,227}

def coverage(art):
    layout=building.layout_for(art);where={k:[x,z] for z,row in enumerate(layout) for x,k in enumerate(row)};audit=json.loads(building.PROOF.read_text())['occurrences'][0];columns={(c['x'],c['y']):c for c in audit['source_column_audit']};proof={}
    for n in sorted(OWNED):
        k=rows_for('mauville')[n-1]['key'];x,z=where[k];places=art.locations[k];assert places==[{'map':'EM_ROUTE111','x':29+x,'y':105+z,'palette':'BAKED'}]
        terrain=[x,z] in building.TERRAIN;assert terrain==(n in NATIVE)
        c=columns[(29+x,105+z)];assert all('cell:' not in q['before_keys'] for q in c['columns'])
        proof[k]={'local_cell':[x,z],'occurrence':places[0],'template':building.IDENT,'terrain_excluded':terrain,'native_kind':c['kind'],'native_quarter_heights':[q['before_blocks'] for q in c['columns']],'native_profile_proof':building.PROOF.relative_to(ROOT).as_posix(),'native_profile_sha256':building.PROOF_SHA256}
    return proof

def main():
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('mauville');proof=coverage(art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n in sorted(OWNED):
        r=rows[n-1];k=r['key'];source=art.cell(k,'BAKED');target=OUT/k[4:];target.mkdir(exist_ok=True);source.save(target/'source.png')
        if n in NATIVE:
            reason='Retain this source-local cliff fallback and exclude its source cell from the new whole template. The exact exported native fixture uses CUBE/ROOF cliff columns here rather than this isolated fitted shape. '
            reason+=('The tiny white/teal roof-tip or portal edge is a projection over the cliff, so it does not justify replacing the surrounding cliff with a freestanding facade.' if n in (183,193,196,199,202) else 'This is the continuous cliff platform in front of the upper hall and above the lower entrance; retaining its native profile avoids flattening or removing that ledge.')
        else:
            reason='Preserve the isolated original facade fallback while reconstructing its exact sole occurrence in the source-complete Trainer Hill template. '
            reason+=('The lower teal portal has a distinct recessed entry beneath the cliff, separate from the upper hall.' if n==189 else 'The white pointed roof, crystal facets, emblem wing, glazed bay or blue pilaster is explicitly modeled as part of the upper hall, rather than raised independently per source cell.')
        reason+=' Source collision/elevation and warp metadata are unchanged. This decision records native terrain/whole-building routing, not visual approval of the original isolated extrusion; exact candidate runtime matching and Minecraft comparison remain required.'
        ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'mauville-trainer-hill-whole-hall-and-native-cliff','decision':'retained','reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(source.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__mauville/contexts/page-{(n-1)//16+1:03}.png','exact_source_role':proof[k]}],'visual_status':'source/original front/east/top/context inspected; candidate runtime and Minecraft pending'})
    for name,value in [('updates.json',{'cells':{},'buildings':{}}),('ledger.json',ledger),('coverage-proof.json',proof)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'retained':33,'whole_claimed':23,'native_cliff':10,'source_occurrences':33}))

if __name__=='__main__':main()
