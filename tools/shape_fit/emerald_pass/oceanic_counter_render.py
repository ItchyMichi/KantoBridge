"""Stage four source-proven Oceanic counter opt-ins, preserving reviewed geometry."""
from __future__ import annotations
import argparse
import base64
import copy
import hashlib
from pathlib import Path
from tools.shape_fit.emerald_pass import review,building_interiors as c
from tools.model_gallery import write_json

OWNER=c.OUT.parent/'oceanic-museum'
OUT=c.OUT.parent/'oceanic-counter-render'
KEYS=('g3q/7740-7740-7764-7764','g3q/7741-7743-7741-7743','g3q/7740-7788-7764-7764','g3q/7788-7789-7764-7764')


def generate(table,art):
    patch={};proof=[]
    for key in KEYS:
        old=table['cells'][key]
        assert old.get('class')=='voxels' and not any(old.get(flag) for flag in ('counter','walk','seat','on','up'))
        occ,_=c.decode(old);assert occ.any() and not occ[16:].any()
        new=copy.deepcopy(old);new['counter']=True;patch[key]=new
        places=[]
        for loc in art.locations[key]:
            m=art.maps[loc['map']];raw=base64.b64decode(m['cells'])
            collision=raw[(loc['y']*m['w']+loc['x'])*4+2]
            assert collision==144
            places.append(dict(loc,collision=collision,map_cells_sha256=hashlib.sha256(raw).hexdigest()))
        assert places
        proof.append(dict(key=key,before={k:v for k,v in old.items() if k not in review.GEOMETRY},after={k:v for k,v in new.items() if k not in review.GEOMETRY},input_entry_sha256=review.entry_hash(old),proposed_entry_sha256=review.entry_hash(new),source_pixel_sha256=hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest(),occurrences=places))
    return patch,proof


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);ap.add_argument('--table',type=Path,default=review.TABLE);args=ap.parse_args()
    table=review.read(args.table);art=c.EmeraldArt(c.BUNDLE);patch,proof=generate(table,art)
    updates=review.read(OWNER/'updates.json');ledger=review.read(OWNER/'ledger.json');metadata=review.read(OWNER/'metadata-evidence.json')
    for row in ledger:
        if row['key'] not in patch:continue
        row['decision']='changed'
        row['reason']+=' Add the explicit Emerald counter render opt-in for every source COUNTER144 occurrence. Reviewed voxel geometry is unchanged; the normal planner retains one collision block and places any raised lip on a second noncolliding carrier.'
        row['evidence'].append('oceanic-counter-render/counter-proposal.json')
        row['visual_status']='Source/whole-model authoring review and normal WorldPlan counter carrier fixture pass; Minecraft recapture pending'
    updates['cells'].update(patch);metadata.extend(proof);args.out.mkdir(parents=True,exist_ok=True)
    for name,value in [('updates',updates),('ledger',ledger),('metadata-evidence',metadata),('counter-proposal',proof),('patch',dict(cells=patch,buildings={}))]:write_json(args.out/(name+'.json'),value)
    write_json(args.out/'ownership-overlay.json',dict(owner=str(OWNER),keys=list(KEYS),canonical_inputs={name:hashlib.sha256((OWNER/name).read_bytes()).hexdigest() for name in ('updates.json','ledger.json','metadata-evidence.json')},overlay_outputs={name:hashlib.sha256((args.out/name).read_bytes()).hexdigest() for name in ('updates.json','ledger.json','metadata-evidence.json')},input_table_sha256=hashlib.sha256(args.table.read_bytes()).hexdigest()))
    print('Four counter render flags; geometry unchanged; complete Oceanic owner overlay staged')


if __name__=='__main__':main()
