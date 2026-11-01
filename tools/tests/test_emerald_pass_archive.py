"""Future Emerald snapshots preserve historical names and complete selective rollback."""
import copy
import gzip
import json
from types import SimpleNamespace

import pytest

from tools.shape_fit.emerald_pass import archive

history = archive.history
A, B, C, OTHER = (f"g3q/{n}-{n}-{n}-{n}" for n in range(1, 5))


@pytest.fixture
def sample(tmp_path, monkeypatch):
    old = {"game": "emerald", "metadata": "retained", "cells": {
        A: {"class": "voxels", "v": "old", "on": B},
        B: {"class": "voxels", "v": "base"}, OTHER: {"class": "columns", "walk": True}},
        "buildings": [{"id": "emerald_house", "height": 8}, {"id": "emerald_lab", "height": 12}]}
    approved = copy.deepcopy(old)
    approved["cells"][A]["v"] = "approved"
    batch = copy.deepcopy(approved)
    batch["cells"][A]["v"] = "batch"
    batch["cells"][C] = {"class": "voxels", "v": "new member"}
    batch["buildings"][0]["height"] = 20
    historic = tmp_path / "historical"
    historical_tables = {"original": old, "trees-approved": approved, "approved": approved}
    # Noncanonical formatting proves the archive retains bytes rather than reserializing snapshots.
    historical_bytes = {name: b"\xef\xbb\xbf" + json.dumps(table).encode() + b"\r\n"
                        for name, table in historical_tables.items()}
    monkeypatch.setitem(history.SOURCES, "emerald", {name: ("unused", history.digest(data))
                                                    for name, data in historical_bytes.items()})
    records = {name: history.store_snapshot(historic, "emerald", name, data)
               for name, data in historical_bytes.items()}
    old_gallery = {"entries": [{"id": "emerald_pair", "kind": "compound", "label": "Pair",
        "source_keys": [A, B], "layout": [[A, B]], "placement_mode": "floor", "occurrences": [{"map": "TOWN"}]}]}
    index_data = history.encode(history.build_index("emerald", historical_tables, [old_gallery], {}))
    history.immutable_write(historic / "index/emerald.json", index_data)
    manifest = {"schema": 1, "games": {"emerald": {"revisions": records,
        "index": {"path": "index/emerald.json", "sha256": history.digest(index_data)}, "changed": 1}}}
    manifest_bytes = history.encode(manifest)
    history.immutable_write(historic / "manifest.json", manifest_bytes)
    history.immutable_write(historic / "manifest.sha256", (history.digest(manifest_bytes) + "  manifest.json\n").encode())
    baseline = tmp_path / "baseline.json"
    baseline.write_bytes(historical_bytes["approved"])
    table = tmp_path / "current.json"
    table.write_bytes(history.encode(batch))
    gallery = tmp_path / "gallery.json"
    gallery.write_bytes(history.encode({"schema": 1, "game": "emerald", "source": {"table_sha256": history.digest(table.read_bytes())},
        "entries": [{"id": "emerald_pair", "kind": "compound", "label": "Expanded pair", "source_keys": [B, C],
            "layout": [[B, C]], "placement_mode": "floor", "occurrences": [{"map": "ROUTE"}]}]}))
    return SimpleNamespace(old=old, approved=approved, batch=batch, historical=historic, historical_bytes=historical_bytes,
                           destination=tmp_path / "pass", baseline=baseline, table=table, gallery=gallery)


def create(sample):
    return archive.create_archive(sample.destination, sample.table, sample.baseline, [sample.gallery], sample.historical)


def test_archive_roundtrip_preserves_current_native_descriptor_and_loaded_source_proof(sample):
    gallery=json.loads(sample.gallery.read_text());entry=gallery['entries'][0]
    descriptor={'evidence':'local/proof.json','evidence_sha256':'e'*64,'current_table_sha256':'c'*64,
                'occurrence':{'map':'ROUTE','x':2,'y':3},'source_layout':[[B,C]],
                'source_cell_hashes':{B:'b'*64,C:'c'*64},'source_template_hashes':{}}
    loaded={'descriptor':descriptor,'record':{'corrected_fragments':[{'key':'cube:0|1,1,1,1,1,1','x':0,'y':0,'z':0,'palette':'BAKED'}]}}
    entry.update(current_native_fragments=descriptor,native_current_evidence=loaded,placement_mode='source-map-current')
    sample.gallery.write_bytes(history.encode(gallery));create(sample);archive.verify_archive(sample.destination)
    index=json.loads((sample.destination/'index/emerald.json').read_text());saved=next(e for e in index if e['id']=='emerald_pair')
    assert any(x.get('current_native_fragments')==descriptor and x.get('native_current_evidence')==loaded for x in saved['assembly_layouts'].values())
    assert any(x.get('current_native_fragments')==descriptor and x.get('native_current_evidence')==loaded for x in saved['native_baselines'].values())


def test_archive_preserves_exact_historical_bytes_and_all_membership(sample):
    before = {p.relative_to(sample.historical): p.read_bytes() for p in sample.historical.rglob("*") if p.is_file()}
    result = create(sample)
    assert result["emerald"]["latest"] == "batch-01"
    manifest = history.load_manifest(sample.destination)
    for name, exact in sample.historical_bytes.items():
        path = sample.destination / manifest["games"]["emerald"]["revisions"][name]["path"]
        assert gzip.decompress(path.read_bytes()) == exact
    index = history.load_index(sample.destination, manifest, "emerald")
    pair = history.resolve_models(index, ["emerald_pair"])[0]
    assert set(pair["source_keys"]) == {A, B, C}
    assert pair["maps"] == ["ROUTE", "TOWN"]
    assert pair["assembly_layouts"]["historical"]["layout"] == [[A, B]]
    assert pair["assembly_layouts"]["gallery-0"]["layout"] == [[B, C]]
    assert {p.relative_to(sample.historical): p.read_bytes() for p in sample.historical.rglob("*") if p.is_file()} == before
    assert sample.destination.with_suffix(".zip").exists()
    assert sample.destination.with_suffix(".zip.sha256").exists()


def test_native_terrain_without_fitted_override_survives_archive_and_restore(sample):
    terrain = 'g3q/9-9-9-9'
    gallery = history.decode(sample.gallery.read_bytes())
    entry = gallery['entries'][0]
    entry['source_keys'].append(terrain)
    entry['layout'][0].append(terrain)
    descriptor = {'source_layout': entry['layout'], 'source_cell_hashes': {terrain: None}}
    entry['current_native_fragments'] = descriptor
    entry['native_current_evidence'] = {'descriptor': copy.deepcopy(descriptor),
                                     'record': {'current_source_cell_hashes': {terrain: None}}}
    sample.gallery.write_bytes(history.encode(gallery))
    create(sample)
    scratch = sample.table.with_name('native-scratch.json')
    scratch.write_bytes(sample.table.read_bytes())
    history.restore(sample.destination, 'emerald', ['emerald_pair'], 'original', scratch, write=True)
    history.restore(sample.destination, 'emerald', ['emerald_pair'], 'batch-01', scratch, write=True)
    assert history.decode(scratch.read_bytes()) == sample.batch
    next_archive = sample.destination.with_name('native-next')
    archive.create_archive(next_archive, sample.table, sample.baseline, [], sample.historical,
                           revision='next', prior_archive=sample.destination)
    saved = history.resolve_models(history.load_index(next_archive, history.load_manifest(next_archive), 'emerald'), ['emerald_pair'])[0]
    assert terrain in saved['source_keys']
    assert any(record.get('current_native_fragments') == descriptor for record in saved['native_baselines'].values())


@pytest.mark.parametrize('missing_proof', ['loaded', 'null_binding'])
def test_unknown_native_member_requires_loaded_absence_proof(sample, missing_proof):
    gallery = history.decode(sample.gallery.read_bytes())
    entry = gallery['entries'][0]
    entry['source_keys'].append('missing')
    descriptor = {'source_layout': [['missing']], 'source_cell_hashes': {'missing': None}}
    entry['current_native_fragments'] = descriptor
    entry['native_current_evidence'] = {'descriptor': descriptor, 'record': {'current_source_cell_hashes': {'missing': None}}}
    if missing_proof == 'loaded':
        del entry['native_current_evidence']
    else:
        entry['native_current_evidence']['record']['current_source_cell_hashes']['missing'] = 'unexpected'
    sample.gallery.write_bytes(history.encode(gallery))
    with pytest.raises(ValueError, match='Unknown gallery membership'):
        create(sample)
    assert not sample.destination.exists()


@pytest.mark.parametrize("selectors", [[A], ["emerald_house"], ["emerald_pair"], ["emerald_pair", "emerald_house"]])
def test_selective_scratch_roundtrip_preserves_unrelated_edits(sample, selectors):
    create(sample)
    current = copy.deepcopy(sample.batch)
    current["cells"][OTHER]["user_note"] = "keep"
    current["buildings"][1]["user_note"] = "keep"
    scratch = sample.table.with_name("scratch.json")
    exact = b"\xef\xbb\xbf" + json.dumps(current).encode() + b"\r\n"
    scratch.write_bytes(exact)
    report = history.restore(sample.destination, "emerald", selectors, "original", scratch, write=True)
    assert report["written"]
    restored = history.decode(scratch.read_bytes())
    assert restored["cells"][OTHER] == current["cells"][OTHER]
    assert restored["buildings"][1] == current["buildings"][1]
    if "emerald_pair" in selectors:
        assert C not in restored["cells"]
        assert restored["cells"][A] == sample.old["cells"][A]
    if "emerald_house" in selectors:
        assert restored["buildings"][0] == sample.old["buildings"][0]
    history.restore(sample.destination, "emerald", selectors, "batch-01", scratch, write=True)
    assert history.decode(scratch.read_bytes()) == current
    archive.verify_archive(sample.destination)
    assert sample.table.read_bytes() == history.encode(sample.batch)


def test_approved_remains_historical_and_destination_is_immutable(sample):
    create(sample)
    manifest = history.load_manifest(sample.destination)
    assert history.load_revision(sample.destination, manifest, "emerald", "approved") == sample.approved
    assert history.load_revision(sample.destination, manifest, "emerald", "batch-01") == sample.batch
    before = {p.relative_to(sample.destination): p.read_bytes() for p in sample.destination.rglob("*") if p.is_file()}
    with pytest.raises(ValueError, match="already exists"):
        create(sample)
    assert {p.relative_to(sample.destination): p.read_bytes() for p in sample.destination.rglob("*") if p.is_file()} == before
    with pytest.raises(ValueError, match="separate"):
        archive.create_archive(sample.historical, sample.table, sample.baseline, [], sample.historical)


def test_redefined_historical_revision_rejected_before_write(sample, monkeypatch):
    monkeypatch.setitem(history.SOURCES["emerald"], "approved", ("unused", "0" * 64))
    with pytest.raises(ValueError, match="Historical approved was redefined"):
        create(sample)
    assert not sample.destination.exists()


@pytest.mark.parametrize("problem", ["wrong_table", "unknown_member"])
def test_invalid_gallery_rejected_before_write(sample, problem):
    doc = history.decode(sample.gallery.read_bytes())
    if problem == "wrong_table":
        doc["source"]["table_sha256"] = "0" * 64
    else:
        doc["entries"][0]["source_keys"] = ["missing"]
    sample.gallery.write_bytes(history.encode(doc))
    with pytest.raises(ValueError):
        create(sample)
    assert not sample.destination.exists()


def test_archived_gallery_tampering_is_detected(sample):
    create(sample)
    (sample.destination / "evidence/gallery-0/manifest.json").write_text("{}")
    with pytest.raises(ValueError, match="Checksum mismatch"):
        archive.verify_archive(sample.destination)


def test_next_named_archive_keeps_prior_bytes_and_complete_object_layouts(sample):
    create(sample)
    original_manifest = (sample.destination/'manifest.json').read_bytes()
    next_table = copy.deepcopy(sample.batch)
    next_table['cells'][B]['v'] = 'later base'
    sample.table.write_bytes(history.encode(next_table))
    destination = sample.destination.with_name('next-pass')
    result = archive.create_archive(destination,sample.table,sample.baseline,[],sample.historical,
        revision='batch-02-authoring',prior_archive=sample.destination)
    manifest = history.load_manifest(destination)
    assert result['emerald']['latest'] == 'batch-02-authoring'
    assert history.load_revision(destination,manifest,'emerald','batch-01') == sample.batch
    assert history.load_revision(destination,manifest,'emerald','batch-02-authoring') == next_table
    pair = history.resolve_models(history.load_index(destination,manifest,'emerald'),['emerald_pair'])[0]
    assert set(pair['source_keys']) == {A,B,C}
    assert any(v['layout'] == [[A,B]] for v in pair['assembly_layouts'].values())
    assert any(v['layout'] == [[B,C]] for v in pair['assembly_layouts'].values())
    assert (sample.destination/'manifest.json').read_bytes() == original_manifest
    with pytest.raises(ValueError,match='already exists'):
        archive.create_archive(destination.with_name('redefined'),sample.table,sample.baseline,[],sample.historical,
            revision='batch-01',prior_archive=sample.destination)
