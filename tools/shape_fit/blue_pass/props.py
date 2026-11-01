"""Individually reviewed Blue household, laboratory and club props.

Every key below was checked against its reconstructed source cell, map context,
and both historical Minecraft views. Shapes are semantic, explicitly selected
per key; source UV addresses keep all map palette variants working. This module
does not edit the runtime table. Run with --out to write an auditable patch.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from .common import Model, ROOT, encode, load_baseline, shade_uv, source_shades, uv


# key | semantic label | decision/family | family options
# 'keep' entries deliberately preserve reviewed existing geometry, not a default
# fallback. In particular, mansion table textures shared by building facades
# cannot be changed globally without changing the approved Blue buildings.
SPECS = {
"house": """
1-40-1-1|Scattered floorboard splinters|debris|boards
10-11-8-9|Potted plant leafy crown|foliage|
14-15-30-31|Bookcase lower cabinet|cabinet|
2-3-18-19|Low square stool|keep|Existing seat-height stool has a clear seat and supports; retain the seat interaction.
26-27-24-25|Potted plant pot and stem|pot|
28-29-68-69|Trashed house broken pot|debris|pot
34-1-1-34|Trashed house loose floorboards|debris|boards
38-39-54-47|Dining table northwest corner|keep|Hand-built horizontal tabletop and corner leg already join the other table quarters.
38-39-70-71|Desk northwest quarter with object|keep|Existing hand-built desktop and raised object already distinguish the object from the table.
38-41-14-15|Bookcase upper shelves|cabinet|
38-41-48-49|Display bookcase upper shelves|cabinet|
39-41-47-57|Dining table northeast corner|keep|Hand-built horizontal tabletop and corner leg already join the other table quarters.
42-43-12-13|Fallen plant leaves|debris|leaves
44-37-53-56|Fallen pot and broken rim|debris|pot
47-57-58-59|Dining table southeast corner|keep|Hand-built horizontal tabletop and corner leg already join the other table quarters.
48-49-30-31|Display bookcase lower cabinet|cabinet|
54-47-60-58|Dining table southwest corner|keep|Hand-built horizontal tabletop and corner leg already join the other table quarters.
72-73-88-89|Developer room wall display|frame|
80-81-82-83|Broken table planks|debris|boards
86-87-60-58|Desk southwest quarter with object|keep|Existing hand-built desktop and object already read correctly in both views.
""",
"reds_house": """
0-0-38-39|Bedroom desk raised rear left|deskback|left
0-0-39-41|Bedroom desk raised rear right|deskback|right
10-11-26-27|Downward stair opening|stairwell|right
12-13-28-29|Upward staircase|keep|Stepped three-dimensional stair profile is already correct.
14-15-30-31|Approved bedroom games console|keep|Protected user-approved console; retain the current baseline byte-for-byte.
2-3-18-19|Square stool|keep|Existing low seat and legs already read correctly; retain seat interaction.
34-35-50-51|Bedroom bookcase lower cabinet|cabinet|
36-37-52-53|Wall louvered panel|frame|
38-39-54-55|Table northwest quarter with centrepiece|keep|Hand-built horizontal tabletop and raised centrepiece already join the neighbouring quarters.
38-41-48-49|Bedroom bookcase upper shelves|cabinet|
40-41-56-57|Table northeast quarter with centrepiece|keep|Hand-built horizontal tabletop and raised centrepiece already join the neighbouring quarters.
42-43-58-59|Table southeast quarter|keep|Existing tabletop and corner leg have appropriate depth and join the adjacent quarters.
44-42-60-58|Table southwest quarter|keep|Existing tabletop and corner leg have appropriate depth and join the adjacent quarters.
45-46-61-62|Bed head and pillow|bed|head
6-7-22-23|Television on a low stand|crt|
61-62-63-47|Bed foot and blanket|bed|foot
64-65-32-33|Bedroom computer upper unit|computer|
66-67-50-51|Bedroom computer desk base|cabinet|
68-69-8-9|Bedroom plant leafy crown|foliage|
70-71-24-25|Bedroom plant pot and stem|pot|
""",
"lab": """
10-11-26-27|Lab machinery lower double vent|cabinet|
11-75-27-91|Lab machinery joined control modules|cabinet|
14-15-30-31|Lab square stool|keep|Existing low square seat is appropriately proportioned and keeps seat interaction.
2-3-18-19|Lab computer on workbench|keep|Existing hand-built computer, tabletop and legs already have distinct depth.
25-37-34-43|Warden display table right with placard|keep|Hand-built tabletop and raised placard already form a coherent joined display table.
36-24-42-34|Warden display table left with placard|keep|Hand-built tabletop and raised placard already form a coherent joined display table.
4-5-20-21|Lab instrument on workbench|keep|Existing hand-built instrument and table already have clear separated volumes.
40-41-40-41|Lab bookcase lower shelves|cabinet|
44-45-60-61|Lab plant leafy crown|foliage|
46-47-62-63|Lab plant pot and stem|pot|
48-49-34-34|Telephone on laboratory cabinet|phone|
50-51-67-68|Warden movable boulder|rock|
6-7-22-23|Lab workbench stool|keep|Existing stool is already shallow and retains seat interaction.
64-65-80-72|Warden worktable left with equipment|keep|Hand-built table and equipment already read correctly from front and side.
64-65-80-81|Lab table northwest corner|keep|Hand-built horizontal tabletop and corner leg already join the other table quarters.
64-66-10-11|Lab upper vent cabinet|cabinet|
64-66-40-41|Lab upper bookcase shelves|cabinet|
65-65-81-81|Long lab table north middle|table|north
65-66-73-82|Warden worktable right with equipment|keep|Hand-built table and equipment already read correctly from front and side.
65-66-81-82|Lab table northeast corner|keep|Hand-built horizontal tabletop and corner leg already join the other table quarters.
73-82-58-84|Lab table southeast with object|keep|Existing table corner and raised object already join the other table quarters.
74-59-90-59|Lab machine end cabinet|cabinet|
74-75-90-91|Lab machine middle cabinet|cabinet|
75-10-91-26|Lab machine joined vent cabinets|cabinet|
80-72-83-58|Lab table southwest with object|keep|Existing table corner and raised object already join the other table quarters.
80-81-83-58|Long lab table southwest|table|sw
81-81-58-58|Long lab table south middle|table|south
81-82-58-84|Long lab table southeast|table|se
87-88-28-71|Warden table northwest with object|keep|Hand-built table and object already have clear separated volumes.
88-89-71-29|Warden table northeast with object|keep|Hand-built table and object already have clear separated volumes.
""",
"interior": """
11-12-27-28|Bill computer on workbench|keep|Existing hand-built computer, desk and supports already read as separate three-dimensional parts.
13-14-29-30|Bill instrument on workbench|keep|Existing hand-built instrument and desk already have coherent depth.
19-20-35-36|Fan Club framed Pokemon picture|frame|
24-25-40-41|Bill transporter rear centre|transporter|1,0
26-37-42-53|Bill transporter rear right|transporter|2,0
31-65-31-31|Fan Club sofa front left|sofa|front,left
31-72-72-64|Fan Club octagonal table northwest|keep|Existing hand-built diagonal tabletop corner and leg match the assembled table.
32-23-48-39|Bill transporter rear left|transporter|0,0
34-64-92-64|Fan Club table centrepiece right|keep|Hand-built table and centrepiece parts already align across the shared edge.
38-38-54-54|Bill transporter connecting conduit|pipe|
43-44-59-60|Bill workbench stool|keep|Existing low stool is correctly proportioned and retains seat interaction.
52-52-50-50|Fan Club sofa back middle|sofa|back,middle
52-52-51-68|Fan Club sofa back right|sofa|back,right
52-52-68-49|Fan Club sofa back left|sofa|back,left
56-57-1-2|Bill transporter front centre|transporter|1,1
58-69-15-31|Bill transporter front right|transporter|2,1
61-62-59-60|Fan Club guest stool|keep|Existing low stool already reads as a seat and retains interaction.
63-64-79-63|Fan Club octagonal table southwest|keep|Existing hand-built diagonal tabletop corner and leg match the assembled table.
64-33-64-91|Fan Club table centrepiece left|keep|Hand-built table and centrepiece parts already align across the shared edge.
64-64-64-64|Fan Club tabletop middle|keep|Intentionally plain horizontal top joins the surrounding octagonal table.
64-78-64-78|Fan Club table east edge|keep|Existing tabletop edge aligns with the octagonal table.
64-81-81-82|Fan Club octagonal table southeast|keep|Existing hand-built diagonal tabletop corner and leg match the assembled table.
66-66-31-31|Fan Club sofa front middle|sofa|front,middle
67-31-31-31|Fan Club sofa front right|sofa|front,right
69-55-31-80|Bill transporter front left|transporter|0,1
73-31-64-73|Fan Club octagonal table northeast|keep|Existing hand-built diagonal tabletop corner and leg match the assembled table.
74-74-18-64|Fan Club table north centre right|keep|Existing horizontal tabletop and ornament already join the centrepiece.
74-74-64-17|Fan Club table north centre left|keep|Existing horizontal tabletop and ornament already join the centrepiece.
77-64-77-64|Fan Club table west edge|keep|Existing tabletop edge aligns with the octagonal table.
83-84-85-86|Silph teleport floor pad|floorpad|
""",
"mansion": """
10-11-26-27|Mansion downward stair opening|stairwell|back
12-13-28-29|Mansion upward staircase|keep|Existing steps have a correct three-dimensional ascending profile.
15-15-31-31|Rooftop ornamental railing middle|keep|Part of the user-approved Blue building architecture; preserve shared facade geometry.
15-33-31-49|Rooftop ornamental railing right|keep|Part of the user-approved Blue building architecture; preserve shared facade geometry.
2-3-18-19|Mansion square stool|keep|Existing low stool is appropriately proportioned and retains seat interaction.
30-93-6-7|Rooftop building vent|keep|Part of the user-approved Blue building architecture; preserve shared facade geometry.
34-35-50-51|Mansion cabinet lower shelves|cabinet|
36-37-64-65|Mansion computer on desk|keep|Existing hand-built screen, desktop and legs already read correctly from both directions.
38-39-54-55|Table corner shared with roof left panel|keep|Also used on Celadon Mansion roof; preserve the user-approved building geometry.
38-41-34-35|Mansion cabinet upper shelves|cabinet|
38-41-40-21|Mansion display cabinet upper|cabinet|
39-39-55-55|Table edge shared with roof middle panel|keep|Also used on Celadon Mansion roof; preserve the user-approved building geometry.
39-41-55-57|Table corner shared with roof right panel|keep|Also used on Celadon Mansion roof; preserve the user-approved building geometry.
42-15-43-31|Rooftop ornamental railing left|keep|Part of the user-approved Blue building architecture; preserve shared facade geometry.
45-46-61-62|Mansion bed head and pillow|bed|head
48-30-48-30|Rooftop blue wall panel|keep|Part of the user-approved Blue building architecture; preserve shared facade geometry.
52-53-66-67|Mansion instrument on desk|keep|Existing hand-built instrument and table have clear separate volumes.
54-55-54-55|Rooftop left face|keep|Part of the user-approved Blue building architecture; preserve shared facade geometry.
54-55-60-58|Mansion table southwest corner|keep|Existing horizontal tabletop and corner leg already form a coherent table.
55-55-55-55|Rooftop central face|keep|Part of the user-approved Blue building architecture; preserve shared facade geometry.
55-55-58-58|Mansion table south middle|keep|Existing horizontal table edge already joins its adjacent quarters.
55-57-55-57|Rooftop right face|keep|Part of the user-approved Blue building architecture; preserve shared facade geometry.
55-57-58-59|Mansion table southeast corner|keep|Existing horizontal tabletop and corner leg already form a coherent table.
56-87-50-51|Mansion display cabinet lower|cabinet|
61-62-63-47|Mansion bed foot and blanket|bed|foot
68-69-8-9|Mansion plant leafy crown|foliage|
70-71-24-25|Mansion plant pot and stem|pot|
76-76-38-41|Mansion cupboard upper section|cabinet|
""",
"facility": """
1-1-39-47|Gym statue creature head|statue|head
11-12-27-28|Facility three-book shelf|cabinet|
13-14-29-30|Facility long cabinet back panel|cabinet|
13-2-29-53|Facility table northwest|keep|Existing tabletop and corner leg have appropriate depth and align with the adjacent quarters.
2-14-53-30|Facility table northeast|table|ne
2-2-18-18|Facility cabinet front middle|cabinet|
29-30-18-18|Facility cabinet front end|cabinet|
29-30-29-30|Facility long cabinet middle panel|cabinet|
29-53-29-53|Facility table west edge|keep|Existing horizontal table edge already aligns with its neighbour.
29-53-68-69|Facility table southwest|keep|Existing horizontal tabletop and corner leg already join the other table quarters.
3-4-19-78|Facility staircase|keep|Existing stepped profile is coherent and preserves the marked walk behaviour.
33-49-32-48|Saffron teleport floor pad|floorpad|
40-41-56-57|Facility bed head and pillow|bed|head
42-42-13-14|Gym machine upper cabinet|cabinet|
5-6-21-22|Facility plant leafy crown|foliage|
53-30-53-30|Facility table east edge|keep|Existing horizontal table edge already aligns with its neighbour.
53-30-69-68|Facility table southeast|keep|Existing horizontal tabletop and corner leg already join the other table quarters.
55-63-61-62|Gym statue pedestal and shoulders|statue|base
56-57-25-26|Facility bed foot and blanket|bed|foot
64-64-80-80|Facility striped industrial cabinet|cabinet|
7-15-23-31|Facility plant pot and stem|pot|
74-75-76-77|Gym machine lower twin controls|cabinet|
83-84-54-38|Mansion broken rock debris|debris|rock
9-10-25-26|Facility framed wall panel|frame|
""",
"club": """
11-12-27-28|Bicycle first row left half|bicycle|0
12-14-28-9|Bicycle second row right half|bicycle|1
14-31-9-15|Bicycle first row right half|bicycle|1
15-11-30-27|Bicycle second row left half|bicycle|0
29-13-21-22|Bicycle shop repair chair|repairchair|
42-43-38-39|Link room round stool|keep|Existing low round seat already reads correctly and retains seat interaction.
59-60-55-56|Colosseum left terminal|link|left
6-1-6-17|Bicycle shop rear display rack|rack|
61-62-57-58|Colosseum right terminal|link|right
68-69-67-70|Trade Centre wall screen|frame|
71-72-23-24|Trade Centre left terminal|link|left
73-74-23-24|Trade Centre right terminal|link|right
""",
}

REASONS = {
    "cabinet": "Replaced the pinched/slab depth with a continuous box, side walls and a recessed source-art front; stacked units share the same footprint.",
    "pot": "Built a round pot, raised rim, soil and continuous stem; removed the floor pattern from the volume and restored a non-empty base.",
    "foliage": "Built a connected stem and radial leaves with real depth; the plant crown joins its pot instead of becoming a flat fan.",
    "debris": "Placed separate broken pieces close to the ground, removing the old upright floor/shadow extrusion.",
    "frame": "Replaced the conical side with a thin framed panel while retaining the detailed source-art face.",
    "deskback": "Built a shallow raised rear desktop with supports, removing the tall extrusion of the blank wall above it.",
    "bed": "Built a horizontal mattress, bed frame, legs and pillow/headboard or footboard; the two floor cells join along their shared edge.",
    "crt": "Built a CRT housing, inset screen, rear body and low stand with actual depth and solid side materials.",
    "computer": "Built the computer housing and display above a continuous desk top, aligned to its lower cabinet footprint.",
    "phone": "Built a cabinet, telephone body and raised receiver instead of disconnected floating horizontal strips.",
    "rock": "Built a grounded rounded boulder with stepped mineral facets instead of a black-sided slab.",
    "table": "Replaced a vertical tabletop/shadow extrusion with a horizontal table surface and the appropriate corner support.",
    "sofa": "Built matching sofa cushions, arms, back and feet across the six map cells, leaving the patterned floor empty.",
    "transporter": "Built one six-cell cylindrical transporter with a dome, front door and side equipment; every part is sliced from the same continuous assembly.",
    "pipe": "Built two supported horizontal conduits with circular cross-sections and collars, aligned with the transporter side equipment.",
    "statue": "Built a square pedestal, rounded shoulders and sculpted head with ears; stacked halves share a connected central support.",
    "bicycle": "Built the paired bicycle as one 32-voxel assembly with open wheel rings, frame tubes, saddle, fork and transverse handlebars/pedals.",
    "repairchair": "Built the repair chair with separate seat, back, four legs and a small tool frame.",
    "link": "Built a solid terminal cabinet, raised display and controls with sensible depth, preserving the paired left/right layout.",
    "rack": "Built a thin display rack with shelves and wheel support, excluding the horizontal wall pattern from the object volume.",
    "stairwell": "Replaced the full-height solid wall with a shallow floor opening and low edge rail; maps every source pixel onto the horizontal surface and preserves walk behaviour.",
    "floorpad": "Replaced the thick/cropped front-projected pad with a complete thin floor tile; maps the original diamond motif onto its horizontal top at native proportions.",
}


def specifications():
    result = {}
    for atlas, rows in SPECS.items():
        for row in rows.strip().splitlines():
            ids, label, family, arg = row.strip().split("|", 3)
            key = f"tilesets.{atlas}/{ids}"
            if key in result:
                raise ValueError(f"duplicate specification: {key}")
            result[key] = (label, family, arg)
    return result


def _front(m, rect=(0, 0, 16, 16), side=0):
    """Source art belongs to the exposed front, not to every voxel side."""
    h, d, w = m.occ.shape
    for y in range(h):
        for x in range(w):
            zs = np.flatnonzero(m.occ[y, :, x])
            if len(zs):
                u = min(rect[2]-1, rect[0] + x*(rect[2]-rect[0])//w)
                v = min(rect[3]-1, rect[1] + (h-1-y)*(rect[3]-rect[1])//h)
                m.paint[y, zs[-1], x] = uv(u, v)
    return m


def _line(m, a, b, radius, texel):
    yy, zz, xx = np.indices(m.occ.shape)
    p = np.stack((xx+.5, yy+.5, zz+.5), -1)
    a, b = np.asarray(a), np.asarray(b)
    v = b-a
    t = np.clip(((p-a)*v).sum(-1)/(v*v).sum(), 0, 1)
    return m.put(((p-(a+t[..., None]*v))**2).sum(-1) <= radius**2, texel)


def _transporter():
    # Materials are shade numbers until each cell receives its own source UVs.
    m = Model(height=32, width=48, depth=32)
    m.cylinder((24, 16), (12, 11), (0, 3), 3)
    m.cylinder((24, 16), (11, 10), (3, 23), 1)
    m.ellipsoid((24, 23, 16), (11, 7, 10), 2)
    m.cylinder((24, 16), (12, 11), (2, 4), 2)
    m.cylinder((24, 16), (12, 11), (20, 22), 2)
    m.ellipsoid((24, 27, 16), (7, 3, 6), 0)
    # Door frame and inset window face south across the row boundary.
    m.box((18, 30), (3, 20), (25, 28), 3)
    m.box((20, 28), (5, 18), (28, 29), 2)
    m.box((23, 25), (4, 19), (29, 30), 1)
    m.box((18, 30), (1, 3), (27, 32), 1)
    for x in (2, 36):
        m.box((x, x+10), (0, 3), (9, 24), 3)
        m.box((x+2, x+8), (3, 18), (10, 23), 1)
        m.box((x, x+10), (11, 19), (8, 18), 2)
        m.box((x+2, x+8), (13, 17), (18, 19), 3)
        m.box((x+1, x+9), (19, 21), (9, 17), 3)
    m.box((10, 38), (11, 15), (10, 14), 1)
    return m


def _bicycle():
    m = Model(width=32)
    yy, zz, xx = np.indices(m.occ.shape)
    for cx in (6, 26):
        dist = ((xx+.5-cx)**2+(yy+.5-5.5)**2)**.5
        m.put((dist >= 3.4)&(dist <= 5.1)&(zz >= 7)&(zz <= 8), 3)
        m.put((dist >= 3.4)&(dist <= 4.2)&(zz >= 7)&(zz <= 8), 0)
        _line(m, (cx-3.4, 5.5, 8), (cx+3.4, 5.5, 8), .55, 0)
        _line(m, (cx, 2, 8), (cx, 9, 8), .55, 0)
    for a, b in [((6,5.5,8),(13,12,8)), ((13,12,8),(18,5.5,8)),
                 ((18,5.5,8),(6,5.5,8)), ((13,12,8),(23,12,8)),
                 ((23,12,8),(18,5.5,8)), ((23,12,8),(26,5.5,8)),
                 ((23,12,8),(22,15,8))]:
        _line(m,a,b,.85,2)
    m.box((10, 16),(12,14),(6,10),3)
    m.box((21,24),(14,16),(4,12),0)
    m.box((17,20),(5,7),(4,12),3)
    return m


def build(family, arg, shades):
    p = [shade_uv(shades, s) for s in range(4)]
    m = Model()
    if family == "cabinet":
        m.box((1,15),(0,16),(4,12),p[2])
        m.box((1,2),(0,16),(12,13),p[3]).box((14,15),(0,16),(12,13),p[3])
        m.box((2,14),(0,16),(12,13),p[1])
        _front(m)
    elif family == "pot":
        m.cylinder((8,8),3.5,(0,2),p[3]).cylinder((8,8),4,(2,6),p[2])
        m.cylinder((8,8),5,(6,8),p[0]).cylinder((8,8),4,(8,9),p[3])
        m.cylinder((8,8),1.6,(8,16),p[2])
        m.ellipsoid((6,12,8),(3,2,2),p[0]).ellipsoid((10,14,8),(3,2,2),p[2])
    elif family == "foliage":
        m.cylinder((8,8),1.6,(0,13),p[2])
        for x,y,z,rx,ry,rz,s in [(4,7,8,4,2.5,2,2),(12,7,8,4,2.5,2,1),
                (8,8,4,2,2.5,4,1),(8,8,12,2,2.5,4,2),
                (5,12,6,3.5,3,2.3,1),(11,12,10,3.5,3,2.3,2),
                (10,13,5,2.5,3,3,2),(6,12,11,2.5,3,3,1)]:
            # Shade 1 in these sprites is the checked floor showing between
            # leaves (red in Cinnabar), not foliage. The leaves use 0 and 2.
            m.ellipsoid((x,y,z),(rx,ry,rz),p[0 if s==1 else s])
    elif family == "frame":
        m.box((1,15),(1,16),(7,9),p[2])
        m.box((0,16),(0,2),(7,10),p[3])
        _front(m)
    elif family == "deskback":
        m.box((0,16),(7,9),(9,16),p[2]).box((0,16),(9,13),(9,11),p[1])
        m.box((0,16),(8,9),(11,16),p[1])
        edge=(1,3) if arg=="left" else (13,15)
        m.box(edge,(0,7),(11,14),p[2])
    elif family == "bed":
        m.box((1,15),(3,5),(0,16),p[2]).box((2,14),(5,8),(0,16),p[0])
        for x in (1,13):
            m.box((x,x+2),(0,4),(1,3) if arg=="head" else (13,15),p[2])
        if arg=="head":
            m.box((1,15),(4,14),(0,2),p[1]).box((3,13),(7,9),(3,8),p[0])
            m.box((2,14),(8,9),(10,16),p[0])
        else:
            m.box((2,14),(8,9),(0,13),p[0]).box((1,15),(4,10),(14,16),p[2])
        m.box((2,3),(8,9),(10,16) if arg=="head" else (0,13),p[1])
        m.box((13,14),(8,9),(10,16) if arg=="head" else (0,13),p[1])
    elif family in ("crt","computer"):
        m.box((2,14),(0,2),(3,13),p[2])
        m.box((6,10),(2,5),(6,10),p[3])
        m.box((3,13),(5,14),(3,11),p[2])
        m.box((2,14),(4,15),(10,12),p[3])
        m.box((4,12),(7,13),(12,13),p[1])
        # Keep the recognisable screen pixels on its actual display surface.
        for y in range(7,13):
            for x in range(4,12):
                source_u=3+(x-4)*10//8
                source_v=(7 if family=="crt" else 5)+(12-y)
                m.paint[y,12,x]=uv(source_u,source_v)
        m.box((10,12),(5,6),(12,13),p[0])
        if family=="computer":
            m.box((1,15),(0,2),(3,13),p[2]).box((4,12),(2,3),(12,15),p[0])
    elif family == "table":
        # Retained hand-authored neighbours use y=6..7 for the tabletop.
        m.box((0,16),(6,7),(0,16),p[2]).box((0,16),(7,8),(0,16),p[1])
        if arg in ("sw","se","ne"):
            x=(1,3) if arg=="sw" else (13,15)
            z=(1,3) if arg=="ne" else (13,15)
            m.box(x,(0,6),z,p[2])
        if arg in ("south","sw","se"):
            m.box((0,16),(4,6),(13,15),p[2])
    elif family == "phone":
        m.box((1,15),(0,9),(4,13),p[2]).box((1,15),(9,10),(3,14),p[1])
        m.box((4,12),(10,12),(6,11),p[2])
        m.box((2,5),(11,14),(6,10),p[3]).box((11,14),(11,14),(6,10),p[3])
        m.box((4,12),(13,15),(6,9),p[3])
        m.box((6,10),(12,13),(10,12),p[0])
    elif family == "rock":
        m.ellipsoid((8,6,8),(7,7,6),p[2])
        m.ellipsoid((6,9,6),(4.5,4.5,4),p[0])
        m.box((3,12),(0,2),(3,13),p[2])
    elif family == "debris":
        if arg=="boards":
            for a,b in [((2,1,3),(11,1,5)),((5,2,10),(14,2,7)),((2,1,13),(7,1,11))]:
                _line(m,a,b,1.1,p[2])
        elif arg=="leaves":
            for x,z,rx,rz in [(4,4,3,2),(10,7,4,2),(5,11,3,2),(12,13,2,2)]:
                m.ellipsoid((x,1,z),(rx,1,rz),p[2])
        elif arg=="pot":
            m.ellipsoid((7,3,8),(4,3,4),p[2]).ellipsoid((7,3,10),(2.5,2.5,4),p[0],cut=True)
            m.box((12,15),(0,2),(4,7),p[1]).box((2,5),(0,2),(12,14),p[2])
        else:
            m.ellipsoid((6,3,7),(5,4,4),p[0]).ellipsoid((11,2,11),(3,2,3),p[2])
    elif family == "sofa":
        row,col=arg.split(",")
        zr=(9,16) if row=="back" else (0,7)
        m.box((0,16),(4,7),zr,p[2]).box((1,15),(7,9),zr,p[1])
        if row=="back":
            m.box((0,16),(8,15),(9,12),p[2]).box((1,15),(9,14),(12,13),p[1])
        if col!="middle":
            xr=(0,3) if col=="left" else (13,16)
            m.box(xr,(7,12),zr,p[2])
            m.box(xr,(0,4),(10,13) if row=="back" else (3,6),p[3])
    elif family == "transporter":
        whole=_transporter(); cx,cz=map(int,arg.split(","))
        m=Model(height=32)
        m.occ=whole.occ[:,cz*16:(cz+1)*16,cx*16:(cx+1)*16].copy()
        m.paint=np.take(np.array(p,np.uint8),whole.paint[:,cz*16:(cz+1)*16,cx*16:(cx+1)*16])
    elif family == "pipe":
        yy,zz,xx=np.indices(m.occ.shape)
        for cy in (11,15):
            mask=(yy+.5-cy)**2+(zz+.5-12)**2<=2.2**2
            m.put(mask,p[1])
            m.put(mask&((xx<2)|(xx>13)),p[3])
        m.box((2,4),(0,9),(11,13),p[2]).box((12,14),(0,9),(11,13),p[2])
    elif family == "statue":
        if arg=="base":
            m.box((2,14),(0,3),(2,14),p[3]).box((3,13),(3,10),(3,13),p[1])
            m.box((5,11),(5,8),(13,14),p[0])
            m.ellipsoid((8,13,8),(6,5,5),p[0])
            m.cylinder((8,8),3,(13,16),p[0])
        else:
            m.ellipsoid((8,3,8),(5,4,4),p[2])
            m.ellipsoid((4,5,8),(2,3,2),p[1]).ellipsoid((12,5,8),(2,3,2),p[1])
            m.box((5,7),(3,4),(11,12),p[3]).box((9,11),(3,4),(11,12),p[3])
            m.box((7,9),(1,3),(11,13),p[0])
            m.cylinder((8,10),1.2,(6,10),p[0])
    elif family == "bicycle":
        whole=_bicycle(); x=int(arg)*16
        m.occ=whole.occ[:,:,x:x+16].copy()
        m.paint=np.take(np.array(p,np.uint8),whole.paint[:,:,x:x+16])
    elif family == "repairchair":
        m.box((2,13),(5,7),(3,13),p[2]).box((3,12),(7,8),(4,12),p[1])
        m.box((11,14),(7,16),(3,6),p[2]).box((10,11),(9,15),(3,6),p[0])
        for x in (2,11):
            for z in (3,11):m.box((x,x+2),(0,5),(z,z+2),p[3])
        m.box((2,4),(8,11),(4,11),p[3])
    elif family == "link":
        m.box((0,16),(0,8),(3,14),p[2]).box((0,16),(8,10),(2,15),p[1])
        xr=(6,15) if arg=="left" else (1,10)
        m.box(xr,(10,16),(4,8),p[2])
        m.box((xr[0]+1,xr[1]-1),(11,15),(8,9),p[3])
        m.box((xr[0]+2,xr[1]-2),(12,14),(9,10),p[2])
        m.box((xr[0]+2,xr[0]+3),(13,14),(10,11),p[0])
        m.box((3,13),(10,11),(11,14),p[3])
        m.box((4,6),(11,12),(12,13),p[0]).box((8,10),(11,12),(12,13),p[0])
        m.box((2,14),(2,6),(14,15),p[1])
    elif family == "rack":
        m.box((11,14),(0,16),(6,9),p[3])
        m.box((5,14),(0,2),(5,12),p[2]).box((5,14),(13,15),(6,10),p[1])
        m.box((8,11),(4,11),(6,9),p[2])
        m.ellipsoid((10,4,10),(3,3,1),p[3]).ellipsoid((10,4,11),(1.5,1.5,1),p[1])
    elif family in ("stairwell", "floorpad"):
        m.box((0,16),(0,1),(0,16),p[0])
        if family=="stairwell":
            if arg=="right":
                m.box((14,16),(1,3),(0,16),p[2])
                for z in (0,14):m.box((14,16),(3,5),(z,z+2),p[2])
            else:
                m.box((0,16),(1,3),(0,2),p[2])
                for x in (0,14):m.box((x,x+2),(3,5),(0,2),p[2])
        # These are horizontal drawings, not facade art. Preserve all source
        # pixels, including the actual stair opening; invent no black depth.
        yy,zz,xx=np.indices(m.occ.shape)
        m.paint[m.occ]=(zz*16+xx)[m.occ]
    else:
        raise ValueError(f"unknown explicit family {family}")
    return m


def generate(baseline, art=None):
    if art is None:
        from tools.model_gallery import BlueArt, DEFAULT_BUNDLE
        art=BlueArt(DEFAULT_BUNDLE)
    specs=specifications()
    assigned={k for k,v in baseline["cells"].items() if v.get("class")=="voxels"
              and k.split("/")[0].removeprefix("tilesets.") in SPECS}
    if set(specs)!=assigned:
        raise ValueError(f"unreviewed/unknown keys: {set(specs)^assigned}")
    updates,ledger={},[]
    for key,(label,family,arg) in specs.items():
        changed=family!="keep"
        if changed:
            m=build(family,arg,source_shades(art,key))
            updates[key]=encode(m.occ,m.paint,baseline["cells"][key],
                                source="hand-blue-props-review",trim=True)
            if key=="tilesets.facility/40-41-56-57":
                # Authorised placement correction: source map shows a north/south
                # floor-adjacent bed, not two vertically stacked furniture cells.
                updates[key].pop("on",None)
        ledger.append({"key":key,"label":label,"family":family,
                       "decision":"changed" if changed else "retained",
                       "reason":REASONS[family] if changed else arg})
        if key=="tilesets.facility/40-41-56-57":
            ledger[-1]["reason"] += " Removed the incorrect legacy on flag so the head remains on its source floor cell north of the foot."
    return updates,ledger


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out",type=Path,default=ROOT/"local/model-gallery/blue-rollout-study/props")
    args=parser.parse_args()
    updates,ledger=generate(load_baseline())
    args.out.mkdir(parents=True,exist_ok=True)
    for name,value in (("updates.json",updates),("ledger.json",ledger)):
        (args.out/name).write_text(json.dumps(value,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"reviewed":len(ledger),"changed":len(updates),"retained":len(ledger)-len(updates)}))


if __name__=="__main__":main()
