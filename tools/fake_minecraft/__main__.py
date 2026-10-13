"""Run fake_minecraft by hand: python -m fake_minecraft [--port 27151] [--seconds 10] (from tools/).

Connects, says hello, keeps the heartbeat going, and prints what arrives: types and sizes, or with
--verbose the messages with ROM-derived fields redacted. Ends with a count per message type.

Several links (MG4): --links blue:27151,emerald:27152 opens one connection per hidden game (a bare
port is a legacy link that learns its game from hello.ok), follows one (--follow <game>, else the first
that checked out) and keeps the others parked (--park sends region.park to them, as the real client
does at a pass); a link whose hello.ok names another game than its entry is reported as wrong_game
and never followed. Ends with each link's state and counts.
"""
import argparse
import asyncio

from .client import FakeMinecraft
from .links import LinksDriver, parse_links


async def _main(args):
    fake = FakeMinecraft(args.host, args.port, role=args.role, strict=not args.lenient, log=print,
                         verbose=args.verbose)
    await fake.connect(attempts=args.attempts)
    try:
        ok = await fake.hello()
        print(f"hello.ok: protocol {ok['data']['protocol']}, bridge {ok['data']['bridge']}, "
              f"engine {ok['data']['engine']}, game {ok['data']['game']}")
        await asyncio.sleep(args.seconds)
    finally:
        await fake.close()
    print("received: " + ", ".join(f"{t} x{n}" for t, n in sorted(fake.counts.items())))


async def _links(args):
    driver = LinksDriver(parse_links(args.links), host=args.host, role=args.role, follow=args.follow, park=args.park,
                         log=print, attempts=args.attempts, strict=not args.lenient)
    try:
        await driver.connect()
        if driver.followed is not None:
            print(f"following {driver.followed.label()}")
        await asyncio.sleep(args.seconds)
    finally:
        summary = driver.summary()
        await driver.close()
    for link in summary:
        counts = ", ".join(f"{t} x{n}" for t, n in sorted(link["counts"].items()))
        print(f"link {link['configured'] or link['game'] or '?'}:{link['port']} {link['state']}: {counts}")


def main(argv=None):
    parser = argparse.ArgumentParser(prog="python -m fake_minecraft", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=27151)
    parser.add_argument("--links", help="MG4: game:port entries, comma-separated (replaces --port)")
    parser.add_argument("--follow", help="MG4: the game to follow among --links (default: the first that checks out)")
    parser.add_argument("--park", action="store_true", help="MG4: send region.park to the parked links")
    parser.add_argument("--role", choices=("client", "builder", "test"), default="test")
    parser.add_argument("--seconds", type=float, default=10.0, help="how long to stay connected")
    parser.add_argument("--attempts", type=int, default=10, help="connection attempts (with backoff)")
    parser.add_argument("--verbose", action="store_true", help="print redacted messages")
    parser.add_argument("--lenient", action="store_true", help="record schema problems instead of failing")
    args = parser.parse_args(argv)
    asyncio.run(_links(args) if args.links else _main(args))


if __name__ == "__main__":
    main()
