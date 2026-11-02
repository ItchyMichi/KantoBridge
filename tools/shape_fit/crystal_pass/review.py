"""Guarded, resumable Crystal review merge. Pending inventory is never an approval.

Family modules own disjoint entries and emit full entries plus ledger rows (crystal_pass.common.Pass).
The cumulative merge always starts from the immutable pre-pass table (crystal-full-pass-baseline.json,
Codex's accepted trials included); a write needs the live table to be exactly the baseline bytes,
the result bytes, or the previous report's result. Codex's two accepted building trials are protected.

    python -m tools.shape_fit.crystal_pass.review --module players-house --module ... [--check|--write-table]
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
BASELINE = ROOT / 'local/model-gallery/crystal-full-pass-baseline.json'
TABLE = ROOT / 'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/crystal.json'
STUDY = ROOT / 'local/model-gallery/crystal-pass-study'
REPORT = STUDY / 'review.json'
GEOMETRY = {'v', 't', 'up', 'from'}
PROTECTED = {'crystal_b010_G2_ECRUTEAK_CITY', 'crystal_b088_G2_VIOLET_CITY'}
PART_SHAPES = {'box', 'gable', 'hip', 'profile', 'barrel', 'cylinder', 'dome', 'cone'}

# Checked-in allowlist of source-evidenced metadata changes: {"metadata": {key: {before, after,
# evidence, witnesses}}, "stacks": {key: {before, after, evidence}}, "terrain": {building: {...}}}.
# Authoring output cannot extend it during a merge.
AMENDMENTS = json.loads(Path(__file__).with_name('source_amendments.json').read_text(encoding='utf-8'))


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def digest(data):
    return hashlib.sha256(data).hexdigest()


def entry_hash(entry):
    return digest(json.dumps(entry, sort_keys=True, separators=(',', ':')).encode())


def validate_cell(key, old, new):
    a = {k: v for k, v in old.items() if k not in GEOMETRY}
    b = {k: v for k, v in new.items() if k not in GEOMETRY}
    meta = AMENDMENTS.get('metadata', {}).get(key)
    if meta and a == meta['before'] and b == meta['after']:
        a = dict(b)
    stack = AMENDMENTS.get('stacks', {}).get(key)
    if stack and a != b:
        before = {k: a[k] for k in ('on', 'alsoOn') if k in a}
        after = {k: b[k] for k in ('on', 'alsoOn') if k in b}
        if before == stack['before'] and after == stack['after']:
            for field in ('on', 'alsoOn'):
                a.pop(field, None)
            a.update(after)
    if a != b:
        raise ValueError(f'interaction/matching metadata changed: {key}')
    if new == old or all(new.get(k) == old.get(k) for k in GEOMETRY):
        return  # unchanged, or a metadata-only amendment (geometry, possibly with default paint, untouched)
    if new.get('class') == 'columns':
        # Deferral to native Tier A columns only through an allowlisted amendment, no voxels left.
        if not meta or meta['after'].get('class') != 'columns':
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
    from tools.shape_fit.crystal.parts_model import bounds
    fields = {'height', 'parts', 'roofRows', 'roof', 'bands', 'terrain'}
    a = {k: v for k, v in old.items() if k not in fields}
    b = {k: v for k, v in new.items() if k not in fields}
    if a != b:
        raise ValueError(f'building identity/matching metadata changed: {key}')
    if new.get('terrain', []) != old.get('terrain', []):
        allowed = AMENDMENTS.get('terrain', {}).get(key)
        if not allowed or old.get('terrain', []) != allowed['before'] or new.get('terrain') != allowed['after']:
            raise ValueError(f'building terrain changed without an amendment: {key}')
    if 'parts' not in new:
        if new != old:
            raise ValueError(f'a changed building must be a parts model: {key}')
        return
    if any(f in new for f in ('roofRows', 'roof', 'bands')):
        raise ValueError(f'parts model keeps box-building fields: {key}')
    if not 0 < new.get('height', 0) <= 240 or not new.get('parts'):
        raise ValueError(f'invalid building height/parts: {key}')
    w, d = len(new['tiles'][0]) * 8, len(new['tiles']) * 8
    for p in new['parts']:
        if p.get('shape') not in PART_SHAPES:
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
    if baseline.get('game') != 'crystal':
        raise ValueError('Crystal table required')
    originals = {'cell': baseline['cells'], 'building': {b['id']: b for b in baseline['buildings']}}
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
                    raise ValueError(f'addition needs an explicit scope amendment: {key}')
                old = originals[kind][key]
                if key in PROTECTED and new != old:
                    raise ValueError(f'accepted pilot changed: {key}')
                (validate_cell if kind == 'cell' else validate_building)(key, old, new)
                updates[identity] = new
    for ledger in ledgers:
        for row in ledger:
            identity = (row['kind'], row['key'])
            kind, key = identity
            if identity in decisions:
                raise ValueError(f'duplicate review ownership: {key}')
            if kind not in originals or key not in originals[kind]:
                raise ValueError(f'unknown reviewed entry: {identity}')
            if not all(row.get(k) for k in ['label', 'family', 'reason', 'evidence']):
                raise ValueError(f'missing semantic/source evidence: {key}')
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
    report = {'schema': 1, 'game': 'crystal', 'status': 'in_progress',
              'semantic_coverage_complete': not pending,
              'visual_review_complete': False,
              'counts': dict(Counter(r['decision'] for r in [*decisions.values(), *pending])),
              'protected_pilots': sorted(PROTECTED),
              'entries': [decisions[k] for k in sorted(decisions)], 'pending': pending}
    return result, report


def table_bytes(result):
    return (json.dumps(result, indent=2) + '\n').encode()


def write_guarded(table, baseline_bytes, result, previous=None):
    current = table.read_bytes()
    data = table_bytes(result)
    allowed = {digest(baseline_bytes), digest(data)}
    if previous and previous.get('baseline_sha256') == digest(baseline_bytes):
        allowed.add(previous['table_sha256'])
    if digest(current) not in allowed:
        raise ValueError('table changed independently; reconcile before merging')
    temp = table.with_suffix('.crystal-review.tmp')
    temp.write_bytes(data)
    temp.replace(table)
    return data


def module_paths(names):
    patches, ledgers = [], []
    for name in names:
        patches.append(STUDY / name / 'updates.json')
        ledgers.append(STUDY / name / 'ledger.json')
    return patches, ledgers


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--baseline', type=Path, default=BASELINE)
    ap.add_argument('--table', type=Path, default=TABLE)
    ap.add_argument('--module', action='append', default=[], help='a study folder holding updates.json and ledger.json')
    ap.add_argument('--patch', type=Path, action='append', default=[])
    ap.add_argument('--ledger', type=Path, action='append', default=[])
    ap.add_argument('--report', type=Path, default=REPORT)
    ap.add_argument('--candidate', type=Path, help='write the merged table here (scratch) instead of the live table')
    ap.add_argument('--proposed', type=Path, action='append', default=[],
                    help="a module's amendments.json, accepted for this dry run only (needs --candidate)")
    ap.add_argument('--require-complete', action='store_true')
    ap.add_argument('--write-table', action='store_true')
    ap.add_argument('--check', action='store_true')
    args = ap.parse_args()
    if args.proposed:
        if not args.candidate:
            raise SystemExit('--proposed amendments are for dry runs: give --candidate')
        for path in args.proposed:
            extra = read(path)
            for section in ('metadata', 'stacks', 'terrain'):
                AMENDMENTS.setdefault(section, {}).update(extra.get(section, {}))
    patches, ledgers = module_paths(args.module)
    patches += args.patch
    ledgers += args.ledger
    baseline_bytes = args.baseline.read_bytes()
    result, report = merge(json.loads(baseline_bytes), [read(p) for p in patches],
                           [read(p) for p in ledgers], args.require_complete)
    data = table_bytes(result)
    report.update(baseline_sha256=digest(baseline_bytes), table_sha256=digest(data),
                  modules=args.module, patches=[str(p.resolve()) for p in patches],
                  ledgers=[str(p.resolve()) for p in ledgers])
    if args.check and read(args.table) != result:
        raise ValueError('table differs from cumulative reviewed patches')
    if args.candidate:
        args.candidate.parent.mkdir(parents=True, exist_ok=True)
        args.candidate.write_bytes(data)
        print('candidate', args.candidate, digest(data))
        print(json.dumps(report['counts']))
        return
    if args.write_table:
        write_guarded(args.table, baseline_bytes, result, read(args.report) if args.report.exists() else None)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report['counts']))


if __name__ == '__main__':
    main()
