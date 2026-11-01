"""Four explicit retained town seams after their nature neighbors were delegated.

The Route124 facade pair remains a source-proven missing whole-house template
case, tracked separately; reviewing these cells does not close that gap.
"""
import hashlib
import json
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.model_gallery import EmeraldArt

OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-seams'
REGISTER={
 'petalburg':{
  1:('Littleroot brown roof seam','All six placements are inside the complete b000/b001 Littleroot houses. Keep this thin source roof seam as a matching fallback; the reviewed complete house roofs supply the actual volume.'),
  22:('Oldale brown roof seam','All four placements are inside the complete b058 Oldale houses. The source rib and roof colors match the reviewed whole-house roof; retain the fallback rather than duplicate that roof with a separate cell.'),
 },
 'mossdeep':{
  3:('Route124 Treasure Hunter house west facade corner','The hand-authored straight west wall, yellow siding, dark cornice and narrow red roof lip match the actual house at Route124(69,47). Retain this coherent local facade; the absent exact whole-house template is separately recorded in coverage_gaps.json.'),
  4:('Route124 Treasure Hunter house east facade corner','The mirrored east wall at Route124(72,47) preserves the yellow siding and roof/cornice return without a generic tapered facade. Retain its source-specific geometry while the documented whole-house template addition remains pending.'),
 },
}


def main():
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);proof=json.loads((ROOT/'local/model-gallery/emerald-pass-study/towns/building-coverage-proof.json').read_text());ledger=[];OUT.mkdir(parents=True,exist_ok=True)
    for group,items in REGISTER.items():
        rows=rows_for(group)
        for i,(label,reason) in items.items():
            row=rows[i-1];key=row['key'];sprite=art.cell(key,'BAKED');target=OUT/key.removeprefix('g3q/');target.mkdir(parents=True,exist_ok=True);sprite.save(target/'source.png')
            evidence=[{'source_tilesets':row['tileset_membership'],'map_occurrences':row['occurrences'],'contact_sheet':str((INVENTORY/row['contact_page']).relative_to(ROOT)),'contact_position':i},
             {'source':str((target/'source.png').relative_to(ROOT)),'source_pixels_sha256':hashlib.sha256(sprite.tobytes()).hexdigest()},
             {'review_card':str((OUT/'source-review.png').relative_to(ROOT))}]
            if group=='petalburg':evidence.append({'exact_building_occurrence_coverage':proof[key]})
            else:evidence.append({'whole_house_gap':'tools/shape_fit/emerald_pass/coverage_gaps.json','gap_id':'emerald-route124-treasure-hunters-house'})
            ledger.append({'key':key,'kind':'cell','label':label,'family':group,'decision':'retained','reason':reason,'baseline_entry_sha256':digest(table['cells'][key]),'evidence':evidence,'visual_status':'source, actual map context, front/east/top inspected; retained'})
    (OUT/'updates.json').write_text(json.dumps({'cells':{},'buildings':{}},indent=2)+'\n');(OUT/'ledger.json').write_text(json.dumps(ledger,indent=2)+'\n')
    sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
    (OUT/'python-visual-qa.json').write_text(json.dumps({'status':'four retained source/map/orthographic inspections complete','user_approved':False,'ledger_sha256':sha(OUT/'ledger.json'),'updates_sha256':sha(OUT/'updates.json'),'review_card_sha256':sha(OUT/'source-review.png'),'findings':[{'key':r['key'],'finding':r['reason']} for r in ledger],'known_followup':'Source-proven exact Route124 house template is still pending; these cell decisions do not imply that it exists.'},indent=2)+'\n')
    print(json.dumps({'reviewed':4,'changed':0,'retained':4}))


if __name__=='__main__':main()
