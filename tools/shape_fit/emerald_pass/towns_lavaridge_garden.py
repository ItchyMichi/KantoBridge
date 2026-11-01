"""Lavaridge flowers, horizontal sand baths and whole-covered pool seams."""
import argparse,hashlib,json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.blue_pass.common import Model,encode,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass.towns_rustboro_props import address,render_assembly
from tools.shape_fit.emerald_pass.vegetation import flower
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-lavaridge-garden'
LIVE=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json'
OWNED={8,9,70,71,73,74,76,77};CHANGED={8,9,70,73}
LABELS={8:'western three-flower clump',9:'eastern three-flower clump',70:'sand bath northern rim and pale sand',71:'hot spring pool northern steps',73:'sand bath southern rim',74:'Lavaridge Gym sign and facade',76:'hot spring pool southwest stone corner',77:'hot spring pool southeast stone corner'}
COLORS={1:(115,82,41),2:(181,156,74),3:(255,230,140)}

def bath():
    """One shallow oval in the source 16×32 horizontal footprint."""
    m=Model(depth=32);y,z,x=np.indices(m.occ.shape);outer=((x+.5-8)/6.8)**2+((z+.5-15)/9.3)**2<=1;inner=((x+.5-8)/5.4)**2+((z+.5-15)/7.8)**2<=1
    m.occ[:]=outer&(y<1);m.paint[:]=3;m.occ|=outer&~inner&(y<2);m.paint[outer&~inner]=1
    # The upper lip is sandy gold; the native dark earth is exposed below it.
    m.paint[(y==1)&outer&~inner]=2
    return m

def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('lavaridge');whole=bath();out={}
    for n in sorted(CHANGED):
        k=rows[n-1]['key'];im=art.cell(k,'BAKED')
        if n in (8,9):e=flower(im).entry(table['cells'][k],'hand-emerald-lavaridge-flowers',trim=True)
        else:
            z=0 if n==70 else 16;o=whole.occ[:,z:z+16];p=np.zeros(o.shape,np.uint8);kind=whole.paint[:,z:z+16]
            for index,color in COLORS.items():p[kind==index]=address(im,color)
            e=encode(o,p,table['cells'][k],'hand-emerald-horizontal-sand-bath',trim=True)
            if n==70:e.pop('on')
        out[k]=e
    return out

def coverage(art,table):
    specs={b['id']:b for b in table['buildings']};catalog=building_catalog(table,art);rows=rows_for('lavaridge');out={}
    for n in sorted(OWNED-CHANGED):
        k=rows[n-1]['key'];places=[]
        for loc in art.locations[k]:
            matches=[]
            for e in catalog:
                for p in e['occurrences']:
                    x,z=loc['x']-p['x'],loc['y']-p['y']
                    if loc['map']==p['map'] and 0<=z<len(e['layout']) and 0<=x<len(e['layout'][z]) and e['layout'][z][x]==k and [x,z] not in specs[e['id']].get('terrain',[]):matches.append({'id':e['id'],'local_cell':[x,z],'building_entry_sha256':digest(specs[e['id']])})
            assert matches,(n,loc);places.append({'occurrence':loc,'buildings':matches})
        out[k]={'fully_covered':True,'occurrences':places}
    return out

def assemblies(art):
    rows=rows_for('lavaridge');g=art.map_cells(art.maps['EM_LAVARIDGE_TOWN']);pair=[[rows[69]['key']],[rows[72]['key']]];occ=[{'map':'EM_LAVARIDGE_TOWN','x':x,'y':8,'palette':'BAKED'} for x in (3,5)]
    for p in occ:assert [[r[p['x']]] for r in g[8:10]]==pair
    flowers=[[rows[7]['key'],rows[8]['key']]];assert g[4][12:14]==flowers[0]
    return [{'id':'emerald_lavaridge_horizontal_sand_bath','kind':'object (model as one)','note':'One shallow oval sand bath across two horizontal source rows, repeated twice. Both rows are native WALK floor; remove the obsolete vertical on relation without adding decor or collision. The old man is an independent map object at the eastern bath.','layout':pair,'occurrences':occ},{'id':'emerald_lavaridge_flower_pair','kind':'object (model as one)','note':'Two source-specific three-flower clumps; native WALK floor routing is unchanged.','layout':flowers,'occurrences':[{'map':'EM_LAVARIDGE_TOWN','x':12,'y':4,'palette':'BAKED'}]}]

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();table=json.loads(BASELINE.read_text());current=json.loads(LIVE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('lavaridge');updates=generate(table,art);proof=coverage(art,current);entities=assemblies(art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n in sorted(OWNED):
        r=rows[n-1];k=r['key'];im=art.cell(k,'BAKED');dest=OUT/k[4:];dest.mkdir(exist_ok=True);im.save(dest/'source.png')
        if args.previews:render(table['cells'][k],im,dest/'before');render(updates.get(k,table['cells'][k]),im,dest/'after')
        if n in (8,9):reason='Author three separate coral flowers on narrow green stems and low native foliage, using this exact source variant palette. Exclude turquoise background from physical petals and leaves. Preserve native WALK routing with no new placement flag.'
        elif n in (70,73):reason='Build the complete shallow horizontal oval sand bath before slicing its two source rows. A pale sand center and low earth rim replace the vertical pinched extrusion. Both exact 1×2 placements are WALK0 sand ground; remove only the northern false on link, keep all other metadata, and add no new walk decor or collision. The old man at the eastern bath remains a separate source map object.'
        else:reason='Retain this original source fallback because every exact occurrence is claimed by the reviewed complete '+('Lavaridge Gym sign/facade' if n==74 else 'Lavaridge Pokemon Center hot spring pool')+'. Its '+LABELS[n]+' belongs to that continuous whole, not a separate freestanding slab. Preserve source identity and metadata; this is exact whole-building coverage, not isolated extrusion visual approval.'
        evidence=[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(im.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__lavaridge/contexts/page-{(n-1)//16+1:03}.png'}]
        if k in proof:evidence.append({'exact_complete_object_coverage':proof[k]})
        ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'lavaridge-garden-sand-baths-pool-seams','decision':'changed' if n in CHANGED else 'retained','reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':evidence,'visual_status':'source/context inspected; Python and runtime pending'})
    if args.previews:
        for e in entities:
            dest=OUT/'assemblies'/e['id'];dest.mkdir(parents=True,exist_ok=True);im=art.drawing(e['layout'],'BAKED');im.resize((im.width*6,im.height*6),Image.Resampling.NEAREST).save(dest/'source.png');render_assembly(e,art,table['cells'],dest,'before');render_assembly(e,art,{**current['cells'],**updates},dest,'after')
    k=rows[69]['key'];geometry={'v','t','up','from'};amendment={'key':k,'before':{a:b for a,b in table['cells'][k].items() if a not in geometry},'after':{a:b for a,b in updates[k].items() if a not in geometry},'source_pixel_sha256':hashlib.sha256(art.cell(k,'BAKED').convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB','witnesses':witnesses(art,rows[69]),'reason':'Two exact source sand baths at Lavaridge(3,8) and(5,8) are horizontal1×2 shallow ovals. Both halves WALK0. Remove false upper-to-lower vertical on, preserve native floor and independent old-man object, add no walk flag.'}
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('coverage-proof.json',proof),('metadata-corrections.json',[amendment]),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(LIVE.read_bytes()).hexdigest(),'runtime_verified':False,'native_floor_simulated':False,'metadata_change_keys':[k]})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'reviewed':8,'changed':4,'retained':4,'metadata_corrections':1,'whole_objects':2}))

if __name__=='__main__':main()
