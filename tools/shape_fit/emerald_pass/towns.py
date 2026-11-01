"""Individually reviewed Emerald town props; guarded local candidates only.

The explicit register is filled by visual review of sources, orthographic models
and map neighborhoods. Membership in a tileset is not a retention decision.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np

from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model, decode, encode, render, uv
from tools.shape_fit.emerald_pass.buildings import ROOT, BASELINE, BUNDLE, digest

INVENTORY = ROOT / "local/model-gallery/emerald-pass-study/inventory"
OUT = ROOT / "local/model-gallery/emerald-pass-study/towns"
# Entries are contact-page positions, explicitly reviewed in the associated group.
REGISTER = {
    "inside_of_truck": {
        1: ("retained", "upper-left strapped cargo box", "Rectangular hand-authored box with recessed front and lid; placed at the truck's upper-left cargo corner."),
        2: ("retained", "rear strapped cargo box", "Separate top and front faces preserve the pale strapped cargo beside the large brown crate."),
        3: ("retained", "large brown crate left half", "Joins the adjacent right half into the two-cell crate at the rear of the truck; solid top and dark lower frame already read clearly."),
        4: ("retained", "large brown crate right half", "Right end of the same two-cell rear crate; existing rounded corner and dark lower frame match its left half."),
        5: ("retained", "truck side exit upper frame", "Thin warp frame keeps the bright exit center open at the northeast side of the truck; walk metadata is retained."),
        6: ("retained", "truck west cargo return", "Existing low box occupies the pale cargo portion of this west-edge tile without filling the black exterior."),
        7: ("retained", "truck side exit middle frame", "Open-center thin exit frame continues the vertical bright opening; retaining the walkable warp segment avoids creating a solid wall across it."),
        8: ("retained", "stacked strapped cargo case", "The hand-authored two-step box has a raised rear case and lower front case, matching the paired boxes along the truck wall."),
        9: ("retained", "truck side exit lower frame", "Lower continuation of the same side exit remains an open thin frame with its bottom edge and walk flag preserved."),
        10: ("retained", "lower-west stacked cargo case", "Existing upper and lower cargo masses join the neighboring case row and exclude the surrounding dark truck boundary."),
        11: ("retained", "brown crate and front strapped case", "The brown rear lid and pale forward box are distinct volumes within this mixed cargo cell; both remain readable in front/east/top views."),
        12: ("retained", "truck lower dark cargo edge", "Only the top cargo edge has volume; the black source half is excluded. The shallow stepped geometry closes the west stack."),
        13: ("retained", "truck lower pale cargo edge", "The low pale case and dark base follow the final cargo row; black exterior pixels do not become a solid outside wall."),
    },
    "facility": {
        1: ("retained", "Aqua entrance threshold", "Low four-voxel slab preserves the sandy threshold under the coastal entrance and its shallow grated lip."),
        2: ("changed", "glass cabinet lower case", "Author the glazed cabinet's lower body, gold base trim and two feet, excluding the red floor from physical surfaces; joins its existing upper on-linked panel."),
        3: ("retained", "orange stepped exterior marker", "The Mt Pyre path context shows a small orange marker with a stepped cap; current tapered solid and separate front inset match that object without copying the surrounding grass."),
        4: ("retained", "blue stepped exterior marker", "The blue counterpart keeps its own cool source palette, stepped cap and inset front; current volume is coherent beside the exterior rock path."),
        5: ("changed", "rounded summit standing stone", "Replace the pinched silhouette extrusion with a grounded rounded stone using only the pale stone and outline materials; no green ground painted on the sides."),
        6: ("changed", "summit hollow stone pedestal", "Author a short circular stone pedestal with an actual inset hollow top, avoiding the previous hourglass side profile and grass band."),
        7: ("changed", "summit shrine rail left", "Left return and supporting post of the map's three-cell curved stone altar rail, authored with its matching center and right section before slicing."),
        8: ("changed", "summit shrine rail center", "The low rounded center and raised rail join both side returns across the actual three-cell summit assembly."),
        9: ("changed", "summit shrine rail right", "Mirrors the left supporting return in the actual three-cell stone altar; clean stone materials exclude the source grass."),
        10: ("retained", "New Mauville threshold left corner", "Low sandy slab continues the left cave-mouth threshold; a raised cliff-wall object would misread this projected ground corner."),
        11: ("retained", "New Mauville threshold right corner", "Matching right sandy threshold corner remains a low slab and preserves its ground texture."),
        12: ("changed", "Aqua orb-machine upper crown A", "Upper glass half is shaped as a round dome joining the existing on-linked lower machine at y16; source floor pixels are excluded from the crown."),
        13: ("changed", "Aqua orb-machine lower body A", "Distinct foot, metal pedestal, gold collar and lower glass sphere replace the pinched extrusion. Its matching crown completes one connected machine."),
        14: ("changed", "glass cabinet upper panel", "Thin continuous glazed upper cabinet joins the existing lower-case on link; clean metal side strips preserve a cabinet rather than a freestanding flat sprite."),
        15: ("changed", "Aqua orb-machine lower body B", "The alternate control-base source retains its indicator panel on a grounded pedestal beneath the gold collar and glass sphere."),
        16: ("changed", "Aqua orb-machine upper crown B", "Corresponding glass crown uses only local crystal colors and joins the alternate lower machine through its unchanged on link."),
    },
    "battle_palace": {
        1: ("changed", "palace arched niche crown", "Build the upper half of a shallow red wall niche with a true arched recess and gold rim, joining the source's lower sill through the existing on link."),
        2: ("changed", "palace niche sill and lower wall", "Replace the generic tapered pedestal with a straight red wall panel and projecting gold sill beneath the matching upper arch."),
    },
    "ever_grande": {
        1: ("changed", "League round garden shrub A", "Round leafy shrub in the League forecourt; remove the old grass-colored band through its crown and use a grounded rounded canopy."),
        2: ("changed", "League round garden shrub B", "Source variant of the forecourt round shrub, using the same authored foliage form with local source colors and no terrain-colored upper face."),
        3: ("changed", "League dense hedge foliage", "Dense repeated hedge cell along the eastern garden wall; use an overlapping, full-width leafy mass instead of a pinched central extrusion."),
        4: ("changed", "League hedge trunk end", "The bottom hedge source exposes a short trunk and root collar; author those separately beneath the leaf mass and exclude the green ground from the roots."),
    },
    "battle_frontier": {
        1: ("changed", "reception red chair right", "Actual chair with four short feet, raised seat and right-hand backrest in the reception map, replacing the previous almost-flat footprint extrusion."),
        2: ("changed", "reception red chair left", "Mirror-oriented chair with a left-hand backrest, preserving the source's furniture orientation and keeping pale floor out of its surfaces."),
        3: ("changed", "reception potted palm crown", "Several tapered leaf blades grow from the upper stem and join the existing pot on link. The broad source foliage is a complete potted plant, not an hourglass extrusion."),
        4: ("retained", "reception pale counter left corner", "The existing hand-authored shallow counter corner joins the U-shaped reception desk in the map; its pale top and gray plinth occupy the correct floor region."),
        5: ("retained", "reception pale counter right corner", "Matching right counter end preserves the U-shaped desk and its gray base without introducing a separate upright sign or cabinet."),
        6: ("changed", "reception palm pot and lower stem", "A rounded gray pot with dark soil and narrow trunk supports the fan-shaped crown; the existing crown-to-pot relation is unchanged."),
        7: ("changed", "reception golden lamp crown", "A rounded yellow light head and narrow stem complete the source's freestanding lobby lamp, without carrying pale floor pixels into the upper model."),
        8: ("changed", "reception stone lamp pedestal", "Grounded round gray pedestal with a small neck joins the golden cap through its existing on link, replacing the pinched generic silhouette."),
    },
}


def rows_for(group):
    return json.loads((INVENTORY / f"general__{group}" / "entries.json").read_text())


def texel(sprite, color, rect=(0, 0, 16, 16)):
    """Find an address for a deliberately selected material in its source region."""
    pixels = np.asarray(sprite.convert("RGB")).astype(int)
    x0, y0, x1, y1 = rect
    error = ((pixels[y0:y1, x0:x1] - np.asarray(color)) ** 2).sum(axis=2)
    y, x = np.unravel_index(error.argmin(), error.shape)
    return uv(x + x0, y + y0)


def stone(sprite, mode):
    pale = texel(sprite, (239, 230, 239), (2, 2, 14, 14))
    shade = texel(sprite, (115, 115, 140), (1, 2, 15, 14))
    m = Model()
    if mode == "round":
        m.ellipsoid((8, 7, 8), (6.5, 7, 6), pale)
        y, z, x = np.indices(m.occ.shape)
        m.paint[m.occ & ((x + z) > 19)] = shade
    else:
        m.cylinder((8, 8), 5, (0, 8), shade)
        m.cylinder((8, 8), 6.5, (7, 11), pale)
        m.cylinder((8, 8), 4.5, (8, 12), cut=True)
    return m


def shrine():
    m = Model(width=48)
    m.box((17, 31), (0, 5), (5, 12))
    m.ellipsoid((24, 7, 9), (10, 3, 6))
    # The source's central oval is a shallow stone basin, with a real rim.
    y, z, x = np.indices(m.occ.shape)
    hollow = ((x + .5 - 24) / 7) ** 2 + ((z + .5 - 9) / 3.5) ** 2 < 1
    m.put(hollow & (y >= 8), cut=True)
    m.paint[m.occ & hollow & (y == 7)] = 1
    for left in (True, False):
        m.cylinder((6 if left else 42, 9), 2.5, (0, 13))
        m.box((6, 17) if left else (31, 42), (7, 10), (8, 13))
        m.box((3, 8) if left else (40, 45), (9, 13), (2, 10))
    return m


def cabinet(sprite, upper):
    m = Model()
    metal = texel(sprite, (115, 123, 148), (0, 0, 16, 13))
    gold = texel(sprite, (239, 206, 99), (0, 0, 16, 13))
    if upper:
        m.box((1, 15), (0, 16), (5, 12), metal)
        m.project(side=metal, top=metal, back=metal)
    else:
        m.box((1, 15), (5, 16), (5, 12), metal)
        m.box((1, 15), (4, 8), (4, 13), gold)
        for x in (2, 12):
            m.box((x, x + 2), (0, 5), (6, 11), metal)
        y, z, x = np.indices(m.occ.shape)
        face = m.occ & (z == 11) & (y >= 8)
        addresses = ((15 - y) * 16 + x).astype(np.uint8)
        m.paint[face] = addresses[face]
    return m


def orb_machine(sprite, upper, controls=True):
    whole = Model(height=24)
    metal = texel(sprite, (74, 82, 115), (1, 1, 15, 16))
    gold = texel(sprite, (231, 189, 82), (2, 1, 14, 16))
    glass_rect = (4, 11, 12, 16) if upper else (3, 0, 13, 8)
    blue = texel(sprite, (82, 173, 231), glass_rect)
    light = texel(sprite, (132, 206, 255), glass_rect)
    if not upper:
        whole.box((3, 13), (0, 3), (4, 13), metal)
        whole.box((4, 12), (3, 10), (5, 12), metal)
        whole.cylinder((8, 8), 6.5, (9, 12), gold)
    whole.ellipsoid((8, 15.5, 8), (5.5, 6.5, 5.5), blue)
    y, z, x = np.indices(whole.occ.shape)
    whole.paint[whole.occ & (y >= 11) & ((x // 2 + y // 2 + z // 2) % 3 == 0)] = light
    if not upper and controls:
        # Small source control panel retained only on the pedestal's front.
        whole.box((5, 11), (4, 7), (11, 12), texel(sprite, (90, 165, 140), (3, 11, 13, 16)))
    s = slice(16, 24) if upper else slice(0, 16)
    model = Model(height=8 if upper else 16)
    model.occ, model.paint = whole.occ[s].copy(), whole.paint[s].copy()
    return model


def palace(sprite, upper):
    red = texel(sprite, (206, 66, 25));gold = texel(sprite, (247, 214, 82));dark = texel(sprite, (66, 49, 66))
    m = Model(height=32)
    m.box((0, 16), (0, 32), (7, 11), red)
    m.box((4, 12), (10, 22), (10, 12), gold)
    m.ellipsoid((8, 22, 10), (4.5, 5, 2), gold)
    m.box((5, 11), (12, 22), (9, 13), cut=True)
    yy, zz, xx = np.indices(m.occ.shape)
    arch = ((xx + .5 - 8) / 3.5) ** 2 + ((yy + .5 - 22) / 4) ** 2 <= 1
    m.put(arch & (zz >= 9) & (zz < 13), cut=True)
    m.box((5, 11), (12, 22), (8, 9), dark)
    m.ellipsoid((8, 22, 8.5), (3.5, 4, .6), dark)
    m.box((3, 13), (10, 12), (9, 14), gold)
    s = slice(16, 32) if upper else slice(0, 16)
    out = Model();out.occ, out.paint = m.occ[s].copy(), m.paint[s].copy()
    return out


def garden(sprite, mode):
    m = Model()
    dark = texel(sprite, (33, 90, 25), (2, 2, 14, 15))
    mid = texel(sprite, (66, 148, 41), (2, 2, 14, 14))
    light = texel(sprite, (140, 214, 82), (2, 2, 14, 13))
    if mode in (1, 2):
        m.ellipsoid((8, 7, 8), (6.8, 7, 6.8), mid)
    elif mode == 3:
        m.ellipsoid((8, 8, 8), (8.5, 8, 7.5), mid)
        m.ellipsoid((4, 11, 7), (4, 5, 5), mid)
        m.ellipsoid((12, 11, 7), (4, 5, 5), mid)
    else:
        m.cylinder((8, 8), 2.5, (0, 8), texel(sprite, (82, 66, 49), (4, 8, 12, 16)))
        m.cylinder((8, 8), 3.5, (0, 2), texel(sprite, (49, 49, 33), (3, 8, 13, 16)))
        m.ellipsoid((8, 10, 8), (7, 5.5, 6.5), mid)
    y, z, x = np.indices(m.occ.shape)
    foliage = m.occ & ((y >= 6) if mode == 4 else True)
    m.paint[foliage & ((x // 2 + z // 2 + y // 3) % 5 == 0)] = dark
    m.paint[foliage & (y > 8) & ((x // 2 + z // 2 + y // 3) % 3 == 0)] = light
    return m


def chair(sprite, right):
    orange=texel(sprite,(255,90,41),(2,2,14,14));edge=texel(sprite,(123,74,66),(1,1,15,15))
    m=Model()
    for x in (3,11):
        for z in (3,11):m.box((x,x+2),(0,5),(z,z+2),edge)
    m.box((2,14),(4,7),(2,14),orange)
    m.box((12,15) if right else (1,4),(7,14),(2,14),orange)
    m.box((2,14),(6,9),(1,3),edge)
    return m


def lobby_stack(sprite, upper, palm):
    m=Model(height=32)
    gray=texel(sprite,(132,123,148),(2,1,14,16));dark=texel(sprite,(66,66,82),(2,1,14,16))
    if palm:
        trunk=texel(sprite,(148,115,33),(4,1,12,15))
        m.cylinder((8,8),4,(0,7),gray);m.cylinder((8,8),5,(6,9),gray)
        m.cylinder((8,8),4,(8,9),dark);m.cylinder((8,8),1.3,(8,23),trunk)
        green=texel(sprite,(148,206,74),(1,1,15,15));bright=texel(sprite,(206,239,115),(1,1,15,15))
        for i,end in enumerate(((1,20,8),(15,20,8),(8,21,1),(8,20,15),(3,26,3),(13,26,4),(8,28,8))):
            for t in np.linspace(0,1,9):
                center=np.asarray((8,21,8))*(1-t)+np.asarray(end)*t
                span=2.2*(1-t)+.55
                m.ellipsoid(center,(span,1.1,span),bright if i%3==0 else green)
    else:
        gold=texel(sprite,(239,189,82),(3,8,13,16));light=texel(sprite,(255,230,132),(3,9,13,16))
        m.cylinder((8,8),4.5,(0,3),dark);m.cylinder((8,8),4,(3,14),gray)
        m.cylinder((8,8),2,(13,23),gold);m.ellipsoid((8,24,8),(5.5,4,5.5),light)
        m.cylinder((8,8),5,(20,22),gold)
    s=slice(16,32) if upper else slice(0,16);out=Model();out.occ,out.paint=m.occ[s].copy(),m.paint[s].copy()
    return out


def generate(table, art):
    updates = {}
    for group, decisions in REGISTER.items():
        rows = rows_for(group)
        for position, (status, label, reason) in decisions.items():
            if status != "changed":
                continue
            key = rows[position - 1]["key"]
            sprite = art.cell(key, "BAKED")
            if group == "facility":
                if position in (2, 14):model = cabinet(sprite, position == 14)
                elif position in (5, 6):model = stone(sprite, "round" if position == 5 else "hollow")
                elif position in (7, 8, 9):
                    full = shrine();start=(position-7)*16;model=Model();model.occ=full.occ[:, :, start:start+16].copy()
                    model.paint[model.occ] = texel(sprite, (239, 230, 239), (1, 2, 15, 14))
                    model.paint[model.occ & (full.paint[:, :, start:start+16] == 1)] = texel(sprite, (148, 148, 165), (1, 2, 15, 14))
                else:model=orb_machine(sprite, position in (12, 16), controls=position == 15)
            elif group == "battle_palace":model = palace(sprite, position == 1)
            elif group == "ever_grande":model = garden(sprite, position)
            elif group == "battle_frontier":
                model = chair(sprite, position == 1) if position in (1,2) else lobby_stack(sprite,position in (3,7),position in (3,6))
            else:raise ValueError((group, position))
            updates[key] = model.entry(table["cells"][key], "hand-emerald-town-scenery", trim=True)
    return updates


def assemblies():
    facility = [row['key'] for row in rows_for('facility')]
    palace_keys = [row['key'] for row in rows_for('battle_palace')]
    lobby=[row['key'] for row in rows_for('battle_frontier')]
    return [
        {'id':'emerald_town_summit_shrine_rail','kind':'object (model as one)','note':'Mt Pyre summit three-cell curved shrine rail','layout':[[facility[6],facility[7],facility[8]]]},
        {'id':'emerald_town_aqua_glass_cabinet','kind':'stack (model as one)','note':'Aqua Hideout glass cabinet','layout':[[facility[13]],[facility[1]]]},
        {'id':'emerald_town_aqua_orb_machine_a','kind':'stack (model as one)','note':'Aqua orb machine, overlapping-neighbor source variant','layout':[[facility[11]],[facility[12]]]},
        {'id':'emerald_town_aqua_orb_machine_b','kind':'stack (model as one)','note':'Aqua orb machine, exposed control base','layout':[[facility[15]],[facility[14]]]},
        {'id':'emerald_town_palace_arched_niche','kind':'stack (model as one)','note':'Battle Palace two-cell arched wall niche','layout':[[palace_keys[0]],[palace_keys[1]]]},
        {'id':'emerald_town_reception_palm','kind':'stack (model as one)','note':'Reception gate potted palm','layout':[[lobby[2]],[lobby[5]]]},
        {'id':'emerald_town_reception_lamp','kind':'stack (model as one)','note':'Reception gate golden lamp','layout':[[lobby[6]],[lobby[7]]]},
    ]


def assembly_previews(table, updates, art, out):
    from tools.shape_fit.hand_check import picture, front
    from PIL import Image
    records=[]
    for item in assemblies():
        target=out/'assemblies'/item['id'];target.mkdir(parents=True,exist_ok=True)
        art.drawing(item['layout'],'BAKED').resize((len(item['layout'][0])*160,len(item['layout'])*160),Image.Resampling.NEAREST).save(target/'source.png')
        stacked=item['kind'].startswith('stack')
        keys=[row[0] for row in item['layout'][::-1]] if stacked else item['layout'][0]
        for revision, entries in [('before',table['cells']),('after',{**table['cells'],**updates})]:
            masks=[];colors=[]
            for key in keys:
                occ,paint=decode(entries[key]);pixels=np.asarray(art.cell(key,'BAKED').convert('RGB'))
                masks.append(occ);colors.append(pixels[paint//16,paint%16])
            occupied=np.concatenate(masks,axis=0 if stacked else 2);painted=np.concatenate(colors,axis=0 if stacked else 2)
            picture(occupied,painted,target/f'{revision}-angled.png',8);front(occupied,painted,target/f'{revision}-front.png',10)
        # The actual source layout is verified at an exported map placement.
        found=[]
        first=item['layout'][0][0]
        for place in art.locations.get(first,[]):
            grid=art.map_cells(art.maps[place['map']]);x,y=place['x'],place['y']
            if all(y+dz<len(grid) and x+dx<len(grid[y+dz]) and grid[y+dz][x+dx]==key for dz,row in enumerate(item['layout']) for dx,key in enumerate(row)):
                found.append(place)
        records.append({'id':item['id'],'layout':item['layout'],'occurrences':found})
    (out/'assembly-placements.json').write_text(json.dumps(records,indent=2)+'\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previews", action="store_true")
    parser.add_argument("--out", type=Path, default=OUT)
    args = parser.parse_args()
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);updates=generate(table, art);ledger=[]
    args.out.mkdir(parents=True, exist_ok=True)
    for group, decisions in REGISTER.items():
        rows=rows_for(group)
        for position,(status,label,reason) in decisions.items():
            row=rows[position-1];key=row['key'];target=args.out/group/key.removeprefix('g3q/');target.mkdir(parents=True,exist_ok=True)
            source=art.cell(key,'BAKED');source.save(target/'source.png')
            evidence=[{'source_tilesets':row['tileset_membership'],'map_occurrences':row['occurrences'],'contact_sheet':str((INVENTORY/row['contact_page']).relative_to(ROOT)),'contact_position':position},
                      {'map_context_sheet':str((OUT/f'general__{group}'/'contexts'/f'page-{(position-1)//16+1:03d}.png').relative_to(ROOT)),'context_position':position},
                      {'source':str((target/'source.png').relative_to(ROOT)),'source_pixels_sha256':hashlib.sha256(source.tobytes()).hexdigest()}]
            if args.previews and key in updates:
                render(updates[key],source,target/'after');render(table['cells'][key],source,target/'before')
            if (target/'after/angled.png').exists():evidence.append({'candidate_preview':str((target/'after/angled.png').relative_to(ROOT))})
            ledger.append({'key':key,'kind':'cell','label':label,'family':group,'decision':status,'reason':reason,'baseline_entry_sha256':digest(table['cells'][key]),'evidence':evidence,'visual_status':'source/map/orthographic inspection; changed candidate Minecraft review pending'})
    (args.out/'updates.json').write_text(json.dumps({'cells':updates,'buildings':{}},indent=2)+'\n')
    (args.out/'ledger.json').write_text(json.dumps(ledger,indent=2)+'\n')
    (args.out/'entities.json').write_text(json.dumps(assemblies(),indent=2)+'\n')
    if args.previews:assembly_previews(table,updates,art,args.out)
    print(json.dumps({'reviewed':len(ledger),'changed':len(updates),'retained':len(ledger)-len(updates),'status':'partial town inventory'}))


if __name__ == '__main__':main()
