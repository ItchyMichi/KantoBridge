"""Reviewed Blue gate, gym, lobby, Pokémon Center and S.S. Anne furnishings.

The registry is deliberately explicit: these are semantic authoring decisions
made after inspecting every source cell, its old front/side screenshots, and the
reconstructed maps. Geometry does not classify arbitrary new sprites. All paint
remains addresses into the original 16x16 art, so map palettes still work.

Run from the repository root with ``python tools/shape_fit/blue_pass/interiors.py``.
This writes review artifacts only; the rollout driver owns the fitted table.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

TOOLS = Path(__file__).resolve().parents[2]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
from model_gallery import BlueArt
from shape_fit.blue_pass.common import Model, ROOT, load_baseline, source_shades, shade_uv, uv, render, encode, decode

ATLASES = ('gate', 'gym', 'lobby', 'pokecenter', 'ship')
SPECS = {}


def register(atlas, family, label, keys, reason):
    for suffix in keys.split():
        key = f'tilesets.{atlas}/{suffix}'
        if key in SPECS:
            raise ValueError(f'duplicate authoring decision: {key}')
        SPECS[key] = dict(family=family, label=label, reason=reason)


# Gate furnishings, museum cases and Route 22 statues. The binoculars and plants
# share source cells between museum/gate maps; these are not fossil fragments.
register('gate', 'statue_wall', 'Gate guardian head against the rear wall', '0-0-14-15',
         'Replace the broad background hourglass with a rounded guardian head and a shallow rear wall strip.')
register('gate', 'stairs', 'Descending indoor stair flight', '10-11-26-27',
         'Give the stair icon horizontal treads and a narrow side rail; retain walk semantics.')
register('gate', 'stairs', 'Ascending indoor stair flight', '12-13-28-29',
         'Replace the dark block silhouette with tread geometry and matching rails; retain walk semantics.')
register('gate', 'statue_head', 'Route 22 guardian upper body', '17-1-14-15',
         'Sculpt the face, paired ears, muzzle and shoulder volume; align the lower contact with its stacked pedestal.')
register('gate', 'chair', 'Gate and museum chair', '2-3-18-19',
         'Use a seat, rear backrest and four separated legs; preserve the runtime seat contract.')
register('gate', 'counter', 'Modular museum or gate counter',
         '23-24-23-24 23-24-50-51 49-60-50-51 60-44-50-51 60-60-50-51 7-8-23-24 7-9-23-24 7-9-50-51 9-8-23-24 9-8-50-51 9-9-50-51',
         'Use one constant-height counter surface and a connected cabinet front; remove the projection taper and preserve adjacent seams.')
register('gate', 'statue_base', 'Route 22 guardian pedestal', '30-31-50-51',
         'Build a stepped plinth and connected statue body reaching the upper cell instead of the detached hourglass body.')
register('gate', 'counter_sign', 'Gate counter information sign', '32-33-50-51 83-63-50-51',
         'Mount the original sign on a shallow plaque above the shared cabinet counter, with a plain supported rear.')
register('gate', 'cabinet', 'Museum bookcase lower shelving', '34-35-34-35',
         'Restore a straight cabinet enclosure and recessed illustrated shelves; match the upper cabinet depth at the stack seam.')
register('gate', 'binocular_top', 'Twin viewing binocular eyepieces', '36-36-52-52',
         'Create two deep optical barrels on straight posts, matching the lower stand rather than a flat silhouette.')
register('gate', 'plant_base', 'Potted palm pot and trunk', '37-38-53-54',
         'Give the round pot a rim, soil, tapered body and a central trunk reaching the upper foliage cell.')
register('gate', 'showcase_back', 'Museum fossil showcase rear or end panel',
         '39-40-48-70 40-25-71-44 40-40-58-70 40-40-71-58',
         'Rebuild the case fragment as a connected display shelf with shallow illustrated backing; preserve the fossil image across horizontal neighbours.')
register('gate', 'plant_top', 'Potted palm crown', '5-6-21-22',
         'Replace thin black-sided foliage with a branching trunk and rounded radial leaf masses; match the pot stack contact.')
register('gate', 'binocular_base', 'Twin viewing binocular supports', '57-57-1-17',
         'Make matching feet and full-height posts so the upper optical assembly has continuous support.')
register('gate', 'showcase_art', 'Museum shuttle display fragment', '64-65-80-81 66-67-82-82 68-69-84-85',
         'Keep the authored shuttle artwork continuous on a shallow display backing and shared case base; remove unsupported wedge depth.')
register('gate', 'cabinet', 'Museum bookcase upper shelving', '7-8-34-35',
         'Use the same straight cabinet depth as the lower shelves and retain the original shelf face instead of the pinched side.')
register('gate', 'bin', 'Museum waste bin', '76-77-92-93',
         'Replace the open horseshoe with a closed round bin, raised lid rim and recessed opening.')
register('gate', 'showcase_front', 'Museum mineral display counter', '86-87-50-51 88-89-50-51 90-91-50-51',
         'Put the mineral illustration on a backed case display above a connected base; stop the specimen from floating separately from its cabinet.')

# Gym fixtures and Oak's laboratory. Pool cells and deliberately shallow wall
# signs keep their established world roles; laboratory machinery is volumetric.
register('gym', 'retain', 'Cerulean pool edge or water surface', '1-1-17-17 4-4-20-20 9-10-25-26',
         'Inspected in Cerulean Gym: this is intentional pool terrain, not a freestanding object; keep its existing surface and walk contract.')
register('gym', 'bin', 'Vermilion Gym switch bin', '11-12-27-28',
         'Build the same recognizable round bin as the source, with a recessed top opening and a closed cylindrical body.')
register('gym', 'cabinet', 'Oak laboratory bookcase', '13-14-29-30 41-42-13-14',
         'Make a straight deep cabinet with illustrated shelf faces; the upper and lower cells meet at identical depth without a pinched waist.')
register('gym', 'statue_head', 'Gym guardian statue upper body', '2-56-18-19',
         'Replace the extrusion with a round torso, face, muzzle and pointed ears resting on its stacked pedestal.')
register('gym', 'plaque', 'Elite Four or laboratory wall plaque', '32-33-48-49 52-67-82-83',
         'Retain the original diagram on a framed shallow plaque; remove the thick black projection and give the back a neutral solid surface.')
register('gym', 'statue_base', 'Gym guardian statue pedestal', '34-35-50-51',
         'Build a stepped pedestal with a straight connecting neck and front nameplate; align its top with the statue head.')
register('gym', 'lab_table', 'Laboratory table segment', '41-59-78-57 59-42-57-79 59-59-57-57',
         'Make the table top and apron continuous at a shared height; put corner legs only at the illustrated outer edges.')
register('gym', 'bush', 'Celadon Gym round shrub', '44-45-46-47',
         'Remove floor checkerboard volume and sculpt overlapping rounded leafy lobes around a hidden short trunk.')
register('gym', 'retain', 'Bruno room floor boundary', '5-5-16-16',
         'Inspected as an intentional floor boundary strip; its shallow relief is correct and must not become furniture.')
register('gym', 'wall_panel', 'Vermilion Gym partition and electrical trim', '5-71-86-87 68-5-84-86 69-70-86-86',
         'Keep a straight shallow partition with the original electrical stripe on the face instead of a solid depth-extruded wall icon.')
register('gym', 'flower_pot', 'Celadon Gym flowering plant', '64-65-80-81',
         'Give the two flower clusters, stem and planter distinct rounded volumes; remove the black wedge behind the stem.')
register('gym', 'boulder', 'Bruno room decorative boulder', '7-8-23-24',
         'Sculpt overlapping stone volumes with irregular stepped shoulders and source-palette shading instead of a thin front mask.')
register('gym', 'stairs', 'Lance room stair flight', '72-73-74-75',
         'Replace the compressed stair symbol with a usable tread profile and narrow rail while preserving walk metadata.')
register('gym', 'lab_table_front', 'Oak laboratory table front edge and feet', '88-89-17-17 89-90-17-17',
         'Coordinate-level map inspection identifies the narrow front row of the laboratory tables; make a matching apron and outer foot at the common tabletop height, leaving the floor clear.')
register('gym', 'lab_computer', 'Laboratory computer and keyboard', '91-92-54-55',
         'Build a monitor enclosure, recessed screen, keyboard and table support; use neutral side surfaces rather than the front outline on all faces.')
register('gym', 'lab_instrument', 'Laboratory instrument and printer', '93-94-85-95',
         'Rebuild the right-hand laboratory apparatus with a deep rectangular housing, tray and tabletop matching the neighbouring computer.')

# Celadon shops, diner, game corner and elevator. Retail stacks retain their
# labels and control faces; no background checkerboard becomes physical bulk.
register('lobby', 'retain', 'Diner background floor strip', '1-1-33-33',
         'Map context shows a floor/boundary cell rather than a discrete prop; retain the established boundary geometry.')
register('lobby', 'counter', 'Celadon modular service counter',
         '1-1-38-41 38-39-48-21 38-39-54-57 38-41-54-57 39-39-21-21 39-39-55-55 39-41-21-21 39-41-21-49 39-41-54-57 54-57-54-57 55-55-91-91',
         'Use a level cabinet counter with consistent top and apron depth; preserve long connected surfaces rather than isolated tapered posts.')
register('lobby', 'plaque', 'Elevator control or store directory plaque', '1-62-33-63 46-47-33-33 6-1-22-33 62-1-63-33',
         'Isolate the original control/sign face in a shallow framed housing, removing the checkerboard floor and mushroom support.')
register('lobby', 'stairs', 'Celadon department-store staircase', '10-11-26-27 12-13-28-29',
         'Map inspection identifies the stair pair; use horizontal treads and side rails rather than a chair-like or solid black extrusion.')
register('lobby', 'cabinet_monitor', 'Store display monitor on cabinet', '14-15-30-31',
         'This cell occurs both over a shelf base and beside a demo peripheral. Build a deep monitor over the common counter-height cabinet so the demo assembly has a continuous tabletop; retain full-width load-bearing contact with the repaired shelf below.')
register('lobby', 'cabinet_phone', 'Department-store public phone', '2-3-18-19',
         'Give the phone a solid console enclosure, recessed controls and projecting handset above a continuous lower cabinet.')
register('lobby', 'cabinet', 'Shared phone, monitor and shelf cabinet base', '34-35-50-51',
         'The baseline entry is empty: author a solid cabinet case with shelves and a continuous full-width top to support all three upper fixtures.')
register('lobby', 'table_accessory', 'Celadon demonstration computer peripheral', '36-37-52-53',
         'Place the small console and controller on a real supported display counter, with distinct shallow housing and cable detail.')
register('lobby', 'cabinet', 'Game-corner and roof cabinet upper shelving', '38-41-34-35',
         'Rebuild the upper shelf as a straight case at the exact depth of its shared lower cabinet, preserving shelf art and stack metadata.')
register('lobby', 'round_table', 'Diner or roof octagonal table corner', '39-25-55-57 55-71-87-55 70-55-85-86 9-39-54-55',
         'Map context shows a four-cell octagonal table; give each corner a clipped tabletop at the same height and connected central support.')
register('lobby', 'retain', 'Department-store elevator door', '40-40-56-56',
         'Existing shallow double-door frame matches the sprite and map role; keep it rather than turning architectural trim into furniture.')
register('lobby', 'merch_shelf', 'Celadon merchandise shelving', '42-43-58-59 44-45-60-61 64-65-80-81 66-67-82-83',
         'Create a rigid shelf case with recessed front merchandise art and constant side depth; remove disconnected shelves and tapering black sides.')
register('lobby', 'counter_sign', 'Department-store reception counter sign', '46-47-21-49',
         'Mount the illustrated directory over a coherent cabinet counter, with a real plaque back and continuous tabletop.')
register('lobby', 'lattice', 'Department-store roof safety mesh', '68-68-84-84',
         'Replace the hourglass with a thin framed mesh panel whose thickness matches a roof barrier.')
register('lobby', 'chair', 'Celadon diner chair', '7-8-23-24',
         'Author an open four-legged chair, seat and backrest; retain the runtime seat flag and occupied footprint.')
register('lobby', 'plaque', 'Department-store advertisement and Game Corner poster', '72-73-88-89',
         'Final Minecraft QA and source occurrence checks identify the Mart wall advertisements and Game Corner Rocket poster. Use a grounded shallow framed plaque, preserving the artwork and excluding the bottom checkerboard floor pixels; remove the mistaken floating table.')
register('lobby', 'wall_panel', 'Game-corner booth divider', '75-76-75-76 75-76-77-78',
         'Use a consistent thin partition with plain side material and the original trim on its front.')

# Center/mart atlas includes healing machinery, retail cases and flower pots.
register('pokecenter', 'counter_register', 'Poké Mart cash register counter', '14-15-30-31',
         'Put a deep register body and small keypad on the matching cabinet counter; remove the flat unsupported register silhouette.')
register('pokecenter', 'counter', 'Poké Mart or Pokémon Center counter segment',
         '16-41-16-41 16-41-25-24 40-89-16-41 56-8-24-25 56-8-25-24 8-8-24-25 90-91-25-24',
         'Construct a continuous level counter with top art mapped horizontally, plain side panels and the illustrated lower trim on the front.')
register('pokecenter', 'wall_panel', 'Center striped rear partition', '3-40-19-40 40-2-40-18 40-40-40-40',
         'These cells belong to the striped rear wall around healing machines: make shallow connected panels instead of large I-shaped volume.')
register('pokecenter', 'healer_back', 'Healing station rear monitor and wall', '40-58-40-74 59-40-75-40',
         'The two source cells form one monitor across a horizontal seam; build matching half-width enclosures with a recessed screen over the shallow striped rear wall.')
register('pokecenter', 'plant_top', 'Hotel and Center potted palm crown', '32-33-48-49',
         'Give the palm leaf sprays depth around a branching trunk and align it with the lower pot, removing background checkerboard mass.')
register('pokecenter', 'plant_base', 'Hotel and Center pot and trunk', '34-35-50-51',
         'Build a round tapered pot with a lip, soil and a straight trunk that connects to the crown above.')
register('pokecenter', 'nurse_machine', 'Center side reception and service terminal', '36-37-52-53 38-39-42-43',
         'Map context identifies the side reception machinery; give it a connected rectangular housing and original illustrated face without an hourglass backing.')
register('pokecenter', 'retain', 'Center entrance mat or wall panel', '4-5-20-21',
         'The existing shallow framed rectangular surface matches its architectural role and source proportions.')
register('pokecenter', 'cabinet', 'Poké Mart sale cabinet upper fascia', '40-40-78-79',
         'Make the SALE fascia a straight cabinet section at the same depth as its lower display rather than a narrow spike.')
register('pokecenter', 'cabinet', 'Poké Mart glazed merchandise display', '46-47-62-63 76-77-23-29 90-91-44-45',
         'Give the display its connected rectangular case and recessed illustrated glass front; upper and lower stack sections use identical depth.')
register('pokecenter', 'floor_strip', 'Center patterned floor and counter gap', '54-11-26-27 57-11-60-27',
         'Reconstructed Center and Hotel maps show adjacent patterned floor cells, not foliage. Reduce accidental hourglass mass to a thin patterned surface; remove the explicitly approved erroneous on link from 57-11-60-27 without adding walk metadata.')
register('pokecenter', 'merch_shelf', 'Poké Mart stocked shelving', '64-65-80-81 65-67-81-83 68-69-84-85 69-71-85-87',
         'Make one connected cabinet with shelf recesses and original merchandise pixels, replacing floating shelf strips and pinched side silhouettes.')
register('pokecenter', 'cabinet_monitor', 'Pokémon Center public PC upper monitor', '66-70-82-86',
         'Create a monitor enclosure with recessed original screen and a straight cabinet contact, matching the keyboard base below.')
register('pokecenter', 'healer', 'Pokémon Center healing station module', '72-76-72-6 77-7-22-13 77-73-22-73',
         'Build a coherent rectangular healing bed/controller housing with shallow round pod details; remove the detached black plate and unsupported terminal.')
register('pokecenter', 'counter_bell', 'Pokémon Center service bell counter', '8-10-24-25',
         'Give the counter a continuous cabinet body and put a small rounded service bell on its top instead of a floating ring.')
register('pokecenter', 'pc_base', 'Pokémon Center public PC keyboard base', '9-88-17-27',
         'Replace the background-heavy hourglass with a solid workstation pedestal and shallow original keyboard, connected across the upper monitor seam.')
register('pokecenter', 'wall_panel', 'Indigo lobby wall terminal panel', '90-91-20-40',
         'Use a shallow framed electrical panel with plain side depth rather than a freestanding I-shaped extrusion.')
register('pokecenter', 'retain', 'Center rear doorway and patterned transom', '92-93-94-95',
         'Existing shallow portal and opening match the map architecture; preserve doorway clearance and trim.')

# S.S. Anne table fragments share actual 16-voxel world-space table heights.
# Plates/cups are attached to that common horizontal surface, never floating
# independent circular objects. The one erroneous bed stack is explicitly fixed.
register('ship', 'retain', 'Ship dark doorway or room boundary', '1-1-17-17',
         'The black rectangle is a deliberate doorway/boundary cell in map context; preserve its current architectural role.')
register('ship', 'ship_table', 'S.S. Anne dining table fragment',
         '10-10-44-53 10-10-44-64 10-10-53-44 10-12-44-28 25-26-11-11 25-44-11-59 25-44-25-44 25-44-59-60 25-64-25-80 26-28-44-28 44-28-11-11 44-28-59-60 44-28-60-11 44-44-11-11 65-28-81-28 81-44-26-44 9-10-25-26 9-10-25-44',
         'Rebuild this explicitly reviewed dining-table segment with a shared horizontal slab/apron, edge supports, and source-positioned raised crockery; remove floating cup and plate silhouettes.')
register('ship', 'book_table', 'Captain’s desk with open book', '10-10-68-69',
         'Put the open book on the same substantial desk top as adjacent table modules, with two page blocks and a centre crease.')
register('ship', 'retain', 'Ship cabin door', '14-15-30-31',
         'The existing shallow door and handle match the original proportions; preserve its architectural clearance.')
register('ship', 'wall_counter', 'Cabin wall above table', '16-16-10-12 2-3-9-10',
         'Give the cabin wall a consistent shallow rear plane and join its low table section to the dining-counter height; keep the porthole on the original face.')
register('ship', 'retain', 'Ship wall skirting or corridor rail', '16-16-18-19 32-16-48-19 4-4-74-74',
         'These are intentionally repeating architectural wall/rail cells; their shallow planes and trim are already appropriate.')
register('ship', 'porthole', 'Ship circular porthole wall', '2-3-18-19',
         'Attach the round porthole to a shallow connected wall panel and sill instead of letting the ring float above detached strips.')
register('ship', 'stairs', 'Ship staircase', '39-40-55-56 41-42-57-58',
         'Give the ship stair art an actual stepped profile and narrow handrail rather than a solid dark planar icon.')
register('ship', 'rail', 'Ship bow deck bollard and rail post', '45-35-61-35',
         'Replace the waist-shaped stripe extrusion with a sturdy post, cap and narrow attached rail geometry.')
register('ship', 'plaque', 'Ship bow framed panel', '46-47-62-63',
         'Make a framed shallow panel with a solid neutral back instead of the black hourglass supporting the artwork.')
register('ship', 'cabinet', 'Captain’s bookcase', '54-43-54-43 9-12-54-43',
         'Turn the thin front-only shelf into a connected, constant-depth cabinet with the original book rows on recessed fronts.')
register('ship', 'chair', 'Ship cabin chair', '7-8-23-24',
         'Build an open chair with seat, backrest and four legs; preserve its seat interaction and runtime height contract.')
register('ship', 'bed_head', 'Ship cabin bed upper headboard section', '70-71-86-87',
         'Correct the explicitly approved erroneous on relationship: the source is a horizontal two-cell bed, not a vertical stack. Remove on and author the headboard and pillow at the same mattress height as the adjacent foot/body cell. Both source head occurrences are SOLID, so no walk flag is needed.')
register('ship', 'bin', 'Ship cabin or kitchen waste bin', '72-73-88-89',
         'The baseline voxel entry is empty despite a clear round bin sprite: author a closed cylindrical body, lid rim and dark opening.')
register('ship', 'bed_base', 'Ship cabin bed lower section', '86-87-59-60 86-87-86-87',
         'Build the horizontal mattress and foot frame at the same height as the adjacent bed head. Remove the former one-voxel-wide strip and omit an interior headboard at the mattress seam.')


def front_face(model, z=13, y=(0, 16), x=(0, 16), rect=(0, 0, 16, 16)):
    """Project original art onto just the exposed front, leaving sides authored."""
    x0, y0, x1, y1 = rect
    for yy in range(*y):
        v = y1 - 1 - (yy-y[0]) * (y1-y0) // (y[1]-y[0])
        for xx in range(*x):
            u = x0 + (xx-x[0]) * (x1-x0) // (x[1]-x[0])
            if model.occ[yy, z, xx]:
                model.paint[yy, z, xx] = uv(u, v)
    return model


def geometry(family, shades, key):
    m = Model()
    light, body, dark, ink = [shade_uv(shades, n) for n in range(4)]
    # Interior neutral colours come from broad source regions, not black outlines.
    wood = shade_uv(shades, 1, (2, 1, 14, 12))

    def face(z=13, y=(0,16), x=(0,16), rect=(0,0,16,16)):
        front_face(m, z, y, x, rect)

    def cabinet():
        m.box((0,16),(0,16),(4,13),wood)
        m.box((1,15),(1,15),(12,13),ink)
        # Recess front display by one voxel; sides remain continuous across stacks.
        m.box((0,16),(0,16),(13,14),body)
        face()

    def counter(legs=False):
        if legs:
            # A border in the original top view marks a real outside table edge.
            left = np.count_nonzero(shades[:,0] == 3) >= 5
            right = np.count_nonzero(shades[:,-1] == 3) >= 5
            for x in ([1] if left else []) + ([13] if right else []):
                for z in (1,13):
                    m.box((x,x+2),(0,9),(z,z+2),dark)
            m.box((0,16),(7,9),(1,15),wood)
        else:
            m.box((0,16),(0,9),(0,16),wood)
            face(15,(0,9),rect=(0,12,16,16))
        m.box((0,16),(9,11),(0,16),wood)
        for z in range(16):
            for x in range(16):
                top_v = min(11,z*12//16)
                if key == 'tilesets.lobby/1-1-38-41': top_v = 9+z*6//16
                if key == 'tilesets.lobby/55-55-91-91': top_v = z*8//16
                m.paint[10,z,x] = uv(x,top_v)
        m.box((0,16),(8,9),(15,16),dark)
        if key in ('tilesets.lobby/1-1-38-41','tilesets.lobby/55-55-91-91'):
            m.paint[:8,15,:] = wood

    def monitor(y0=3):
        m.box((2,14),(y0,16),(4,12),body)
        m.box((3,13),(y0+2,14),(11,13),ink)
        face(12,(y0+2,14),(3,13),(3,3,13,11))
        m.box((6,10),(0,y0),(6,10),dark)
        m.box((3,13),(0,2),(4,13),body)

    if family in ('cabinet','merch_shelf','pc_base'):
        cabinet()
        if family == 'merch_shelf':
            # Each shelf gets a two-voxel projecting ledge without breaking its back.
            for y in (0,7): m.box((0,16),(y,y+1),(13,15),body)
        if family == 'pc_base':
            m.paint[:,13,:] = body
            m.box((1,15),(10,12),(12,16),body)
            face(15,(10,12),(1,15),(1,1,15,6))
    elif family in ('counter','counter_sign','counter_register','counter_bell','table_accessory'):
        counter()
        if family == 'counter_sign':
            m.box((3,13),(11,16),(6,8),body)
            face(7,(11,16),(3,13),(2,1,14,7))
        elif family == 'counter_register':
            m.box((7,14),(11,15),(5,11),body)
            m.box((3,12),(11,12),(10,15),light)
            face(10,(11,15),(7,14),(8,1,15,8))
        elif family == 'counter_bell':
            m.cylinder((11,9),3,(11,12),dark).ellipsoid((11,12,9),(2.5,2,2.5),light)
            m.box((10,12),(14,15),(8,10),ink)
        elif family == 'table_accessory':
            m.box((1,7),(11,15),(3,9),body)
            face(8,(11,15),(1,7),(1,0,7,9))
            m.ellipsoid((11,12,10),(3,1.5,2.5),light)
            m.box((8,10),(11,12),(8,11),ink)
    elif family in ('lab_table','ship_table','book_table','round_table'):
        counter(legs=True)
        if family in ('ship_table','book_table'):
            # Explicit table family only: light crockery pixels and their dark rims
            # live on the tabletop, at the original top-view source positions.
            for z in range(2,15):
                for x in range(1,15):
                    if shades[z,x] == 0 and (shades[max(0,z-1):min(16,z+2),max(0,x-1):min(16,x+2)] == 3).any():
                        m.box((x,x+1),(11,12),(z,z+1),uv(x,z))
            # Table supports at inner seams are supplied by adjoining edge cells.
        if family == 'book_table':
            m.box((4,8),(11,12),(8,13),light).box((8,12),(11,12),(8,13),light)
            m.box((7,9),(11,12),(8,13),dark)
        if family == 'round_table':
            suffix=key.rsplit('/',1)[1]
            left=suffix in ('9-39-54-55','70-55-85-86')
            back=suffix in ('9-39-54-55','39-25-55-57')
            m.occ[:7] = False
            yy,zz,xx=np.indices(m.occ.shape)
            # Four source quadrants share a 32-voxel octagonal outline.
            ox=xx if left else 15-xx; oz=zz if back else 15-zz
            m.occ[(ox+oz<7)] = False
            m.box((11,16) if left else (0,5),(0,9),(11,16) if back else (0,5),wood)
            for z in range(16):
                for x in range(16):
                    m.paint[10,z,x] = uv(x,z)
    elif family == 'lab_table_front':
        m.box((0,16),(7,10),(0,4),body)
        x=1 if key.endswith('/88-89-17-17') else 13
        m.box((x,x+2),(0,8),(1,3),body)
        m.box((0,16),(9,11),(0,3),light)
        face(3,(7,10),rect=(0,0,16,4))
    elif family in ('showcase_back','showcase_art','showcase_front'):
        counter()
        if family != 'showcase_front':
            # Rear-row sprite bottoms contain specimens, not cabinet front panels.
            # Keep that artwork on the top/back display, never stretch it over the
            # solid cabinet skirt.
            m.paint[:9,15,:] = wood
        m.box((0,16),(11,16),(2,4),body)
        face(3,(11,16),rect=(0,0,16,12))
        # Original fossil/shuttle pixels form a backed, shallow relief. No
        # invented anatomy or black wedge extruded through the whole exhibit.
        for y in range(11,16):
            v=(15-y)*12//5
            for x in range(16):
                if shades[v,x] in (0,3):
                    m.box((x,x+1),(y,y+1),(4,5),uv(x,v))
    elif family in ('plaque','wall_panel','lattice','porthole'):
        m.box((0,16),(0,16),(3,5),body)
        face(4)
        if family == 'plaque':
            m.box((0,1),(0,16),(4,6),dark).box((15,16),(0,16),(4,6),dark)
            m.box((0,16),(0,1),(4,6),dark).box((0,16),(15,16),(4,6),dark)
            if key=='tilesets.lobby/72-73-88-89':
                face(4,rect=(0,0,16,13))
            # Elevator controls and signs occupy only part of these source cells.
            # Checkerboard on the remaining pixels is floor, not plaque material.
            isolated = {
                'tilesets.lobby/1-62-33-63': ((9,16),(2,16),(8,0,16,16)),
                'tilesets.lobby/62-1-63-33': ((0,7),(2,16),(0,0,8,16)),
                'tilesets.lobby/6-1-22-33': ((0,7),(5,16),(0,0,8,12)),
                'tilesets.lobby/46-47-33-33': ((0,16),(10,16),(0,0,16,6)),
            }
            if key in isolated:
                xr,yr,rect=isolated[key]
                m.occ[:] = False
                m.box(xr,yr,(3,5),body)
                face(4,yr,xr,rect)
        if family == 'lattice':
            for x in range(1,15):
                for y in range(1,15):
                    if (x+y)%2: m.box((x,x+1),(y,y+1),(3,5),cut=True)
        if family == 'porthole':
            m.ellipsoid((8,11,5),(4,4,1.5),light)
            m.ellipsoid((8,11,6),(2.5,2.5,.7),shade_uv(shades,2,(4,0,12,8)))
    elif family == 'floor_strip':
        m.box((0,16),(0,1),(0,16),light)
        for z in range(16):
            for x in range(16): m.paint[0,z,x]=uv(x,z)
    elif family == 'chair':
        for x in (2,12):
            for z in (3,12): m.box((x,x+2),(0,7),(z,z+2),dark)
        m.box((1,15),(6,9),(2,15),body)
        m.box((1,15),(9,16),(2,4),body)
        face(3,(9,16),(1,15),(1,1,15,7))
        m.box((2,14),(8,9),(4,14),light)
    elif family == 'stairs':
        for step in range(4):
            m.box((1,15),(0,(step+1)*4),(step*4,(step+1)*4),dark)
            m.box((1,15),((step+1)*4-1,(step+1)*4),(step*4,step*4+1),light)
        # A side rail follows the flight instead of an opaque staircase-width wall.
        for step in range(4):
            m.box((0,1),(0,min(16,(step+1)*4+2)),(step*4,(step+1)*4),dark)
    elif family == 'bin':
        shell=body if key.startswith('tilesets.gate/') else shade_uv(shades,2,(3,3,13,14))
        m.cylinder((8,8),5,(0,12),shell)
        m.cylinder((8,8),5.6,(11,14),light)
        m.cylinder((8,8),3.8,(13,14),ink)
        m.cylinder((8,8),2.2,(12,13),dark)
        m.box((5,11),(4,7),(12,13),light)
    elif family in ('plant_base','plant_top'):
        if family == 'plant_base':
            m.cylinder((8,8),3.5,(0,2),dark)
            m.cylinder((8,8),4.5,(2,7),body)
            m.cylinder((8,8),5.5,(7,9),light)
            m.cylinder((8,8),4.5,(9,10),dark)
            m.box((7,9),(9,16),(7,9),body)
            m.ellipsoid((8,14,8),(3,2,2),body)
        else:
            m.box((7,9),(0,10),(7,9),body)
            for cx,cy,cz,rx,ry,rz in ((4,9,8,4,2,3),(12,9,8,4,2,3),(8,11,4,3,2,4),(8,11,12,3,2,4),(4,13,5,3,2,3),(12,13,11,3,2,3),(8,14,8,3,2,3)):
                m.ellipsoid((cx,cy,cz),(rx,ry,rz),body)
            yy,zz,xx=np.indices(m.occ.shape)
            m.paint[m.occ & ((xx+2*zz+yy)%7<2)] = light
            m.paint[m.occ & (yy<8)] = dark
    elif family in ('bush','flower_pot','boulder'):
        if family == 'flower_pot':
            m.cylinder((8,8),3,(0,3),body).box((7,9),(2,9),(7,9),dark)
            m.ellipsoid((4,10,8),(4,4,4),light).ellipsoid((11,12,8),(4,4,4),body)
        elif family == 'bush':
            m.box((6,10),(0,4),(6,10),dark)
            for p,r in [((8,7,8),(7,6,6)),((5,11,8),(4,4,4)),((11,11,8),(4,4,4))]: m.ellipsoid(p,r,body)
        else:
            m.ellipsoid((8,5,8),(7,5,6),body).ellipsoid((6,10,7),(5,5,4),body).ellipsoid((11,8,9),(4,5,4),dark)
        yy,zz,xx=np.indices(m.occ.shape)
        m.paint[m.occ & ((xx+yy+zz)%9<2)]=light
    elif family in ('statue_base','statue_head','statue_wall'):
        if family == 'statue_base':
            m.box((2,14),(0,2),(2,14),dark).box((3,13),(2,10),(3,13),body)
            m.box((2,14),(10,12),(2,14),light)
            m.ellipsoid((8,14,8),(5,4,5),body)
            m.box((5,11),(4,8),(13,14),light)
        else:
            y=0 if family=='statue_head' else -5
            body=shade_uv(shades,2)
            m.ellipsoid((8,5+y,8),(5.5,6,5),body)
            m.ellipsoid((8,10+y,8),(6,4.5,4.5),light)
            m.ellipsoid((8,8+y,12),(4,2,2),body)
            m.box((3,5),(11+y,16+y),(6,9),body).box((11,13),(11+y,16+y),(6,9),body)
            m.box((4,6),(10+y,11+y),(11,13),ink).box((10,12),(10+y,11+y),(11,13),ink)
            m.box((6,10),(6+y,7+y),(13,14),ink)
            if family=='statue_wall': m.box((0,16),(10,16),(0,2),body)
    elif family.startswith('binocular_'):
        if family == 'binocular_base':
            for x in (4,12):
                m.cylinder((x,8),2.7,(0,2),dark).box((x-1,x+1),(2,16),(7,9),body)
        else:
            for x in (4,12):
                m.box((x-1,x+1),(0,7),(7,9),body)
                m.ellipsoid((x,10,8),(2.7,5,6),body)
                m.ellipsoid((x,10,13),(2.2,3.5,1),light)
                m.ellipsoid((x,10,14),(1.2,2.4,.7),ink)
    elif family in ('cabinet_monitor','cabinet_phone'):
        if key == 'tilesets.lobby/14-15-30-31':
            counter()
            m.box((2,14),(11,16),(3,10),body)
            m.box((3,13),(12,15),(10,11),ink)
            face(10,(12,15),(3,13),(3,5,13,13))
        else:
            # Full-depth low contact is required for shared cabinet bases.
            m.box((0,16),(0,2),(4,14),body)
            monitor(2)
            if family == 'cabinet_phone':
                m.box((3,5),(4,13),(12,15),dark)
                m.box((3,7),(11,14),(12,15),body)
                m.box((3,7),(3,6),(12,15),body)
    elif family in ('lab_computer','lab_instrument','table_monitor'):
        counter(legs=True)
        if family in ('lab_computer','table_monitor'):
            m.box((3,13),(11,16),(3,9),body)
            m.box((4,12),(12,15),(9,10),ink)
            face(9,(12,15),(4,12),(3,2,13,9))
            m.box((2,14),(11,12),(10,14),light)
        else:
            m.box((2,13),(11,16),(3,12),body)
            face(11,(11,16),(2,13),(1,1,14,12))
            m.box((4,11),(11,12),(12,15),light)
    elif family == 'healer_back':
        left=key.endswith('/40-58-40-74')
        xr=(8,16) if left else (0,8)
        m.box((0,16),(0,16),(0,2),body)
        face(1)
        m.box(xr,(0,15),(2,9),body)
        m.box(xr,(5,14),(9,10),ink)
        face(9,(5,14),xr,(8,0,16,16) if left else (0,0,8,16))
        m.box(xr,(0,4),(8,14),body)
        # This monitor belongs to the front healing bed: a low machinery plinth
        # crosses the source-row boundary instead of leaving two detached units.
        m.box((0,16),(0,6),(9,16),body)
    elif family in ('healer','nurse_machine'):
        m.box((1,15),(0,10),(3,14),body)
        m.box((0,16),(10,12),(2,15),light)
        m.box((3,13),(12,16),(3,7),body)
        face(13,(0,10),(1,15),(1,6,15,16))
        face(6,(12,16),(3,13),(3,0,13,5))
        if family == 'healer':
            m.box((0,16),(0,6),(0,4),body)
            m.occ[12:] = False
            left=key.endswith('/72-76-72-6')
            m.box((8,16) if left else (0,8),(11,12),(3,14),ink)
            if left or key.endswith('/77-73-22-73'):
                for z in (5,11):
                    m.ellipsoid((3 if left else 12,12,z),(2.5,2,2.5),light)
            else:
                m.box((10,15),(12,13),(4,13),light)
                for z in (5,8,11): m.box((11,14),(13,14),(z,z+1),body)
    elif family == 'wall_counter':
        counter()
        m.box((0,16),(11,16),(0,2),light)
        face(1,(11,16),rect=(0,0,16,9))
    elif family == 'rail':
        m.box((5,10),(0,15),(6,10),body)
        m.box((3,12),(0,2),(4,12),dark).box((3,12),(14,16),(4,12),light)
        m.box((9,16),(9,11),(7,9),body)
    elif family in ('bed_base','bed_head'):
        m.box((1,15),(2,7),(0,16),wood)
        m.box((2,14),(7,9),(0,16),light)
        for x in (1,13):
            m.box((x,x+2),(0,3),(1,3) if family=='bed_head' else (13,15),dark)
        if family == 'bed_head':
            m.box((1,15),(7,15),(0,2),wood)
            m.box((3,13),(9,11),(3,7),light)
            face(1,(8,15),(1,15),(1,0,15,6))
        else:
            m.box((1,15),(3,7),(14,16),body)
            if key.endswith('/86-87-59-60'):
                face(15,(3,7),(1,15),(1,12,15,16))
    else:
        raise ValueError(f'unknown manually assigned geometry family {family}')
    return m


def generate(baseline, art=None):
    art = art or BlueArt(ROOT / 'local/minecraft/kantobridge/export/blue')
    cells = baseline['cells']
    owned = {k for k,v in cells.items() if v.get('class')=='voxels' and k.split('/')[0] in {f'tilesets.{a}' for a in ATLASES}}
    if owned != set(SPECS):
        raise ValueError(f'incomplete reviewed registry: missing={sorted(owned-set(SPECS))}; extra={sorted(set(SPECS)-owned)}')
    updates, ledger = {}, []
    for key,spec in sorted(SPECS.items()):
        row = dict(key=key, **spec, decision='retained' if spec['family']=='retain' else 'changed')
        row['source_review'] = 'sprite + old Minecraft front/side + reconstructed map context'
        if spec['family'] != 'retain':
            model = geometry(spec['family'], source_shades(art,key), key)
            updates[key] = model.entry(cells[key], source='hand-blue-interiors-v1')
            if key in ('tilesets.ship/70-71-86-87','tilesets.pokecenter/57-11-60-27'):
                updates[key].pop('on',None)
                row['metadata_change'] = {'removed_on': cells[key].get('on'), 'added_walk': False}
        ledger.append(row)
    museum = museum_specimens(baseline, art)
    updates.update({k:v for k,v in museum.items() if k in cells})
    for row in ledger:
        if row['key'] in museum:
            row['reason'] = ('Build a coherent display cabinet with an independently sculpted, supported specimen above it; '
                             'fossils have rounded shells, a backbone and raised ribs; the shuttle has a tapered fuselage, swept wings, '
                             'canopy, engines and vertical tail. Source colours remain live sprite addresses; remove duplicate specimen imagery from the cabinet top.')
            row['family'] = 'museum_sculpted_specimen'
    return updates, ledger


def museum_specimens(baseline, art):
    """Hand-shaped cross-cell museum specimens; logical shades are localized per cell.

    All structures sit on the common y=11 cabinet top. Specimen geometry is built
    once in exhibit coordinates and sliced, so fuselage, wing and shell pieces
    remain continuous across source-cell boundaries.
    """
    result={}

    def display(w,d):
        m=Model(height=32,width=w,depth=d)
        m.box((0,w),(0,10),(0,d),1)
        m.box((0,w),(10,11),(0,d),2)
        # Low blue glass rim leaves the actual specimens visible from the front.
        m.box((0,w),(11,13),(0,1),2)
        m.box((0,1),(11,12),(0,d),2).box((w-1,w),(11,12),(0,d),2)
        return m

    def slices(m,layout):
        for cz,row in enumerate(layout):
            for cx,suffix in enumerate(row):
                key='tilesets.gate/'+suffix
                shades=source_shades(art,key)
                lookup=np.array([shade_uv(shades,n) for n in range(4)],np.uint8)
                occ=m.occ[:,cz*16:cz*16+16,cx*16:cx*16+16]
                logical=m.paint[:,cz*16:cz*16+16,cx*16:cx*16+16]
                paint=lookup[logical]
                if cz==len(layout)-1:
                    # The source bottom-row cabinet panels stay on the real front.
                    # Rear-only fossil/mineral cells have no cabinet panel pixels.
                    if len(layout)>1:
                        for y in range(10):
                            paint[y,15,:]=np.arange(16,dtype=np.uint8)+(15-y*4//10)*16
                original=baseline['cells'].get(key,{})
                result[key]=encode(occ,paint,original,'hand-blue-interiors-v1',trim=True)

    fossil=display(64,16)
    for cx in (16,48):
        # Two small fossil shells with a dark stone core, pale ribs and a spine.
        fossil.ellipsoid((cx,15.5,8.5),(7.5,4.5,5.5),2)
        fossil.ellipsoid((cx,18,8.5),(6,4,4.5),1)
        fossil.box((cx-1,cx+1),(13,23),(8,10),0)
        for offset in (-6,-3,3,6):
            fossil.ellipsoid((cx+offset,17-abs(offset)*.2,10.5),(1.3,3.5,2),0)
        fossil.ellipsoid((cx,20,6),(3.5,2.5,3),0)
        fossil.box((cx-2,cx+2),(18,20),(3,4),3)
        # Small grounded stone plinth supports the entire preserved specimen.
        fossil.cylinder((cx,8.5),(6,4),(11,13),1)
    slices(fossil,[['39-40-48-70','40-40-71-58','40-40-58-70','40-25-71-44']])

    mineral=display(64,16)
    for cx in (12,20,44,52):
        mineral.ellipsoid((cx,14.5,10),(3.7,3.7,3.5),2)
        mineral.ellipsoid((cx-1,16.5,9),(2.4,2.3,2),0)
        mineral.box((cx-1,cx+1),(11,13),(8,12),1)
    slices(mineral,[['39-40-48-46','40-40-46-58','40-40-58-46','40-25-46-44']])

    shuttle=display(48,32)
    # Display mounts, fuselage, tapered nose and a deep dark engine cluster.
    for cx in (11,25,39):
        shuttle.box((cx-1,cx+1),(11,15),(15,17),2)
    shuttle.ellipsoid((27,16,16),(17.5,4,4),0)
    shuttle.ellipsoid((43,16,16),(4.5,2.6,2.7),0)
    shuttle.box((7,17),(14,18),(13,19),0)
    for z in (13,19):
        shuttle.ellipsoid((12,14,z),(5,2.2,2),2)
        shuttle.box((7,8),(13,16),(z-1,z+1),3)
    yy,zz,xx=np.indices(shuttle.occ.shape)
    span=np.abs(zz+.5-16)
    wing=(span<=13)&(xx+.5>=14+span*.55)&(xx+.5<=32-span*.45)&(yy>=14)&(yy<16)
    shuttle.put(wing,0)
    shuttle.put(wing&(yy==14)&(span>5),3)
    # Rear horizontal stabilizers and an unmistakable swept vertical tail.
    tailplane=(np.abs(zz+.5-16)<=8)&(xx>=8)&(xx<=16-np.abs(zz+.5-16)*.3)&(yy>=16)&(yy<18)
    shuttle.put(tailplane,0)
    tail=(xx>=7)&(xx<=14-(yy-18)*.45)&(yy>=18)&(yy<28)&(zz>=15)&(zz<17)
    shuttle.put(tail,0)
    shuttle.put(tail&(xx==7),2)
    shuttle.ellipsoid((36,19.2,16),(4.5,1.8,2.8),3)
    shuttle.ellipsoid((36.5,20.1,16),(3,1,2),2)
    slices(shuttle,[['64-65-80-81','66-67-82-82','68-69-84-85'],
                    ['86-87-50-51','88-89-50-51','90-91-50-51']])
    return result


def generate_additions(baseline, art=None):
    """Four proven solid mineral-case cells with no previous fitted entry.

    Their review sheets must be labelled source/current-only: the original
    baseline has no fitted voxel entry, so an old model cannot be invented.
    """
    art = art or BlueArt(ROOT/'local/minecraft/kantobridge/export/blue')
    suffixes=('39-40-48-46','40-40-46-58','40-40-58-46','40-25-46-44')
    sculpted=museum_specimens(baseline,art)
    updates,ledger={},[]
    for x,suffix in enumerate(suffixes,1):
        key='tilesets.gate/'+suffix
        if key in baseline['cells']:
            raise ValueError(f'expected absent mineral-case baseline entry: {key}')
        updates[key]=sculpted[key]
        ledger.append(dict(key=key,label='Museum mineral showcase rear section',decision='added',family='showcase_back',
            reason='This source cell is SOLID in MUSEUM_2F but has no previous fitted entry. Add a coherent mineral cabinet with individually sculpted raised stone specimens and facets, matching the existing front row. Compare source/current only; no invented before model.',
            source_position=dict(map='MUSEUM_2F',x=x,y=4,flags=0),baseline='no fitted entry',
            source_review='reconstructed source map and absent baseline table entry'))
    return updates,ledger


def render_museum_review(entries, art, output):
    from PIL import Image, ImageDraw
    from shape_fit.hand_check import picture
    cases=[
        ('Ribbed fossil specimens',[['39-40-48-70','40-40-71-58','40-40-58-70','40-25-71-44'],
                                   ['49-60-50-51','83-63-50-51','60-60-50-51','60-44-50-51']]),
        ('Sculpted mineral specimens',[['39-40-48-46','40-40-46-58','40-40-58-46','40-25-46-44'],
                                      ['49-60-50-51','83-63-50-51','60-60-50-51','60-44-50-51']]),
        ('Shuttle: fuselage, wings, canopy, engines and tail',[['64-65-80-81','66-67-82-82','68-69-84-85'],
                                                           ['86-87-50-51','88-89-50-51','90-91-50-51']]),
    ]
    sheet=Image.new('RGB',(1300,1230),'#eeeeef');draw=ImageDraw.Draw(sheet)
    for n,(label,suffixes) in enumerate(cases):
        layout=[['tilesets.gate/'+k for k in row] for row in suffixes]
        occ=np.zeros((32,len(layout)*16,len(layout[0])*16),bool)
        colors=np.zeros(occ.shape+(3,),np.uint8)
        for cz,row in enumerate(layout):
            for cx,key in enumerate(row):
                o,p=decode(entries[key]);art_pixels=np.asarray(art.cell(key,'PEWTER').convert('RGB'))
                occ[:len(o),cz*16:cz*16+16,cx*16:cx*16+16]=o
                colors[:len(o),cz*16:cz*16+16,cx*16:cx*16+16]=art_pixels[p//16,p%16]
        path=output/f'museum-assembly-{n+1}.png'
        picture(occ,colors,path,8)
        draw.text((15,n*410+10),label+' — source / authored voxel preview',fill='black')
        source=art.drawing(layout,'PEWTER').convert('RGB');source=source.resize((source.width*6,source.height*6),Image.Resampling.NEAREST)
        sheet.paste(source,(15,n*410+80))
        iso=Image.open(path).convert('RGB');iso.thumbnail((850,385))
        sheet.paste(iso,(420,n*410+25))
    sheet.save(output/'museum-sculpted-review.png')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=ROOT/'local/model-gallery/blue-rollout-study/interiors')
    parser.add_argument('--previews',action='store_true')
    args=parser.parse_args()
    art=BlueArt(ROOT/'local/minecraft/kantobridge/export/blue')
    updates,ledger=generate(load_baseline(),art)
    additions,added_ledger=generate_additions(load_baseline(),art)
    args.output.mkdir(parents=True,exist_ok=True)
    (args.output/'updates.json').write_text(json.dumps(updates,indent=2)+'\n',encoding='utf-8')
    (args.output/'review-ledger.json').write_text(json.dumps(ledger,indent=2)+'\n',encoding='utf-8')
    (args.output/'additions.json').write_text(json.dumps(additions,indent=2)+'\n',encoding='utf-8')
    (args.output/'additions-ledger.json').write_text(json.dumps(added_ledger,indent=2)+'\n',encoding='utf-8')
    if args.previews:
        for key,entry in (updates|additions).items():
            palette=art.locations[key][0]['palette'] if art.locations[key] else 'GRAYMON'
            render(entry,art.cell(key,palette),args.output/'previews'/key.replace('/','_'))
        render_museum_review(updates|additions,art,args.output)
    print(json.dumps(dict(reviewed=len(ledger),changed=len(updates),retained=len(ledger)-len(updates),added=len(additions))))


if __name__=='__main__':
    main()
