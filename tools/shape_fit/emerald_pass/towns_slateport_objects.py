"""Ten source fragments represented by their exact complete boat/lighthouse."""
import hashlib,json
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass import buildings_slateport_objects as whole
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-slateport-objects'
BOAT={7,8,9,11,12,13};LIGHTHOUSE={22,23,25,26};OWNED=BOAT|LIGHTHOUSE
LABELS={7:'display boat west covered bow',8:'display boat north interior and seat',9:'display boat east stern',11:'display boat west near hull',12:'display boat near gunwale and seat',13:'display boat east hull and projecting oar',22:'lighthouse west shaft and gallery',23:'lighthouse east shaft and gallery',25:'lighthouse southwest round foot',26:'lighthouse southeast round foot'}

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
        reason='The six individual source slices are parts of one dry display boat, not separate deep walls. Its complete source template supplies a connected flaring hull, open gray interior, pale gunwale, covered bow, cross seats and projecting near-side oar on the exact six SOLID cells.' if n in BOAT else 'The four original lower slices contain projected curved shaft/base features that cannot form a coherent tower as separate floating quarters. The complete exact 2×4 lighthouse template supplies one grounded circular foot, narrow shaft, supported lantern gallery, dark panes and white conical roof entirely over the four lower SOLID cells; four upper WALK source projection cells stay physically empty.'
        ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'slateport-complete-source-objects-covered-fragments','decision':'retained','reason':reason+' Keep the original fallback entry and metadata byte-identical because every occurrence is covered by this whole. This is source/whole coverage, not approval of the isolated original extrusion; actual native fixture and Minecraft comparison are required.','baseline_entry_sha256':digest(baseline['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(im.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__slateport/contexts/page-{(n-1)//16+1:03}.png','exact_complete_object_coverage':proof[k]}],'visual_status':'source/original front/east/top and actual context inspected; whole Python views inspected, native runtime/Minecraft pending'})
    for name,value in [('updates.json',{'cells':{},'buildings':{}}),('ledger.json',ledger),('coverage-proof.json',proof),('provenance.json',{'baseline_table_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'candidate_updates_sha256':hashlib.sha256((whole.OUT/'updates.json').read_bytes()).hexdigest()})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'retained':len(ledger),'exact_source_occurrences':sum(len(v['occurrences']) for v in proof.values())}))

if __name__=='__main__':main()
