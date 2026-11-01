"""Strict consumer for hash-bound Emerald WorldPlan surface evidence.

``consume`` validates a complete source rectangle and returns visible fragments at
their original integer y relative to the map base. It does not move floors, infer
water, or create geometry for invisible collision/story carriers. The caller must
first verify the proof file and table hashes, then translate both revisions by
one common datum. TMPL keys require the caller's exact payload validator.
"""
from __future__ import annotations
import hashlib
import re

MODES = {'worldplan-environment-v1', 'worldplan-route110-v1'}
KINDS = {0:'WALK',7:'SOLID',24:'WALK',41:'WATER',43:'WALK',113:'WALK',
         114:'WALK',130:'SIGN',144:'COUNTER',147:'SOLID',160:'LEDGE',
         161:'LEDGE',162:'LEDGE',163:'LEDGE',255:'SOLID'}
PLAIN = {1:'FULL',2:'HALF',7:'FULL'}
SHAPED = {1,3,7,8,12,14}
INVISIBLE = {3,4,5,6,8,11,12,14,15,16}
COLUMN_FIELDS = {'x','z','feet','source_tile','kind','raw_collision','built_collision',
                 'model_kind','source_elevation','source_tags','before_effective_floor',
                 'after_effective_floor','before','after'}
BLOCK_FIELDS = {'y','code_kind','code_value','visible'}


def integer(value, low, high, label):
    if type(value) is not int or not low <= value <= high:
        raise ValueError('invalid native surface '+label)
    return value


def tile(value, allow_blank=False):
    return integer(value, -1 if allow_blank else 0, 1048575, 'tile')


def layout_witness(record, source, cx, cz):
    layout = record.get('actual_map_layout')
    if layout is None:
        return 0,None,None
    w,h = source['w'],source['h']
    if (not isinstance(layout,dict) or layout.get('w')!=w or layout.get('h')!=h
            or not isinstance(layout.get('levels'),list) or len(layout['levels'])!=w*h
            or not isinstance(layout.get('pieces'),list) or not isinstance(layout.get('pieceOf'),list)
            or len(layout['pieceOf'])!=w*h or not isinstance(layout.get('ramps'),dict)
            or not isinstance(layout.get('decks'),dict)):
        raise ValueError('invalid native surface map layout')
    index = cz*w+cx
    pi = integer(layout['pieceOf'][index],0,len(layout['pieces'])-1,'piece index')
    piece = layout['pieces'][pi]
    if (not isinstance(piece,dict) or set(piece)!={'x','y','w','h','dx','dy','dz'}
            or any(type(v) is not int for v in piece.values())
            or not (piece['x']<=cx<piece['x']+piece['w'] and piece['y']<=cz<piece['y']+piece['h'])
            or any(piece[n]!=0 for n in ('dx','dy','dz'))):
        raise ValueError('unsupported native surface split or shifted piece')
    feet = integer(layout['levels'][index],0,64,'source level')
    ramp,deck = layout['ramps'].get(str(index)),layout['decks'].get(str(index))
    if ramp is not None:
        if (not isinstance(ramp,dict) or set(ramp)!={'x','y','dir','rise'}
                or ramp['x']!=cx or ramp['y']!=cz or ramp['dir'] not in ('UP','DOWN','LEFT','RIGHT')):
            raise ValueError('invalid native surface ramp')
        integer(ramp['rise'],1,8,'ramp rise')
    if deck is not None:
        if (not isinstance(deck,dict) or set(deck)!={'x','y','under','elev'}
                or deck['x']!=cx or deck['y']!=cz):
            raise ValueError('invalid native surface deck')
        integer(deck['under'],0,feet-1,'deck underfloor');integer(deck['elev'],0,15,'deck elevation')
    return feet,ramp,deck


def validate_shape(shape, q, block, layout, table, model_hash, template_validator):
    if not isinstance(shape,str):
        raise ValueError('invalid native surface shape key')
    cube = re.fullmatch(r'cube:0\|(-?\d+(?:,-?\d+){5})(?:\|tint=[1-3])?',shape)
    roof = re.fullmatch(r'roof:0\|(-?\d+(?:,-?\d+){2})\|(\d+),(\d+)\|([01]),([01])',shape)
    slab = re.fullmatch(r'slab:0\|(\d+)\|4',shape)
    cell = re.fullmatch(r'cell:0\|(\d+(?:,\d+){3})\|(-?\d+)\|([01]),(\d+),([01])\|v=(g[0-9a-f]i?r?s?t?(?:_voxels_k\d{12}(?:\d{2})?|_stair_[nesw]_k\d+)?)',shape)
    if cube or roof:
        if block['code_kind']==12:
            raise ValueError('native surface STEP requires its measured stair shape')
        for n in (cube or roof)[1].split(','):tile(int(n),True)
        if roof:
            integer(int(roof[2]),0,16,'roof height');integer(int(roof[3]),0,16,'roof height')
        return shape
    if slab:
        tile(int(slab[1]))
        if q['raw_collision'] not in (161,163) or block['y']!=q['feet'] or block['code_kind']!=3:
            raise ValueError('unsupported native surface ledge slab')
        return shape
    if cell:
        ids = tuple(map(int,cell[1].split(',')));key='g3q/'+'-'.join(map(str,ids))
        for n in ids:tile(n)
        tile(int(cell[2]),True)
        bx,by,bz = map(int,cell.group(3,4,5));variant=cell[6]
        if (bx,bz)!=(q['x']%2,q['z']%2) or not 0<=by<30:
            raise ValueError('native surface CELL quadrant mismatch')
        if '_voxels_' in variant:
            if key not in {k for row in layout for k in row} or key not in table.get('cells',{}):
                raise ValueError('native surface fitted source key missing')
            params=variant.rsplit('_k',1)[1];layer=int(params[12:]) if len(params)>12 else 0
            if params[:12]!=model_hash(table['cells'][key])[0] or layer>len(table['cells'][key].get('up',[])):
                raise ValueError('stale native surface fitted model')
            lift=block['y']-q['feet']-by-layer*2
            if lift<0 or lift%2:
                raise ValueError('native surface fitted vertical quadrant mismatch')
            sx,sz=q['x']//2,q['z']//2
            if lift:
                if key==layout[sz][sx]:
                    prefix={b['y']:b.get('shape_key','') for b in q['rendered_blocks']}
                    if any(not prefix.get(y,'').startswith(('cube:0|','roof:0|')) for y in range(q['feet'],q['feet']+lift)):
                        raise ValueError('native surface fitted lift lacks native support')
                else:
                    steps=lift//2
                    if sz<steps or layout[sz-steps][sx]!=key:
                        raise ValueError('native surface stack lacks exact northern source')
                    for z in range(sz,sz-steps,-1):
                        upper=layout[z-1][sx];lower=layout[z][sx];entry=table['cells'].get(upper,{})
                        carriers=[entry.get('on'),*entry.get('alsoOn',[])]
                        if not any(v in (lower,lower.split('/')[1]) for v in carriers) or table['cells'].get(lower,{}).get('up'):
                            raise ValueError('native surface stack lacks source-bound on relation')
        elif '_stair_' in variant:
            ramp=q.get('ramp');match=re.fullmatch(r'g[0-9a-f]i?r?s?t?_stair_([nesw])_k(\d+)',variant)
            direction={'UP':'n','DOWN':'s','LEFT':'w','RIGHT':'e'}
            if (not ramp or match[1]!=direction[ramp['dir']] or int(match[2])!=ramp['rise']*8
                    or by!=block['y']-q['feet'] or by>=ramp['rise']
                    or key!=layout[q['z']//2][q['x']//2]):
                raise ValueError('native surface stair differs from measured ramp')
            if block['code_kind']==12:
                code_dir={'DOWN':0,'UP':1,'LEFT':2,'RIGHT':3}[ramp['dir']]
                high_row={'UP':bz==0,'DOWN':bz==1,'LEFT':bx==0,'RIGHT':bx==1}[ramp['dir']]
                stage=(2 if high_row else 1) if ramp['rise']>=3 else 0
                expected_by=(2 if high_row else 0) if ramp['rise']>=3 else (0 if ramp['rise']==1 else (1 if high_row else 0))
                if q['kind']!='WALK' or by!=expected_by or block['code_value']!=(stage<<2|code_dir):
                    raise ValueError('native surface STEP code differs from measured stage')
        elif key not in {k for row in layout for k in row} or key in table.get('cells',{}) or by!=block['y']-q['feet'] or by>=2:
            raise ValueError('unsupported native surface unfitted CELL')
        return shape
    if re.fullmatch(r'tmpl:0\|[0-9a-f]{16}\|\d+,\d+,\d+',shape):
        if template_validator is None:
            raise ValueError('native surface template requires exact payload validator')
        validated=template_validator(shape,q,block)
        if not isinstance(validated,str) or not re.fullmatch(r'tmpl:\d+\|[0-9a-f]{16}\|\d+,\d+,\d+',validated):
            raise ValueError('native surface template validator rejected payload')
        return validated
    raise ValueError('unsupported native surface shape key')


def consume(record, *, source, raw, grid, revision, table, model_hash, template_validator=None):
    """Return ``fragments``, absolute ``y_range``, ``bounds_xz`` and invisible ``audit``.

    ``revision`` is ``before`` or ``after``. ``model_hash`` matches the runtime's
    fitted geometry hash; ``template_validator(shape, quarter, block)`` must
    return a source/payload-validated TMPL key. Hash verification remains with
    the existing gallery descriptor reader; this function also checks raw map
    bytes and every source witness. No datum translation occurs here.
    """
    if revision not in ('before','after') or record.get('surface_evidence_mode') not in MODES:
        raise ValueError('unsupported native surface proof mode')
    if table.get('game')!='emerald' or not isinstance(raw,bytes) or len(raw)!=source['w']*source['h']*4:
        raise ValueError('invalid native surface source input')
    if hashlib.sha256(raw).hexdigest()!=record.get('map_cells_sha256') or record.get('map')!=source.get('id'):
        raise ValueError('stale native surface source bytes')
    layout=record.get('layout');x0=integer(record.get('x'),0,source['w']-1,'source x');z0=integer(record.get('y'),0,source['h']-1,'source z')
    if not isinstance(layout,list) or not 1<=len(layout)<=32 or not isinstance(layout[0],list) or not 1<=len(layout[0])<=32:
        raise ValueError('invalid native surface rectangle')
    w,d=len(layout[0]),len(layout)
    if (any(not isinstance(row,list) or len(row)!=w for row in layout) or x0+w>source['w'] or z0+d>source['h']
            or record.get('width')!=w or record.get('depth')!=d
            or [row[x0:x0+w] for row in grid[z0:z0+d]]!=layout):
        raise ValueError('stale native surface source layout')
    collisions=[[raw[((z0+z)*source['w']+x0+x)*4+2] for x in range(w)] for z in range(d)]
    if record.get('source_collisions')!=collisions:
        raise ValueError('stale native surface source collisions')
    columns=record.get('source_surface_columns')
    if not isinstance(columns,list) or len(columns)!=w*d*4:
        raise ValueError('incomplete native surface source quarters')
    source_tiles={int(t) for line in grid for key in line for t in key.split('/')[1].split('-')}
    fragments=[];audit=[];seen=set();slots={};all_positions=set()
    for q in columns:
        if not isinstance(q,dict) or not COLUMN_FIELDS<=set(q) or set(q)-COLUMN_FIELDS-{'ramp','deck'}:
            raise ValueError('invalid native surface quarter fields')
        x=integer(q['x'],0,w*2-1,'quarter x');z=integer(q['z'],0,d*2-1,'quarter z')
        if (x,z) in seen:raise ValueError('duplicate native surface source quarter')
        seen.add((x,z));cx=x0+x//2;cz=z0+z//2;i=(cz*source['w']+cx)*4
        for name in ('source_tile','raw_collision','built_collision','source_elevation','feet'):
            integer(q[name],0,1048575,'quarter '+name)
        match=re.fullmatch(r'g3q/(\d+)-(\d+)-(\d+)-(\d+)',layout[z//2][x//2])
        if not match:raise ValueError('invalid native surface source key')
        expected_tile=int(match[1+(z%2)*2+x%2]);collision=raw[i+2]
        if (q['source_tile']!=expected_tile or q['raw_collision']!=collision or q['built_collision']!=collision
                or q['source_elevation']!=raw[i+3]):
            raise ValueError('stale native surface quarter source witness')
        expected_kind=KINDS.get(collision,'SOLID')
        if cx>=source.get('trueW',source['w']) or cz>=source.get('trueH',source['h']):expected_kind='SOLID'
        expected_model='GROUND' if expected_kind=='WALK' else expected_kind
        if q['kind']!=expected_kind or q['model_kind']!=expected_model:
            raise ValueError('native surface kind differs from source collision')
        integer(q['source_tags'],0,65535,'source tags')
        feet,ramp,deck=layout_witness(record,source,cx,cz)
        if q['feet']!=feet or q.get('ramp')!=ramp or q.get('deck')!=deck:
            raise ValueError('native surface feet/ramp/deck differs from actual layout')
        for rev in ('before','after'):tile(q[rev+'_effective_floor'])
        effective=q[revision+'_effective_floor'];blocks=q[revision]
        if not isinstance(blocks,list) or len(blocks)>97:raise ValueError('invalid native surface carrier count')
        floor_y=(deck['under'] if deck else feet)-1
        if not any(b.get('y')==floor_y and b.get('code_kind') in (1,2) for b in blocks):
            raise ValueError('native surface proof omits its actual floor')
        local_seen=set()
        for block in blocks:
            if (not isinstance(block,dict) or not BLOCK_FIELDS<=set(block)
                    or set(block)-BLOCK_FIELDS-{'tile','slot_shape','shape_key','palette','story_key'}
                    or type(block['visible']) is not bool):
                raise ValueError('invalid native surface carrier fields')
            y=integer(block['y'],-1,95,'carrier y');kind=integer(block['code_kind'],1,16,'carrier code');value=integer(block['code_value'],0,16777215,'carrier value')
            if y in local_seen:raise ValueError('duplicate native surface carrier position')
            local_seen.add(y);position=x,y,z
            if position in all_positions:raise ValueError('overlapping native surface carriers')
            all_positions.add(position)
            shape=block.get('shape_key')
            if kind in PLAIN:
                if block.get('slot_shape')!=PLAIN[kind] or 'tile' not in block:raise ValueError('native surface slot shape mismatch')
                actual_tile=tile(block['tile']);slot=(block['slot_shape'],value)
                if actual_tile not in source_tiles:raise ValueError('native surface slot art is absent from source map')
                if slots.setdefault(slot,actual_tile)!=actual_tile:raise ValueError('native surface slot maps to conflicting tiles')
            elif 'tile' in block or 'slot_shape' in block:
                raise ValueError('non-tile native surface carrier has slot art')
            if shape is not None and (kind not in SHAPED or block.get('palette')!='BAKED'):
                raise ValueError('unsupported native surface shape/code combination')
            if shape is None and 'palette' in block:raise ValueError('native surface palette lacks shape')
            expected_visible=kind in PLAIN or shape is not None
            if block['visible']!=expected_visible:raise ValueError('native surface visibility differs from actual carrier kind')
            if not block['visible']:
                if kind not in INVISIBLE and not (kind==9 and value==20):raise ValueError('unsupported invisible native surface carrier')
                if ((kind in (4,11) and value>15) or (kind==8 and value>7)
                        or (kind in (14,15,16) and value!=0) or (kind==12 and value>35)):
                    raise ValueError('unsupported invisible native surface value')
                if kind in (5,6):
                    if block.get('story_key')!=f"{record['map']}:{cx}:{cz}":raise ValueError('native surface story identity mismatch')
                elif 'story_key' in block:raise ValueError('native surface story key on non-story carrier')
                audit.append(dict(block,x=x,z=z));continue
            if 'story_key' in block:raise ValueError('story carrier cannot become native surface visual')
            if kind==2:
                if shape is not None or q['kind']!='WATER' or y!=(deck['under'] if deck else feet)-1 or block['tile']!=q['source_tile'] or block['tile']!=effective:
                    raise ValueError('unsupported native surface HALF water mapping')
                shape=f"slab:0|{block['tile']}|8"
            elif shape is not None:
                shape=validate_shape(shape,dict(q,effective_floor=effective,rendered_blocks=blocks),block,layout,table,model_hash,template_validator)
            elif kind==1:
                # Raised ground's lower fill can use the map's border tile if
                # the chosen effective floor has no ordinary FULL slot. The
                # exact slot/tile witness is retained; only the top floor must
                # equal effective_floor. No FULL is invented over WATER.
                if ((y==feet-1 and block['tile']!=effective)
                        or (y>=feet and (q['kind']!='SOLID' or block['tile']!=q['source_tile']))):
                    raise ValueError('unsupported native surface FULL mapping')
                shape='cube:0|'+','.join([str(block['tile'])]*6)
            else:
                raise ValueError('native surface shaped carrier lacks explicit geometry')
            fragments.append({'key':shape,'palette':'BAKED','x':x,'y':y,'z':z})
    if not fragments:raise ValueError('native surface proof has no visible surfaces')
    low=min(f['y'] for f in fragments);high=max(f['y'] for f in fragments)+1
    if high-low>96:raise ValueError('native surface scene exceeds height limit')
    return {'fragments':fragments,'y_range':[low,high],'bounds_xz':[w*2,d*2],
            'audit':audit,'source_quarters':len(seen),'datum':'relative-to-map-base; untranslated'}
