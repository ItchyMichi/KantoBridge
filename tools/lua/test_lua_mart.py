"""The Mart through the generic M4 menus (M7), on the simulated engine (fake_center.lua's ShopMenu):
the BUY/SELL/QUIT menu with its money box and the clerk's line, the dialogue lists with money, the
quantity box with its unit price, the YES/NO confirm, a purchase and a sale changing money and the bag
(the lists and the menu sent again with their ids when money or the clerk's line changes), the
cancels, not enough money, an unsellable item, the "anything else?" loop and QUIT. Every message the
peer receives is schema-checked (rig.Peer, strict). All data is made up (centersim.py: BALL 200,
MEDS 300)."""

import pytest

from centersim import CLERK, CenterGame

pytestmark = pytest.mark.lua


def at_the_mart(money=3000):
    g = CenterGame(start=("TEST_MART", 2, 3, "up"))
    save = g.E.save
    save.inventory = g.b.to_lua({"BOULDERBADGE": True})
    save.bagOrder = g.b.table()
    g.E.Bag.add(save, b"POTION", 3)
    g.E.Bag.add(save, b"TOWN_MAP", 1)
    save.money = money
    g.run(2)
    return g


def shop_menu(g):
    """Talks to the clerk across the counter: the greeting, then the menu. Returns (id, data)."""
    g.send("act.interact", CLERK)
    g.run(2)
    assert g.received("act.result")[-1]["data"] == {"ok": True, "kind": "npc"}
    return g.wait_ui("menu", screen="ShopMenu")


def pick(g, ui_id, index):
    g.send("ui.pick", {"ui": ui_id, "index": index})
    g.run(2)


def a_list(g):
    return g.wait_ui("list")


def opens_of(g, ui_id, after=0):
    return [m["data"] for m in g.log[after:] if m["type"] == "ui.open" and m["data"]["ui"] == ui_id]


def status(g):
    st = g.status()
    return st["money"], st["bagTotal"]


def test_the_shop_menu_carries_the_money_box_and_the_clerks_line():
    g = at_the_mart()
    menu_id, menu = shop_menu(g)
    texts = [m["data"] for m in g.received("ui.open") if m["data"]["kind"] == "text"]
    assert texts[0]["stay"] is True, "the greeting stays up under the menu, no press"
    assert menu["items"] == [{"label": "BUY"}, {"label": "SELL"}, {"label": "QUIT"}]
    assert menu["money"] == 3000 and menu["footer"] == "HELLO! CAN I HELP?" and menu["index"] == 0
    assert g.mode() == "screen"


def test_buying_takes_money_adds_to_the_bag_and_reopens_the_list():
    g = at_the_mart()
    money0, bag0 = status(g)
    menu_id, _ = shop_menu(g)
    pick(g, menu_id, 0)                                   # BUY
    list_id, lst = a_list(g)
    assert lst["money"] == 3000 and lst["footer"] == "PICK AWAY."
    assert lst["items"] == [{"label": "BALL", "right": "¥200"}, {"label": "MEDS", "right": "¥300"},
                            {"label": "CANCEL"}]
    pick(g, list_id, 0)                                   # BALL
    qty_id, qty = g.wait_ui("quantity")
    assert (qty["qty"], qty["max"], qty["unit"]) == (1, 99, 200)
    mark = len(g.log)
    g.send("ui.qty", {"ui": qty_id, "qty": 2})
    g.run(2)
    text_id, text = g.wait_ui("text")
    assert text["choice"] is True and text["pages"] == [["THAT'S 400.", "OK?"]]
    choice_id, _ = g.wait_ui("choice")
    g.send("ui.pick", {"ui": choice_id, "index": 0})      # YES
    g.run(20)
    g.read_top_text()                                     # "HERE YOU GO!"
    g.run(3)
    top_id, _ = g.top_ui()
    assert top_id == list_id, "back on the list, the quantity box gone"
    assert qty_id not in g.open_uis()
    # the list and the menu under it were sent again with their ids and the new money
    assert [d["money"] for d in opens_of(g, list_id, mark)] == [2600]
    assert [d["money"] for d in opens_of(g, menu_id, mark)] == [2600]
    assert status(g) == (money0 - 400, bag0 + 2)
    assert g.received("bag.sync")[-1]["data"]["items"] == {"POKE_BALL": 2, "POTION": 3}
    assert g.received("snapshot")[-1]["data"]["money"] == 3000
    g.send("resync")
    g.run(2)
    assert g.received("snapshot")[-1]["data"]["money"] == 2600, "the snapshot's money"


def test_cancels_change_nothing():
    g = at_the_mart()
    before = status(g)
    menu_id, _ = shop_menu(g)
    pick(g, menu_id, 0)
    list_id, _ = a_list(g)
    # B at the quantity box
    pick(g, list_id, 1)
    qty_id, _ = g.wait_ui("quantity")
    g.send("ui.back", {"ui": qty_id})
    g.run(3)
    assert g.top_ui()[0] == list_id and qty_id not in g.open_uis()
    # NO at the confirm
    pick(g, list_id, 1)
    qty_id, _ = g.wait_ui("quantity")
    g.send("ui.qty", {"ui": qty_id, "qty": 3})
    g.run(2)
    choice_id, _ = g.wait_ui("choice")
    g.send("ui.back", {"ui": choice_id})
    g.run(20)
    assert g.top_ui()[0] == list_id and qty_id not in g.open_uis()
    assert status(g) == before
    # the CANCEL row: back to the menu, "anything else?"
    mark = len(g.log)
    pick(g, list_id, 2)
    top_id, menu = g.wait_ui("menu", screen="ShopMenu")
    assert top_id == menu_id and list_id not in g.open_uis()
    assert [d["footer"] for d in opens_of(g, menu_id, mark)] == ["ANYTHING ELSE?"]
    assert menu["index"] == 0
    assert status(g) == before


def test_not_enough_money_goes_back_to_the_menu():
    g = at_the_mart(money=300)
    menu_id, _ = shop_menu(g)
    pick(g, menu_id, 0)
    list_id, _ = a_list(g)
    pick(g, list_id, 0)
    qty_id, _ = g.wait_ui("quantity")
    g.send("ui.qty", {"ui": qty_id, "qty": 2})
    g.run(2)
    choice_id, _ = g.wait_ui("choice")
    g.send("ui.pick", {"ui": choice_id, "index": 0})
    g.run(20)
    g.read_top_text()                                     # "TOO POOR."
    g.run(10)
    top_id, menu = g.top_ui()
    assert top_id == menu_id and list_id not in g.open_uis() and qty_id not in g.open_uis()
    assert menu["footer"] == "ANYTHING ELSE?"
    assert status(g)[0] == 300


def test_selling_pays_and_updates_the_rows():
    g = at_the_mart()
    money0, bag0 = status(g)
    menu_id, _ = shop_menu(g)
    pick(g, menu_id, 1)                                   # SELL
    list_id, lst = a_list(g)
    assert lst["items"] == [{"label": "MEDS", "right": "×3"}, {"label": "MAP"}, {"label": "CANCEL"}]
    pick(g, list_id, 0)
    qty_id, qty = g.wait_ui("quantity")
    assert (qty["max"], qty["unit"]) == (3, 150)
    mark = len(g.log)
    g.send("ui.qty", {"ui": qty_id, "qty": 1})
    g.run(2)
    choice_id, _ = g.wait_ui("choice")
    g.send("ui.pick", {"ui": choice_id, "index": 0})
    g.run(20)
    assert g.top_ui()[0] == list_id
    again = opens_of(g, list_id, mark)
    assert again and again[-1]["money"] == money0 + 150
    assert again[-1]["items"][0] == {"label": "MEDS", "right": "×2"}
    assert status(g) == (money0 + 150, bag0 - 1)
    assert g.received("bag.sync")[-1]["data"]["items"] == {"POTION": 2}


def test_an_unsellable_item_is_refused():
    g = at_the_mart()
    before = status(g)
    menu_id, _ = shop_menu(g)
    pick(g, menu_id, 1)
    list_id, _ = a_list(g)
    pick(g, list_id, 1)                                   # the key item
    g.read_top_text()                                     # "NO PRICE ON THAT."
    g.run(10)
    assert g.top_ui()[0] == menu_id and list_id not in g.open_uis()
    assert status(g) == before


def test_quit_says_goodbye_and_hands_back_the_world():
    g = at_the_mart()
    menu_id, _ = shop_menu(g)
    pick(g, menu_id, 2)                                   # QUIT
    g.read_top_text()
    g.run(5)
    assert not g.open_uis() and g.mode() == "idle"
    assert g.E.ow.npcs[1].frozen is False
    assert g.status()["uiReopened"] == 0, "nothing changed while open"
