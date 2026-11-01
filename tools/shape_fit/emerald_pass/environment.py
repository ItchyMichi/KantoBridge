"""Individually audited Emerald exterior cells and source object assemblies.

The numbered selections below are an explicit manual source/map/three-view
inspection record, not image classification. Inventory positions are guarded
by original entry hashes and the fixed baseline; outputs are proposals only.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import Model, decode, encode, render
from tools.shape_fit.emerald_pass.review import BASELINE, entry_hash, read
from tools.shape_fit.emerald_pass.scenery import ROOT, BUNDLE
from tools.shape_fit.emerald_pass.inventory import OUT as INVENTORY, contact_pages

OUT = ROOT/'local/model-gallery/emerald-pass-study/environment'
WALK_STEPS_KEYS = (
    'g3q/15537-15538-15539-15540', 'g3q/14054-14055-14056-14057',
    'g3q/20230-20231-20232-20233', 'g3q/22615-22616-22617-22618',
    'g3q/23734-23735-23736-23737', 'g3q/25849-25850-25851-25852',
    'g3q/20634-20635-20636-20637',
)


def numbers(text):
    result=[]
    for word in text.split():
        a,_,b=word.partition('-');result.extend(range(int(a),int(b or a)+1))
    return result


# Categories were assigned after inspecting every original source/front/east/top
# contact and every indexed first-occurrence neighborhood (35 context sheets).
AUDIT = {
 'shared-general': {
  'building fragment':'7-10 13-16 18-20 25-27 31-34 36-39 44-46 53-56 59-62 66-68 296-297 315-317 321-323 325-333 336-343 345 347 352-355',
  'paving curb':'40-42 48-52 57-58 63-65 70-72 269 274 276 279-281 283-285 288-289 358',
  'notice sign':'2 21 270-271', 'gym sign':'156-157', 'fence':'158-169',
  'terrain surface':'1 79-80 95-97 101-108 125-127 131 144-155 170-172 210-213 215 273 275 286 290-291 312-314',
  'rail bridge':'132-143', 'timber bridge':'259 263 282 292 294 344 346 348-349',
  'boulder':'128-130 214 334-335',
  'sea rock quarter':'173-178 180-185 188-191 194-199',
  'cliff section':'47 69 73-78 81-94 98-100 109-122 179 186-187 192-193 200-209 272 287 293 295 299-302 305-311',
  'wall crystal':'303-304 318-320', 'raised flowerbed':'255-258 260-262 264-265',
  'cliff recess':'216', 'cave entrance threshold':'298', 'stairs':'324', 'festival flag':'350-351 356-357',
 },
 'general__cave': {
  'boulder':'1-2 49 58-59 73 81 103', 'rock pillar':'19 21 82 85',
  'ladder upper section':'12 98 104', 'ladder':'16 36-37 96 99 106', 'ladder opening':'4 38 97', 'stairs':'3',
  'terrain surface':'7 20 32-35 39-48 50-51 53 55 61-62 64 100-101 105', 'timber bridge':'67-72',
  'cave entrance section':'5 9-11 13-15 22-28 30-31 54 56-57 63 65 74-78 88 90 92',
  'cliff section':'6 8 17-18 52 60 66 79-80 83-84 86-87 89 91 93-95',
  'inscribed stone panel':'29', 'small mineral deposit':'102',
 },
 'general__meteor_falls': {
  'stairs':'1', 'ladder upper section':'2 26', 'ladder':'3', 'boulder':'4-6 8 41',
  'cliff section':'7 9-11 13-15', 'mineral column section':'12 16 20 27-30 33-40',
  'rounded plateau':'17-19 21-23', 'timber bridge':'24-25 31-32',
 },
 'general__underwater': {
  'cliff section':'1-5 7-11 13-18', 'cave entrance threshold':'6', 'boulder':'12',
  'seabed growth':'19', 'submarine section':'20-23',
 },
 'general__navel_rock': {
  'boulder':'1-2 11-13', 'stairs':'3 18', 'cave entrance threshold':'4',
  'ladder upper section':'5 16 19', 'ladder':'6 17 20', 'ladder opening':'7',
  'cave entrance section':'8-10', 'cliff section':'14-15',
 },
 'general__island_harbor': {
  'landing stage':'1-2 9 12-14', 'building fragment':'3', 'harbor railing':'4-8 10-11',
 },
 'general__rusturf_tunnel': {
  'boulder':'1-4', 'cave entrance threshold':'5', 'rounded plateau':'6 8-12', 'stairs':'7',
 },
 'general__mirage_tower': {
  'boulder':'1-2', 'stairs':'3', 'ladder opening':'4 9',
  'ladder upper section':'5-6', 'ladder':'7', 'cliff section':'8 10',
 },
 'secret_base__secret_base_brown_cave': {'exit threshold':'1','boulder':'2','computer':'3','floor pit':'4-5'},
 'secret_base__secret_base_red_cave': {'exit threshold':'1','boulder':'2','computer':'3','floor pit':'4-5'},
 'secret_base__secret_base_yellow_cave': {'exit threshold':'1','boulder':'2','computer':'3','floor pit':'4-5'},
 'secret_base__secret_base_blue_cave': {'boulder':'1','computer':'2'},
 'secret_base__secret_base_tree': {'exit threshold':'1','background foliage':'2','shrub':'3','computer':'4'},
 'secret_base__secret_base_shrub': {'shrub':'1','computer':'2'},
}

# Concrete operation choices, not a blanket rule for entries of a class.
OPERATIONS = {
 'shared-general': {
  'sign':'2 21 270-271',
  'floor':'1 79-80 95-97 101-108 125-127 144-154 170-172 273 275 291',
  'surface':'64 155 210 212 286 290',
  'curb':'40-42 48-52 57-58 63 65 70-72 269 274 276 279-281 283-285 288-289 358',
  'bridge':'259 263 282 292 294 344 346 348-349',
  'rock':'128-130 214 334-335', 'steps':'324',
  'flowerbed':'255-258 260-262 264-265',
  'sea':'173-178 180-185 188-191 194-199',
  'arch':'69 114 306', 'threshold':'117 298 310',
  'flag':'350-351 356-357', 'fence':'158-169',
 },
 'general__cave': {'rock':'1-2 49 58-59 73 81 103','steps':'3',
  'floor':'7 20 32-35 39-48 50-51 53 55 61-62 64 100-101 105','bridge':'67-72','arch':'10 24 88','threshold':'14 27'},
 'general__meteor_falls': {'rock':'4-6 8 41','steps':'1','floor':'17-19 21-23','bridge':'24-25 31-32','arch':'10','threshold':'14','column':'16 20 33-40'},
 'general__underwater': {'rock':'12','shrub':'19','submarine':'20-23','arch':'7 14','threshold':'11 17'},
 'general__navel_rock': {'rock':'1-2 11-12','steps':'3 18'},
 'general__island_harbor': {'floor':'1-2 9 12-14'},
 'general__rusturf_tunnel': {'rock':'1-4','steps':'7','surface':'6','floor':'8-12'},
 'general__mirage_tower': {'rock':'1-2','steps':'3'},
 'secret_base__secret_base_brown_cave': {'surface':'1','rock':'2','computer':'3','pit':'4-5'},
 'secret_base__secret_base_red_cave': {'surface':'1','rock':'2','computer':'3','pit':'4-5'},
 'secret_base__secret_base_yellow_cave': {'surface':'1','rock':'2','computer':'3','pit':'4-5'},
 'secret_base__secret_base_blue_cave': {'rock':'1','computer':'2'},
 'secret_base__secret_base_tree': {'surface':'1','shrub':'2-3','computer':'4'},
 'secret_base__secret_base_shrub': {'shrub':'1','computer':'2'},
}

CORRECTED_ON = {
 'g3q/28866-28867-28868-28869':'g3q/28870-28871-28872-28873',
 'g3q/30692-30693-30694-30695':'g3q/30708-30709-30710-30711',
}

SEA_ROLES = {0:numbers('173 175 177 182 188 190 194'),1:numbers('174 176 178 183 189 191 195'),2:numbers('180 184 196 198'),3:numbers('181 185 197 199')}


def source_palette(sprite, rect=(3,3,13,13), *, neutral=False):
    """Source-local palette addresses; geometry never depends on source pixels."""
    rgb=np.asarray(sprite.convert('RGB'));x0,y0,x1,y1=rect
    colors=Counter(tuple(map(int,c)) for c in rgb[y0:y1,x0:x1].reshape(-1,3))
    values=[c for c,n in colors.items() if n>=2]
    if neutral:
        selected=[c for c in values if max(c)-min(c)<85]
        if selected:values=selected
    values=sorted(values,key=lambda c:sum(c))
    if not values:values=[tuple(rgb[y0,x0])]
    return [int(np.argwhere((rgb==c).all(axis=2))[0] @ np.array([16,1])) for c in values]


def closest_uv(sprite, target):
    rgb=np.asarray(sprite.convert('RGB'),dtype=int)
    return int(np.argmin(((rgb-np.asarray(target))**2).sum(axis=2)))


def material_paint(model, sprite, neutral=False, rect=(3,3,13,13), tone=None):
    palette=source_palette(sprite,rect,neutral=neutral);yy,zz,xx=np.indices(model.occ.shape)
    rgb=np.asarray(sprite.convert('RGB')).reshape(-1,3)
    if tone=='warm':chosen=[u for u in palette if rgb[u,0]>=rgb[u,1] and rgb[u,0]>=rgb[u,2]]
    elif tone=='cool':chosen=[u for u in palette if rgb[u,2]>=rgb[u,0] and rgb[u,2]>=rgb[u,1]]
    elif tone=='green':chosen=[u for u in palette if rgb[u,1]>=rgb[u,0] and rgb[u,1]>=rgb[u,2]]
    else:chosen=palette
    if chosen:palette=chosen
    # Broad deterministic sculpted facets, with light from the upper west.
    q=np.clip((.48+.025*yy-.018*xx-.009*zz+((xx//4+zz//4)%3-1)*.08),0,1)
    model.paint[:]=np.asarray(palette,dtype=np.uint8)[np.minimum(len(palette)-1,(q*len(palette)).astype(int))]
    return model


def horizontal(original, height=None):
    occ,paint=decode(original)
    if height is not None:
        occ=np.zeros((16,16,16),bool);occ[:height]=True
        paint=np.zeros(occ.shape,np.uint8)
    yy,zz,xx=np.indices(occ.shape);top=(zz*16+xx).astype(np.uint8)
    above=np.roll(occ,-1,axis=0);above[-1]=False
    paint[occ&~above]=top[occ&~above]
    return encode(occ,paint,original,source='hand-emerald-ground-paint')


def rock(sprite, variant=0, group='shared-general'):
    m=Model()
    if variant%2:
        m.ellipsoid((6,6,6.5),(6,6.5,5.8));m.ellipsoid((11.5,4,10),(3.8,4.3,4))
        m.ellipsoid((4,2.5,12),(3.2,2.8,2.8))
    else:
        m.ellipsoid((8,6.5,8),(7,7,6.7));m.ellipsoid((5,3.3,11),(3.8,3.8,3.8))
    cool=group=='general__underwater' or group=='general__meteor_falls' or group=='shared-general' and variant in (334,335)
    neutral=group=='shared-general' and variant==214
    rect=(4,8,12,14) if group.startswith('secret_base') else (3,3,13,13)
    tone='cool' if cool or group.endswith('blue_cave') else 'green' if group=='general__rusturf_tunnel' else 'warm'
    material_paint(m,sprite,neutral=neutral,rect=rect,tone=None if neutral else tone)
    if group=='general__cave' and variant==103:
        yy,zz,xx=np.indices(m.occ.shape)
        accent=m.occ&(xx>=6)&(xx<11)&(yy>=5)&(yy<7)&(zz>=11)
        m.paint[accent]=closest_uv(sprite,(33,99,239))
    return m


def shrub(sprite):
    m=Model();m.ellipsoid((8,5,8),(6.6,5.5,6.6));m.ellipsoid((4,3,7),(3,3.4,3.2));m.ellipsoid((11,4,10),(3.5,4,3.5))
    palette=source_palette(sprite,(3,5,13,13));rgb=np.asarray(sprite.convert('RGB')).reshape(-1,3)
    green=[u for u in palette if rgb[u,1]>=rgb[u,0] and rgb[u,1]>=rgb[u,2]]
    material_paint(m,sprite)
    if green:
        yy,zz,xx=np.indices(m.occ.shape);m.paint[:]=np.asarray(green,dtype=np.uint8)[(xx//3+zz//3+yy//3)%len(green)]
    return m


def steps(sprite):
    m=Model()
    for z in range(16):
        m.box((1,15),(0,16-(z//4)*4),(z,z+1))
    yy,zz,xx=np.indices(m.occ.shape);m.paint[:]=((15-yy)*16+xx).astype(np.uint8)
    above=np.roll(m.occ,-1,axis=0);above[-1]=False
    m.paint[m.occ&~above]=(zz*16+xx).astype(np.uint8)[m.occ&~above]
    return m


def sign(sprite, blue=False):
    m=Model()
    if blue:
        m.box((2,14),(1,15),(6,10));m.box((1,15),(0,2),(5,11))
    else:
        m.box((2,14),(7,15),(7,10));m.box((7,9),(0,8),(7,10))
    m.project(side=closest_uv(sprite,(130,110,105)),back=closest_uv(sprite,(130,110,105)),top=closest_uv(sprite,(200,180,170)))
    return m


def curb(sprite, number):
    m=Model();m.box((0,16),(0,1),(0,16));yy,zz,xx=np.indices(m.occ.shape)
    # Manually inspected border directions; source pixels supply paint only.
    directions={40:'nw',41:'n',42:'ne',48:'w',49:'e',50:'sw',51:'s',52:'se',57:'nw',58:'n',63:'w',65:'e',70:'sw',71:'s',72:'se',269:'se',274:'w',276:'w',279:'e',280:'e',281:'s',283:'n',284:'n',285:'ne',288:'s',289:'s',358:'sw'}
    for side in directions[number]:
        x=(0,8) if side=='w' else (8,16) if side=='e' else (0,16)
        z=(0,8) if side=='n' else (8,16) if side=='s' else (0,16)
        m.box(x,(0,3),z)
    m.paint[:]=(zz*16+xx).astype(np.uint8)
    return m


def computer(sprite):
    m=Model();gray=closest_uv(sprite,(130,130,130));dark=closest_uv(sprite,(75,75,75));light=closest_uv(sprite,(220,220,220))
    m.box((2,14),(0,2),(4,13),dark);m.box((3,13),(2,7),(5,12),gray)
    m.box((1,15),(7,16),(5,9),gray);m.box((2,14),(6,8),(8,14),light)
    # Readable screen face and keyboard, with the source's frame/indicator art.
    for x in range(1,15):
        for y in range(8,16):m.paint[y,8,x]=(15-y)*16+x
    for z in range(9,14):
        for x in range(2,14):m.paint[7,z,x]=(z+1)*16+x
    return m


def pit(sprite):
    m=Model();dark=closest_uv(sprite,(35,35,35));rim=closest_uv(sprite,(100,95,80))
    m.box((2,14),(0,1),(0,16),dark)
    m.box((1,3),(0,3),(0,16),rim);m.box((13,15),(0,3),(0,16),rim)
    return m


def flowerbed(sprite, number):
    m=Model();wall=closest_uv(sprite,(255,197,148));soil=closest_uv(sprite,(58,115,58));leaf=closest_uv(sprite,(66,156,66));red=closest_uv(sprite,(239,74,82));cream=closest_uv(sprite,(255,222,132));trim=closest_uv(sprite,(239,239,239))
    left=number in (255,258,262);right=number in (257,261,265);north=number in (255,256,257);south=number in (262,264,265)
    x0,x1=(4 if left else 0),(12 if right else 16);z0,z1=(4 if north else 0),(4 if south else 16)
    m.box((x0,x1),(0,4),(z0,z1),wall);m.box((x0,x1),(4,5),(z0,z1),soil)
    if left:m.box((x0-2,x0),(0,6),(z0,z1),wall)
    if right:m.box((x1,x1+2),(0,6),(z0,z1),wall)
    if north:m.box((x0,x1),(0,6),(z0-2,z0),wall)
    if south:m.box((x0,x1),(0,6),(z1,z1+2),wall)
    if left:m.box((x0-2,x0),(5,6),(z0,z1),trim)
    if right:m.box((x1,x1+2),(5,6),(z0,z1),trim)
    if north:m.box((x0,x1),(5,6),(z0-2,z0),trim)
    if south:
        m.box((x0,x1),(4,5),(z0,z1),wall)
        m.box((x0,x1),(5,6),(z1,z1+2),trim)
        return m  # These source cells contain the wall lip, without foliage paint.
    for x in range(x0+2,x1,4):
        for z in range(z0+2,z1,4):
            m.box((x,x+1),(5,9),(z,z+1),leaf)
            m.ellipsoid((x+.5,7,z+.5),(2,2,2),leaf)
            m.ellipsoid((x+.5,10,z+.5),(1.8,1.2,1.8),red)
            m.box((x,x+1),(10,11),(z,z+1),cream)
    return m


def sea_rock(sprite, number):
    q=next(q for q,values in SEA_ROLES.items() if number in values);dx,dz=q%2,q//2
    whole=Model(width=32,depth=32);whole.ellipsoid((16,7,16),(8.8,7.4,8.6));whole.ellipsoid((13,4,19),(6.2,4.6,5.5))
    m=Model();m.occ[:]=whole.occ[:,dz*16:(dz+1)*16,dx*16:(dx+1)*16]
    palette=[closest_uv(sprite,c) for c in ((99,99,123),(132,132,140),(222,230,239))]
    yy,zz,xx=np.indices(m.occ.shape);shade=np.clip(((yy+2)/6).astype(int),0,2)
    m.paint[:]=np.asarray(palette,np.uint8)[shade]
    # Surrounding water/shore stays a one-voxel horizontal native surface.
    m.occ[0]=True;m.paint[0]=(zz[0]*16+xx[0]).astype(np.uint8)
    return m


def mineral_column(sprite, number):
    narrow=number in (16,20);width=16 if narrow else 32
    end=number in (20,37,38,39,40);right=number in (34,36,38,40)
    m=Model(height=32);yy,zz,xx=np.indices(m.occ.shape);gx=xx+(16 if right else 0)
    radius=np.where(yy<5,width*.46,np.where(yy>27,width*.44,width*.35))
    across=(gx+.5-width/2)/radius
    depth=((zz+.5)/16)**2 if end else 0
    m.occ=(across**2+depth<=1)
    palette=[closest_uv(sprite,c) for c in ((123,107,66),(189,173,99),(222,206,132))]
    shade=np.clip((gx//3)%3+(yy>26),0,2)
    m.paint[:]=np.asarray(palette,np.uint8)[shade]
    return m


def arch(sprite):
    m=Model();m.box((0,16),(0,16),(0,16))
    for x,y in (((3,13),(0,7)),((4,12),(7,10)),((5,11),(10,12))):m.box(x,y,(0,16),cut=True)
    m.project(side=closest_uv(sprite,(120,110,115)),top=closest_uv(sprite,(180,165,150)),back=closest_uv(sprite,(120,110,115)))
    return m


def flag(sprite, number):
    m=Model(height=32 if number in (350,351) else 16)
    gray=closest_uv(sprite,(130,130,140));light=closest_uv(sprite,(222,222,230))
    if number!=351:
        m.box((3,5),(0,24 if number==350 else 16),(7,9),gray)
    if number==356:
        m.box((1,7),(0,3),(5,11),gray);m.box((2,6),(3,4),(6,10),light)
    else:
        x0,x1=(0,6) if number==351 else (5,16);y0,y1=(12,24) if number in (350,351) else (3,15)
        m.box((x0,x1),(y0,y1),(7,9),light)
        if number in (351,357):m.box((x1-2,x1),(y0+4,y0+8),(7,9),cut=True)
        for y in range(y0,y1):
            for x in range(x0,x1):
                m.paint[y,7:9,x]=(y1-1-y)*16+x
    return m


def fence(sprite, number):
    m=Model();gray=closest_uv(sprite,(132,132,140));dark=closest_uv(sprite,(66,74,90));light=closest_uv(sprite,(239,247,247))
    shape={158:'w',159:'e',160:'h',161:'es',162:'w',163:'ws',164:'w',165:'e',166:'e',167:'en',168:'h',169:'wn'}[number]
    if shape in ('w','e'):
        x=3 if shape=='w' else 13;points=[(x,2),(x,13)];m.box((x-1,x+1),(7,9),(0,16),light)
    elif shape=='h':
        points=[(3,8),(13,8)];m.box((0,16),(7,9),(7,9),light)
    else:
        x=3 if 'e' in shape else 13;z=8;points=[(x,z)]
        m.box((x,16) if 'e' in shape else (0,x+1),(7,9),(z-1,z+1),light)
        m.box((x-1,x+1),(7,9),(0,z+1) if 'n' in shape else (z,16),light)
    for x,z in points:
        m.box((x-2,x+2),(0,2),(z-2,z+2),dark);m.box((x-1,x+2),(2,10),(z-1,z+2),gray);m.box((x-1,x+2),(10,11),(z-1,z+2),light)
    return m


def submarine():
    m=Model(height=32,width=64,depth=16)
    m.ellipsoid((25,7.5,8),(23,7.8,7),1)
    m.ellipsoid((26,14,8),(10,7,4.5),2)
    m.box((9,11),(12,25),(7,9),3);m.box((9,15),(23,25),(7,9),3)
    m.box((43,57),(6,9),(7,9),1);m.box((54,57),(2,14),(6,10),1)
    m.box((54,57),(6,9),(2,14),1)
    yy,zz,xx=np.indices(m.occ.shape)
    port=((xx+.5-27)**2+(yy+.5-15)**2<7)&(zz>=11)&m.occ
    m.paint[port]=3
    glass=((xx+.5-27)**2+(yy+.5-15)**2<2)&(zz>=11)&m.occ
    m.paint[glass]=2
    return m


REASONS = {
 'floor':'Keep the inspected terrain footprint and height; address horizontal texels by source x/z so ground and edge detail reads on the top instead of becoming repeated vertical color stripes.',
 'surface':'Source neighborhood identifies a continuous ground/threshold surface. Replace sparse decorative marks or a raised sprite volume with an explicit shallow complete surface and native top paint.',
 'curb':'Source neighborhood identifies a paving border. Model a one-voxel ground surface and a three-voxel curb, retaining native top markings and avoiding a tall frontal pavement card.',
 'bridge':'Source neighborhood identifies repeated timber decking. Preserve the fitted deck footprint and height, and place plank/end detail on the horizontal top.',
 'rock':'The source and map identify a freestanding rock. Replace the sprite-cut hourglass side and holes with grounded rounded rock lobes, broad source-native facets and clear surrounding air.',
 'steps':'The source/map shows stairs between adjacent terrain levels. Author four broad solid treads with coherent rise and depth; preserve all movement/interaction metadata.',
 'sign':'The source/map shows a freestanding notice board. Give the sign a narrow board, solid edge/back, and rooted post or blue plinth, keeping readable native front artwork.',
 'computer':'The source/map shows a secret-base computer. Author a grounded cabinet, shallow monitor, keyboard shelf and readable source screen, replacing hourglass sprite extrusion.',
 'pit':'The source/map shows a floor opening, not an upright object. Use a recessed dark floor with a low rock lip; the adjacent parts form one floor pit.',
 'shrub':'Source/map identifies a rooted vegetation or seabed-growth clump. Author joined rounded lobes with native material addresses and clear air around the plant.',
 'flowerbed':'Source/map identifies the large raised flowerbed. Author a shallow bordered soil bed with rooted flowers; side/corner pieces join the repeated middle pieces in the complete planter.',
 'submarine':'The four adjacent source cells form the Seafloor Cavern submarine. Author one connected ellipsoidal hull, raised conning tower/periscope, tail shaft and fins before splitting its four source cells.',
 'sea':'Complete 2x2 source neighborhoods identify one shoreline rock, not four water columns. Author one grounded rounded rock before slicing its source quadrants; retain water/shore artwork only on a thin horizontal surface and native gray rock paint above it.',
 'arch':'The complete source doorway shows a rock arch above a walk-through threshold. Author solid side piers and a stepped overhead arch with an open passage, retaining native rock paint and existing movement flags.',
 'threshold':'This source cell is the lower doorway aperture. Replace its opaque vertical picture with a thin recessed threshold so the complete rock arch remains open; retain warp and movement metadata.',
 'flag':'Source context identifies a festival flag. Author a narrow rooted pole and thin hanging cloth with a clipped flying edge, using native emblem artwork without pavement or sky volume.',
 'fence':'Source/map establishes the direction of this fence run or corner. Author grounded capped posts and a narrow connecting rail, excluding surrounding grass volume and preserving source-native pale/gray materials.',
 'column':'Source/map identifies the gold mineral formation projecting from the cave wall. Author joined fluted column segments with a broad grounded foot and shaped cap; preserve the separate gray wall-cap cells and use native gold addresses, replacing isolated hourglass extrusions.',
}


def selections():
    ownership=read(INVENTORY/'environment-ownership.json');rows=[]
    for owned in ownership['groups']:
        group=owned['group'];keys=set(owned['keys']);entries=read(INVENTORY/group/'entries.json')
        labels={};ops={}
        for label,spec in AUDIT[group].items():
            for n in numbers(spec):
                if n in labels:raise ValueError(f'duplicate manual audit {group}:{n}')
                labels[n]=label
        for op,spec in OPERATIONS.get(group,{}).items():
            for n in numbers(spec):
                if n in ops:raise ValueError(f'duplicate operation {group}:{n}')
                ops[n]=op
        chosen=[r for r in entries if r['key'] in keys]
        if set(labels)!={r['contact_position'] for r in chosen}:
            raise ValueError(f'manual audit coverage mismatch {group}: missing {set(r["contact_position"] for r in chosen)-set(labels)}, extra {set(labels)-set(r["contact_position"] for r in chosen)}')
        for r in chosen:
            rows.append({**r,'family':labels[r['contact_position']],'operation':ops.get(r['contact_position'])})
    return rows


def generate(table,art,rows):
    updates={};sub=submarine()
    for row in rows:
        key=row['key'];n=row['contact_position'];op=row['operation'];original=table['cells'][key]
        if row['baseline_entry_sha256']!=entry_hash(original):raise ValueError(f'baseline changed since manual inventory: {key}')
        if row['sprite_sha256']!=hashlib.sha256(art.cell(key,'BAKED').tobytes()).hexdigest():raise ValueError(f'source changed since manual inventory: {key}')
        if not op:continue
        sprite=art.cell(key,'BAKED')
        if op in ('floor','bridge'):entry=horizontal(original)
        elif op=='surface':entry=horizontal(original,4)
        elif op=='threshold':entry=horizontal(original,1)
        elif op=='submarine':
            part=n-20;selection=(slice(None),slice(None),slice(part*16,(part+1)*16));o=sub.occ[selection];tags=sub.paint[selection]
            palette=[closest_uv(sprite,c) for c in ((205,197,255),(173,165,239),(229,222,255),(82,66,173))]
            p=np.asarray(palette,dtype=np.uint8)[tags]
            entry=encode(o,p,original,source='hand-emerald-submarine',trim=True)
        else:
            fn={'rock':lambda:rock(sprite,n,row['group']),'steps':lambda:steps(sprite),'sign':lambda:sign(sprite,n==2),'curb':lambda:curb(sprite,n),'computer':lambda:computer(sprite),'pit':lambda:pit(sprite),'shrub':lambda:shrub(sprite),'flowerbed':lambda:flowerbed(sprite,n),'sea':lambda:sea_rock(sprite,n),'arch':lambda:arch(sprite),'flag':lambda:flag(sprite,n),'fence':lambda:fence(sprite,n),'column':lambda:mineral_column(sprite,n)}[op]
            entry=fn().entry(original,source=f'hand-emerald-environment-{op}',trim=True)
        if key in CORRECTED_ON:
            if entry.get('on')!=CORRECTED_ON[key]:raise ValueError(f'unexpected old on: {key}')
            entry.pop('on')
        if key in WALK_STEPS_KEYS:
            assert op=='steps' and not any(original.get(f) for f in ('walk','on','seat'))
            entry['walk']=True
        updates[key]=entry
    return updates


# QA05 found these four floor-depth interpretations disconnected or projected
# into long walls. Exact source-pinned templates in environment_templates.py
# replace them; historical capture/output files remain immutable.
RETIRED_WHOLE_INTERPRETATIONS = {
    'emerald_environment_038959886fff': ('emerald_object_mineral_narrow_b81bdfe83c04',),
    'emerald_environment_b00acee3a0e5': ('emerald_object_mineral_wide_0f9a4c17c6b8',),
    'emerald_environment_ac7f620d95f6': ('emerald_object_mineral_tall_0ea2c24ea2f4',
        'emerald_object_mineral_tall_18dbff1bce28','emerald_object_mineral_tall_7ce06ba7e019'),
    'emerald_environment_f278b044fee3': ('emerald_object_festival_flag_231978bd74e5',
        'emerald_object_festival_flag_27a496557182','emerald_object_festival_flag_b805ebae843d'),
}


def assemblies_for(table, art, rows):
    """Actual contiguous source layouts, with placement evidence per exhibit."""
    by_group={}
    for row in rows:by_group.setdefault(row['group'],{})[row['contact_position']]=row['key']
    found={}
    def add(layout,label,place,*,corrected=False):
        keys=[k for line in layout for k in line if k]
        if any(table['cells'].get(k,{}).get('class')!='voxels' for k in keys):
            raise ValueError(f'assembly contains a nonvoxel source: {label}')
        stamp=json.dumps([layout,'stack' if corrected else 'floor'],separators=(',',':'))
        identifier=('emerald_compound_' if corrected else 'emerald_environment_')+hashlib.sha256(stamp.encode()).hexdigest()[:12]
        item=found.setdefault(identifier,{'id':identifier,'kind':'object (model as one)','note':label,'layout':layout,'occurrences':[]})
        if corrected:item['replaces_stack']=True
        item['occurrences'].append(place)

    # Finite source components whose full native source-key family is audited.
    families={'timber bridge','raised flowerbed','submarine section','floor pit'}
    sets={family:{r['key'] for r in rows if r['family']==family} for family in families}
    # Fortree-owned lower plank row: assembly membership only, no table change.
    sets['timber bridge'].add('g3q/14961-14961-17462-17462')
    for name,source in art.maps.items():
        grid=art.map_cells(source);height=len(grid);width=len(grid[0])
        for family,keys in sets.items():
            unseen={(x,y) for y,line in enumerate(grid) for x,key in enumerate(line) if key in keys}
            while unseen:
                start=min(unseen);todo=[start];component=set()
                while todo:
                    p=todo.pop()
                    if p not in unseen:continue
                    unseen.remove(p);component.add(p);x,y=p
                    todo.extend(((x-1,y),(x+1,y),(x,y-1),(x,y+1)))
                if len(component)<2:continue
                x0=min(p[0] for p in component);x1=max(p[0] for p in component)+1
                y0=min(p[1] for p in component);y1=max(p[1] for p in component)+1
                layout=[[grid[y][x] if (x,y) in component else None for x in range(x0,x1)] for y in range(y0,y1)]
                corrected=family=='floor pit' and layout[0][0] in CORRECTED_ON
                add(layout,f'Complete {family}: {name}',{'map':name,'x':x0,'y':y0},corrected=corrected)

    # Manually inspected finite patterns; every result must exactly occur in a map.
    templates=[]
    def template(group,positions,label):
        templates.append(([[by_group[group][n] if n else None for n in line] for line in positions],label))
    template('general__cave',[[9,10,11],[13,14,15]],'Granite cave entrance')
    template('general__cave',[[23,24,25],[26,27,28]],'Ancient tomb cave entrance')
    template('shared-general',[[305,306,307],[309,310,311]],'Cave of Origin entrance')
    template('shared-general',[[114],[117]],'Outdoor cliff opening')
    template('general__underwater',[[7],[11]],'Underwater cave opening')
    template('general__underwater',[[13,14,15],[16,17,18]],'Wide underwater cave opening')
    template('general__meteor_falls',[[9,10,11],[13,14,15]],'Meteor Falls cave entrance')
    # Mineral formations use the five complete templates above, including all
    # actual three/four/five-row patterns and measured indoor headroom.
    template('general__meteor_falls',[[17,18,19],[21,22,23]],'Rounded cave plateau')
    template('general__rusturf_tunnel',[[1],[2]],'Mossy rock formation')
    # The wider flags use three exact complete templates, including WALK curbs.
    seen_templates=Counter()
    sea_roles={by_group['shared-general'][n]:q for q,values in SEA_ROLES.items() for n in values}
    for name,source in art.maps.items():
        grid=art.map_cells(source);h=len(grid);w=len(grid[0])
        for y in range(h):
            for x in range(w):
                for index,(layout,label) in enumerate(templates):
                    th,tw=len(layout),len(layout[0])
                    if y+th>h or x+tw>w or grid[y][x]!=layout[0][0]:continue
                    if all(key is None or grid[y+dy][x+dx]==key for dy,line in enumerate(layout) for dx,key in enumerate(line)):
                        add(layout,label,{'map':name,'x':x,'y':y});seen_templates[index]+=1
                if x+1<w and y+1<h:
                    layout=[line[x:x+2] for line in grid[y:y+2]]
                    if all(sea_roles.get(key)==dy*2+dx for dy,line in enumerate(layout) for dx,key in enumerate(line)):
                        add(layout,'Complete shoreline rock source variant',{'map':name,'x':x,'y':y})
    # Foliage and console were distinct adjacent objects, not one vertical stack.
    upper='g3q/30692-30693-30694-30695';lower=CORRECTED_ON[upper]
    for place in art.locations[upper]:
        g=art.map_cells(art.maps[place['map']]);x,y=place['x'],place['y']
        if y+1<len(g) and g[y+1][x]==lower:
            add([[upper],[lower]],'Foliage north of an independent secret-base computer',place,corrected=True)
    missing=[label for i,(_,label) in enumerate(templates) if not seen_templates[i]]
    if missing:raise ValueError(f'manual source patterns did not occur: {missing}')
    definitions=[{k:v for k,v in item.items() if k!='occurrences'} for item in found.values()]
    return definitions,list(found.values())


def assembly_previews(definitions, table, art, out):
    out.mkdir(parents=True,exist_ok=True)
    for start in range(0,len(definitions),8):
        chunk=definitions[start:start+8];sheet=Image.new('RGB',(1600,((len(chunk)+3)//4)*470),'#eceff1');draw=ImageDraw.Draw(sheet)
        for index,item in enumerate(chunk):
            layout=item['layout'];h=max(16*(1+len(table['cells'][k].get('up',[]))) for row in layout for k in row if k)
            occ=np.zeros((h,len(layout)*16,len(layout[0])*16),bool);colors=np.zeros((*occ.shape,3),np.uint8)
            for z,row in enumerate(layout):
                for x,key in enumerate(row):
                    if not key:continue
                    o,p=decode(table['cells'][key]);a=np.asarray(art.cell(key,'BAKED').convert('RGB'));s=(slice(0,len(o)),slice(z*16,z*16+16),slice(x*16,x*16+16))
                    occ[s]=o;colors[s]=a[p//16,p%16]
            pictures=[art.drawing(layout,'BAKED')]
            for axis in (1,2,0):
                visible=occ.any(axis=axis);ids=occ.shape[axis]-1-np.argmax(np.flip(occ,axis=axis),axis=axis);coords=np.indices(visible.shape);lookup=[];j=0
                for dimension in range(3):
                    if dimension==axis:lookup.append(ids)
                    else:lookup.append(coords[j]);j+=1
                rgba=np.zeros((*visible.shape,4),np.uint8);rgba[...,:3]=colors[tuple(lookup)];rgba[...,3]=visible.astype(np.uint8)*255
                if axis!=0:rgba=rgba[::-1]
                pictures.append(Image.fromarray(rgba))
            left,top=(index%4)*400,(index//4)*470
            draw.text((left+5,top+5),item['id'],fill='black');draw.text((left+5,top+20),item['note'][:57],fill='black')
            for j,picture in enumerate(pictures):
                scale=min(380/picture.width,96/picture.height);size=(max(1,int(picture.width*scale)),max(1,int(picture.height*scale)));picture=picture.resize(size,Image.Resampling.NEAREST)
                px=left+10+(380-size[0])//2;py=top+45+j*104+(96-size[1])//2
                sheet.paste(picture,(px,py),picture if picture.mode=='RGBA' else None)
        sheet.save(out/f'page-{start//8+1:02d}.png')
    write_json(out/'index.json',[{'id':d['id'],'page':f'page-{i//8+1:02d}.png','layout':d['layout']} for i,d in enumerate(definitions)])


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args()
    table=read(BASELINE);art=EmeraldArt(BUNDLE);rows=selections();updates=generate(table,art,rows)
    context={r['key']:r for r in read(INVENTORY/'environment-context/index.json')};ledger=[]
    for row in rows:
        key=row['key'];op=row['operation'];original=table['cells'][key];place=context[key].get('occurrence');family=row['family']
        reason=REASONS[op] if op else f'Inspected source, front/east/top occupancy, and map neighborhood. Retain this {family}: its fitted section preserves the source silhouette, visible depth and adjoining terrain/architecture boundary; it is documented as a section where applicable, not claimed as an independent complete prop.'
        if key in CORRECTED_ON:
            reason+=f' Remove erroneous on={CORRECTED_ON[key]}: the map shows adjacent floor pit pieces or background foliage north of a separate computer; stacking would misrepresent that placement.'
        if place:reason+=f' Inspected context: {place["map"]} ({place["x"]},{place["y"]}).'
        reason+=f' Original occupancy: {row["voxels"]} voxels, bounds y/z/x {row["occupied_bounds_yzx"]}.'
        ledger.append({'key':key,'kind':'cell','label':f'{family.title()} — {row["group"]} #{row["contact_position"]}','family':family,'decision':'changed' if key in updates else 'retained','reason':reason,'baseline_entry_sha256':entry_hash(original),'occurrences':row['occurrences'],'evidence':[f'inventory/{row["group"]}/{Path(row["contact_page"]).name}',f'inventory/environment-context/{context[key]["page"]}']})
    write_json(args.out/'updates.json',{'cells':updates,'buildings':{}});write_json(args.out/'ledger.json',ledger)
    definitions,placements=assemblies_for(table,art,rows)
    write_json(args.out/'entities.json',definitions);write_json(args.out/'assembly-placements.json',placements)
    write_json(args.out/'metadata-corrections.json',[{'key':k,'field':'on','before':v,'after':None,'context':context[k]} for k,v in CORRECTED_ON.items()])
    current={**table,'cells':{**table['cells'],**updates}}
    contact_pages('environment-current',rows,current,art,args.out/'preview')
    contact_pages('changes',[dict(r) for r in rows if r['key'] in updates],current,art,args.out/'preview')
    assembly_previews(definitions,current,art,args.out/'preview/assemblies')
    print(json.dumps({'audited':len(rows),'changed':len(updates),'retained':len(rows)-len(updates),'assemblies':len(definitions)}))


if __name__=='__main__':main()
