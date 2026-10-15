"""MG9 (G2E): the synthetic Gen 2 export (tools/fake_gen1/synth2.py) matches the export schemas and the real export's
shapes (gen1-bridge/lib/engine/gen2/export.lua): every block's 16 tiles on a page, every collision byte described, the
animated water's tiles of their own, the maps' blocks filling their size, warps standing on warp bytes, the sites'
blocks where the maps have them."""
import base64

from fake_gen1 import synth2
from kb_protocol import load_protocol

PROTOCOL = load_protocol()
RESOURCES = synth2.resources()


def test_every_resource_validates():
    for (kind, rid), data in RESOURCES.items():
        problems = PROTOCOL.check_export(kind, data)
        assert not problems, (kind, rid, [str(p) for p in problems])


def test_index_names_crystal_and_every_kind():
    index = synth2.index(res=RESOURCES)
    assert index["game"] == "crystal"
    assert not PROTOCOL.check({"type": "export.index", "seq": 1, "re": 1, "data": index})
    kinds = {e["kind"] for e in index["resources"]}
    assert kinds == {"g2map", "g2tiles", "g2image", "g2anims", "g2sprite", "g2layout", "g2encounters", "g2names", "swaps"}


def test_tiles_pages_and_meanings_agree():
    pages = {int(i.split(".")[1]) for (k, i) in RESOURCES if k == "g2image"}
    anims = RESOURCES[("g2anims", "all")]["anims"]
    animated = {a["q"] for a in anims}
    for (kind, rid), t in RESOURCES.items():
        if kind != "g2tiles":
            continue
        for block in t["blocks"]:
            assert all(q // 1024 in pages for q in block)
        for coll in t["coll"]:
            assert all(str(c) in t["meanings"] for c in coll)
    water = RESOURCES[("g2tiles", "TILESET_TEST_1")]["blocks"][3]
    assert set(water) <= animated, "the water's tiles are animated tiles of their own"
    assert all(f not in animated for a in anims for f in a["f"])


def test_maps_warps_and_sites():
    maps = {i: d for (k, i), d in RESOURCES.items() if k == "g2map"}
    tiles = {i: d for (k, i), d in RESOURCES.items() if k == "g2tiles"}
    for mid, m in maps.items():
        blocks = base64.b64decode(m["blocks"])
        assert len(blocks) == m["w"] * m["h"]
        t = tiles[m["variant"]]
        for w in m["warps"]:
            block = blocks[(w["y"] // 2) * m["w"] + w["x"] // 2]
            coll = t["coll"][block][(w["y"] % 2) * 2 + w["x"] % 2]
            assert "warp" in t["meanings"][str(coll)], (mid, w)
    for site in RESOURCES[("swaps", "all")]["sites"]:
        m = maps[site["map"]]
        block = base64.b64decode(m["blocks"])[site["by"] * m["w"] + site["bx"]]
        assert block in (site["before"], site["after"]), site
    layouts = {i: d for (k, i), d in RESOURCES.items() if k == "g2layout"}
    assert set(layouts) == {"johto", "kanto"} and layouts["johto"]["start"]["map"] in maps
