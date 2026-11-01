"""The unique Route119 Weather Institute: rounded glazed wings and radar roof."""
from __future__ import annotations
import argparse
import base64
import hashlib
from pathlib import Path
import numpy as np
from tools.model_gallery import EmeraldArt,building_catalog,write_json
from tools.shape_fit.emerald import building_trials as p,parts_model
from tools.shape_fit.emerald_pass import review
from tools.shape_fit.emerald_pass.buildings import ROOT,BUNDLE
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters
from tools.shape_fit.emerald_pass.buildings_west import box,material

OUT=ROOT/'local/model-gallery/emerald-pass-study/building-weather-institute'
ID='emerald_b130_EM_ROUTE119'
LAYOUT=review.read(Path(__file__).with_name('weather_institute_layout.json'))
SOURCE_SHA256='582657937e14e5c20ba237af4fb82f0cc17fc40ef911c7f1ed222c7c22bb0f0d'
# The rear source row combines the projected roof ridge/dome tips and the cliff.
# Keep that cliff in the map; the full roof is supported inside z>=16.
TERRAIN=[[x,0] for x in range(11)]+[[0,5],[0,6],[1,6],[9,6],[10,6]]


def generate():
    sand=material((49,5,50,6));cream=material((49,4,50,5))
    dark=material((48,4,49,5));trim=material((48,3,49,4))
    white=material((29,9,30,10));pale=material((27,10,28,11))
    parts=[]
    # Two round, two-storey glazed wings. Their native cyan window bands wrap
    # their forward halves; the rear uses a plain sand wall material.
    for cx,rect in [(32,[14,54,40,72]),(144,[136,54,162,72])]:
        # The southwest source wing is partly obscured by independent forest.
        # Shorten its physical forward depth before meshing, rather than cutting
        # a rectangular notch out of the roof where those tree cells are kept.
        cz,depth=(50,34) if cx==32 else (55,41)
        for low,high in [(0,29),(32,61)]:
            # The lower drawing overlaps forest and grass at its corners. Both
            # physical storeys instead use the same clean native glass course.
            paint=p.paint(rect,[13,49,14,50],[13,49,14,50],[49,5,50,6])
            parts.append(p.round_part('cylinder',[cx,cz],[27,depth],[low,high],paint))
        for low,high in [(0,2),(29,33),(60,64)]:
            parts.append(p.round_part('cylinder',[cx,cz],[28,depth+1],[low,high],sand))
    # Central hall and front colonnade have their own physical frontage, rather
    # than stretching the curved window strips through the entrance.
    parts.extend([
        box((32,144),(16,96),(0,63),sand),
        box((48,128),(94,108),(0,60),p.paint([48,54,128,112],[58,60,60,62],[58,60,60,64],[58,60,60,64])),
        box((32,144),(16,98),(63,67),cream),
        box((46,130),(96,111),(59,64),sand),
    ])
    for cx in (32,144):
        cz,depth=(50,35) if cx==32 else (55,43)
        parts.append(p.round_part('cylinder',[cx,cz],[30,depth],[63,67],cream))
    for x in (48,72,96,122):
        parts.append(box((x,x+6),(105,111),(0,61),sand))
        parts.append(box((x,x+6),(105,112),(0,2),trim))
    # Source warp is local (5,6): a real recessed central entrance and shallow
    # threshold are inside that existing non-solid carrier, with no new wall.
    parts.extend([
        box((80,96),(90,112),(0,33),carve=True),
        box((80,96),(90,91),(1,33),dark),
        box((80,96),(91,112),(0,1),trim),
        box((80,96),(91,109),(1,2),trim),
        box((80,96),(108,109),(33,38),p.paint([80,89,96,93],[83,98,84,99],[83,98,84,99])),
    ])
    # The roof's repeated shallow vents are separate supported hood frames.
    # Open south faces expose dark recesses beneath cream lintels.
    for z in (23,35,47,59,71,83):
        for x in (49,59,69,79,89,99,109,119):
            parts.extend([
                box((x,x+9),(z,z+4),(67,70),sand),
                box((x+1,x+8),(z+2,z+5),(67,69),carve=True),
                box((x+1,x+8),(z+1,z+2),(67,69),dark),
                box((x,x+9),(z,z+4),(69,70),cream),
            ])
    # The two large weather radomes and four smaller tan rooftop instruments
    # stand on circular bases connected to the roof, with no source cliff paint.
    for cx in (32,144):
        parts.extend([
            p.round_part('cylinder',[cx,43],[18,18],[67,70],sand),
            p.round_part('dome',[cx,43],[16,16],[70,90],p.paint([29,9,30,10],[29,9,30,10],[27,10,28,11],[27,10,28,11])),
        ])
        direction=1 if cx==32 else -1
        for x,z,r in [(cx-13*direction,70,8),(cx+10*direction,75,6)]:
            parts.extend([
                p.round_part('cylinder',[x,z],[r+1,r+1],[67,69],trim),
                p.round_part('dome',[x,z],[r,r],[69,77 if r==8 else 75],pale),
            ])
    return {'id':ID,'atlas':'g3q','tiles':quarters(LAYOUT),'parts':parts,'height':96,'terrain':TERRAIN}


def evidence(art,entry):
    places=building_catalog({'game':'emerald','cells':{},'buildings':[entry]},art)[0]['occurrences']
    assert len(places)==1 and (places[0]['map'],places[0]['x'],places[0]['y'])==('EM_ROUTE119',1,26)
    result=[]
    for place in places:
        m=art.maps[place['map']];raw=base64.b64decode(m['cells']);cells=[]
        for z,row in enumerate(LAYOUT):
            for x,key in enumerate(row):
                mx,my=place['x']+x,place['y']+z;i=(my*m['w']+mx)*4
                cells.append(dict(x=mx,y=my,local=[x,z],key=key,metatile=int.from_bytes(raw[i:i+2],'little'),collision=raw[i+2],elevation=raw[i+3],terrain=[x,z] in TERRAIN))
        result.append(dict(place,width=11,depth=7,cells=cells,map_cells_sha256=hashlib.sha256(raw).hexdigest(),warps=[v for v in m['warps'] if place['x']<=v['x']<place['x']+11 and place['y']<=v['y']<place['y']+7]))
    return result


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    args.out=args.out.resolve()
    art=EmeraldArt(BUNDLE);entry=generate();source=p.drawing(entry,BUNDLE)
    assert hashlib.sha256(source.tobytes()).hexdigest()==SOURCE_SHA256
    assert all(v['id']!=ID and v['tiles']!=entry['tiles'] for v in review.read(review.BASELINE)['buildings'])
    places=evidence(art,entry);args.out.mkdir(parents=True,exist_ok=True);source.save(args.out/'source.png')
    if args.previews:
        occ,colors=parts_model.build(entry,np.asarray(source))
        parts_model.picture(occ,colors,args.out/'view.png',3)
        parts_model.front_view(occ,colors,args.out/'front.png',4)
        parts_model.front_view(np.transpose(occ,(0,2,1))[:,:,::-1],np.transpose(colors,(0,2,1,3))[:,:,::-1],args.out/'side.png',4)
    relative=args.out.relative_to(ROOT).as_posix()
    row=dict(key=ID,kind='building',label='Route119 Weather Institute',family='weather-research-institute',decision='added',baseline_absent=True,baseline_entry_sha256=None,proposed_entry_sha256=review.entry_hash(entry),source_pixel_sha256=SOURCE_SHA256,source_pixel_mode='RGB',occurrences=places,reason='The unique 11x7 source pattern and its existing Weather Institute warp are absent from the original112 building templates. Author two rounded two-storey glazed wings, a central hall with four front columns, recessed entrance, roof vent hoods, two white radomes and four tan instruments. Keep the northern projected roof/cliff row, southwest forest corner and two southeast WALK ground cells as terrain. All physical architecture is supported inside the remaining footprint.',evidence=[{'source':relative+'/source.png','context':'local/model-gallery/emerald-pass-study/towns-fortree/weather-institute-context.png','source_proof':relative+'/occurrences.json'},{'python_front':relative+'/front.png','python_side':relative+'/side.png','python_angled':relative+'/view.png'}],visual_status='Source inspected; Python and Minecraft QA pending',user_approved=False)
    for name,value in [('updates',dict(cells={},buildings={ID:entry})),('added-ledger',[row]),('occurrences',places),('spec',entry)]:write_json(args.out/(name+'.json'),value)
    print(f'{ID}: {len(entry["parts"])} explicit parts; {len(places)} exact occurrence')


if __name__=='__main__':main()
