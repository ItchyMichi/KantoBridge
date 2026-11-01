"""Source-specific Slateport curb returns, shore lips and floor projections."""
from __future__ import annotations
import hashlib
import json
import numpy as np
from PIL import Image
from tools import model_gallery as gallery
from tools.shape_fit.blue_pass.common import Model, decode, render
from tools.shape_fit.emerald_pass.towns import ROOT, BUNDLE, BASELINE, rows_for, digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass.towns_rustboro_props import address
from tools.shape_fit.emerald_pass.towns_rustboro_rails import render_assembly

OUT = ROOT / 'local/model-gallery/emerald-pass-study/towns-slateport-edges'
OWNED = {15,16,19,20,21,24,33,50,51,52,53,58,62,88,92}
RETAINED = {15,16,58,62}
LABELS = {15:'west straight pale quay curb',16:'east straight pale quay curb',19:'north pale quay curb',20:'northeast quay curb return',21:'northwest quay curb return',24:'south pale quay curb',33:'flat beach/cliff-tip projection',50:'northwest curb and brown shore lip',51:'straight curb and brown shore lip',52:'northeast curb and brown shore lip',53:'curb over western water edge',58:'west curb beside mixed garden paving',62:'east curb beside mixed garden paving',88:'southeast quay curb return',92:'Oceanic Museum lower tread projection'}


def model(position, sprite):
    m = Model()
    if position in (33,92):
        m.box((0,16),(0,1),(0,16))
        _,z,x=np.indices(m.occ.shape)
        m.paint[m.occ]=(z*16+x).astype(np.uint8)[m.occ]
        return m
    gray = address(sprite,(132,132,140))
    if position in (19,20,21,50,51,52,53):m.box((0,16),(0,4),(0,8),gray)
    if position in (24,88):m.box((0,16),(0,4),(8,16),gray)
    if position in (20,88):m.box((8,16),(0,4),(0 if position==88 else 8,8 if position==88 else 16),gray)
    if position==21:m.box((0,8),(0,4),(8,16),gray)
    curb=m.occ.copy()
    # Top paint follows the actual curb's x/z drawing. Native paving, grass,
    # beach and sea outside these explicitly selected strips have no volume.
    _,z,x=np.indices(m.occ.shape)
    top=curb & ~np.concatenate((curb[1:],np.zeros_like(curb[:1])))
    m.paint[top]=(z*16+x).astype(np.uint8)[top]
    if position in (50,51,52,53):
        brown=address(sprite,(132,90,90));dark=address(sprite,(99,66,82))
        m.box((0,16),(0,3),(8,11),brown)
        m.box((0,16),(0,1),(10,11),dark)
        if position==50:
            m.box((0,4),(0,3),(11,16),brown);m.box((4,6),(0,3),(11,13),brown)
        if position==52:
            m.box((12,16),(0,3),(11,16),brown);m.box((10,12),(0,3),(11,13),brown)
    return m


def generate(base=None,art=None):
    base=base or json.loads(BASELINE.read_text());art=art or gallery.EmeraldArt(BUNDLE);rows=rows_for('slateport')
    return {rows[n-1]['key']:model(n,art.cell(rows[n-1]['key'],'BAKED')).entry(base['cells'][rows[n-1]['key']],'hand-emerald-slateport-quay-edges') for n in sorted(OWNED-RETAINED)}


def entities(art):
    keys={rows_for('slateport')[n-1]['key'] for n in OWNED}
    # Existing environment-owned east curb joins the southern shore return.
    # It is included in the source assembly without transferring its ownership.
    keys.add('g3q/10658-10673-10659-10668')
    grid=art.map_cells(art.maps['EM_SLATEPORT_CITY']);result=[]
    for name,x,y,w,d in [('museum_west',24,21,5,9),('museum_east',33,21,6,11),('south_shore',20,53,9,6),('western_shore',0,55,10,4),('museum_treads',30,27,2,1)]:
        ref=[r[x:x+w] for r in grid[y:y+d]];layout=[[k if k in keys else None for k in line] for line in ref]
        result.append({'id':'emerald_slateport_edges_'+name,'kind':'source quay section','layout':layout,'reference_layout':ref,'occurrences':[{'map':'EM_SLATEPORT_CITY','x':x,'y':y,'palette':'BAKED'}],'note':'Exact source section of pale curb runs, turns and shore lips, including the reviewed adjacent environment curb where present. Unrelated terrain/building art remains reference-only; native terrain heights are not simulated by this fitted assembly. Source offset/occluded curb transitions remain visible as source boundaries, not asserted as a complete standalone railing. WALK projection cells keep native floor routing.'})
    return result


def main():
    art=gallery.EmeraldArt(BUNDLE);base=json.loads(BASELINE.read_text());rows=rows_for('slateport');patch=generate(base,art);OUT.mkdir(parents=True,exist_ok=True)
    live_path=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';live=json.loads(live_path.read_text());ledger=[]
    for n in sorted(OWNED):
        row=rows[n-1];key=row['key'];assert live['cells'][key]==base['cells'][key]
        sprite=art.cell(key,'BAKED');dest=OUT/'cells'/str(n);dest.mkdir(parents=True,exist_ok=True);sprite.save(dest/'source.png');render(base['cells'][key],sprite,dest/'before');render(patch.get(key,base['cells'][key]),sprite,dest/'after')
        if n in RETAINED:reason='The existing four-high strip already occupies only the source white/gray vertical curb half, with no adjoining garden/paving volume. Its straight top and gray side meet the explicit corner and horizontal curb segments.'
        elif n in (33,92):reason=('Keep the beach/cliff-tip drawing as a one-voxel source-x/z floor reference.' if n==33 else 'Place the two-cell Museum lower tread/grass projection on a one-voxel source-x/z floor reference; the existing whole Museum ends above this lower projection.')+' The source is WALK0 with no walk/seat/on flag, so actual rendering remains native floor; no extra collider or raised grass slab is introduced.'
        elif n in (50,51,52,53):reason='Author the four-high white curb and its shallow brown shore lip explicitly, with a returned lip only on the actual corner variant. Source sand, grass and water no longer fill or paint the physical edge; all brown/gray materials come from this exact drawing. Existing source kinds, elevation and native cliff routing are preserved.'
        else:reason='Replace the full-cell slab with the source horizontal curb strip and the indicated return only. Its top uses native x/z curb art, gray sides remain gray, and adjacent paving has no raised volume. Four-high strip interfaces match the retained straight vertical curbs.'
        ledger.append({'key':key,'kind':'cell','label':LABELS[n],'family':'slateport-quay-and-shore-edges','decision':'retained' if n in RETAINED else 'changed','reason':reason,'baseline_entry_sha256':digest(base['cells'][key]),'evidence':[{'contact_position':n,'source_pixel_sha256':hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB','map_occurrences':witnesses(art,row)},{'source_contact':'local/model-gallery/emerald-pass-study/towns/general__slateport/contexts','previews':dest.relative_to(ROOT).as_posix()}],'visual_status':'Source inspected; local preview and runtime placement review pending'})
    whole=entities(art)
    for e in whole:
        dest=OUT/'assemblies'/e['id'];dest.mkdir(parents=True,exist_ok=True);im=art.drawing(e['reference_layout'],'BAKED');im.resize((im.width*3,im.height*3),Image.Resampling.NEAREST).save(dest/'source.png');render_assembly(e,art,live['cells'],dest,'before');render_assembly(e,art,{**live['cells'],**patch},dest,'after')
    for filename,value in [('updates.json',{'cells':patch,'buildings':{}}),('ledger.json',ledger),('entities.json',whole),('preview-provenance.json',{'current_table_sha256':hashlib.sha256(live_path.read_bytes()).hexdigest(),'native_terrain_simulated':False,'metadata_changed':False})]:gallery.write_json(OUT/filename,value)
    print('15 Slateport edge decisions:11 changed,4 retained;5 source assemblies; runtime review pending')


if __name__=='__main__':main()
