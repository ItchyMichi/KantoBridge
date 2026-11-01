"""Town props keep matching metadata and model their complete assemblies."""
import json
from pathlib import Path
import sys
import numpy as np
import pytest

ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.shape_fit.emerald_pass import towns
from tools.shape_fit.blue_pass.common import decode


@pytest.fixture(scope='module')
def authored():
    if not towns.BASELINE.exists():pytest.skip('local authoring inputs required')
    table=json.loads(towns.BASELINE.read_text());art=towns.EmeraldArt(towns.BUNDLE)
    return table,art,towns.generate(table,art)


def test_register_is_explicit_and_preserves_interaction_fields(authored):
    table,art,updates=authored
    assert len(updates)==23
    for group,decisions in towns.REGISTER.items():
        rows=towns.rows_for(group)
        assert set(decisions)==set(range(1,len(rows)+1))
    for key,new in updates.items():
        old=table['cells'][key]
        assert {k:v for k,v in old.items() if k not in ('v','t','up','from')}=={k:v for k,v in new.items() if k not in ('v','t','up','from')}
        occ,paint=decode(new)
        assert occ.any() and paint.shape==occ.shape


def test_orb_devices_have_grounded_pedestals_and_joined_crowns(authored):
    table,art,updates=authored
    for item in towns.assemblies():
        if 'orb_machine' not in item['id']:continue
        upper,lower=(updates[row[0]] for row in item['layout'])
        base=decode(lower)[0];crown=decode(upper)[0]
        assert base[0].any()
        assert (base[-1] & crown[0]).any()
        assert base[5,8,8] and base[14,8,8]
        assert not crown[10:].any()


def test_shrine_is_continuous_across_actual_three_cell_layout(authored):
    table,art,updates=authored
    item=towns.assemblies()[0];pieces=[decode(updates[k])[0] for k in item['layout'][0]]
    assert (pieces[0][:,:,-1] & pieces[1][:,:,0]).any()
    assert (pieces[1][:,:,-1] & pieces[2][:,:,0]).any()
    assert pieces[0][0].any() and pieces[1][0].any() and pieces[2][0].any()
    # Source oval is a recessed basin: its central floor survives below an open top.
    assert pieces[1][7,9,8] and not pieces[1][8,9,8]


def test_palace_niche_has_actual_open_recess_and_back_wall(authored):
    table,art,updates=authored
    item=next(item for item in towns.assemblies() if 'palace' in item['id'])
    whole=np.concatenate([decode(updates[row[0]])[0] for row in item['layout'][::-1]])
    assert whole[18,8,8] and not whole[18,10,8]
    assert whole[11,13,8]
    assert whole[24,10,4] and not whole[24,10,8]


def test_all_authored_assemblies_exist_on_exported_maps():
    path=towns.OUT/'assembly-placements.json'
    if not path.exists():pytest.skip('preview assembly evidence not yet generated')
    rows=json.loads(path.read_text())
    assert len(rows)==7
    assert all(row['occurrences'] for row in rows)
