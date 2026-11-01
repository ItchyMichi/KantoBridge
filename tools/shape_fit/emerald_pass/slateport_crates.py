"""Two timber crate sources and their exact three-crate market piles."""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
import numpy as np
from PIL import Image, ImageDraw
from tools import model_gallery as gallery
from tools.shape_fit.blue_pass.common import Model, render
from tools.shape_fit.emerald import parts_model
from tools.shape_fit.emerald_pass.towns import ROOT,BUNDLE,BASELINE,rows_for,digest
from tools.shape_fit.emerald_pass.towns_rustboro_props import address
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters
from tools.shape_fit.emerald_pass.buildings_slateport_market import goods_parts

OUT=ROOT/'local/model-gallery/emerald-pass-study/slateport-crates'
MAP='EM_SLATEPORT_CITY'
OWNED={90,91,93,94}
# List order is part of the matching proof: a full pile must claim its top
# source cell before the single-crate subset tries that same drawing.
CASES={
 'emerald_object_slateport_crate_3stack_plain':(90,93),
 'emerald_object_slateport_crate_3stack_labelled':(91,94),
 'emerald_object_slateport_crate_single_plain':(90,),
 'emerald_object_slateport_crate_single_labelled':(91,),
}


def crate(sprite,count=1,lid=True):
    m=Model(height=16 if count<3 else 32)
    dark=address(sprite,(66,74,107));brown=address(sprite,(140,115,74));cream=address(sprite,(222,206,148))
    yy,zz,xx=np.indices(m.occ.shape)
    for j in range(count):
        y=j*8
        m.box((1,15),(y,y+8),(1,15),brown)
        m.box((0,16),(y,y+1),(0,16),dark)
        m.box((1,15),(y+7,y+8),(1,15),cream if lid else brown)
        # Straight timber corner cleats and a subtle side plank seam.
        for x in (1,14):
            for z in (1,14):m.box((x,x+1),(y+1,y+7),(z,z+1),cream)
        m.box((1,2),(y+4,y+5),(2,14),dark)
        m.box((14,15),(y+4,y+5),(2,14),dark)
        front=(zz==14)&(yy>=y+1)&(yy<y+7)&(xx>=1)&(xx<15)
        m.paint[front]=((15-(yy-y))*16+xx).astype(np.uint8)[front]
        if lid:
            top=(yy==y+7)&(zz>=1)&(zz<15)&(xx>=1)&(xx<15)
            m.paint[top]=((zz//2)*16+xx).astype(np.uint8)[top]
    # The exported drawing's clipped top corners include the lilac pavement
    # outside the crate outline. They are not timber or lid highlights.
    rgb=np.asarray(sprite.convert('RGB'))[m.paint//16,m.paint%16]
    ground=np.isin(rgb.reshape(-1,3).view('V3').ravel(),np.asarray([(148,140,165),(165,156,165),(197,181,222)],np.uint8).view('V3').ravel()).reshape(m.occ.shape)
    m.paint[m.occ&ground]=dark
    return m


def cell_updates(base,art):
    rows=rows_for('slateport');return {rows[n-1]['key']:crate(art.cell(rows[n-1]['key'],'BAKED'),1 if n in (90,91) else 2,n in (90,91)).entry(base['cells'][rows[n-1]['key']],'hand-emerald-slateport-timber-crates') for n in sorted(OWNED)}


def layout(positions):
    rows=rows_for('slateport');return [[rows[n-1]['key']]for n in positions]


def buildings(art):
    result={}
    for ident,positions in CASES.items():
        grid=layout(positions);sprite=art.cell(grid[0][0],'BAKED');count=3 if len(positions)==2 else 1
        model=crate(sprite,count);parts=goods_parts(model,0,16 if count==3 else 0,0,0)
        # Exact source diagnostics include up to seven native carriers under
        # these drawings. This envelope preserves their collision profile;
        # visible timber remains eight/twenty-four voxels high in the parts.
        result[ident]={'id':ident,'atlas':'g3q','tiles':quarters(grid),'height':56,'terrain':[],
                      'preserveTerrain':True,'preserveCarriers':True,'parts':parts}
    return result


def occurrences(art,ident,entry):
    grid=layout(CASES[ident]);places=gallery.building_catalog({'game':'emerald','cells':{},'buildings':[entry]},art)[0]['occurrences']
    # Use a true standalone representative for each single-crate comparison;
    # every raw match (including pile subsets) stays in the evidence and pins.
    preferred={'emerald_object_slateport_crate_single_plain':(11,35),'emerald_object_slateport_crate_single_labelled':(13,33)}.get(ident)
    if preferred:places.sort(key=lambda p:(p['x'],p['y'])!=preferred)
    result=[]
    for p in places:
        s=art.maps[p['map']];raw=base64.b64decode(s['cells']);cells=[]
        for z,row in enumerate(grid):
            x,y=p['x'],p['y']+z;i=(y*s['w']+x)*4
            cells.append({'key':row[0],'x':x,'y':y,'local':[0,z],'collision':raw[i+2],'elevation':raw[i+3],'metatile':int.from_bytes(raw[i:i+2],'little'),'terrain_excluded':False})
        assert all(c['collision']==7 for c in cells)
        result.append(dict(p,width=1,depth=len(grid),cells=cells,warps=[]))
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args()
    art=gallery.EmeraldArt(BUNDLE);base=json.loads(BASELINE.read_text());patch=cell_updates(base,art);objects=buildings(art);OUT.mkdir(parents=True,exist_ok=True);rows=rows_for('slateport');ledger=[];added=[]
    for n in sorted(OWNED):
        row=rows[n-1];key=row['key'];im=art.cell(key,'BAKED');dest=OUT/'cells'/str(n);dest.mkdir(parents=True,exist_ok=True);im.save(dest/'source.png')
        if args.previews:render(base['cells'][key],im,dest/'before');render(patch[key],im,dest/'after')
        ledger.append({'key':key,'kind':'cell','decision':'changed','label':('labelled' if n in (91,94) else 'plain')+(' single timber crate' if n in (90,91) else ' lower two crate faces'),'family':'slateport-timber-crates','baseline_entry_sha256':digest(base['cells'][key]),'reason':'Replace the hourglass extrusion with explicit straight timber boxes, grounded dark lower rims, real horizontal lids where the source has a lid, corner cleats and source-local slat/label front faces. Preserve every metadata field. Complete exact three-box patterns and standalone patterns provide real-map placement; these cells retain source-specific fallback geometry.','occurrences':art.locations[key],'evidence':[{'contact_position':n,'source_pixel_sha256':hashlib.sha256(im.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB','map_occurrences':witnesses(art,row)},{'native_before_diagnostic':'local/model-gallery/emerald-pass-study/towns-slateport-edges/runtime-proof.json'}],'visual_status':'authoring candidate;source/runtime review pending'})
    for ident,entry in objects.items():
        grid=layout(CASES[ident]);im=art.drawing(grid,'BAKED').convert('RGB');places=occurrences(art,ident,entry);dest=OUT/ident;dest.mkdir(exist_ok=True);im.save(dest/'source.png')
        if args.previews:
            o,c=parts_model.build(entry,np.asarray(im));parts_model.picture(o,c,dest/'view.png',9);parts_model.front_view(o,c,dest/'front.png',10);parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(c,(0,2,1,3))[:,:,::-1],dest/'side.png',10)
        added.append({'key':ident,'kind':'building','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(entry),'label':ident.replace('emerald_object_','').replace('_',' '),'family':'slateport-source-crate-wholes','source_pixel_sha256':hashlib.sha256(im.tobytes()).hexdigest(),'source_pixel_mode':'RGB','occurrences':places,'terrain_exclusions':[],'reason':'Exact source '+('three-crate vertical pile, with three connected boxes rooted in the lower source cell; upper source row is the perspective projection of the high lid and first front' if len(grid)==2 else 'standalone timber crate, using its real lid, slatted wood or fish-marked lid and green front label')+'. Match full piles before their single-cell source subsets. Preserve all source SOLID kinds and existing unclaimed native terrain profiles; no inferred on links or floor plates. Raw global occurrences include nested single patterns, while actual fixture must prove12standalone+4wholepile placements.','evidence':[{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'native_before_diagnostic':'local/model-gallery/emerald-pass-study/towns-slateport-edges/runtime-proof.json'}],'user_approved':False,'visual_status':'authoring candidate;source/native proof pending'})
    gallery.write_json(OUT/'updates.json',{'cells':patch,'buildings':objects});gallery.write_json(OUT/'ledger.json',ledger);gallery.write_json(OUT/'added-ledger.json',added)
    print('Four cells+four exact crate patterns; matching/raw-occurrence coverage still requires source fixture')


if __name__=='__main__':main()
