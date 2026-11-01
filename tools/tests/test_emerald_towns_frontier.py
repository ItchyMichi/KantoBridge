"""Exact Frontier ownership, matching links, source colors, and whole joins."""
import json
from collections import deque
from pathlib import Path
import sys
import numpy as np
import pytest

ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns_frontier as frontier


@pytest.fixture(scope='module')
def authored():
    if not frontier.BASELINE.exists():pytest.skip('local export required')
    table=json.loads(frontier.BASELINE.read_text());art=frontier.EmeraldArt(frontier.BUNDLE)
    updates,additions=frontier.generate(table,art)
    return table,art,updates,additions


def test_full_declared_scope_and_exact_metadata_exceptions(authored):
    table,art,updates,additions=authored;decisions=frontier.decisions()
    assert sum(map(len,decisions.values()))==351
    assert set(frontier.TRANSFERRED_WEST).isdisjoint(decisions[frontier.WEST])
    from tools.shape_fit.emerald_pass.frontier_rail_material import KEYS
    assert {frontier.key(frontier.WEST,i) for i in frontier.TRANSFERRED_WEST}==set(KEYS)
    assert len(updates)==29 and set(additions)==set(frontier.WEST_BASES)
    assert all(k not in table['cells'] for k in additions)
    expected=frontier.metadata_corrections(table,art)
    assert len(expected)==8
    for key,new in updates.items():
        old=table['cells'][key];original={k:v for k,v in old.items() if k not in ('v','t','up','from')}
        original.update(expected.get(key,{}))
        assert {k:v for k,v in new.items() if k not in ('v','t','up','from')}==original


def test_proven_retained_building_fragments_have_full_occurrence_coverage():
    path=frontier.OUT/'coverage-proof.json'
    if not path.exists():pytest.skip('local source evidence required')
    proof=json.loads(path.read_text())
    assert sum(map(len,frontier.COVERED.values()))==277
    for group,indices in frontier.COVERED.items():
        for i in indices:
            row=proof[frontier.key(group,i)]
            assert row['fully_covered'] and row['occurrences']
            assert all(p['buildings'] for p in row['occurrences'])


def test_flag_has_no_water_colored_voxels(authored):
    table,art,updates,additions=authored
    for group,indices in [(frontier.EAST,(202,203,204,205)),(frontier.WEST,(124,125,131,132))]:
        for i in indices:
            key=frontier.key(group,i);occ,paint=frontier.decode(updates[key]);pixels=np.asarray(art.cell(key,'BAKED').convert('RGB'));colors=pixels[paint[occ]//16,paint[occ]%16]
            assert not np.all(colors==(82,107,214),axis=1).any()
            assert not np.all(colors==(107,132,214),axis=1).any()


def test_all_flag_folds_join_to_the_pole_and_base(authored):
    table,art,updates,additions=authored
    environment=json.loads((ROOT/'local/model-gallery/emerald-pass-study/environment/updates.json').read_text())['cells']
    entries={**table['cells'],**environment,**updates,**additions}
    for item in frontier.assemblies():
        if 'flag' not in item['id']:continue
        whole=np.concatenate([np.concatenate([frontier.decode(entries[k])[0] for k in row],axis=2) for row in item['layout'][::-1]],axis=0)
        start=tuple(np.argwhere(whole)[0]);seen={start};queue=deque([start])
        while queue:
            p=queue.popleft()
            for axis in range(3):
                for delta in (-1,1):
                    n=list(p);n[axis]+=delta;n=tuple(n)
                    if all(0<=n[a]<whole.shape[a] for a in range(3)) and whole[n] and n not in seen:seen.add(n);queue.append(n)
        assert len(seen)==whole.sum(),item['id']


def test_new_curbs_match_adjacent_environment_cross_section(authored):
    table,art,updates,additions=authored
    for i,edge in frontier.CURBS.items():
        if i==206:continue
        occ,_=frontier.decode(updates[frontier.key(frontier.EAST,i)])
        assert occ[0].all() and not occ[3:].any()
        assert occ[1:3].any()
    for k in additions:
        occ,_=frontier.decode(additions[k])
        assert occ[0].all() and occ[15,3,5]
        assert not occ[3:,8:,:].any()


def test_all_real_flag_variants_and_conifer_pairs_exist():
    path=frontier.OUT/'assembly-placements.json'
    if not path.exists():pytest.skip('local assembly evidence required')
    rows=json.loads(path.read_text());flags={r['id']:len(r['occurrences']) for r in rows if 'flag' in r['id']}
    assert flags['emerald_frontier_east_flag']==9
    assert sum(n for k,n in flags.items() if 'west_flag' in k)==15
    assert len(rows)==9 and all(r['occurrences'] for r in rows)
