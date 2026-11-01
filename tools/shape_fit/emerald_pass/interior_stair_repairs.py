"""Source-visible shallow treads behind four previously open stair surrounds."""
from __future__ import annotations
import argparse,copy,hashlib
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.emerald.building_trials import box,paint
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.environment_templates import swatch
from tools.shape_fit.emerald_pass import generic_appliance_templates as previews

OUT=ROOT/'local/model-gallery/emerald-pass-study/interior-stair-repairs'
ORIGINAL=ROOT/'local/model-gallery/emerald-pass-study/generic-architecture-templates'
QA=ROOT/'local/model-gallery/emerald-batch-02/buildings-final-04/qa-followups-scenery.json'
IDS={'emerald_object_generic_green_stair_3a0ff8053824','emerald_object_generic_green_stair_4e31c26c21bb',
     'emerald_object_generic_yellow_stair_5f514013d9c1','emerald_object_generic_yellow_double_stair_615a0f2ed69e'}
STAIR='g3q/5491-5492-5493-5494'

def generate(art):
    old=read(ORIGINAL/'additions.json')['buildings'];rows={r['key']:r for r in read(ORIGINAL/'added-ledger.json')}
    groups=[g for g in read(ORIGINAL/'source-evidence.json')['groups'] if g['id'] in IDS]
    updates={};ledger=[]
    for g in groups:
        i=g['id'];t=copy.deepcopy(old[i]);d=art.drawing(g['layout'],'BAKED').convert('RGB')
        assert g['layout'][1][1]==STAIR and g['source_kinds'][1][1]==114
        # The exact source depicts stairs rising away from the southern warp.
        # They are behind the arch plane, leaving its existing doorway volume
        # z24..32/y0..16 empty. Source collisions remain the runtime authority.
        light=swatch(d,(222,222,74),(17,17,31,32));dark=swatch(d,(132,132,49),(17,17,31,32))
        for z,h in ((20,2),(16,4),(12,6),(8,8)):
            t['parts'].append(box([16,32],[z,z+4],[0,h],paint(dark,light,dark,dark)))
        updates[i]=t;r=copy.deepcopy(rows[i]);r.update(proposed_entry_sha256=entry_hash(t),previous_entry_sha256=entry_hash(old[i]),
            reason='Fresh Minecraft comparison confirmed that the complete wall/arch was coherent but omitted the upward treads clearly visible in the exact source. Add four grounded shallow steps rising north behind the arch face, using the native gold tread and shaded riser colors. The original doorway volume remains clear. Preserve template ID, source pattern, height, terrain and interior metadata; native source warp/collision and closed story leaves remain unchanged. Fresh corrected Minecraft QA is pending.',
            evidence=[str(QA),str(OUT/'source-evidence.json'),str(OUT/'inspection/index.json')]);ledger.append(r)
    assert set(updates)==IDS
    return updates,ledger,groups,{i:old[i] for i in IDS}

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args();art=EmeraldArt(BUNDLE)
    updates,ledger,groups,old=generate(art)
    for name,value in [('updates.json',{'cells':{},'buildings':updates}),('added-ledger.json',ledger),('preconditions.json',{'buildings':old}),
        ('source-evidence.json',{'groups':groups,'captured_qa_sha256':hashlib.sha256(QA.read_bytes()).hexdigest(),'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'source_headroom_evidence':str(ORIGINAL/'runtime-proof.json'),'source_headroom_evidence_sha256':hashlib.sha256((ORIGINAL/'runtime-proof.json').read_bytes()).hexdigest(),'geometry':'Four explicit steps at x16..32, z20..24/16..20/12..16/8..12, y0..2/4/6/8. Northward rise stays behind the arch plane z24..32; the doorway keeps its original16-voxel clear height. The second descending portal in the double arch remains unchanged.','limits':'Source/local correction candidate; fresh runtime and Minecraft evidence are separate.'})]:write_json(OUT/name,value)
    if args.previews:
        previous=previews.OUT
        try:previews.OUT=OUT;previews.render(art,[updates[g['id']] for g in groups],groups)
        finally:previews.OUT=previous
    print('4 exact existing-template geometry replacements; metadata unchanged')
if __name__=='__main__':main()
