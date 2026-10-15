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


def test_every_item_has_a_symbol_and_gen1_ids_derive_from_theirs():
    """A Gen 1 item's id is its gen1 symbol in lower case; MG7 (G3F): an item only Gen 3 has (SUN_STONE, Emerald's
    TMs for moves no Gen 1 TM teaches) is named by its own symbol or move; every symbol maps back to its item."""
    for item_id in ITEMS.ids:
        item = ITEMS.get(item_id)
        assert item.symbols, item_id
        if "gen1" in item.symbols:
            assert item.symbols["gen1"].lower() == item_id, item_id
        for family, symbol in item.symbols.items():
            assert ITEMS.canonical(family, symbol) == item_id, (family, symbol)


def test_the_mirror_set_is_todays_29_battle_items():
    mirrored = ITEMS.mirrored()
    assert len(mirrored) == 29
    assert {ITEMS.get(i).cobblemon for i in mirrored} >= {"poke_ball", "potion", "elixir", "paralyze_heal", "x_defence", "x_special_attack"}
    assert "rare_candy" not in mirrored and "moon_stone" not in mirrored


def test_machines_cross_by_move_and_keys_never_cross():
    tms = [i for i in ITEMS.ids if ITEMS.get(i).clazz == "tm"]
    assert len({ITEMS.get(i).move for i in tms}) == len(tms), "one entry per move"
    gen1 = [i for i in tms if "gen1" in ITEMS.get(i).symbols]
    assert len(gen1) == 50 and all(1 <= ITEMS.get(i).move <= 165 for i in gen1)
    # MG7 (G3F): Emerald's 50 TMs, TM01..TM50; the 14 whose move a Gen 1 TM teaches share its entry
    gen3 = {ITEMS.get(i).symbols["gen3"]: i for i in tms if "gen3" in ITEMS.get(i).symbols}
    assert sorted(gen3) == ["TM%02d" % n for n in range(1, 51)]
    assert all(1 <= ITEMS.get(i).move <= 354 for i in gen3.values())
    assert gen3["TM06"] == "tm_toxic" and gen3["TM01"] == "tm_focus_punch"
    assert len(set(gen1) & set(gen3.values())) == 14
    assert ITEMS.get("tm_mega_punch").move == 5 and ITEMS.get("tm_substitute").move == 164
    # MG9 (G2F): Crystal's 50 TMs, TM_<MOVE>; 28 share an entry with a Gen 1 or Gen 3 TM, 22 are Gen 2's own
    gen2 = {ITEMS.get(i).symbols["gen2"]: i for i in tms if "gen2" in ITEMS.get(i).symbols}
    assert len(gen2) == 50 and all(s.startswith("TM_") for s in gen2)
    assert all(1 <= ITEMS.get(i).move <= 251 for i in gen2.values())
    assert gen2["TM_TOXIC"] == "tm_toxic" and gen2["TM_DYNAMICPUNCH"] == "tm_dynamic_punch"
    assert len([i for i in gen2.values() if set(ITEMS.get(i).symbols) == {"gen2"}]) == 22
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


def test_gen2s_items():
    """MG9 (G2F): Crystal's symbols on the items it shares (THUNDERSTONE, PARLYZ_HEAL, ELIXER), and its own items by
    their modern names: held items and berries (Cobblemon's item where a held item crosses), balls, medicine and
    valuables the ledger keeps; Minecraft's mirror stays Gen 1's 29."""
    assert ITEMS.canonical("gen2", "THUNDERSTONE") == "thunder_stone"
    assert ITEMS.canonical("gen2", "PARLYZ_HEAL") == "parlyz_heal"
    assert ITEMS.canonical("gen2", "BERRY") == "oran_berry" and ITEMS.get("oran_berry").cobblemon == "oran_berry"
    assert ITEMS.canonical("gen2", "BLACKBELT_I") == "black_belt" and ITEMS.get("black_belt").clazz == "held"
    assert ITEMS.canonical("gen2", "LEVEL_BALL") == "level_ball" and ITEMS.get("level_ball").cobblemon is None
    assert ITEMS.canonical("gen2", "HM_SURF") == "hm_surf" and ITEMS.get("hm_surf").clazz == "key"
    held = [i for i in ITEMS.ids if ITEMS.get(i).clazz == "held"]
    assert len(held) == 48 and all(set(ITEMS.get(i).symbols) == {"gen2"} for i in held)
    assert all(ITEMS.get(i).cobblemon is None or "gen1" in ITEMS.get(i).symbols for i in ITEMS.mirrored())
