"""The canonical item catalogue (protocol/items.json, MULTI_GAME_PLAN §4.9): it matches its schema, names
only symbols, mirrors exactly today's battle items into Cobblemon, and its Lua copy is current."""
import json

from kb_protocol import load_items, load_protocol
from kb_protocol.items import read_items, render_items_lua
from kb_protocol.paths import PROTOCOL_DIR, REPO_ROOT

PROTOCOL = load_protocol()
ITEMS = load_items()
KB_ITEMS_LUA = REPO_ROOT / "gen1-bridge" / "lib" / "kb_items.lua"


def test_the_catalogue_matches_its_schema():
    problems = PROTOCOL.check_items(read_items())
    assert not problems, [str(p) for p in problems]


def test_every_item_has_a_gen1_symbol_and_an_id_derived_from_it():
    for item_id in ITEMS.ids:
        item = ITEMS.get(item_id)
        assert item.symbols["gen1"].lower() == item_id, item_id
        assert ITEMS.canonical("gen1", item.symbols["gen1"]) == item_id


def test_the_mirror_set_is_todays_29_battle_items():
    mirrored = ITEMS.mirrored()
    assert len(mirrored) == 29
    assert {ITEMS.get(i).cobblemon for i in mirrored} >= {"poke_ball", "potion", "elixir", "paralyze_heal", "x_defence", "x_special_attack"}
    assert "rare_candy" not in mirrored and "moon_stone" not in mirrored


def test_machines_cross_by_move_and_keys_never_cross():
    tms = [i for i in ITEMS.ids if ITEMS.get(i).clazz == "tm"]
    assert len(tms) == 50 and len({ITEMS.get(i).move for i in tms}) == 50
    assert all(1 <= ITEMS.get(i).move <= 165 for i in tms)
    assert ITEMS.get("tm_mega_punch").move == 5 and ITEMS.get("tm_substitute").move == 164
    hms = [i for i in ITEMS.ids if i.startswith("hm_")]
    assert len(hms) == 5 and all(ITEMS.get(i).clazz == "key" for i in hms)
    assert "bicycle" not in ITEMS.crossing() and "hm_cut" not in ITEMS.crossing() and "potion" in ITEMS.crossing()
    assert all(ITEMS.get(i).cobblemon is None for i in ITEMS.ids if ITEMS.get(i).clazz == "key")


def test_the_lua_copy_is_current():
    assert KB_ITEMS_LUA.read_text(encoding="utf-8") == render_items_lua(read_items(PROTOCOL_DIR)), \
        "run python -m kb_protocol.catalog --write from tools/"


def test_a_bad_catalogue_is_refused():
    good = read_items()
    bad = json.loads(json.dumps(good)); bad["items"]["potion"]["class"] = "snack"
    assert [str(p) for p in PROTOCOL.check_items(bad)] == ["/items/potion/class: enum"]
    bad = json.loads(json.dumps(good)); bad["items"]["Potion"] = bad["items"]["potion"]
    assert [str(p) for p in PROTOCOL.check_items(bad)] == ["/items: pattern"]
