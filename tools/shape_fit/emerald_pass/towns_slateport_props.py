"""Slateport ornamental shrub, curved harbor fixture and market notice board."""
import argparse,hashlib,json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass.towns_rustboro_props import address
from tools.shape_fit.emerald_pass.towns_rustboro_rails import render_assembly
from tools.shape_fit.emerald_pass.vegetation import shrub
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-slateport-props'
OWNED={18,89,108}
LABELS={18:'Slateport rounded ornamental shrub',89:'harbor curved pale mooring fixture',108:'market framed notice board'}

def harbor_fixture(sprite):
    m=Model();dark=address(sprite,(99,99,123));gray=address(sprite,(132,132,140));white=address(sprite,(255,255,255));pale=address(sprite,(189,206,230));blue=address(sprite,(156,181,222))
    m.ellipsoid((7,1,8),(4.1,1.5,3.5),gray);m.box((5,9),(1,6),(6,10),blue)
    y,z,x=np.indices(m.occ.shape);r=((x+.5-8)/5.8)**2+((y+.5-8.5)/5.5)**2;inner=((x+.5-8)/3.2)**2+((y+.5-8.5)/3.1)**2
    curve=(r<=1)&(inner>=1)&((x<8)|(y>=9))&(z>=5)&(z<11);m.put(curve,pale);m.paint[curve&(z>=9)]=white;m.paint[curve&(y<6)]=blue
    m.box((8,13),(9,12),(5,11),pale);m.box((8,13),(11,12),(5,10),white);m.box((8,12),(8,9),(6,10),dark)
    return m

def notice(sprite):
    m=Model();edge=address(sprite,(66,74,107));metal=address(sprite,(132,132,140));orange=address(sprite,(247,115,74))
    for x in (1,13):m.box((x,x+2),(0,4),(7,11),metal)
    m.box((0,16),(2,16),(7,10),edge);m.box((1,15),(3,14),(9,11),orange)
    y,z,x=np.indices(m.occ.shape);front=(z==10)&(y>=3)&(y<14)&(x>=1)&(x<15)
    m.paint[front]=((15-y)*16+x).astype(np.uint8)[front]
    m.box((1,15),(14,15),(9,11),address(sprite,(255,230,156)))
    return m

def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('slateport');out={}
    for n in sorted(OWNED):
        k=rows[n-1]['key'];im=art.cell(k,'BAKED');m=shrub(im) if n==18 else harbor_fixture(im) if n==89 else notice(im);out[k]=m.entry(table['cells'][k],'hand-emerald-slateport-source-props',trim=True)
    return out

def assemblies(art):
    rows=rows_for('slateport');g=art.map_cells(art.maps['EM_SLATEPORT_CITY']);out=[]
    for name,x,y,w,d,n in [('north_garden',7,10,7,3,18),('harbor_garden',27,25,8,2,18),('harbor_mooring_pair',33,34,5,1,89)]:
        ref=[r[x:x+w] for r in g[y:y+d]];layout=[[k if k==rows[n-1]['key'] else None for k in r] for r in ref];out.append({'id':'emerald_slateport_props_'+name,'kind':'object (model as one)','note':'Source-exact placement of the reviewed shrubs or harbor fixtures; unrelated building, ground and water remain in reference art only.','layout':layout,'reference_layout':ref,'occurrences':[{'map':'EM_SLATEPORT_CITY','x':x,'y':y,'palette':'BAKED'}]})
    assert [sum(k is not None for r in e['layout'] for k in r) for e in out]==[6,4,2]
    return out

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('slateport');updates=generate(table,art);entities=assemblies(art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    reasons={18:'Use the reviewed rounded ornamental shrub sculpture with overlapping leafy lobes and this exact source-native green palette. The source is a complete one-cell shrub repeated in two symmetric gardens, not a tall crown stack. Exclude turquoise ground from all foliage; preserve source SOLID7/elevation3.',89:'Author the dockside pale fixture as a supported curved head with a genuine opening beneath its hooked upper portion, a short blue-gray lower stem and grounded foot. The curved pale silhouette and shaded underside replace the hourglass extrusion. Only its native white/blue-gray metal colors are used; pale pavement stays outside the physical fixture.',108:'Build a narrow freestanding notice board with two grounded metal feet, dark frame, orange inner border and pale printed face. Map the native blue text marks onto the actual front face while using clean dark frame material on the sides and back. Remove the old deep pinched extrusion and retain its exact market source position.'}
    for n in sorted(OWNED):
        r=rows[n-1];k=r['key'];im=art.cell(k,'BAKED');dest=OUT/k[4:];dest.mkdir(exist_ok=True);im.save(dest/'source.png')
        if args.previews:render(table['cells'][k],im,dest/'before');render(updates[k],im,dest/'after')
        ledger.append({'key':k,'kind':'cell','label':LABELS[n],'family':'slateport-garden-harbor-market-props','decision':'changed','reason':reasons[n]+' Existing metadata remains unchanged.','baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(im.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'source_detail':'local/model-gallery/emerald-pass-study/slateport-props-source.png','actual_context':f'local/model-gallery/emerald-pass-study/towns/general__slateport/contexts/page-{(n-1)//16+1:03}.png'}],'visual_status':'source/context inspected; candidate/runtime pending'})
    if args.previews:
        for e in entities:
            dest=OUT/'assemblies'/e['id'];dest.mkdir(parents=True,exist_ok=True);im=art.drawing(e['reference_layout'],'BAKED');im.resize((im.width*3,im.height*3),Image.Resampling.NEAREST).save(dest/'source.png');render_assembly(e,art,table['cells'],dest,'before');render_assembly(e,art,{**table['cells'],**updates},dest,'after')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('preview-provenance.json',{'baseline_table_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'metadata_changed':False,'unrelated_geometry_masked':True,'native_terrain_simulated':False})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'changed':3,'source_occurrences':16,'masked_assemblies':3}))

if __name__=='__main__':main()
