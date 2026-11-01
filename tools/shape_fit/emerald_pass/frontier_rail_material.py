"""Six source-proven Dome rail paint corrections after captured and whole QA.

Keep every occupied voxel and non-grass source address. Only the three exact
grass colors leaking onto the post edges become the source's native gray metal.
"""
from __future__ import annotations
import hashlib
import json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import decode,encode,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,rows_for,texel,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses,preview

OUT=ROOT/'local/model-gallery/emerald-pass-study/frontier-rail-material'
KEYS=('g3q/13358-11358-13359-11356','g3q/13379-11358-13380-11356',
      'g3q/11356-13419-11358-13420','g3q/11356-11374-11358-13437',
      'g3q/11356-13445-11358-13446','g3q/11370-11358-13456-11356')
GRASS=((66,181,132),(115,197,165),(165,214,197))
GRAY=(132,132,140)


def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);out={}
    for key in KEYS:
        old=table['cells'][key];occ,paint=decode(old);sprite=art.cell(key,'BAKED');rgb=np.asarray(sprite.convert('RGB'))
        colors=rgb[paint//16,paint%16];bad=occ & np.any(np.all(colors[...,None,:]==np.asarray(GRASS),axis=-1),axis=-1)
        paint[bad]=texel(sprite,GRAY)
        out[key]=encode(occ,paint,old,'hand-emerald-dome-rail-material')
    return out


def entity(art,west=False):
    name='EM_BATTLE_FRONTIER_OUTSIDE_WEST';x=13 if west else 25
    g=art.map_cells(art.maps[name]);layout=[[g[y][x]] for y in range(9,18)]
    return {'id':'emerald_frontier_dome_'+('west' if west else 'east')+'_fence','kind':'object (model as one)',
            'note':'Complete actual Dome metal fence, including both returned endpoints and corrected post edges. Upper WALK source projection remains outside its physical footprint.',
            'layout':layout,'occurrences':[{'map':name,'x':x,'y':9,'palette':'BAKED'}]}


def main():
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True)
    live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';current=json.loads(live.read_text())
    rows={r['key']:r for r in rows_for('battle_frontier_outside_west')};ledger=[]
    for key in KEYS:
        assert table['cells'][key]==current['cells'][key], 'Re-audit later edits before overwriting this narrow correction'
        sprite=art.cell(key,'BAKED');target=OUT/key[4:];target.mkdir(exist_ok=True);sprite.save(target/'source.png')
        render(table['cells'][key],sprite,target/'before');render(updates[key],sprite,target/'after')
        where=art.locations[key][0];side='west' if where['x']==13 else 'east'
        ledger.append({'key':key,'kind':'cell','label':'Dome '+side+' fence post gray edge repair','family':'frontier-metal-fence',
            'decision':'changed','baseline_entry_sha256':digest(table['cells'][key]),
            'reason':'Captured views and subsequent whole-fence source/model audit exposed green ground pixels on the narrow post and cap edges. Preserve every occupied voxel, white highlight, dark upright and gray connector; replace only the three exact native grass colors with the source gray metal pixel.',
            'evidence':[{'source_pixel_sha256':hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB','map_occurrences':witnesses(art,rows[key])},
                        {'captured_defect':'local/model-gallery/emerald-batch-02/scenery-04/visual-qa-bounds.json','captured_defect_sha256':hashlib.sha256((ROOT/'local/model-gallery/emerald-batch-02/scenery-04/visual-qa-bounds.json').read_bytes()).hexdigest()},
                        {'whole_entity':'emerald_frontier_dome_'+side+'_fence','source_rectangle':{'map':'EM_BATTLE_FRONTIER_OUTSIDE_WEST','x':where['x'],'y':9,'width':1,'height':9},'source_and_geometry_audit':'local/model-gallery/emerald-pass-study/frontier-rail-material/all-rail-audit.png'}],
            'visual_status':'source and defect inspected; final preview/runtime recapture pending'})
    entities=[entity(art,False),entity(art,True)]
    for item in entities:
        target=OUT/'assemblies'/item['id'];target.mkdir(parents=True,exist_ok=True)
        art.drawing(item['layout'],'BAKED').resize((64,576),Image.Resampling.NEAREST).save(target/'source.png')
        preview(item,art,current['cells'],target,'before');preview(item,art,{**current['cells'],**updates},target,'after')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'occupancy_unchanged':True,'metadata_unchanged':True})]:
        (OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'changed':len(updates),'entities':len(entities)}))


if __name__=='__main__':main()
