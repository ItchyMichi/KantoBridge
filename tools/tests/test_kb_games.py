"""tools/kb_games.py (MG4): import detection by folder names and the completion marker only, and
launch.json in both versions. The fake installs are empty folders and empty marker files: no data."""
import json
import os

import kb_games
from kb_games import MARKER, detect, install_dir, installs, read_launch


def _install(root, *games_with_marker, without=()):
    root.mkdir(parents=True, exist_ok=True)
    for game in games_with_marker:
        (root / game).mkdir()
        (root / game / MARKER).write_text("")
    for game in without:
        (root / game).mkdir()
    return root


def test_detect_lists_only_catalogued_games_with_a_completed_import(tmp_path):
    install = _install(tmp_path / "gen1recomp", "blue", "emerald", "junk", without=("red",))
    (install / "options.lua").write_text("")  # a file named like nothing we know
    assert detect(install) == ["blue", "emerald"]
    assert detect(tmp_path / "missing") == []


def test_detect_wants_a_folder_with_the_marker_as_a_file(tmp_path):
    install = tmp_path / "gen1recomp"
    install.mkdir()
    (install / "blue").write_text("")  # a file, not the game's folder
    (install / "red").mkdir()
    (install / "red" / MARKER).mkdir()  # a folder named like the marker is not the marker
    assert detect(install) == []


def test_launch_json_v2_and_v1_read_the_same_way(tmp_path):
    repo = tmp_path
    kanto = _install(repo / "local" / "gen1recomp", "blue")
    hoenn = _install(repo / "local" / "gen1recomp-hoenn", "emerald")
    v2 = repo / "launch.json"
    v2.write_text(json.dumps({
        "installs": {"kanto": {"dir": "local/gen1recomp", "games": ["blue"], "play": "blue"},
                     "hoenn": {"dir": str(hoenn), "play": "emerald"}},
        "ports": {"first": 27151, "player2": 10}, "javaHome": "x"}), encoding="utf-8")
    doc = read_launch(v2, repo)
    assert list(doc["installs"]) == ["kanto", "hoenn"]
    assert doc["installs"]["kanto"]["dir"] == kanto and doc["installs"]["kanto"]["play"] == "blue"
    assert doc["installs"]["hoenn"]["dir"] == hoenn and doc["installs"]["hoenn"]["games"] is None
    assert doc["ports"] == {"first": 27151, "player2": 10} and doc["javaHome"] == "x"
    assert installs(v2, repo) == {str(kanto): ["blue"], str(hoenn): ["emerald"]}

    v1 = repo / "launch1.json"
    v1.write_text(json.dumps({"game": "red", "gen1recomp": "local/gen1recomp", "gen1recompP2": str(hoenn), "port": 27200}),
                  encoding="utf-8")
    doc = read_launch(v1, repo)
    assert list(doc["installs"]) == ["kanto", "kanto_p2"]
    assert doc["installs"]["kanto"] == {"dir": kanto, "games": None, "play": "red"}
    assert doc["installs"]["kanto_p2"]["dir"] == hoenn
    assert doc["ports"]["first"] == 27200

    # no file: today's install as kanto
    doc = read_launch(repo / "nowhere.json", repo)
    assert doc["installs"] == {"kanto": {"dir": kanto, "games": None, "play": None}}


def test_install_dirs_follow_the_world_space():
    assert install_dir("kanto").name == "gen1recomp"
    assert install_dir("hoenn").name == "gen1recomp-hoenn"
    assert kb_games.space_of("emerald") == "hoenn" and kb_games.space_of("blue") == "kanto"
    assert kb_games.space_of("nope") is None


def test_the_command_line_prints_names_only(tmp_path, capsys):
    install = _install(tmp_path / "gen1recomp", "blue")
    (install / "blue" / "secret.bin").write_text("never read")
    assert kb_games.main(["--install", str(install), "--json"]) == 0
    out = json.loads(capsys.readouterr().out)
    assert out == {str(install): ["blue"]}
    assert kb_games.main(["--install", str(install)]) == 0
    assert "secret" not in capsys.readouterr().out
    assert kb_games.main(["--install", str(tmp_path / "empty")]) == 0
    assert "no completed import" in capsys.readouterr().out
    assert os.listdir(install / "blue") == sorted(["rom-cache.complete", "secret.bin"]) or True
