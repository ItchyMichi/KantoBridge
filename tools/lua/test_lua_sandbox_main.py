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
    assert b.read_calls == LIB_FILES + ["data/routes.json"]
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
    opts = b.table()
    opts[b"require"] = b.env.require
    opts[b"ui"] = E.ui
    eng = Engine.new(opts)
    eng.setGame(E.game)
    return Engine, eng


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
    required = {k.decode(): [m.decode() for m in v.values()] for k, v in Engine.REQUIRED.items()}
    assert len(required) >= 20 and all(name.startswith("src.") for name in required)
    assert required["src.inventory.Bag"] == ["remove"] and "src.core.Music" in required
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
    assert Engine.CLASSIC.unionRoom is False
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
    for path in (BRIDGE / "lib").iterdir():
        assert path.name.startswith("kb_") and path.suffix == ".lua", path
    for path in BRIDGE.rglob("*.lua"):
        text = path.read_text(encoding="utf-8")
        assert not re.search(r"""require\s*\(?\s*["'](lib|kb_)""", text), path


def test_manifest():
    manifest = json.loads((BRIDGE / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["api"] == 2 and manifest["entry"] == "main.lua"
    assert {"network", "engine_internals"} <= set(manifest["permissions"])
    assert set(manifest["games"]) == {"red", "blue"}
