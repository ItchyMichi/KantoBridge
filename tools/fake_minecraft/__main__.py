"""Run fake_minecraft by hand: python -m fake_minecraft [--port 27151] [--seconds 10] (from tools/).

Connects, says hello, keeps the heartbeat going, and prints what arrives: types and sizes, or with
--verbose the messages with ROM-derived fields redacted. Ends with a count per message type.
"""
import argparse
import asyncio

from .client import FakeMinecraft


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


def main(argv=None):
    parser = argparse.ArgumentParser(prog="python -m fake_minecraft", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=27151)
    parser.add_argument("--role", choices=("client", "builder", "test"), default="test")
    parser.add_argument("--seconds", type=float, default=10.0, help="how long to stay connected")
    parser.add_argument("--attempts", type=int, default=10, help="connection attempts (with backoff)")
    parser.add_argument("--verbose", action="store_true", help="print redacted messages")
    parser.add_argument("--lenient", action="store_true", help="record schema problems instead of failing")
    asyncio.run(_main(parser.parse_args(argv)))


if __name__ == "__main__":
    main()
