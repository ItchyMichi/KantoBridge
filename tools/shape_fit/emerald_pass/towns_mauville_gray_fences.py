"""Source-specific gray garden fences with existing shared-corner interfaces."""
import argparse,hashlib,json
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model,render,decode
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass.towns_rustboro_props import address
from tools.shape_fit.emerald_pass.towns_rustboro_rails import render_assembly
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-mauville-gray-fences'
LABELS={111:'Verdanturf south terminal gray fence post',144:'Verdanturf paired gray garden fence',145:'Verdanturf vertical gray fence middle',180:'Cycling Road south gate gray fence upper terminal',191:'Verdanturf gray fence north terminal'}
OWNED=set(LABELS)

def fence(sprite,n):
    m=Model();gray=address(sprite,(132,132,140));dark=address(sprite,(66,74,107));light=address(sprite,(255,255,255));shade=address(sprite,(189,206,230))
    if n==144:
        points=[(3,8),(13,8)];m.box((0,16),(7,9),(7,9),light)
    else:
        points=[(3,4)] if n==111 else [(3,4),(3,12)];span=(0,4) if n==111 else (4,16) if n in (180,191) else (0,16);m.box((2,4),(7,9),span,light)
    for x,z in points:
        m.box((x-2,x+2),(0,2),(z-2,z+2),dark);m.box((x-1,x+2),(2,10),(z-1,z+2),gray);m.box((x-1,x+2),(10,11),(z-1,z+2),light);m.box((x+1,x+2),(10,11),(z-1,z+2),shade)
    return m

def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('mauville');return {rows[n-1]['key']:fence(art.cell(rows[n-1]['key'],'BAKED'),n).entry(table['cells'][rows[n-1]['key']],'hand-emerald-mauville-gray-fence',trim=True) for n in sorted(OWNED)}

def assemblies(art):
    rows=rows_for('mauville');out=[]
    for name,mid,x,y,w,d,note in [('verdanturf_west','EM_VERDANTURF_TOWN',10,2,1,3,'Complete five-post north/south garden fence beside the Center; two source terminals and middle share one continuous slim rail.'),('verdanturf_south','EM_VERDANTURF_TOWN',4,12,4,1,'Complete four-cell gray fence joining the gap between the two southern houses; eight capped posts and uninterrupted horizontal rail.'),('cycling_south_gate','EM_ROUTE110',31,91,4,2,'Exact L-shaped gray fence beside the south cycling gate. The new northern terminal meets the current shared-general corner and three unchanged shared horizontal/end cells; surrounding grass is outside the fence exhibit.')]:
        g=art.map_cells(art.maps[mid]);reference=[r[x:x+w] for r in g[y:y+d]];layout=[row[:] for row in reference]
        if name=='cycling_south_gate':layout[0][1:]=[None]*3
        out.append({'id':'emerald_mauville_gray_fence_'+name,'kind':'object (model as one)','note':note,'layout':layout,'reference_layout':reference,'occurrences':[{'map':mid,'x':x,'y':y,'palette':'BAKED'}]})
    assert out[0]['layout']==[[rows[190]['key']],[rows[144]['key']],[rows[110]['key']]]
    assert out[1]['layout']==[[rows[143]['key']]*4]
    assert out[2]['layout'][0][0]==rows[179]['key']
    return out

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('mauville');updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n in sorted(OWNED):
        r=rows[n-1];k=r['key'];im=art.cell(k,'BAKED');dest=OUT/k[4:];dest.mkdir(exist_ok=True);im.save(dest/'source.png')
        if args.previews:render(table['cells'][k],im,dest/'before');render(updates[k],im,dest/'after')
        reason='Build source-aligned grounded gray uprights, dark small feet, separate white caps and a narrow white connecting rail. Exclude all green grass and source shadow from the metal. '
        reason+=('The exact north/south terminal starts the short fence at its northern post and joins the existing shared corner at x2..4/y7..9 along the southern boundary. All neighboring shared entries are preserved.' if n==180 else 'The south terminal has one final post and no invented forward continuation.' if n==111 else 'The paired horizontal posts and rail repeat across all four source cells between the two houses.' if n==144 else 'The middle has two posts and joins both adjacent source cells at the same rail height.' if n==145 else 'The north terminal has two posts and starts its rail at the first actual post, leaving the northern endpoint open.')
        ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'mauville-gray-garden-fences','decision':'changed','baseline_entry_sha256':digest(table['cells'][k]),'reason':reason+' All SOLID/elevation and placement metadata remain unchanged.','evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(im.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'source_context':'local/model-gallery/emerald-pass-study/mauville-gray-rail-source.png','whole_entities':(OUT/'entities.json').relative_to(ROOT).as_posix()}],'visual_status':'source/context inspected; candidate/runtime pending'})
    live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';payload=live.read_bytes();current=json.loads(payload);entities=assemblies(art)
    if args.previews:
        for e in entities:
            target=OUT/'assemblies'/e['id'];target.mkdir(parents=True,exist_ok=True);im=art.drawing(e['reference_layout'],'BAKED');im.resize((im.width*5,im.height*5),Image.Resampling.NEAREST).save(target/'source.png');render_assembly(e,art,table['cells'],target,'before');render_assembly(e,art,{**current['cells'],**updates},target,'after')
    neighbor='g3q/11451-11369-11452-11370';nocc,_=decode(current['cells'][neighbor]);aocc,_=decode(updates[rows[179]['key']]);assert (nocc[:,0]&aocc[:,-1]).any()
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(payload).hexdigest(),'metadata_changed':False,'neighbor_entry_sha256':{neighbor:digest(current['cells'][neighbor])},'northern_terminal_shared_corner_interface':[2,4,7,9]})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'changed':5,'complete_fence_assemblies':3}))

if __name__=='__main__':main()
