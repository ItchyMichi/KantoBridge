"""act/kb_field.lua (M8, PLAN M8 item 4: field moves from Minecraft) and kb_engine.fieldAction through
main.lua on the simulated overworld (fake_field.lua): every act.field action from the request to the
engine's own entry point to the act.result, the eligibility refusals the engine makes (the move in
the shadow party, the badge, the place, the target), the warp kinds Dig, Teleport and the Escape
Rope report, the Fly list as a list UI, Strength's boulder push through move.step, and malformed
requests. Every message the peer receives is schema-checked strictly. All maps, items and texts are
made up (fieldsim.py)."""

import pytest

from fieldsim import MAPS, FieldGame

pytestmark = pytest.mark.lua

ROCKS = {
    "rows": [
        "########",
        "#......#",
        "#......#",  # a boulder at (4,2)
        "#......#",
        "########",
    ],
    "tileset": "CAVERN",
    "npcs": [{"id": "TEST_ROCKS_obj_1", "x": 4, "y": 2, "sprite": "SPRITE_BOULDER", "pushable": True}],
}


def take(g, m_type):
    return [m["data"] for m in g.log if m["type"] == m_type]


def ready(g, *moves, badges=(), items=()):
    if moves:
        g.give_moves(*moves)
    g.give(*badges)
    for item in items:
        g.give(item, qty=2)


# ------------------------------------------------------------ Cut


def test_cut_the_tree_in_front():
    g = FieldGame()
    ready(g, "CUT", badges=("CASCADEBADGE",))
    assert g.field("cut") == {"ok": True, "kind": "cut"}
    assert [c.decode() for c in g.E.fieldCalls.values()] == ["useCutFieldMove", "tryCut"]
    g.read_texts()
    assert g.block(1, 0) == 3
    n = g.step("up")
    assert g.answer(n)["data"]["kind"] == "walk", "the tree is gone"
    assert g.status()["fieldKind_cut"] == 1


def test_cut_at_an_adjacent_cell_turns_to_it_first():
    g = FieldGame(start=("TEST_FOREST", 2, 2, "down"))
    ready(g, "CUT", badges=("CASCADEBADGE",))
    assert g.field("cut", x=2, y=0)["reason"] == "not_adjacent"
    assert g.cell()[3] == "down"
    assert g.field("cut", x=2, y=1) == {"ok": True, "kind": "cut"}
    assert g.cell()[3] == "up"


@pytest.mark.parametrize("moves, badges, start, reason", [
    ((), ("CASCADEBADGE",), ("TEST_FOREST", 2, 2, "up"), "no_move"),
    (("CUT",), (), ("TEST_FOREST", 2, 2, "up"), "no_badge"),
    (("CUT",), ("CASCADEBADGE",), ("TEST_FOREST", 4, 2, "up"), "no_target"),   # floor in front
])
def test_cut_refusals(moves, badges, start, reason):
    g = FieldGame(start=start)
    ready(g, *moves, badges=badges)
    assert g.field("cut") == {"ok": False, "reason": reason}
    assert g.block(1, 0) == 2


def test_the_engines_party_knows_decides_so_a_mod_may_allow_more():
    g = FieldGame()
    # a mod's fieldmove.eligibility hook answering for CUT (OC:2230-2237) with no member knowing it
    g.E.ow.partyKnows = g.b.eval("""function(real) return function(self, move)
        if move == 'CUT' then return { species = 'TESTMON' } end
        return real(self, move)
      end end""")(g.E.ow.partyKnows)
    assert g.field("cut") == {"ok": True, "kind": "cut"}


def test_not_idle_busy_and_not_ingame():
    g = FieldGame()
    ready(g, "CUT", badges=("CASCADEBADGE",))
    g.push(g.E.textBox(g.s("A TEST TEXT."), g.opts()))
    g.run(2)
    assert g.field("cut") == {"ok": False, "reason": "not_idle"}
    g.E.uiPop()
    first = g.send("act.field", {"action": "strength"})
    second = g.send("act.field", {"action": "cut"})
    assert g.result(second) == {"ok": False, "reason": "busy"}
    assert g.result(first)["reason"] == "no_move"
    g.E.leaveWorld()
    assert g.field("cut") == {"ok": False, "reason": "not_ingame"}


def test_a_request_waits_for_the_step_in_flight():
    g = FieldGame(start=("TEST_FOREST", 3, 3, "up"))
    ready(g, "CUT", badges=("CASCADEBADGE",))
    g.step("up")
    seq = g.send("act.field", {"action": "cut"})  # sent while the step walks
    assert g.result(seq) == {"ok": True, "kind": "cut"}
    assert g.cell()[:3] == ("TEST_FOREST", 3, 2)


# ------------------------------------------------------------ Surf


def test_surf_onto_the_water_walk_on_it_and_get_off():
    g = FieldGame(start=("TEST_FOREST", 7, 2, "right"))
    ready(g, "SURF", badges=("SOULBADGE",))
    assert g.field("surf") == {"ok": True, "kind": "surf"}
    g.read_texts()
    assert g.cell()[:3] == ("TEST_FOREST", 8, 2)
    assert take(g, "player.state")[-1]["surf"] is True
    assert g.answer(g.step("right"))["data"]["kind"] == "surf"   # walking on the water
    g.settle()
    g.answer(g.step("left"))
    g.settle()
    assert g.cell() == ("TEST_FOREST", 8, 2, "left")
    assert g.field("surf") == {"ok": True, "kind": "dismount"}
    g.settle()
    assert g.cell()[:3] == ("TEST_FOREST", 7, 2)
    assert take(g, "player.state")[-1]["surf"] is False


def test_surf_needs_water_in_front_and_the_badge():
    g = FieldGame(start=("TEST_FOREST", 4, 2, "right"))
    ready(g, "SURF")
    assert g.field("surf")["reason"] == "no_badge"
    g.give("SOULBADGE")
    assert g.field("surf")["reason"] == "no_target"
    assert g.field("surf", x=8, y=2)["reason"] == "not_adjacent"


# ------------------------------------------------------------ Strength


def test_strength_then_walking_into_a_boulder_pushes_it():
    maps = dict(MAPS)
    maps["TEST_ROCKS"] = ROCKS
    g = FieldGame(start=("TEST_ROCKS", 3, 2, "right"), maps=maps)
    ready(g, "STRENGTH", badges=("RAINBOWBADGE",))
    assert g.field("strength") == {"ok": True, "kind": "strength"}
    g.read_texts()
    assert g.field("strength") == {"ok": False, "reason": "already"}
    first = g.answer(g.step("right"))         # the first try only arms the push
    assert first["type"] == "player.blocked"
    push = g.answer(g.step("right"))
    assert (push["data"]["kind"], push["data"]["frames"]) == ("push", 0)
    g.settle()
    moves = take(g, "npc.move")
    assert [(m["id"], m["x"], m["kind"], m["frames"]) for m in moves] == [("TEST_ROCKS_obj_1", 5, "push", 32)]


# ------------------------------------------------------------ Flash


def test_flash_refusals():
    g = FieldGame(start=("VICTORY_ROAD_1F", 2, 2, "down"))
    ready(g, "FLASH")
    g.E.save.inventory[b"BOULDERBADGE"] = None
    assert g.field("flash")["reason"] == "no_badge"
    g2 = FieldGame()
    ready(g2, "FLASH", badges=("BOULDERBADGE",))
    assert g2.field("flash")["reason"] == "not_here"


# ------------------------------------------------------------ Dig, Teleport, the Escape Rope


def warps(g):
    return take(g, "warp")


def test_dig_out_of_a_cave_is_a_dig_warp_to_the_heal_point():
    g = FieldGame(start=("VICTORY_ROAD_1F", 2, 2, "down"))
    ready(g, "DIG")
    assert g.field("dig") == {"ok": True, "kind": "dig"}
    g.settle()
    assert warps(g) == [{"phase": "start", "kind": "dig"},
                        {"phase": "arrive", "kind": "dig", "to": {"map": "TEST_FOREST", "x": 2, "y": 3,
                                                                  "facing": "down"}}]


def test_the_escape_rope_is_dig_with_the_item():
    g = FieldGame(start=("VICTORY_ROAD_1F", 2, 2, "down"))
    assert g.field("dig", item="ESCAPE_ROPE") == {"ok": False, "reason": "no_item"}
    g.give("ESCAPE_ROPE", qty=2)
    assert g.field("dig", item="ESCAPE_ROPE") == {"ok": True, "kind": "rope"}
    assert g.E.save.inventory[b"ESCAPE_ROPE"] == 1
    g.settle()
    assert [w["kind"] for w in warps(g)] == ["rope", "rope"]
    assert g.field("dig", item="POTION")["reason"] == "no_item"


def test_teleport_works_only_outside_and_dig_only_inside():
    g = FieldGame()
    ready(g, "DIG", "TELEPORT")
    assert g.field("dig")["reason"] == "not_here"
    assert g.field("dig", item="ESCAPE_ROPE")["reason"] == "no_item"
    assert g.field("teleport") == {"ok": True, "kind": "teleport"}
    g.settle()
    assert [w["kind"] for w in warps(g)] == ["teleport", "teleport"]
    cave = FieldGame(start=("VICTORY_ROAD_1F", 2, 2, "down"))
    ready(cave, "TELEPORT")
    assert cave.field("teleport")["reason"] == "not_here"


def test_no_heal_point_no_escape():
    g = FieldGame(start=("VICTORY_ROAD_1F", 2, 2, "down"))
    ready(g, "DIG")
    g.E.save.lastHeal = None
    assert g.field("dig")["reason"] == "not_here"


def test_a_native_spin_out_of_a_cave_is_named_by_the_place():
    g = FieldGame(start=("VICTORY_ROAD_1F", 2, 2, "down"))
    g.E.ow.beginTeleportOut(g.E.ow)           # the party menu's DIG, on the mirror
    g.settle()
    assert [w["kind"] for w in warps(g)] == ["dig", "dig"]


# ------------------------------------------------------------ the bicycle, the rods


def test_the_bicycle_on_and_off():
    g = FieldGame(start=("TEST_FOREST", 4, 3, "down"))
    assert g.field("bicycle")["reason"] == "no_item"
    g.give("BICYCLE")
    assert g.field("bicycle") == {"ok": True, "kind": "bike_on"}
    g.read_texts()
    assert take(g, "player.state")[-1]["bike"] is True
    assert g.answer(g.step("down"))["data"]["kind"] == "bike"
    g.settle()
    assert g.field("bicycle") == {"ok": True, "kind": "bike_off"}
    g.read_texts()
    assert take(g, "player.state")[-1]["bike"] is False


def test_no_riding_indoors_or_on_the_water():
    lab = FieldGame(start=("TEST_LAB", 2, 3, "up"))
    lab.give("BICYCLE")
    assert lab.field("bicycle")["reason"] == "not_here"
    sea = FieldGame(start=("TEST_FOREST", 9, 3, "up"))
    sea.give("BICYCLE")
    sea.E.ow.player.surfing = True
    assert sea.field("bicycle")["reason"] == "not_here"


def test_fishing_takes_the_rod_asked_for_or_the_best_one():
    g = FieldGame(start=("TEST_FOREST", 7, 2, "right"))
    assert g.field("fish")["reason"] == "no_item"
    g.give("OLD_ROD", "SUPER_ROD")
    assert g.field("fish") == {"ok": True, "kind": "fish"}
    assert g.E.fishedWith == b"SUPER_ROD"
    assert g.field("fish", item="OLD_ROD") == {"ok": True, "kind": "fish"}
    assert g.E.fishedWith == b"OLD_ROD"
    assert g.field("fish", item="GOOD_ROD")["reason"] == "no_item"
    assert g.field("fish", item="POTION")["reason"] == "no_item"
    land = FieldGame(start=("TEST_FOREST", 4, 2, "right"))
    land.give("OLD_ROD")
    assert land.field("fish")["reason"] == "no_target"


# ------------------------------------------------------------ Fly


def test_fly_to_a_visited_town():
    g = FieldGame(start=("TEST_FOREST", 4, 3, "down"))
    ready(g, "FLY", badges=("THUNDERBADGE",))
    assert g.field("fly", dest="TEST_FOREST")["reason"] == "no_target"   # not visited yet
    g.E.save.visited = g.b.to_lua({"TEST_FOREST": True})
    assert g.field("fly", dest="TEST_LAB")["reason"] == "no_target"
    assert g.field("fly", dest="TEST_FOREST") == {"ok": True, "kind": "fly"}
    g.settle()
    assert warps(g) == [{"phase": "start", "kind": "fly"},
                        {"phase": "arrive", "kind": "fly", "to": {"map": "TEST_FOREST", "x": 2, "y": 3,
                                                                  "facing": "down"}}]


def test_fly_without_a_destination_opens_the_native_fly_list():
    g = FieldGame(start=("TEST_FOREST", 4, 3, "down"))
    ready(g, "FLY", badges=("THUNDERBADGE",))
    assert g.field("fly")["reason"] == "no_target"                      # nowhere visited
    g.E.save.visited = g.b.to_lua({"TEST_FOREST": True})
    assert g.field("fly") == {"ok": True, "kind": "fly_list"}
    opened = g.first("ui.open")["data"]
    assert (opened["kind"], opened["screen"], len(opened["items"])) == ("list", "TownMap", 1)
    g.send("ui.pick", {"ui": opened["ui"], "index": 0})
    g.settle()
    assert [w["kind"] for w in warps(g)] == ["fly", "fly"]


def test_no_fly_indoors():
    g = FieldGame(start=("TEST_LAB", 2, 3, "up"))
    ready(g, "FLY", badges=("THUNDERBADGE",))
    g.E.save.visited = g.b.to_lua({"TEST_FOREST": True})
    assert g.field("fly", dest="TEST_FOREST")["reason"] == "not_here"


# ------------------------------------------------------------ requests


@pytest.mark.parametrize("data", [
    {"action": "swim"}, {"action": "cut", "x": 1}, {"action": "cut", "x": 1.5, "y": 2},
    {"action": "fly", "dest": "lower_case"}, {"action": "fish", "item": 7},
])
def test_malformed_requests_are_refused(data):
    g = FieldGame()
    seq = g.send("act.field", data)
    err = g.until(lambda m: m["type"] == "error")
    assert (err["data"]["code"], err.get("re")) == ("bad_message", seq)


def test_only_client_and_test_roles_may_use_field_moves():
    g = FieldGame(connect=False)
    g.connect(role="builder")
    seq = g.send("act.field", {"action": "cut"})
    err = g.until(lambda m: m["type"] == "error")
    assert (err["data"]["code"], err.get("re")) == ("role", seq)
