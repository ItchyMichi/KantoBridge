"""Source-proven Weather terminal rim/PC/floor correction after Minecraft QA."""
from __future__ import annotations
import argparse,base64,copy,hashlib
from pathlib import Path
from tools.shape_fit.emerald_pass import building_interiors as c,review
from tools.shape_fit.blue_pass.common import encode,render
from tools.model_gallery import write_json

CAP='g3q/6557-6557-6586-6587'
PC='g3q/9-10-11-12'
FOOT='g3q/6599-6600-6588-6588'
OUT=c.OUT.parent/'weather-terminal-correction'
MAP='EM_ROUTE119_WEATHER_INSTITUTE_1F'


def generate(table,art):
    cap,paint=c.weather_terminal_cap(table['cells'][CAP])
    floor,fpaint=c.weather_terminal_floor(art.cell(FOOT,'BAKED'))
    updates={CAP:encode(cap,paint,table['cells'][CAP],source='hand-emerald-interior-review',trim=True),FOOT:encode(floor,fpaint,table['cells'][FOOT],source='hand-emerald-interior-review',trim=True)}
    updates[CAP]['on']=PC;updates[FOOT]['walk']=True
    proof=[]
    for key,expect_y,expect_collision in [(CAP,0,7),(PC,1,147),(FOOT,2,0)]:
        places=[]
        for place in art.locations[key]:
            if key==PC and place['map']!=MAP:continue
            source=art.maps[place['map']];raw=base64.b64decode(source['cells']);collision=raw[(place['y']*source['w']+place['x'])*4+2]
            assert place['map']==MAP and (place['x'],place['y'],collision)==(2,expect_y,expect_collision)
            places.append(dict(place,collision=collision,map_cells_sha256=hashlib.sha256(raw).hexdigest()))
        assert len(places)==1
        row=dict(key=key,source_pixel_sha256=hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest(),occurrences=places,baseline_entry_sha256=review.entry_hash(table['cells'][key]))
        if key in updates:
            row.update(before_metadata={k:v for k,v in table['cells'][key].items() if k not in review.GEOMETRY},after_metadata={k:v for k,v in updates[key].items() if k not in review.GEOMETRY},proposed_entry_sha256=review.entry_hash(updates[key]))
        proof.append(row)
    return updates,proof


def assembly():
    return dict(id='emerald_weather_institute_tall_terminal',kind='object (model as one)',note='Actual Institute1F three-row terminal: rim above the shared PC, native floor below. Baseline retains independent cap and PC, while the unflagged WALK foot is absent.',map=MAP,rect=[2,0,1,3],source_layout=[[CAP],[PC],[FOOT]],placements=[dict(source=[0,0],offset=[0,1,1]),dict(source=[0,1],offset=[0,0,1]),dict(source=[0,2],offset=[0,0,2])])


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args()
    art=c.EmeraldArt(c.BUNDLE);baseline=review.read(review.BASELINE);current=review.read(review.TABLE);patch,proof=generate(baseline,art)
    assert 'on' not in current['cells'][PC], 'Shared PC must retain the reviewed independent WALK-foot correction'
    current['cells'].update(patch);args.out.mkdir(exist_ok=True,parents=True)
    spec=assembly();folder=args.out/'assemblies';folder.mkdir(exist_ok=True)
    entity=c.assembly_evidence(spec,current,art,folder)
    entity['baseline_layout']=[[CAP],[PC],[None]]
    entity['baseline_placements']=[dict(source=[0,0],offset=[0,0,0]),dict(source=[0,1],offset=[0,0,1])]
    before=copy.deepcopy(spec);before['id']+='__baseline';before['model_layout']=[[CAP],[PC],[None]];before['placements']=entity['baseline_placements']
    c.assembly_evidence(before,baseline,art,folder)
    owner=c.OUT;dest=args.out/'ownership-overlays/building-interiors';dest.mkdir(exist_ok=True,parents=True)
    updates=review.read(owner/'updates.json');rows=review.read(owner/'ledger.json');updates['cells'].update(patch)
    reasons={CAP:'Actual Minecraft QA showed a detached white room cornice floating over the PC rim. Preserve the coherent native curved rim at y0..7 and remove the separate wall component at y11..15. Source SOLID7 cap at Institute1F(2,0) now explicitly stacks on the unchanged adjacent shared PC at(2,1), raw147 PC/SOLID.',FOOT:'Source at Institute1F(2,2) is WALK0 below the shared PC. Its cabinet vent is projected from the machine above. Replace the catalog vent/floor block with a one-voxel native-gray floor and enable the existing walk-decor path; collision and the shared PC entry remain unchanged.'}
    selected=[]
    for row in rows:
        if row['key'] not in patch:continue
        key=row['key'];row.update(decision='changed',reason=reasons[key],proposed_entry_sha256=review.entry_hash(patch[key]),visual_status='Source, individual corrections and truthful before/current whole-object views manually inspected; actual-export planner fixture passed; Minecraft recapture pending')
        if key==FOOT:row['label']='Weather Institute terminal floor projection'
        row['evidence']=list(dict.fromkeys(row.get('evidence',[])+['../weather-terminal-correction/source-evidence.json','../weather-terminal-correction/assemblies/emerald_weather_institute_tall_terminal.png']))
        selected.append(copy.deepcopy(row))
    assert len(selected)==2
    for key in (CAP,FOOT):
        render(baseline['cells'][key],art.cell(key,'BAKED'),args.out/'before'/key.split('/')[1]);render(patch[key],art.cell(key,'BAKED'),args.out/'models'/key.split('/')[1])
    write_json(dest/'updates.json',updates);write_json(dest/'ledger.json',rows)
    for name,value in [('updates',dict(cells=patch,buildings={})),('ledger',selected),('source-evidence',proof),('metadata-evidence',[r for r in proof if r['key'] in patch]),('entities',[entity]),('assemblies',[spec]),('dependency',dict(key=PC,entry_sha256=review.entry_hash(current['cells'][PC]),geometry_changed_here=False))]:write_json(args.out/(name+'.json'),value)
    digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
    write_json(args.out/'ownership-overlays.json',dict(owner='building-interiors',canonical_inputs={n:digest(owner/n) for n in ('updates.json','ledger.json')},overlay_outputs={n:digest(dest/n) for n in ('updates.json','ledger.json')},corrected_keys=[CAP,FOOT]))
    print('Two source-bound corrections; shared PC unchanged; one actual three-row terminal assembly')


if __name__=='__main__':main()
