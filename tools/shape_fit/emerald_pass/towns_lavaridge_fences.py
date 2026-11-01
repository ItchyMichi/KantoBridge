"""Volcanic-path gray fences, authored as four complete source runs."""
import argparse,hashlib,json
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass.towns_rustboro_props import address
from tools.shape_fit.emerald_pass.towns_rustboro_rails import render_assembly
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-lavaridge-fences'
LABELS={63:'Mt Chimney east overlook northwest fence turn',64:'upper station southeast fence turn',65:'upper station northwest fence start',66:'upper station west vertical fence middle',67:'upper station east-edge lower fence turn and terminal',68:'Mt Chimney east overlook southwest fence terminal',69:'volcanic path paired gray fence posts'}
OWNED=set(LABELS)

def fence(sprite,n):
    m=Model();gray=address(sprite,(132,132,140));dark=address(sprite,(66,74,107));white=address(sprite,(255,255,255))
    def horizontal(lo,hi,z=8):m.box((lo,hi),(7,9),(z-1,z+1),white)
    def vertical(lo,hi,x):m.box((x-1,x+1),(7,9),(lo,hi),white)
    if n==69:points=[(3,8),(13,8)];horizontal(0,16)
    elif n==66:points=[(13,4),(13,12)];vertical(0,16,13)
    elif n==65:points=[(13,4),(13,12)];vertical(4,16,13);horizontal(13,16)
    elif n==63:points=[(3,8),(13,8)];horizontal(3,16);vertical(8,16,3)
    elif n==64:points=[(3,8),(13,8)];horizontal(0,14);vertical(8,16,13)
    elif n==67:points=[(13,8)];vertical(0,8,13);horizontal(13,16)
    else:points=[(3,8)];vertical(0,8,3)
    for x,z in points:
        m.box((x-2,x+2),(0,2),(z-2,z+2),dark);m.box((x-1,x+2),(2,10),(z-1,z+2),gray);m.box((x-1,x+2),(10,11),(z-1,z+2),white)
    return m

def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('lavaridge');return {rows[n-1]['key']:fence(art.cell(rows[n-1]['key'],'BAKED'),n).entry(table['cells'][rows[n-1]['key']],'hand-emerald-volcanic-path-fence',trim=True) for n in sorted(OWNED)}

def assemblies(art):
    rows=rows_for('lavaridge');keys={rows[n-1]['key'] for n in OWNED};out=[]
    cases=[('upper_station','EM_MT_CHIMNEY',13,31,9,10,23),('east_overlook','EM_MT_CHIMNEY',23,39,6,2,7),('lower_station_west','EM_ROUTE112',24,30,2,1,2),('lower_station_east','EM_ROUTE112',28,30,2,1,2)]
    for name,mid,x,y,w,d,count in cases:
        g=art.map_cells(art.maps[mid]);ref=[r[x:x+w] for r in g[y:y+d]];layout=[[k if k in keys else None for k in row] for row in ref];assert sum(k is not None for r in layout for k in r)==count
        out.append({'id':'emerald_lavaridge_fence_'+name,'kind':'object (model as one)','note':'Complete source gray fence run with actual corners, middle sections and endpoints; unrelated station, volcanic ground and trees stay outside the masked fence geometry.','layout':layout,'reference_layout':ref,'occurrences':[{'map':mid,'x':x,'y':y,'palette':'BAKED'}]})
    covered={(p['map'],p['x']+x,p['y']+z) for e in out for p in e['occurrences'] for z,row in enumerate(e['layout']) for x,k in enumerate(row) if k is not None}
    assert covered=={(p['map'],p['x'],p['y']) for k in keys for p in art.locations[k]}
    return out

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('lavaridge');updates=generate(table,art);entities=assemblies(art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n in sorted(OWNED):
        r=rows[n-1];k=r['key'];im=art.cell(k,'BAKED');dest=OUT/k[4:];dest.mkdir(exist_ok=True);im.save(dest/'source.png')
        if args.previews:render(table['cells'][k],im,dest/'before');render(updates[k],im,dest/'after')
        reason='Replace the broad or pinched source extrusion with grounded narrow gray posts, dark small feet, white caps and a continuous white connecting rail. Exclude all volcanic rock/ground pixels from physical metal. '
        reason+=('The north terminal has two posts and a short east branch joining the station top run.' if n==65 else 'The vertical middle repeats two grounded posts and joins both neighboring cells at the same rail height.' if n==66 else 'This north-fed post is the station bottom turn; its short east connector also forms the native end stub at the second source terminal.' if n==67 else 'The lower terminal stops its north rail at the final west-edge post.' if n==68 else 'The corner joins its horizontal run to the southern vertical return.' if n in (63,64) else 'Two capped posts and the connecting rail repeat across the source horizontal runs, including the two separate lower-station barriers.')
        ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'lavaridge-volcanic-path-gray-fences','decision':'changed','reason':reason+' All SOLID/elevation and placement metadata remain unchanged.','baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(im.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'source_detail':'local/model-gallery/emerald-pass-study/lavaridge-fence-source.png','whole_entities':(OUT/'entities.json').relative_to(ROOT).as_posix()}],'visual_status':'source and full runs inspected; candidate/runtime pending'})
    if args.previews:
        for e in entities:
            dest=OUT/'assemblies'/e['id'];dest.mkdir(parents=True,exist_ok=True);im=art.drawing(e['reference_layout'],'BAKED');im.resize((im.width*3,im.height*3),Image.Resampling.NEAREST).save(dest/'source.png');render_assembly(e,art,table['cells'],dest,'before');render_assembly(e,art,{**table['cells'],**updates},dest,'after')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('preview-provenance.json',{'baseline_table_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'metadata_changed':False,'whole_masked':True,'native_terrain_simulated':False})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'changed':7,'source_occurrences':34,'complete_runs':4}))

if __name__=='__main__':main()
