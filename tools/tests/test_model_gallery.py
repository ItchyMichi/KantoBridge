"""Gallery identity, compound placement, source reconstruction and report integrity."""
import base64
import hashlib
import importlib.util
import io
import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from PIL import Image

SPEC = importlib.util.spec_from_file_location("model_gallery", Path(__file__).parents[1] / "model_gallery.py")
gallery = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gallery)


def voxel(value=255, **extra):
    return {"class": "voxels", "v": base64.b64encode(bytes([value]) * 512).decode(), **extra}


def test_hash_includes_paint_and_upper_layers_and_matches_java():
    entry = voxel(t=base64.b64encode(bytes([2]) * 4096).decode(), up=[voxel(127)])
    expected = hashlib.sha256(bytes([255]) * 512 + bytes([2]) * 4096 + bytes([127]) * 512).digest()
    params, full = gallery.model_hash(entry)
    assert params == "".join(f"{v % 100:02d}" for v in expected[:6])
    assert full == expected.hex()
    assert gallery.model_hash(voxel())[0] != params
    fragments = gallery.fragments_for_cell("a/0-1-2-3", entry, 4, "PALLET", (2, 0, 0))
    assert len(fragments) == 16
    assert max(f["y"] for f in fragments) == 3
    assert fragments[-1]["key"].endswith(params + "01")


def test_stack_chains_are_complete_and_cycles_rejected():
    cells = {"top": voxel(on="middle"), "middle": voxel(on="base"), "base": voxel()}
    assert gallery.stack_layouts(cells) == [[["top"], ["middle"], ["base"]]]
    with pytest.raises(ValueError, match="cyclic"):
        gallery.stack_layouts({"a": voxel(on="b"), "b": voxel(on="a")})


def test_catalog_keeps_individual_cells_but_excludes_cyclic_compounds():
    a, b, c, d = (f"a/{n}-{n}-{n}-{n}" for n in range(4))
    cells = {a: voxel(on=b), b: voxel(on=a), c: voxel(on=d), d: voxel()}
    assert gallery.cyclic_stack_keys(cells) == [a, b]
    class Art:
        locations = {}
    entries = gallery.catalog({"game": "emerald", "cells": cells}, [], Art())
    assert len([e for e in entries if e["kind"] == "cell"]) == 4
    assert [e["layout"] for e in entries if e["kind"] == "compound"] == [[[c], [d]]]


def test_source_proven_native_baseline_cells_remain_visible_without_fake_fitted_entries():
    missing,lower='a/0-1-2-3','a/4-5-6-7'
    proof={'key':missing,'map':'SCHOOL','baseline_sha256':'baseline','evidence_sha256':'proof',
           'columns':[{'dx':x,'dz':z,'blocks':6,'shape_mask':0,'decor':0,
                       'keys':['cube:0|0,0,8,2,2,2']*6} for x in range(2) for z in range(2)]}
    entity={'id':'board','kind':'object','layout':[[missing],[lower]],
            'baseline_native_cells':{missing:proof}}
    table={'game':'emerald','cells':{lower:voxel()}}
    class Art:locations={}
    # Current authoring cannot silently invoke a baseline-only native fallback.
    assert all(e['id']!='board' for e in gallery.catalog(table,[entity],Art()))
    before=gallery.catalog(table,gallery.baseline_entities([entity],'baseline'),Art())
    single=next(e for e in before if e['source_keys']==[missing])
    whole=next(e for e in before if e['id']=='board')
    assert single['bounds']==[2,6,2] and len(single['fragments'])==24
    assert whole['bounds']==[2,6,4] and len(whole['fragments'])==32
    assert all(f['key'].startswith('cube:0|') for f in single['fragments'])
    assert missing not in table['cells']
    with pytest.raises(ValueError,match='another table'):
        gallery.baseline_entities([entity],'other')
    with pytest.raises(ValueError,match='absent from fitted'):
        gallery.catalog({'cells':{lower:voxel(),missing:voxel()}},gallery.baseline_entities([entity]),Art())
    broken=json.loads(json.dumps(proof));broken['columns'][0]['keys'][0]='cell:0|0,1,2,3|0|0,0,0'
    with pytest.raises(ValueError,match='exact cube'):
        gallery.fragments_for_native_cell(missing,broken,0,'BAKED')


def test_compound_reference_can_include_projected_art_outside_physical_footprint():
    a, b, tip = "g3q/0-1-2-3", "g3q/4-5-6-7", "g3q/8-9-10-11"
    class Art:
        locations = {}
    entities = [{"id": "tree", "kind": "object", "layout": [[a, b]], "reference_layout": [[tip, tip], [a, b]]}]
    entries = gallery.catalog({"game": "emerald", "cells": {a: voxel(), b: voxel()}}, entities, Art())
    entity = next(e for e in entries if e["id"] == "tree")
    assert entity["bounds"] == [4, 2, 2]
    assert entity["source_keys"] == [a, b]
    assert entity["reference_layout"] == [[tip, tip], [a, b]]
    assert len(entity["fragments"]) == 16


def test_compounds_deduplicate_and_stand_vertically():
    top, base = "a/0-1-2-3", "a/4-5-6-7"
    table = {"cells": {top: voxel(on=base), base: voxel()}}
    class Art:
        locations = {}
    entities = [{"id": "duplicate", "kind": "stack (tall object)", "layout": [[top], [base]]}]
    entries = gallery.catalog(table, entities, Art())
    assert len(entries) == 3
    compound = next(e for e in entries if e["kind"] == "compound")
    assert compound["bounds"] == [2, 4, 2]
    assert {f["y"] for f in compound["fragments"]} == {0, 1, 2, 3}
    assert {f["z"] for f in compound["fragments"]} == {0, 1}
    assert compound['aliases']==['duplicate']
    assert gallery.select_entries(entries,['duplicate'])==[compound]


def test_prepare_keeps_explicit_baseline_placement_when_current_stack_is_automatic(tmp_path,monkeypatch):
    a,b='g3q/0-1-2-3','g3q/4-5-6-7'
    class Art:
        maps={};locations={}
        def __init__(self,bundle):pass
        def drawing(self,layout,palette):return Image.new('RGB',(16,32),'white')
    old={'game':'emerald','cells':{a:voxel(),b:voxel()}}
    current={'game':'emerald','cells':{a:voxel(on=b),b:voxel()}}
    entity={'id':'calendar','kind':'stack','layout':[[a],[b]],'baseline_placements':[
        {'source':[0,0],'offset':[0,0,0]},{'source':[0,1],'offset':[0,0,1]}]}
    source,baseline,entities=[tmp_path/n for n in ('current.json','old.json','entities.json')]
    gallery.write_json(source,current);gallery.write_json(baseline,old);gallery.write_json(entities,[entity])
    monkeypatch.setattr(gallery,'ROOT',tmp_path);monkeypatch.setattr(gallery,'EmeraldArt',Art)
    args=SimpleNamespace(game='emerald',table=source,baseline_table=baseline,bundle=tmp_path,entities=entities,
        subset='all',only=['calendar'],source_map=None,angled=True,world='Comparison test',out=tmp_path/'local/out')
    manifest=gallery.prepare(args);before,after=manifest['entries']
    assert before['bounds']==[2,2,4] and after['bounds']==[2,4,2]
    assert before['id']==after['id']+'_before'
    assert before['comparison_id']==after['comparison_id']==after['id']
    assert after['aliases']==['calendar'] and after['baseline_placements']==entity['baseline_placements']


def test_compound_combines_vertical_cap_with_projected_floor_foot():
    a,b,c=(f'a/{n}-{n}-{n}-{n}' for n in range(3))
    table={'cells':{a:voxel(on=b),b:voxel(),c:voxel()}}
    class Art:locations={}
    entity={'id':'shelf','kind':'object','layout':[[a],[b],[c]],'placements':[
        {'source':[0,0],'offset':[0,1,0]}, {'source':[0,1],'offset':[0,0,0]},
        {'source':[0,2],'offset':[0,0,1]}]}
    item=next(e for e in gallery.catalog(table,[entity],Art()) if e['id']=='shelf')
    assert item['bounds']==[2,4,4] and item['placement_mode']=='custom'
    assert {(f['y'],f['z']) for f in item['fragments']}=={(y,z) for y in range(4) for z in range(2)}|{(y,z) for y in range(2) for z in range(2,4)}
    entity['placements'].pop()
    with pytest.raises(ValueError,match='incomplete'):
        gallery.catalog(table,[entity],Art())


def test_corrected_bed_compares_original_vertical_stack_with_revised_floor_assembly():
    top, base = "a/0-1-2-3", "a/4-5-6-7"
    old = {"cells": {top: voxel(on=base), base: voxel()}}
    class Art:
        locations = {}
    original = next(e for e in gallery.catalog(old, [], Art()) if e["kind"] == "compound")
    entity = {"id": original["id"], "kind": "object (model as one)", "note": "Bed",
              "layout": [[top], [base]], "replaces_stack": True}
    before = next(e for e in gallery.catalog(old, [entity], Art()) if e["kind"] == "compound")
    new = {"cells": {top: voxel(), base: voxel()}}
    after = next(e for e in gallery.catalog(new, [entity], Art()) if e["kind"] == "compound")
    assert before["id"] == after["id"]
    assert before["placement_mode"] == "stack" and before["bounds"] == [2, 4, 2]
    assert after["placement_mode"] == "floor" and after["bounds"] == [2, 2, 4]
    assert max(f["y"] for f in before["fragments"]) == 3
    assert max(f["z"] for f in after["fragments"]) == 3


def test_source_map_uses_each_blocks_four_quadrants(tmp_path):
    for part in ("tileset", "map", "image", "palettes"):
        (tmp_path / part).mkdir()
    gallery.write_json(tmp_path / "tileset/T.json", {"image": "a", "tilesPerRow": 4, "blocks": [list(range(16))]})
    gallery.write_json(tmp_path / "map/PALLET_TOWN.json", {"id": "PALLET_TOWN", "width": 1, "height": 1,
                                                       "blocks": [0], "tileset": "T", "palette": "PALLET"})
    colors = [[240, 240, 240], [170, 170, 170], [85, 85, 85], [10, 10, 10]]
    gallery.write_json(tmp_path / "palettes/sgb.json", {"palettes": {"PALLET": colors}})
    im = Image.new("L", (32, 32), 255)
    for n in range(16):
        im.paste(255 - (n % 4) * 85, (n % 4 * 8, n // 4 * 8, n % 4 * 8 + 8, n // 4 * 8 + 8))
    buffer = io.BytesIO()
    im.save(buffer, format="PNG")
    gallery.write_json(tmp_path / "image/a.json", {"png": base64.b64encode(buffer.getvalue()).decode()})
    art = gallery.BlueArt(tmp_path)
    assert art.map_cells(art.maps["PALLET_TOWN"]) == [
        ["a/0-1-4-5", "a/2-3-6-7"], ["a/8-9-12-13", "a/10-11-14-15"]]
    rebuilt = art.map_image("PALLET_TOWN")
    assert rebuilt.size == (32, 32)
    assert rebuilt.getpixel((24, 24)) == (10, 10, 10, 255)


def test_emerald_reconstructs_full_colour_quarters_across_pages_and_border_fallback(tmp_path):
    for part in ("g3tiles", "g3map", "g3image"):
        (tmp_path / part).mkdir()
    gallery.write_json(tmp_path / "g3tiles/general.json", {
        "mids": [468], "quarters": [[0, 1024, 1, 1025]],
    })
    # First cell is the named metatile; second lacks a metatile and uses the border.
    data = (468).to_bytes(2, "little") + bytes([7, 3]) + (999).to_bytes(2, "little") + bytes([7, 0])
    gallery.write_json(tmp_path / "g3map/EM_LITTLEROOT_TOWN.json", {
        "id": "EM_LITTLEROOT_TOWN", "w": 2, "h": 1, "pair": "general",
        "cells": base64.b64encode(data).decode(), "border": {"w": 1, "h": 1, "mids": [468]},
    })
    colours = [(20, 193, 52, 0), (157, 88, 43, 255)]
    for page, colour in enumerate(colours):
        image = Image.new("RGBA", (256, 256), colour)
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        gallery.write_json(tmp_path / f"g3image/g3q.{page}.json", {"png": base64.b64encode(buffer.getvalue()).decode()})
    art = gallery.EmeraldArt(tmp_path)
    key = "g3q/0-1024-1-1025"
    assert art.map_cells(art.maps["EM_LITTLEROOT_TOWN"]) == [[key, key]]
    assert art.locations[key][0] == {"map": "EM_LITTLEROOT_TOWN", "x": 0, "y": 0, "palette": "BAKED"}
    image = art.cell(key, "BAKED")
    assert image.getpixel((0, 0)) == colours[0][:3] + (255,)
    assert image.getpixel((8, 0)) == colours[1]
    assert art.map_image("EM_LITTLEROOT_TOWN").size == (32, 16)
    entries = gallery.catalog({"game": "emerald", "cells": {key: voxel()}}, [], art)
    assert entries[0]["id"].startswith("emerald_")
    assert {f["palette"] for f in entries[0]["fragments"]} == {"BAKED"}


def test_game_selection_sets_all_default_inputs_together(monkeypatch):
    selected = []
    monkeypatch.setattr(gallery, "prepare", lambda args: selected.append(args))
    assert gallery.main(["prepare", "--game", "emerald", "--only", "test-tree"]) == 0
    args = selected.pop()
    assert args.bundle == gallery.ROOT / "local/minecraft/kantobridge/export/emerald"
    assert args.table.name == args.entities.name == "emerald.json"
    assert args.out.name == "emerald"
    assert args.world == "Emerald Model Gallery"
    assert gallery.main(["prepare"]) == 0
    args = selected.pop()
    assert args.bundle == gallery.DEFAULT_BUNDLE
    assert args.table == gallery.DEFAULT_TABLE
    assert args.entities == gallery.DEFAULT_ENTITIES


def test_crystal_reconstructs_variant_block_quarters_and_baked_colours(tmp_path):
    for part in ("g2tiles", "g2map", "g2image"):
        (tmp_path / part).mkdir()
    gallery.write_json(tmp_path / "g2tiles/JOHTO_DAY.json", {"blocks": [list(range(16))]})
    gallery.write_json(tmp_path / "g2map/G2_ECRUTEAK_CITY.json", {
        "id": "G2_ECRUTEAK_CITY", "w": 1, "h": 1, "tileset": "JOHTO", "variant": "JOHTO_DAY",
        "blocks": base64.b64encode(bytes([0])).decode(), "border": 0,
    })
    page = Image.new("RGB", (256, 256), (44, 82, 19))
    page.paste((171, 45, 113), (8, 0, 16, 8))
    buffer = io.BytesIO()
    page.save(buffer, format="PNG")
    gallery.write_json(tmp_path / "g2image/g2q.0.json", {"png": base64.b64encode(buffer.getvalue()).decode()})
    art = gallery.CrystalArt(tmp_path)
    grid = art.map_cells(art.maps["G2_ECRUTEAK_CITY"])
    assert grid == [["g3q/0-1-4-5", "g3q/2-3-6-7"], ["g3q/8-9-12-13", "g3q/10-11-14-15"]]
    assert art.cell(grid[0][0], "BAKED").getpixel((8, 0)) == (171, 45, 113, 255)


def building_fixture():
    tiles = [list(range(n, n + 4)) for n in range(0, 16, 4)]
    keys = [["g3q/0-1-4-5", "g3q/2-3-6-7"], ["g3q/8-9-12-13", "g3q/10-11-14-15"]]

    class Art:
        maps = {"TOWN": {"warps": [{"x": 0, "y": 1}]}}
        locations = {keys[0][0]: [{"map": "TOWN", "x": 0, "y": 0, "palette": "BAKED"}]}

        def __init__(self, bundle=None):
            pass

        def map_cells(self, source):
            return keys

        def drawing(self, layout, palette):
            return Image.new("RGBA", (32, 32), "green")

    building = {"id": "crystal_trial", "atlas": "g3q", "tiles": tiles,
                "parts": [{"shape": "box", "x": [0.0, 16.0], "z": [0, 32], "y": [0, 20],
                           "paint": {"front": [0, 16, 16, 32]}}], "height": 20, "terrain": [[1, 0]]}
    return building, Art


def test_building_template_has_complete_geometry_and_omits_terrain_carriers():
    building, Art = building_fixture()
    table = {"game": "crystal", "cells": {}, "buildings": [building]}
    assert gallery.catalog(table, [], Art(), "all") == []  # ordinary model selection still excludes buildings
    entry, = gallery.catalog(table, [], Art(), "buildings")
    template, = entry["templates"]
    assert entry["bounds"] == [4, 3, 4]
    assert entry["occurrences"][0]["map"] == "TOWN"
    assert template["doors"] == [[0, 1]]
    assert template["hull"]["air"] == [0] * 8 + [3, 3, 0, 0] * 2
    assert len(entry["fragments"]) == 12 * 3
    assert not any(f["x"] >= 2 and f["z"] < 2 for f in entry["fragments"])
    assert {f["y"] for f in entry["fragments"]} == {0, 1, 2}
    assert template["sig"] == gallery.template_signature(template)
    assert template["hull"]["box"]["parts"][0]["x"] == [0, 16]
    assert template["sig"] == gallery.template_signature(json.loads(json.dumps(template).replace('[0, 16]', '[0.0, 16.0]')))


def test_prepare_keeps_distinct_before_and_after_building_templates(tmp_path, monkeypatch):
    building, Art = building_fixture()
    before = {k: v for k, v in building.items() if k not in ("parts", "height", "terrain")}
    before.update(roofRows=8, roof="hip", bands=[])
    source, baseline = tmp_path / "table.json", tmp_path / "baseline.json"
    gallery.write_json(source, {"game": "crystal", "cells": {}, "buildings": [building]})
    gallery.write_json(baseline, {"game": "crystal", "cells": {}, "buildings": [before]})
    monkeypatch.setattr(gallery, "ROOT", tmp_path)
    monkeypatch.setattr(gallery, "CrystalArt", Art)
    args = SimpleNamespace(game="crystal", table=source, baseline_table=baseline, bundle=tmp_path,
                           entities=tmp_path / "unused.json", subset="buildings", only=["crystal_trial"],
                           source_map=None, angled=True, world="Building test", out=tmp_path / "local/out")
    manifest = gallery.prepare(args)
    assert len(manifest["templates"]) == 2
    old, current = manifest["entries"]
    assert old["revision"] == "before" and current["revision"] == "current"
    assert old["templates"][0]["sig"] != current["templates"][0]["sig"]
    assert "parts" not in old["templates"][0]["hull"]["box"]
    assert "parts" in current["templates"][0]["hull"]["box"]
    known = {t["sig"] for t in manifest["templates"]}
    assert all(t["sig"] == gallery.template_signature(t) for t in manifest["templates"])
    assert {f["key"].split("|")[1] for e in manifest["entries"] for f in e["fragments"]} == known
    assert manifest["excluded"]["building_templates"] == 0
    assert gallery.capture_views(manifest) == ["front", "side", "three_quarter"]


@pytest.mark.parametrize("with_baseline", [False, True])
def test_prepare_snapshots_exact_parsed_bytes_even_if_sources_change(tmp_path, monkeypatch, with_baseline):
    key = "a/0-1-2-3"
    original = {"game": "blue", "cells": {key: voxel(1)}}
    baseline = {"game": "blue", "cells": {key: voxel(2)}}
    changed = {"game": "blue", "cells": {key: voxel(4)}}
    # Deliberately preserve BOM, formatting and CRLF, not just equivalent JSON.
    original_bytes = b"\xef\xbb\xbf" + json.dumps(original, indent=3).replace("\n", "\r\n").encode()
    baseline_bytes = (json.dumps(baseline, separators=(",", ":")) + "\r\n").encode()
    table_path, baseline_path = tmp_path / "source.json", tmp_path / "before.json"
    table_path.write_bytes(original_bytes)
    baseline_path.write_bytes(baseline_bytes)

    class Art:
        maps, locations = {}, {}

        def __init__(self, bundle):
            pass

        def drawing(self, layout, palette):
            # A save during preparation must not decouple hashes from geometry.
            gallery.write_json(table_path, changed)
            gallery.write_json(baseline_path, changed)
            return Image.new("RGBA", (16, 16), "red")

    monkeypatch.setattr(gallery, "ROOT", tmp_path)
    monkeypatch.setattr(gallery, "BlueArt", Art)
    output = tmp_path / "local/gallery"
    args = SimpleNamespace(out=output, table=table_path, entities=tmp_path / "none.json",
                           bundle=tmp_path, subset="all", world="test",
                           baseline_table=baseline_path if with_baseline else None)
    manifest = gallery.prepare(args)
    manifest_bytes = (output / "manifest.json").read_bytes()
    assert (output / "fitted-current.json").read_bytes() == original_bytes
    assert manifest["source"]["table_sha256"] == hashlib.sha256(original_bytes).hexdigest()
    assert manifest["source"]["table_snapshot"] == "fitted-current.json"
    current = next(e for e in manifest["entries"] if e.get("revision", "current") == "current")
    assert current["model_hashes"][key] == gallery.model_hash(original["cells"][key])[1]
    assert gallery.read_json(table_path) == changed
    if with_baseline:
        assert manifest["fitted_tables"] == ["fitted-baseline.json"]
        assert (output / "fitted-baseline.json").read_bytes() == baseline_bytes
        assert manifest["source"]["baseline_table_sha256"] == hashlib.sha256(baseline_bytes).hexdigest()
        assert manifest["source"]["baseline_table_snapshot"] == "fitted-baseline.json"
        before = next(e for e in manifest["entries"] if e["revision"] == "before")
        assert before["model_hashes"][key] == gallery.model_hash(baseline["cells"][key])[1]
    else:
        assert "fitted_tables" not in manifest
        assert not (output / "fitted-baseline.json").exists()
    # Source edits do not touch the prepared revision or its capture identity.
    gallery.write_json(table_path, baseline)
    latest_bytes = table_path.read_bytes()
    assert (output / "manifest.json").read_bytes() == manifest_bytes
    assert (output / "fitted-current.json").read_bytes() == original_bytes
    # An explicit new prepare adopts the latest source revision.
    updated = gallery.prepare(args)
    assert updated["source"]["table_sha256"] == hashlib.sha256(latest_bytes).hexdigest()
    assert (output / "fitted-current.json").read_bytes() == latest_bytes
    assert updated["entries"][-1]["model_hashes"][key] == gallery.model_hash(baseline["cells"][key])[1]


def test_missing_screenshots_never_report_complete(tmp_path):
    manifest = {"entries": [{"id": "test", "label": "Test", "sprite": "sprite.png"}]}
    gallery.write_json(tmp_path / "manifest.json", manifest)
    Image.new("RGB", (16, 16), "red").save(tmp_path / "sprite.png")
    report = gallery.compose(tmp_path / "manifest.json")
    assert report["complete"] == 0
    assert len(report["entries"][0]["missing"]) == 2
    assert (tmp_path / "sheets/test.png").is_file()


def test_same_run_before_after_has_one_five_panel_sheet(tmp_path):
    entries = [
        {"id": "test_before", "label": "Before", "sprite": "sprite.png", "revision": "before", "comparison_id": "test"},
        {"id": "test", "label": "After", "sprite": "sprite.png", "revision": "current", "comparison_id": "test"},
    ]
    gallery.write_json(tmp_path / "manifest.json", {"entries": entries})
    Image.new("RGB", (16, 16), "red").save(tmp_path / "sprite.png")
    (tmp_path / "screenshots").mkdir()
    for identifier in ("test", "test_before"):
        for direction in ("front", "side"):
            Image.new("RGB", (1280, 720), "gray").save(tmp_path / "screenshots" / f"{identifier}_{direction}.png")
    gallery.write_json(tmp_path / "capture.json", {
        "status": "complete", "manifest_sha256": hashlib.sha256((tmp_path / "manifest.json").read_bytes()).hexdigest(),
        "captures": [{"id": identifier, "direction": direction} for identifier in ("test", "test_before")
                     for direction in ("front", "side")],
    })
    report = gallery.compose(tmp_path / "manifest.json")
    assert report["models"] == 1
    assert report["complete"] == 1
    with Image.open(tmp_path / "sheets/test.png") as sheet:
        assert sheet.size == (2440, 520)


def test_stale_screenshots_are_not_verified_by_their_existence(tmp_path):
    entries = [{"id": "test", "label": "After", "sprite": "sprite.png"}]
    gallery.write_json(tmp_path / "manifest.json", {"entries": entries})
    Image.new("RGB", (16, 16), "red").save(tmp_path / "sprite.png")
    (tmp_path / "screenshots").mkdir()
    for direction in ("front", "side"):
        Image.new("RGB", (100, 100), "gray").save(tmp_path / "screenshots" / f"test_{direction}.png")
    gallery.write_json(tmp_path / "capture.json", {"status": "complete", "manifest_sha256": "stale",
                                                 "captures": [{"id": "test", "direction": d} for d in ("front", "side")]})
    report = gallery.compose(tmp_path / "manifest.json")
    assert report["complete"] == 0
    assert report["entries"][0]["missing"] == []
    assert "manifest" in report["entries"][0]["provenance_errors"][0]


def test_exact_capture_set_rejects_duplicate_and_unexpected_views():
    entries = [{"id": "one"}, {"id": "two"}]
    records = [{"id": e["id"], "direction": d} for e in entries for d in ("front", "side")]
    assert gallery.capture_coverage(entries, records)["exact"]
    wrong = records[:-1] + [records[0], {"id": "other", "direction": "front"}]
    coverage = gallery.capture_coverage(entries, wrong)
    assert not coverage["exact"]
    assert coverage["missing"] == ["two/side"]
    assert coverage["unexpected"] == ["other/front"]
    assert coverage["duplicates"] == 1


def test_contact_pages_keep_every_subject_and_originals_are_fingerprinted(tmp_path):
    image = tmp_path / "sprite.png"
    Image.new("RGB", (16, 24), "red").save(image)
    entries = [{"id": f"subject-{n:03d}", "sprite": "sprite.png"} for n in range(13)]
    pages = gallery.contact_sheets(tmp_path, entries, [{"id": e["id"], "complete": False} for e in entries])
    assert [len(p["ids"]) for p in pages] == [12, 1]
    assert [identifier for page in pages for identifier in page["ids"]] == [e["id"] for e in entries]
    assert all((tmp_path / p["file"]).is_file() for p in pages)
    record = gallery.image_record(image, "Source sprite")
    assert (record["width"], record["height"]) == (16, 24)
    assert record["sha256"] == hashlib.sha256(image.read_bytes()).hexdigest()


def test_exact_selectors_choose_cells_and_compounds_and_reject_unknowns():
    entries = [
        {"id": "cell_one", "label": "First cell", "kind": "cell", "source_keys": ["atlas/0-1-2-3"]},
        {"id": "cell_two", "label": "Second cell", "kind": "cell", "source_keys": ["atlas/4-5-6-7"]},
        {"id": "compound_one", "label": "Stack", "kind": "compound", "source_keys": ["atlas/0-1-2-3", "atlas/4-5-6-7"]},
    ]
    assert gallery.select_entries(entries, ["atlas/0-1-2-3"]) == entries[:1]
    assert gallery.select_entries(entries, ["compound_one", "First cell", "cell_one"]) == [entries[0], entries[2]]
    with pytest.raises(ValueError, match="unknown model selector.*atlas/0-1-2"):
        gallery.select_entries(entries, ["cell_one", "atlas/0-1-2"])


def test_baseline_assembly_can_omit_old_unrendered_projection_but_preserves_source():
    original = {'id': 'tower', 'kind': 'stack', 'layout': [['a'], ['b'], ['b']],
                'baseline_layout': [[None], [None], ['b']],
                'baseline_placements': [{'source': [0, 2], 'offset': [0, 0, 0]}]}
    before = gallery.baseline_entities([original])[0]
    assert before['layout'] == [[None], [None], ['b']]
    assert before['reference_layout'] == original['layout']
    assert before['placements'] == original['baseline_placements']
    assert original['layout'] == [['a'], ['b'], ['b']]
    assert 'placements' not in original
    with pytest.raises(ValueError, match='source identity'):
        gallery.baseline_entities([dict(original, baseline_layout=[['c'], [None], ['b']])])


def test_two_source_objects_keep_distinct_baselines_when_missing_walk_rows_coincide():
    a,b,c='g3q/0-1-2-3','g3q/4-5-6-7','g3q/8-9-10-11'
    class Art:locations={}
    entities=[{'id':identifier,'layout':[[a],[key]],'baseline_layout':[[a],[None]],
               'baseline_placements':[{'source':[0,0],'offset':[0,0,0]}]}
              for identifier,key in [('sofa-blue',b),('sofa-pink',c)]]
    entries=gallery.catalog({'game':'emerald','cells':{k:voxel() for k in (a,b,c)}},gallery.baseline_entities(entities),Art())
    pair=[e for e in entries if e['id'].startswith('sofa-')]
    assert [e['id'] for e in pair]==['sofa-blue','sofa-pink']
    assert pair[0]['reference_layout']!=pair[1]['reference_layout']
    assert pair[0]['fragments']==pair[1]['fragments']


def test_map_context_keeps_source_sprite_and_fragments_on_the_same_occurrence_palette():
    class Art:
        maps = {"BEDROOM": {}, "OTHER": {}}
        locations = {"atlas/0-1-2-3": [
            {"map": "OTHER", "palette": "OTHER_PALETTE", "x": 1, "y": 2},
            {"map": "BEDROOM", "palette": "PALLET", "x": 3, "y": 5},
        ]}
    entry = {"id": "console", "source_keys": ["atlas/0-1-2-3"], "palette": "OTHER_PALETTE",
             "fragments": [{"palette": "OTHER_PALETTE"}]}
    gallery.apply_map_context([entry], Art(), "BEDROOM")
    assert entry["palette"] == entry["fragments"][0]["palette"] == "PALLET"
    assert entry["source_occurrence"]["x"] == 3
    with pytest.raises(ValueError, match="unknown source map"):
        gallery.apply_map_context([entry], Art(), "MISSING")
    entry["source_keys"] = ["atlas/4-5-6-7"]
    with pytest.raises(ValueError, match="does not occur"):
        gallery.apply_map_context([entry], Art(), "BEDROOM")


@pytest.mark.parametrize('north',[False,True])
def test_angled_capture_requires_third_view_and_writes_a_separate_detail_sheet(tmp_path,north):
    entries = [{"id": "console", "label": "Console", "sprite": "sprite.png"}]
    views = ["front", "side", "three_quarter"]+(["north"] if north else [])
    gallery.write_json(tmp_path / "manifest.json", {"entries": entries, "views": views})
    Image.new("RGB", (16, 16), "red").save(tmp_path / "sprite.png")
    (tmp_path / "screenshots").mkdir()
    for direction in views:
        Image.new("RGB", (1280, 960), "gray").save(tmp_path / "screenshots" / f"console_{direction}.png")
    proof = {"status": "complete", "manifest_sha256": hashlib.sha256((tmp_path / "manifest.json").read_bytes()).hexdigest(),
             "captures": [{"id": "console", "direction": d} for d in views]}
    gallery.write_json(tmp_path / "capture.json", proof)
    report = gallery.compose(tmp_path / "manifest.json")
    assert report["complete"] == 1
    assert report["capture_coverage"]["expected"] == len(views)
    assert Path(report["entries"][0]["detail_sheet"]).is_file()
    assert "console_detail.png" in (tmp_path / "index.html").read_text()
    if north:
        assert Path(report['entries'][0]['north_sheet']).is_file()
        assert 'console_north.png' in (tmp_path/'index.html').read_text()
        assert any('north' in item['file'] for item in report['entries'][0]['originals'])
    proof["captures"].pop()
    gallery.write_json(tmp_path / "capture.json", proof)
    assert gallery.compose(tmp_path / "manifest.json")["complete"] == 0
