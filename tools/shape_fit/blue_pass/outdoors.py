"""Individually reviewed Blue outdoor props and terrain segments.

The source art is consulted for palette addresses; it never supplies geometry.
Every owned fitted cell receives a decision in ``review``.  Geometry is authored
as semantic parts, including one tree split across four horizontal cells and
three statues split across their existing vertical stack links.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

from .common import BASELINE, ROOT, Model, decode, encode, shade_uv, source_shades, uv

ATLASES = {"cavern", "cemetery", "forest", "overworld", "plateau", "ship_port", "underground"}
PROTECTED = "tilesets.overworld/42-43-58-59"
OUT = ROOT / "local/model-gallery/blue-rollout-study/outdoors"
TREE = (("4-5-35-21", "6-7-22-23"), ("36-37-0-53", "38-39-54-48"))


def key(atlas, ids):
    return f"tilesets.{atlas}/{ids}"


def make_art():
    sys.path.insert(0, str(ROOT / "tools"))
    from model_gallery import BlueArt
    return BlueArt(ROOT / "local/minecraft/kantobridge/export/blue")


def colors(art, k):
    shades = source_shades(art, k)
    return shades, [shade_uv(shades, s) for s in range(4)]


def front_surface(m, rect=(0, 0, 16, 16), yrange=(0, 16)):
    """Paint the south-facing visible surface, leaving authored side materials."""
    x0, v0, x1, v1 = rect
    lo, hi = yrange
    h, d, w = m.occ.shape
    for y in range(max(0, lo), min(h, hi)):
        v = min(v1-1, v0 + (hi-1-y) * (v1-v0) // (hi-lo))
        for x in range(w):
            zs = np.flatnonzero(m.occ[y, :, x])
            if len(zs):
                u = min(x1-1, x0 + x * (x1-x0) // w)
                m.paint[y, zs[-1], x] = uv(u, v)
    return m


def rock(art, k):
    _, c = colors(art, k)
    m = Model()
    m.ellipsoid((8, 5.5, 8), (7, 6.5, 6.5), c[1])
    m.ellipsoid((6, 8, 7), (4.8, 4.5, 4.8), c[1])
    m.box((4, 12), (0, 2), (4, 12), c[2])
    yy, zz, xx = np.indices(m.occ.shape)
    m.paint[m.occ & (yy > 8) & (xx < 8) & (zz < 10)] = c[0]
    m.paint[m.occ & (xx > 10) & (yy < 7)] = c[2]
    front_surface(m, yrange=(0, 13))
    # A front silhouette's black outline does not become the entire side.
    return m


def cave_wall(art, k):
    _, c = colors(art, k)
    m = Model().box((0, 16), (0, 15), (0, 16), c[1])
    # Connected, tessellating rock mass. Narrow ledges are facets, never holes.
    m.box((0, 16), (15, 16), (0, 13), c[1])
    m.box((0, 16), (2, 5), (14, 16), c[2])
    m.box((1, 7), (8, 10), (15, 16), c[0])
    m.box((10, 15), (11, 13), (15, 16), c[2])
    front_surface(m)
    return m


def sign(art, k):
    _, c = colors(art, k)
    m = Model()
    m.box((7, 9), (0, 7), (7, 10), c[2])
    m.box((1, 15), (5, 15), (6, 10), c[1])
    m.box((1, 15), (14, 16), (6, 10), c[2])
    m.box((1, 2), (5, 15), (6, 10), c[2])
    m.box((14, 15), (5, 15), (6, 10), c[2])
    # The board carries the original lettering, while pole and edges use wood.
    for y in range(5, 16):
        for x in range(2, 14):
            if m.occ[y, 9, x]:
                m.paint[y, 9, x] = uv(x, 15-y)
    return m


def grave(art, k):
    _, c = colors(art, k)
    m = Model()
    m.box((1, 15), (0, 2), (3, 13), c[2])
    m.box((2, 14), (2, 4), (4, 12), c[1])
    m.box((4, 12), (4, 13), (6, 10), c[2])
    m.box((5, 11), (13, 14), (6, 10), c[0])
    front_surface(m)
    return m


def bollard(art, k):
    _, c = colors(art, k)
    m = Model()
    m.box((2, 14), (0, 2), (2, 14), c[2])
    m.box((4, 12), (2, 11), (4, 12), c[1])
    m.box((3, 13), (10, 12), (3, 13), c[2])
    m.box((4, 12), (12, 14), (4, 12), c[0])
    front_surface(m)
    return m


def statue(art, bottom, top, tall=False):
    """One sculpted creature/plinth, split at the existing y=16 stack seam."""
    _, c = colors(art, bottom)
    m = Model(height=32)
    base = 16 if tall else 8
    m.box((1, 15), (0, 2), (2, 14), c[2])
    m.box((2, 14), (2, base-1), (3, 13), c[1])
    m.box((1, 15), (base-1, base), (2, 14), c[2])
    m.ellipsoid((8, base+4, 8), (5.5, 5, 4.5), c[2])
    m.box((3, 6), (base, base+2), (8, 13), c[2])
    m.box((10, 13), (base, base+2), (8, 13), c[2])
    m.ellipsoid((8, base+9, 8), (5.3, 4.5, 4.3), c[2])
    # Broad projecting muzzle, paired ears, and arms are solid in profile.
    m.box((5, 11), (base+7, base+10), (10, 13), c[2])
    m.box((3, 6), (base+11, base+15), (6, 9), c[3])
    m.box((10, 13), (base+11, base+15), (6, 9), c[3])
    m.ellipsoid((3.5, base+4, 8.5), (2, 3.2, 2.5), c[2])
    m.ellipsoid((12.5, base+4, 8.5), (2, 3.2, 2.5), c[2])
    result = {}
    for layer, k in enumerate((bottom, top)):
        part = Model()
        part.occ[:] = m.occ[layer*16:(layer+1)*16]
        _, local = colors(art, k)
        # Materials refer to each half's own source cell, including the upper
        # layer's pale background being excluded from the statue silhouette.
        for s in range(4):
            part.paint[m.paint[layer*16:(layer+1)*16] == c[s]] = local[s]
        front_surface(part)
        result[k] = part
    return result


def forest_tree(art):
    """32×32 footprint, continuous broad crown, forked trunk, no shadow slab."""
    whole = Model(height=32, width=32, depth=32)
    # Temporary material indices, remapped to each cell's available texels.
    whole.cylinder((16, 21), (3, 3), (0, 16), 3)
    whole.box((12, 20), (0, 2), (18, 25), 2)
    whole.box((10, 15), (1, 4), (20, 25), 2)
    whole.box((18, 23), (1, 4), (19, 24), 2)
    whole.ellipsoid((12, 12, 20), (5, 7, 3.5), 2)
    whole.ellipsoid((20, 13, 20), (5, 6, 3.5), 2)
    for center, radii in [((16, 20, 15), (14.8, 10.5, 13.8)),
                          ((9, 19, 16), (7.5, 7.5, 9)),
                          ((23, 20, 16), (7.5, 7.5, 9)),
                          ((16, 25, 12), (9, 6, 9))]:
        whole.ellipsoid(center, radii, 2)
    yy, zz, xx = np.indices(whole.occ.shape)
    leaf = whole.occ & (yy >= 14)
    # Broad, coherent leaf facets follow the source crown's light checker band.
    whole.paint[leaf & (yy >= 25) & (((xx//3 + zz//3) % 3) == 0)] = 0
    # A black source outline is not a material band around the whole crown.
    # Keep sparse lower leaf notches; directional lighting supplies its shading.
    whole.paint[leaf & (yy < 16) & (zz > 17) & ((xx//3 + zz//3) % 4 == 0)] = 3
    result = {}
    for rz, row in enumerate(TREE):
        for rx, ids in enumerate(row):
            k = key("forest", ids)
            _, c = colors(art, k)
            p = Model(height=32)
            sl = np.s_[:, rz*16:(rz+1)*16, rx*16:(rx+1)*16]
            p.occ[:] = whole.occ[sl]
            p.paint[:] = np.asarray(c, np.uint8)[whole.paint[sl]]
            result[k] = p
    return result


def fence(art, k):
    """Quarter-tile rail network: source tiles16/32 rails,33 post,35 ground."""
    _, c = colors(art, k)
    m = Model()
    tiles = list(map(int, k.split('/')[-1].split('-')))
    for n, tile in enumerate(tiles):
        x, z = n % 2 * 8, n // 2 * 8
        if tile == 16:
            m.box((x+3, x+5), (4, 7), (z, z+8), c[1])
            m.box((x+3, x+5), (1, 3), (z, z+8), c[2])
        elif tile == 32:
            m.box((x, x+8), (4, 7), (z+3, z+5), c[1])
            m.box((x, x+8), (1, 3), (z+3, z+5), c[2])
        elif tile == 33:
            m.box((x+2, x+6), (0, 9), (z+2, z+6), c[1])
            m.box((x+1, x+7), (8, 10), (z+1, z+7), c[2])
            m.box((x+2, x+6), (10, 11), (z+2, z+6), c[0])
            # Join the neighbouring rail to the post centre, not just the
            # quarter-tile boundary. This avoids gaps at 16/33/32 corners.
            for dx, dz, expected in ((-1, 0, 32), (1, 0, 32), (0, -1, 16), (0, 1, 16)):
                nx, nz = n % 2 + dx, n // 2 + dz
                if 0 <= nx < 2 and 0 <= nz < 2 and tiles[nz*2+nx] == expected:
                    if dx:
                        m.box((x if dx < 0 else x+3, x+5 if dx < 0 else x+8), (4, 7), (z+3, z+5), c[1])
                    else:
                        m.box((x+3, x+5), (4, 7), (z if dz < 0 else z+3, z+5 if dz < 0 else z+8), c[1])
    # Long rails have supports even when a run's post lies outside this cell.
    for n, tile in enumerate(map(int, k.split('/')[-1].split('-'))):
        x, z = n % 2 * 8, n // 2 * 8
        if tile == 16:
            m.box((x+3, x+5), (0, 7), (z+3, z+5), c[2])
        elif tile == 32:
            m.box((x+3, x+5), (0, 7), (z+3, z+5), c[2])
    return m


def pickets(art, k):
    _, c = colors(art, k)
    m = Model()
    for x in (2, 10):
        m.box((x, x+4), (0, 10), (6, 10), c[1])
        m.box((x+1, x+3), (10, 12), (7, 9), c[0])
        m.box((x, x+4), (3, 5), (6, 10), c[2])
    return m


def balustrade(art, k):
    _, c = colors(art, k)
    m = Model()
    # Continuous rails meet adjoining cells; the two vertical balusters leave
    # real openings and have the same useful thickness from either direction.
    m.box((0, 16), (11, 14), (6, 10), c[1])
    m.box((0, 16), (14, 15), (5, 11), c[2])
    m.box((0, 16), (0, 2), (6, 10), c[2])
    for x in (2, 10):
        m.box((x, x+4), (2, 11), (6, 10), c[1])
    return m


def grass(art, k):
    _, c = colors(art, k)
    m = Model()
    for x, z in ((4, 4), (12, 4), (4, 12), (12, 12)):
        m.box((x-2, x+3), (0, 1), (z-2, z+3), c[1])
        for dx, dz, h in ((0, 0, 5), (-2, 0, 3), (2, 1, 4), (0, -2, 4), (0, 2, 3)):
            m.box((x+dx, x+dx+1), (1, h), (z+dz, z+dz+1), c[2])
        m.box((x-1, x+2), (2, 3), (z, z+1), c[1])
    return m


def flower(art, k):
    _, c = colors(art, k)
    m = Model()
    n = list(map(int, k.split('/')[-1].split('-'))).index(3)
    x, z = (n % 2)*8+4, (n//2)*8+4
    m.box((x, x+1), (0, 3), (z, z+1), c[2])
    m.box((x-2, x+3), (2, 3), (z, z+1), c[1])
    m.box((x, x+1), (2, 3), (z-2, z+3), c[1])
    m.box((x, x+1), (3, 4), (z, z+1), c[0])
    return m


def floor(art, k):
    m = Model().box((0, 16), (0, 1), (0, 16))
    for z in range(16):
        for x in range(16):
            m.paint[0, z, x] = uv(x, z)
    return m


def cut_tree(art, k):
    _, c = colors(art, k)
    m = Model().cylinder((8, 8), 1.8, (0, 8), c[2])
    m.box((4, 12), (5, 7), (7, 9), c[2])
    for center, radii in [((4.5, 9, 8), (3.5, 4, 3)),
                          ((11.5, 9, 8), (3.5, 4, 3)),
                          ((8, 12, 8), (3, 3.5, 3))]:
        m.ellipsoid(center, radii, c[1])
    yy, zz, xx = np.indices(m.occ.shape)
    m.paint[m.occ & (yy > 11) & (xx < 9)] = c[0]
    return m


def stairs(art, k):
    _, c = colors(art, k)
    m = Model()
    for i in range(3):
        m.box((2+i*4, 6+i*4), (0, 3+i*4), (3, 13), c[1])
        m.box((2+i*4, 6+i*4), (2+i*4, 3+i*4), (3, 13), c[0])
    return m


def ladder_well(art, k):
    _, c = colors(art, k)
    m = Model().box((3, 13), (0, 1), (3, 13), c[3])
    for x in (3, 12):
        m.box((x, x+1), (0, 2), (3, 13), c[1])
    for z in (3, 12):
        m.box((3, 13), (0, 2), (z, z+1), c[1])
    for x in (5, 10):
        m.box((x, x+1), (1, 2), (4, 12), c[2])
    for z in (5, 8, 11):
        m.box((5, 11), (1, 2), (z, z+1), c[0])
    return m


def pressure_switch(art, k):
    _, c = colors(art, k)
    m = Model().cylinder((8, 8), 6, (0, 2), c[2])
    m.cylinder((8, 8), 4.5, (1, 2), c[0])
    m.cylinder((8, 8), 3.5, (1, 3), c[1])
    return m


def specs():
    """Explicit audited selections; no silhouette or atlas-wide replacement."""
    s = {}
    def add(atlas, ids, fn, label, why):
        for item in ids.split():
            s[key(atlas, item)] = (fn, label, why)
    add("cavern", "12-13-28-29", rock, "Cave boulder", "Replace disconnected rock chips with a grounded, faceted solid boulder; wrap rock material onto its side.")
    add("cavern", "17-23-16-17 2-3-18-19 4-41-49-5 41-41-5-5 41-7-5-23 49-5-40-16 5-23-16-17 5-23-5-23 5-5-16-16 5-5-5-5", cave_wall, "Cave rock boundary", "Replace floating chips, flat shadow strips or empty fallback with continuous solid rock. Existing passage and ladder cells are excluded.")
    add("cavern", "14-15-30-31", sign, "Cave information sign", "Give the lettered board real thickness and one grounded post; remove the extruded ground and shadow.")
    add("cavern", "8-9-24-25", ladder_well, "Downward ladder opening", "Expose the recessed dark opening, rails and rungs in a low walkable floor model; keep walk metadata.")
    add("cavern", "43-44-45-46", pressure_switch, "Victory Road floor switch", "Model a low stone ring and raised centre button, so the floor mechanism reads in both views.")
    add("cemetery", "5-6-21-22", grave, "Gravestone and plinth", "Replace the black extruded side with a narrow chamfered headstone on a full-depth stepped plinth.")
    add("forest", "2-3-18-19", bollard, "Safari Zone stone boundary post", "Make a square stone bollard with a capped top and grounded base, instead of extruding ground speckle and its shadow.")
    add("forest", "33-34-49-50", sign, "Safari Zone information sign", "Make the board and post separate meaningful solid parts, with source lettering on the board face.")
    add("forest", "84-85-86-87", rock, "Safari Zone boundary boulder", "Give the rounded rock a coherent mass and faceted depth instead of detached outline pixels.")
    add("forest", "66-66-67-67", balustrade, "Safari Zone fence balustrade", "Reconstruct the fence between statue pedestals as continuous top/bottom rails and two grounded balusters, replacing the hourglass side and extruded gaps.")
    add("forest", "32-32-32-32 32-32-32-52 32-52-32-32 52-32-32-32", grass, "Safari Zone tall grass patch", "Map reconstruction identifies a tall-grass patch, not paving. Replace the horizontal texture slab with four small grounded blade clumps, preserving the grass footprint.")
    add("overworld", "14-14-85-85", pickets, "Paired short fence posts", "Model two capped, grounded posts with an open gap and consistent material on their sides.")
    add("overworld", "16-35-16-35 16-35-33-32 16-35-33-35 32-32-35-35 32-33-35-16 33-32-16-35 33-35-16-35 33-35-35-35 35-16-32-33 35-16-35-16 35-16-35-33 35-33-35-16 35-33-35-35 35-35-32-32 35-35-33-35 35-35-35-33", fence, "Connected outdoor rail and post segment", "Interpret rail and post quarter-tiles as an upright network in the source footprint; leave the plain-ground quarters empty.")
    add("overworld", "3-44-44-44 44-44-44-3", flower, "Low ground flower", "Ground speckles must not become a tall coral shape. Keep one low stem and petals in the source flower quarter.")
    add("overworld", "45-46-61-62", cut_tree, "Cuttable sapling", "Give the small forked tree a round trunk, branches and three solid leaf clusters with a readable side silhouette.")
    add("overworld", "57-57-57-57 60-60-4-60", floor, "Dotted ground / marked paving", "This is walkable terrain art, not a tall plant. Use a one-voxel top-projected surface; fill the empty marked-road fallback.")
    add("overworld", "64-65-80-81", rock, "Outdoor rounded boulder", "Replace thin disconnected rock outlines with a grounded faceted boulder and shaded side material.")
    add("overworld", "70-71-86-87", sign, "Town information sign", "Preserve the source lettering on a thick framed board with a single grounded support.")
    add("overworld", "82-82-82-82", grass, "Tall grass patch", "Replace tall coral-like extrusion with four low grass tufts matching the source quarter-tile pattern.")
    add("plateau", "42-43-34-29 7-8-23-24", rock, "Route 23 boundary boulder", "Make a continuous rounded rock volume with useful side depth and grounded base.")
    add("plateau", "69-69-69-69", grass, "Route 23 tall grass", "Reconnect four grass tufts to their low bases and use blade geometry at plant scale.")
    add("plateau", "9-10-25-26", sign, "Route 23 information sign", "Make a board and post, preserving the source lettering while removing the broad extruded shadow.")
    add("underground", "3-4-19-20", stairs, "Underground passage stairs", "Replace an empty model fallback with three connected ascending stone steps and contrasting treads.")
    return s


def review(baseline):
    cells = baseline.get("cells", baseline)
    selected = specs()
    records = []
    buildings = set("10-10-26-26 10-10-34-34 10-31-26-79 10-34-26-26 10-75-75-75 11-12-27-28 15-10-78-26 18-18-23-23 21-56-21-22 34-47-34-34 37-38-15-34 37-38-78-26 40-41-26-79 40-41-34-31 5-6-21-22 5-6-21-56 56-25-24-25 63-34-34-34 7-7-23-23 8-9-24-25 8-9-56-25 83-83-18-18".split())
    water = {"forest": "20-73-20-20 20-74-20-74 20-78-20-74 72-20-72-20 73-20-20-20 73-73-20-20 73-73-20-74 73-73-72-20 77-20-72-20",
             "overworld": "20-51-20-20 20-84-20-84 49-49-20-20 50-20-50-20 51-20-20-20 51-51-20-20 51-51-20-84 51-51-50-20",
             "plateau": "20-31-20-20 20-31-20-31 50-20-20-20 50-20-50-20 51-51-20-20"}
    stacks = {key("cemetery", "1-1-39-47"), key("cemetery", "55-63-61-62"),
              key("forest", "48-48-10-11"), key("forest", "26-27-75-76"),
              key("plateau", "16-18-40-41"), key("plateau", "21-22-48-49")}
    tree_keys = {key("forest", ids) for row in TREE for ids in row}
    for k, entry in sorted(cells.items()):
        atlas, ids = k.split('/')
        atlas = atlas.split('.')[-1]
        if atlas not in ATLASES or entry.get("class") != "voxels":
            continue
        decision, family = "retained", "terrain"
        label = "Connected cliff / terrain boundary segment"
        reason = "Map reconstruction identifies a cliff face or boundary corner, not a freestanding prop. Retain the existing ramp/edge profile with its neighbouring terrain family; this review does not replace the shared terrain elevation scheme."
        if k in selected:
            fn, label, reason = selected[k]
            decision, family = "changed", fn.__name__
        elif k in stacks:
            decision, family, label = "changed", "statue", "Creature statue and pedestal stack"
            reason = "Rebuild as one solid creature and plinth before splitting at the existing y=16 seam; retain the on link and paint each layer from its own sprite."
        elif k in tree_keys:
            decision, family, label = "changed", "tree", "Safari Zone broadleaf tree quadrant"
            reason = "Slice one connected 32×32 tree into its four footprint cells, with rounded leaf crown, forked trunk and no ground/shadow slab."
        elif k == PROTECTED:
            family, label = "approved", "Approved Pallet Town boundary tree"
            reason = "Previously reviewed and approved tree pilot; preserve the entire entry byte-for-byte."
        elif (atlas == "overworld" and ids in buildings) or (atlas == "forest" and ids == "8-9-24-25") or (atlas == "cemetery" and ids in {"2-2-18-18", "29-30-29-30", "9-10-25-26"}) or (atlas == "plateau" and ids in {"11-12-27-28", "21-22-5-6", "32-33-46-47", "37-38-40-41", "5-6-21-22", "5-6-48-49"}) or (atlas == "ship_port" and ids in {"34-35-34-51", "40-41-56-57", "72-73-88-89", "74-75-60-76"}):
            family, label = "building", "Building / architectural assembly segment"
            reason = "Reviewed in its reconstructed map context: this is a facade, column, door or ship assembly segment. Blue buildings were explicitly approved, so preserve its geometry and assembly behavior."
        elif ids in water.get(atlas, "").split():
            family, label = "waterbank", "Waterbank / shoreline floor segment"
            reason = "Source and both views show intentional low shoreline or water surface geometry. The tile must join its neighbouring ground and water planes, so retain its low profile."
        elif atlas == "cavern" and ids in {"10-11-26-27", "33-33-33-33"}:
            family, label = "warp", "Cave ladder / open passage"
            reason = "Existing ladder rails/rungs or open arch are intentional warp geometry. Preserve the opening and original walk metadata; cave-wall replacements exclude this key."
        elif atlas == "cavern" and ids == "47-47-34-34":
            family, label = "pit", "Seafoam Islands dark pit opening"
            reason = "The dark flat surface denotes a hole, not a freestanding object. Its low level and absence of a tall body are intentional."
        elif atlas == "cemetery" and ids in {"11-12-27-28", "3-4-19-20"}:
            family, label = "warp", "Pokémon Tower staircase / passage"
            reason = "The existing stair or open doorway is an intentional traversable warp marker, with walk metadata. Preserve the runtime transition geometry."
        elif (atlas == "forest" and ids == "64-65-64-65") or (atlas == "cavern" and ids == "21-22-21-22"):
            family, label = "ground_steps", "Surface steps joining raised terrain"
            reason = "Source-map context identifies ground steps between adjacent terrain levels. Preserve their low surface representation; warp_models.py explicitly excludes surface-only stairs from raised warp-marker geometry."
        elif atlas == "overworld" and ids in {"44-44-60-60", "57-57-60-60", "60-60-60-60"}:
            family, label = "paving", "Patterned paving / ground transition"
            reason = "Reconstructed Celadon/Cerulean context shows paving and its ground transition, with no standalone object. Keep the existing low, continuous paved surface."
        elif atlas == "overworld" and ids in {"1-1-17-17", "17-17-17-17"}:
            family, label = "terrain", "Raised terrain top / rim texture"
            reason = "The source lies on the top of a raised terrain region or its rim. Its full-height terrain profile belongs to the contiguous cliff family, so preserve it with those adjoining cells."
        elif atlas == "ship_port":
            family, label = "dock", "Vermilion dock floor / water edge"
            reason = "This is a connected dock surface or edge with a deliberately low profile, not a raised prop. Keep the existing material-bearing floor geometry."
        elif atlas == "overworld" and ids == "72-73-88-89":
            family, label = "entrance", "Open cave entrance arch"
            reason = "The current rock arch has a deliberate clear passage and a readable side profile. Preserve the opening and source entrance shape."
        records.append({"key": k, "label": label, "family": family, "decision": decision, "reason": reason})
    if len(records) != 155:
        raise ValueError(f"outdoor review scope changed: {len(records)} != 155")
    return records


def generate(baseline, art=None):
    art = art or make_art()
    cells = baseline.get("cells", baseline)
    models = {k: fn(art, k) for k, (fn, _, _) in specs().items()}
    for atlas, lower, upper, tall in (("cemetery", "55-63-61-62", "1-1-39-47", False),
                                       ("forest", "26-27-75-76", "48-48-10-11", False),
                                       ("plateau", "21-22-48-49", "16-18-40-41", True)):
        models.update(statue(art, key(atlas, lower), key(atlas, upper), tall))
    models.update(forest_tree(art))
    updates = {k: m.entry(cells[k], source="hand-blue-outdoor-review", trim=True) for k, m in models.items()}
    expected = {r["key"] for r in review(baseline) if r["decision"] == "changed"}
    assert set(updates) == expected
    assert PROTECTED not in updates
    return updates


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=BASELINE)
    parser.add_argument("--out", type=Path, default=OUT)
    args = parser.parse_args()
    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    updates = generate(baseline)
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "updates.json").write_text(json.dumps(updates, indent=2)+"\n", encoding="utf-8")
    (args.out / "review.json").write_text(json.dumps(review(baseline), indent=2)+"\n", encoding="utf-8")
    print(f"Reviewed 155 cells: {len(updates)} changed, {155-len(updates)} retained.")


if __name__ == "__main__":
    main()
