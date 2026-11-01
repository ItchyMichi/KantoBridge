"""Render the inspected low gym dais centers on their source WALK cells.

This is a metadata-only overlay of the latest reviewed geometry. It stages full
owner patches without mutating the canonical patches or live resource table.
"""
from __future__ import annotations
import argparse
import base64
import copy
import hashlib
from pathlib import Path
from tools.shape_fit.emerald_pass import building_interiors as c, review
from tools.model_gallery import write_json

OUT=c.OUT.parent/'gym-walk-corrections'
GROUPS={
    'building__rustboro_gym': ('gyms-west', '8800-8801-8802-8803 8804-8805-8806-8806 8807-8798-8808-8802 8821-8802-8822-8823 6954-6954-8823-8823 8802-8821-8823-8824'.split()),
    'building__dewford_gym': ('gyms-west', '3963-3964-3965-3966 3964-3967-3966-3968 3979-3962-3980-3981 3962-3982-3981-3983'.split()),
    'building__petalburg_gym': ('gyms-rooms', '7918-7921-7920-7922 7923-7923-7924-7924 7925-7918-7926-7920 7930-7930-7933-7934 7930-7930-7934-7934 7930-7930-7934-7935'.split()),
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def generate(table,art):
    patch={};proof={}
    for group,(_,suffixes) in GROUPS.items():
        for suffix in suffixes:
            key='g3q/'+suffix;old=table['cells'][key]
            if any(old.get(flag) for flag in ('walk','on','seat')):
                raise ValueError('Expected unlinked solid-only low dais '+key)
            occupied,_=c.decode(old)
            if occupied[16:].any():raise ValueError('WALK decor supports only the base voxel layer')
            new=copy.deepcopy(old);new['walk']=True;patch[key]=new
            places=[]
            for place in art.locations[key]:
                m=art.maps[place['map']];raw=base64.b64decode(m['cells'])
                collision=raw[(place['y']*m['w']+place['x'])*4+2]
                if collision!=0:raise ValueError('Unexpected dais source collision '+key)
                places.append(dict(place,collision=collision,map_cells_sha256=hashlib.sha256(raw).hexdigest()))
            if not places:raise ValueError('Missing exact source occurrence '+key)
            proof[key]=dict(group=group,input_entry_sha256=review.entry_hash(old),proposed_entry_sha256=review.entry_hash(new),source_pixel_sha256=hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest(),old_metadata={k:v for k,v in old.items() if k not in review.GEOMETRY},new_metadata={k:v for k,v in new.items() if k not in review.GEOMETRY},occurrences=places)
    return patch,proof


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--table',type=Path,default=review.TABLE)
    ap.add_argument('--out',type=Path,default=OUT)
    args=ap.parse_args();table=review.read(args.table);art=c.EmeraldArt(c.BUNDLE)
    patch,proof=generate(table,art);args.out.mkdir(parents=True,exist_ok=True)
    baseline=review.read(review.BASELINE);ledger=[];overlays={}
    for owner in sorted({v[0] for v in GROUPS.values()}):
        canonical=c.OUT.parent/owner;target=args.out/'ownership-overlays'/owner
        target.mkdir(parents=True,exist_ok=True)
        updates=review.read(canonical/'updates.json');rows=review.read(canonical/'ledger.json')
        selected={key:entry for key,entry in patch.items() if GROUPS[proof[key]['group']][0]==owner}
        updates['cells'].update(selected)
        for row in rows:
            key=row['key']
            if key not in selected:continue
            assert row['baseline_entry_sha256']==review.entry_hash(baseline['cells'][key])
            row.update(decision='changed',reason=row['reason']+' The exact source occurrences are WALK 0; add the existing walk-decor flag so this reviewed shallow platform is emitted in normal rooms without solid collision carriers.',visual_status='Source and complete platform geometry inspected; normal-runtime WALK rendering flag corrected; Minecraft recapture pending')
            row['evidence']=list(dict.fromkeys(row.get('evidence',[])+['../gym-walk-corrections/walk-proposal.json']))
            ledger.append(copy.deepcopy(row))
        assert len([r for r in rows if r['key'] in selected])==len(selected)
        write_json(target/'updates.json',updates);write_json(target/'ledger.json',rows)
        overlays[owner]=dict(corrected_keys=list(selected),canonical_inputs={n:digest(canonical/n) for n in ('updates.json','ledger.json')},overlay_outputs={n:digest(target/n) for n in ('updates.json','ledger.json')})
    write_json(args.out/'updates.json',dict(cells=patch,buildings={}))
    write_json(args.out/'ledger.json',ledger);write_json(args.out/'walk-proposal.json',proof)
    write_json(args.out/'ownership-overlays.json',overlays)
    write_json(args.out/'input.json',dict(table=str(args.table),sha256=digest(args.table)))
    print(f'{len(patch)} exact WALK render flags staged in {len(overlays)} owner overlays')


if __name__=='__main__':main()
