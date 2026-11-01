"""Thirty-four explicitly assigned shared interior keys and one missing bench cap.

Source contact positions and reconstructed map neighborhoods were inspected
before these parts were authored. Does not write the live fitted table.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json

import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import Model, encode
from tools.shape_fit.emerald_pass.coastal import ROOT, BASELINE, BUNDLE, INVENTORY
from tools.shape_fit.emerald_pass.environment import assembly_previews, closest_uv
from tools.shape_fit.emerald_pass.interior_furniture import chair as existing_chair
from tools.shape_fit.emerald_pass.inventory import contact_pages, compact_context_pages
from tools.shape_fit.emerald_pass.review import entry_hash, read
from tools.shape_fit.emerald_pass.vegetation import color_uv

OUT=ROOT/'local/model-gallery/emerald-pass-study/shared-furniture'
GRAVES=(381,382,417,514)
CABLE=(396,405,413)
SOFA=(429,430,431,435,436,437)
RAILS=(233,426,427,432,433,438,439,440,456,457)
ORANGE=(469,474,481,517,522,525,527,529,531,532,541)
POSITIONS=tuple(sorted((*GRAVES,*CABLE,*SOFA,*RAILS,*ORANGE)))
CAP='g3q/5056-5057-4992-4991'
ORANGE_MIDDLE='g3q/1201-1202-1201-1202'
RAIL_CORNER='g3q/4871-4872-4873-4824'
BENCH_PARTS={469:(0,False),474:(1,False),481:(2,False),517:(0,True),522:(0,False),527:(2,True),531:(2,False)}


def inventory_rows():
    allrows=read(INVENTORY/'shared-building/entries.json')
    rows=[allrows[n-1] for n in POSITIONS]
    assert all(r['contact_position']==n for r,n in zip(rows,POSITIONS))
    assert len(rows)==34
    return rows


def grave(sprite):
    dark=closest_uv(sprite,(66,74,107));body=closest_uv(sprite,(165,173,222))
    rim=closest_uv(sprite,(214,222,247));white=color_uv(sprite,(255,255,255))
    m=Model()
    m.box((2,14),(0,2),(3,14),dark)
    m.box((3,13),(2,4),(4,13),rim)
    m.box((4,12),(4,6),(5,12),body)
    m.box((4,12),(5,14),(4,7),body)
    m.box((5,11),(14,15),(4,7),rim)
    m.box((5,11),(8,13),(7,8),white)
    m.box((5,11),(6,7),(7,10),rim)
    return m


def cable_bench(sprite,part):
    """Native green seating; part 2 ends one bench and starts the next."""
    green=color_uv(sprite,(107,222,115));edge=color_uv(sprite,(74,181,99))
    pale=color_uv(sprite,(189,239,181));frame=color_uv(sprite,(66,74,107))
    intervals={0:((12,16),),1:((0,16),),2:((0,4),(12,16)),3:((0,4),)}[part]
    m=Model()
    for z0,z1 in intervals:
        m.box((3,14),(5,8),(z0,z1),green)
        m.box((1,4),(5,14),(z0,z1),edge)
        m.box((2,4),(9,13),(z0,z1),pale)
        if part in (0,2) and z0==12:m.box((3,14),(8,11),(12,14),frame)
        if part in (2,3) and z0==0:m.box((3,14),(8,11),(2,4),frame)
    if part==1:
        for x in ((2,4),(11,13)):
            for z in ((3,5),(11,13)):m.box(x,(0,5),z,frame)
        m.box((4,13),(7,8),(7,8),edge)
    return m


def sofa_parts():
    # Paint contains material tags until each whole-object slice is remapped.
    m=Model(height=24,width=48,depth=32)
    m.box((2,46),(5,8),(11,29),0)
    m.box((2,46),(8,10),(15,28),1)
    m.box((2,46),(8,20),(10,14),0)
    m.box((4,44),(10,19),(13,15),1)
    for x in ((1,5),(43,47)):m.box(x,(8,15),(11,29),2)
    for x in ((3,5),(23,25),(43,45)):
        for z in ((11,13),(26,28)):m.box(x,(0,5),z,3)
    for x in (16,32):m.box((x,x+1),(9,10),(15,28),0)
    return m


def sofa_slice(sprite,n):
    m=sofa_parts();index=SOFA.index(n);x,z=index%3,index//3
    selection=(slice(None),slice(z*16,z*16+16),slice(x*16,x*16+16))
    palette=np.asarray([color_uv(sprite,c) for c in ((66,74,107),(99,99,123),(115,123,165),(49,49,74))],np.uint8)
    return m.occ[selection],palette[m.paint[selection]]


def single_chair(sprite,mirror):
    cloth=color_uv(sprite,(255,99,49));edge=color_uv(sprite,(222,74,33));frame=color_uv(sprite,(82,82,107))
    m=Model()
    m.box((3,14),(5,8),(3,14),cloth)
    m.box((1,4),(5,14),(3,14),edge)
    m.box((2,4),(8,13),(4,13),cloth)
    for z in ((3,5),(12,14)):
        m.box((3,14),(8,11),z,edge)
        for x in ((2,4),(11,13)):m.box(x,(0,5),z,frame)
    if mirror:m.occ=m.occ[:,:,::-1].copy();m.paint=m.paint[:,:,::-1].copy()
    return m


def railing(sprite,n):
    dark=color_uv(sprite,(66,74,107));gray=color_uv(sprite,(140,148,165));light=color_uv(sprite,(222,222,222))
    m=Model()
    def horizontal(x0,x1):
        m.box((x0,x1),(11,13),(7,9),light);m.box((x0,x1),(3,5),(7,9),gray)
        for x in range(x0+1,x1,5):m.box((x,min(x+2,x1)),(0,12),(7,9),dark)
    def vertical(z0,z1):
        m.box((7,9),(11,13),(z0,z1),light);m.box((7,9),(3,5),(z0,z1),gray)
        for z in range(z0+1,z1,5):m.box((7,9),(0,12),(z,min(z+2,z1)),dark)
    if n==427:vertical(0,16)
    elif n==426:horizontal(0,9);vertical(8,16)
    elif n==432:horizontal(7,16);vertical(8,16)
    elif n==233:horizontal(7,16);vertical(0,9)
    else:horizontal(0,16)
    return m


def generate(table,art,rows):
    updates={};ledger=[]
    for index,row in enumerate(rows):
        n,key=row['contact_position'],row['key'];old=table['cells'][key];sprite=art.cell(key,'BAKED')
        assert entry_hash(old)==row['baseline_entry_sha256']
        assert hashlib.sha256(sprite.tobytes()).hexdigest()==row['sprite_sha256']
        if n in GRAVES:
            m=grave(sprite);occ,paint=m.occ,m.paint
            label,family='Mt Pyre grave marker and stepped plinth','grave marker'
            reason='Source neighborhoods show individual grave markers in Mt Pyre, with a pale upright memorial and two low plinth steps. Author those solid parts, a small pale front inset and native stone backs; exclude surrounding mosaic-floor volume.'
        elif n in CABLE:
            m=cable_bench(sprite,CABLE.index(n));occ,paint=m.occ,m.paint
            label,family='Cable-station green bench section','cable bench'
            reason='Both cable-station maps place two green benches in a five-cell north/south source column. This section is an upper cap, main seat or the separated end/start caps. Use a supported seat, narrow raised back and end arms; omit gray floor, yellow caution stripe and railing backing. The missing final lower cap is proposed separately.'
        elif n in SOFA:
            occ,paint=sofa_slice(sprite,n)
            label,family='Devon president office sofa section','Devon sofa'
            reason='Devon Corp 3F (8..10,1..2) contains one three-by-two navy sofa against the wall. Build its backrest, seat cushions, raised arms and supported legs as one 48-by-32 model, then slice into six cells. Wallpaper, wooden trim and red carpet are excluded from upholstery.'
        elif n in RAILS:
            m=railing(sprite,n);occ,paint=m.occ,m.paint
            label,family='Harbor or Space Center railing section','metal railing'
            reason='Exact harbor/Space Center neighborhoods identify horizontal runs, vertical runs or corner turns of an open guardrail. Model rooted posts and two thin rails with air between them, using native metal colors; retain every matching and interaction field. The lower-left corner joins both the north vertical run and east horizontal run in the complete harbor layout.'
        elif n in BENCH_PARTS:
            section,mirror=BENCH_PARTS[n];occ,paint=existing_chair(sprite,'bench',section,mirror)
            label,family='Orange lounge bench section','orange seating'
            reason='Exchange Corner/lounge maps identify a west- or east-backed bench end or repeat section. Reuse the already authored whole-bench construction and slicing convention so these variants join shared-building ordinal103 without a seam. Upholstery and dark native edges replace the blue-carpet extrusion; original seat flags are preserved.'
        else:
            m=single_chair(sprite,n in (525,529));occ,paint=m.occ,m.paint
            label,family='Orange lounge armchair','orange seating'
            reason='Lounge map context shows a complete single-cell armchair facing toward the room. Give it a raised side backrest, seat cushion, armrests and four grounded legs; source blue pixels are carpet and are excluded. Preserve the original seat flag.'
        updates[key]=encode(occ,paint,old,source='hand-emerald-shared-furniture',trim=True)
        place=row['occurrences'][0]
        ledger.append({'key':key,'kind':'cell','label':label,'family':family,'decision':'changed','baseline_entry_sha256':entry_hash(old),
            'reason':reason+f" Inspected source context: {place['map']} ({place['x']},{place['y']}); original {row['voxels']} voxels and bounds {row['occupied_bounds_yzx']}.",
            'occurrences':row['occurrences'],'evidence':[str(OUT/'selected-source'/f'page-{index//32+1:03d}.png'),str(OUT/'source-context'/f'page-{index//16+1:03d}.png')]})
    assert CAP not in table['cells']
    cap_sprite=art.cell(CAP,'BAKED');m=cable_bench(cap_sprite,3)
    cap=m.entry({'class':'voxels','seat':True},source='hand-emerald-cable-bench-cap')
    added={'key':CAP,'kind':'cell','label':'Cable-station lower bench cap','family':'cable bench','decision':'added','baseline_absent':True,
        'baseline_entry_sha256':None,'proposed_entry_sha256':entry_hash(cap),'source_pixel_sha256':hashlib.sha256(cap_sprite.tobytes()).hexdigest(),'occurrences':art.locations[CAP],
        'reason':'Both cable stations have the exact five-row seat layout at x0,y7..11. The existing fitted family stops before the lower green end cap at y11. Add only the native-green seat/back/arm slice in z0..4, leaving the remaining twelve rows empty and blue-gray floor unmodeled. Original neighboring seat sections all carry seat:true; this completes that same seat object.',
        'evidence':['EM_MT_CHIMNEY_CABLE_CAR_STATION (0,11)','EM_ROUTE112_CABLE_CAR_STATION (0,11)',str(OUT/'source-context/page-001.png')]}
    return updates,ledger,{CAP:cap},[added]


def assemblies_for(art,rows):
    lookup={r['contact_position']:r['key'] for r in rows}
    families={'Devon sofa':{lookup[n] for n in SOFA},'Cable-station bench pair':{lookup[n] for n in CABLE}|{CAP},
              'Orange lounge seating row':{lookup[n] for n in ORANGE}|{ORANGE_MIDDLE},
              'Metal guardrail run':{lookup[n] for n in RAILS}|{RAIL_CORNER}}
    found={}
    for name,source in art.maps.items():
        grid=art.map_cells(source)
        for family,keys in families.items():
            unseen={(x,y) for y,line in enumerate(grid) for x,key in enumerate(line) if key in keys}
            while unseen:
                component=set();todo=[min(unseen)]
                while todo:
                    x,y=todo.pop()
                    if (x,y) not in unseen:continue
                    unseen.remove((x,y));component.add((x,y));todo.extend(((x-1,y),(x+1,y),(x,y-1),(x,y+1)))
                if len(component)<2:continue
                x0,x1=min(x for x,y in component),max(x for x,y in component)+1;y0,y1=min(y for x,y in component),max(y for x,y in component)+1
                layout=[[grid[y][x] if (x,y) in component else None for x in range(x0,x1)] for y in range(y0,y1)]
                identifier='emerald_shared_furniture_'+hashlib.sha256(json.dumps(layout,separators=(',',':')).encode()).hexdigest()[:12]
                item=found.setdefault(identifier,{'id':identifier,'kind':'object (model as one)','note':family,'layout':layout,'occurrences':[]})
                item['occurrences'].append({'map':name,'x':x0,'y':y0})
    return [{k:v for k,v in item.items() if k!='occurrences'} for item in found.values()],list(found.values())


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=type(OUT),default=OUT);args=ap.parse_args()
    table=read(BASELINE);art=EmeraldArt(BUNDLE);rows=inventory_rows();updates,ledger,added,added_ledger=generate(table,art,rows)
    write_json(args.out/'ownership.json',{'owner':'emerald_scenery','positions':list(POSITIONS),'keys':[r['key'] for r in rows]})
    compact_context_pages(rows,art,args.out/'source-context')
    contact_pages('selected-source',[copy.deepcopy(r) for r in rows],table,art,args.out)
    entities,placements=assemblies_for(art,rows)
    for name,data in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('additions.json',{'cells':added,'buildings':{}}),('added-ledger.json',added_ledger),('entities.json',entities),('assembly-placements.json',placements)]:write_json(args.out/name,data)
    current=copy.deepcopy(table);current['cells'].update(updates);current['cells'].update(added)
    current['cells'][ORANGE_MIDDLE]=read(ROOT/'local/model-gallery/emerald-pass-study/interior-furniture/updates.json')['cells'][ORANGE_MIDDLE]
    contact_pages('current',[copy.deepcopy(r) for r in rows],current,art,args.out/'preview')
    contact_pages('added-cap',[{'key':CAP,'occurrences':art.locations[CAP],
        'maps':sorted({p['map'] for p in art.locations[CAP]}),'building_source_membership':[]}],current,art,args.out/'preview')
    assembly_previews(entities,current,art,args.out/'preview/assemblies')
    print(f'{len(updates)} revised entries, one proposed cap addition, {len(entities)} exact source assemblies')


if __name__=='__main__':main()
