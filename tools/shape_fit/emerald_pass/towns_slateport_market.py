"""Eleven Slateport market goods, authored from their own source drawings."""
import argparse,hashlib,json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass.towns_rustboro_props import address
from tools.shape_fit.emerald_pass.towns_rustboro_rails import render_assembly
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-slateport-market'
OWNED={1,2,3,4,5,6,10,14,34,35,95}
LABELS={1:'market bottle and pale bundle tray',2:'market open round brown jar',3:'market red and gold leafy goods tray',4:'shaded red and gold leafy goods tray',5:'market green-filled round tub',6:'shaded green-filled round tub',10:'market red-filled round tub',14:'market gold-filled round tub',34:'shaded bottle and pale bundle tray',35:'shaded open round brown jar',95:'market cluster of colored round goods'}
DARK=(66,74,107);BROWN=(140,115,74);TAN=(173,165,107);RIM=(222,206,148);CREAM=(255,255,222);GREEN=(0,214,156);LEAF=(66,165,115);RED=(239,58,33);GOLD=(255,156,0);YELLOW=(230,222,58)

def vessel(sprite,mode):
    m=Model();dark=address(sprite,DARK);brown=address(sprite,BROWN);tan=address(sprite,TAN);rim=address(sprite,RIM)
    # Broad round vessel with a stable flat foot; never an hourglass extrusion.
    for y,r in ((0,4.5),(1,5.2),(2,5.8),(3,6.2),(4,6.5),(5,6.5),(6,6.5),(7,6.3),(8,6.1)):
        m.cylinder((8,8),r,(y,y+1),brown if y not in (0,7) else dark)
    if mode=='open':
        m.cylinder((8,8),5.7,(8,10),tan);m.cylinder((8,8),4.5,(9,11),rim);m.cylinder((8,8),3.3,(6,11),cut=True);m.cylinder((8,8),3.3,(5,6),dark)
        # Small native highlight on the western shoulder, not pale pavement.
        m.box((2,3),(5,7),(7,9),address(sprite,CREAM))
    else:
        m.cylinder((8,8),6.4,(8,10),rim);m.cylinder((8,8),5.3,(9,10),tan)
        fill,shade=(GREEN,LEAF) if mode=='green' else (RED,(197,82,16)) if mode=='red' else (GOLD,(197,82,16))
        a,b=address(sprite,fill),address(sprite,shade)
        m.cylinder((8,8),5.2,(9,10),b)
        for x,z,h in ((5,5,10),(10,5,10),(4,9,10),(8,8,11),(12,9,10),(8,12,10)):
            m.ellipsoid((x,h,z),(2.3,1.7,2.3),a)
            if mode=='green':m.box((x-1,x),(h,h+1),(z-1,z+1),b)
            else:m.box((x-1,x),(h+1,h+2),(z-1,z),address(sprite,YELLOW))
    return m

def tray(sprite,bottles):
    m=Model();edge=address(sprite,DARK);rim=address(sprite,RIM);cream=address(sprite,CREAM)
    m.box((2,14),(0,2),(2,14),edge);m.box((2,14),(2,3),(2,14),rim)
    for x in (1,13):m.box((x,x+2),(1,4),(2,14),rim)
    for z in (1,13):m.box((2,14),(1,4),(z,z+2),rim)
    if bottles:
        green=address(sprite,GREEN);leaf=address(sprite,LEAF)
        for x,z,h in ((4,5,11),(8,4,14)):
            m.cylinder((x,z),1.9,(3,h-3),green);m.cylinder((x,z),1.2,(h-3,h-1),leaf);m.cylinder((x,z),1.4,(h-1,h),cream)
        m.box((10,13),(3,13),(5,10),cream);m.box((12,13),(3,12),(5,10),rim)
        m.cylinder((7,11),2.5,(3,8),address(sprite,YELLOW));m.cylinder((7,11),2.6,(5,6),address(sprite,RED));m.cylinder((7,11),2.1,(8,9),cream)
    else:
        red=address(sprite,RED);gold=address(sprite,GOLD);green=address(sprite,GREEN);leaf=address(sprite,LEAF)
        for i,(x,z,h) in enumerate(((4,4,7),(10,4,7),(4,9,7),(10,10,7),(7,7,10))):
            m.cylinder((x,z),1.2,(3,h),leaf);m.ellipsoid((x,h,z),(2.5,2.3,2.5),gold if i%2==0 else red);m.box((x-1,x),(h,h+2),(z-2,z-1),address(sprite,YELLOW));m.box((x+1,x+3),(h-1,h),(z,z+2),green)
    return m

def colored_cluster(sprite):
    m=Model();edge=address(sprite,DARK);cream=address(sprite,CREAM)
    # Separate visible goods touch a small low display tray; no tall brown vessel.
    m.ellipsoid((8,1,8),(6.1,1.3,6),edge);m.ellipsoid((8,2,8),(5.8,1,5.7),address(sprite,RIM))
    for x,z,h,r,color in ((4.5,5.5,5,3,RED),(5.7,10,5,3.4,YELLOW),(10.8,9.5,5.5,3.5,GOLD)):
        m.cylinder((x,z),r*.65,(1,h),address(sprite,color));m.ellipsoid((x,h,z),(r,r,r),address(sprite,color));m.box((int(x)-1,int(x)+1),(int(h+r)-1,int(h+r)),(int(z)-1,int(z)+1),cream)
    m.cylinder((10,4),2,(2,10),address(sprite,GREEN));m.cylinder((10,4),1.5,(10,11),cream)
    return m

def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('slateport');out={}
    for n in sorted(OWNED):
        k=rows[n-1]['key'];im=art.cell(k,'BAKED')
        if n in (1,34):m=tray(im,True)
        elif n in (3,4):m=tray(im,False)
        elif n==95:m=colored_cluster(im)
        else:m=vessel(im,'open' if n in (2,35) else 'green' if n in (5,6) else 'red' if n==10 else 'gold')
        out[k]=m.entry(table['cells'][k],'hand-emerald-slateport-market-goods',trim=True)
    return out

def assemblies(art):
    rows=rows_for('slateport');keys={rows[n-1]['key'] for n in OWNED};g=art.map_cells(art.maps['EM_SLATEPORT_CITY']);out=[]
    for name,y,d in (('north',34,6),('middle',41,7),('south',50,6)):
        ref=[r[3:13] for r in g[y:y+d]];layout=[[k if k in keys else None for k in r] for r in ref]
        out.append({'id':'emerald_slateport_market_goods_'+name,'kind':'object (model as one)','note':'Actual market goods placements in this section; masked source awnings, boxes, paving and unrelated architecture remain outside these reviewed small objects. Native SOLID routing and every original placement flag are preserved.','layout':layout,'reference_layout':ref,'occurrences':[{'map':'EM_SLATEPORT_CITY','x':3,'y':y,'palette':'BAKED'}]})
    covered={(e['occurrences'][0]['map'],3+x,e['occurrences'][0]['y']+z) for e in out for z,r in enumerate(e['layout']) for x,k in enumerate(r) if k}
    assert covered=={(p['map'],p['x'],p['y']) for k in keys for p in art.locations[k]};assert len(covered)==54
    return out

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('slateport');updates=generate(table,art);entities=assemblies(art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n in sorted(OWNED):
        r=rows[n-1];k=r['key'];im=art.cell(k,'BAKED');dest=OUT/k[4:];dest.mkdir(exist_ok=True);im.save(dest/'source.png')
        if args.previews:render(table['cells'][k],im,dest/'before');render(updates[k],im,dest/'after')
        if n in (1,34):reason='Build the square pale display tray with two narrow green bottles, cream caps, a tall pale bundle and a low yellow/red-banded container. Distinct objects stand on the tray; surrounding pale or shaded paving is excluded.'
        elif n in (2,35):reason='Build a broad grounded round brown jar with a genuine recessed dark opening and pale rim. The native light shoulder highlight remains on the jar, while surrounding pavement is excluded. Replace the deep pinched sprite extrusion.'
        elif n in (3,4):reason='Build separate red and gold rounded goods with native green stems/leaves in a shallow pale square tray. Preserve this source-specific bright or shaded variant without using the ground pixels as side material.'
        elif n==95:reason='Build the source cluster as distinct red, yellow and orange rounded goods plus a small green capped item on a low pale tray. Do not invent the tall brown vessel used by neighboring round tubs or extrude the gray paving.'
        else:reason='Build a broad grounded brown tub with a pale round rim and separate '+('green' if n in (5,6) else 'red' if n==10 else 'gold')+' contents. Native darker fill and small highlights give the top relief; the vessel sides use only its brown/outline/rim materials. Remove the old pinched hourglass profile and pavement pixels.'
        ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'slateport-source-specific-market-goods','decision':'changed','reason':reason+' All exact SOLID7 source placements and existing metadata remain unchanged.','baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(im.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'source_detail':'local/model-gallery/emerald-pass-study/slateport-market-source.png','actual_context':f'local/model-gallery/emerald-pass-study/towns/general__slateport/contexts/page-{(n-1)//16+1:03}.png','complete_market_sections':(OUT/'entities.json').relative_to(ROOT).as_posix()}],'visual_status':'source and contexts inspected; candidate/runtime pending'})
    if args.previews:
        for e in entities:
            dest=OUT/'assemblies'/e['id'];dest.mkdir(parents=True,exist_ok=True);im=art.drawing(e['reference_layout'],'BAKED');im.resize((im.width*3,im.height*3),Image.Resampling.NEAREST).save(dest/'source.png');render_assembly(e,art,table['cells'],dest,'before');render_assembly(e,art,{**table['cells'],**updates},dest,'after')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('preview-provenance.json',{'baseline_table_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'metadata_changed':False,'native_terrain_simulated':False,'unrelated_geometry_masked':True})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'changed':11,'source_occurrences':54,'market_sections':3}))

if __name__=='__main__':main()
