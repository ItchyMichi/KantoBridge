"""Source-audited Emerald vegetation variants and complete garden objects.

Uses explicit parts and source-local paint. Existing interaction/stack flags are
preserved. The broadleaf extensions reuse the approved whole-tree sculpture.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import Model, decode, encode, render
from tools.shape_fit.emerald.littleroot_tree import generate as broadleaf, KEYS
from tools.shape_fit.emerald_pass.scenery import ROOT, BASELINE, BUNDLE, tree as conifer
from tools.shape_fit.emerald_pass.review import entry_hash, read, STACK_AMENDMENTS

OUT = ROOT / "local/model-gallery/emerald-pass-study/vegetation"
INVENTORY = ROOT / "local/model-gallery/emerald-pass-study/inventory/vegetation-ownership.json"
# Explicit IDs selected after inspecting source/geometry and reconstructed map contexts.
BROADLEAF = {
    "g3q/12357-12350-12358-12359": 486, "g3q/12353-12360-12361-12362": 487,
    "g3q/12353-12354-12373-12390": 485, "g3q/12349-12350-12391-12372": 484,
    "g3q/12426-12332-12340-12334": 470, "g3q/12335-12427-12337-12342": 471,
}
ORNAMENTAL = (("g3q/11319-11320-11321-11322", "g3q/11362-11363-11364-11365"),
              ("g3q/11924-11925-11926-11927", "g3q/12011-12012-12013-12014"))
CROWNS = (
    "g3q/10755-10756-10757-10758", "g3q/11285-11286-10757-10758",
    "g3q/12292-12293-12294-12295", "g3q/12112-12121-12129-12136",
    "g3q/12460-12461-10757-10758", "g3q/10891-10892-10893-10894",
    "g3q/12380-12381-12382-12383", "g3q/12384-12385-12386-12387",
    "g3q/10755-17581-10757-10758", "g3q/17425-17426-17427-17428",
    "g3q/17561-17562-10893-10894",
)
BASES = ("g3q/10913-10914-10915-10916", "g3q/10913-10914-10915-12396",
         "g3q/10913-10914-12397-10916", "g3q/18166-18167-18168-18169",
         "g3q/17443-17444-17445-17446")
MIDDLE = "g3q/10875-10876-10877-10878"
FLOWER = "g3q/12098-12099-12100-12101"
GRASS = "g3q/10736-10737-10738-10739"
PLANTERS = ("g3q/12392-12393-10670-10671", "g3q/10670-10671-12392-12393", "g3q/12392-12393-12392-12393")
SHRUBS = ("g3q/12738-12739-12740-12741", "g3q/12742-12743-12744-12745")
LEAVES = ((58, 82, 0), (58, 140, 49), (132, 197, 99), (181, 255, 132))
BARK = ((66, 58, 49), (107, 90, 90))


def color_uv(sprite, color):
    pixels = np.asarray(sprite.convert("RGB"))
    candidates = np.argwhere(np.all(pixels == color, axis=2))
    if not len(candidates):
        raise ValueError(f"Required source material missing: {color}")
    y, x = candidates[np.argmin(((candidates-[8, 8])**2).sum(axis=1))]
    return int(y*16+x)


def leaf_paint(shape, sprite, offset=(0, 0, 0), bright=True):
    y, z, x = np.indices(shape)
    y, z, x = y+offset[0], z+offset[1], x+offset[2]
    n = ((x//2*73856093) ^ (y//2*19349663) ^ (z//2*83492791)) % 100
    tone = np.where(n < 20, 0, np.where(n < 65, 1, 2))
    if bright:
        tone = np.where((y >= 20) & (z < 16) & (n < 30), 3, tone)
    colors = LEAVES if bright else LEAVES[:3]
    return np.asarray([color_uv(sprite, c) for c in colors], dtype=np.uint8)[tone]


def repaint_template(entry, old_sprite, new_sprite, original):
    occ, paint = decode(entry)
    old_pixels = np.asarray(old_sprite.convert("RGB"))
    translation = {int(v): color_uv(new_sprite, tuple(old_pixels[v//16, v%16])) for v in np.unique(paint[occ])}
    result = np.zeros_like(paint)
    for old, new in translation.items():
        result[(paint == old) & occ] = new
    return encode(occ, result, original, source="hand-emerald-broadleaf-variant", trim=True)


def pointed_tree():
    model = Model(height=40, width=32, depth=32)
    model.cylinder((16, 21), 2.5, (0, 18))
    model.cylinder((16, 21), 3.2, (0, 2))
    stem = model.occ.copy()
    yy, zz, xx = np.indices(model.occ.shape)
    radius = np.maximum.reduce([np.where((yy>=7)&(yy<22), 14-(yy-7)*.65, 0),
                                np.where((yy>=17)&(yy<30), 10-(yy-17)*.65, 0),
                                np.where((yy>=25)&(yy<39), 6-(yy-25)*.44, 0)])
    leaf = ((xx+.5-16)**2 + ((zz+.5-17)*1.14)**2 <= radius**2) & (radius>0)
    model.put(leaf)
    return model.occ, leaf, stem & ~leaf


def flower(sprite):
    green = color_uv(sprite, LEAVES[1]); dark = color_uv(sprite, LEAVES[0])
    petal = color_uv(sprite, (255,197,148)); heart = color_uv(sprite, (206,66,82))
    model = Model()
    for x,z,h in ((4,10,7),(11,10,8),(8,5,10)):
        model.box((x,x+1),(0,h),(z,z+1),green)
        model.ellipsoid((x+1,3,z),(3,2,2.5),green)
        model.ellipsoid((x,h,z),(2.8,1,2.8),petal)
        model.box((x-1,x+1),(h,h+1),(z-1,z+1),heart)
    model.ellipsoid((8,1.5,8),(5,1.5,5),dark)
    return model


def grass(sprite):
    model = Model()
    a,b = color_uv(sprite,(25,165,107)),color_uv(sprite,(58,82,0))
    for x,z,h,lean in ((3,4,7,-1),(7,3,9,1),(12,5,8,1),(4,11,8,-1),(9,10,10,1),(13,12,6,-1)):
        for y in range(h):
            shift=int(y*lean/4)
            model.box((x+shift,x+shift+2 if y<h-2 else x+shift+1),(y,y+1),(z,z+2),a if y>2 else b)
    return model


def shrub(sprite):
    model=Model()
    model.ellipsoid((8,7,8),(6.5,6.5,6.5))
    model.ellipsoid((5,7,7),(4,4,4));model.ellipsoid((11,6,9),(4,4,4))
    p=leaf_paint(model.occ.shape,sprite,offset=(15,0,0))
    model.paint[model.occ]=p[model.occ]
    return model


def planter(sprite, mode):
    model=Model()
    terra=color_uv(sprite,(165,99,90));rim=color_uv(sprite,(222,148,115));soil=color_uv(sprite,BARK[0])
    rows=[0,8] if mode==2 else ([0] if mode==0 else [8])
    for z in rows:
        model.box((1,15),(0,4),(z+1,z+7),terra)
        model.box((0,16),(3,5),(z,z+8),rim)
        model.box((1,15),(4,5),(z+1,z+7),soil)
        for x in (4,8,12):model.ellipsoid((x,6,z+4),(3,3,2.8),color_uv(sprite,LEAVES[2 if x==8 else 1]))
    if mode!=2:
        z=8 if mode==0 else 0
        model.box((0,16),(0,2),(z,z+8),color_uv(sprite,(165,165,165)))
        model.box((1,15),(2,3),(z+1,z+7),color_uv(sprite,(255,255,255)))
    return model


def generate(table, art):
    updates, semantic = {}, {}
    def add(key,entry,label,family,reason):
        updates[key]=entry;semantic[key]=(label,family,reason)
    approved=broadleaf()
    for key,mid in BROADLEAF.items():
        base=approved[KEYS[mid]]
        add(key,repaint_template(base,art.cell(KEYS[mid],"BAKED"),art.cell(key,"BAKED"),table['cells'][key]),
            "Broadleaf tree quadrant variant", "broadleaf tree", "Extend the accepted whole-tree quadrant sculpture to this source/background variant; map adjacency establishes its quadrant and source-local leaf/bark colors exclude neighboring terrain and projected crown tips.")
    occ,leaf,stem=pointed_tree()
    for z,row in enumerate(ORNAMENTAL):
        for x,key in enumerate(row):
            sprite=art.cell(key,"BAKED");selection=(slice(None),slice(z*16,z*16+16),slice(x*16,x*16+16));o=occ[selection]
            p=leaf_paint(o.shape,sprite,(0,z*16,x*16),bright=z==0)
            if z==1:p[stem[selection]]=color_uv(sprite,BARK[1])
            add(key,encode(o,p,table['cells'][key],source="hand-emerald-pointed-tree",trim=True),
                "Pointed ornamental tree quadrant", "pointed ornamental tree", "Four map-adjacent quarters on Routes110/111/114/115 form one pointed tree; authored tapered tiered canopy and connected trunk before slicing, without grass or cast-shadow volume.")
    whole,foliage=conifer()
    for key in (*CROWNS,*BASES,MIDDLE):
        sprite=art.cell(key,"BAKED")
        start=0 if key in BASES else 16 if key in CROWNS else 8
        o=whole[start:start+16];leaf=foliage[start:start+16]
        p=leaf_paint(o.shape,sprite,(start,0,0),bright=key not in BASES)
        if key in BASES:p[o&~leaf]=color_uv(sprite,BARK[1])
        add(key,encode(o,p,table['cells'][key],source="hand-emerald-conifer-family"),
            "Conifer rooted base" if key in BASES else "Conifer forest middle" if key==MIDDLE else "Conifer crown source variant",
            "narrow conifer", "Source/map inspection identifies a crown, repeated forest section or rooted lower section. Reuse connected whole-tree foliage geometry and source-local leaf/bark addresses, preserve existing on links and exclude rock/water/grass pixels. The former empty water-edge crown now has explicit nonempty foliage." if key=="g3q/12112-12121-12129-12136" else "Source/map inspection identifies a crown, repeated forest section or rooted lower section. Reuse connected whole-tree foliage geometry and source-local leaf/bark addresses, preserve existing on links and exclude rock/water/grass pixels.")
    for key,fn,label,family in [(FLOWER,flower,"Three-flower garden clump","flowers"),(GRASS,grass,"Tall grass tuft cluster","grass"),*[(k,shrub,"Rounded clipped shrub","shrub") for k in SHRUBS]]:
        model=fn(art.cell(key,"BAKED"))
        add(key,model.entry(table['cells'][key],source="hand-emerald-garden"),label,family,
            "Reviewed source and map contexts; replace frontal extrusion with grounded explicit stems/leaves/petals or rounded shrub lobes, retaining source-native materials and clear air around the object.")
    for mode,key in enumerate(PLANTERS):
        model=planter(art.cell(key,"BAKED"),mode)
        add(key,model.entry(table['cells'][key],source="hand-emerald-planters"),"Terracotta planters with clipped foliage","garden planters",
            "Battle Frontier and Petalburg map contexts show repeated rectangular planters; model shallow pots, raised rims, soil and rounded foliage in their floor footprint. Pale adjoining paving is a low slab, not a vertical billboard.")
    for key in ('g3q/10755-10756-10757-10758', MIDDLE):
        updates[key].update(STACK_AMENDMENTS[key]['after'])
        label,family,reason=semantic[key]
        semantic[key]=(label,family,reason+' Source-verified Frontier bases also match the exact alternate on keys; primary on and sculpture are preserved.')
    return updates,semantic


def entities_for(art, updates):
    from tools.shape_fit.emerald.littleroot_tree import ROLES
    role_keys = {(x,z):{KEYS[mid] for mid,role in ROLES.items() if role==(x,z)} for x in range(2) for z in range(2)}
    for key,mid in BROADLEAF.items():role_keys[ROLES[mid]].add(key)
    layouts={}
    for map_id,source in art.maps.items():
        grid=art.map_cells(source)
        for y in range(len(grid)-1):
            for x in range(len(grid[y])-1):
                layout=[row[x:x+2] for row in grid[y:y+2]]
                if any(key in BROADLEAF for row in layout for key in row) and all(layout[z][dx] in role_keys[dx,z] for z in range(2) for dx in range(2)):
                    stamp=json.dumps(layout)
                    layouts.setdefault(stamp,{"layout":layout,"occurrences":[]})["occurrences"].append({"map":map_id,"x":x,"y":y})
    definitions=[]
    for n,item in enumerate(layouts.values()):
        definitions.append({"id":f"emerald_broadleaf_variant_{n:02d}","kind":"object (model as one)","note":"Broadleaf source variant assembly", "layout":item['layout']})
    definitions.append({"id":"emerald_pointed_ornamental_tree","kind":"object (model as one)","note":"Four-cell pointed ornamental tree","layout":[list(row) for row in ORNAMENTAL]})
    return definitions,list(layouts.values())


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);ap.add_argument('--baseline',type=Path,default=BASELINE);ap.add_argument('--bundle',type=Path,default=BUNDLE);args=ap.parse_args()
    table=read(args.baseline);art=EmeraldArt(args.bundle);updates,semantic=generate(table,art);rows=[]
    ownership={r['key']:r for r in read(INVENTORY)['entries']}
    from tools.shape_fit.emerald_pass.inventory import context_pages
    context_pages(list(ownership.values()),art,INVENTORY.parent/'vegetation-context')
    for key,entry in updates.items():
        label,family,reason=semantic[key];original=table['cells'][key];inv=ownership[key]
        rows.append({'key':key,'kind':'cell','label':label,'family':family,'decision':'changed','reason':reason,'baseline_entry_sha256':entry_hash(original),'occurrences':art.locations.get(key,[]),'evidence':[f"inventory/shared-general/{Path(inv['contact_page']).name}",f"inventory/vegetation-context/page-{list(ownership).index(key)//8+1:02d}.png"]})
        render(entry,art.cell(key,'BAKED'),args.out/'models'/key.split('/')[1])
    definitions,placements=entities_for(art,updates)
    write_json(args.out/'updates.json',{'cells':updates,'buildings':{}});write_json(args.out/'ledger.json',rows);write_json(args.out/'entities.json',definitions);write_json(args.out/'assembly-placements.json',placements)
    write_json(args.out/'remaining.json',[r for k,r in ownership.items() if k not in updates])
    print(f'{len(updates)} explicit vegetation cells; {len(definitions)} additional complete-object exhibits; {len(ownership)-len(updates)} projected-terrain cases still pending')


if __name__=='__main__':main()
