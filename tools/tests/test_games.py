"""The game catalogue (protocol/games.json, MULTI_GAME_PLAN §4.2): it matches its schema, names only
ids and symbols, agrees with gen1-bridge's manifest, and its Lua copy is current."""
import json
import re

from kb_protocol import load_games, load_protocol
from kb_protocol.games import read_games, render_lua
from kb_protocol.paths import PROTOCOL_DIR, REPO_ROOT

PROTOCOL = load_protocol()
GAMES = load_games()
MANIFEST = json.loads((REPO_ROOT / "gen1-bridge" / "manifest.json").read_text(encoding="utf-8"))
KB_GAMES_LUA = REPO_ROOT / "gen1-bridge" / "lib" / "kb_games.lua"


def test_the_catalogue_matches_its_schema():
    problems = PROTOCOL.check_games(read_games())
    assert not problems, [str(p) for p in problems]


def test_every_gen1recomp_game_is_listed_once_with_its_family():
    assert GAMES.ids == ["blue", "crystal", "emerald", "firered", "gold", "leafgreen", "red", "ruby", "sapphire", "silver",
                         "yellow"]
    assert {GAMES.family(g) for g in ("red", "blue", "yellow")} == {"gen1"}
    assert {GAMES.family(g) for g in ("gold", "silver", "crystal")} == {"gen2"}
    assert {GAMES.family(g) for g in ("firered", "leafgreen", "ruby", "sapphire", "emerald")} == {"gen3"}
    assert GAMES.family("nope") is None and "nope" not in GAMES


def test_versions_of_one_world_space_share_a_region():
    # D4: Red and Blue are one world space, one region; the same for the other families' versions
    assert {GAMES.region(g) for g in ("red", "blue", "yellow")} == {"kanto"}
    assert {GAMES.region(g) for g in ("gold", "silver", "crystal")} == {"johto"}
    assert {GAMES.region(g) for g in ("firered", "leafgreen")} == {"kanto_frlg"}
    assert {GAMES.region(g) for g in ("ruby", "sapphire", "emerald")} == {"hoenn"}
    for game in GAMES.ids:
        assert GAMES.get(game).art_set == game
        assert (GAMES.get(game).layout is not None) == (GAMES.family(game) == "gen3")


def test_compat_profiles_grow_by_generation():
    assert GAMES.compat("red") == {"maxDex": 151, "maxMove": 165, "nick": 10}
    assert GAMES.compat("crystal") == {"maxDex": 251, "maxMove": 251, "nick": 10}
    assert GAMES.compat("emerald") == {"maxDex": 386, "maxMove": 354, "nick": 10}
    assert GAMES.compat("nope") is None


def test_the_adapter_flags_agree_with_the_mod_manifest():
    # the mod's manifest `games` gate is what Gen1Recomp loads the mod for (Appendix A.4)
    assert GAMES.with_adapter() == sorted(MANIFEST["games"])


def test_compatible_means_one_family():
    assert GAMES.compatible("red", "blue") and GAMES.compatible("blue", "blue")
    assert not GAMES.compatible("red", "emerald") and not GAMES.compatible(None, "red")
    assert not GAMES.compatible("red", "nope")


def test_markers_are_flag_symbols_only():
    symbol = re.compile(r"^[A-Za-z0-9_]{1,64}$")
    for game in GAMES.ids:
        for name, flag in GAMES.get(game).markers.items():
            assert re.fullmatch(r"[a-z][a-z0-9_]{0,15}", name) and symbol.match(flag), (game, name)
    assert GAMES.get("red").markers == {"starter": "EVENT_GOT_STARTER", "champion": "EVENT_BEAT_CHAMPION_RIVAL"}


def test_the_lua_copy_is_current_and_deterministic():
    text = render_lua(read_games(PROTOCOL_DIR))
    assert KB_GAMES_LUA.read_text(encoding="utf-8") == text, "run python -m kb_protocol.catalog --write from tools/"
    assert text == render_lua(read_games(PROTOCOL_DIR))
    assert "\t" not in text and text.endswith("}\n")
    assert 'blue = {' in text and 'region = "kanto"' in text and 'family = "gen3"' in text


def test_a_bad_catalogue_is_refused():
    bad = {"version": 1, "games": {"Red": {"family": "gen1", "engine": "gen1", "generation": 1, "label": "Red",
                                           "region": "kanto", "compat": {"maxDex": 151, "maxMove": 165, "nick": 10},
                                           "artSet": "red", "adapter": True, "markers": {}}}}
    assert [str(p) for p in PROTOCOL.check_games(bad)] == ["/games: pattern"]
    bad = {"version": 1, "games": {"red": {"family": "gen9", "engine": "gen1", "generation": 1, "label": "Red",
                                           "region": "kanto", "compat": {"maxDex": 151, "maxMove": 165, "nick": 10},
                                           "artSet": "red", "adapter": True, "markers": {}}}}
    assert [str(p) for p in PROTOCOL.check_games(bad)] == ["/games/red/family: enum"]
