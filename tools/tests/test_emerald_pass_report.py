import json

import pytest
from PIL import Image

from tools.model_gallery import capture_coverage, image_record
from tools.shape_fit.emerald_pass.report import verify_gallery
from tools.shape_fit.emerald_pass.review import digest


def fixture_gallery(tmp_path, views=None):
    write = lambda path, value: path.write_text(json.dumps(value), encoding="utf-8")
    table = {"game": "emerald", "cells": {"g3q/1-2-3-4": {"class": "voxels", "test_geometry": 1}}, "buildings": []}
    write(tmp_path / "fitted-current.json", table)
    write(tmp_path / "fitted-baseline.json", table)
    table_hash = digest((tmp_path / "fitted-current.json").read_bytes())
    current = {"id": "tree", "comparison_id": "tree", "kind": "cell", "source_keys": ["g3q/1-2-3-4"], "sprite": "sprite.png"}
    before = {**current, "id": "tree_before", "revision": "before"}
    views = views or ["front", "side", "three_quarter"]
    manifest = {"game": "emerald", "views": views, "source": {"table_sha256": table_hash, "baseline_table_sha256": table_hash}, "entries": [current, before]}
    write(tmp_path / "manifest.json", manifest)
    manifest_hash = digest((tmp_path / "manifest.json").read_bytes())
    captures = [{"id": e["id"], "direction": view} for e in manifest["entries"] for view in views]
    capture = {"status": "complete", "manifest_sha256": manifest_hash, "captures": captures}
    write(tmp_path / "capture.json", capture)
    paths = [tmp_path / "sprite.png"]
    (tmp_path / "screenshots").mkdir()
    paths += [tmp_path / "screenshots" / f"{c['id']}_{c['direction']}.png" for c in captures]
    originals = list(paths)
    output_paths = [tmp_path / "sheet.png", tmp_path / "detail.png"]
    if "north" in views:
        output_paths.append(tmp_path / "north.png")
    paths += output_paths
    for path in paths:
        Image.new("RGB", (4, 4), "green").save(path)
    sheets = {"manifest_sha256": manifest_hash, "capture_status": "complete", "models": 1, "complete": 1,
              "capture_coverage": capture_coverage(manifest["entries"], captures, views),
              "entries": [{"id": "tree", "complete": True, "originals": [image_record(p, "source") for p in originals],
                           "sheet": str(output_paths[0]), "detail_sheet": str(output_paths[1]),
                           **({"north_sheet": str(output_paths[2])} if "north" in views else {})}]}
    write(tmp_path / "sheet-report.json", sheets)
    return {"baseline_sha256": table_hash}, table


def test_report_rechecks_images_and_snapshot_geometry(tmp_path):
    review, table = fixture_gallery(tmp_path)
    verify_gallery(tmp_path, review, table)
    table["cells"]["g3q/1-2-3-4"]["test_geometry"] = 2
    with pytest.raises(ValueError, match="captured cell is stale"):
        verify_gallery(tmp_path, review, table)
    table["cells"]["g3q/1-2-3-4"]["test_geometry"] = 1
    Image.new("RGB", (4, 4), "red").save(tmp_path / "screenshots/tree_front.png")
    with pytest.raises(ValueError, match="stale screenshot"):
        verify_gallery(tmp_path, review, table)


def test_report_recomputes_coverage_and_checks_manifest_binding(tmp_path):
    review, table = fixture_gallery(tmp_path)
    capture_path = tmp_path / "capture.json"
    capture = json.loads(capture_path.read_text())
    capture["captures"].append(capture["captures"][0])
    capture_path.write_text(json.dumps(capture))
    with pytest.raises(ValueError, match="coverage is not exact"):
        verify_gallery(tmp_path, review, table)
    capture["captures"].pop()
    capture["manifest_sha256"] = "old"
    capture_path.write_text(json.dumps(capture))
    with pytest.raises(ValueError, match="stale manifest"):
        verify_gallery(tmp_path, review, table)


def test_report_checks_optional_north_views_and_their_sheet(tmp_path):
    review, table = fixture_gallery(tmp_path, ["front", "side", "three_quarter", "north"])
    verify_gallery(tmp_path, review, table)
    path = tmp_path / "capture.json"
    capture = json.loads(path.read_text())
    capture["captures"] = [c for c in capture["captures"] if not (c["id"] == "tree_before" and c["direction"] == "north")]
    path.write_text(json.dumps(capture))
    with pytest.raises(ValueError, match="coverage is not exact"):
        verify_gallery(tmp_path, review, table)


def test_report_still_requires_angled_comparison_when_north_is_present(tmp_path):
    review, table = fixture_gallery(tmp_path, ["front", "side", "north"])
    with pytest.raises(ValueError, match="requires front, side and angled"):
        verify_gallery(tmp_path, review, table)
