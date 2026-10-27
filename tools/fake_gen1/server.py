"""fake_gen1: listens like gen1-bridge and plays a synthetic world (docs/PROTOCOL.md).

It follows gen1-bridge's connection rules: one client at a time (a new one replaces the old),
hello first, a snapshot after hello.ok, pong for ping, and a drop after the heartbeat timeout.
Movement, warps and text boxes are simplified but use the real message shapes.

MG2 (several games, docs/PROTOCOL.md "Regions"): ``state`` plays the hidden game's playthrough
(``title``: not in the game, ``fresh``: a New Game never saved, ``saved``), ``game.status`` follows the
snapshot, a bind, a save, a reload and every marker or badge change, and the region acts are
answered: ``region.park`` holds the player (steps refused, ``snapshot.away``), ``region.resume``
releases the hold (a warp onto a route map or the away cell), ``region.start`` starts a game at its
title (CONTINUE when ``slot_has_save``, else NEW GAME).

Foreign Pokémon (docs/specs/FOREIGN_POKEMON_PLAN.md §8, FP1): ``hello.ok.compat.visitor`` says whether the fake
registered its stand-in (``visitor``, default true); a ``party.sync`` member the game can't show as itself (``dex`` 0,
past ``compat.maxDex``, or any form in a Gen 1 game) is a visitor unless the last ``session.bind`` said ``leave_out``,
and ``game.status.visitors`` counts them (sent again when the count changes). The helpers ``use_item``, ``order``,
``change``, ``hold``, ``learn``, ``forget`` and ``take`` send what the hidden game reports.
"""
import asyncio
import secrets

from kb_protocol import load_games, load_protocol
from kb_protocol.aio import STREAM_LIMIT, Connection
from kb_protocol.framing import FramingError

from . import synth
from .world import DIRS, MapDef, Player, State, Story, build_world

PROTOCOL_NUMBER = 1
BRIDGE_VERSION = "fake_gen1-0.1.0"
ENGINE_VERSION = "0.0.0-fake"
ROLES = ("client", "builder", "test")
WALK_FRAMES = 16
BIKE_FRAMES = 8  # BIKE_PLAN B1: a ride walks on at the bike's frames
PLAYTHROUGHS = ("title", "fresh", "saved")
START_FRAMES = 10  # frames a region.start takes to reach the overworld
ROUTE_MAP_PREFIX = "KB_ROUTE_"  # the bridge's route maps: the only warp targets a resume accepts


class FakeGen1:
    def __init__(self, host="127.0.0.1", port=27151, game="red", timeout_s=10.0, log=None, state="saved",
                 slot_has_save=True, visitor=True):
        if state not in PLAYTHROUGHS:
            raise ValueError(f"state must be one of {PLAYTHROUGHS}")
        self.host = host
        self.port = port
        self.game = game
        # MG1: the game's catalogue entry (family, compat) for hello.ok; an id outside the catalogue is
        # allowed here so tests can play a bridge whose game the client doesn't know
        self.catalogue = load_games().get(game)
        self.timeout_s = timeout_s
        self.log = log or (lambda line: None)
        self.protocol = load_protocol()
        self.world = build_world()
        self.exports = synth.resources(game)
        self.export_index = synth.index(game, ENGINE_VERSION, BRIDGE_VERSION, self.exports)
        self.player = Player()
        self.state = State()
        self.ride = {"dir": None, "b": False}  # BIKE_PLAN B1: move.ride's held intent
        self._ride_task = None
        self.rides = 0
        self.story = Story()
        self.session = "s-" + secrets.token_hex(4)
        self.server = None
        self.conn = None
        self.role = None
        self.connections = 0
        self.received = []
        self.ui_counter = 0
        self.open_ui = None
        # Save pairs (M10): the save the fake game "runs from" and the Pokémon operations sent since.
        self.pair = secrets.token_hex(8)
        self.saved_at = 1790000000
        self.ops = 0
        self.save_counter = 0
        self.pc_counter = 0
        self.pc_open = None
        # A session.bind that waits before session.bound: (reason, seconds), used once.
        self.bind_wait = None
        # MG2: the playthrough (title: not in the game), whether the slot a region.start would CONTINUE
        # holds a save, the region.park hold (the cell parked on), and the markers' last values
        self.playthrough = state
        self.slot_has_save = slot_has_save
        self.slot = 1
        self.slots = {1}  # WS2: the slots the fake engine has (a recorded slot outside them is refused)
        self.last_bind = {}
        self.away = None
        self.markers = dict(self.catalogue.markers) if self.catalogue is not None else {}
        self._status_sent = None
        self._start_task = None
        # FP1: the stand-in registered (hello.ok.compat.visitor), the bind's choice, the last party.sync's members
        self.visitor = visitor
        self.incompatible = "visitor"
        self.party = []
        self.effect_counter = 0
        # WORLD_SESSIONS_PLAN WS1: where route.leave takes the hidden player home, per zone (map, x, y, facing);
        # a zone without an entry goes back to the synthetic town's start cell
        self.route_back = {}
        self._route_sent = None

    # --- lifecycle -------------------------------------------------------------------------------

    async def start(self):
        self.server = await asyncio.start_server(self._on_client, self.host, self.port, limit=STREAM_LIMIT)
        self.port = self.server.sockets[0].getsockname()[1]
        self.log(f"fake_gen1 listening on {self.host}:{self.port} (game {self.game})")
        return self

    async def stop(self):
        if self.conn is not None:
            await self.conn.close()
        if self.server is not None:
            self.server.close()
            await self.server.wait_closed()

    async def __aenter__(self):
        return await self.start()

    async def __aexit__(self, *exc):
        await self.stop()

    @property
    def connected(self):
        return self.conn is not None and not self.conn.closed and self.role is not None

    async def drop(self):
        """Close the current connection, as when Gen1Recomp restarts."""
        if self.conn is not None:
            await self.conn.close()

    # --- connection handling ---------------------------------------------------------------------

    async def _on_client(self, reader, writer):
        if self.conn is not None:
            self.log("new connection replaces the old one")
            await self.conn.close()
        conn = Connection(reader, writer, protocol=self.protocol, send_leg="g2m", recv_leg="m2g", strict=False)
        self.conn = conn
        self.role = None
        self.connections += 1
        try:
            await self._serve(conn)
        except FramingError as exc:
            self.log(f"framing error {exc.code}; dropping the connection")
        except (ConnectionError, OSError):
            pass
        finally:
            await conn.close()
            if self.conn is conn:
                self.conn = None
                self.role = None
                self.open_ui = None

    async def _recv(self, conn):
        try:
            return await conn.recv(timeout=self.timeout_s)
        except asyncio.TimeoutError:
            self.log(f"no message for {self.timeout_s:g} s; dropping the connection")
            return None

    async def _serve(self, conn):
        hello = await self._recv(conn)
        if hello is None:
            return
        self._note(hello)
        if hello["type"] != "hello":
            await conn.send("error", {"code": "no_hello", "msg": "hello must come first"}, re=hello["seq"])
            return
        data = hello["data"]
        if data.get("protocol") != PROTOCOL_NUMBER:
            await conn.send("hello.reject", {"reason": "protocol", "protocol": PROTOCOL_NUMBER}, re=hello["seq"])
            return
        role = data.get("role")
        if isinstance(role, str) and role not in ROLES:
            # Like gen1-bridge: a well-formed but unknown role is "role"; anything else wrong is "malformed".
            await conn.send("hello.reject", {"reason": "role", "protocol": PROTOCOL_NUMBER}, re=hello["seq"])
            return
        if conn.problems or role not in ROLES:
            await conn.send("hello.reject", {"reason": "malformed", "protocol": PROTOCOL_NUMBER}, re=hello["seq"])
            return
        self.role = data["role"]
        ok = {"protocol": PROTOCOL_NUMBER, "bridge": BRIDGE_VERSION, "engine": ENGINE_VERSION,
              "tested": ENGINE_VERSION, "game": self.game, "session": self.session,
              "union_room": "off"}  # ENGINE_UPGRADE_PLAN U3: the real bridge says what it found
        if self.catalogue is not None:  # MG1: the family and the adapter's limits, as gen1-bridge sends them
            ok["family"] = self.catalogue.family
            ok["compat"] = dict(self.catalogue.compat, visitor=self.visitor)
        await conn.send("hello.ok", ok, re=hello["seq"])
        await conn.send("snapshot", self.snapshot())
        await conn.send("game.status", self.game_status())  # MG2: the progress follows every snapshot
        while True:
            before = len(conn.problems)  # recv() records a failed schema check
            message = await self._recv(conn)
            if message is None:
                return
            self._note(message)
            if len(conn.problems) > before:
                await conn.send("error", {"code": "bad_message", "msg": "failed schema check"}, re=message["seq"])
                continue
            if await self._dispatch(conn, message) == "close":
                return

    def _note(self, message):
        self.received.append(message)
        self.log(f"rx {message.get('type')} #{message.get('seq')}")

    async def _dispatch(self, conn, message):
        msg_type, data, seq = message["type"], message["data"], message["seq"]
        if msg_type == "ping":
            await conn.send("pong", {"t": data["t"]}, re=seq)
        elif msg_type == "resync":
            await conn.send("snapshot", self.snapshot(), re=seq)
            await conn.send("game.status", self.game_status())
        elif msg_type == "move.step":
            await self._step(conn, data["dir"], data["n"])
        elif msg_type == "move.ride":
            await self._ride(conn, data.get("dir"), bool(data.get("b")))
        elif msg_type == "move.face":
            self.player.facing = data["dir"]
            await conn.send("player.pos", self._pos("sync", 0))
        elif msg_type == "act.interact":
            await self._interact(conn, message)
        elif msg_type == "warp.take":
            await self._take(conn, message)
        elif msg_type == "act.button":
            await conn.send("act.result", {"ok": True}, re=seq)
        elif msg_type == "act.field":
            await conn.send("act.result", {"ok": False, "reason": "unsupported"}, re=seq)
        elif msg_type == "ui.advance":
            await self._advance(conn, data["ui"])
        elif msg_type in ("ui.pick", "ui.back", "ui.qty", "ui.name"):
            await self._close_ui(conn, data["ui"])
        elif msg_type == "session.bind":
            # FP1: the region's choice for members the game lacks (absent: visitor)
            self.incompatible = data.get("incompatible", "visitor")
            # WORLD_SESSIONS_PLAN WS2/WS3: the recorded slot, the first-join choice and what to do after
            self.last_bind = dict(data)
            # A waiting bind answers later without blocking pings.
            self._bind_task = asyncio.create_task(self._bind(conn, seq))
        elif msg_type == "party.sync":
            # FP1: the shadow party is Cobblemon's, member for member; game.status follows a new visitor count
            before = self.visitors()
            self.party = list(data["party"])
            if self.ingame and self.visitors() != before:
                await conn.send("game.status", self.game_status())
        elif msg_type == "pc.closed":
            if self.pc_open == data["pc"]:
                self.pc_open = None
        elif msg_type == "region.park":
            await self._park(conn, seq)
        elif msg_type == "region.resume":
            await self._resume(conn, seq, data["to"])
        elif msg_type == "region.start":
            await self._start(conn, seq)
        elif msg_type == "route.leave":
            await self._leave(conn, seq, data["zone"])
        elif msg_type in ("export.index", "export.get"):
            await self._export(conn, message)
        elif msg_type == "debug.status":
            if self.role != "test":
                await conn.send("error", {"code": "test_only", "msg": "debug.* needs role test"}, re=seq)
            else:
                await conn.send("debug.status", self.status(), re=seq)
        elif msg_type == "debug.quit":
            return "close"
        elif msg_type == "hello":
            await conn.send("error", {"code": "already_hello", "msg": "hello was already accepted"}, re=seq)
        # Everything else (dex.sync, battle.result, act.result, ...) is only recorded.
        return None

    async def _bind(self, conn, seq):
        """session.bind: fake_gen1 has one slot. With bind_wait set it first says why it waits, like
        gen1-bridge switching saves (session.waiting), then answers. save.loaded follows the answer."""
        switched = "none"
        bind = getattr(self, "last_bind", {}) or {}
        slot = bind.get("slot")
        if slot is not None and slot not in self.slots:
            # WS2: a recorded slot the engine no longer has
            await conn.send("error", {"code": "session.no_slot", "msg": "session.no_slot"}, re=seq)
            return
        save = bind.get("save")
        if slot is not None:
            self.slot = slot
        elif save in ("copy", "new"):
            self.slot = max(self.slots) + 1
            self.slots.add(self.slot)
            self.slot_has_save = save == "copy" and self.slot_has_save
        if self.bind_wait is not None:
            reason, seconds = self.bind_wait
            self.bind_wait = None
            await conn.send("session.waiting", {"slot": 1, "reason": reason}, re=seq)
            await asyncio.sleep(seconds)
            switched = "title"
        bound = {"slot": self.slot, "switched": switched}
        if save is not None:
            bound["save"] = save  # WS2: the choice applied
        await conn.send("session.bound", bound, re=seq)
        await conn.send("game.status", self.game_status())  # MG2: after session.bound
        await conn.send("save.loaded", self.loaded())

    def loaded(self):
        if self.playthrough == "fresh":  # a New Game never saved has no pair yet
            return {"fresh": True, "ops": self.ops}
        return {"fresh": False, "pair": self.pair, "at": self.saved_at, "ops": self.ops}

    # --- regions (MG2) ---------------------------------------------------------------------------

    @property
    def ingame(self):
        return self.playthrough != "title"

    @property
    def parked(self):
        return self.away is not None

    def game_status(self):
        """game.status as gen1-bridge builds it (observe/kb_progress.lua): the playthrough, the badge count
        and the game profile's markers, each a flag of the synthetic story."""
        data = {"game": self.game, "playthrough": self.playthrough, "ingame": self.ingame,
                "started": self.playthrough != "title", "badges": len(self.story.badges),
                "markers": {name: flag in self.story.flags for name, flag in sorted(self.markers.items())},
                "slot": self.slot}
        if self.ingame:
            data["name"] = "TESTER"
            data["visitors"] = self.visitors()  # FP1
        elif self.slot_has_save:
            # WS2: at the title, the slot's save summary (what a CONTINUE would load)
            data["save"] = {"slot": self.slot, "name": "TESTER", "badges": len(self.story.badges)}
        return data

    # --- foreign Pokémon (FP1) ---------------------------------------------------------------------

    def is_visitor(self, member):
        """A party.sync member this game can't show as itself: no national number, past the catalogue's
        maxDex, or (Gen 1, which has no forms) any form."""
        max_dex = self.catalogue.compat["maxDex"] if self.catalogue is not None else 151
        family = self.catalogue.family if self.catalogue is not None else "gen1"
        return member["dex"] == 0 or member["dex"] > max_dex or (family == "gen1" and "form" in member)

    def visitors(self):
        """How many members are visitors (0 when the stand-in didn't register or the region leaves them out)."""
        if not self.visitor or self.incompatible == "leave_out":
            return 0
        return sum(1 for member in self.party if self.is_visitor(member))

    def _effect_id(self, prefix):
        self.effect_counter += 1
        return f"{prefix}-fake-{self.effect_counter}"

    async def use_item(self, item, uid, kind, move=None, forget=None):
        """An item from the hidden bag used on a member (party.item with its id and kind); returns the id."""
        data = {"item": item, "uid": uid, "id": self._effect_id("it"), "kind": kind}
        if move is not None:
            data["move"] = move
        if forget is not None:
            data["forget"] = forget
        await self._send("party.item", data)
        return data["id"]

    async def order(self, uids):
        """A SWITCH in the hidden party menu (party.order)."""
        await self._send("party.order", {"uids": list(uids)})

    async def change(self, uid, **values):
        """An effect outside items (party.change: hp, status, friendship); returns the id."""
        data = {"uid": uid, "id": self._effect_id("ch"), **values}
        await self._send("party.change", data)
        return data["id"]

    async def hold(self, uid, item):
        """GIVE (a canonical id) or TAKE (None) in the hidden party menu (party.hold); returns the id."""
        data = {"uid": uid, "item": item, "id": self._effect_id("hd")}
        await self._send("party.hold", data)
        return data["id"]

    async def learn(self, uid, move, forget=None):
        """A Move Tutor or the Move Reminder (party.learn); returns the id."""
        data = {"uid": uid, "move": move, "id": self._effect_id("ln")}
        if forget is not None:
            data["forget"] = forget
        await self._send("party.learn", data)
        return data["id"]

    async def forget(self, uid, move):
        """The Move Deleter (party.forget); returns the id."""
        data = {"uid": uid, "move": move, "id": self._effect_id("fg")}
        await self._send("party.forget", data)
        return data["id"]

    async def take(self, uid, reason="trade"):
        """The hidden game takes a member away (party.take: trade, daycare, quest)."""
        await self._send("party.take", {"uid": uid, "reason": reason})
        if self.connected:
            self.ops += 1

    async def _status_if_changed(self):
        """game.status when a marker or the badge count changed since the last one sent."""
        now = self.game_status()
        key = (now["badges"], tuple(sorted(now["markers"].items())))
        if key != self._status_sent:
            self._status_sent = key
            await self._send("game.status", now)

    def route_state(self):
        """WS1: {zone, map} while the player stands on a bridge route map (KB_ROUTE_<ID>, the zone the id lowercased; a
        connecting route's far end KB_ROUTE_<ID>_TO is the same zone), else None."""
        m = self.player.map
        if not m.startswith(ROUTE_MAP_PREFIX):
            return None
        zone = m[len(ROUTE_MAP_PREFIX):].lower()
        if zone.endswith("_to"):
            zone = zone[:-3]
        return {"zone": zone, "map": m}

    def state_data(self):
        """player.state as gen1-bridge sends it: the state plus (WS1) route while on a route map."""
        data = self.state.as_data()
        route = self.route_state() if self.ingame else None
        if route:
            data["route"] = route
        return data

    async def _route_changed(self):
        """WS1: a player.state goes out when the route state changed (gen1-bridge's rule)."""
        now = self.route_state() if self.ingame else None
        if now != self._route_sent:
            self._route_sent = now
            await self._send("player.state", self.state_data())

    async def _leave(self, conn, seq, zone):
        """route.leave (WS1, D9): only on that zone's route map; the back warp (kind script) follows the answer."""
        here = self.route_state() if self.ingame else None
        if here is None or here["zone"] != zone:
            await conn.send("act.result", {"ok": False, "kind": "route.leave", "reason": "not_on_route"}, re=seq)
            return
        if not self._idle() or self.parked:
            await conn.send("act.result", {"ok": False, "kind": "route.leave", "reason": "busy"}, re=seq)
            return
        await conn.send("act.result", {"ok": True, "kind": "route.leave"}, re=seq)
        back = self.route_back.get(zone) or ("TEST_TOWN", 5, 8, "down")
        await self.warp_to(*back, kind="script")

    def _idle(self):
        return self.ingame and self.state.mode == "idle"

    async def _park(self, conn, seq):
        """region.park: hold the player where it stands (a park while parked is ok again)."""
        if not self.ingame:
            await conn.send("act.result", {"ok": False, "kind": "park", "reason": "not_ingame"}, re=seq)
        elif self.parked:
            await conn.send("act.result", {"ok": True, "kind": "park"}, re=seq)
        elif not self._idle():
            await conn.send("act.result", {"ok": False, "kind": "park", "reason": "not_idle"}, re=seq)
        else:
            self.away = self._cell()
            await conn.send("act.result", {"ok": True, "kind": "park"}, re=seq)

    async def _resume(self, conn, seq, to):
        """region.resume: release the hold; a target other than a route map or the away cell is refused
        (story safety, MULTI_GAME_PLAN §4.13); another cell is a warp of kind script."""
        if not self.ingame:
            await conn.send("act.result", {"ok": False, "kind": "resume", "reason": "not_ingame"}, re=seq)
            return
        if not self.parked:
            await conn.send("act.result", {"ok": False, "kind": "resume", "reason": "not_parked"}, re=seq)
            return
        if not (to["map"].startswith(ROUTE_MAP_PREFIX) or to["map"] == self.away["map"]):
            await conn.send("act.result", {"ok": False, "kind": "resume", "reason": "bad_target"}, re=seq)
            return
        self.away = None
        if (to["map"], to["x"], to["y"]) != (self.player.map, self.player.x, self.player.y):
            await self.warp_to(to["map"], to["x"], to["y"], to["facing"], "script")
        else:
            self.player.facing = to["facing"]
        await conn.send("act.result", {"ok": True, "kind": "resume"}, re=seq)

    async def _start(self, conn, seq):
        """region.start: from the title only; CONTINUE when the slot holds a save, else NEW GAME. The game
        reaches its overworld START_FRAMES later: a sync, the map's NPCs, save.loaded and game.status."""
        if self.ingame or (self._start_task is not None and not self._start_task.done()):
            reason = "busy" if not self.ingame else "not_title"
            await conn.send("act.result", {"ok": False, "kind": "start", "reason": reason}, re=seq)
            return
        detail = "continue" if self.slot_has_save else "new"
        await conn.send("act.result", {"ok": True, "kind": "start", "detail": detail}, re=seq)
        self._start_task = asyncio.create_task(self._reach_overworld(detail))

    async def _reach_overworld(self, detail):
        await asyncio.sleep(START_FRAMES / 60)
        self.playthrough = "saved" if detail == "continue" else "fresh"
        if detail == "new":
            self.ops = 0
        await self._send("player.pos", self._pos("sync", 0))
        await self._send("npc.set", self._npc_set())
        await self._send("save.loaded", self.loaded())
        await self._send("game.status", self.game_status())

    async def start_game(self):
        """The CLI's startgame: as a region.start from the title (no answer to send)."""
        if self.ingame:
            return False
        detail = "continue" if self.slot_has_save else "new"
        await self._reach_overworld(detail)
        return True

    async def set_badge(self, item, on=True):
        """A badge gained or lost (the badges are bag items in Gen 1); game.status follows a change."""
        if on and item not in self.story.badges:
            self.story.badges.append(item)
        elif not on and item in self.story.badges:
            self.story.badges.remove(item)
        await self._status_if_changed()

    # --- world behaviour -------------------------------------------------------------------------

    def _map(self):
        here = self.world.get(self.player.map)
        if here is None:
            # a map outside the synthetic world: a bridge route map a region.resume warped onto (MG2), a
            # 2x2 block with nothing on it, like gen1-bridge's KB_ROUTE_<ID>
            here = self.world[self.player.map] = MapDef(self.player.map, 2, 2)
        return here

    def _pos(self, kind, frames, n=None):
        data = dict(self.player.as_data(), kind=kind, frames=frames)
        if n is not None:
            data["n"] = n
        return data

    def _flags(self, x, y):
        exported = self.exports.get(("map", self.player.map))
        if exported is None:  # a route map outside the synthetic export: no doors, no warp tiles
            return 0
        cells = exported["cells"]
        return cells[y][x] if 0 <= y < len(cells) and 0 <= x < len(cells[0]) else 0

    async def _ride(self, conn, direction, b):
        """move.ride (BIKE_PLAN B1): the held intent; while a direction is held on the bike and idle, the player walks on
        one cell each BIKE_FRAMES (player.pos kind bike), until released or refused. No answer."""
        self.rides += 1
        self.ride = {"dir": direction, "b": b}
        if direction and (self._ride_task is None or self._ride_task.done()):
            self._ride_task = asyncio.create_task(self._ride_loop(conn))

    async def _ride_loop(self, conn):
        while self.ride["dir"] and self.state.bike and self.state.mode == "idle" and self.ingame:
            direction = self.ride["dir"]
            dx, dy = DIRS[direction]
            self.player.facing = direction
            x, y = self.player.x + dx, self.player.y + dy
            if self._map().walkable(x, y) and self._map().npc_at(x, y) is None:
                self.player.x, self.player.y = x, y
                await conn.send("player.pos", self._pos("bike", BIKE_FRAMES))
            await asyncio.sleep(BIKE_FRAMES / 60)

    async def _step(self, conn, direction, n):
        if self.ride["dir"]:
            # a ride direction is held: steps are refused (the hidden game drives)
            await conn.send("player.blocked", dict(self._cell(), dir=direction, reason="script", n=n))
            return
        self.player.facing = direction
        # not in the game, a region.park hold (MG2) or a screen: the hidden game takes no steps
        if not self.ingame or self.parked or self.state.mode != "idle":
            await conn.send("player.blocked", dict(self._cell(), dir=direction, reason="script", n=n))
            return
        dx, dy = DIRS[direction]
        x, y = self.player.x + dx, self.player.y + dy
        here = self._map()
        if not here.walkable(x, y):
            exit_warp = here.warps.get((self.player.x, self.player.y))
            if exit_warp is not None:
                # Like Gen 1's exit mats: a step off the edge from a warp cell takes the warp. There is
                # no visible step, so the step's answer is the arrival's sync (with its n).
                await self.warp_to(exit_warp.to_map, exit_warp.to_x, exit_warp.to_y, exit_warp.facing,
                                   exit_warp.kind, n=n, walk_out=exit_warp.walk_out)
                return
            reason = "bounds" if not (0 <= x < here.width and 0 <= y < here.height) else "tile"
            await conn.send("player.blocked", dict(self._cell(), dir=direction, reason=reason, n=n))
            return
        if here.npc_at(x, y) is not None:
            await conn.send("player.blocked", dict(self._cell(), dir=direction, reason="entity", n=n))
            return
        self.player.x, self.player.y = x, y
        await conn.send("player.pos", self._pos("walk", WALK_FRAMES, n))
        warp = here.warps.get((x, y))
        # A warp fires on arrival only on a door or warp tile (engine Warp rules).
        if warp is not None and self._flags(x, y) & (synth.F_DOOR | synth.F_WARPTILE):
            await self.warp_to(warp.to_map, warp.to_x, warp.to_y, warp.facing, warp.kind,
                               walk_out=warp.walk_out)

    async def _export(self, conn, message):
        seq = message["seq"]
        if self.role not in ("builder", "test"):
            await conn.send("error", {"code": "role", "msg": "export needs role builder"}, re=seq)
        elif message["type"] == "export.index":
            await conn.send("export.index", self.export_index, re=seq)
        else:
            kind, rid = message["data"]["kind"], message["data"]["id"]
            data = self.exports.get((kind, rid))
            if data is None:
                await conn.send("error", {"code": "not_found", "msg": "no such export resource"}, re=seq)
                return
            entry = next(e for e in self.export_index["resources"] if (e["kind"], e["id"]) == (kind, rid))
            await conn.send("export.data", {"kind": kind, "id": rid, "hash": entry["hash"], "data": data}, re=seq)

    def _cell(self):
        return {"map": self.player.map, "x": self.player.x, "y": self.player.y}

    async def _interact(self, conn, message):
        data = message["data"]
        target = (data["x"], data["y"])
        here = self._map()
        npc = here.npc_at(*target) if data["map"] == self.player.map else None
        lines = None
        if npc is not None and npc.text:
            lines = npc.text
        elif data["map"] == self.player.map and target in here.signs:
            lines = here.signs[target]
        if lines is None:
            await conn.send("act.result", {"ok": False, "reason": "nothing_there"}, re=message["seq"])
            return
        await conn.send("act.result", {"ok": True, "kind": "npc" if npc else "sign"}, re=message["seq"])
        await self.open_text([list(lines)])

    async def _advance(self, conn, ui):
        if not self.open_ui or self.open_ui["ui"] != ui:
            return
        self.open_ui["page"] += 1
        if self.open_ui["page"] >= self.open_ui["pages"]:
            await self._close_ui(conn, ui)
        else:
            await conn.send("ui.update", {"ui": ui, "page": self.open_ui["page"], "line": 0, "waiting": True})

    async def _close_ui(self, conn, ui):
        if self.open_ui and self.open_ui["ui"] == ui:
            self.open_ui = None
            self.state.mode = "idle"
            await conn.send("ui.close", {"ui": ui})
            await conn.send("player.state", self.state_data())

    # --- actions for the CLI and tests (no-ops while nobody is connected) -----------------------

    async def _send(self, msg_type, data=None):
        if self.connected:
            await self.conn.send(msg_type, data)

    async def teleport(self, map_id, x, y, facing=None):
        self.player.map, self.player.x, self.player.y = map_id, x, y
        if facing:
            self.player.facing = facing
        await self._route_changed()
        await self._send("player.pos", self._pos("sync", 0))

    async def walk(self, direction, steps=1, frames=WALK_FRAMES, fps=60):
        """Walk as if the user played in Gen1Recomp's own window (blocked steps are skipped)."""
        dx, dy = DIRS[direction]
        for _ in range(steps):
            self.player.facing = direction
            x, y = self.player.x + dx, self.player.y + dy
            if self._map().walkable(x, y) and self._map().npc_at(x, y) is None:
                self.player.x, self.player.y = x, y
                await self._send("player.pos", self._pos("walk", frames))
            await asyncio.sleep(frames / fps)

    async def set_mode(self, mode):
        self.state.mode = mode
        await self._send("player.state", self.state_data())

    async def _take(self, conn, message):
        """warp.take, a cave shaft's warp (docs/PROTOCOL.md "Ladders and holes (H5)"): taken while idle, on the
        take's map, within a cell of its near end; then the warp, kind stairs or hole, with the take's re."""
        data, seq = message["data"], message["seq"]
        near = data["from"]
        reason = None
        if not self._idle() or self.parked:
            reason = "mode"
        elif self.player.map != near["map"]:
            reason = "map"
        elif abs(self.player.x - near["x"]) + abs(self.player.y - near["y"]) > 1:
            reason = "far"
        if reason is not None:
            await conn.send("warp.taken", {"ok": False, "reason": reason}, re=seq)
            return
        await conn.send("warp.taken", {"ok": True}, re=seq)
        kind = "hole" if data["kind"] == "hole" else "stairs"
        to = data["to"]
        await conn.send("warp", {"phase": "start", "kind": kind}, re=seq)
        self.player.map, self.player.x, self.player.y, self.player.facing = to["map"], to["x"], to["y"], to["facing"]
        await conn.send("warp", {"phase": "arrive", "kind": kind, "to": self.player.as_data()}, re=seq)
        await self._send("player.pos", self._pos("sync", 0))
        await self._send("npc.set", self._npc_set())

    async def warp_to(self, map_id, x, y, facing, kind="door", n=None, walk_out=None):
        """A warp as gen1-bridge reports it: start, arrive at the entry cell, a sync (the step's
        answer when the warp had no visible step), the new map's NPCs, then any door walk-out."""
        await self._send("warp", {"phase": "start", "kind": kind})
        self.player.map, self.player.x, self.player.y, self.player.facing = map_id, x, y, facing
        await self._route_changed()
        await self._send("warp", {"phase": "arrive", "kind": kind, "to": self.player.as_data()})
        await self._send("player.pos", self._pos("sync", 0, n))
        await self._send("npc.set", self._npc_set())
        if walk_out:
            dx, dy = DIRS[walk_out]
            self.player.x, self.player.y, self.player.facing = x + dx, y + dy, walk_out
            await self._send("player.pos", self._pos("script", WALK_FRAMES))

    async def open_text(self, pages):
        self.ui_counter += 1
        ui = self.ui_counter
        self.open_ui = {"ui": ui, "page": 0, "pages": len(pages)}
        self.state.mode = "screen"
        await self._send("player.state", self.state_data())
        await self._send("ui.open", {"ui": ui, "kind": "text", "pages": pages,
                                     "cont": [[False] * len(page) for page in pages]})
        return ui

    async def give(self, dex=7, level=5, source="gift"):
        """A made-up gift, as gen1-bridge sends one (party.give)."""
        self.gift_counter = getattr(self, "gift_counter", 0) + 1
        await self._send("party.give", {"give": f"g-fake-{self.gift_counter}", "dex": dex, "level": level,
                                        "moves": [33], "source": source})
        if self.connected:
            self.ops += 1

    async def save(self, reason="menu"):
        """A made-up hidden-game save (save.begin): a new save pair, operations counted from zero. A fresh
        playthrough becomes a saved one (MG2: game.status follows)."""
        self.save_counter += 1
        self.pair = secrets.token_hex(8)
        self.saved_at += 60
        self.ops = 0
        await self._send("save.begin", {"save": f"sv-fake-{self.save_counter}", "reason": reason,
                                        "pair": self.pair, "at": self.saved_at})
        if self.playthrough == "fresh":
            self.playthrough = "saved"
        self.slot_has_save = True
        await self._send("game.status", self.game_status())

    async def reload_save(self):
        """As if the hidden game went back to its last save (crash and CONTINUE): save.loaded with ops 0."""
        self.ops = 0
        if self.playthrough == "fresh":
            self.playthrough = "saved"
        await self._send("save.loaded", self.loaded())
        await self._send("game.status", self.game_status())

    async def open_pc(self):
        """Bill's PC chosen (pc.open); the matching pc.closed ends it."""
        self.pc_counter += 1
        self.pc_open = f"pc-fake-{self.pc_counter}"
        await self._send("pc.open", {"pc": self.pc_open})

    async def start_battle(self, kind="trainer"):
        """A made-up trainer battle (battle.start); the answer arrives as battle.result."""
        self.battle_counter = getattr(self, "battle_counter", 0) + 1
        data = {"battle": f"b-fake-{self.battle_counter}", "kind": kind, "rules": {"catch": False, "flee": False}}
        if kind in ("trainer", "rival", "scripted"):
            data["trainer"] = {"cls": "OPP_TEST", "party": 1, "name": "TESTER", "map": self.player.map,
                               "x": self.player.x, "y": max(self.player.y - 2, 0)}
            data["team"] = [{"dex": 16, "level": 4, "moves": [33], "dvs": {"atk": 9, "def": 8, "spd": 8, "spc": 8}}]
        else:
            data["wild"] = {"dex": 19, "level": 3}
        await self._send("battle.start", data)

    def counts(self):
        """Message counts received from the client, by type."""
        out = {}
        for message in self.received:
            out[message.get("type")] = out.get(message.get("type"), 0) + 1
        return out

    async def set_flag(self, flag, on=True):
        (self.story.flags.add if on else self.story.flags.discard)(flag)
        await self._send("story.flags", {"set": [flag] if on else [], "clear": [] if on else [flag]})
        await self._status_if_changed()  # MG2: a marker flag changed

    # --- views -----------------------------------------------------------------------------------

    def _npc_set(self):
        return {"map": self.player.map, "list": [npc.as_data() for npc in self._map().npcs]}

    def snapshot(self):
        story = self.story
        data = {
            "ingame": self.ingame,  # false at the title (MG2 state title): player is the save's position
            "player": self.player.as_data(),
            "state": self.state_data(),
            "flags": sorted(story.flags),
            "toggles": story.toggles,
            "blocks": [],
            "lit": [],
            "bag": dict(story.bag),
            "money": story.money,
            "coins": story.coins,
            "badges": list(story.badges),
            "party": list(story.party),
            "dex": {"seen": sorted(story.seen), "owned": sorted(story.owned)},
            "npcs": self._npc_set(),
        }
        if self.parked:  # MG2: the hold is up
            data["away"] = dict(self.away)
        route = self.route_state()  # WS1: in game, or at the title the save's position
        if route:
            data["route"] = route
        self._route_sent = route if self.ingame else None
        self._status_sent = (len(story.badges), tuple(sorted((n, f in story.flags) for n, f in self.markers.items())))
        return data

    def status(self):
        top = "FakeTitle" if not self.ingame else "FakeOverworld" if not self.open_ui else "FakeTextBox"
        return {"npcs": len(self._map().npcs), "topState": top,
                "mode": self.state.mode, "flags": len(self.story.flags), "connections": self.connections,
                "ops": self.ops, "pcHeld": self.pc_open is not None, "ingame": self.ingame,
                # MG2: the hold (debug.status carries counts and booleans only; the playthrough is game.status's)
                "regionParked": self.parked,
                # ENGINE_UPGRADE_PLAN UP1 / U3: what the real bridge reports at game.ready
                "engineMissing": [], "unionRoom": "off"}
