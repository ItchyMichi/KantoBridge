"""Battle Dome's source-specific potted plants and paired queue barriers."""
from __future__ import annotations
import argparse,base64,copy,hashlib,math
import numpy as np
from tools import model_gallery as g
from tools.shape_fit.blue_pass.common import Model,decode,encode,render
from tools.shape_fit.emerald_pass import review,inventory,building_interiors

OUT=review.ROOT/'local/model-gallery/emerald-pass-study/battle-dome/props'
GROUP='building__battle_dome'
UPPER={31,33,35,37,40,73};LOWER={32,34,36,38,41,74};BARRIERS={25,26,27,28,29,30}
OWNED=UPPER|LOWER|BARRIERS
PAIRS=((31,32),(33,34),(35,36),(35,34),(37,38),(40,41),(73,74))
ROPES=((25,26),(27,28),(29,26),(30,28))


def rows():return review.read(OUT.parent.parent/'inventory'/GROUP/'entries.json')


def material(sprite,rgb,family):
    p=np.asarray(sprite.convert('RGB')).astype(int);r,green,b=np.moveaxis(p,-1,0)
    masks={'green':(green>r+8)&(green>b+20),'neutral':(np.max(p,axis=2)-np.min(p,axis=2)<55),
           'soil':(r>green+10)&(green>b+15),'red':(r>green*1.5)&(r>b*1.5)}
    mask=masks[family];assert mask.any(),family
    distance=((p-np.asarray(rgb))**2).sum(axis=2).astype(float);distance[~mask]=np.inf
    return int(distance.argmin())


def crown(sprite):
    m=Model();dark=material(sprite,(107,148,57),'green');light=material(sprite,(206,239,132),'green');mid=material(sprite,(165,206,82),'green')
    m.cylinder((8,8),1.4,(0,8),dark)
    # Six connected tapered fronds rise from the stem and droop at their tips.
    for ray in range(6):
        angle=ray*math.pi/3
        for step in range(9):
            t=step/8;radius=.7+1.5*math.sin(math.pi*t)
            x=8+math.cos(angle)*6.5*t;z=8+math.sin(angle)*6.5*t;y=5+6*math.sin(math.pi*t*.85)
            m.ellipsoid((x,y,z),(radius,1.5,radius),light if ray in (1,2) else mid)
    m.ellipsoid((8,10,8),(2.2,4.6,2.2),light)
    return m


def pot(sprite):
    m=Model();dark=material(sprite,(82,82,107),'neutral');body=material(sprite,(156,156,173),'neutral');rim=material(sprite,(214,214,231),'neutral')
    soil=material(sprite,(165,132,49),'soil');stem=material(sprite,(107,148,57),'green')
    m.cylinder((8,8),3.8,(0,2),dark).cylinder((8,8),4.8,(2,8),body)
    m.cylinder((8,8),5.8,(8,10),rim).cylinder((8,8),4.4,(9,10),dark)
    m.cylinder((8,8),3.4,(9,10),soil).cylinder((8,8),1.4,(10,16),stem)
    return m


def barrier(sprite,right=False):
    m=Model();dark=material(sprite,(82,82,107),'neutral');light=material(sprite,(239,239,247),'neutral');gray=material(sprite,(156,156,173),'neutral');red=material(sprite,(239,74,33),'red')
    m.cylinder((7,8),3.5,(0,2),dark).cylinder((7,8),2.7,(1,3),gray)
    m.cylinder((7,8),1.3,(2,13),gray).box((6,7),(3,13),(8,9),light).ellipsoid((7,13.4,8),(2,1.6,2),light)
    for x in range(8,16):
        y=9+4*((15-x)/7)**2;m.box((x,x+1),(y,y+2),(7,9),red)
    if right:m.occ=np.flip(m.occ,axis=2).copy();m.paint=np.flip(m.paint,axis=2).copy()
    return m


def generate(table,art):
    entries=rows();updates={}
    for n in sorted(OWNED):
        key=entries[n-1]['key'];sprite=art.cell(key,'BAKED')
        model=crown(sprite) if n in UPPER else pot(sprite) if n in LOWER else barrier(sprite,n in (26,28))
        updates[key]=model.entry(table['cells'][key],source='hand-emerald-battle-dome-props')
    top,base=entries[34]['key'],entries[33]['key']
    assert table['cells'][top]['on']==entries[35]['key']
    updates[top]['alsoOn']=[base]
    return updates


def assemblies(art,entries):
    result=[]
    for a,b in (*PAIRS,*ROPES):
        vertical=a in UPPER;ka,kb=entries[a-1]['key'],entries[b-1]['key'];layout=[[ka],[kb]] if vertical else [[ka,kb]];occ=[]
        for place in art.locations[ka]:
            grid=art.map_cells(art.maps[place['map']]);x,y=place['x'],place['y'];other=grid[y+1][x] if vertical else grid[y][x+1]
            if other==kb:occ.append(place)
        assert occ,(a,b);place=occ[0];ident=f'emerald_dome_{"plant" if vertical else "queue"}_{a}_{b}'
        spec={'id':ident,'kind':'stack potted plant' if vertical else 'paired floor queue barrier','map':place['map'],
              'rect':[place['x'],place['y'],1 if vertical else 2,2 if vertical else 1],'source_layout':layout,
              'note':f'Battle Dome {"potted fern" if vertical else "paired queue posts and sagging red rope"}, source variants {a}/{b}; native background excluded from physical materials.','occurrences':occ}
        if (a,b)==(35,34):
            spec.update(baseline_layout=[[None],[kb]],baseline_placements=[{'source':[0,1],'offset':[0,0,0]}])
        result.append(spec)
    return result


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    OUT.mkdir(parents=True,exist_ok=True);entries=rows();old=review.read(review.BASELINE);art=g.EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');updates=generate(old,art)
    current=copy.deepcopy(old);current['cells'].update(updates);specs=assemblies(art,entries);ledger=[]
    for n in sorted(OWNED):
        r=entries[n-1];key=r['key'];family='potted fern crown' if n in UPPER else 'round gray plant pot' if n in LOWER else 'queue post and rope'
        reason={'potted fern crown':'Six tapered, joined native-green fronds rise from the centered stalk; the blue/green floor and wall pixels remain source background, not a solid crown backing.',
                'round gray plant pot':'A grounded round foot, widening gray body, pale open rim, native soil and centered stalk replace the slab-like pot silhouette, with an exact contact to its own upper crown.',
                'queue post and rope':'The gray post reaches its broad floor foot; the thin red rope slopes to a shared low midpoint at the neighboring source-cell seam, preserving the six actual paired barriers.'}[family]
        if n==35:reason+=' The same WALK crown occurs above two pot variants; add only the second exact lower key as alsoOn, preserving its original primary on relation. Baseline omits this crown over the unmatched second pot; runtime fixture required before freeze.'
        ledger.append({'key':key,'kind':'cell','source_ordinal':n,'label':f'Battle Dome {family}, source {n}','family':family,'decision':'changed',
                       'baseline_entry_sha256':review.entry_hash(old['cells'][key]),'reason':reason,'occurrences':r['occurrences'],
                       'evidence':['../contexts/'+f'page-{(n-1)//16+1:03d}.png','source-evidence.json','assemblies.json'],
                       'visual_status':'Source and existing projections inspected; candidate Python and actual Minecraft review pending'})
    evidence=[]
    for n in sorted(OWNED):
        r=entries[n-1];key=r['key'];places=[]
        for p in art.locations[key]:
            source=art.maps[p['map']];raw=base64.b64decode(source['cells']);grid=art.map_cells(source);x,y=p['x'],p['y']
            places.append(dict(p,collision=raw[(y*source['w']+x)*4+2],south_key=grid[y+1][x],south_collision=raw[((y+1)*source['w']+x)*4+2]))
        evidence.append({'key':key,'ordinal':n,'baseline_entry_sha256':review.entry_hash(old['cells'][key]),'source_pixel_sha256':hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest(),'pixel_mode':'RGB','occurrences':places})
    g.write_json(OUT/'updates.json',{'cells':updates,'buildings':{}});g.write_json(OUT/'preconditions.json',{'cells':{k:old['cells'][k] for k in updates},'buildings':{}});g.write_json(OUT/'ledger.json',ledger);g.write_json(OUT/'source-evidence.json',{'entries':evidence,'assemblies':specs,'runtime_validation':'pending'})
    g.write_json(OUT/'assemblies.json',specs);entities=[];folder=OUT/'assemblies';folder.mkdir(exist_ok=True)
    for spec in specs:
        e=building_interiors.assembly_evidence(spec,current,art,folder);e['occurrences']=spec['occurrences'];entities.append(e)
    g.write_json(OUT/'entities.json',entities)
    if args.previews:
        selected=[copy.deepcopy(entries[n-1]) for n in sorted(OWNED)];inventory.contact_pages('dome-props',selected,current,art,OUT/'contacts')
        for r in selected:render(updates[r['key']],art.cell(r['key'],'BAKED'),OUT/'models'/r['key'].split('/')[1])
    print('Staged18 props,11 source assemblies covering22 plants and6 paired queue barriers; runtime/visual validation pending')


if __name__=='__main__':main()
