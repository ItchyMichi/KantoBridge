"""Fallarbor native boulders, rock-smash clusters, sign and cliff openings."""
from __future__ import annotations
import argparse
import hashlib
import json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model,decode,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,texel,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses,preview
from tools.shape_fit.emerald_pass.towns_fortree_ground import fitted_preview

OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-fallarbor-props'
ROCKS={13,20,31,38,54,56}
CLUSTERS={14,55,61}
CHANGED=ROCKS|CLUSTERS|{6,12}
RETAINED={32,33,98}
OWNED=CHANGED|RETAINED
STONE=((90,90,58),(115,115,74),(148,140,115),(173,173,140),(206,206,165),(230,230,206))


def paint_stone(m,sprite):
    rgb=np.asarray(sprite.convert('RGB'));palette=[]
    for color in STONE:
        hits=np.argwhere((rgb==color).all(axis=2))
        if len(hits):palette.append(int(hits[0,0]*16+hits[0,1]))
    assert len(palette)>=3
    y,z,x=np.indices(m.occ.shape);tone=np.clip(.19+y*.044+(16-x)*.014-z*.005+((x//3+z//4)%3-1)*.08,0,.999)
    m.paint[:]=np.asarray(palette,np.uint8)[(tone*len(palette)).astype(int)]
    return m


def rock(sprite,cluster=False,large=False):
    m=Model()
    if cluster:
        m.ellipsoid((7.5,6.5,6),(6.5,7,5.8));m.ellipsoid((4,2.7,12),(3.6,3.1,3.1));m.ellipsoid((12.5,2.2,10),(2.7,2.7,2.8))
    else:
        m.ellipsoid((8,5.8,8),(7.6 if large else 7,8.1 if large else 7.2,7.4 if large else 6.8))
        m.ellipsoid((5.8,3.8,10),(5,4.2,4.5))
    # Carry each authored stone lobe down to the floor: no narrow waist or
    # unsupported lower skirt under a rounded rock.
    m.occ[:]=np.logical_or.accumulate(m.occ[::-1],axis=0)[::-1]
    return paint_stone(m,sprite)


def recess(sprite):
    m=Model();m.box((0,16),(0,16),(0,16));paint_stone(m,sprite)
    # Closed SOLID secret-base mark: shallow inset, never an invented passage.
    dark=texel(sprite,(41,49,90));rim=texel(sprite,(132,132,140))
    m.box((4,12),(4,12),(14,16),rim);m.box((5,11),(5,11),(14,16),cut=True);m.box((5,11),(5,11),(13,14),dark)
    return m


def sign(sprite):
    dark=texel(sprite,(99,66,82));wood=texel(sprite,(132,90,90));m=Model()
    m.box((7,9),(0,8),(7,10),dark);m.box((1,15),(6,16),(6,10),wood);m.box((2,14),(7,15),(10,11),wood)
    y,z,x=np.indices(m.occ.shape);face=m.occ&(z==10)&(y>=7)&(y<15);m.paint[face]=((16-y)*16+x).astype(np.uint8)[face]
    return m


def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('fallarbor');out={}
    for n in sorted(CHANGED):
        k=rows[n-1]['key'];source=art.cell(k,'BAKED');m=recess(source) if n==6 else sign(source) if n==12 else rock(source,n in CLUSTERS,n in (31,38))
        out[k]=m.entry(table['cells'][k],'hand-emerald-fallarbor-props')
    return out


def decisions():
    labels={6:'closed olive cliff secret-base recess',12:'Route113 wooden road sign',13:'ash-ground olive boulder',14:'ash-ground rock-smash cluster',20:'pink-dirt olive boulder',31:'pale olive route boulder',32:'olive cave-mouth upper arch',33:'olive cave-mouth lower warp threshold',38:'grass-ground pale round boulder',54:'tunnel boulder against west orange cliff',55:'tunnel rock-smash cluster against west cliff',56:'tunnel boulder against east orange cliff',61:'tunnel rock-smash cluster against east cliff',98:'Fossil Maniac tunnel interior warp rim'}
    out={}
    for n in sorted(OWNED):
        if n in ROCKS:reason='Author a grounded rounded olive boulder with broad lower mass and sculpted native stone facets. Remove the pinched/hourglass side and exclude ash, grass, pink dirt, orange cliff background and blue shadow from the stone itself. Each source variant retains its own native stone palette and exact movement metadata.'
        elif n in CLUSTERS:reason='The source is a rock-smash cluster: one large rear stone and two smaller front chunks. Reconstruct those three grounded lobes with native olive stone facets, avoiding the old pinched block and its blue shadow pedestal. Preserve the exact source interaction and movement fields.'
        elif n==6:reason='All12 source placements are SOLID7 cliff marks. Build a continuous olive cliff face around the small gray-edged blue recess; its shallow dark inset is closed, not a fabricated walkable cave or floor pit. Native collision and story handling remain unchanged.'
        elif n==12:reason='Replace the thick oval extrusion with a narrow brown timber signboard on a grounded central post. Preserve the native pale notice/lettering within the front board; ash-ground pixels remain outside its surfaces.'
        elif n==32:reason='The existing explicit solid cliff block has a genuine open lower arch and native dark inner face. It joins the two-row source cave mouth at Route114(8,62), with the actual warp in the lower cell; retain this coherent arch rather than adding a freestanding roof.'
        elif n==33:reason='The source lower cave row is warp113. Its thin one-high threshold leaves the portal approach open beneath the northern solid arch. Preserve the native threshold and movement metadata; it is a projected lower rim, not a second vertical wall.'
        else:reason='The source tunnel warp114 uses an existing thin open brown rim with walk:true. Source context shows it cut into the northern orange cliff. The narrow border remains grounded and the center is open; preserve that passage instead of replacing it with a blocking stone box.'
        out[n]=('changed' if n in CHANGED else 'retained',labels[n],reason)
    return out


def assemblies(art):
    cases=[('road_sign','EM_ROUTE113',57,3,3,3,'Actual ash-road sign with surrounding ground.'),('closed_recess','EM_ROUTE114',8,46,3,3,'Closed native SOLID recess in the olive cliff; no new walk passage.'),('cave_portal','EM_ROUTE114',7,61,3,3,'Complete upper olive arch and lower warp threshold with neighboring cliff.'),('tunnel_portal','EM_ROUTE114_FOSSIL_MANIACS_TUNNEL',5,1,3,3,'Retained open brown warp rim within native orange cliff.'),('pale_boulder','EM_ROUTE114',28,46,3,3,'Pale boulder at the actual shallow ledge bend.')]
    # The solitary boulders/rock-smash clusters are complete single-cell
    # objects; their source occurrence lists in the ledger remain authoritative.
    result=[]
    for ident,mid,x,y,w,d,note in cases:
        g=art.map_cells(art.maps[mid]);result.append({'id':'emerald_fallarbor_props_'+ident,'kind':'object (model as one)','note':note,'layout':[r[x:x+w] for r in g[y:y+d]],'occurrences':[{'map':mid,'x':x,'y':y,'palette':'BAKED'}]})
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args()
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('fallarbor');updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n,(decision,label,reason) in decisions().items():
        row=rows[n-1];k=row['key'];source=art.cell(k,'BAKED');target=OUT/k[4:];target.mkdir(exist_ok=True);source.save(target/'source.png')
        if args.previews:render(table['cells'][k],source,target/'before');render(updates.get(k,table['cells'][k]),source,target/'after')
        ledger.append({'key':k,'kind':'cell','label':label,'family':'fallarbor-stone-sign-portals','decision':decision,'reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':row['tileset_membership'],'map_occurrences':witnesses(art,row),'contact_sheet':(INVENTORY/row['contact_page']).relative_to(ROOT).as_posix(),'contact_position':n},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(source.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'context_sheet':f'local/model-gallery/emerald-pass-study/towns/general__fallarbor/contexts/page-{(n-1)//16+1:03}.png'}],'visual_status':'source and context inspected; Python/runtime pending'})
    live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';current=json.loads(live.read_text());entities=assemblies(art)
    if args.previews:
        for e in entities:
            target=OUT/'assemblies'/e['id'];target.mkdir(parents=True,exist_ok=True);layout=e['layout'];art.drawing(layout,'BAKED').resize((len(layout[0])*64,len(layout)*64),Image.Resampling.NEAREST).save(target/'source.png');fitted_preview(e,art,table['cells'],target,'before');fitted_preview(e,art,{**current['cells'],**updates},target,'after');preview(e,art,{**current['cells'],**updates},target,'runtime-floor')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'elevation_simulated':False})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'reviewed':len(OWNED),'changed':len(updates),'retained':len(RETAINED),'assemblies':len(entities)}))


if __name__=='__main__':main()
