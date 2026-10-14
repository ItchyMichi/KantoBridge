"""Run fake_gen1 by hand: python -m fake_gen1 [--port 27151] [--game red] (from tools/).

--game takes any id of the game catalogue (protocol/games.json); hello.ok then carries that id, its
family and its compat profile, while the synthetic world and export stay Gen 1-shaped.
--state title|fresh|saved (MG2) is the playthrough the fake starts in: at the title it is not in the
game (snapshot ingame false, steps refused) until a region.start; --no-slot-save makes that start a
NEW GAME (act.result detail new) instead of CONTINUE.

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
    save [REASON]          a made-up hidden-game save (save.begin, menu or center)
    reload                 as if the hidden game went back to its last save (save.loaded, ops 0)
    pc                     choose Bill's PC (pc.open)
    waitbind REASON SECS   make the next session.bind wait (session.waiting busy|new_game|save_failed)
    badge ITEM on|off      gain or lose a badge (game.status follows)
    park                   hold the player as region.park does (steps refused, snapshot.away)
    resume [MAP X Y DIR]   release the hold, warping to the cell when one is given
    startgame              leave the title as a region.start does (CONTINUE or NEW GAME)
    state                  print the playthrough, the hold and the game.status data
    counts                 print how many of each message type the client has sent
    status                 print the connection and world state
    drop                   drop the connection (the client should reconnect)
    quit
"""
import argparse
import asyncio
import shlex
import sys

from kb_protocol import load_games

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
            elif cmd == "save":
                await fake.save(args[0] if args else "menu")
            elif cmd == "reload":
                await fake.reload_save()
            elif cmd == "pc":
                await fake.open_pc()
            elif cmd == "waitbind":
                fake.bind_wait = (args[0], float(args[1]))
            elif cmd == "badge":
                await fake.set_badge(args[0], args[1] == "on")
            elif cmd == "park":
                if fake.ingame and not fake.parked:
                    fake.away = fake._cell()
                print(f"parked={fake.parked} away={fake.away}")
            elif cmd == "resume":
                fake.away = None
                if args:
                    await fake.warp_to(args[0], int(args[1]), int(args[2]), args[3], "script")
                print("parked=False")
            elif cmd == "startgame":
                print(f"started={await fake.start_game()} playthrough={fake.playthrough}")
            elif cmd == "state":
                print(f"playthrough={fake.playthrough} parked={fake.parked} status={fake.game_status()}")
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
    async with FakeGen1(args.host, args.port, game=args.game, timeout_s=args.timeout, log=print, state=args.state,
                        slot_has_save=args.slot_has_save) as fake:
        await _commands(fake)


def main(argv=None):
    parser = argparse.ArgumentParser(prog="python -m fake_gen1", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=27151)
    # any catalogued game id (protocol/games.json, MG1); the synthetic world stays Gen 1-shaped
    parser.add_argument("--game", choices=load_games().ids, default="red")
    parser.add_argument("--timeout", type=float, default=10.0, help="heartbeat timeout in seconds")
    # MG2: the playthrough the fake starts in, and whether a region.start finds a save to CONTINUE
    parser.add_argument("--state", choices=("title", "fresh", "saved"), default="saved",
                        help="the playthrough at start: title (not in the game), fresh or saved")
    parser.add_argument("--no-slot-save", dest="slot_has_save", action="store_false",
                        help="a region.start from the title runs NEW GAME instead of CONTINUE")
    parser.add_argument("--variant", choices=["pass"], default=None,
                        help="MG8: with --write-bundle and a Gen 3 game, the town with a Wild Routes end")
    parser.add_argument("--write-bundle", metavar="DIR",
                        help="write the synthetic export as a bundle under DIR/<game>/ and exit")
    args = parser.parse_args(argv)
    if args.write_bundle:
        from .bundle import write_bundle
        print(f"wrote {write_bundle(args.write_bundle, args.game, args.variant)}")
        return
    asyncio.run(_main(args))


if __name__ == "__main__":
    main()
