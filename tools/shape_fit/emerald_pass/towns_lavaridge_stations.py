"""Source review of both complete cable-car station facade families."""
import hashlib,json
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass import building_route112_cable_station as lower
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-lavaridge-stations'
LABELS={10:'station orange roof north edge',11:'upper station northeast facade lintel',12:'upper station northwest roof over ground',13:'lower station orange west roof edge',14:'station repeated orange roof panel',15:'upper station east roof edge',16:'lower station orange roof east crease',17:'upper station narrow orange west wing',18:'upper station upper west side vent',19:'upper station western roof crease',20:'lower station southwest roof hem',21:'station broad roof lower hem',22:'upper station southeast roof hem',23:'lower station east roof corner hem',24:'lower station two east wing vents',25:'upper station southwest wing tip',26:'upper station lower west side vent',27:'upper station west roof bottom crease',28:'lower station northwest facade lintel',29:'station west transom and entrance lintel',30:'station east transom and entrance lintel',31:'lower station northeast facade lintel',32:'lower station right diagonal wing hem',33:'lower station narrow orange east wing',34:'upper station left diagonal wing hem',35:'upper station west pale facade panel',36:'lower station southwest pale facade panel',37:'station wide entrance west warp',38:'station wide entrance east warp',39:'lower station east door jamb and base',40:'lower station southeast pale wing panel',41:'lower station southeast wing tip',42:'upper station southwest pale wing panel',43:'upper station west door jamb and base',51:'upper station southeast pale facade panel'}
OWNED=set(LABELS)

def coverage(art,table):
    new=lower.generate(table,art)[lower.IDENT];upper=next(b for b in table['buildings'] if b['id']==lower.UPPER);specs={e['id']:e for e in (upper,new)};catalog=building_catalog({'game':'emerald','cells':{},'buildings':[upper,new]},art);proof={};rows=rows_for('lavaridge')
    for n in sorted(OWNED):
        k=rows[n-1]['key'];places=[]
        for loc in art.locations[k]:
            matches=[]
            for e in catalog:
                for p in e['occurrences']:
                    x,z=loc['x']-p['x'],loc['y']-p['y']
                    if loc['map']==p['map'] and 0<=z<len(e['layout']) and 0<=x<len(e['layout'][z]) and e['layout'][z][x]==k and [x,z] not in specs[e['id']].get('terrain',[]):matches.append({'id':e['id'],'local_cell':[x,z]})
            assert matches,(n,loc);places.append({'occurrence':loc,'buildings':matches})
        proof[k]={'fully_covered':True,'occurrences':places}
    return proof

def main():
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('lavaridge');proof=coverage(art,table);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n in sorted(OWNED):
        r=rows[n-1];k=r['key'];im=art.cell(k,'BAKED');dest=OUT/k[4:];dest.mkdir(exist_ok=True);im.save(dest/'source.png')
        reason='Keep the isolated source fallback while its complete architecture is represented by the reviewed Mt Chimney station or the exact mirrored Route112 lower station. '
        if n in (37,38):reason+='Both actual station entrances consist of two adjacent warp cells leading to their own station interiors; the whole models retain a wide open entrance and distinct native door metadata. '
        elif n in (18,24,26):reason+='The dark source marks are side ventilation slots in the orange station wing. The new lower model places both slots on its actual east side instead of treating the oblique source panel as an isolated wall. '
        elif n in (12,17,25,33,41):reason+='This is a narrow station wing or roof/ground seam within the complete asymmetric station footprint; surrounding volcanic ground does not become the station wall. '
        else:reason+='The orange curved roof, pale facade, transom or side-wing panel continues coherently across its neighboring source cells in the complete station. '
        reason+='Every exported occurrence is covered by one of the two exact complete station patterns, excluding terrain-release cells. Source metadata stays unchanged. This is whole-building coverage, not independent visual approval of the old isolated extrusion; the added lower station still requires actual native routing and Minecraft comparison.'
        ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'lavaridge-cable-station-whole-covered-facades','decision':'retained','reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(im.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__lavaridge/contexts/page-{(n-1)//16+1:03}.png','exact_complete_object_coverage':proof[k]}],'visual_status':'source/original front/east/top/actual map contexts inspected; exact lower whole runtime and Minecraft pending'})
    for name,value in [('updates.json',{'cells':{},'buildings':{}}),('ledger.json',ledger),('coverage-proof.json',proof)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'retained':len(ledger),'exact_source_occurrences':sum(len(p['occurrences']) for p in proof.values())}))

if __name__=='__main__':main()
