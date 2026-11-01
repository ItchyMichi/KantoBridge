"""Source-specific ash corner, olive cliff caps and projected tree backgrounds."""
from __future__ import annotations
import argparse
import hashlib
import json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model,decode,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses,preview
from tools.shape_fit.emerald_pass.towns_fortree_ground import fitted_preview

OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-fallarbor-cliffs'
OWNED=CHANGED={1,27,28,29,30,59,60,70,71}
ROLES={27:(0,0),28:(1,0),29:(0,1),30:(1,1)}
OLIVE=((90,90,58),(115,115,74),(148,140,115))
PINK=((99,66,82),(132,90,90),(156,115,115),(189,148,140),(222,181,165))
ORANGE=((90,49,16),(148,82,25),(189,107,58),(214,132,82),(239,156,107))


def address(sprite,color):
    hits=np.argwhere((np.asarray(sprite.convert('RGB'))==color).all(axis=2));assert len(hits),color
    return int(hits[0,0]*16+hits[0,1])


def paint(m,sprite,colors,offset=(0,0)):
    y,z,x=np.indices(m.occ.shape);gx,gz=x+offset[0],z+offset[1];tone=np.clip(.3+y*.065+(16-x)*.035+(gx//3+gz//4)%3*.12,0,.999)
    m.paint[:]=np.asarray([address(sprite,c) for c in colors],np.uint8)[(tone*len(colors)).astype(int)]
    return m


def cap():
    m=Model(width=32,depth=32);y,z,x=np.indices(m.occ.shape);r=np.sqrt(((x+.5-16)/15.8)**2+((z+.5-16)/15.5)**2)
    m.occ[:]=(r<=1)&(y+.5<3+12*np.maximum(0,1-r)**.75)
    return m


def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('fallarbor');whole=cap();out={}
    for n in sorted(OWNED):
        k=rows[n-1]['key'];sprite=art.cell(k,'BAKED');m=Model()
        if n==1:
            # Native proof fixes both adjoining west/south columns at two
            # Minecraft blocks. This northeast rounded return reaches both
            # interfaces at the same16px height; north/east stay beveled.
            y,z,x=np.indices(m.occ.shape);m.occ[:]=((x+.5)/16)**2+((15.5-z)/16)**2<=1
            paint(m,sprite,PINK)
        elif n in ROLES:
            qx,qz=ROLES[n];m.occ[:]=whole.occ[:,qz*16:qz*16+16,qx*16:qx*16+16];paint(m,sprite,OLIVE,(qx*16,qz*16))
            if qz==0:
                y,z,x=np.indices(m.occ.shape);m.paint[(y>7)&(z<13)&((x//3+z//4)%3==0)]=address(sprite,(206,206,165))
        else:
            # These cells are a solid cliff with a neighboring tree drawn in
            # front. The complete tree is elsewhere (accepted Littleroot or
            # Fallarbor lower-root footprint); do not add floating leaf tips.
            m.box((0,16),(0,16),(0,16));paint(m,sprite,ORANGE if n in (59,60) else OLIVE)
        out[k]=m.entry(table['cells'][k],'hand-emerald-fallarbor-cliff',trim=True)
    return out


def decisions():
    labels={1:'ash cliff rounded northeast return',27:'olive cliff northwest shoulder',28:'olive cliff northeast shoulder',29:'olive cliff southwest foot',30:'olive cliff southeast foot',59:'orange cliff behind projected west tree crown',60:'orange cliff behind projected east tree crown',70:'olive cliff behind accepted tree northwest tip',71:'olive cliff behind accepted tree northeast tip'}
    out={}
    for n in sorted(OWNED):
        if n==1:reason='The previous small eight-high wedge was disconnected from its actual west/south cliff. Exact native source fixture proves both neighbors occupy two Minecraft blocks. Author a rounded northeast return16vox high reaching both west and south interfaces, with only native pink stone. All11source placements have solid west and south neighbors; retain collision/elevation and keep northern/eastern ledge/path variants separate.'
        elif n in ROLES:reason='Build one connected shallow olive cap before slicing these four source corners, closing the old central trench and pinched foot when the keys form a2x2 outcrop. Preserve a single16-high layer: exact native Route114 cliff audit shows tall-cliff occurrences use native cube/roof columns instead of these fallback shapes, and must keep that native routing. Only native stone paints the cap; grass and gray background are excluded.'
        elif n in (59,60):reason='This upper SOLID source cell is the orange cliff behind a projected tree crown. The complete three-lobed tree occupies the lower2x1solid root footprint. Restore a continuous native orange cliff face here instead of a duplicate floating crown, using only its own orange rock pixels and preserving the single-layer/native placement policy. The neighboring cliff and tree cells retain their exact identities.'
        else:reason='The small green source corners are projected tips of the protected accepted Littleroot tree immediately south, not separate forest trunks. Preserve the solid olive cliff mass with a continuous native stone face, exclude foliage and gray background from the wall, and leave all accepted tree keys and their reference-layout projections unchanged. No stack link or duplicate foliage object is added.'
        out[n]=('changed',labels[n],reason)
    return out


def assemblies(art):
    cases=[('ash_return','EM_ROUTE113',50,3,3,3,'Actual ash-cliff return: exact native west/south columns are2blocks high; native source proof is attached to its ledger.'),('olive_outcrop','EM_ROUTE114',22,38,2,2,'Complete four-cell grass-backed olive outcrop, with its connected cap and foot.'),('olive_cliff_join','EM_ROUTE114',31,47,3,3,'Native tall-cliff occurrence: fixture proves allnine cells use5block native wall columns; standalone fitted preview is not its actual map height.'),('orange_tree_backdrop','EM_FALLARBOR_TOWN',4,2,2,3,'Orange cliff behind the exact complete tree rooted at row4; compare projected upper crown to the restored cliff face.'),('accepted_tree_backdrop','EM_ROUTE115',32,49,8,4,'Actual olive cliff and projected leaf tips above the protected accepted Littleroot trees; preserve every accepted source key.')]
    out=[]
    for ident,mid,x,y,w,d,note in cases:
        g=art.map_cells(art.maps[mid]);out.append({'id':'emerald_fallarbor_cliffs_'+ident,'kind':'object (model as one)','note':note,'layout':[r[x:x+w] for r in g[y:y+d]],'occurrences':[{'map':mid,'x':x,'y':y,'palette':'BAKED'}]})
    return out


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('fallarbor');updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    native=ROOT/'local/model-gallery/emerald-pass-study/cliff-interface-study/runtime-proof.json'
    for n,(_,label,reason) in decisions().items():
        r=rows[n-1];k=r['key'];source=art.cell(k,'BAKED');target=OUT/k[4:];target.mkdir(exist_ok=True);source.save(target/'source.png')
        if args.previews:render(table['cells'][k],source,target/'before');render(updates[k],source,target/'after')
        evidence=[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix(),'contact_position':n},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(source.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'context_sheet':f'local/model-gallery/emerald-pass-study/towns/general__fallarbor/contexts/page-{(n-1)//16+1:03}.png'}]
        if n in {1,27,28,29,30}:evidence.append({'native_neighbor_runtime_proof':native.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(native.read_bytes()).hexdigest()})
        if n in (70,71):evidence.append({'accepted_tree_generator':'tools/shape_fit/emerald/littleroot_tree.py','protected_lower_source_keys':['g3q/12331-12332-12333-12334','g3q/12335-12336-12337-12338'],'finding':'Only source-projected tips overlap this cliff cell; no accepted tree entry changes.'})
        ledger.append({'key':k,'kind':'cell','label':label,'family':'fallarbor-cliff-source-interfaces','decision':'changed','reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':evidence,'visual_status':'source/native neighbors reviewed; Python/runtime candidate pending'})
    live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';current=json.loads(live.read_text());entities=assemblies(art)
    # Unfrozen tree candidate is composed for source interface inspection.
    from tools.shape_fit.emerald_pass.towns_fallarbor_trees import generate as trees
    combined={**current['cells'],**trees(table,art),**updates}
    if args.previews:
        for e in entities:
            target=OUT/'assemblies'/e['id'];target.mkdir(parents=True,exist_ok=True);layout=e['layout'];art.drawing(layout,'BAKED').resize((len(layout[0])*64,len(layout)*64),Image.Resampling.NEAREST).save(target/'source.png');fitted_preview(e,art,table['cells'],target,'before');fitted_preview(e,art,combined,target,'after');preview(e,art,combined,target,'runtime-floor')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'elevation_simulated':False,'includes_unfrozen_tree_candidate':True,'native_cliff_source_proof_sha256':hashlib.sha256(native.read_bytes()).hexdigest()})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'reviewed':9,'changed':9,'assemblies':5,'single_layer_native_policy_preserved':True}))


if __name__=='__main__':main()
