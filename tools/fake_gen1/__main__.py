"""Run fake_gen1 by hand: python -m fake_gen1 [--port 27151] [--game red] (from tools/).

Type commands to drive the connected Minecraft client:

    pos X Y [DIR]          teleport within the current map
    walk DIR [N]           walk N cells as if playing in Gen1Recomp's window
    face DIR               turn
    mode MODE              idle | screen | forced | cutscene | busy
    warp MAP X Y DIR       warp (TEST_TOWN, TEST_HOUSE)
    text LINE | LINE ...   open a one-page text box
    flag NAME on|off       set or clear a story flag
    give [DEX] [LEVEL] [SOURCE]   send a made-up party.give (default 7 5 gift)
    battle [KIND]          send a made-up battle.start (trainer, rival, static, surf, fish)
    counts                 print how many of each message type the client has sent
    status                 print the connection and world state
    drop                   drop the connection (the client should reconnect)
    quit
"""
import argparse
import asyncio
import shlex
import sys

from .server import FakeGen1


async def _commands(fake):
    loop = asyncio.get_running_loop()
    while True:
        line = await loop.run_in_executor(None, sys.stdin.readline)
        if not line:
            return
        try:
            words = shlex.split(line)
        except ValueError:
            print("? bad quoting")
            continue
        if not words:
            continue
        cmd, args = words[0], words[1:]
        try:
            if cmd == "quit":
                return
            elif cmd == "pos":
                await fake.teleport(fake.player.map, int(args[0]), int(args[1]), args[2] if len(args) > 2 else None)
            elif cmd == "walk":
                await fake.walk(args[0], int(args[1]) if len(args) > 1 else 1)
            elif cmd == "face":
                fake.player.facing = args[0]
                await fake.teleport(fake.player.map, fake.player.x, fake.player.y, args[0])
            elif cmd == "mode":
                await fake.set_mode(args[0])
            elif cmd == "warp":
                await fake.warp_to(args[0], int(args[1]), int(args[2]), args[3])
            elif cmd == "text":
                lines = [part.strip() for part in " ".join(args).split("|")]
                await fake.open_text([lines])
            elif cmd == "flag":
                await fake.set_flag(args[0], args[1] == "on")
            elif cmd == "give":
                await fake.give(int(args[0]) if args else 7, int(args[1]) if len(args) > 1 else 5,
                                args[2] if len(args) > 2 else "gift")
            elif cmd == "battle":
                await fake.start_battle(args[0] if args else "trainer")
            elif cmd == "counts":
                print("counts " + " ".join(f"{t}={n}" for t, n in sorted(fake.counts().items())))
            elif cmd == "status":
                print(f"connected={fake.connected} role={fake.role} connections={fake.connections} "
                      f"player={fake.player.as_data()} mode={fake.state.mode}")
            elif cmd == "drop":
                await fake.drop()
            else:
                print("? unknown command (see python -m fake_gen1 --help)")
        except (IndexError, ValueError, KeyError):
            print("? bad arguments")


async def _main(args):
    async with FakeGen1(args.host, args.port, game=args.game, timeout_s=args.timeout, log=print) as fake:
        await _commands(fake)


def main(argv=None):
    parser = argparse.ArgumentParser(prog="python -m fake_gen1", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=27151)
    parser.add_argument("--game", choices=("red", "blue"), default="red")
    parser.add_argument("--timeout", type=float, default=10.0, help="heartbeat timeout in seconds")
    parser.add_argument("--write-bundle", metavar="DIR",
                        help="write the synthetic export as a bundle under DIR/<game>/ and exit")
    args = parser.parse_args(argv)
    if args.write_bundle:
        from .bundle import write_bundle
        print(f"wrote {write_bundle(args.write_bundle, args.game)}")
        return
    asyncio.run(_main(args))


if __name__ == "__main__":
    main()
