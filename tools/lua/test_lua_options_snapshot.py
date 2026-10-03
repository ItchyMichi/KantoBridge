"""The display-options pin (PLAN P21), the snapshot's save-derived fields, and
the generated kb_catalog.lua."""

import json

import pytest

import gen_catalog
from rig import CONTENT, Rig

pytestmark = pytest.mark.lua

CLASSIC = {"tilt": 0, "zoom": 0, "battleLayout": b"og", "battleFit": b"fixed",
           "uiLayout": b"centered", "textSpeed": 1}


def options(rig):
    o = rig.E.save.options
    return {k: o[k.encode()] for k in CLASSIC}


# ------------------------------------------------------------ the pin


def test_game_ready_pins_and_reapplies():
    rig = Rig()
    assert options(rig) == CLASSIC
    o = rig.E.save.options
    assert o.pipelines.test_voxel == 0 and o.pipelines.test_off == 0
    assert o.colors == b"gbc", "colours are left alone"
    assert list(rig.E.applied.values()) == [b"Tilt", b"Pipelines", b"Zoom"]
    assert "[info] [gen1-bridge] options.pinned changed=7" in rig.logs()


def test_new_game_options_are_pinned_before_the_engine_applies_them():
    rig = Rig()
    applied = len(rig.E.applied)
    fresh = rig.b.to_lua({"options": {"tilt": 3, "zoom": 2, "uiLayout": "dynamic", "textSpeed": 5,
                                       "pipelines": {"test_voxel": 1}}})
    payload = rig.b.table()
    payload[b"save"] = fresh
    rig.b.emit("save.created", payload)
    assert fresh.options.tilt == 0 and fresh.options.zoom == 0 and fresh.options.textSpeed == 1
    assert fresh.options.uiLayout == b"centered" and fresh.options.pipelines.test_voxel == 0
    assert len(rig.E.applied) == applied, "Game:startNewGame applies them itself right after"


def test_continue_repins_and_reapplies():
    rig = Rig()
    rig.E.save.options.tilt = 2
    rig.E.save.options.battleLayout = b"wide"
    applied = len(rig.E.applied)
    rig.b.emit("save.loaded", rig.b.table())
    assert options(rig) == CLASSIC
    assert len(rig.E.applied) == applied + 3


def test_drift_is_repinned_within_a_second():
    rig = Rig()
    rig.E.save.options.zoom = -2  # e.g. a zoom hotkey in Gen1Recomp's window
    rig.step(60)
    assert options(rig) == CLASSIC


# ------------------------------------------------------------ the snapshot


def snapshot(rig, role="test"):
    peer = rig.connect()
    peer.hello(role=role)
    msgs = peer.messages()
    assert [m["type"] for m in msgs][:2] == ["hello.ok", "snapshot"]
    # then the observers' follow-ups: the map's npc.set, the open UIs again (M4), the mirrored
    # bag (M6)
    assert {m["type"] for m in msgs[2:]} <= {"npc.set", "ui.open", "log", "bag.sync"}
    return msgs[1]["data"]


def test_snapshot_at_the_title_uses_the_save_position():
    rig = Rig()
    rig.E.push(rig.E.menu(b"TitleState"))
    data = snapshot(rig)
    assert data["player"] == {"map": "TEST_HOUSE", "x": 3, "y": 6, "facing": "down"}
    assert data["state"]["mode"] == "screen"
    assert data["npcs"] == {"map": "TEST_HOUSE", "list": []}


def test_snapshot_mid_step_reports_the_target():
    rig = Rig()
    rig.world()
    rig.E.startStep(b"right", 16)
    data = snapshot(rig)
    assert data["player"] == {"map": "TEST_TOWN", "x": 6, "y": 6, "facing": "right"}


def test_snapshot_party_dex_toggles_lit_and_npcs():
    rig = Rig()
    rig.world("TEST_CAVE", 2, 2, "up")
    E, b = rig.E, rig.b
    E.save.party = b.to_lua([
        {"species": "TESTMON", "level": 5, "hp": 18, "stats": {"hp": 20}, "status": "PSN",
         "moves": [{"id": "TEST_MOVE"}, {"id": "UNKNOWN_MOVE"}, {"id": "OTHER_MOVE"}]},
        {"species": "UNKNOWNMON", "level": 3, "moves": []},
    ])
    E.save.pokedex = b.to_lua({"seen": {}, "owned": {}})
    for species in ("TESTMON", "OTHERMON", "BIGMON", "UNKNOWNMON"):
        E.save.pokedex.seen[species.encode()] = True
    E.save.pokedex.owned[b"TESTMON"] = True
    # effective visibility (M8): only objects whose visibility differs from their record's default
    E.data.maps = b.to_lua({
        "TEST_TOWN": {"id": "TEST_TOWN", "width": 1, "height": 1, "blocks": [0], "objects": [
            {"index": 1, "name": "TESTTOWN_BALL", "item": "POTION"},
            {"index": 2, "name": "TESTTOWN_GIRL", "hidden": True},
            {"index": 3, "name": "TESTTOWN_BOY"},
        ]},
        "TEST_CAVE": {"id": "TEST_CAVE", "width": 1, "height": 1, "blocks": [0], "objects": []},
    })
    E.save.objectToggles = b.to_lua({"TEST_TOWN": {}, "TEST_CAVE": {}})
    E.save.objectToggles.TEST_TOWN.TESTTOWN_BALL = False
    E.save.objectToggles.TEST_TOWN.TESTTOWN_GIRL = True
    E.save.objectToggles.TEST_TOWN.TESTTOWN_BOY = True  # its default: not listed
    E.save.flashLit = True
    E.save.coins = 120
    E.npc(b"TEST_CAVE_obj_1", b"SPRITE_BOULDER", 4, 4)
    data = snapshot(rig)
    assert data["party"] == [{"dex": 7, "level": 5, "moves": [33, 39], "hp": 18, "maxhp": 20, "status": "psn"}]
    assert data["dex"] == {"seen": [7, 16], "owned": [7]}  # 251 is beyond Gen 1
    assert data["toggles"] == {"TEST_TOWN": {"TESTTOWN_BALL": False, "TESTTOWN_GIRL": True}}
    assert data["lit"] == ["TEST_CAVE"] and data["state"]["dark"] and data["state"]["lit"]
    assert data["coins"] == 120
    assert data["npcs"]["map"] == "TEST_CAVE"
    assert data["npcs"]["list"] == [{"id": "TEST_CAVE_obj_1", "sprite": "SPRITE_BOULDER", "kind": "boulder",
                                     "x": 4, "y": 4, "facing": "down", "moving": False}]
    assert any("snapshot.dropped" in line for line in rig.logs()), "the unknown species is counted"


def test_snapshot_leaves_out_values_that_dont_fit():
    rig = Rig()
    rig.world()
    E, b = rig.E, rig.b
    E.save.inventory = b.to_lua({"POTION": 3, "BAD ITEM": 1, "TOO_MANY": 123456})
    E.save.flags[b"bad flag!"] = True
    E.save.money = -5
    data = snapshot(rig)
    assert data["bag"] == {"POTION": 3, "TOO_MANY": 9999}
    assert data["flags"] == ["EVENT_TEST_ONE"] and data["money"] == 0


def test_resync_snapshot_matches_the_live_view():
    rig = Rig()
    rig.world()
    peer = rig.connect()
    peer.hello()
    peer.messages()
    rig.E.startStep(b"up", 16)
    rig.step(20)
    rig.E.land()
    rig.step()
    peer.messages()
    seq = peer.send("resync")
    rig.step()
    snap, npcs, bag = peer.messages()
    assert npcs["type"] == "npc.set" and npcs["data"] == {"map": "TEST_TOWN", "list": []}
    assert bag["type"] == "bag.sync" and bag["data"] == {"items": {"POTION": 2}}
    assert snap["re"] == seq and snap["data"]["player"] == {"map": "TEST_TOWN", "x": 5, "y": 5, "facing": "up"}


def test_content_lookups_use_the_supported_api():
    assert set(CONTENT) == {"pokemon", "moves"}


# ------------------------------------------------------------ kb_catalog.lua


def test_kb_catalog_is_current():
    assert gen_catalog.main(["--check"]) == 0, "run tools/lua/gen_catalog.py"


def test_kb_catalog_matches_the_protocol_catalog():
    rig = Rig()
    cat = rig.b.module("lib/kb_catalog.lua")
    real = json.loads(gen_catalog.CATALOG.read_text(encoding="utf-8"))
    assert cat.protocol == real["protocol"] and cat.maxLineBytes == real["limits"]["maxLineBytes"]
    lua_types = {k.decode(): {leg.decode() for leg, v in t.items() if v is True} for k, t in cat.types.items()}
    assert lua_types == {k: set(v["legs"]) for k, v in real["messages"].items()}
