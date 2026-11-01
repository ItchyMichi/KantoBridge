"""Twelve Abandoned Ship body fragments covered by the exact Route108 whole."""
import hashlib,json
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass import building_abandoned_ship as whole
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-slateport-ship'
OWNED=set(range(96,108))
LABELS={96:'Abandoned Ship slatted west bow',97:'Abandoned Ship west main cabin roof corner',98:'Abandoned Ship slatted damaged main roof',99:'Abandoned Ship east main roof edge',100:'Abandoned Ship pale slatted annex roof',101:'Abandoned Ship slatted east stern',102:'Abandoned Ship southwest hull and waterline',103:'Abandoned Ship west cabin/hull join',104:'Abandoned Ship main front window row',105:'Abandoned Ship small annex front windows',106:'Abandoned Ship east annex/hull join',107:'Abandoned Ship southeast stern and waterline'}

def coverage(art,table):
    specs=whole.generate(art=art);entry=building_catalog({'game':'emerald','cells':{},'buildings':list(specs.values())},art)[0];loc=entry['occurrences'][0];rows=rows_for('slateport');index={}
    for z,r in enumerate(entry['layout']):
        for x,k in enumerate(r):
            if [x,z] not in whole.TERRAIN:index[(loc['map'],loc['x']+x,loc['y']+z)]={'id':entry['id'],'local_cell':[x,z],'building_entry_sha256':digest(specs[entry['id']])}
    out={}
    for n in sorted(OWNED):
        k=rows[n-1]['key'];places=[]
        for site in art.locations[k]:
            match=index.get((site['map'],site['x'],site['y']));assert match,(n,site);places.append({'occurrence':site,'buildings':[match]})
        out[k]={'fully_covered':True,'occurrences':places}
    return out

def main():
    baseline=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('slateport');proof=coverage(art,baseline);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n in sorted(OWNED):
        r=rows[n-1];k=r['key'];im=art.cell(k,'BAKED');dest=OUT/k[4:];dest.mkdir(exist_ok=True);im.save(dest/'source.png')
        role='The west bow has a rounded olive hull and exposed low native slatted deck.' if n==96 else 'The west main roof corner and raised pale rim belong to the continuous upper cabin.' if n==97 else 'The main roof has native narrow slats and a specific broken central patch, not an upright slab.' if n==98 else 'The upper cabin east rim borders the separate lower pale-slatted annex.' if n==99 else 'The lower annex has distinct north/south pale roof slats rather than the main roof horizontal pattern.' if n==100 else 'The east stern has a small exposed slatted deck inside a pale low edge.' if n==101 else 'The native green band is the actual weathered hull waterline; surrounding checker water stays excluded from the hull.' if n in (102,107) else 'The cabin and hull meet continuously above the real moss waterline.' if n in (103,106) else 'The main cabin front has a row of four dark panes above the actual boarding entrance.' if n==104 else 'The lower annex front has a distinct group of three small dark panes.'
        ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'route108-complete-ship-covered-fragments','decision':'retained','reason':role+' Preserve this original fallback and metadata byte-identically: every occurrence belongs to the exact new complete Route108 Abandoned Ship template. Sea cells and the unrelated northeast rock are excluded; the true deck warp remains clear. This records whole coverage, not approval of isolated sliced geometry; exact native interfaces and Minecraft views remain pending.','baseline_entry_sha256':digest(baseline['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(im.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__slateport/contexts/page-{(n-1)//16+1:03}.png','exact_complete_object_coverage':proof[k]}],'visual_status':'source/original front/east/top and exact whole context inspected; whole Python and runtime review pending'})
    for name,value in [('updates.json',{'cells':{},'buildings':{}}),('ledger.json',ledger),('coverage-proof.json',proof),('provenance.json',{'baseline_table_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'candidate_updates_sha256':hashlib.sha256((whole.OUT/'updates.json').read_bytes()).hexdigest()})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'retained':len(ledger),'exact_source_occurrences':sum(len(v['occurrences']) for v in proof.values())}))

if __name__=='__main__':main()
