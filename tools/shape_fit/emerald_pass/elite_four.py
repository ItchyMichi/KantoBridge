"""Individually reviewed Elite Four wall panels, inset features and hall rails."""
from __future__ import annotations
import argparse,copy,hashlib,json
from collections import Counter
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,decode,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE,INVENTORY
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.center import source_occurrences
from tools.shape_fit.emerald_pass.inventory import contact_pages
from tools.shape_fit.emerald_pass.ship_beams import previews

OUT=ROOT/'local/model-gallery/emerald-pass-study/elite-four'
GROUP='building__elite_four'
KEEP={1,2,3,4,69,70,71,72,73,83,84}
PITS={**{n:'Sidney metal-grille inset' for n in range(7,21)},
      **{n:'Phoebe masonry inset' for n in (*range(21,35),82)},
      **{n:'Glacia ice-panel inset' for n in range(35,49)},
      **{n:'Drake pointed-rock inset' for n in range(49,63)}}
WALK={64,65,74,75,76,77,79,80,81,85}
REMOVE_ON={63:67,66:68}


def rows():
    result=copy.deepcopy(read(INVENTORY/GROUP/'entries.json'))
    for n,r in enumerate(result,1):r['source_ordinal']=n
    return result


def top_paint(m):
    yy,zz,xx=np.indices(m.occ.shape);m.paint[m.occ]=(zz*16+xx)[m.occ]
    return m.occ,m.paint


def inset(art,row,n):
    """One native-painted low relief, defined by its exact chamber location.

    The recessed field has a clipped inner perimeter. Geometry comes from the
    authored outline/ribs/blocks below, never a color-threshold extrusion.
    """
    p=row['occurrences'][0];zrow=p['y']-4
    assert 0<=zrow<=4 and p['x'] in (0,1,11,12)
    # Right features mirror the authored left 32x80 footprint.
    flip=p['x']>=11
    localx=p['x'] if not flip else 12-p['x']
    m=Model();yy,zz,xx=np.indices(m.occ.shape)
    gx=localx*16+(15-xx if flip else xx);gz=zrow*16+zz
    field=(gx<30)&(gz>=8)&(gz<79)
    field&=(gx+np.maximum(0,16-gz)<31)&(gx+np.maximum(0,gz-64)<45)
    # Full source cell remains a two-voxel native top surface; the field is
    # slightly lowered relative to a continuous clipped four-voxel rim.
    m.put(yy<2)
    rim=(~field)&(yy<4);m.put(rim)
    theme=PITS[n]
    if 'grille' in theme:
        slats=field&(gz%4<1)&(yy<3);m.put(slats)
        m.put(field&((gx%8==0)|(gx==29))&(yy<3))
    elif 'masonry' in theme:
        # Offset rectangular native stone faces above a low dark bed.
        blocks=field&((gz%8)<6)&(((gx+(gz//8%2)*4)%12)<10)&(yy<4)
        m.put(blocks)
    elif 'ice-panel' in theme:
        # Four-by-two crisp panels follow the source floor grid.
        m.put(field&(yy<4))
        seams=field&(((gx%16)==0)|((gz%16)==0));m.put(seams&(yy>=2),cut=True)
    else:
        # Repeated pointed stone reliefs, not upright floor-row walls.
        for cx in (6,22):
            for cz in (27,43,59,75):
                radius=np.maximum(0,7-yy*.65)
                m.put(field&((gx-cx)**2+((gz-cz)*.8)**2<radius**2)&(yy<7))
    return top_paint(m)


def lamp(sprite,n):
    m=Model();blue=material(sprite,(66,173,214));pale=material(sprite,(165,214,239));white=material(sprite,(247,247,255))
    if n in (5,6):
        wall=material(sprite,(49,107,148));m.box((0,16),(0,16),(0,4),wall)
        yy,zz,xx=np.indices(m.occ.shape);m.paint[m.occ]=((15-yy)*16+xx)[m.occ]
    m.ellipsoid((8,9,8),(5,3.5,4),blue).cylinder((8,8),(4,3),(4,7),pale)
    m.cylinder((8,8),(3,2.5),(2,4),white).box((7,9),(0,3),(7,9),white)
    return m.occ,m.paint


def rail(sprite,n):
    m=Model();orange=material(sprite,(255,82,33));gold=material(sprite,(255,190,66));shade=material(sprite,(165,82,66))
    lo=8 if n in (63,66) else 0
    m.box((5,11),(0,9),(lo,16),shade).box((4,12),(9,12),(lo,16),orange)
    m.box((5,11),(12,13),(lo,16),gold)
    # Narrow native gray edging continues at both sides of the orange rail.
    gray=material(sprite,(148,156,156));m.box((3,5),(0,2),(lo,16),gray).box((11,13),(0,2),(lo,16),gray)
    return m.occ,m.paint


def landing(sprite,n):
    m=Model();m.box((0,16),(0,2),(0,16));return top_paint(m)


def stairs(sprite,n):
    m=Model();gray=material(sprite,(156,156,148));white=material(sprite,(214,214,222))
    for z in range(16):
        # A shallow native stair surface preserves the map's independent floor
        # heights; no invented ten-block vertical rise across the room.
        h=1+(15-z)//4
        m.box((0,16),(0,h),(z,z+1),white if z%4<2 else gray)
    if n in (74,77):m.box((0,1),(0,5),(0,16),white)
    if n in (76,79):m.box((15,16),(0,5),(0,16),white)
    return m.occ,m.paint


def arch(sprite):
    m=Model();edge=material(sprite,(49,107,148));light=material(sprite,(115,189,230))
    m.box((1,15),(10,16),(5,10),edge).box((2,14),(13,16),(5,10),light)
    m.box((1,3),(8,14),(5,10),edge).box((13,15),(8,14),(5,10),edge)
    return m.occ,m.paint


def generate(baseline,art,owned):
    updates={};ledger=[];meta=[];keys={r['source_ordinal']:r['key'] for r in owned}
    for r in owned:
        n=r['source_ordinal'];key=r['key'];old=baseline['cells'][key];sprite=art.cell(key,'BAKED');new=copy.deepcopy(old)
        if n in KEEP:
            if n==84:
                label='Unused open-hall doorway fallback';reason='No static exported occurrence exists. The native source is a dark doorway field with a pale bottom edge and the existing walk-enabled thin warp surface preserves it. Retain this fallback explicitly without inventing a placed whole doorway or claiming map runtime QA.'
            elif n==69:
                label='Champion upper mosaic wall panel'
                reason='Exact source places this gray mosaic wall panel above the Champion arch. Its existing full structural cuboid preserves the native panel and small pale top vent. Keep this architectural wall background; it is not an independent prop or a stand-in for the complete arch below.'
            else:
                label='Complete-template-covered arch source '+str(n)
                reason='Source and whole preview show that these projected arch quarters must be interpreted together. Their individual baseline floor arrangement is not a coherent upright arch, so do not approve them as standalone objects. Preserve these fallback entries because two exact normalized open-state interior templates cover all ten actual arches and every actual occurrence of these nine source keys. Blue room upper centers become WALK in G3Built while halls remain SOLID; each occurrence is pinned separately. The open center warp is preserved. Closed leaves remain the native per-player story overlay, outside this fitted model approval. Actual template/interface fixtures and native baseline companions remain required before publication.'
            decision='retained'
        else:
            if n in PITS:
                o,p=inset(art,r,n);label=PITS[n]+' section '+str(n);family=PITS[n]
                reason='The full room shows a clipped inset running along the floor edge. Author the exact source-position rim and connected low relief: grating ribs, offset masonry, ice seams or pointed stone clusters. Native paint follows the horizontal source layout. Avoid interpreting each projected row as a separate tall wall; preserve every SOLID source kind and the complete five-row footprint.'
            elif n in (5,6,85):
                o,p=lamp(sprite,n);label='Cyan hall lamp ornament '+str(n);family='Elite cyan lamps'
                reason='Source shows the same cyan bulb, pale lower bowl and white point. Model rounded lamp tiers with native source colors; wall variants retain their blue backing at the north face, while the hall-side variant has no invented wall or pole. The hall-side source is WALK0 and receives an exact decorative display flag.'
            elif n in (63,66,67,68):
                o,p=rail(sprite,n);label='Orange stair rail '+('start' if n in (63,66) else 'repeat')+' '+str(n);family='Elite hall stair rails'
                reason='Exact hall source shows continuous orange rails running north-to-south beside the long stair. Use one constant-height orange rail with gold top and narrow gray foot; start cells stop at the northern cap. Remove only the two cap-to-repeat vertical links, which otherwise elevate the first section above the floor-depth run. Preserve all collision and material sources.'
            elif n in (64,65,80,81):
                o,p=landing(sprite,n);label='Pale stair landing and dotted lip '+str(n);family='Elite stair landings'
                reason='This pale dotted landing lies horizontally at the head of the actual stair. Keep native source paint on a shallow continuous two-voxel surface and enable decorative WALK display; preserve map floor heights and source collision.'
            elif 74<=n<=79:
                o,p=stairs(sprite,n);label='Champion shallow stair surface '+str(n);family='Champion stairs'
                reason='The whole Champion source has a three-wide stair continuing over three source rows. Author consistent shallow pale treads and the exact outer white side edge. These are WALK0 surfaces; retain the existing walk-enabled middle and enable only its five missing companions. Do not infer a vertical stack or alter room floor elevations.'
            else:
                assert n==83;o,p=arch(sprite);label='Open hall doorway blue lintel';family='Elite hall arch'
                reason='Exact source is the shallow blue arched lintel over the open hall passage. Model the lintel and short side returns using native blue material; preserve the existing source kind and the open lower span.'
            new=encode(o,p,old,'hand-emerald-elite-four-review');changes={}
            if n in REMOVE_ON:
                assert old.get('on')==keys[REMOVE_ON[n]];changes['on']={'old':new.pop('on'),'new':None}
            if n in WALK:
                assert not old.get('walk');new['walk']=True;changes['walk']={'old':None,'new':True}
            for field,change in changes.items():
                meta.append({'key':key,'field':field,'old_value':change['old'],'new_value':change['new'],
                             'baseline_entry_sha256':entry_hash(old),'proposed_entry_sha256':entry_hash(new),
                             'source_pixel_mode':'RGBA','source_pixel_sha256':hashlib.sha256(sprite.tobytes()).hexdigest(),
                             'occurrences':source_occurrences(art,key),'reason':reason})
            updates[key]=new;decision='changed'
        ledger.append({'key':key,'kind':'cell','label':label,'family':family if n not in KEEP else 'Elite architectural panel',
                       'decision':decision,'reason':reason,'baseline_entry_sha256':entry_hash(old),
                       'occurrences':source_occurrences(art,key),'evidence':[str(OUT/'source-evidence.json'),str(OUT/'preview/current/index.json')]})
    return updates,ledger,meta


def assemblies(art,owned,current,baseline):
    keys={r['source_ordinal']:r['key'] for r in owned};allowed=set(keys.values());found={}
    def add(mapid,x,y,w,h,label):
        grid=art.map_cells(art.maps[mapid]);reference=[r[x:x+w] for r in grid[y:y+h]]
        layout=[[k if k in allowed else None for k in line] for line in reference]
        def placements(table):
            present={}
            for zz,line in enumerate(layout):
                for xx,k in enumerate(line):
                    if not k:continue
                    row=next(p for p in source_occurrences(art,k) if p['map']==mapid and p['x']==x+xx and p['y']==y+zz)
                    e=table['cells'][k]
                    if row['collision']==7 or any(e.get(f) for f in ('walk','seat','on')):present[(xx,zz)]=k
            links={p:(p[0],p[1]+1) for p,k in present.items() if table['cells'][k].get('on')==present.get((p[0],p[1]+1)) and table['cells'][k].get('on')}
            ps=[]
            for start in sorted(set(present)-set(links.values())):
                chain=[start]
                while chain[-1] in links:chain.append(links[chain[-1]])
                end=chain[-1]
                for level,pos in enumerate(reversed(chain)):ps.append({'source':list(pos),'offset':[end[0],level,end[1]]})
            shown={tuple(p['source']) for p in ps}
            return ps,[[k if (xx,zz) in shown else None for xx,k in enumerate(line)] for zz,line in enumerate(layout)]
        now,shown=placements(current);before,old=placements(baseline)
        sig=hashlib.sha256(json.dumps([shown,now,before],separators=(',',':')).encode()).hexdigest()[:12]
        found.setdefault(sig,{'id':'emerald_elite_four_'+sig,'kind':'object (model as one)','note':label,
                             'layout':shown,'reference_layout':reference,'placements':now,'baseline_layout':old,
                             'baseline_placements':before,'occurrences':[]})['occurrences'].append({'map':mapid,'x':x,'y':y})
    for room in ('SIDNEYS','PHOEBES','GLACIAS','DRAKES'):
        mapid='EM_EVER_GRANDE_CITY_'+room+'_ROOM'
        add(mapid,0,4,2,5,room.title()+' complete left inset')
        add(mapid,11,4,2,5,room.title()+' complete right inset')
    for n in (5,6):
        p=art.locations[keys[n]][0];add(p['map'],max(0,p['x']-1),p['y'],3,1,'Complete cyan wall lamp row')
    for n in (63,66):
        for p in art.locations[keys[n]]:
            grid=art.map_cells(art.maps[p['map']]);end=p['y']+1
            while end<len(grid) and grid[end][p['x']]==keys[REMOVE_ON[n]]:end+=1
            add(p['map'],p['x'],p['y'],1,end-p['y'],'Complete continuous orange stair rail')
    add('EM_EVER_GRANDE_CITY_CHAMPIONS_ROOM',5,10,3,3,'Complete Champion shallow three-wide stair')
    add('EM_EVER_GRANDE_CITY_CHAMPIONS_ROOM',5,3,3,1,'Complete pale Champion upper landing')
    # Upright complete arches are source-pinned templates from elite_arches.py.
    # Do not publish these source quarters as an incorrectly deep floor object.
    p=art.locations[keys[85]][0];add(p['map'],p['x'],p['y'],1,1,'Standalone cyan hall-side ornament')
    return list(found.values())


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    art=EmeraldArt(BUNDLE);baseline=read(BASELINE);owned=rows();updates,ledger,meta=generate(baseline,art,owned)
    current=read(ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json');current['cells'].update(updates)
    entities=assemblies(art,owned,current,baseline)
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('metadata-evidence.json',meta),('entities.json',entities),
                       ('source-evidence.json',{'sources':[{'ordinal':r['source_ordinal'],'key':r['key'],'occurrences':source_occurrences(art,r['key'])} for r in owned],
                        'stair_runtime_proof':'local/model-gallery/emerald-pass-study/generic-elite/stair-runtime-proof.json',
                        'limits':'Local previews and exact source review only; actual Minecraft visual QA remains pending. Actual exported-map stair fixture verifies flat_one_level, no native ramps/pins and only previously walk-enabled ordinal78 rendered. Exact ten missing walk flags are justified; arch native baselines/interface fixture are recorded separately.'})]:write_json(OUT/name,value)
    if args.previews:
        contact_pages('current',owned,current,art,OUT/'preview');previews(entities,current,art,OUT/'preview/assemblies')
    print(Counter(r['decision'] for r in ledger),len(entities),'complete/context assemblies')


if __name__=='__main__':main()
