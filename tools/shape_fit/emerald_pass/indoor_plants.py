"""Three source-proven potted crown variants over the reviewed cream pot."""
import argparse
import copy
import hashlib
from pathlib import Path
import numpy as np
from tools.shape_fit.emerald_pass import building_interiors as c,interior_details,review
from tools.shape_fit.blue_pass.common import Model,render
from tools.model_gallery import write_json

KEYS=(
 ('g3q/5774-5775-5776-5777','building__generic_building','Cream-pot crown against the house wall'),
 ('g3q/5774-5775-7599-7600','building__mystery_events_house','Cream-pot crown crossing the wall and carpet'),
 ('g3q/7607-7608-7609-7610','building__mystery_events_house','Freestanding cream-pot crown against carpet'),
)
BASE='g3q/5791-5792-5793-5794'
OUT=c.OUT.parent/'indoor-plant-crowns'


def crown(sprite):
    """Leaf clusters join the existing centered stem, without a room backing."""
    m=Model()
    m.box((7,9),(0,7),(7,9),0)
    for center,radii in [((8,7,8),(6,6,6)),((8,12,8),(3,3,3)),
                         ((4,7,7),(3,4,3)),((12,7,7),(3,4,3)),
                         ((7,7,4),(3,4,3)),((9,7,12),(3,4,3))]:
        m.ellipsoid(center,radii,1)
    y,z,x=np.indices(m.occ.shape)
    leaf=m.paint==1
    light=.6*y-.25*x+.2*z
    m.paint[leaf&(light>5.4)]=2
    m.paint[leaf&(light<2.8)]=0
    # Small irregular native highlights break up the rounded lobes.
    m.paint[leaf&(y>8)&(((x//2+z//3+y//3)%5)==0)]=3
    shades=[c.material(sprite,rgb) for rgb in [(82,123,41),(123,181,66),(181,239,123),(206,255,156)]]
    m.paint=np.asarray(shades,np.uint8)[m.paint]
    return m


def generate(table,art):
    updates={}
    for key,_,_ in KEYS:
        assert table['cells'][key]['on']==BASE
        updates[key]=crown(art.cell(key,'BAKED')).entry(table['cells'][key],source='hand-emerald-indoor-plant-crown')
    return updates


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args()
    table=review.read(review.BASELINE);art=c.EmeraldArt(c.BUNDLE);updates=generate(table,art)
    current=copy.deepcopy(table);current['cells'].update(updates)
    pot_spec=review.read(interior_details.REGISTRY)[BASE]
    current['cells'][BASE]=interior_details.model(art.cell(BASE,'BAKED'),pot_spec).entry(table['cells'][BASE],source='hand-emerald-interior-details')
    args.out.mkdir(parents=True,exist_ok=True);folder=args.out/'assemblies';folder.mkdir(exist_ok=True)
    rows=[];entities=[];specs=[]
    for key,group,label in KEYS:
        inventory=review.read(c.OUT.parent/'inventory'/group/'entries.json')
        original=next(r for r in inventory if r['key']==key)
        location=art.locations[key][0]
        identity='emerald_cream_pot_'+key.split('/')[1].replace('-','_')
        spec=dict(id=identity,kind='stack (model as one)',map=location['map'],rect=[location['x'],location['y'],1,2],source_layout=[[key],[BASE]],note=label+'; rounded foliage sits on the reviewed stem and cream pot, without wall or carpet geometry.')
        specs.append(spec);entities.append(c.assembly_evidence(spec,current,art,folder))
        rows.append(dict(key=key,kind='cell',group=group,label=label,family='potted ornamental tree',decision='changed',baseline_entry_sha256=review.entry_hash(table['cells'][key]),reason='Actual complete-object capture showed room wall/carpet backing retained as crown geometry. Replace it with six joined rounded leaf clusters, native green highlights and a narrow central attachment to the already reviewed pot stem. Preserve the exact source variant and existing on link.',occurrences=art.locations[key],source_contact='../inventory/'+original['contact_page'],source_card=original['contact_position'],assemblies=[identity],visual_status='Source/map and corrected complete object inspected; Minecraft recapture pending',evidence=['source-study.png','source-evidence.json','assemblies/'+identity+'.png']))
        render(updates[key],art.cell(key,'BAKED'),args.out/'models'/key.split('/')[1])
        render(table['cells'][key],art.cell(key,'BAKED'),args.out/'before'/key.split('/')[1])
    write_json(args.out/'updates.json',dict(cells=updates,buildings={}))
    write_json(args.out/'ledger.json',rows);write_json(args.out/'entities.json',entities);write_json(args.out/'assembly-placements.json',specs)
    write_json(args.out/'dependency.json',dict(key=BASE,entry_sha256=review.entry_hash(current['cells'][BASE]),owner='interior-details',geometry_changed_here=False))
    print('3 explicit crowns; 9 source occurrences; existing lower pot unchanged')


if __name__=='__main__':main()
