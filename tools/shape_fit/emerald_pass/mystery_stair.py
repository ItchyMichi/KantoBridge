"""Repair the source-confirmed Mystery House stair opening seen in QA10 Minecraft."""
from __future__ import annotations
import hashlib
import copy
import numpy as np
from pathlib import Path
from tools.model_gallery import write_json
from tools.shape_fit.blue_pass.common import Model,encode,render
from tools.shape_fit.emerald_pass import building_interiors as c,review
from tools.shape_fit.emerald_pass.day_care import native
from tools import model_gallery as gallery
from tools.shape_fit.emerald_pass.qa10_wholes import canonical_hash

KEY='g3q/7625-7626-7627-7628'
OUT=c.OUT.parent/'mystery-stair-correction'


def model(sprite):
    m=Model();gold=native(sprite,(214,198,82),(12,0,16,16));light=native(sprite,(255,239,132),(12,0,16,16));dark=native(sprite,(148,132,33),(12,0,16,16));navy=native(sprite,(66,74,107))
    for x0,x1 in ((0,2),(14,16)):
        m.box((x0,x1),(0,14),(0,3),gold)
        m.box((x0,x1),(1,2),(0,3),dark)
    m.box((0,16),(13,14),(0,3),light)
    m.box((0,16),(14,16),(0,3),navy)
    for z0,z1,h in ((3,6,8),(6,9,6),(9,12,4),(12,16,2)):
        m.box((1,15),(0,h),(z0,z1),gold)
        m.box((1,15),(h-1,h),(z0,z1),light)
        m.box((1,15),(max(0,h-2),h-1),(z1-1,z1),dark)
    return m


def generate(table,art):
    old=table['cells'][KEY];sprite=art.cell(KEY,'BAKED');m=model(sprite);entry=encode(m.occ,m.paint,old,source=old['from'],trim=True)
    reason='Actual QA10 whole capture confirms a full dark backplane blocking the concealed stair opening. Replace that backplane with a clear central arch and four supported native-gold treads rising north; preserve the lintel/jamb silhouette and exact walk:true/from:warp metadata. Painted foliage belongs to the source projection, so use only the visible stair-edge gold and dark room trim for the frame and steps.'
    row={'key':KEY,'kind':'cell','group':'building__mystery_events_house','source_ordinal':15,'label':'concealed Mystery House stair opening','family':'source-confirmed native warp stair','decision':'changed','baseline_entry_sha256':review.entry_hash(old),'proposed_entry_sha256':review.entry_hash(entry),'source_pixel_sha256':hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest(),'occurrences':art.locations[KEY],'reason':reason,'evidence':['local/model-gallery/emerald-batch-02/qa10-wholes-01/visual-qa-root.json','local/model-gallery/emerald-batch-02/qa10-wholes-01/screenshots/emerald_qa10_mystery_stairs_1f_three_quarter.png'],'visual_status':'Actual defect inspected; local proposal and source-route validation required before recapture'}
    return entry,row


def bind_actual_whole(out=OUT):
    """Refresh only this corrected current context; retain its original before proof."""
    runtime=review.read(out/'runtime-proof.json')
    if runtime['candidate_updates_sha256']!=hashlib.sha256((out/'updates.json').read_bytes()).hexdigest():
        raise ValueError('Mystery stair runtime proof belongs to a different candidate')
    snapshot=out/'fitted-proof-current.json'
    if runtime['current_table_sha256']!=hashlib.sha256(snapshot.read_bytes()).hexdigest():
        raise ValueError('Mystery stair runtime snapshot differs')
    table=review.read(snapshot);old=review.read(out/'original-entity.json');proof=copy.deepcopy(runtime)
    if len(proof['occurrences'])!=1:raise ValueError('expected one exact Mystery stair source context')
    row=proof['occurrences'][0];layout=row['layout'];art=c.EmeraldArt(c.BUNDLE)
    if layout!=old['layout']:raise ValueError('Mystery stair source layout differs')
    common={'occurrence':{k:row[k] for k in ('map','x','y')},'source_layout':layout}
    row['current_source_cell_hashes']={key:canonical_hash(table['cells'].get(key)) for key in sorted({k for line in layout for k in line})}
    row['current_source_template_hashes']=gallery.native_source_template_hashes(table,{'descriptor':common,'record':row},art)
    path=out/'runtime-current-provenance.json';write_json(path,proof)
    common.update(evidence=path.relative_to(review.ROOT).as_posix(),evidence_sha256=hashlib.sha256(path.read_bytes()).hexdigest())
    entity=copy.deepcopy(old)
    entity['current_native_fragments']=dict(common,current_table_sha256=runtime['current_table_sha256'],source_cell_hashes=row['current_source_cell_hashes'],source_template_hashes=row['current_source_template_hashes'])
    entity['note']='Exact Mystery House source-map columns with the source-confirmed center stair repair; native original baseline and story behavior remain unchanged.'
    write_json(out/'entities.json',[entity]);write_json(out/'entity-preconditions.json',{old['id']:old})
    return entity


def main():
    OUT.mkdir(exist_ok=True,parents=True);art=c.EmeraldArt(c.BUNDLE);entry,row=generate(review.read(review.BASELINE),art)
    write_json(OUT/'updates.json',{'cells':{KEY:entry},'buildings':{}});write_json(OUT/'ledger.json',[row]);render(entry,art.cell(KEY,'BAKED'),OUT/'inspection')
    if (OUT/'runtime-proof.json').exists():bind_actual_whole()
    print('One source-confirmed stair correction; unchanged warp metadata')


if __name__=='__main__':main()
