"""The sandbox-parity environment itself, gen1-bridge's main.lua in it, and the
mod-folder rules modkit would enforce (MK301, MK305)."""

import json
import re

import pytest

from kblua import BRIDGE, Bridge

pytestmark = pytest.mark.lua

LIB_FILES = [
    "lib/kb_json.lua",
    "lib/kb_sha256.lua",
    "lib/kb_log.lua",
    "lib/kb_net.lua",
    "lib/kb_proto.lua",
    "lib/kb_engine.lua",
    "lib/kb_routes.lua",
    "lib/kb_catalog.lua",
    "lib/kb_games.lua",
    "lib/kb_items.lua",
    "lib/kb_session.lua",
    "lib/kb_perf.lua",
    "observe/kb_player.lua",
    "observe/kb_warp.lua",
    "observe/kb_snapshot.lua",
    "observe/kb_npcs.lua",
    "observe/kb_story.lua",
    "observe/kb_ui.lua",
    "observe/kb_mirror.lua",
    "observe/kb_bag.lua",
    "observe/kb_progress.lua",
    "act/kb_move.lua",
    "act/kb_interact.lua",
    "act/kb_field.lua",
    "act/kb_uiact.lua",
    "act/kb_battle.lua",
    "act/kb_party.lua",
    "act/kb_save.lua",
    "act/kb_pairing.lua",
    "act/kb_pc.lua",
    "act/kb_route.lua",
    "act/kb_climb.lua",
    "act/kb_region.lua",
    "act/kb_ledger.lua",
    "export/kb_cells.lua",
    "export/kb_images.lua",
    "export/kb_swapsites.lua",
    "export/kb_export.lua",
]

VERSION_OK = "{ engine = '0.3.85', modApi = 2 }"


# ------------------------------------------------------------ the environment


def test_forbidden_globals_are_absent():
    b = Bridge(strict=False)
    for name in [b"io", b"package", b"debug", b"loadfile", b"dofile", b"getfenv", b"setfenv",
                 b"newproxy", b"module", b"ffi"]:
        assert b.env[name] is None, name
    assert sorted(b.env.os.keys()) == [b"clock", b"date", b"difftime", b"time"]
    assert b.eval("rawequal")(b.env._G, b.env)  # lupa wrappers don't compare by identity


@pytest.mark.parametrize(
    "name, message",
    [
        (b"io", b"io is not available to mods"),
        (b"os", b"os is not available to mods"),
        (b"debug", b"debug is not available to mods"),
        (b"ffi", b"ffi is not available to mods"),
        (b"jit.util", b"jit.util is not available to mods"),
        (b"love.filesystem", b"love.filesystem is not available to mods"),
    ],
)
def test_require_denials(name, message):
    b = Bridge()
    ok, err = b.eval("function(env, n) return pcall(env.require, n) end")(b.env, name)
    assert ok is False and message in err


def test_socket_needs_the_network_permission():
    b = Bridge(permissions=("engine_internals",))
    ok, err = b.eval("function(env) return pcall(env.require, 'socket') end")(b.env)
    assert ok is False and b'needs the "network" permission' in err
    b2 = Bridge()
    got = b2.eval("function(env) return env.require('socket') end")(b2.env)
    assert b2.eval("rawequal")(got, b2.fake.socket)


def test_load_compiles_into_the_mod_env_and_refuses_bytecode():
    b = Bridge(strict=False)
    chunk = b.env.load(b"x_from_chunk = 42 return _G == x_env", b"@probe.lua")
    b.env[b"x_env"] = b.env
    assert chunk() is True
    assert b.env[b"x_from_chunk"] == 42 and b.lua[b"x_from_chunk"] is None
    dumped = b.eval("string.dump(function() return 1 end)")
    result = b.env.load(dumped, b"@bytecode")
    assert result[0] is None and b"must ship Lua source" in result[1]
    # per-mod copies: changing the mod's string table leaves the real one alone
    b.env.string[b"trim"] = 1
    assert b.lua.string.trim is None


def test_strict_mode_catches_typos_and_leaks():
    b = Bridge()
    ok, err = b.eval("function(env) return pcall(env.load('return tostrnig(1)', '@typo.lua')) end")(b.env)
    assert ok is False and b"undefined global tostrnig" in err
    ok, err = b.eval("function(env) return pcall(env.load('leak = 1', '@leak.lua')) end")(b.env)
    assert ok is False and b"undeclared global leak" in err


# ------------------------------------------------------------ main.lua


def test_main_loads_modules_and_logs():
    b = Bridge(modules={"src.core.Version": VERSION_OK})
    before = b.env_keys()
    b.run_main()
    assert b.log_lines() == ["[info] [gen1-bridge] gen1-bridge loaded"]
    # every module, then (Wild Routes, WR2) the route list
    # MG2: kb_engine's loader reads the Gen 1 adapter after main.lua's own imports (not counted as a module)
    assert b.read_calls == LIB_FILES + ["lib/engine/gen1.lua", "data/routes.json"]
    status = b.mod.exports
    assert (status.ready, status.modules, status.bridge, status.protocol) == (False, len(LIB_FILES), b"0.1.0", 1)
    assert b.env_keys() == before, "main.lua or a module leaked a global"


def test_game_ready_sets_status():
    b = Bridge(modules={"src.core.Version": VERSION_OK})
    before = b.env_keys()
    b.run_main()
    game = b.table()
    b.emit("game.ready", b.eval("function(g) return { game = g } end")(game))
    status = b.mod.exports
    assert status.ready is True and status.engine == b"0.3.85"
    assert b.log_lines()[-1] == f"[info] [gen1-bridge] game.ready api=2 engine=0.3.85 modules={len(LIB_FILES)}"
    assert b.env_keys() == before


@pytest.mark.parametrize(
    "version, warns",
    [("{ engine = '0.3.86', modApi = 2 }", True), ("{ engine = '0.0.0-dev+abcdef123456', modApi = 2 }", False)],
)
def test_pin_check(version, warns):
    b = Bridge(modules={"src.core.Version": version})
    b.run_main()
    b.emit("game.ready", b.eval("{}"))
    warned = [line for line in b.log_lines() if "engine.unchecked_version" in line]
    assert bool(warned) == warns
    if warns:
        assert warned == ["[warn] [gen1-bridge] engine.unchecked_version checked=0.3.85 engine=0.3.86"]


def test_missing_engine_modules_are_survivable():
    b = Bridge()  # no src.core.Version at all
    b.run_main()
    b.emit("game.ready", None)
    assert b.mod.exports.ready is True and b.mod.exports.engine is None
    assert not any("[error]" in line for line in b.log_lines())


def test_main_never_touches_the_socket_at_boot():
    b = Bridge(modules={"socket": "function() error('socket must not load at boot') end",
                        "src.core.Version": VERSION_OK})
    b.run_main()
    b.emit("game.ready", b.eval("{}"))
    assert b.mod.exports.ready is True


# ------------------------------------------------------------ ENGINE_UPGRADE_PLAN: the pin, the required internals, the Union Room


def engine_on(rig_or_game):
    """kb_engine's seam table on a rig's fake game (as main.lua builds it)."""
    b, E = rig_or_game.b, rig_or_game.E
    Engine = b.module("lib/kb_engine.lua")
    eng = Engine.new(b.engine_opts(ui=E.ui))  # MG2: the loader picks lib/engine/gen1.lua
    eng.setGame(E.game)
    return Engine, eng


def gen1_statics(rig_or_game):
    """The Gen 1 adapter module (lib/engine/gen1.lua, MG2): its statics (REQUIRES, CLASSIC, ...)."""
    return rig_or_game.b.module("lib/engine/gen1.lua")


def test_the_pin_is_the_upgraded_engine_and_the_fake_plays_it():
    from rig import Rig
    rig = Rig()
    Engine, eng = engine_on(rig)
    assert Engine.CHECKED == b"0.3.85" and Engine.TESTED == b"0.3.85"
    assert eng.version() == (b"0.3.85", 2)
    # the fake engine's Version module: src/core/Version.lua:6-23 at v0.3.85
    V = rig.E.Version
    assert (V.engine, V.shell, V.modApi, V.saveFormat) == (b"0.3.85", 3, 2, 5)
    assert eng.checkPin() is True and not any("engine.unchecked_version" in line for line in rig.logs())


def test_required_internals_are_listed_and_all_present_on_the_fakes():
    from battlesim import BattleGame
    g = BattleGame()  # the fullest fake: script, wild, center and content modules attached
    Engine, eng = engine_on(g)
    required = {k.decode(): [m.decode() for m in v.values()] for k, v in gen1_statics(g).REQUIRES.items()}
    assert len(required) >= 20 and all(name.startswith("src.") for name in required)
    assert required["src.inventory.Bag"] == ["add", "capacity", "isBadge", "remove", "slots"] and "src.core.Music" in required  # MG6: bag.set
    assert list(eng.checkRequired().values()) == [], "every module and member the seam reads exists on the fakes"
    # main.lua checked it at game.ready: nothing missing, nothing logged
    assert g.status()["engineMissing"] == []
    assert not any("engine.missing" in line for line in g.rig.logs())


def test_a_missing_internal_is_named_once_at_game_ready():
    from rig import Rig
    rig = Rig(ready=False, script=True)  # every module the seam reads (the plain rig lacks the M5+ ones)
    rig.E.modules[b"src.inventory.Badges"][b"itemFor"] = None
    rig.b.mods_loaded()
    rig.b.ready()
    _, eng = engine_on(rig)
    assert list(eng.checkRequired().values()) == [b"src.inventory.Badges.itemFor"]
    assert "[warn] [gen1-bridge] engine.missing count=1 first=src.inventory.Badges.itemFor" in rig.logs()
    assert sum("engine.missing" in line for line in rig.logs()) == 1


def test_the_union_room_is_pinned_off_with_the_display_options():
    from rig import Rig
    rig = Rig()
    Engine, eng = engine_on(rig)
    assert gen1_statics(rig).CLASSIC.unionRoom is False
    assert rig.E.save.options.unionRoom is False, "pinned at game.ready like the display keys"
    assert eng.displayPinned(rig.E.save.options) is True
    rig.E.save.options.unionRoom = True
    assert eng.displayPinned(rig.E.save.options) is False
    assert eng.pinDisplay(rig.E.save.options, False) == 1 and rig.E.save.options.unionRoom is False


def test_union_room_patched_reads_the_added_second_floor():
    from rig import Rig
    rig = Rig(script=True)  # with fake_center's SaveData (loadOptions / saveOptions)
    _, eng = engine_on(rig)
    assert eng.unionRoomPatched() is False, "no POKECENTER_2F in the live maps"
    assert rig.status.unionRoom == b"off"
    rig.E.applyUnionRoom()
    assert eng.unionRoomPatched() is True
    # and written off for the next boot: SaveData.loadOptions then saveOptions
    assert eng.unionRoomOff() is True
    assert rig.E.optionsFile.unionRoom is False and rig.E.optionsWrites == 1
    assert eng.unionRoomOff() is True and rig.E.optionsWrites == 1, "already off: nothing written again"


def test_a_boot_that_finds_the_union_room_on_says_so_and_writes_it_off():
    from rig import Rig
    rig = Rig(ready=False, script=True)
    rig.E.applyUnionRoom()
    rig.b.mods_loaded()
    rig.b.ready()
    assert "[warn] [gen1-bridge] engine.union_room_on written=1" in rig.logs()
    assert rig.status.unionRoom == b"on" and rig.E.optionsFile.unionRoom is False
    rig.world()
    peer = rig.connect()
    peer.hello()
    peer.messages()
    seq = peer.send("debug.status")
    rig.step()
    status = [m for m in peer.messages() if m["type"] == "debug.status" and m.get("re") == seq][0]["data"]
    assert status["unionRoom"] == "on" and status["engineMissing"] == []


# ------------------------------------------------------------ mod folder rules


GENERATED_MODULES = {
    "constants", "maps", "tilesets", "text", "text_pointers", "trainer_headers", "font", "sprites",
    "pokemon", "moves", "items", "type_chart", "trainers", "encounters", "field", "battle_anims",
    "audio", "palettes", "icons",
}


def bridge_files():
    return [p for p in BRIDGE.rglob("*") if p.is_file()]


def test_no_generated_paths_or_module_names():
    for path in bridge_files():
        assert path.stem not in GENERATED_MODULES, path  # modkit MK305
        if path.suffix in {".lua", ".json"}:
            text = path.read_text(encoding="utf-8")
            assert "data" + "/generated/" not in text and "assets" + "/generated/" not in text, path  # MK301


def test_lib_layout_and_no_self_require():
    # MG2: lib/engine/<engine>.lua are the engine adapters (one per Gen1Recomp engine id, D1); the
    # loader lib/kb_engine.lua names each in Engine.ADAPTERS
    for path in (BRIDGE / "lib").iterdir():
        if path.name == "engine":
            assert path.is_dir()
            adapters = sorted(p.name for p in path.iterdir())
            assert adapters == ["gen1.lua"], adapters
            continue
        assert path.name.startswith("kb_") and path.suffix == ".lua", path
    for path in BRIDGE.rglob("*.lua"):
        text = path.read_text(encoding="utf-8")
        assert not re.search(r"""require\s*\(?\s*["'](lib|kb_)""", text), path


def test_only_the_engine_adapters_require_engine_modules():
    # D1: only lib/engine/*.lua touch engine internals (CLAUDE.md's rule); the loader asks only
    # for GameVersion's engine id, the stub for Version and GameVersion
    for path in BRIDGE.rglob("*.lua"):
        rel = path.relative_to(BRIDGE).as_posix()
        if rel.startswith("lib/engine/"):
            continue
        text = path.read_text(encoding="utf-8")
        found = set(re.findall(r"""requireFn,\s*["'](src\.[A-Za-z.]+)["']|try\(["'](src\.[A-Za-z.]+)["']""", text))
        names = {a or b for a, b in found}
        if rel == "lib/kb_engine.lua":
            assert names <= {"src.core.GameVersion", "src.core.Version"}, names
        else:
            assert not names, (rel, names)


def test_manifest():
    manifest = json.loads((BRIDGE / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["api"] == 2 and manifest["entry"] == "main.lua"
    assert {"network", "engine_internals"} <= set(manifest["permissions"])
    assert set(manifest["games"]) == {"red", "blue"}


# ------------------------------------------------------------ MG2: the adapter loader


def test_the_loader_picks_the_gen1_adapter_for_the_gen1_engine():
    from rig import Rig
    rig = Rig()
    Engine, eng = engine_on(rig)
    assert eng.hasAdapter() is True and eng.engineId() == b"gen1" and eng.family() == b"gen1"
    assert Engine.ADAPTERS.gen1 == b"lib/engine/gen1.lua"
    assert list(Engine.missingGroups(eng).values()) == [], "the Gen 1 adapter implements every group"
    groups = {k.decode() for k in Engine.GROUPS.keys()}
    assert groups == {"boot", "interaction", "ui", "movement", "party", "items", "saving", "battles", "story",
                      "sessions", "reliability", "export", "routes", "region"}
    assert (rig.status.engineId, rig.status.engineAdapter) == (b"gen1", True)
    assert list(rig.status.engineGroupsMissing.values()) == []
    assert not any("engine.groups" in line or "engine.no_adapter" in line for line in rig.logs())


def test_an_engine_without_an_adapter_gets_the_stub_and_rejects_every_hello():
    from rig import Rig
    rig = Rig(engine_id="game3")
    assert (rig.status.engineId, rig.status.engineAdapter, rig.status.ready) == (b"game3", False, True)
    assert len(rig.status.engineGroupsMissing) == 14
    assert sum("engine.no_adapter engine=game3" in line for line in rig.logs()) == 1
    assert sum("engine.groups adapter=false count=14" in line for line in rig.logs()) == 1
    rig.step(3)
    peer = rig.connect()
    peer.hello()
    msgs = peer.messages()
    assert [m["type"] for m in msgs] == ["hello.reject"] and msgs[0]["data"]["reason"] == "game"
    assert peer.closed()
    assert any("session.rejected game=blue reason=game" in line for line in rig.logs())
    rig.step(3)  # the stub answers every per-step call without a fault
    assert not any("step_failed" in line for line in rig.logs())


def test_the_stubs_hook_wrappers_pass_through():
    from rig import Rig
    rig = Rig(engine_id="gs")
    Engine, eng = engine_on(rig)
    assert eng.hasAdapter() is False and eng.engineId() == b"gs"
    nxt = rig.b.eval("function(...) return select('#', ...), ... end")
    assert tuple(eng.scriptCommand(nxt, b"ctx", b"name", b"args")) == (3, b"ctx", b"name", b"args")
    assert tuple(eng.pcItems(nxt, b"g", b"items")) == (2, b"g", b"items")
    assert tuple(eng.encounterRoll(nxt, b"def", b"ctx")) == (2, b"def", b"ctx")
    assert eng.takeOver(nxt) is False and eng.version()[0] == b"0.3.85"
    assert eng.observe().inGame is False and eng.playthrough() == b"title" and eng.saveSlot() == 0
    assert eng.noSuchFunction() is None, "anything else is a function returning nothing"
