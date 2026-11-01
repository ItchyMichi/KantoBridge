"""27 reviewed facade keys covered by existing or source-exact Route104 hulls."""
import hashlib,json
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass import buildings_route104
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-rustboro-architecture'
OWNED={5}|set(range(6,22))|set(range(24,27))|set(range(28,35))
LABELS={5:'Rustboro gray house framed blue window',6:'flower-shop central orange roof',7:'flower-shop east orange roof',8:'flower-shop southwest orange eave',9:'flower-shop central orange eave',10:'flower-shop southeast orange eave',11:'flower-shop west window and roof seam',12:'flower-shop striped awning left',13:'flower-shop striped awning over entrance',14:'flower-shop striped awning right',15:'flower-shop middle window and roof seam',16:'flower-shop east window and roof seam',17:'flower-shop west blue window and flower panel',18:'flower-shop west entrance jamb and flower panel',19:'flower-shop east entrance jamb and flower panel',20:'flower-shop middle blue window and flower panel',21:'flower-shop east blue window and flower panel',24:'Briney house west timber roof',25:'Briney house middle timber roof',26:'Briney house east timber roof',28:'Briney house west blue transom and eave',29:'Briney house middle blue transom and eave',30:'Briney house east blue transom and eave',31:'Briney house west corner post and slats',32:'Briney house west entrance slats',33:'Briney house east entrance slats',34:'Briney house east corner post and slats'}

def coverage(art,table):
    merged={**table,'buildings':table['buildings']+list(buildings_route104.generate(table,art).values())};catalog=building_catalog(merged,art);specs={b['id']:b for b in merged['buildings']};proof={};rows=rows_for('rustboro')
    for n in sorted(OWNED):
        k=rows[n-1]['key'];locs=[]
        for loc in art.locations[k]:
            matches=[]
            for b in catalog:
                for pos in b['occurrences']:
                    x,z=loc['x']-pos['x'],loc['y']-pos['y']
                    if loc['map']==pos['map'] and 0<=z<len(b['layout']) and 0<=x<len(b['layout'][z]) and b['layout'][z][x]==k and [x,z] not in specs[b['id']].get('terrain',[]):matches.append({'id':b['id'],'local_cell':[x,z]})
            assert matches,(n,k,loc);locs.append({'occurrence':loc,'buildings':matches})
        proof[k]={'fully_covered':True,'occurrences':locs}
    return proof

def main():
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('rustboro');proof=coverage(art,table);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n in sorted(OWNED):
        r=rows[n-1];k=r['key'];source=art.cell(k,'BAKED');target=OUT/k[4:];target.mkdir(exist_ok=True);source.save(target/'source.png')
        reason='Preserve this isolated facade as a covered fallback. All20 exported occurrences are claimed by reviewed complete Rustboro house templates b063/b070/b071 with their distinct stone walls, roofs, doors and separate windows.' if n==5 else 'Preserve this exact isolated facade as a covered fallback. Every exported occurrence is part of the proposed source-exact Pretty Petal flower shop or Briney house whole template, with continuous roofs, explicit entrance and native architectural details. The original112 templates omitted these complete exteriors; the paired added building candidate fills that source-proven gap.'
        reason+=' This records whole-building source coverage, not visual approval of the old isolated extrusion. It is released together with the complete candidate, whose Minecraft review remains pending.'
        ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'rustboro-route104-whole-building-covered-fallback','decision':'retained','reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(source.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__rustboro/contexts/page-{(n-1)//16+1:03}.png','exact_complete_object_coverage':proof[k]}],'visual_status':'source, original front/east/top and actual context inspected; paired whole candidates require Minecraft comparison'})
    for name,value in [('updates.json',{'cells':{},'buildings':{}}),('ledger.json',ledger),('coverage-proof.json',proof)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'retained':27,'all_source_occurrences_covered':True}))

if __name__=='__main__':main()
