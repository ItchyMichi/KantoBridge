"""Twenty beach furniture source fragments covered by five exact wholes."""
import hashlib,json
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass import buildings_route109_beach as whole
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-slateport-beach'
OWNED=set(range(68,88))
LABELS={68:'rolled mat padded head edge',69:'folding chair upper striped back',70:'blue umbrella northwest canopy',71:'blue umbrella northeast canopy',72:'orange umbrella northwest canopy',73:'orange umbrella northeast canopy',74:'green umbrella northwest canopy',75:'green umbrella northeast canopy',76:'blue and green striped mat textile',77:'folding chair seat and metal braces',78:'blue umbrella southwest canopy',79:'blue umbrella southeast canopy',80:'orange umbrella southwest canopy',81:'orange umbrella southeast canopy',82:'green umbrella southwest canopy',83:'green umbrella southeast canopy',84:'rolled mat lower textile edge',85:'folding chair lower feet',86:'umbrella pole west half and sand shadow',87:'umbrella pole east half and sand shadow'}

def coverage(art,table):
    specs=whole.generate(art=art);cat=building_catalog({'game':'emerald','cells':{},'buildings':list(specs.values())},art);rows=rows_for('slateport');index={}
    for e in cat:
        for loc in e['occurrences']:
            for z,r in enumerate(e['layout']):
                for x,k in enumerate(r):index.setdefault((loc['map'],loc['x']+x,loc['y']+z),[]).append({'id':e['id'],'local_cell':[x,z],'building_entry_sha256':digest(specs[e['id']])})
    out={}
    for n in sorted(OWNED):
        k=rows[n-1]['key'];places=[]
        for loc in art.locations[k]:
            matches=index.get((loc['map'],loc['x'],loc['y']),[]);assert len(matches)==1,(n,loc);places.append({'occurrence':loc,'buildings':matches})
        out[k]={'fully_covered':True,'occurrences':places}
    return out

def main():
    baseline=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('slateport');proof=coverage(art,baseline);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n in sorted(OWNED):
        r=rows[n-1];k=r['key'];im=art.cell(k,'BAKED');dest=OUT/k[4:];dest.mkdir(exist_ok=True);im.save(dest/'source.png')
        role='The complete three-row striped mat provides a thin grounded textile and a low padded head roll; these separated source slices are not separate raised objects.' if n in (68,76,84) else 'The complete three-row folding chair provides one inclined striped back, low seat, narrow metal frame and grounded feet; source sand remains reference floor.' if n in (69,77,85) else 'The umbrella pole is a single narrow grounded stem; the large ochre shape is its native sand shadow and must stay flat floor art, not a raised brown plate.' if n in (86,87) else 'This colored/white radial canopy quarter belongs to one complete thin overhead umbrella with a central supported pole; its source sand is excluded from physical canopy paint.'
        ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'route109-complete-beach-furniture-covered-fragments','decision':'retained','reason':role+' Preserve the original fallback entry and metadata: every occurrence is covered by one of five exact complete source templates (three canopy colors, folding chair, rolled mat). All actual source cells are WALK0, so whole placement remains noncolliding template decoration. This is whole coverage, not approval of isolated sliced extrusions; actual source runtime and Minecraft comparison remain pending.','baseline_entry_sha256':digest(baseline['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(im.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__slateport/contexts/page-{(n-1)//16+1:03}.png','exact_complete_object_coverage':proof[k]}],'visual_status':'source/original front/east/top and source contexts inspected; whole Python/runtime pending'})
    for name,value in [('updates.json',{'cells':{},'buildings':{}}),('ledger.json',ledger),('coverage-proof.json',proof),('provenance.json',{'baseline_table_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'candidate_updates_sha256':hashlib.sha256((whole.OUT/'updates.json').read_bytes()).hexdigest()})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'retained':len(ledger),'exact_source_cell_occurrences':sum(len(v['occurrences']) for v in proof.values())}))

if __name__=='__main__':main()
