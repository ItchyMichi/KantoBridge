"""Selective rollback preserves unrelated edits and validates its archival evidence."""
import copy
import gzip
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

import pytest


SPEC = importlib.util.spec_from_file_location("model_history", Path(__file__).parents[1] / "model_history.py")
history = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(history)

BASE = "tilesets.house/1-2-3-4"
TOP = "tilesets.house/5-6-7-8"
ADDED = "tilesets.house/9-10-11-12"
OTHER = "tilesets.house/13-14-15-16"


def write_manifest(archive, manifest):
    data = history.encode(manifest)
    (archive / "manifest.json").write_bytes(data)
    (archive / "manifest.sha256").write_text(history.digest(data) + "  manifest.json\n", encoding="ascii")


@pytest.fixture
def sample(tmp_path):
    original = {
        "game": "blue", "format": {"keep": "root metadata"},
        "cells": {
            BASE: {"class": "voxels", "v": "old base", "t": "old paint", "walk": True},
            TOP: {"class": "voxels", "v": "old top", "t": "old paint", "on": BASE,
                  "up": [{"v": "old upper", "t": "upper paint"}], "seat": False, "from": "old"},
            OTHER: {"class": "columns", "params": [1, 2]},
        },
        "buildings": [
            {"id": "blue_house", "kind": "box", "roof": {"height": 4}},
            {"id": "blue_lab", "kind": "box", "roof": {"height": 8}},
        ],
    }
    approved = copy.deepcopy(original)
    approved["cells"][BASE] = {"class": "voxels", "v": "new base", "t": "new paint", "walk": False}
    approved["cells"][TOP] = {"class": "voxels", "v": "new top", "t": "new paint",
                              "up": [{"v": "new upper", "t": "new upper paint"}], "from": "approved"}
    approved["cells"][ADDED] = {"class": "voxels", "v": "new extension", "t": "extension paint"}
    approved["buildings"][0] = {"id": "blue_house", "kind": "parts", "parts": [{"roof": 9}]}
    archive = tmp_path / "archive"
    revisions = {"original": original, "approved": approved}
    records = {name: history.store_snapshot(archive, "blue", name, history.encode(table))
               for name, table in revisions.items()}
    galleries = [{"entries": [
        {"id": "blue_bed", "label": "Whole bed", "kind": "compound",
         "source_keys": [TOP, BASE, ADDED], "layout": [[TOP], [BASE, ADDED]],
         "placement_mode": "floor", "occurrences": [{"map": "BEDROOM"}, {"map": "BEDROOM"}]},
        {"id": "blue_shared_pair", "label": "Shared bedside model", "kind": "compound",
         "source_keys": [TOP, OTHER], "layout": [[TOP, OTHER]], "occurrences": [{"map": "HOUSE"}]},
    ]}]
    index = history.build_index("blue", revisions, galleries, {TOP: "Bed head", BASE: "Bed foot"})
    index_data = history.encode(index)
    (archive / "index").mkdir()
    (archive / "index/blue.json").write_bytes(index_data)
    manifest = {"schema": 1, "games": {"blue": {
        "revisions": records, "index": {"path": "index/blue.json", "sha256": history.digest(index_data)},
        "models": len(index), "changed": sum(e["changed"] for e in index),
    }}}
    write_manifest(archive, manifest)
    current = copy.deepcopy(approved)
    current["cells"][OTHER]["user_note"] = "keep this later edit"
    current["buildings"][1]["user_note"] = "keep this building edit"
    table = tmp_path / "current-blue.json"
    # Deliberately unlike the tool's serializer, to verify byte-exact backups.
    exact = b"\xef\xbb\xbf" + json.dumps(current, separators=(",", ":")).encode() + b"\r\n"
    table.write_bytes(exact)
    return SimpleNamespace(archive=archive, original=original, approved=approved, current=current,
                           index=index, manifest=manifest, table=table, exact=exact)


def test_single_cell_restores_complete_metadata_and_preserves_other_edits(sample):
    selected = history.resolve_models(sample.index, [TOP])
    result, report = history.restore_table(sample.current, sample.original, selected)
    assert result["cells"][TOP] == sample.original["cells"][TOP]
    expected = copy.deepcopy(sample.current)
    expected["cells"][TOP] = sample.original["cells"][TOP]
    assert result == expected
    assert report["changed"] == [TOP]
    assert sample.current["cells"][TOP] == sample.approved["cells"][TOP]
    result["cells"][TOP]["up"][0]["v"] = "mutated result"
    assert sample.original["cells"][TOP]["up"][0]["v"] == "old upper"


def test_compound_restores_all_parts_removes_added_override_and_reapplies_approved(sample):
    selected = history.resolve_models(sample.index, ["blue_bed"])
    result, report = history.restore_table(sample.current, sample.original, selected)
    assert result["cells"][BASE] == sample.original["cells"][BASE]
    assert result["cells"][TOP] == sample.original["cells"][TOP]
    assert ADDED not in result["cells"]
    assert report["removed"] == [ADDED]
    assert result["cells"][OTHER] == sample.current["cells"][OTHER]
    reapplied, _ = history.restore_table(result, sample.approved, selected)
    assert reapplied == sample.current


def test_building_restore_preserves_other_buildings_order_and_cells(sample):
    selected = history.resolve_models(sample.index, ["blue_house"])
    result, report = history.restore_table(sample.current, sample.original, selected)
    assert result["buildings"] == [sample.original["buildings"][0], sample.current["buildings"][1]]
    assert result["cells"] == sample.current["cells"]
    assert result["format"] == sample.current["format"]
    assert report["building_ids"] == ["blue_house"]
    assert report["changed"] == ["blue_house"]


def test_readding_selected_template_restores_precedence_without_reordering_user_templates():
    a,b,c,extra=[{'id':key,'height':n} for n,key in enumerate(('a','b','c','user-template'))]
    source={'cells':{},'buildings':[a,b,c]}
    current={'cells':{},'buildings':[a,extra,c]}
    selected=[{'source_keys':[],'building_ids':['b']}]
    restored,_=history.restore_table(current,source,selected)
    assert restored['buildings']==[a,extra,b,c]
    old,_=history.restore_table(source,{'cells':{},'buildings':[a,c]},selected)
    again,_=history.restore_table(old,source,selected)
    assert again==source


def test_preview_is_read_only_and_reports_shared_compounds(sample):
    before_files = {p.relative_to(sample.archive): p.read_bytes()
                    for p in sample.archive.rglob("*") if p.is_file()}
    report = history.restore(sample.archive, "blue", ["Bed head"], "original", sample.table)
    assert report["written"] is False
    assert sample.table.read_bytes() == sample.exact
    assert {p.relative_to(sample.archive): p.read_bytes()
            for p in sample.archive.rglob("*") if p.is_file()} == before_files
    assert {e["id"] for e in report["shared_objects"]} == {"blue_bed", "blue_shared_pair"}


def test_write_saves_exact_backup_and_repeated_selectors_are_deduplicated(sample):
    report = history.restore(sample.archive, "blue", ["blue_bed", TOP, "blue_bed"],
                             "original", sample.table, write=True)
    assert report["written"] is True
    backup = Path(report["backup"])
    assert gzip.decompress((backup / "before.json.gz").read_bytes()) == sample.exact
    transaction = history.decode((backup / "transaction.json").read_bytes())
    assert transaction["before_sha256"] == history.digest(sample.exact)
    assert transaction["after_sha256"] == history.digest(sample.table.read_bytes())
    assert (backup / "completed.json").is_file()
    actual = history.decode(sample.table.read_bytes())
    assert actual["cells"][TOP] == sample.original["cells"][TOP]
    assert ADDED not in actual["cells"]
    assert actual["cells"][OTHER] == sample.current["cells"][OTHER]
    assert actual["buildings"] == sample.current["buildings"]
    # Reapplying the approved compound leaves the unrelated later edits intact.
    history.restore(sample.archive, "blue", ["blue_bed"], "approved", sample.table, write=True)
    assert history.decode(sample.table.read_bytes()) == sample.current


@pytest.mark.parametrize("target", ["manifest", "index", "snapshot"])
def test_tampered_archive_is_rejected_before_target_write(sample, target):
    relative = {"manifest": "manifest.json", "index": "index/blue.json",
                "snapshot": "snapshots/blue/original.json.gz"}[target]
    path = sample.archive / relative
    path.write_bytes(path.read_bytes() + b"tampered")
    with pytest.raises(ValueError, match="Checksum mismatch"):
        history.restore(sample.archive, "blue", [TOP], "original", sample.table, write=True)
    assert sample.table.read_bytes() == sample.exact
    assert not (sample.archive / "switches").exists()


def test_ambiguous_label_refuses_restore_but_exact_key_is_unambiguous(sample):
    for entry in sample.index:
        if entry["kind"] == "cell" and entry["source_keys"][0] in (BASE, TOP):
            entry["label"] = "Bed piece"
    data = history.encode(sample.index)
    (sample.archive / "index/blue.json").write_bytes(data)
    sample.manifest["games"]["blue"]["index"]["sha256"] = history.digest(data)
    write_manifest(sample.archive, sample.manifest)
    with pytest.raises(ValueError, match="Ambiguous model: bed piece"):
        history.restore(sample.archive, "blue", ["bed piece"], "original", sample.table, write=True)
    assert sample.table.read_bytes() == sample.exact
    report = history.restore(sample.archive, "blue", [TOP], "original", sample.table)
    assert report["source_keys"] == [TOP]


def test_target_edit_during_restore_is_preserved_and_original_backup_retained(sample, monkeypatch):
    external = sample.exact + b" \n"
    real_write = history.immutable_write

    def concurrent_write(path, data):
        real_write(path, data)
        if path.name == "transaction.json":
            sample.table.write_bytes(external)

    monkeypatch.setattr(history, "immutable_write", concurrent_write)
    with pytest.raises(ValueError, match="Target changed during restore"):
        history.restore(sample.archive, "blue", [TOP], "original", sample.table, write=True)
    assert sample.table.read_bytes() == external
    backups = list((sample.archive / "switches").glob("*/before.json.gz"))
    assert len(backups) == 1
    assert gzip.decompress(backups[0].read_bytes()) == sample.exact
    assert not list(sample.table.parent.glob(".model-history-*"))
    assert not list((sample.archive / "switches").glob("*/completed.json"))


def test_index_tracks_missing_original_cells_compound_maps_and_unchanged_entries(sample):
    by_id = {entry["id"]: entry for entry in sample.index}
    assert by_id["blue_bed"]["maps"] == ["BEDROOM"]
    assert by_id["blue_bed"]["changed"] is True
    added = next(entry for entry in sample.index if entry["kind"] == "cell" and entry["source_keys"] == [ADDED])
    other = next(entry for entry in sample.index if entry["kind"] == "cell" and entry["source_keys"] == [OTHER])
    assert added["changed"] is True
    assert other["changed"] is False


def test_snapshot_cannot_be_replaced_with_different_bytes(sample):
    history.store_snapshot(sample.archive, "blue", "original", history.encode(sample.original))
    altered = copy.deepcopy(sample.original)
    altered["cells"][TOP]["v"] = "different"
    with pytest.raises(ValueError, match="Refusing to replace existing archive file"):
        history.store_snapshot(sample.archive, "blue", "original", history.encode(altered))


def test_init_completes_portable_backup_when_manifest_exists_without_zip(sample):
    portable = sample.archive.with_suffix(".zip")
    assert not portable.exists()
    result = history.initialize(sample.archive)
    assert portable.is_file()
    checksum = portable.with_suffix(".zip.sha256").read_text(encoding="ascii").split()[0]
    assert history.digest(portable.read_bytes()) == checksum
    assert result["blue"]["models"] == len(sample.index)
    assert sample.table.read_bytes() == sample.exact
    # A completed rerun must reuse the portable evidence, not overwrite it.
    exact_zip = portable.read_bytes()
    history.initialize(sample.archive)
    assert portable.read_bytes() == exact_zip


def test_init_recovers_missing_portable_checksum_after_validating_contents(sample):
    history.initialize(sample.archive)
    portable = sample.archive.with_suffix(".zip")
    exact_zip = portable.read_bytes()
    checksum = portable.with_suffix(".zip.sha256")
    checksum.unlink()
    with pytest.raises(ValueError, match="Portable backup lacks checksum"):
        history.verify(sample.archive)
    history.initialize(sample.archive)
    assert portable.read_bytes() == exact_zip
    assert checksum.read_text(encoding="ascii").split()[0] == history.digest(exact_zip)
    history.verify(sample.archive)


def test_verify_rejects_corrupt_switch_backup(sample):
    report = history.restore(sample.archive, "blue", [TOP], "original", sample.table, write=True)
    backup = Path(report["backup"]) / "before.json.gz"
    history.verify(sample.archive)
    backup.write_bytes(gzip.compress(sample.exact + b"tampered", mtime=0))
    with pytest.raises(ValueError, match="Switch backup checksum mismatch"):
        history.verify(sample.archive)
