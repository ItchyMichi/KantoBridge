"""Several links (MULTI_GAME_PLAN §4.5, MG4): one FakeMinecraft per hidden game, one followed, the rest parked.

The stand-in for the client's link manager: ``LinksDriver`` connects to every ``game:port`` entry, says
hello on each, marks a link ``wrong_game`` when ``hello.ok.game`` isn't the game the entry named (such a link
is never followed), follows one link (``follow``, else the first that checked out) and keeps the others
parked: with ``park`` it sends ``region.park`` to each parked link and ``region.resume`` to a parked link
that becomes followed, as the real client does at a pass. Steps go to the followed link only; the
heartbeat runs on every link. A parked link that drops never disturbs the followed one.

Everything is synthetic; logs show games, ports, states and counts only.
"""
import asyncio
import re

from kb_protocol import load_games

from .client import FakeMinecraft, HelloRejected

ENTRY = re.compile(r"^(?:([a-z][a-z0-9_]{0,15}):)?([0-9]{1,5})$")
STATES = ("idle", "connecting", "down", "rejected", "wrong_game", "followed", "parked")


def parse_links(text):
    """``"blue:27151,emerald:27152"`` (a bare port is a legacy link whose game is learnt from hello.ok)
    -> [(game or None, port), ...]."""
    out = []
    for part in text.split(","):
        part = part.strip()
        if not part:
            continue
        match = ENTRY.match(part)
        if not match:
            raise ValueError(f"bad link {part!r}: want game:port or port")
        port = int(match.group(2))
        if not 1 <= port <= 65535:
            raise ValueError(f"bad port in {part!r}")
        out.append((match.group(1), port))
    if not out:
        raise ValueError("no links")
    return out


class Link:
    def __init__(self, game, port, host, role, ping_ms, log, strict=True):
        self.configured = game        # the entry's game, or None for a legacy link
        self.game = game              # learnt from hello.ok for a legacy link
        self.port = port
        self.client = FakeMinecraft(host, port, role=role, strict=strict, ping_ms=ping_ms, log=log)
        self.state = "idle"
        self.hello_ok = None
        self.region = load_games().region(game) if game else None

    @property
    def connected(self):
        return self.state in ("followed", "parked", "wrong_game") and not self.client.eof

    def label(self):
        return f"{self.configured or self.game or '?'}:{self.port}"

    def counts(self):
        return dict(self.client.counts)

    def latest(self, msg_type):
        for message in reversed(self.client.received):
            if message["type"] == msg_type:
                return message
        return None


class LinksDriver:
    def __init__(self, entries, host="127.0.0.1", role="test", follow=None, park=False, log=None, ping_ms=2000,
                 attempts=10, strict=True):
        self.log = log or (lambda line: None)
        self.links = [Link(game, port, host, role, ping_ms, self.log, strict) for game, port in entries]
        self.wanted = follow
        self.park_parked = park
        self.attempts = attempts
        self.followed = None
        self.events = []  # (link label, what)

    # --- connecting ----------------------------------------------------------------------------

    async def connect(self):
        """Connects and greets every link, decides the followed one, parks the rest (with ``park``)."""
        for link in self.links:
            link.state = "connecting"
            try:
                await link.client.connect(attempts=self.attempts)
                ok = await link.client.hello()
            except HelloRejected as exc:
                link.state = "rejected"
                self.events.append((link.label(), f"rejected {exc.reason}"))
                self.log(f"link {link.label()}: hello rejected ({exc.reason})")
                continue
            except (OSError, ConnectionError, asyncio.TimeoutError):
                link.state = "down"
                self.events.append((link.label(), "down"))
                self.log(f"link {link.label()}: not reachable")
                continue
            link.hello_ok = ok
            running = ok["data"]["game"]
            if link.configured is None:
                link.game = running   # a legacy link learns its game
                link.region = load_games().region(running)
            if link.configured is not None and running != link.configured:
                link.state = "wrong_game"
                self.events.append((link.label(), f"wrong_game {running}"))
                self.log(f"link {link.label()}: port {link.port} runs {running}, expected {link.configured}: never followed")
            else:
                link.state = "parked"
        candidates = [l for l in self.links if l.state == "parked"]
        chosen = None
        if self.wanted is not None:
            chosen = next((l for l in candidates if l.game == self.wanted), None)
            if chosen is None:
                self.log(f"links: no link runs {self.wanted}; following the first that checked out")
        if chosen is None and candidates:
            chosen = candidates[0]
        if chosen is not None:
            chosen.state = "followed"
            self.followed = chosen
            self.events.append((chosen.label(), "followed"))
        if self.park_parked:
            for link in self.links:
                if link.state == "parked":
                    await self.park(link)
        self.log("links: " + ", ".join(f"{l.label()} {l.state}" for l in self.links))
        return self

    # --- the acts ---------------------------------------------------------------------------------

    async def park(self, link, timeout=5.0):
        """region.park on a connected link; the act.result data (ok, or the refusal)."""
        reply = await link.client.request("region.park", {"region": link.region or "kanto"}, "act.result", timeout)
        self.events.append((link.label(), "park " + ("ok" if reply["data"]["ok"] else reply["data"].get("reason", "refused"))))
        return reply["data"]

    async def resume(self, link, to=None, timeout=5.0):
        """region.resume onto ``to`` (default: the parked link's away cell from its latest snapshot, facing down)."""
        if to is None:
            snapshot = link.latest("snapshot")
            away = snapshot["data"].get("away") if snapshot else None
            if away is None:
                # a fresh snapshot shows the hold (snapshot.away) when the link is parked
                snapshot = await link.client.request("resync", {}, "snapshot", timeout)
                away = snapshot["data"].get("away")
            if away is None:
                raise RuntimeError(f"link {link.label()} is not parked (no away cell)")
            to = {"map": away["map"], "x": away["x"], "y": away["y"], "facing": snapshot["data"]["player"].get("facing", "down")}
        reply = await link.client.request("region.resume", {"region": link.region or "kanto", "to": to}, "act.result", timeout)
        self.events.append((link.label(), "resume " + ("ok" if reply["data"]["ok"] else reply["data"].get("reason", "refused"))))
        return reply["data"]

    async def follow(self, game):
        """Swap: park the followed link (with ``park``), resume the one running ``game``, follow it.
        False for an unknown, wrong_game or dropped link (the followed one is unchanged)."""
        target = next((l for l in self.links if l.game == game and l.state in ("parked", "followed")), None)
        if target is None or target.client.eof:
            self.events.append((game, "follow refused"))
            self.log(f"links: cannot follow {game}: no connected link runs it (or it runs another game)")
            return False
        if target is self.followed:
            return True
        old = self.followed
        if old is not None and self.park_parked and not old.client.eof:
            await self.park(old)
        if old is not None:
            old.state = "parked"
        if self.park_parked:
            await self.resume(target)
        target.state = "followed"
        self.followed = target
        self.events.append((target.label(), "followed"))
        return True

    async def step(self, direction, n=1, timeout=5.0):
        """A move.step on the followed link; its answer (player.pos, or player.blocked)."""
        if self.followed is None:
            raise RuntimeError("no followed link")
        client = self.followed.client
        message = await client.send("move.step", {"dir": direction, "n": n})
        deadline = asyncio.get_running_loop().time() + timeout
        while True:
            left = deadline - asyncio.get_running_loop().time()
            if left <= 0:
                raise asyncio.TimeoutError("no answer to move.step")
            reply = await client.next(left)
            if reply is None:
                raise ConnectionError("the followed link closed")
            # gen1-bridge answers with the step's own seq as `re`; the stand-in sends the position without one
            if reply["type"] in ("player.pos", "player.blocked") and reply.get("re", message["seq"]) == message["seq"]:
                return reply

    # --- bookkeeping ------------------------------------------------------------------------------

    def refresh(self):
        """Marks links whose connection ended as down (a parked drop never pauses the followed one)."""
        for link in self.links:
            if link.state in ("followed", "parked", "wrong_game") and link.client.eof:
                link.state = "down"
                self.events.append((link.label(), "dropped"))
                if link is self.followed:
                    self.followed = None
        return self

    def summary(self):
        self.refresh()
        return [{"game": link.game, "configured": link.configured, "port": link.port, "state": link.state,
                 "counts": link.counts()} for link in self.links]

    async def close(self):
        for link in self.links:
            await link.client.close()
