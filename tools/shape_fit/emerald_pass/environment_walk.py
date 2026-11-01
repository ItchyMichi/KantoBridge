"""Exact walkable staircase display amendments; geometry is unchanged."""
import argparse
import base64
import copy
import hashlib

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.emerald_pass import environment
from tools.shape_fit.emerald_pass.inventory import contact_pages, compact_context_pages
from tools.shape_fit.emerald_pass.review import read, entry_hash

OUT=environment.ROOT/'local/model-gallery/emerald-pass-study/environment-walk'
KEYS=environment.WALK_STEPS_KEYS


def generate(baseline,art):
    updates={};ledger=[];proof=[]
    for key in KEYS:
        old=baseline['cells'][key]
        assert not any(old.get(n) for n in ('walk','seat','on'))
        new=environment.steps(art.cell(key,'BAKED')).entry(old,source='hand-emerald-environment-steps',trim=True)
        new['walk']=True;updates[key]=new;places=[]
        for p in art.locations[key]:
            m=art.maps[p['map']];raw=base64.b64decode(m['cells']);kind=raw[(p['y']*m['w']+p['x'])*4+2]
            assert kind in (0,43),(key,p,kind)
            places.append(dict(p,export_collision=kind,scene_kind='WALK'))
        reason=('Exact source/map inspection shows a stone staircase. Preserve the authored four-tread geometry and add walk:true so Units.walkUnit emits it on noncolliding decor carriers. '
                'All source collisions are WALK0 or CAVE43, both mapped to WALKABLE by G3Cells.flags. Actual MapLayout.ramp handling runs before this fallback and retains precedence. '
                'Source collision, warp tags, floor height and navigation remain unchanged.')
        ledger.append({'key':key,'kind':'cell','label':'Walkable stone staircase','family':'stairs','decision':'changed','reason':reason,
                       'baseline_entry_sha256':entry_hash(old),'occurrences':art.locations[key],
                       'evidence':[str(OUT/'source/page-001.png'),str(OUT/'source-context/page-001.png'),str(OUT/'metadata-evidence.json')]})
        proof.append({'key':key,'baseline_entry_sha256':entry_hash(old),'proposed_entry_sha256':entry_hash(new),
                      'old_walk':None,'walk':True,'source_pixel_sha256':hashlib.sha256(art.cell(key,'BAKED').tobytes()).hexdigest(),'occurrences':places})
    assert sum(len(p['occurrences']) for p in proof)==118
    return updates,ledger,proof


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=type(OUT),default=OUT);args=ap.parse_args()
    art=EmeraldArt(environment.BUNDLE);baseline=read(environment.BASELINE);updates,ledger,proof=generate(baseline,art)
    current=read(environment.ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json')
    for key,new in updates.items():
        assert all(new.get(n)==current['cells'][key].get(n) for n in ('v','t','up')),'unexpected live geometry '+key
    rows=[r for r in read(environment.INVENTORY/'inventory.json')['entries'] if r['key'] in KEYS]
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('metadata-evidence.json',proof)]:write_json(args.out/name,value)
    contact_pages('source',copy.deepcopy(rows),baseline,art,args.out)
    current['cells'].update(updates);contact_pages('current',copy.deepcopy(rows),current,art,args.out/'preview')
    compact_context_pages(rows,art,args.out/'source-context')
    print('7 exact walk metadata amendments; 118 source positions; authored geometry unchanged')


if __name__=='__main__':main()
