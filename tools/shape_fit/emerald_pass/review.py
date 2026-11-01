"""Guarded, resumable Emerald review. Pending inventory is never an approval.

Authoring modules own disjoint entries and emit full entries plus evidence rows.
The cumulative merge always starts from the immutable pre-pass table; writes
require the exact baseline bytes or the previously reported result bytes.
"""
from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASELINE = ROOT / 'local/model-gallery/emerald-full-pass-baseline.json'
TABLE = ROOT / 'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json'
REPORT = ROOT / 'local/model-gallery/emerald-pass-study/review.json'
GEOMETRY = {'v', 't', 'up', 'from'}
# The bottom conifer cell was linked back to its own crown. Map evidence:
# EM_ROUTE112 and EM_LAVARIDGE_TOWN, documented by scenery.py's source crops.
CORRECTED_STACKS = {
    'g3q/18237-18238-18239-18240': 'g3q/18202-18203-18191-18188',
    # Contest Hall side-facing chairs occupy adjacent floor cells. The source
    # upper/lower sprite rows are not a vertical stack (see furniture registry).
    'g3q/3773-3774-3736-3775': 'g3q/3753-3754-3784-3756',
    'g3q/3738-3776-3777-3741': 'g3q/3757-3758-3759-3785',
    # Floor pit halves and a foliage background north of an independent PC.
    # Exact source crops/coordinates: environment/metadata-corrections.json.
    'g3q/28866-28867-28868-28869': 'g3q/28870-28871-28872-28873',
    'g3q/30692-30693-30694-30695': 'g3q/30708-30709-30710-30711',
    # Devon wall frieze is behind the separate glass pedestal, not above it.
    'g3q/4601-4601-4602-4603': 'g3q/4621-4622-4623-4624',
}
# Complete bookcase rows from Birch's lab, Lanette's house and Weather Institute.
# All 38 adjacency/collision witnesses and 8/9-block room ceilings are recorded
# in building-interiors/bookcase-{stack,headroom}-evidence.json.
ADDED_STACKS = {
    # Contest noticeboard columns and two halves of the lobby banner.
    # All fourteen actual lower neighbors are SOLID7 (interior-details evidence).
    'g3q/3698-3695-3746-3747': 'g3q/3763-3764-3765-3766',
    'g3q/3698-3695-3747-3747': 'g3q/3764-3764-3766-3766',
    'g3q/3698-3695-3747-3748': 'g3q/3764-3767-3766-3768',
    'g3q/3742-3742-3743-3744': 'g3q/3697-3761-3732-3732',
    'g3q/3742-3742-3745-3743': 'g3q/3762-3697-3732-3732',
    'g3q/2918-2918-6384-6385': 'g3q/6404-6405-6406-6407',
    'g3q/2918-2918-6385-6386': 'g3q/6408-6409-6410-6411',
    'g3q/6446-6447-6448-6449': 'g3q/6384-6385-6404-6405',
    'g3q/6450-6451-6452-6453': 'g3q/6385-6386-6408-6409',
    'g3q/6477-6478-6448-6449': 'g3q/6384-6385-6404-6405',
    'g3q/6384-6385-6404-6405': 'g3q/6406-6407-6436-6437',
    'g3q/6385-6386-6408-6409': 'g3q/6410-6411-6440-6441',
    # Contest PC cap and three Union Room wall-display variants; seven exact
    # source occurrences: public-rooms/stack-evidence.json.
    'g3q/3742-3742-3782-3783': 'g3q/9-10-11-12',
    'g3q/10567-10567-10568-10570': 'g3q/10587-10590-10589-10591',
    'g3q/10567-10567-10571-10572': 'g3q/10592-10593-10594-10595',
    'g3q/10567-10567-10631-10569': 'g3q/10648-10588-10649-10589',
}
# Source-verified overhead roofs/arches above exported collision-0 WALK cells.
# The existing planner supplies decor carriers here; no solid ground is added.
# Exact map coordinates and collision values are in buildings-east/terrain-evidence.json.
OVERHEAD_TERRAIN_REMOVALS = {
    'emerald_b081_EM_SLATEPORT_CITY': {(1, 2), (2, 2), (3, 2)},
    'emerald_b098_EM_BATTLE_FRONTIER_OUTSIDE_WEST': {(1, 3), (2, 3), (3, 3)},
    'emerald_b105_EM_BATTLE_FRONTIER_OUTSIDE_EAST': {(x, 2) for x in range(2, 11)},
    # These lower-facade cells were wrongly excluded despite exported SOLID7.
    # Door113 x8 and WALK0 x5/11 stay excluded. See facade-terrain-evidence.json.
    'emerald_b099_EM_BATTLE_FRONTIER_OUTSIDE_EAST': {(x, 14) for x in (0,1,2,3,4,6,7,9,10,12,13,14,15,16)},
    'emerald_b103_EM_BATTLE_FRONTIER_OUTSIDE_EAST': {(x, 1) for x in range(5,10)},
}

# Narrow source-backed additions completing existing whole objects. New keys
# remain forbidden unless their metadata, source pixels and placements are here.
ADDED_CELLS = {
    'g3q/5056-5057-4992-4991': {
        'metadata': {'class': 'voxels', 'seat': True},
        'source_pixel_sha256': 'ef44acb5dc96d560d74e40ebbdfa4cd322b3d39ab4a290b14b2f1df89d15ac24',
        'occurrences': [('EM_MT_CHIMNEY_CABLE_CAR_STATION',0,11), ('EM_ROUTE112_CABLE_CAR_STATION',0,11)],
    },
}
# Six missing horizontal centers complete the existing five-by-two Lavaridge
# gym dais. Each source cell is WALK0; no blocking carrier is introduced.
for _key, _sha, _x, _y in [
    ('g3q/6672-6673-6671-6674','57ecfecc29e3d7f92a85ae179280ee839fb181844bf275ce7d7ca46ef7d1abcb',12,9),
    ('g3q/6675-6676-6677-6677','7978a0f2c5189859f05eac17deb4acdb8a18efa231266abf3b8da5f9e3ec7dc6',13,9),
    ('g3q/6678-6669-6679-6671','b238b073bad60ef619f55199dbfafc713651254cefc24a0dd62721aa339da959',14,9),
    ('g3q/6671-6671-6689-6690','29f24d8b834886e1058a9cd2f760efb51fcaec075b6540d6d5ce6399a2b4777d',12,10),
    ('g3q/6671-6671-6690-6690','89e69d11b3400dbf70db24cf54c4ee2a8922c7c3042dd28f2bc57374785cce4a',13,10),
    ('g3q/6671-6671-6690-6691','29575aa648c636f2aeece47430a2c95075293a3e1eaa870e558914eb24d64c68',14,10),
]:
    ADDED_CELLS[_key] = {'metadata': {'class':'voxels','walk':True},
        'source_pixel_sha256': _sha, 'occurrences': [('EM_LAVARIDGE_TOWN_GYM_1F',_x,_y)]}

# Individually inspected Frontier flags/tree variants and Mauville pylons.
# This checked-in allowlist pins both sides of each metadata change and all
# source witnesses; authoring output cannot extend it during a merge.
SOURCE_AMENDMENTS = json.loads(Path(__file__).with_name('source_amendments.json').read_text())
STACK_AMENDMENTS = SOURCE_AMENDMENTS['stacks']
METADATA_AMENDMENTS = SOURCE_AMENDMENTS.get('metadata', {})
for _key, _spec in SOURCE_AMENDMENTS['added_cells'].items():
    ADDED_CELLS[_key] = dict(_spec, occurrences=[tuple(p) for p in _spec['occurrences']])
ADDED_BUILDINGS = {key:dict(spec, occurrences=[tuple(p) for p in spec['occurrences']])
                  for key,spec in SOURCE_AMENDMENTS.get('added_buildings',{}).items()}


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def digest(data):
    return hashlib.sha256(data).hexdigest()


def entry_hash(entry):
    return digest(json.dumps(entry, sort_keys=True, separators=(',', ':')).encode())


def protected():
    from tools.shape_fit.emerald.littleroot_tree import KEYS
    from tools.shape_fit.emerald.building_trials import IDS
    return set(KEYS.values()) | set(IDS)


def validate_cell(key, old, new):
    a = {k: v for k, v in old.items() if k not in GEOMETRY}
    b = {k: v for k, v in new.items() if k not in GEOMETRY}
    if key in METADATA_AMENDMENTS:
        amendment = METADATA_AMENDMENTS[key]
        if a == amendment['before'] and b == amendment['after']:
            a = dict(b)
    if key in CORRECTED_STACKS and a.get('on') == CORRECTED_STACKS[key] and 'on' not in b:
        a.pop('on')
    if key in ADDED_STACKS and 'on' not in a and b.get('on') == ADDED_STACKS[key]:
        a['on'] = ADDED_STACKS[key]
    if key in STACK_AMENDMENTS and a != b:
        amendment = STACK_AMENDMENTS[key]
        before = {k:a[k] for k in ('on','alsoOn') if k in a}
        after = {k:b[k] for k in ('on','alsoOn') if k in b}
        if before == amendment['before'] and after == amendment['after']:
            for field in ('on','alsoOn'):a.pop(field,None)
            a.update(after)
    if a != b:
        raise ValueError(f'interaction/matching metadata changed: {key}')
    if new == old:
        return
    if new.get('class') == 'columns':
        # Deferral to native Tier A columns (flat wall art folded by the planner, at the wall's
        # measured height): only through an allowlisted amendment, and with no voxel geometry left.
        if key not in METADATA_AMENDMENTS or METADATA_AMENDMENTS[key]['after'].get('class') != 'columns':
            raise ValueError(f'native column deferral needs an amendment: {key}')
        if any(k in new for k in ('v', 't', 'up')):
            raise ValueError(f'native column deferral keeps voxel geometry: {key}')
        return
    layers = [new, *new.get('up', [])]
    if not 1 <= len(layers) <= 15:
        raise ValueError(f'invalid layer count: {key}')
    for layer in layers:
        raw = base64.b64decode(layer['v'], validate=True)
        paint = base64.b64decode(layer['t'], validate=True)
        if len(raw) != 512 or len(paint) != 4096 or not any(raw):
            raise ValueError(f'invalid or empty revised geometry: {key}')


def validate_building(key, old, new):
    from tools.shape_fit.emerald.parts_model import bounds
    fields = {'height', 'parts'}
    a = {k: v for k, v in old.items() if k not in fields}
    b = {k: v for k, v in new.items() if k not in fields}
    if key in OVERHEAD_TERRAIN_REMOVALS and old.get('terrain') != new.get('terrain'):
        removals = OVERHEAD_TERRAIN_REMOVALS[key]
        if not removals <= {tuple(cell) for cell in old.get('terrain', [])}:
            raise ValueError(f'overhead footprint evidence is stale: {key}')
        expected = [cell for cell in old['terrain'] if tuple(cell) not in removals]
        if new.get('terrain') != expected:
            raise ValueError(f'unsupported building footprint change: {key}')
        a['terrain'] = expected
    if a != b:
        raise ValueError(f'building identity/matching metadata changed: {key}')
    if not 0 < new.get('height', 0) <= 240 or not new.get('parts'):
        raise ValueError(f'invalid building height/parts: {key}')
    w, d = len(new['tiles'][0]) * 8, len(new['tiles']) * 8
    for p in new['parts']:
        if p.get('shape') not in {'box', 'gable', 'hip', 'profile', 'barrel', 'cylinder', 'dome', 'cone'}:
            raise ValueError(f'unknown building part: {key}')
        x0, x1, z0, z1 = bounds(p)
        if not (0 <= x0 < x1 <= w and 0 <= z0 < z1 <= d):
            raise ValueError(f'part outside building footprint: {key}')
        if not 0 <= p['y'][0] < p['y'][1] <= new['height']:
            raise ValueError(f'part height outside building: {key}')
        if p['shape'] == 'profile':
            for name in ('profile', 'cross'):
                if name in p and (not p[name] or any(not 0 <= h <= p['y'][1] - p['y'][0] for h in p[name])):
                    raise ValueError(f'profile outside part height: {key}')
        for face, rect in p.get('paint', {}).items():
            if face != 'wrap' and not (len(rect) == 4 and 0 <= rect[0] < rect[2] <= w and 0 <= rect[1] < rect[3] <= d):
                raise ValueError(f'paint outside source drawing: {key}')


def merge(baseline, patches, ledgers, require_complete=False):
    if baseline.get('game') != 'emerald':
        raise ValueError('Emerald table required')
    originals = {'cell': baseline['cells'], 'building': {b['id']: b for b in baseline['buildings']}}
    additions = {'cell': ADDED_CELLS, 'building': ADDED_BUILDINGS}
    updates, decisions = {}, {}
    for patch in patches:
        if set(patch) - {'cells', 'buildings'}:
            raise ValueError('unknown patch section')
        for kind, section in [('cell', 'cells'), ('building', 'buildings')]:
            for key, new in patch.get(section, {}).items():
                identity = (kind, key)
                if identity in updates:
                    raise ValueError(f'duplicate patch ownership: {key}')
                if key not in originals[kind]:
                    if key not in additions[kind]:
                        raise ValueError(f'addition needs an explicit scope amendment: {key}')
                    old = additions[kind][key]['metadata']
                else:
                    old = originals[kind][key]
                if key in protected() and new != old:
                    raise ValueError(f'accepted pilot changed: {key}')
                (validate_cell if kind == 'cell' else validate_building)(key, old, new)
                updates[identity] = new
    for ledger in ledgers:
        for row in ledger:
            identity = (row['kind'], row['key'])
            kind, key = identity
            if identity in decisions:
                raise ValueError(f'duplicate review ownership: {key}')
            added = kind in additions and key in additions[kind] and key not in originals[kind]
            if kind not in originals or (key not in originals[kind] and not added):
                raise ValueError(f'unknown reviewed entry: {identity}')
            if not all(row.get(k) for k in ['label', 'family', 'reason', 'evidence']):
                raise ValueError(f'missing semantic/source evidence: {key}')
            if added:
                source = additions[kind][key]
                places = sorted((p['map'],p['x'],p['y']) for p in row.get('occurrences',[]))
                if (not row.get('baseline_absent') or row.get('baseline_entry_sha256') is not None
                    or identity not in updates or row.get('decision') != 'added'
                    or row.get('proposed_entry_sha256') != entry_hash(updates[identity])
                    or row.get('source_pixel_sha256') != source['source_pixel_sha256']
                    or places != sorted(source['occurrences'])):
                    raise ValueError(f'addition evidence disagrees with patch/source: {key}')
                decisions[identity] = copy.deepcopy(row)
                continue
            if row.get('baseline_entry_sha256') != entry_hash(originals[kind][key]):
                raise ValueError(f'stale source entry: {key}')
            changed = updates.get(identity, originals[kind][key]) != originals[kind][key]
            if row.get('decision') != ('changed' if changed else 'retained'):
                raise ValueError(f'decision disagrees with patch: {key}')
            decisions[identity] = copy.deepcopy(row)
    if set(updates) - set(decisions):
        raise ValueError('patch without review decision')
    pending = [{'kind': kind, 'key': key, 'decision': 'pending'}
               for kind, entries in originals.items() for key in entries if (kind, key) not in decisions]
    if require_complete and pending:
        raise ValueError(f'{len(pending)} entries still need semantic review')
    result = copy.deepcopy(baseline)
    result['cells'].update({key: v for (kind, key), v in updates.items() if kind == 'cell'})
    result['buildings'] = [updates.get(('building', b['id']), b) for b in result['buildings']]
    result['buildings'].extend(v for (kind,key),v in sorted(updates.items())
                               if kind=='building' and key not in originals['building'])
    report = {'schema': 1, 'game': 'emerald', 'status': 'in_progress',
              'semantic_coverage_complete': not pending,
              'visual_review_complete': False,
              'counts': dict(Counter(r['decision'] for r in [*decisions.values(), *pending])),
              'protected_pilots': sorted(protected()),
              'entries': [decisions[k] for k in sorted(decisions)], 'pending': pending}
    return result, report


def write_guarded(table, baseline_bytes, result, previous=None):
    current = table.read_bytes()
    data = (json.dumps(result, indent=2) + '\n').encode()
    allowed = {digest(baseline_bytes), digest(data)}
    if previous and previous.get('baseline_sha256') == digest(baseline_bytes):
        allowed.add(previous['table_sha256'])
    if digest(current) not in allowed:
        raise ValueError('table changed independently; reconcile before merging')
    temp = table.with_suffix('.emerald-review.tmp')
    temp.write_bytes(data)
    temp.replace(table)
    return data


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--baseline', type=Path, default=BASELINE)
    ap.add_argument('--table', type=Path, default=TABLE)
    ap.add_argument('--patch', type=Path, action='append', default=[])
    ap.add_argument('--ledger', type=Path, action='append', default=[])
    ap.add_argument('--report', type=Path, default=REPORT)
    ap.add_argument('--require-complete', action='store_true')
    ap.add_argument('--write-table', action='store_true')
    ap.add_argument('--check', action='store_true')
    args = ap.parse_args()
    baseline_bytes = args.baseline.read_bytes()
    result, report = merge(json.loads(baseline_bytes), [read(p) for p in args.patch],
                           [read(p) for p in args.ledger], args.require_complete)
    data = (json.dumps(result, indent=2) + '\n').encode()
    report.update(baseline_sha256=digest(baseline_bytes), table_sha256=digest(data),
                  patches=[str(p.resolve()) for p in args.patch], ledgers=[str(p.resolve()) for p in args.ledger])
    if args.check and read(args.table) != result:
        raise ValueError('table differs from cumulative reviewed patches')
    if args.write_table:
        write_guarded(args.table, baseline_bytes, result, read(args.report) if args.report.exists() else None)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report['counts']))


if __name__ == '__main__':
    main()
