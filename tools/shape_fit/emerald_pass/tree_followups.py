"""Two roof-occluded broadleaf quarters and exact Fortree conifer aliases.

Proposals only. Existing accepted tree geometry and building templates are not
changed. The roof-occluded quarters have native foliage but no visible stem.
"""
from __future__ import annotations

import argparse
import base64
import copy
import gzip
import hashlib
import json

import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import decode, encode
from tools.shape_fit.emerald.littleroot_tree import KEYS, generate as broadleaf
from tools.shape_fit.emerald_pass.coastal import ROOT, BASELINE, BUNDLE, INVENTORY
from tools.shape_fit.emerald_pass.inventory import contact_pages, compact_context_pages
from tools.shape_fit.emerald_pass.review import read, entry_hash
from tools.shape_fit.emerald_pass.ship_beams import previews
from tools.shape_fit.emerald_pass.vegetation import color_uv, LEAVES, MIDDLE

OUT = ROOT/'local/model-gallery/emerald-pass-study/tree-followups'
LIVE = ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json'
PRECONDITION = ROOT/'local/model-history/emerald-batch-02-2243/snapshots/emerald/batch-02-2243.json.gz'
PRECONDITION_SHA256 = 'a457876964841f6698cf85c469d5ac7dba9da54a55e94ba220c532290406be9d'
PARTIAL = {'g3q/12349-12350-12103-12103':476, 'g3q/12353-12354-12103-25144':477}
UPPERS = (MIDDLE, 'g3q/10891-10892-10893-10894', 'g3q/11285-11286-10757-10758')
BASES = ('g3q/10913-10914-17555-17556', 'g3q/10913-10914-17563-17564', 'g3q/10913-10914-17582-17583')


def precondition_table():
    """Use the immutable pre-integration sculpture and existing aliases."""
    data = gzip.decompress(PRECONDITION.read_bytes())
    if hashlib.sha256(data).hexdigest() != PRECONDITION_SHA256:
        raise ValueError('tree follow-up precondition snapshot changed')
    return json.loads(data)


def partial_canopy(key, original, art):
    """Retain only the native leaf material of the accepted lower quarter."""
    template_key = KEYS[PARTIAL[key]]
    occ, paint = decode(broadleaf()[template_key])
    old_pixels = np.asarray(art.cell(template_key, 'BAKED').convert('RGB'))
    colors = old_pixels[paint//16, paint%16]
    leaves = np.any(np.all(colors[..., None, :] == np.asarray(LEAVES), axis=-1), axis=-1)
    occ &= leaves
    target = art.cell(key, 'BAKED')
    translated = np.zeros_like(paint)
    for address in np.unique(paint[occ]):
        translated[(paint == address) & occ] = color_uv(target, tuple(old_pixels[address//16, address%16]))
    assert occ.any()
    return encode(occ, translated, original, source='hand-emerald-roof-occluded-canopy', trim=True)


def source_witnesses(art):
    witnesses = []
    for base in BASES:
        for p in art.locations[base]:
            m = art.maps[p['map']]; grid = art.map_cells(m); raw = base64.b64decode(m['cells'])
            x,y = p['x'],p['y']; upper = grid[y-1][x]
            assert upper in UPPERS
            kind = lambda yy: raw[(yy*m['w']+x)*4+2]
            assert kind(y) == 7 and kind(y-1) in (0,7)
            assert (kind(y-1)==0) == (upper==UPPERS[2])
            witnesses.append(dict(p, upper_key=upper, base_key=base,
                upper_position=[x,y-1], upper_collision=kind(y-1), base_collision=kind(y),
                north_of_upper=grid[y-2][x] if y>=2 else None,
                south_key=grid[y+1][x] if y+1<len(grid) else None,
                south_collision=kind(y+1) if y+1<len(grid) else None,
                map_cells_sha256=hashlib.sha256(raw).hexdigest()))
    assert len(witnesses) == 19
    assert {(p['upper_key'],p['base_key']) for p in witnesses} == {(u,b) for u in UPPERS for b in BASES}
    return witnesses


def generate(baseline, current, art):
    updates, ledger, metadata = {}, [], []
    inventory = {r['key']:r for r in read(INVENTORY/'inventory.json')['entries']}
    for key in PARTIAL:
        old = baseline['cells'][key]
        assert old == current['cells'][key]
        updates[key] = partial_canopy(key, old, art)
        reason = ('At Petalburg (26,9)/(27,9), these are the lower quarters of the broadleaf canopy immediately behind the blue Mart. '
                  'The source roof occludes every stem texel. Keep only the leaf volume and native leaf colors from the accepted lower-quarter sculpture; '
                  'do not invent a green trunk, borrow bark from another key, or extrude the blue roof through the foliage. '
                  'The accepted northern quarters and neighboring rooted forest remain unchanged. The Mart is the existing exact b096 template at (24,10); '
                  'its roof and all existing placement/collision metadata are preserved. This is an occluded canopy component, not a newly inferred freestanding trunk.')
        ledger.append({'key':key,'kind':'cell','label':'Mart-roof-occluded broadleaf lower canopy quarter',
                       'family':'broadleaf tree','decision':'changed','reason':reason,
                       'baseline_entry_sha256':entry_hash(old),'occurrences':art.locations[key],
                       'evidence':[str(OUT/'source-evidence.json'), str(OUT/'source-context/page-001.png'), str(OUT/'mart-forest-context.png')]})
    witnesses = source_witnesses(art)
    for key in UPPERS:
        old = current['cells'][key]; new = copy.deepcopy(old)
        assert old.get('on') and old['on'] not in BASES
        new['alsoOn'] = list(dict.fromkeys([*old.get('alsoOn', []), *BASES]))
        new['stackFit'] = True
        assert {k:v for k,v in new.items() if k not in ('alsoOn','stackFit')} == {k:v for k,v in old.items() if k not in ('alsoOn','stackFit')}
        updates[key] = new
        rows = [p for p in witnesses if p['upper_key']==key]
        reason = ('Preserve the reviewed conifer sculpture, primary on relation and existing Frontier aliases. Add only the three exact Fortree hedge-overlap base keys as alsoOn targets. '
                  'All19 base occurrences are SOLID7 and immediately south of one of these three upper source keys. Fifteen upper occurrences are SOLID7; the four 11285 crown occurrences are WALK0 projections. The aliases combine each evidenced pair into one four-block tree section '
                  'at its existing terminal base. Source-specific stackFit opts the locally matching alsoOn pair into fitted planning after both entries pass the unlayered, unclaimed, unpinned terminal-pair preflight; native Tier-A columns otherwise prevent these exact pairs from becoming units. No repeated-middle self link, other crown-to-middle relation, hedge carrier, collision or floor flag is changed.')
        ledger.append({'key':key,'kind':'cell','label':'Conifer upper section with exact Fortree hedge-base aliases',
                       'family':'narrow conifer','decision':'changed','reason':reason,
                       'baseline_entry_sha256':entry_hash(baseline['cells'][key]), 'occurrences':art.locations[key],
                       'evidence':[str(OUT/'metadata-evidence.json'),str(OUT/'fortree-context/index.json')]})
        sprite = art.cell(key,'BAKED')
        metadata.append({'key':key,'field':'alsoOn','old_value':old.get('alsoOn'), 'new_value':new['alsoOn'],
                         'baseline_entry_sha256':entry_hash(baseline['cells'][key]), 'precondition_entry_sha256':entry_hash(old),
                         'proposed_entry_sha256':entry_hash(new),'source_pixel_mode':'RGBA',
                         'source_pixel_sha256':hashlib.sha256(sprite.convert('RGBA').tobytes()).hexdigest(),
                         'exact_added_targets':list(BASES),'occurrences':rows,'reason':reason})
        metadata.append({'key':key,'field':'stackFit','old_value':old.get('stackFit'),'new_value':True,
                         'baseline_entry_sha256':entry_hash(baseline['cells'][key]),'precondition_entry_sha256':entry_hash(old),
                         'proposed_entry_sha256':entry_hash(new),'source_pixel_mode':'RGBA',
                         'source_pixel_sha256':hashlib.sha256(sprite.convert('RGBA').tobytes()).hexdigest(),
                         'exact_added_targets':list(BASES),'occurrences':rows,'reason':reason})
    return updates, ledger, metadata, witnesses


def assemblies(art,witnesses):
    found = {}
    for p in witnesses:
        layout = [[p['upper_key']],[p['base_key']]]
        identifier = 'emerald_fortree_hedge_tree_'+hashlib.sha256(json.dumps(layout,separators=(',',':')).encode()).hexdigest()[:12]
        item = found.setdefault(identifier,{'id':identifier,'kind':'stack (model as one)',
            'note':'Exact Fortree upper and hedge-overlap base; existing sculpture and native material retained',
            'layout':layout,'placements':[{'source':[0,0],'offset':[0,1,0]},{'source':[0,1],'offset':[0,0,0]}],
            'baseline_layout':layout if p['upper_collision']==7 else [[None],[p['base_key']]],
            'baseline_placements':([{'source':[0,0],'offset':[0,0,0]}] if p['upper_collision']==7 else [])+[{'source':[0,1],'offset':[0,0,1]}], 'occurrences':[]})
        item['occurrences'].append({'map':p['map'],'x':p['x'],'y':p['y']-1,'palette':'BAKED'})
    proof_path=OUT/'runtime-proof.json'
    if proof_path.exists():
        proof=read(proof_path)
        assert proof['original_baseline_sha256']==hashlib.sha256(BASELINE.read_bytes()).hexdigest()
        for item in found.values():
            p=item['occurrences'][0]
            item['baseline_native_fragments']={
                'evidence':str(proof_path.relative_to(ROOT)).replace('\\','/'),
                'evidence_sha256':hashlib.sha256(proof_path.read_bytes()).hexdigest(),
                'baseline_sha256':proof['original_baseline_sha256'],
                'occurrence':{k:p[k] for k in ('map','x','y')},
                'source_layout':copy.deepcopy(item['layout'])}
    g=art.map_cells(art.maps['EM_PETALBURG_CITY'])
    # Includes the neighboring rooted forest. The source-occluded pair is never
    # advertised as a complete isolated tree with a visible stem.
    layout=[r[26:30] for r in g[6:10]]
    found['petalburg']={'id':'emerald_petalburg_mart_forest_canopy','kind':'object (model as one)',
        'note':'Rooted Petalburg forest adjoining the Mart, including its two roof-occluded canopy quarters',
        'layout':layout,'placements':[{'source':[x,z],'offset':[x,0,z]} for z in range(4) for x in range(4)],
        'occurrences':[{'map':'EM_PETALBURG_CITY','x':26,'y':6,'palette':'BAKED'}]}
    return list(found.values())


def mart_scene(table,art,out):
    """Read-only actual b096 plus forest geometry at their source positions."""
    from tools.model_gallery import building_catalog
    from tools.shape_fit.emerald.parts_model import build
    from tools.shape_fit import hand_check
    building_id='emerald_b096_EM_BATTLE_FRONTIER_OUTSIDE_WEST'
    building=next(b for b in table['buildings'] if b['id']==building_id)
    catalog=next(b for b in building_catalog(table,art) if b['id']==building_id)
    assert any(p['map']=='EM_PETALBURG_CITY' and p['x']==24 and p['y']==10 for p in catalog['occurrences'])
    draw=np.asarray(art.drawing(catalog['layout'],'BAKED').convert('RGB'))
    bo,bc=build(building,draw)
    h=max(len(bo),48);occ=np.zeros((h,112,96),bool);colors=np.zeros((*occ.shape,3),np.uint8)
    g=art.map_cells(art.maps['EM_PETALBURG_CITY'])
    for y in range(6,10):
        for x in range(26,30):
            key=g[y][x];o,t=decode(table['cells'][key]);a=np.asarray(art.cell(key,'BAKED').convert('RGB'))
            sl=(slice(0,len(o)),slice((y-6)*16,(y-5)*16),slice((x-24)*16,(x-23)*16))
            occ[sl]=o;colors[sl]=a[t//16,t%16]
    sl=(slice(0,len(bo)),slice(64,64+bo.shape[1]),slice(0,bo.shape[2]))
    occ[sl]|=bo;target=colors[sl];target[bo]=bc[bo]
    out.mkdir(parents=True,exist_ok=True)
    hand_check.picture(occ,colors,out/'angled.png',4)
    hand_check.front(occ,colors,out/'front.png',4)
    hand_check.front(occ.transpose(0,2,1),colors.transpose(0,2,1,3),out/'side.png',4)
    return {'building_id':building_id,'building_entry_sha256':entry_hash(building),
            'source_origin':[24,6],'building_source_origin':[24,10],
            'reason':'Source-pinned unchanged Mart next to native forest. The blue roof hides the partial tree stem in the source; leaf-only quarters never fabricate hidden bark.'}


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args()
    baseline,current,art=read(BASELINE),precondition_table(),EmeraldArt(BUNDLE)
    updates,ledger,metadata,witnesses=generate(baseline,current,art)
    definitions=assemblies(art,witnesses)
    OUT.mkdir(parents=True,exist_ok=True)
    write_json(OUT/'updates.json',{'cells':updates,'buildings':{}});write_json(OUT/'ledger.json',ledger)
    write_json(OUT/'metadata-evidence.json',metadata);write_json(OUT/'entities.json',definitions)
    evidence={'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),
        'precondition_table_sha256':PRECONDITION_SHA256,
        'partial_canopy':[{'key':k,'occurrences':art.locations[k], 'template_key':KEYS[n],
            'source_pixel_mode':'RGBA','source_pixel_sha256':hashlib.sha256(art.cell(k,'BAKED').convert('RGBA').tobytes()).hexdigest(),
            'baseline_entry_sha256':entry_hash(baseline['cells'][k]),'proposed_entry_sha256':entry_hash(updates[k]),
            'non_geometry_metadata_preserved':True} for k,n in PARTIAL.items()],
        'fortree_pair_witnesses':witnesses,
        'limits':'Outdoor pairs with SOLID terminal bases; 15 SOLID and four WALK upper cells. No ceiling restriction. Metadata affects only the immediate evidenced terminal base. Whole Minecraft QA remains pending.'}
    write_json(OUT/'source-evidence.json',evidence)
    if args.previews:
        inv={r['key']:r for r in read(INVENTORY/'inventory.json')['entries']}
        rows=[copy.deepcopy(inv[k]) for k in updates]
        proposed=copy.deepcopy(current);proposed['cells'].update(updates)
        contact_pages('current',rows,proposed,art,OUT/'inspection')
        compact_context_pages([copy.deepcopy(inv[k]) for k in PARTIAL],art,OUT/'source-context')
        compact_context_pages([dict(key=p['base_key'],group='fortree-alias',occurrences=[p],contact_position=i+1) for i,p in enumerate(witnesses)],art,OUT/'fortree-context')
        art.map_image('EM_PETALBURG_CITY').crop((22*16,6*16,31*16,16*16)).resize((576,640)).save(OUT/'mart-forest-context.png')
        previews(definitions,proposed,art,OUT/'assemblies')
        scene=mart_scene(proposed,art,OUT/'mart-context-current')
        write_json(OUT/'mart-scene-evidence.json',scene)
    print(f'{len(updates)} proposals: two canopy geometries, three metadata-only overlays; {len(witnesses)} exact pairs; {len(definitions)} whole/context exhibits')


if __name__=='__main__':main()
