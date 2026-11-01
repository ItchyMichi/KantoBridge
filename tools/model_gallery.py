"""Prepare a local model gallery and compose sprite/Minecraft comparison sheets.

Only exported image/tileset/map data is read. ROM files and hidden-game saves are
not inputs. Generated references, manifests and screenshots belong under local/.
"""
from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import html
import io
import json
import math
import re
import textwrap
from collections import defaultdict
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BUNDLE = ROOT / "local/minecraft/kantobridge/export/blue"
DEFAULT_TABLE = ROOT / "fabric-bridge/src/main/resources/kantobridge/fitted_shapes/blue.json"
DEFAULT_ENTITIES = ROOT / "tools/shape_fit/entities/blue.json"
DEFAULT_OUT = ROOT / "local/model-gallery/blue"
# Complete, repeatable tree cells used around Pallet; unlike building art these
# each describe one tree rather than a portion of a facade.
PALLET_TREE_KEYS = {"tilesets.overworld/42-43-58-59"}
HASH_PARAMS = 6  # FittedShapes.HASH_PARAMS; byte-wise SHA-256 modulo 100.


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def safe_id(text: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_-]+", "_", text).strip("_")


def model_hash(entry: dict) -> tuple[str, str]:
    """Match the Java loader: paint and every extra layer contribute to identity."""
    data = bytearray()
    for layer in [entry, *entry.get("up", [])]:
        voxels = base64.b64decode(layer["v"], validate=True)
        if len(voxels) != 512:
            raise ValueError("a fitted layer must have 4096 occupancy bits")
        data.extend(voxels)
        paint = base64.b64decode(layer["t"], validate=True) if "t" in layer else b""
        if len(paint) == 4096:
            data.extend(paint)
    digest = hashlib.sha256(data).digest()
    return "".join(f"{n % 100:02d}" for n in digest[:HASH_PARAMS]), digest.hex()


def split_key(key: str) -> tuple[str, list[int]]:
    atlas, tiles = key.rsplit("/", 1)
    ids = [int(t) for t in tiles.split("-")]
    if len(ids) != 4:
        raise ValueError(f"expected four tiles in {key}")
    return atlas, ids


def fragments_for_cell(key: str, entry: dict, atlas_index: int, palette: str,
                       origin=(0, 0, 0)) -> list[dict]:
    _, tiles = split_key(key)
    digest, _ = model_hash(entry)
    result = []
    for layer in range(1 + len(entry.get("up", []))):
        variant = "g0_voxels_k" + digest + (f"{layer:02d}" if layer else "")
        for x in range(2):
            for y in range(2):
                for z in range(2):
                    result.append({
                        "key": f"cell:{atlas_index}|{','.join(map(str, tiles))}|0|{x},{y},{z}|v={variant}",
                        "palette": palette, "x": origin[0] + x,
                        "y": origin[1] + y + layer * 2, "z": origin[2] + z,
                    })
    return result


def fragments_for_native_cell(key, proof, atlas_index, palette, origin=(0, 0, 0)):
    """Exact source-map CUBE columns for an absent fitted baseline cell.

    The planner fixture supplies these shapes; this never invents or installs
    a fitted entry, and current models cannot opt into the baseline path.
    """
    columns=proof.get('columns',[])
    if (proof.get('key')!=key or not proof.get('map') or not proof.get('baseline_sha256')
            or not proof.get('evidence_sha256') or len(columns)!=4
            or {(c.get('dx'),c.get('dz')) for c in columns}!={(0,0),(1,0),(0,1),(1,1)}):
        raise ValueError('incomplete native baseline evidence: '+key)
    result=[];height=0
    for column in columns:
        n=column.get('blocks');keys=column.get('keys',[])
        if (type(n) is not int or not 1<=n<=40 or len(keys)!=n
                or any(type(column.get(f)) is not int for f in ('dx','dz'))
                or column.get('shape_mask')!=0 or column.get('decor')!=0):
            raise ValueError('invalid native baseline column: '+key)
        height=max(height,n)
        for y,shape in enumerate(keys):
            if not isinstance(shape,str) or not re.fullmatch(r'cube:\d+\|\d+(?:,\d+){5}',shape):
                raise ValueError('native baseline requires exact cube keys: '+key)
            result.append(dict(key=f'cube:{atlas_index}|'+shape.split('|',1)[1],palette=palette,
                               x=origin[0]+column['dx'],y=origin[1]+y,z=origin[2]+column['dz']))
    return result,height


def load_native_assembly_proof(descriptor, table_sha256, *, current_table=None):
    """Read an exact source rectangle from the original-table planner fixture.

    Unlike the absent-cell School fallback, an existing fitted entry may have
    been bypassed by native CUBE/ROOF planning. Evidence is per occurrence: the
    same source key can produce different columns beside different map art.
    """
    current=current_table is not None
    revision='current_table_sha256' if current else 'baseline_sha256'
    required={'evidence','evidence_sha256',revision,'occurrence','source_layout'}
    if current:required.update(('source_cell_hashes','source_template_hashes'))
    if not isinstance(descriptor,dict) or set(descriptor)!=required:
        raise ValueError('incomplete native assembly evidence')
    for field in ('evidence_sha256',revision):
        if not isinstance(descriptor[field],str) or not re.fullmatch('[0-9a-f]{64}',descriptor[field]):
            raise ValueError('invalid native assembly evidence hash')
    if not current and descriptor['baseline_sha256']!=table_sha256:
        raise ValueError('native assembly evidence belongs to another baseline table')
    path=(ROOT/descriptor['evidence']).resolve()
    if not path.is_relative_to((ROOT/'local').resolve()):
        raise ValueError('native assembly evidence must stay in local')
    data=path.read_bytes()
    if hashlib.sha256(data).hexdigest()!=descriptor['evidence_sha256']:
        raise ValueError('stale native assembly evidence')
    evidence=json.loads(data.decode('utf-8-sig'))
    if not current and evidence.get('original_baseline_sha256')!=table_sha256:
        raise ValueError('native assembly proof belongs to another baseline table')
    if current and evidence.get('current_table_sha256')!=descriptor[revision]:
        raise ValueError('native current assembly proof belongs to another table revision')
    occurrence=descriptor['occurrence'];layout=descriptor['source_layout']
    if (not isinstance(occurrence,dict) or set(occurrence)!={'map','x','y'}
            or not isinstance(occurrence['map'],str) or any(type(occurrence[f]) is not int or occurrence[f]<0 for f in ('x','y'))
            or not isinstance(layout,list) or not 1<=len(layout)<=32 or not isinstance(layout[0],list) or not 1<=len(layout[0])<=32
            or any(not isinstance(r,list) or len(r)!=len(layout[0]) or any(not isinstance(k,str) for k in r) for r in layout)):
        raise ValueError('invalid native assembly source occurrence')
    matches=[r for r in evidence.get('occurrences',[]) if r.get('map')==occurrence['map']
             and r.get('x')==occurrence['x'] and r.get('y')-(0 if 'layout' in r else 1)==occurrence['y']]
    if len(matches)!=1:
        raise ValueError('missing or conflicting native assembly context')
    row=matches[0]
    actual=row.get('layout',[[row.get('upper_key')],[row.get('base_key')]])
    if layout!=actual or ('layout' not in row and row.get('upper_position')!=[occurrence['x'],occurrence['y']]):
        raise ValueError('native assembly source identity changed')
    if current:
        hashes=descriptor['source_cell_hashes'];keys={key for line in layout for key in line}
        if not isinstance(hashes,dict) or set(hashes)!=keys or hashes!=row.get('current_source_cell_hashes'):
            raise ValueError('incomplete native current source metadata proof')
        for key,expected in hashes.items():
            value=current_table['cells'].get(key)
            actual_hash=hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest() if value is not None else None
            if expected!=actual_hash:
                raise ValueError('stale native current source cell metadata: '+key)
        if not isinstance(descriptor['source_template_hashes'],dict) or descriptor['source_template_hashes']!=row.get('current_source_template_hashes'):
            raise ValueError('incomplete native current template proof')
    return dict(descriptor=copy.deepcopy(descriptor),record=copy.deepcopy(row))


def native_source_template_hashes(table, evidence, art):
    """Fingerprint exact source templates intersecting this occurrence, including terrain exclusions."""
    descriptor=evidence['descriptor'];source,raw,grid=native_assembly_source(evidence,art)
    place=descriptor['occurrence'];x0,y0=place['x'],place['y'];d=len(descriptor['source_layout']);w=len(descriptor['source_layout'][0]);result={}
    for spec in table.get('buildings',[]):
        tiles=spec.get('tiles',[])
        if spec.get('atlas')!='g3q' or not tiles or len(tiles)%2 or len(tiles[0])%2:continue
        bd,bw=len(tiles)//2,len(tiles[0])//2
        pattern=[['g3q/'+'-'.join(str(tiles[y*2+dz][x*2+dx]) for dz in range(2) for dx in range(2)) for x in range(bw)] for y in range(bd)]
        for y in range(max(0,y0-bd+1),min(len(grid)-bd,y0+d-1)+1):
            for x in range(max(0,x0-bw+1),min(len(grid[0])-bw,x0+w-1)+1):
                if [line[x:x+bw] for line in grid[y:y+bd]]==pattern:
                    result[f'{spec["id"]}@{x},{y}']=hashlib.sha256(json.dumps(spec,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    return result


def native_assembly_templates(record, descriptor, table, source, raw, grid, atlas_index):
    """Validate actual fitted-parts carrier payloads against source-pinned production entries."""
    result={};placements={};specs={s['id']:s for s in table.get('buildings',[])}
    for placed in record.get('current_templates',[]):
        if not isinstance(placed,dict) or set(placed)!={'sig','x0','y0','entry'}:
            raise ValueError('invalid native template placement')
        x0,z0=placed['x0'],placed['y0'];entry=placed['entry'];sig=placed['sig']
        if any(type(v) is not int or v<0 or v%2 for v in (x0,z0)) or not isinstance(entry,dict):
            raise ValueError('invalid native template source coordinates')
        matches=[]
        for name in descriptor['source_template_hashes']:
            identifier,position=name.rsplit('@',1)
            if position==f'{x0//2},{z0//2}' and identifier in specs:matches.append(specs[identifier])
        if len(matches)!=1:raise ValueError('native template is not an exact source-pinned match')
        spec=matches[0]
        if 'parts' not in spec:raise ValueError('native template adapter requires explicit parts')
        tiles=spec['tiles'];w,d=len(tiles[0]),len(tiles);terrain={tuple(p) for p in spec.get('terrain',[])}
        pattern=[['g3q/'+'-'.join(str(tiles[z*2+dz][x*2+dx]) for dz in range(2) for dx in range(2)) for x in range(w//2)] for z in range(d//2)]
        if [line[x0//2:x0//2+w//2] for line in grid[z0//2:z0//2+d//2]]!=pattern:
            raise ValueError('native template source pattern changed')
        box={'roofRows':0,'roof':'flat','parts':copy.deepcopy(spec['parts']),'height':spec.get('height',64),'terrain':spec.get('terrain',[]),'bands':spec.get('bands',[])}
        for flag in ('interior','preserveTerrain','preserveCarriers','berrySoil'):
            if spec.get(flag) is True:box[flag]=True
        if 'carrierLevels' in spec:box['carrierLevels']=copy.deepcopy(spec['carrierLevels'])
        if 'outline' in spec:box['outline']=spec['outline']
        high=min(30,record['headroom'],max(2,math.ceil(box['height']/8)))
        outdoor=source.get('mapType') in (1,2,3,6);solid=[];air=[];doors=[]
        native_profile={}
        berry_soil=spec.get('berrySoil') is True
        if berry_soil:
            if spec.get('preserveCarriers') is not True or any(part.get('shape')!='box' or 'carve' in part or len(part.get('y',[]))!=2 or not(part['y'][0]==0<part['y'][1]<=4) for part in spec['parts']):
                raise ValueError('native berry soil requires explicit shallow carrier-preserving boxes')
        if spec.get('preserveCarriers') is True:
            if spec.get('preserveTerrain') is not True or not outdoor or not re.fullmatch(r'[0-9a-f]{64}',str(record.get('carrier_reference_table_sha256',''))):
                raise ValueError('native carrier preservation lacks exact outdoor reference proof')
            columns=record.get('current_carrier_reference')
            rw,rd=len(descriptor['source_layout'][0])*2,len(descriptor['source_layout'])*2
            if not isinstance(columns,list) or len(columns)!=rw*rd:raise ValueError('incomplete native carrier reference')
            for column in columns:
                if (not isinstance(column,dict) or set(column)!={'x','z','blocks','decor','floor','feet'}
                        or any(type(v) is not int for v in column.values()) or not(0<=column['x']<rw and 0<=column['z']<rd)
                        or not(0<=column['blocks']<=30) or column['decor']!=0 or column['floor']< -1 or column['feet']<0):
                    raise ValueError('invalid native carrier reference')
                position=(column['x'],column['z'])
                if position in native_profile:raise ValueError('duplicate native carrier reference')
                native_profile[position]=column
            from tools.shape_fit.emerald_pass.carrier_levels import valid as carrier_levels_valid
            levels=[]
            for z in range(d//2):
                line=[]
                for x in range(w//2):
                    if (x,z) in terrain:line.append(None);continue
                    positions=[(x0+2*x+dx-descriptor['occurrence']['x']*2,z0+2*z+dz-descriptor['occurrence']['y']*2) for dz in range(2) for dx in range(2)]
                    if any(position not in native_profile for position in positions):raise ValueError('native carrier level witness leaves source reference')
                    feet={native_profile[position]['feet'] for position in positions}
                    if len(feet)!=1:raise ValueError('native carrier cell has mixed quarter feet')
                    line.append(next(iter(feet)))
                levels.append(line)
            if not carrier_levels_valid(spec.get('carrierLevels'),levels,spec['parts']):raise ValueError('native carrier reference differs from independent level witness')
        for z in range(d):
            for x in range(w):
                collision=raw[((z0//2+z//2)*source['w']+x0//2+x//2)*4+2]
                claimed=(x//2,z//2) not in terrain
                if claimed and collision not in (0,7,113,114,144):raise ValueError('unsupported native template source collision')
                s=high if claimed and collision in (7,144) else 0
                if not outdoor and spec.get('interior') is True and box['height']<=8 and collision==144:s=min(s,1)
                a=high if claimed and collision in (0,113,114) else 0
                if native_profile:
                    relative=(x0+x-descriptor['occurrence']['x']*2,z0+z-descriptor['occurrence']['y']*2)
                    if relative not in native_profile:raise ValueError('native carrier template extends outside reference proof')
                    reference=native_profile[relative]
                    if claimed and (collision not in (0,7) or reference['blocks']>high or (collision==0 and reference['blocks']!=0)):
                        raise ValueError('native carrier reference changes source collision or headroom')
                    s=reference['blocks'] if claimed else 0;a=0
                    if berry_soil and claimed:
                        cx,cz=(x0+x)//2,(z0+z)//2
                        supported={'g3q/11197-11467-11204-11207','g3q/11204-11205-11205-11204','g3q/11205-11204-11220-11221','g3q/11460-11198-11462-11205','g3q/11463-11464-11465-11466'}
                        objects=[o for o in source.get('objects',[]) if o['x']==cx and o['y']==cz]
                        if (collision!=7 or grid[cz][cx] not in supported or len(objects)!=1 or objects[0].get('gfx')!='OBJ_EVENT_GFX_BERRY_TREE'
                                or objects[0].get('kind')!='berry' or objects[0].get('stays') is not True or objects[0].get('hidden',False)):
                            raise ValueError('native berry soil source object identity changed')
                solid.append(s);air.append(a)
                if a and x%2==0 and z%2==0:doors.append([x//2,z//2])
        ground=entry.get('ground')
        if type(ground) is not int or ground<0:raise ValueError('invalid native template ground')
        expected={'template':1,'atlas':0,'w':w,'d':d,'perCell':2,'tiles':[t for line in tiles for t in line],'doors':doors,'ground':ground,'outdoor':outdoor,'columns':[[] for _ in range(w*d)],'hull':{'kind':'box','height':box['height'],'solid':solid,'air':air,'box':box}}
        if entry!=expected or template_signature(entry)!=sig:raise ValueError('native template payload differs from source metadata and carriers')
        if sig in result:raise ValueError('duplicate native template signature')
        rewritten=copy.deepcopy(entry);rewritten['atlas']=atlas_index;rewritten['sig']=template_signature(rewritten)
        result[sig]=rewritten;placements[sig]=(x0,z0,solid,air)
    return result,placements


def native_surface_fragments(record, descriptor, table, source, raw, grid, atlas_index, current, templates, template_places):
    """Render only the visible carriers exported by WorldPlan, sharing one old/new vertical datum."""
    from tools.shape_fit.emerald_pass.native_surfaces import consume
    if record.get('source_mask') is not None:
        raise ValueError('native surface contexts require complete source rectangles')
    x0,z0=descriptor['occurrence']['x']*2,descriptor['occurrence']['y']*2
    seen=set()
    def template_fragment(shape,quarter,block):
        match=re.fullmatch(r'tmpl:0\|([0-9a-f]{16})\|(\d+),(\d+),(\d+)',shape)
        if not current or match is None or match[1] not in templates:
            raise ValueError('native surface template lacks exact current payload proof')
        sig=match[1];tx,by,tz=map(int,match.group(2,3,4));template=templates[sig];px,pz,solid,air=template_places[sig]
        if (not 0<=tx<template['w'] or not 0<=tz<template['d']
                or not 0<=by<max(solid[tz*template['w']+tx],air[tz*template['w']+tx])
                or (quarter['x'],quarter['z'],block['y'])!=(px-x0+tx,pz-z0+tz,quarter['feet']+by)):
            raise ValueError('native surface template differs from exact source carrier')
        seen.add((sig,tx,by,tz));return f'tmpl:0|{template["sig"]}|{tx},{by},{tz}'
    value=consume(record,source=source,raw=raw,grid=grid,revision='after' if current else 'before',table=table,model_hash=model_hash,template_validator=template_fragment if current else None)
    width,depth=value['bounds_xz'];expected=set()
    for sig,template in templates.items():
        px,pz,solid,air=template_places[sig]
        for tz in range(template['d']):
            for tx in range(template['w']):
                if 0<=px-x0+tx<width and 0<=pz-z0+tz<depth:
                    expected.update((sig,tx,by,tz) for by in range(max(solid[tz*template['w']+tx],air[tz*template['w']+tx])))
    if seen!=expected:raise ValueError('native surface template carrier coverage differs')
    # Both revisions use the same exact proof origin. Do not independently
    # lower a current surface and hide an actual elevation change.
    heights=[block['y'] for q in record['source_surface_columns'] for rev in ('before','after') for block in q[rev] if block.get('visible') is True]
    if not heights or any(type(y) is not int or not -1<=y<=95 for y in heights):
        raise ValueError('invalid native surface common vertical datum')
    lift=-min(heights);fragments=[dict(f,key=f['key'].replace(':0|',f':{atlas_index}|',1),y=f['y']+lift) for f in value['fragments']]
    height=max(f['y'] for f in fragments)+1
    if height>64 or width*height*depth>64*40*64:
        raise ValueError('native surface scene exceeds bounded gallery height or placement volume')
    audit={'source_quarters':value['source_quarters'],'invisible_carriers':value['audit'],'common_vertical_lift':lift,'source_evidence_mode':record['surface_evidence_mode'],'datum':value['datum']}
    return fragments,[width,height,depth],audit


def native_assembly_fragments(entity, table, art, atlases):
    """Validate source, original voxel hashes and documented keys before replacing one baseline exhibit."""
    current='_current_native_fragments' in entity
    if (not current and not entity.get('_baseline_comparison')) or (current and entity.get('_baseline_comparison')) or table.get('game')!='emerald':
        raise ValueError('native assembly fragments are baseline-only Emerald evidence')
    evidence=entity['_current_native_fragments' if current else '_baseline_native_fragments'];descriptor=evidence['descriptor'];record=evidence['record']
    layout=descriptor['source_layout'];occurrence=descriptor['occurrence']
    if entity.get('reference_layout',entity['layout'])!=layout:
        raise ValueError('native assembly layout differs from source evidence')
    if len(entity['layout'])!=len(layout) or any(len(a)!=len(b) for a,b in zip(entity['layout'],layout)):
        raise ValueError('native assembly layout dimensions changed')
    for row,expected in zip(entity['layout'],layout):
        if any(key not in (None,source) for key,source in zip(row,expected)):raise ValueError('native assembly layout changed source identity')
    source,raw,grid=native_assembly_source(evidence,art);x0,y0=occurrence['x'],occurrence['y']
    if current and native_source_template_hashes(table,evidence,art)!=descriptor['source_template_hashes']:
        raise ValueError('stale native current source template metadata')
    width,depth=len(layout[0]),len(layout)
    if y0+depth>len(grid) or x0+width>len(grid[0]) or [grid[y0+dy][x0:x0+width] for dy in range(depth)]!=layout:
        raise ValueError('native assembly source occurrence changed')
    collisions=record.get('source_collisions',[[record.get('upper_collision')],[record.get('base_collision')]])
    if [[raw[((y0+dy)*source['w']+x0+dx)*4+2] for dx in range(width)] for dy in range(depth)]!=collisions:
        raise ValueError('native assembly source collision changed')
    keys={key for row in layout for key in row};atlas_names={split_key(key)[0] for key in keys}
    source_mask=record.get('source_mask')
    if source_mask is not None:
        if (not isinstance(source_mask,list) or len(source_mask)!=depth
                or any(not isinstance(row,list) or len(row)!=width or any(type(v) is not bool for v in row) for row in source_mask)
                or source_mask!=[[key is not None for key in row] for row in entity['layout']]):
            raise ValueError('native assembly source mask differs from exhibit')
        keys={key for z,row in enumerate(layout) for x,key in enumerate(row) if source_mask[z][x]}
    if len(atlas_names)!=1:raise ValueError('native assembly requires one atlas')
    atlas=next(iter(atlas_names));atlas_index=atlases.index(atlas)
    templates,template_places=native_assembly_templates(record,descriptor,table,source,raw,grid,atlas_index) if current else ({},{})
    if 'source_surface_columns' in record:
        fragments,bounds,audit=native_surface_fragments(record,descriptor,table,source,raw,grid,atlas_index,current,templates,template_places)
        evidence=dict(evidence,native_surface_audit=audit)
        if templates:evidence['templates']=list(templates.values())
        return fragments,bounds,evidence
    seen_template_fragments=set()
    floor_columns=record.get('source_floor_columns')
    floors={}
    if floor_columns is not None:
        if not isinstance(floor_columns,list) or not 1<=len(floor_columns)<=width*depth*4:
            raise ValueError('invalid native source floor count')
        for column in floor_columns:
            if (not isinstance(column,dict) or set(column)!={'x','z','feet','before_tile','after_tile'}
                    or any(type(column[n]) is not int for n in column)
                    or not (0<=column['x']<width*2 and 0<=column['z']<depth*2 and 0<=column['feet']<=30)
                    or min(column['before_tile'],column['after_tile'])<0):
                raise ValueError('invalid native source floor column')
            x,z=column['x'],column['z']
            if (x,z) in floors:raise ValueError('overlapping native source floor columns')
            if collisions[z//2][x//2] not in (0,7,43,113,114,144,147,161,163):
                raise ValueError('native source full floor requires a documented ground kind')
            if source_mask is not None and not source_mask[z//2][x//2]:
                raise ValueError('native source floor lies outside source mask')
            floors[x,z]=column
        expected={(x,z) for z in range(depth*2) for x in range(width*2)
                  if (source_mask is None or source_mask[z//2][x//2]) and collisions[z//2][x//2] in (0,7,43,113,114,144,147,161,163)}
        if set(floors)!=expected:raise ValueError('native source full floor coverage differs')
    fragments=record.get('corrected_fragments' if current else 'original_baseline_fragments')
    if not isinstance(fragments,list) or not (0 if floors else 1)<=len(fragments)<=width*depth*4*30:
        raise ValueError('invalid native assembly fragment count')
    fitted_lifts={}
    for lift in record.get('current_fitted_lifts' if current else 'original_fitted_lifts',[]):
        if (not isinstance(lift,dict) or set(lift)!={'x','z','base_y','key'}
                or any(type(lift[n]) is not int for n in ('x','z','base_y'))
                or not 0<=lift['x']<width*2 or not 0<=lift['z']<depth*2 or not 1<=lift['base_y']<29
                or lift['key']!=layout[lift['z']//2][lift['x']//2]
                or collisions[lift['z']//2][lift['x']//2]!=7):
            raise ValueError('invalid native fitted lift evidence')
        identity=(lift['x'],lift['z'],lift['key'])
        if identity in fitted_lifts:raise ValueError('duplicate native fitted lift evidence')
        column={f['y']:f for f in fragments if f['x']==lift['x'] and f['z']==lift['z']}
        if any(y not in column or not column[y]['key'].startswith(('cube:0|','roof:0|')) for y in range(lift['base_y'])):
            raise ValueError('native fitted lift lacks native support')
        for by in (0,1):
            fragment=column.get(lift['base_y']+by,{})
            pattern=r'cell:0\|'+re.escape(lift['key'].split('/')[1].replace('-',','))+r'\|-?\d+\|'+str(lift['x']%2)+','+str(by)+','+str(lift['z']%2)+r'\|v='
            if not re.match(pattern,fragment.get('key','')):raise ValueError('native fitted lift requires complete source quarters')
        fitted_lifts[identity]=lift['base_y']
    result=[];positions=set()
    for fragment in fragments:
        if (not isinstance(fragment,dict) or set(fragment)!={'key','palette','x','y','z'} or fragment['palette']!='BAKED'
                or any(type(fragment[n]) is not int for n in ('x','y','z'))
                or not (0<=fragment['x']<width*2 and 0<=fragment['z']<depth*2 and 0<=fragment['y']<30)):
            raise ValueError('invalid native assembly fragment placement')
        position=tuple(fragment[n] for n in ('x','y','z'))
        if source_mask is not None and not source_mask[fragment['z']//2][fragment['x']//2]:
            raise ValueError('native assembly fragment lies outside source mask')
        if position in positions:raise ValueError('overlapping native assembly fragments')
        positions.add(position);shape=fragment['key']
        if not isinstance(shape,str):raise ValueError('invalid native assembly shape key')
        cube=re.fullmatch(r'cube:0\|(-?\d+(?:,-?\d+){5})',shape)
        roof=re.fullmatch(r'roof:0\|(-?\d+(?:,-?\d+){2})\|(\d+),(\d+)\|([01]),([01])',shape)
        # A layered entry's upper layers append their two-digit layer to the model hash (fragments_for_cell).
        cell=re.fullmatch(r'cell:0\|(\d+(?:,\d+){3})\|(-?\d+)\|([01]),([01]),([01])\|v=g[0-9a-f]i?r?s?t?_voxels_k(\d{12})((?:0[1-9]|1[0-4])?)',shape)
        native_cell=re.fullmatch(r'cell:0\|(\d+(?:,\d+){3})\|(-?\d+)\|([01]),([01]),([01])\|v=g[0-9a-f]i?r?s?t?',shape)
        ledge_slab=re.fullmatch(r'slab:0\|(\d+)\|4',shape)
        template_fragment=re.fullmatch(r'tmpl:0\|([0-9a-f]{16})\|(\d+),(\d+),(\d+)',shape)
        if cube or roof:
            if any(n < -1 for n in map(int,(cube or roof).group(1).split(','))):raise ValueError('invalid native assembly tile id')
            if roof and any(not 0<=int(roof.group(i))<=16 for i in (2,3)):raise ValueError('invalid native assembly roof height')
        elif cell:
            key=atlas+'/'+cell.group(1).replace(',','-')
            if key not in keys or key not in table['cells'] or cell.group(6)!=model_hash(table['cells'][key])[0]:
                raise ValueError('native assembly fitted key differs from original baseline')
            layer=int(cell.group(7) or 0)
            if layer>len(table['cells'][key].get('up',[])) or layer>fragment['y']//2:
                raise ValueError('native assembly fitted layer absent from its entry')
            lift=fitted_lifts.get((fragment['x'],fragment['z'],key),0)
            expected_y=fragment['y']-lift if lift else fragment['y']%2
            if (int(cell.group(3)),int(cell.group(5)))!=(fragment['x']%2,fragment['z']%2) or int(cell.group(4))!=expected_y:
                raise ValueError('native assembly fitted quadrant mismatch')
        elif native_cell:
            key=atlas+'/'+native_cell.group(1).replace(',','-')
            if key not in keys or key in table['cells']:
                raise ValueError('unfitted native CELL evidence requires an absent source entry')
            if (int(native_cell.group(3)),int(native_cell.group(5)))!=(fragment['x']%2,fragment['z']%2) or int(native_cell.group(4))!=fragment['y'] or fragment['y']>=2:
                raise ValueError('native assembly unfitted quadrant mismatch')
        elif ledge_slab:
            if fragment['y']!=0 or collisions[fragment['z']//2][fragment['x']//2] not in (161,163):
                raise ValueError('native assembly ledge slab requires exact raw161/raw163 ground carrier')
        elif template_fragment and current:
            sig=template_fragment[1];tx,by,tz=map(int,template_fragment.group(2,3,4))
            if sig not in templates:raise ValueError('native template fragment lacks exact carrier proof')
            template=templates[sig];px,pz,solid,air=template_places[sig]
            if not 0<=tx<template['w'] or not 0<=tz<template['d'] or not 0<=by<max(solid[tz*template['w']+tx],air[tz*template['w']+tx]):
                raise ValueError('native template fragment outside claimed carrier')
            if (fragment['x'],fragment['y'],fragment['z'])!=(px-x0*2+tx,by,pz-y0*2+tz):raise ValueError('native template fragment moved from source carrier')
            seen_template_fragments.add((sig,tx,by,tz));shape=f'tmpl:0|{template["sig"]}|{tx},{by},{tz}'
        else:raise ValueError('native assembly requires documented CUBE/ROOF/voxel CELL/native ledge SLAB keys')
        result.append(dict(fragment,key=shape.replace(':0|',f':{atlas_index}|',1)))
    expected_template_fragments=set()
    for sig,template in templates.items():
        px,pz,solid,air=template_places[sig]
        for tz in range(template['d']):
            for tx in range(template['w']):
                x,z=px-x0*2+tx,pz-y0*2+tz
                if 0<=x<width*2 and 0<=z<depth*2 and (source_mask is None or source_mask[z//2][x//2]):
                    expected_template_fragments.update((sig,tx,by,tz) for by in range(max(solid[tz*template['w']+tx],air[tz*template['w']+tx])))
    if seen_template_fragments!=expected_template_fragments:raise ValueError('native template carrier coverage differs')
    for x,z in {(f['x'],f['z']) for f in result}:
        ys=sorted(f['y'] for f in result if f['x']==x and f['z']==z)
        if ys!=list(range(len(ys))):raise ValueError('native assembly column has gaps')
    if floors:
        if any((f['x'],f['z']) not in floors for f in result):raise ValueError('native source carrier lacks its proven floor')
        lift=1-min(column['feet'] for column in floors.values())
        for f in result:f['y']+=floors[f['x'],f['z']]['feet']+lift
        for (x,z),column in sorted(floors.items(),key=lambda item:(item[0][1],item[0][0])):
            tile=column['after_tile' if current else 'before_tile']
            result.append({'key':f'cube:{atlas_index}|'+','.join([str(tile)]*6),'palette':'BAKED','x':x,'y':column['feet']-1+lift,'z':z})
        if max(f['y'] for f in result)>=40:raise ValueError('native source scene exceeds bounded gallery height')
    if templates:evidence=dict(evidence,templates=list(templates.values()))
    return result,[width*2,max(f['y'] for f in result)+1,depth*2],evidence


def native_assembly_source(evidence, art):
    """Verify raw source bytes, or the exact supported Elite G3Built open state.

    This is a gallery-only source occurrence adapter. It never changes the raw
    art inventory, runtime collision, story states or production templates.
    """
    descriptor,record=evidence['descriptor'],evidence['record']
    place=descriptor['occurrence'];source=art.maps.get(place['map'])
    if source is None:raise ValueError('native assembly source map absent')
    raw=base64.b64decode(source['cells'],validate=True)
    if hashlib.sha256(raw).hexdigest()!=record.get('map_cells_sha256'):
        raise ValueError('native assembly source map changed')
    if 'built_source' in record:
        mode=record['built_source'];layout=descriptor['source_layout']
        allowed={'EM_EVER_GRANDE_CITY_'+name for name in
                 ('SIDNEYS_ROOM','PHOEBES_ROOM','GLACIAS_ROOM','DRAKES_ROOM','CHAMPIONS_ROOM',
                  'HALL1','HALL2','HALL3','HALL4','HALL5')}
        if not isinstance(mode,dict) or set(mode)!={'mode','sites_sha256'}:
            raise ValueError('unsupported native assembly built source')
        elite=mode['mode']=='elite-open-arch-v1' and place['map'] in allowed
        trick=(mode['mode']=='generic-interior-arch-v1'
               and place=={'map':'EM_ROUTE110_TRICK_HOUSE_ENTRANCE','x':4,'y':0})
        if (not (elite or trick) or len(layout)!=2 or any(len(row)!=3 for row in layout)):
            raise ValueError('unsupported native assembly built source')
        sites_raw=(art.bundle/'g3sites/all.json').read_bytes()
        if hashlib.sha256(sites_raw).hexdigest()!=mode['sites_sha256']:
            raise ValueError('native assembly story source changed')
        cooked=bytearray(raw);seen=set()
        for site in json.loads(sites_raw)['sites']:
            if site['map']!=place['map'] or not (place['x']<=site['x']<place['x']+3 and place['y']<=site['y']<place['y']+2):continue
            position=(site['x'],site['y'])
            if position in seen:raise ValueError('conflicting native assembly story site')
            seen.add(position);offset=(site['y']*source['w']+site['x'])*4;own=site['own']
            if [int.from_bytes(raw[offset:offset+2],'little'),raw[offset+2],raw[offset+3]]!=[own['metatile'],own['coll'],own['elev']]:
                raise ValueError('native assembly story own state differs from raw source')
            states=[own,*site['states']]
            # This narrow source case uses just ground, warp-ground and solid.
            # Ties keep the map's own state, exactly G3Built.built().
            if any(s['coll'] not in (0,7,114) for s in states):
                raise ValueError('unsupported native assembly story collision')
            built=min(states,key=lambda s:1 if s['coll']==7 else 0)
            if trick and (position!=(5,1) or own['metatile']!=539 or own['coll']!=7
                          or built['metatile']!=537 or built['coll']!=114):
                raise ValueError('unsupported Trick House story state')
            cooked[offset:offset+4]=int(built['metatile']).to_bytes(2,'little')+bytes([built['coll'],built['elev']])
        if trick and seen!={(5,1)}:
            raise ValueError('Trick House story site is missing')
        if ('built_cells_sha256' in record
                and hashlib.sha256(cooked).hexdigest()!=record['built_cells_sha256']):
            raise ValueError('native assembly normalized source hash changed')
        raw=bytes(cooked);source=dict(source,cells=base64.b64encode(raw).decode())
    return source,raw,art.map_cells(source)


class BlueArt:
    """Reassemble cells and maps directly from the exported sprite sheets."""
    def __init__(self, bundle: Path):
        self.bundle = bundle
        self.tilesets = {p.stem: read_json(p) for p in sorted((bundle / "tileset").glob("*.json"))}
        self.by_atlas = {t["image"]: t for t in self.tilesets.values()}
        self.palettes = read_json(bundle / "palettes/sgb.json")["palettes"]
        self.images: dict[str, Image.Image] = {}
        self.maps = {p.stem: read_json(p) for p in sorted((bundle / "map").glob("*.json"))}
        self.locations: dict[str, list[dict]] = defaultdict(list)
        for name, source in self.maps.items():
            grid = self.map_cells(source)
            for y, row in enumerate(grid):
                for x, key in enumerate(row):
                    self.locations[key].append({"map": name, "x": x, "y": y,
                                                "palette": source.get("palette", "GRAYMON")})
        for places in self.locations.values():
            places.sort(key=lambda p: (p["map"] != "PALLET_TOWN", p["map"], p["y"], p["x"]))

    def map_cells(self, source: dict) -> list[list[str]]:
        tileset = self.tilesets[source["tileset"]]
        w, h = source["width"], source["height"]
        blocks = source["blocks"]
        if len(blocks) != w * h:
            raise ValueError(f"map {source.get('id')} has an incomplete block grid")
        grid = [[""] * (w * 2) for _ in range(h * 2)]
        for by in range(h):
            for bx in range(w):
                block = tileset["blocks"][blocks[by * w + bx]]
                for cy in range(2):
                    for cx in range(2):
                        start = cy * 8 + cx * 2
                        ids = [block[start], block[start + 1], block[start + 4], block[start + 5]]
                        grid[by * 2 + cy][bx * 2 + cx] = tileset["image"] + "/" + "-".join(map(str, ids))
        return grid

    def sheet(self, atlas: str) -> Image.Image:
        if atlas not in self.images:
            source = read_json(self.bundle / "image" / (atlas + ".json"))
            self.images[atlas] = Image.open(io.BytesIO(base64.b64decode(source["png"]))).convert("L")
        return self.images[atlas]

    def cell(self, key: str, palette: str) -> Image.Image:
        atlas, ids = split_key(key)
        sheet = self.sheet(atlas)
        per_row = self.by_atlas[atlas]["tilesPerRow"]
        result = Image.new("L", (16, 16))
        for index, tile in enumerate(ids):
            x, y = tile % per_row * 8, tile // per_row * 8
            if x + 8 > sheet.width or y + 8 > sheet.height:
                raise ValueError(f"tile outside sprite sheet: {key}")
            result.paste(sheet.crop((x, y, x + 8, y + 8)), (index % 2 * 8, index // 2 * 8))
        colors = self.palettes.get(palette, self.palettes.get("GRAYMON"))
        if colors is None:
            colors = [[255, 255, 255], [170, 170, 170], [85, 85, 85], [0, 0, 0]]
        painted = Image.new("RGBA", result.size)
        painted.putdata([tuple(colors[max(0, min(3, (255 - p + 42) // 85))]) + (255,)
                         for p in result.tobytes()])
        return painted

    def drawing(self, layout: list[list[str | None]], palette: str) -> Image.Image:
        result = Image.new("RGBA", (len(layout[0]) * 16, len(layout) * 16))
        for y, row in enumerate(layout):
            for x, key in enumerate(row):
                if key:
                    result.paste(self.cell(key, palette), (x * 16, y * 16))
        return result

    def map_image(self, name: str) -> Image.Image:
        source = self.maps[name]
        return self.drawing(self.map_cells(source), source.get("palette", "GRAYMON"))


class EmeraldArt(BlueArt):
    """Use the export's already composed, full-colour GBA quarter pages."""
    def __init__(self, bundle: Path):
        self.bundle = bundle
        self.tilesets = {p.stem: read_json(p) for p in sorted((bundle / "g3tiles").glob("*.json"))}
        self.quarters = {name: dict(zip(t["mids"], t["quarters"])) for name, t in self.tilesets.items()}
        self.images: dict[str, Image.Image] = {}
        self.maps = {p.stem: read_json(p) for p in sorted((bundle / "g3map").glob("*.json"))}
        self.locations: dict[str, list[dict]] = defaultdict(list)
        for name, source in self.maps.items():
            source["palette"] = "BAKED"
            for y, row in enumerate(self.map_cells(source)):
                for x, key in enumerate(row):
                    self.locations[key].append({"map": name, "x": x, "y": y, "palette": "BAKED"})
        for places in self.locations.values():
            places.sort(key=lambda p: (p["map"] != "EM_LITTLEROOT_TOWN", p["map"], p["y"], p["x"]))

    def map_cells(self, source: dict) -> list[list[str]]:
        w, h = source["w"], source["h"]
        data = base64.b64decode(source["cells"], validate=True)
        if len(data) != w * h * 4:
            raise ValueError(f"map {source.get('id')} has an incomplete cell grid")
        quarters = self.quarters[source["pair"]]
        border = source.get("border", {})
        grid = []
        for y in range(h):
            row = []
            for x in range(w):
                index = (y * w + x) * 4
                mid = int.from_bytes(data[index:index + 2], "little")
                ids = quarters.get(mid)
                if ids is None:
                    # Match G3Cells' border fallback for absent metatiles.
                    fallback = border["mids"][(y % border["h"]) * border["w"] + x % border["w"]]
                    ids = quarters[fallback]
                row.append("g3q/" + "-".join(map(str, ids)))
            grid.append(row)
        return grid

    def sheet(self, atlas: str) -> Image.Image:
        if atlas not in self.images:
            source = read_json(self.bundle / getattr(self, "image_dir", "g3image") / (atlas.replace("g3q.", getattr(self, "page_prefix", "g3q.")) + ".json"))
            # The in-game baked terrain renderer treats background pixels as opaque.
            self.images[atlas] = Image.open(io.BytesIO(base64.b64decode(source["png"]))).convert("RGB").convert("RGBA")
        return self.images[atlas]

    def cell(self, key: str, palette: str) -> Image.Image:
        atlas, ids = split_key(key)
        if atlas != "g3q" or palette != "BAKED":
            raise ValueError(f"baked cell must use g3q and BAKED: {key}, {palette}")
        result = Image.new("RGBA", (16, 16))
        for index, quarter in enumerate(ids):
            page = self.sheet(f"g3q.{quarter // 1024}")
            tile = quarter % 1024
            x, y = tile % 32 * 8, tile // 32 * 8
            if x + 8 > page.width or y + 8 > page.height:
                raise ValueError(f"quarter outside sprite sheet: {key}")
            result.paste(page.crop((x, y, x + 8, y + 8)), (index % 2 * 8, index // 2 * 8))
        return result


class CrystalArt(EmeraldArt):
    """Gen 2 block layouts, with already coloured g2q pages behind virtual g3q."""
    image_dir, page_prefix = "g2image", "g2q."

    def __init__(self, bundle: Path):
        self.bundle = bundle
        self.tilesets = {p.stem: read_json(p) for p in sorted((bundle / "g2tiles").glob("*.json"))}
        self.images = {}
        self.maps = {p.stem: read_json(p) for p in sorted((bundle / "g2map").glob("*.json"))}
        self.locations = defaultdict(list)
        for name, source in self.maps.items():
            source["palette"] = "BAKED"
            for y, row in enumerate(self.map_cells(source)):
                for x, key in enumerate(row):
                    self.locations[key].append({"map": name, "x": x, "y": y, "palette": "BAKED"})

    def map_cells(self, source: dict) -> list[list[str]]:
        tileset = self.tilesets[source.get("variant", source["tileset"])]
        w, h = source["w"], source["h"]
        blocks = base64.b64decode(source["blocks"], validate=True)
        if len(blocks) != w * h:
            raise ValueError(f"map {source.get('id')} has an incomplete block grid")
        grid = [[""] * (w * 2) for _ in range(h * 2)]
        for by in range(h):
            for bx in range(w):
                block_id = blocks[by * w + bx]
                if block_id >= len(tileset["blocks"]):
                    block_id = source["border"]
                block = tileset["blocks"][block_id]
                for cy in range(2):
                    for cx in range(2):
                        start = cy * 8 + cx * 2
                        ids = [block[start], block[start + 1], block[start + 4], block[start + 5]]
                        grid[by * 2 + cy][bx * 2 + cx] = "g3q/" + "-".join(map(str, ids))
        return grid


def table_atlases(table: dict, buildings=False) -> list[str]:
    atlases = {split_key(k)[0] for k, v in table["cells"].items() if v.get("class") == "voxels"}
    if buildings:
        atlases.update(b["atlas"] for b in table.get("buildings", []))
    return sorted(atlases)


def canonical(value):
    """Match ProtoJson's canonical form, including integral floating numbers."""
    if isinstance(value, float) and value.is_integer():
        return int(value)
    if isinstance(value, list):
        return [canonical(v) for v in value]
    if isinstance(value, dict):
        return {k: canonical(v) for k, v in value.items()}
    return value


def template_signature(template: dict) -> str:
    body = {k: v for k, v in template.items() if k != "sig"}
    data = json.dumps(canonical(body), sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    return hashlib.sha256(data).hexdigest()[:16]


def building_catalog(table: dict, art: BlueArt) -> list[dict]:
    """The fitted template contract, rendered by Minecraft's normal building code."""
    atlases = table_atlases(table, buildings=True)
    grids = {name: art.map_cells(source) for name, source in art.maps.items()}
    entries = []
    for number, building in enumerate(table.get("buildings", [])):
        identifier = building.get("id", f"{table['game']}_building_{number:03d}")
        tile_rows, atlas = building["tiles"], building["atlas"]
        d, w = len(tile_rows), len(tile_rows[0])
        if w % 2 or d % 2 or any(len(row) != w for row in tile_rows):
            raise ValueError("building tile grid must be rectangular and cell-aligned")
        layout = [[atlas + "/" + "-".join(map(str, (tile_rows[y][x], tile_rows[y][x + 1],
                                                     tile_rows[y + 1][x], tile_rows[y + 1][x + 1])))
                   for x in range(0, w, 2)] for y in range(0, d, 2)]
        locations = []
        for place in art.locations.get(layout[0][0], []):
            grid, x, y = grids[place["map"]], place["x"], place["y"]
            if y + d // 2 <= len(grid) and x + w // 2 <= len(grid[0]) and all(
                    grid[y + dy][x:x + w // 2] == row for dy, row in enumerate(layout)):
                locations.append(dict(place))
        # IDs retain the map where a shared design was first marked. Prefer that
        # complete occurrence rather than an unrelated map sharing the art.
        named_map = identifier.split("_", 2)[-1]
        locations.sort(key=lambda p: (p["map"] != named_map, p["map"], p["y"], p["x"]))
        palette = locations[0]["palette"] if locations else ("BAKED" if table["game"] != "blue" else "GRAYMON")
        doors = []
        if locations:
            place = locations[0]
            for door in art.maps[place["map"]].get("warps", []):
                x, y = door["x"] - place["x"], door["y"] - place["y"]
                if 0 <= x < w // 2 and 0 <= y < d // 2:
                    doors.append([x, y])
        # Exactly FittedShapes.parseBuildings' spec, not arbitrary table metadata.
        if "parts" in building:
            box = {"roofRows": 0, "roof": "flat", "parts": copy.deepcopy(building["parts"]),
                   "height": building.get("height", 64), "terrain": building.get("terrain", [])}
            if building.get("preserveTerrain") is True:
                box["preserveTerrain"] = True
            if building.get("preserveCarriers") is True:
                box["preserveCarriers"] = True
            if "carrierLevels" in building:
                box["carrierLevels"] = copy.deepcopy(building["carrierLevels"])
            if building.get("berrySoil") is True:
                box["berrySoil"] = True
            height = max(1, box["height"])
        else:
            box = {"roofRows": building["roofRows"], "roof": building["roof"]}
            height = math.floor(1.5 * max(1, d * 8 - box["roofRows"]) + .5) + (16 if box["roof"] == "hip" and box["roofRows"] > 0 else 6)
        box["bands"] = building.get("bands", [])
        if "outline" in building:
            box["outline"] = building["outline"]
        high = min(30, max(2, math.ceil(height / 8)))
        terrain = set(map(tuple, box.get("terrain", [])))
        solid, air = [], []
        for z in range(d):
            for x in range(w):
                occupied = (x // 2, z // 2) not in terrain
                door = [x // 2, z // 2] in doors
                solid.append(high if occupied and not door else 0)
                air.append(high if occupied and door else 0)
        template = {"template": 1, "atlas": atlases.index(atlas), "w": w, "d": d, "perCell": 2,
                    "tiles": [t for row in tile_rows for t in row], "doors": doors, "ground": 0,
                    "outdoor": True, "columns": [[] for _ in range(w * d)],
                    "hull": {"kind": "box", "height": height, "solid": solid, "air": air, "box": box}}
        template = canonical(template)
        sig = template["sig"] = template_signature(template)
        fragments = [{"key": f"tmpl:{template['atlas']}|{sig}|{x},{y},{z}", "palette": palette,
                      "x": x, "y": y, "z": z} for z in range(d) for x in range(w)
                     for y in range(max(solid[z * w + x], air[z * w + x]))]
        entries.append({"id": identifier, "label": identifier, "kind": "building", "layout": layout,
                        "placement_mode": "floor", "palette": palette, "source_keys": [k for row in layout for k in row],
                        "occurrences": locations, "model_hashes": {identifier: sig}, "building_index": number,
                        "sprite": f"sprites/{identifier}.png", "fragments": fragments, "bounds": [w, high, d],
                        "templates": [template]})
    return entries


def cyclic_stack_keys(cells: dict) -> list[str]:
    """Return cells whose support chain reaches a cycle, for explicit catalog exclusions."""
    invalid = set()
    for first in cells:
        seen, key = set(), first
        while key in cells and cells[key].get("on"):
            if key in seen or key in invalid:
                invalid.update(seen)
                break
            seen.add(key)
            key = cells[key]["on"]
    return sorted(invalid)


def stack_layouts(cells: dict) -> list[list[list[str]]]:
    """Include complete longest stacks, following table 'on' links, and reject cycles."""
    referenced = {entry["on"] for entry in cells.values() if entry.get("on") in cells}
    result = []
    for top, entry in sorted(cells.items()):
        if not entry.get("on") or top in referenced:
            continue
        chain, seen, key = [], set(), top
        while key in cells:
            if key in seen:
                raise ValueError(f"cyclic fitted stack at {key}")
            seen.add(key)
            chain.append([key])
            key = cells[key].get("on")
        if len(chain) > 1:
            result.append(chain)
    # A cycle has no top, so validate every remaining link too.
    for first in cells:
        seen, key = set(), first
        while key in cells and cells[key].get("on"):
            if key in seen:
                raise ValueError(f"cyclic fitted stack at {key}")
            seen.add(key)
            key = cells[key]["on"]
    return result


def catalog(table: dict, entities: list[dict], art: BlueArt, subset="all") -> list[dict]:
    entities=copy.deepcopy(entities)
    for entity in entities:
        if 'current_native_fragments' in entity:
            if entity.get('_baseline_comparison'):
                raise ValueError('current native assembly cannot override baseline evidence')
            entity['_current_native_fragments']=load_native_assembly_proof(entity['current_native_fragments'],None,current_table=table)
    if subset == "buildings":
        entries=building_catalog(table, art);existing={e['id']:e for e in entries}
        definitions=[e for e in entities if e.get('building_baseline') is True]
        before=[e for e in definitions if '_baseline_native_fragments' in e]
        for entity in definitions:
            if entity['id'] in existing:
                if entity in before:raise ValueError('native building baseline must be absent from the original template table: '+entity['id'])
                if 'baseline_native_fragments' in entity:
                    descriptor=entity['baseline_native_fragments'];entry=existing[entity['id']]
                    if entry['layout']!=descriptor['source_layout']:
                        raise ValueError('native building companion differs from current source layout: '+entity['id'])
                    place=descriptor['occurrence']
                    evidence=load_native_assembly_proof(descriptor,descriptor['baseline_sha256'])
                    if 'built_source' in evidence['record']:
                        source,raw,grid=native_assembly_source(evidence,art)
                        if [grid[place['y']+dy][place['x']:place['x']+len(entry['layout'][0])] for dy in range(len(entry['layout']))]!=entry['layout']:
                            raise ValueError('native building companion normalized source occurrence changed')
                        entry['occurrences'].append(dict(place,palette='BAKED',source_state=evidence['record']['built_source']['mode']))
                    if not any(all(p.get(f)==place[f] for f in ('map','x','y')) for p in entry['occurrences']):
                        raise ValueError('native building companion current source occurrence absent: '+entity['id'])
                    entry['occurrences'].sort(key=lambda p:not all(p.get(f)==place[f] for f in ('map','x','y')))
                    entry['baseline_native_fragments']=copy.deepcopy(descriptor)
        if before:
            entries.extend(catalog(table,before,art,'native-source-only'))
        return entries
    game = table.get("game", "blue")
    cells = {k: v for k, v in table["cells"].items() if v.get("class") == "voxels"}
    atlases = sorted({split_key(k)[0] for k in cells})
    native={};native_contexts={}
    for entity in entities:
        if '_baseline_native_fragments' in entity or '_current_native_fragments' in entity:
            context=entity.get('_baseline_native_fragments',entity.get('_current_native_fragments'))
            if entity['id'] in native_contexts and native_contexts[entity['id']]!=context:
                raise ValueError('conflicting native assembly exhibit context: '+entity['id'])
            native_contexts[entity['id']]=context
        for key,proof in entity.get('_baseline_native_cells',{}).items():
            if not entity.get('_baseline_comparison') or key in table['cells']:
                raise ValueError('native baseline must be absent from fitted table: '+key)
            if split_key(key)[0] not in atlases:
                raise ValueError('native baseline atlas is not present: '+key)
            if key in native and native[key]!=proof:
                raise ValueError('conflicting native baseline contexts: '+key)
            fragments_for_native_cell(key,proof,atlases.index(split_key(key)[0]),'BAKED')
            native[key]=proof
    entries, signatures = [], {}

    def add(layout, kind, label, placement_mode="floor", explicit_id=None, reference_layout=None, replaces_stack=False, placements=None, preserve_identity=False, native_assembly=False):
        keys = [k for row in layout for k in row if k]
        if not keys or (not native_assembly and any(k not in cells and k not in native for k in keys)):
            return
        if subset == "pallet-trees" and not all(k in PALLET_TREE_KEYS for k in keys):
            return
        if subset == "littleroot-trees" and not (explicit_id or "").startswith("emerald_littleroot_tree_"):
            return
        if replaces_stack:
            previous = next((e for e in entries if e["id"] == explicit_id), None)
            if previous is not None:
                if previous["layout"] != layout or previous["placement_mode"] != "stack":
                    raise ValueError(f"corrected assembly collides with another exhibit: {explicit_id}")
                # A baseline table still has the erroneous 'on' link. Keep its
                # real vertical placement under the same comparison identity;
                # the revised table will create this explicit floor assembly.
                previous["label"] = label
                return previous
        custom = None
        if placements is not None:
            expected = {(x,z) for z,row in enumerate(layout) for x,key in enumerate(row) if key}
            custom = {}
            for item in placements:
                source,offset = item.get('source',[]),item.get('offset',[])
                if (len(source)!=2 or len(offset)!=3 or any(type(n) is not int for n in [*source,*offset])
                    or tuple(source) not in expected or tuple(source) in custom
                    or any(n<0 or n>64 for n in offset)):
                    raise ValueError('invalid explicit compound placement: '+str(explicit_id))
                custom[tuple(source)] = tuple(offset)
            if set(custom)!=expected or len(set(custom.values()))!=len(custom):
                raise ValueError('incomplete or overlapping compound placements: '+str(explicit_id))
            placement_mode='custom'
        signature_parts=[layout,placement_mode]
        if custom is not None:signature_parts.append(sorted(custom.items()))
        signature = json.dumps(signature_parts, separators=(",", ":"))
        if signature in signatures and not (preserve_identity and explicit_id!=signatures[signature]['id']):
            return signatures[signature]
        locations = art.locations.get(keys[-1], [])
        palette = locations[0]["palette"] if locations else ("BAKED" if game != "blue" else "GRAYMON")
        identifier = explicit_id or game + "_" + safe_id(keys[0])
        if kind != "cell" and explicit_id is None:
            identifier = game + "_compound_" + hashlib.sha256(signature.encode()).hexdigest()[:12]
        fragments = []
        width, height = len(layout[0]), len(layout)
        max_height,max_width,max_depth = 0,0,0
        for row, values in enumerate(layout):
            for col, key in enumerate(values):
                if not key:
                    continue
                if native_assembly:
                    continue  # The validated source-map fragments below replace this whole baseline exhibit.
                atlas, _ = split_key(key)
                if custom is None:
                    ox=col*2
                    oy = (height - row - 1) * 2 if placement_mode == "stack" else 0
                    oz = 0 if placement_mode == "stack" else row * 2
                else:ox,oy,oz=(n*2 for n in custom[(col,row)])
                if key in native:
                    parts,h=fragments_for_native_cell(key,native[key],atlases.index(atlas),palette,(ox,oy,oz))
                    fragments.extend(parts);max_height=max(max_height,oy+h)
                else:
                    fragments.extend(fragments_for_cell(key, cells[key], atlases.index(atlas), palette, (ox, oy, oz)))
                    max_height = max(max_height, oy + 2 * (1 + len(cells[key].get("up", []))))
                max_width,max_depth=max(max_width,ox+2),max(max_depth,oz+2)
        entries.append({"id": identifier, "label": label, "kind": kind, "layout": layout,
                        "placement_mode": placement_mode, "palette": palette,
                        "source_keys": keys, "occurrences": locations,
                        "model_hashes": {key: (hashlib.sha256(json.dumps(native[key],sort_keys=True).encode()).hexdigest()
                                               if key in native else model_hash(cells[key])[1] if key in cells else 'absent-fitted-entry') for key in keys},
                        "sprite": f"sprites/{identifier}.png", "fragments": fragments,
                        "bounds": [max_width, max_height, max_depth]})
        signatures[signature]=entries[-1]
        if placements is not None:entries[-1]['placements']=placements
        if reference_layout is not None:
            # A GBA sprite may project a tree tip onto walkable grass north of
            # its physical footprint. Show that source art without inventing
            # a separate floating piece of model geometry for the grass cell.
            entries[-1]["reference_layout"] = reference_layout
        empty = [key for key in keys if key in cells and not any(any(base64.b64decode(layer["v"]))
                                               for layer in [cells[key], *cells[key].get("up", [])])]
        if empty:
            entries[-1]["empty_model"] = empty
            entries[-1]["render_note"] = "Empty fitted occupancy: displayed through the runtime fallback; review separately."
        if any(k in native for k in keys):
            entries[-1]['native_baseline_cells']={k:native[k] for k in keys if k in native}
            entries[-1]['render_note']='Missing fitted baseline cells shown using exact source-map planner cube columns.'
        return entries[-1]

    if subset!='native-source-only':
        for key in sorted(set(cells)|set(native)):
            add([[key]], "cell", key)
        invalid_stacks = set(cyclic_stack_keys(cells))
        for layout in stack_layouts({k: v for k, v in cells.items() if k not in invalid_stacks}):
            add(layout, "compound", "Stack: " + " + ".join(row[0] for row in layout), "stack")
    for entity in sorted(entities, key=lambda e: e["id"]):
        if entity.get('building_baseline') is True and subset!='native-source-only':continue
        layout = entity.get("layout", [])
        if (not layout or not layout[0] or (len(layout) * len(layout[0]) <= 1
                and not any(k in entity for k in ('_baseline_native_fragments','_current_native_fragments')))):
            continue
        mode = "stack" if entity.get("kind", "").startswith("stack") else "floor"
        entry=add(layout, "compound", entity.get("note") or entity["id"], mode, entity["id"], entity.get("reference_layout"),
            entity.get("replaces_stack", False), entity.get('placements'), entity.get('_baseline_comparison',False) or '_current_native_fragments' in entity,'_baseline_native_fragments' in entity or '_current_native_fragments' in entity)
        if entry is not None:
            if entry['id']!=entity['id']:
                entry.setdefault('aliases',[]).append(entity['id'])
            for field in ('reference_layout','baseline_layout','baseline_placements','baseline_native_cells','baseline_native_fragments','current_native_fragments'):
                if field in entity:entry[field]=entity[field]
            if '_baseline_native_fragments' in entity:
                fragments,bounds,evidence=native_assembly_fragments(entity,table,art,atlases)
                entry.update(fragments=fragments,bounds=bounds,placement_mode='source-map-baseline',native_baseline_evidence=evidence,
                             source_occurrence=dict(evidence['descriptor']['occurrence'],palette='BAKED'),
                             render_note='Exact original source-map planner columns; fitted catalog entries were not necessarily active here.')
                entry['model_hashes']['native-baseline:'+entity['id']]=hashlib.sha256(json.dumps(fragments,sort_keys=True,separators=(',',':')).encode()).hexdigest()
            elif '_current_native_fragments' in entity:
                fragments,bounds,evidence=native_assembly_fragments(entity,table,art,atlases)
                entry.update(fragments=fragments,bounds=bounds,placement_mode='source-map-current',native_current_evidence=evidence,
                             source_occurrence=dict(evidence['descriptor']['occurrence'],palette='BAKED'),
                             render_note='Exact current source-map planner columns; source metadata and fitted geometry are proof-bound.')
                if evidence.get('templates'):entry['templates']=evidence['templates']
                if 'occurrences' in entity:entry['occurrences']=copy.deepcopy(entity['occurrences'])
                entry['model_hashes']['native-current:'+entity['id']]=hashlib.sha256(json.dumps(fragments,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    return entries


def select_entries(entries: list[dict], selectors: list[str]) -> list[dict]:
    """Exact, repeatable selection; cell keys select individual exhibits only."""
    if not selectors:
        return entries
    found, selected = set(), []
    wanted = set(selectors)
    for entry in entries:
        names = {entry["id"], entry["label"]}
        names.update(entry.get('aliases',[]))
        if entry["kind"] == "cell":
            names.update(entry["source_keys"])
        matched = wanted & names
        if matched:
            found.update(matched)
            selected.append(entry)
    unknown = wanted - found
    if unknown:
        raise ValueError("unknown model selector(s) in this subset: " + ", ".join(sorted(unknown))
                         + "; use an exact catalog id, label, or individual cell key")
    return selected


def apply_map_context(entries: list[dict], art: BlueArt, map_id: str) -> None:
    """Use the requested occurrence's palette for both sprite and every model fragment."""
    if map_id not in art.maps:
        raise ValueError(f"unknown source map: {map_id}")
    for entry in entries:
        if entry.get('placement_mode') in ('source-map-baseline','source-map-current'):
            if entry['source_occurrence']['map']!=map_id:
                raise ValueError('native baseline belongs to another source occurrence: '+entry['id'])
            continue
        if entry.get("kind") == "building":
            occurrence = next((p for p in entry["occurrences"] if p["map"] == map_id), None)
            if occurrence is None:
                raise ValueError(f"complete building {entry['id']} does not occur in source map {map_id}")
            entry["source_occurrence"] = dict(occurrence)
            entry["palette"] = occurrence["palette"]
            for fragment in entry["fragments"]:
                fragment["palette"] = occurrence["palette"]
            continue
        for key in entry["source_keys"]:
            if not any(p["map"] == map_id for p in art.locations.get(key, [])):
                raise ValueError(f"model {entry['id']} does not occur in source map {map_id}")
        occurrence = next(p for p in art.locations[entry["source_keys"][-1]] if p["map"] == map_id)
        entry["source_occurrence"] = dict(occurrence)
        entry["palette"] = occurrence["palette"]
        for fragment in entry["fragments"]:
            fragment["palette"] = occurrence["palette"]


def capture_views(manifest: dict) -> list[str]:
    views = manifest.get("views", ["front", "side"])
    if views not in (["front", "side"], ["front", "side", "three_quarter"], ["front", "side", "north"], ["front", "side", "three_quarter", "north"]):
        raise ValueError("views must be front, side, optional three_quarter, then optional north")
    return views


def baseline_entities(entities, table_sha256=None):
    """Use evidenced old placements without moving the source reference drawing."""
    result = []
    for original in entities:
        entity = dict(original)
        entity.pop('current_native_fragments',None)
        entity.pop('_current_native_fragments',None)
        if any(k in entity for k in ('baseline_layout','baseline_placements','baseline_native_cells','baseline_native_fragments')):
            entity['_baseline_comparison']=True
        if 'baseline_native_fragments' in entity:
            entity['_baseline_native_fragments']=load_native_assembly_proof(entity.pop('baseline_native_fragments'),table_sha256)
        if 'baseline_native_cells' in entity:
            native=entity.pop('baseline_native_cells')
            if table_sha256 is not None and any(p.get('baseline_sha256')!=table_sha256 for p in native.values()):
                raise ValueError('native baseline evidence belongs to another table: '+entity['id'])
            entity['_baseline_native_cells']=native
        if 'baseline_layout' in entity:
            old_layout = entity.pop('baseline_layout')
            layout = entity['layout']
            if len(old_layout) != len(layout) or any(len(a) != len(b) for a, b in zip(old_layout, layout)):
                raise ValueError('baseline layout dimensions changed: '+entity['id'])
            if any(old not in (None, current) for old_row, row in zip(old_layout, layout)
                   for old, current in zip(old_row, row)):
                raise ValueError('baseline layout changed source identity: '+entity['id'])
            entity.setdefault('reference_layout', layout)
            entity['layout'] = old_layout
        if 'baseline_placements' in entity:
            entity['placements'] = entity.pop('baseline_placements')
        result.append(entity)
    return result


def prepare(args) -> dict:
    output = args.out.resolve()
    if not output.is_relative_to((ROOT / "local").resolve()):
        raise ValueError("generated sprite assets must stay inside this repository's local/ directory")
    # Preserve exactly the revision we parse, even if an editor saves the source
    # again while references are being generated.
    table_bytes = args.table.read_bytes()
    table = json.loads(table_bytes.decode("utf-8-sig"))
    game = getattr(args, "game", "blue")
    if game not in ("blue", "emerald", "crystal") or table.get("game") != game:
        raise ValueError(f"gallery requires a fitted table for the selected game: {game}")
    if args.subset == "pallet-trees" and game != "blue":
        raise ValueError("pallet-trees is a Blue subset; use --subset all with exact --only selectors for Emerald")
    if args.subset == "littleroot-trees" and game != "emerald":
        raise ValueError("littleroot-trees requires --game emerald")
    art = {"blue": BlueArt, "emerald": EmeraldArt, "crystal": CrystalArt}[game](args.bundle.resolve())
    entities_bytes=args.entities.read_bytes() if args.entities.is_file() else b'[]\n'
    entities=json.loads(entities_bytes.decode('utf-8-sig'))
    selectors = getattr(args, "only", [])
    entries = select_entries(catalog(table, entities, art, args.subset), selectors)
    if not entries:
        raise ValueError("no models match the selection")
    baseline_table = getattr(args, "baseline_table", None)
    if baseline_table is not None:
        baseline_bytes = baseline_table.read_bytes()
        baseline = json.loads(baseline_bytes.decode("utf-8-sig"))
        if baseline.get("game") != game:
            raise ValueError(f"baseline table must belong to {game}")
        before_entries = catalog(baseline, baseline_entities(entities, hashlib.sha256(baseline_bytes).hexdigest()), art, args.subset)
        baseline_by_id = {identifier:e for e in before_entries for identifier in [e['id'],*e.get('aliases',[])]}
        comparison_entries = []
        for entry in entries:
            entry["comparison_id"] = entry["id"]
            entry["revision"] = "current"
            previous_id=next((identifier for identifier in [entry['id'],*entry.get('aliases',[])] if identifier in baseline_by_id),None)
            if previous_id is not None:
                old = json.loads(json.dumps(baseline_by_id[previous_id]))
                if 'native_baseline_evidence' in old:
                    entry['native_baseline_evidence']=copy.deepcopy(old['native_baseline_evidence'])
                old["comparison_id"] = entry["id"]
                old["revision"] = "before"
                old["id"] = entry['id'] + "_before"
                old["sprite"] = f"sprites/{old['id']}.png"
                old["label"] += " (before)"
                # Atlas order can change when a new cell atlas is introduced.
                old_atlases = table_atlases(baseline, args.subset == "buildings")
                new_atlases = table_atlases(table, args.subset == "buildings")
                remapped_sigs = {}
                for template in old.get("templates", []):
                    previous_sig = template["sig"]
                    template["atlas"] = new_atlases.index(old_atlases[template["atlas"]])
                    template["sig"] = template_signature(template)
                    remapped_sigs[previous_sig] = template["sig"]
                for fragment in old["fragments"]:
                    prefix, suffix = fragment["key"].split("|", 1)
                    atlas = old_atlases[int(prefix.split(":")[1])]
                    kind = prefix.split(":")[0]
                    if kind == "tmpl":
                        old_sig, coordinates = suffix.split("|", 1)
                        suffix = remapped_sigs[old_sig] + "|" + coordinates
                    fragment["key"] = f"{kind}:{new_atlases.index(atlas)}|{suffix}"
                if remapped_sigs:
                    old["model_hashes"] = {key: remapped_sigs.get(value, value) for key, value in old["model_hashes"].items()}
                comparison_entries.append(old)
            comparison_entries.append(entry)
        entries = comparison_entries
    source_map = getattr(args, "source_map", None)
    if source_map:
        apply_map_context(entries, art, source_map)
    output.mkdir(parents=True, exist_ok=True)
    (output / "sprites").mkdir(exist_ok=True)
    for entry in entries:
        drawing = art.drawing(entry.get("reference_layout", entry["layout"]), entry["palette"])
        drawing.save(output / entry["sprite"])
    if "PALLET_TOWN" in art.maps:
        art.map_image("PALLET_TOWN").save(output / "pallet-map.png")
    if source_map:
        art.map_image(source_map).save(output / "source-map.png")
    (output / "fitted-current.json").write_bytes(table_bytes)
    (output / "entities-current.json").write_bytes(entities_bytes)
    if baseline_table is not None:
        (output / "fitted-baseline.json").write_bytes(baseline_bytes)
    all_cells = {k: v for k, v in table["cells"].items() if v.get("class") == "voxels"}
    templates = {template["sig"]: template for entry in entries for template in entry.get("templates", [])}
    manifest = {
        "schema": 1, "game": game, "world": args.world,
        "bundle": str(args.bundle.resolve()), "output": str(output), "planner": "kb-shapes/12",
        "atlases": table_atlases(table, args.subset == "buildings"),
        "palettes": sorted({e["palette"] for e in entries}), "templates": [templates[k] for k in sorted(templates)],
        "source": {"table": str(args.table.resolve()), "table_sha256": hashlib.sha256(table_bytes).hexdigest(),
                   "table_snapshot": "fitted-current.json",
                   "entities": str(args.entities.resolve()), "entities_sha256":hashlib.sha256(entities_bytes).hexdigest(),
                   "entities_snapshot":"entities-current.json", "sprite_method": "exported tilesheet + tileset block layout",
                   "subset": args.subset, "only": selectors},
        "excluded": {"building_templates": len(table.get("buildings", [])) - sum(e["kind"] == "building" and e.get("revision", "current") == "current" for e in entries),
                     "non_voxel_cells": len(table["cells"]) - len(all_cells),
                     "cyclic_stack_cells": cyclic_stack_keys(all_cells)},
        "entries": entries,
        "views": ["front", "side"] + (["three_quarter"] if getattr(args, "angled", False) else []) + (["north"] if getattr(args, "north", False) else []),
    }
    if source_map:
        manifest["source"]["map"] = source_map
    if baseline_table is not None:
        manifest["fitted_tables"] = ["fitted-baseline.json"]
        manifest["source"]["baseline_table"] = str(baseline_table.resolve())
        manifest["source"]["baseline_table_sha256"] = hashlib.sha256(baseline_bytes).hexdigest()
        manifest["source"]["baseline_table_snapshot"] = "fitted-baseline.json"
    write_json(output / "manifest.json", manifest)
    print(f"Prepared {len(entries)} models ({sum(e['kind'] == 'compound' for e in entries)} compound): {output / 'manifest.json'}")
    return manifest


def font(size: int):
    for path in (Path("C:/Windows/Fonts/segoeui.ttf"), Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")):
        if path.is_file():
            return ImageFont.truetype(str(path), size)
    return ImageFont.load_default(size=size)


def panel(image: Image.Image, size: tuple[int, int], nearest=False) -> Image.Image:
    """Contain without stretching/cropping; retain every screenshot pixel."""
    result = Image.new("RGB", size, "#e9edf0")
    image = image.convert("RGBA")
    scale = min((size[0] - 32) / image.width, (size[1] - 32) / image.height)
    if nearest and scale >= 1:
        scale = max(1, math.floor(scale))
    resized = image.resize((max(1, round(image.width * scale)), max(1, round(image.height * scale))),
                           Image.Resampling.NEAREST if nearest else Image.Resampling.LANCZOS)
    result.paste(resized, ((size[0] - resized.width) // 2, (size[1] - resized.height) // 2), resized)
    return result


def capture_coverage(entries: list[dict], captures: list[dict], views=("front", "side")) -> dict:
    expected = {(e["id"], direction) for e in entries for direction in views}
    actual_list = [(c.get("id"), c.get("direction")) for c in captures]
    actual = set(actual_list)
    # Strings keep malformed unexpected records sortable and the report readable.
    label = lambda key: f"{key[0]}/{key[1]}"
    return {"expected": len(expected), "recorded": len(actual_list),
            "missing": sorted(map(label, expected - actual)),
            "unexpected": sorted(map(label, actual - expected)),
            "duplicates": len(actual_list) - len(actual),
            "exact": expected == actual and len(actual_list) == len(actual)}


def image_record(path: Path, label: str) -> dict:
    """Read every pixel before reporting a usable original, and fingerprint the file."""
    with Image.open(path) as image:
        image.load()
        width, height = image.size
    return {"role": label, "file": str(path), "width": width, "height": height,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def contact_sheets(root: Path, entries: list[dict], records: list[dict], game="blue") -> list[dict]:
    """Twelve subjects per page, each with its source, current front and side."""
    destination = root / "contact-sheets"
    destination.mkdir(exist_ok=True)
    complete = {r["id"]: r["complete"] for r in records}
    pages = []
    for page_number, start in enumerate(range(0, len(entries), 12), 1):
        group = entries[start:start + 12]
        rows = math.ceil(len(group) / 3)
        sheet = Image.new("RGB", (1248, 64 + rows * 216), "#f7f8fa")
        draw = ImageDraw.Draw(sheet)
        draw.text((24, 16), f"{game.upper()} MODEL REVIEW / CURRENT / PAGE {page_number}", font=font(22), fill="#245869")
        for index, entry in enumerate(group):
            x, y = 24 + index % 3 * 400, 64 + index // 3 * 216
            draw.rectangle((x, y, x + 388, y + 204), fill="white", outline="#d4dde2")
            identifier = entry["id"]
            title = "\n".join(textwrap.wrap(identifier, width=51)[:2])
            draw.multiline_text((x + 8, y + 6), title, font=font(12), fill="#152a33", spacing=2)
            paths = [("Sprite", root / entry["sprite"], True),
                     ("Front", root / "screenshots" / f"{identifier}_front.png", False),
                     ("Side", root / "screenshots" / f"{identifier}_side.png", False)]
            for col, (label, path, nearest) in enumerate(paths):
                px = x + 6 + col * 126
                draw.text((px + 3, y + 43), label, font=font(12), fill="#506675")
                try:
                    with Image.open(path) as picture:
                        sheet.paste(panel(picture, (122, 120), nearest), (px, y + 62))
                except (OSError, ValueError):
                    draw.rectangle((px, y + 62, px + 122, y + 182), fill="#f8dfd9")
                    draw.text((px + 7, y + 112), "Missing / invalid", font=font(11), fill="#8b3429")
            note = "Fallback model" if entry.get("empty_model") else ""
            if not complete.get(identifier):
                note = "Incomplete capture" + (" / fallback" if note else "")
            if note:
                draw.text((x + 8, y + 187), note, font=font(11), fill="#8b3429")
        path = destination / f"page-{page_number:03d}.png"
        sheet.save(path)
        pages.append({"page": page_number, "file": path.relative_to(root).as_posix(),
                      "ids": [e["id"] for e in group], "width": sheet.width, "height": sheet.height})
    return pages


def detail_sheet(target: Path, title: str, paths: list[tuple], game="blue") -> tuple[list, list, list]:
    """A larger supplementary comparison reveals top controls and separated parts."""
    width, height, margin = 600, 480, 20
    sheet = Image.new("RGB", (margin * 2 + width * len(paths), 620), "#f7f8fa")
    draw = ImageDraw.Draw(sheet)
    draw.text((margin, 16), f"POKEMON {game.upper()} / ANGLED MODEL REVIEW", font=font(22), fill="#245869")
    draw.text((margin, 50), title[:100], font=font(22), fill="#152a33")
    missing, invalid, originals = [], [], []
    for number, (label, path, nearest) in enumerate(paths):
        x = margin + width * number
        draw.text((x + 10, 92), label, font=font(20), fill="#314c59")
        try:
            originals.append(image_record(path, label))
            with Image.open(path) as picture:
                sheet.paste(panel(picture, (width - 12, height), nearest), (x, 125))
        except FileNotFoundError:
            missing.append(str(path))
            draw.text((x + 20, 330), "Capture missing", font=font(24), fill="#8b3429")
        except (OSError, ValueError) as error:
            invalid.append({"file": str(path), "error": str(error)})
            draw.text((x + 20, 330), "Image unreadable", font=font(24), fill="#8b3429")
    sheet.save(target)
    return missing, invalid, originals


def compose(manifest_path: Path, before: Path | None = None) -> dict:
    manifest_path = manifest_path.resolve()
    manifest = read_json(manifest_path)
    game = manifest.get("game", "blue")
    views = capture_views(manifest)
    root = manifest_path.parent
    output = root / "sheets"
    output.mkdir(exist_ok=True)
    records = []
    proof_path = root / "capture.json"
    proof = read_json(proof_path) if proof_path.is_file() else {}
    expected_hash = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
    provenance_errors = []
    if proof.get("manifest_sha256") != expected_hash:
        provenance_errors.append("capture.json does not identify this manifest; rerun Minecraft capture")
    if proof.get("status") != "complete":
        provenance_errors.append("Minecraft capture has not completed")
    coverage = capture_coverage(manifest["entries"], proof.get("captures", []), views)
    if not coverage["exact"]:
        provenance_errors.append("capture.json does not contain exactly one record for every requested model view")
    captured = {(r.get("id"), r.get("direction")) for r in proof.get("captures", [])}
    external_errors, external_captured = [], set()
    if before is not None:
        before = before.resolve()
        old_manifest, old_capture = before / "manifest.json", before / "capture.json"
        old_proof = read_json(old_capture) if old_capture.is_file() else {}
        if not old_manifest.is_file() or old_proof.get("manifest_sha256") != hashlib.sha256(old_manifest.read_bytes()).hexdigest():
            external_errors.append("baseline capture.json does not identify its manifest")
        if old_proof.get("status") != "complete":
            external_errors.append("baseline Minecraft capture has not completed")
        external_captured = {(r.get("id"), r.get("direction")) for r in old_proof.get("captures", [])}
    before_entries = {e.get("comparison_id"): e for e in manifest["entries"] if e.get("revision") == "before"}
    current_entries = [e for e in manifest["entries"] if e.get("revision") != "before"]
    for entry in current_entries:
        identifier = entry["id"]
        paths = [("Source sprite", root / entry["sprite"], True)]
        baseline = before_entries.get(entry.get("comparison_id"))
        if baseline is not None:
            paths += [("Before / front", root / "screenshots" / f"{baseline['id']}_front.png", False),
                      ("Before / side", root / "screenshots" / f"{baseline['id']}_side.png", False)]
        elif before is not None:
            paths += [("Before / front", before / "screenshots" / f"{identifier}_front.png", False),
                      ("Before / side", before / "screenshots" / f"{identifier}_side.png", False)]
        paths += [("Minecraft / front", root / "screenshots" / f"{identifier}_front.png", False),
                  ("Minecraft / side", root / "screenshots" / f"{identifier}_side.png", False)]
        cell_width, cell_height, margin = 480, 380, 20
        sheet = Image.new("RGB", (margin * 2 + cell_width * len(paths), 520), "#f7f8fa")
        draw = ImageDraw.Draw(sheet)
        draw.text((margin, 16), f"POKEMON {game.upper()} / MODEL REVIEW", font=font(19), fill="#245869")
        title = entry["label"]
        # Long compound identities stay in manifest/HTML; image title must fit.
        if len(title) > 100:
            title = title[:97] + "..."
        draw.text((margin, 47), title, font=font(22), fill="#152a33")
        if entry.get("render_note"):
            draw.text((margin, 73), entry["render_note"], font=font(12), fill="#8b3429")
        missing, invalid, originals = [], [], []
        for index, (label, path, nearest) in enumerate(paths):
            x = margin + index * cell_width
            draw.text((x + 10, 89), label, font=font(18), fill="#314c59")
            if path.is_file():
                try:
                    originals.append(image_record(path, label))
                    with Image.open(path) as picture:
                        sheet.paste(panel(picture, (cell_width - 12, cell_height), nearest), (x, 119))
                except (OSError, ValueError) as error:
                    invalid.append({"file": str(path), "error": str(error)})
                    draw.rectangle((x, 119, x + cell_width - 12, 119 + cell_height), fill="#f8dfd9")
                    draw.text((x + 22, 275), "Image unreadable", font=font(23), fill="#8b3429")
            else:
                missing.append(str(path))
                draw.rectangle((x, 119, x + cell_width - 12, 119 + cell_height), fill="#f8dfd9")
                draw.text((x + 22, 275), "Capture missing", font=font(23), fill="#8b3429")
        target = output / f"{identifier}.png"
        sheet.save(target)
        detail_target = None
        if "three_quarter" in views:
            detail_paths = [("Source sprite", root / entry["sprite"], True)]
            if baseline is not None:
                detail_paths.append(("Before / angled", root / "screenshots" / f"{baseline['id']}_three_quarter.png", False))
            elif before is not None:
                detail_paths.append(("Before / angled", before / "screenshots" / f"{identifier}_three_quarter.png", False))
            detail_paths.append(("Minecraft / angled", root / "screenshots" / f"{identifier}_three_quarter.png", False))
            detail_target = output / f"{identifier}_detail.png"
            detail_missing, detail_invalid, detail_originals = detail_sheet(detail_target, entry["label"], detail_paths, game)
            missing.extend(detail_missing)
            invalid.extend(detail_invalid)
            originals.extend(detail_originals)
        north_target = None
        if "north" in views:
            north_paths = [("Source sprite", root / entry["sprite"], True)]
            if baseline is not None:
                north_paths.append(("Before / from north", root / "screenshots" / f"{baseline['id']}_north.png", False))
            elif before is not None:
                north_paths.append(("Before / from north", before / "screenshots" / f"{identifier}_north.png", False))
            north_paths.append(("Minecraft / from north", root / "screenshots" / f"{identifier}_north.png", False))
            north_target = output / f"{identifier}_north.png"
            more_missing, more_invalid, more_originals = detail_sheet(north_target, entry["label"], north_paths, game)
            missing.extend(more_missing); invalid.extend(more_invalid); originals.extend(more_originals)
        errors = list(provenance_errors)
        required_ids = [identifier] + ([baseline["id"]] if baseline is not None else [])
        for shot_id in required_ids:
            for direction in views:
                if (shot_id, direction) not in captured:
                    errors.append(f"capture.json lacks {shot_id}/{direction}")
        if baseline is None and before is not None:
            errors.extend(external_errors)
            for direction in views:
                if (identifier, direction) not in external_captured:
                    errors.append(f"baseline capture.json lacks {identifier}/{direction}")
        records.append({"id": identifier, "sheet": str(target), "complete": not missing and not errors and not invalid,
                        "detail_sheet": str(detail_target) if detail_target else None,
                        "north_sheet": str(north_target) if north_target else None,
                        "missing": missing, "invalid_files": invalid, "provenance_errors": errors,
                        "originals": originals})
    contacts = contact_sheets(root, current_entries, records, game)
    report = {"schema": 1, "manifest": str(manifest_path), "models": len(records),
              "manifest_sha256": expected_hash, "capture_status": proof.get("status", "missing"),
              "capture_coverage": coverage, "contact_sheets": contacts,
              "complete": sum(r["complete"] for r in records), "entries": records}
    write_json(root / "sheet-report.json", report)
    detail_links = {r["id"]: (f'<a href="sheets/{r["id"]}_detail.png"><img loading="lazy" src="sheets/{r["id"]}_detail.png" alt="Angled comparison"></a>'
                              if r["detail_sheet"] else "") for r in records}
    for r in records:
        if r['north_sheet']: detail_links[r['id']] += f'<a href="sheets/{r["id"]}_north.png"><img loading="lazy" src="sheets/{r["id"]}_north.png" alt="Comparison viewed from north, looking south"></a>'
    cards = "\n".join(f'<article><h2>{html.escape(e["label"])}</h2><p>{html.escape(e["id"])}</p>'
                      f'<p>{html.escape(e.get("render_note", ""))}</p>'
                      f'<a href="sheets/{e["id"]}.png"><img loading="lazy" src="sheets/{e["id"]}.png"></a>{detail_links[e["id"]]}</article>'
                      for e in current_entries)
    contact_links = " ".join(f'<a href="{p["file"]}">Page {p["page"]}</a>' for p in contacts)
    (root / "index.html").write_text(
        f'<!doctype html><meta charset="utf-8"><title>{html.escape(game.title())} model gallery</title>'
        '<style>body{font:16px system-ui;margin:36px;background:#f7f8fa;color:#152a33}'
        'article{margin:30px 0;padding:20px;background:white;border:1px solid #d9e1e5;border-radius:8px}'
        'img{width:100%;height:auto}h2{font-size:18px}p{color:#506675}nav{display:flex;gap:12px;flex-wrap:wrap}</style>'
        f'<h1>Pokemon {html.escape(game.title())} model gallery</h1><p>{report["complete"]}/{report["models"]} complete comparisons. '
        'Front = looking north; side = looking west; optional north = looking south. Sprite pixels come from the exported tile sheets.</p>'
        + (('<p style="color:#8b3429">' + html.escape("; ".join(provenance_errors)) + '</p>') if provenance_errors else '')
        + f'<h2>Contact sheets</h2><p>12 current models per page. Open individual sheets below for full comparisons.</p><nav>{contact_links}</nav>'
        + cards,
        encoding="utf-8")
    print(f"Sheets: {report['complete']}/{report['models']} complete; {root / 'index.html'}")
    return report


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare", help="catalog fitted cells + compound objects and reconstruct their sprites")
    prep.add_argument("--game", choices=("blue", "emerald", "crystal"), default="blue")
    prep.add_argument("--bundle", type=Path)
    prep.add_argument("--table", type=Path)
    prep.add_argument("--entities", type=Path)
    prep.add_argument("--out", type=Path)
    prep.add_argument("--subset", choices=("all", "pallet-trees", "littleroot-trees", "buildings"), default="all")
    prep.add_argument("--only", action="append", default=[], metavar="ID_OR_CELL",
                      help="exact catalog id, label or individual cell key; repeat to select several")
    prep.add_argument("--world")
    prep.add_argument("--map", dest="source_map", help="source map occurrence supplying the sprite/model palette")
    prep.add_argument("--angled", action="store_true", help="also capture a 30-degree elevated three-quarter view")
    prep.add_argument("--north", action="store_true", help="also capture a separately named view from north looking south")
    prep.add_argument("--baseline-table", type=Path, help="render old and current fitted models together for before/after review")
    sheets = commands.add_parser("sheets", help="compose real screenshot comparisons; missing views give exit 1")
    sheets.add_argument("--manifest", type=Path, default=DEFAULT_OUT / "manifest.json")
    sheets.add_argument("--before", type=Path, help="optional baseline gallery output folder")
    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            args.bundle = args.bundle or ROOT / "local/minecraft/kantobridge/export" / args.game
            args.table = args.table or ROOT / "fabric-bridge/src/main/resources/kantobridge/fitted_shapes" / f"{args.game}.json"
            args.entities = args.entities or ROOT / "tools/shape_fit/entities" / f"{args.game}.json"
            args.out = args.out or ROOT / "local/model-gallery" / args.game
            args.world = args.world or f"{args.game.title()} Model Gallery"
            prepare(args)
            return 0
        report = compose(args.manifest, args.before)
        return 0 if report["complete"] == report["models"] else 1
    except (OSError, ValueError, KeyError) as error:
        parser.exit(2, f"model gallery: {error}\n")


if __name__ == "__main__":
    raise SystemExit(main())
