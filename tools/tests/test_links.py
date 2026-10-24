"""Several links (MG4, MULTI_GAME_PLAN §4.5, §6 check 3) on the stand-ins: fake_minecraft's links driver
against two fake_gen1 processes. A follow/park swap, a parked drop that never disturbs the followed link,
and a wrong game that is never followed. Everything is synthetic and schema-checked."""
import asyncio

import pytest

from fake_gen1 import FakeGen1
from fake_minecraft.links import LinksDriver, parse_links


def run(coro):
    return asyncio.run(asyncio.wait_for(coro, 30))


def test_parse_links():
    assert parse_links("blue:27151,emerald:27152") == [("blue", 27151), ("emerald", 27152)]
    assert parse_links("27151") == [(None, 27151)], "a bare port is a legacy link"
    for bad in ("", "Blue:1", "blue:0", "blue:x", "blue:27151:2"):
        with pytest.raises(ValueError):
            parse_links(bad)


def test_follow_and_park_swap():
    async def scenario():
        red = await FakeGen1(port=0, game="red").start()
        blue = await FakeGen1(port=0, game="blue").start()
        driver = LinksDriver([("red", red.port), ("blue", blue.port)], follow="red", park=True, ping_ms=0)
        try:
            await driver.connect()
            states = {l.game: l.state for l in driver.links}
            assert states == {"red": "followed", "blue": "parked"}
            assert blue.parked and not red.parked, "the parked link's hidden player is held, the followed one isn't"

            moved = await driver.step("up")
            assert moved["type"] == "player.pos" and moved["data"]["y"] == 7
            assert (red.player.y, blue.player.y) == (7, 8), "a step reaches the followed link only"

            assert await driver.follow("blue")
            states = {l.game: l.state for l in driver.links}
            assert states == {"red": "parked", "blue": "followed"}
            assert red.parked and not blue.parked, "the swap parks the old link and resumes the new one"
            moved = await driver.step("up")
            assert moved["type"] == "player.pos" and (red.player.y, blue.player.y) == (7, 7)

            assert await driver.follow("blue"), "following the followed link is a no-op"
            assert not await driver.follow("emerald"), "no link runs it"
            for link in driver.links:
                assert not link.client.conn.problems, "every message passed its schema"
            assert [e for e in driver.events if e[1].startswith("park")] == [(f"blue:{blue.port}", "park ok"), (f"red:{red.port}", "park ok")]
        finally:
            await driver.close()
            await red.stop()
            await blue.stop()
    run(scenario())


def test_a_parked_drop_never_disturbs_the_followed_link():
    async def scenario():
        red = await FakeGen1(port=0, game="red").start()
        blue = await FakeGen1(port=0, game="blue").start()
        driver = LinksDriver([("red", red.port), ("blue", blue.port)], follow="red", park=True, ping_ms=0)
        try:
            await driver.connect()
            await blue.stop()  # the parked hidden game goes away
            await asyncio.sleep(0.2)
            moved = await driver.step("up")
            assert moved["type"] == "player.pos" and red.player.y == 7, "the followed link flows on"
            summary = {l["game"]: l["state"] for l in driver.summary()}
            assert summary == {"red": "followed", "blue": "down"}
            assert not await driver.follow("blue"), "a dropped link can't be followed"
            assert driver.followed is not None and driver.followed.game == "red"
        finally:
            await driver.close()
            await red.stop()
    run(scenario())


def test_a_wrong_game_is_never_followed():
    async def scenario():
        red = await FakeGen1(port=0, game="red").start()
        blue = await FakeGen1(port=0, game="blue").start()
        # the emerald entry points at the port where Red runs
        driver = LinksDriver([("emerald", red.port), ("blue", blue.port)], follow="emerald", ping_ms=0)
        try:
            await driver.connect()
            states = {l.configured: l.state for l in driver.links}
            assert states == {"emerald": "wrong_game", "blue": "followed"}, "the first that checked out is followed"
            assert driver.links[0].hello_ok["data"]["game"] == "red"
            assert not await driver.follow("emerald") and not await driver.follow("red")
            moved = await driver.step("up")
            assert moved["type"] == "player.pos" and (red.player.y, blue.player.y) == (8, 7)
            assert any(e[1] == "wrong_game red" for e in driver.events)
        finally:
            await driver.close()
            await red.stop()
            await blue.stop()
    run(scenario())


def test_a_legacy_link_learns_its_game():
    async def scenario():
        blue = await FakeGen1(port=0, game="blue").start()
        driver = LinksDriver([(None, blue.port)], ping_ms=0)
        try:
            await driver.connect()
            link = driver.links[0]
            assert (link.configured, link.game, link.state, link.region) == (None, "blue", "followed", "kanto")
        finally:
            await driver.close()
            await blue.stop()
    run(scenario())


def test_a_sync_reaches_every_link_and_each_bind_carries_its_regions_choice():
    # FOREIGN_POKEMON_PLAN §4.15 (FP1): parked links get every party.sync too, so a crossing shows the right
    # party at once; each link's bind carries its own region's onIncompatible
    async def scenario():
        blue = await FakeGen1(port=0, game="blue").start()
        emerald = await FakeGen1(port=0, game="emerald").start()
        driver = LinksDriver([("blue", blue.port), ("emerald", emerald.port)], follow="blue", ping_ms=0)
        try:
            await driver.connect()
            party = [{"uid": "0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b", "dex": 258, "level": 5, "moves": [33],
                      "hp": 20, "maxhp": 20, "types": ["water"]}]
            assert await driver.sync_party(party) == 2
            seqs = await driver.bind("w-fp", incompatible={"emerald": "leave_out"})
            assert len(seqs) == 2
            await asyncio.sleep(0.2)
            assert (blue.counts().get("party.sync"), emerald.counts().get("party.sync")) == (1, 1)
            assert (blue.visitors(), emerald.visitors()) == (1, 0), "Mudkip is a visitor in Blue, native in Emerald"
            assert (blue.incompatible, emerald.incompatible) == ("visitor", "leave_out")
            assert all(not link.client.conn.problems for link in driver.links)
        finally:
            await driver.close()
            await blue.stop()
            await emerald.stop()
    run(scenario())

