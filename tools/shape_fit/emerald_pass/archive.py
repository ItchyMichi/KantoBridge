"""Create a separate immutable Emerald pass archive, usable by model_history.py restore.

The October archive and its original/approved names are never modified. Each new
archive contains those exact snapshots, pre-pass bytes and a named batch-01 result.
"""
from __future__ import annotations

import argparse
import copy
import gzip
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from tools import model_history as history

GAME = "emerald"
LATEST = "batch-01"


def _historical_bytes(archive: Path) -> tuple[dict, dict[str, bytes]]:
    manifest = history.load_manifest(archive)
    records = manifest["games"][GAME]["revisions"]
    result = {}
    for revision, (_, expected) in history.SOURCES[GAME].items():
        if records[revision]["sha256"] != expected:
            raise ValueError(f"Historical {revision} was redefined")
        history.load_revision(archive, manifest, GAME, revision)
        record = records[revision]
        packed = history.checked_read(history.archive_path(archive, record["path"]), record["gzip_sha256"])
        result[revision] = gzip.decompress(packed)
    return manifest, result


def _combined_gallery(historical_index: list[dict], galleries: list[dict], known_keys: set[str],
                      known_buildings: set[str]) -> dict:
    """Union complete-object membership; preserve every supplied assembly layout separately."""
    entries = {}
    old = [{**entry, "occurrences": [{"map": name} for name in entry.get("maps", [])]}
           for entry in historical_index]
    for source, items in [("historical", old), *[(f"gallery-{i}", g["entries"]) for i, g in enumerate(galleries)]]:
        for item in items:
            if item["id"].endswith("_before"):
                continue
            identifier, kind = item["id"], item["kind"]
            if kind not in {"cell", "compound", "building"}:
                raise ValueError(f"Unknown gallery kind: {kind}")
            keys = [] if kind == "building" else list(dict.fromkeys(item.get("source_keys", [])))
            if kind == "building" and identifier not in known_buildings:
                raise ValueError(f"Unknown gallery building: {identifier}")
            # Native context includes source terrain with no fitted override. Keep
            # that membership for selective restores, but require its loaded
            # planner evidence to explicitly prove the absent fitted entries.
            native_absent = set()
            native_records = [item, *item.get('native_baselines', {}).values()]
            for record in native_records:
                descriptor = record.get('current_native_fragments', {})
                loaded = record.get('native_current_evidence', {})
                if not descriptor or loaded.get('descriptor') != descriptor:
                    continue
                layout_keys = {key for row in descriptor.get('source_layout', []) for key in row if key}
                hashes = descriptor.get('source_cell_hashes', {})
                proved = loaded.get('record', {}).get('current_source_cell_hashes', {})
                native_absent.update(key for key in layout_keys
                                     if key in hashes and hashes[key] is None
                                     and key in proved and proved[key] is None)
            if kind != "building" and (not keys or not set(keys).issubset(known_keys | native_absent)):
                raise ValueError(f"Unknown gallery membership: {identifier}")
            if identifier in entries and entries[identifier]["kind"] != kind:
                raise ValueError(f"Conflicting gallery identity: {identifier}")
            previous = entries.get(identifier, {})
            merged = copy.deepcopy(item)
            merged["source_keys"] = list(dict.fromkeys([*previous.get("source_keys", []), *keys]))
            merged["occurrences"] = [{"map": name} for name in sorted({o["map"] for o in
                [*previous.get("occurrences", []), *item.get("occurrences", [])]})]
            native = copy.deepcopy(previous.get('native_baselines', {}))
            for name, value in item.get('native_baselines', {}).items():
                native[source+':'+name] = copy.deepcopy(value)
            if 'baseline_native_fragments' in item or 'current_native_fragments' in item:
                native[source] = {field:copy.deepcopy(item[field]) for field in
                    ('baseline_native_fragments','native_baseline_evidence','current_native_fragments','native_current_evidence') if field in item}
            if native:merged['native_baselines']=native
            if kind == "compound":
                layouts = copy.deepcopy(previous.get("assembly_layouts", {}))
                def preserve_layout(name, layout):
                    if name in layouts and layouts[name] != layout:
                        name += ':' + history.digest(history.encode(layout))[:12]
                    layouts[name] = copy.deepcopy(layout)
                for name, layout in item.get('assembly_layouts', {}).items():
                    preserve_layout(source + ':' + name, layout)
                layout = {"layout": item["layout"], "placement_mode": item.get("placement_mode")}
                for field in ('placements','reference_layout','baseline_layout','baseline_placements','baseline_native_cells','baseline_native_fragments','native_baseline_evidence','current_native_fragments','native_current_evidence'):
                    if field in item:layout[field]=item[field]
                preserve_layout(source, layout)
                merged["assembly_layouts"] = layouts
            entries[identifier] = merged
    return {"entries": list(entries.values())}


def create_archive(archive: Path, table: Path, baseline: Path, galleries: list[Path],
                   historical_archive: Path = history.DEFAULT_ARCHIVE, *,
                   revision: str = LATEST, prior_archive: Path | None = None,
                   extra_snapshots: dict[str, Path] | None = None) -> dict:
    archive = archive.resolve()
    historical_archive = historical_archive.resolve()
    if archive == historical_archive or archive.is_relative_to(historical_archive) or historical_archive.is_relative_to(archive):
        raise ValueError("New archive must be separate from the historical archive")
    if archive.exists() or archive.with_suffix(".zip").exists() or archive.with_suffix(".zip.sha256").exists():
        raise ValueError("Archive destination already exists; choose a new immutable pass name")
    historical_manifest, snapshots = _historical_bytes(historical_archive)
    snapshots["pre-pass"] = baseline.read_bytes()
    if not revision or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789-_' for c in revision):
        raise ValueError('Use a simple named immutable revision')
    if prior_archive is not None:
        prior_archive = prior_archive.resolve()
        if archive == prior_archive or archive.is_relative_to(prior_archive) or prior_archive.is_relative_to(archive):
            raise ValueError('New archive must be separate from the prior archive')
        verify_archive(prior_archive)
        prior_manifest = history.load_manifest(prior_archive)
        for name, record in prior_manifest['games'][GAME]['revisions'].items():
            packed = history.checked_read(history.archive_path(prior_archive, record['path']), record['gzip_sha256'])
            data = gzip.decompress(packed)
            if name in snapshots and snapshots[name] != data:
                raise ValueError('Prior revision was redefined: ' + name)
            snapshots[name] = data
    for name, path in {**(extra_snapshots or {}), revision: table}.items():
        if not name or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789-_' for c in name):
            raise ValueError('Invalid snapshot revision name')
        data = path.read_bytes()
        if name in snapshots and snapshots[name] != data:
            raise ValueError('Immutable revision already exists: ' + name)
        snapshots[name] = data
    revisions = {name: history.decode(data) for name, data in snapshots.items()}
    for value in revisions.values():
        history.validate_table(value, GAME)
    historical_index = history.load_index(historical_archive, historical_manifest, GAME)
    if prior_archive is not None:
        historical_index += history.load_index(prior_archive, prior_manifest, GAME)
    hashes = {history.digest(data) for data in snapshots.values()}
    gallery_bytes, gallery_docs = [], []
    for path in galleries:
        path = path / "manifest.json" if path.is_dir() else path
        data = path.read_bytes()
        doc = history.decode(data)
        if doc.get("schema") != 1 or doc.get("game") != GAME or doc.get("source", {}).get("table_sha256") not in hashes:
            raise ValueError(f"Gallery is not bound to an archived Emerald revision: {path}")
        gallery_bytes.append(data)
        gallery_docs.append(doc)
    known_keys = set().union(*(value["cells"] for value in revisions.values()))
    known_buildings = {b["id"] for value in revisions.values() for b in value.get("buildings", [])}
    combined = _combined_gallery(historical_index, gallery_docs, known_keys, known_buildings)
    index = history.build_index(GAME, revisions, [combined], {})
    layouts = {e["id"]: e["assembly_layouts"] for e in combined["entries"] if e["kind"] == "compound"}
    native_baselines = {e['id']:e['native_baselines'] for e in combined['entries'] if 'native_baselines' in e}
    for entry in index:
        # Existing `changed` keeps its historical meaning; the new field names the new comparison.
        entry["changed_in_pass"] = entry["versions"]["pre-pass"] != entry["versions"][revision]
        if entry["id"] in layouts:
            entry["assembly_layouts"] = layouts[entry["id"]]
        if entry['id'] in native_baselines:
            entry['native_baselines']=native_baselines[entry['id']]
    index_data = history.encode(index)
    # No destination writes occur until every input and membership has been validated.
    archive.mkdir(parents=True, exist_ok=False)
    records = {name: history.store_snapshot(archive, GAME, name, data) for name, data in snapshots.items()}
    evidence = []
    for number, data in enumerate(gallery_bytes):
        relative = f"evidence/gallery-{number}/manifest.json"
        history.immutable_write(archive / relative, data)
        evidence.append({"path": relative, "sha256": history.digest(data)})
    history.immutable_write(archive / "index/emerald.json", index_data)
    manifest = {"schema": 1, "kind": "emerald-immutable-pass", "latest": revision,
                "historical_manifest_sha256": history.digest((historical_archive / "manifest.json").read_bytes()),
                "gallery_manifests": evidence, "games": {GAME: {"revisions": records,
                    "latest": revision, "index": {"path": "index/emerald.json", "sha256": history.digest(index_data)},
                    "models": len(index), "changed": sum(e["changed"] for e in index),
                    "changed_in_pass": sum(e["changed_in_pass"] for e in index)}}}
    manifest_data = history.encode(manifest)
    history.immutable_write(archive / "manifest.json", manifest_data)
    history.immutable_write(archive / "manifest.sha256", (history.digest(manifest_data) + "  manifest.json\n").encode())
    history.immutable_write(archive / "README.md", (
        "# Emerald immutable pass archive\n\n"
        "`original`, `trees-approved`, and `approved` retain their October checkpoint bytes. "
        f"`pre-pass` is the input baseline; `{revision}` is the latest named result, not a renamed approval.\n\n"
        "Use `python tools/model_history.py restore --archive <this-directory> --game emerald "
        f"--model <source-key-or-complete-object-id> --revision {revision}` to preview a selective restore. "
        "Add `--write` only to apply it. Every write backs up the complete previous target. "
        "Use --table with a scratch path to rehearse.\n\n"
        "Complete objects select the union of all recorded member keys. Assembly layouts and source "
        "gallery manifests are preserved alongside version hashes. This archive does not itself establish visual approval.\n"
    ).encode())
    history.ensure_portable_backup(archive)
    return verify_archive(archive)


def verify_archive(archive: Path) -> dict:
    result = history.verify(archive)
    manifest = history.load_manifest(archive)
    for revision, (_, expected) in history.SOURCES[GAME].items():
        if manifest["games"][GAME]["revisions"][revision]["sha256"] != expected:
            raise ValueError(f"Historical {revision} was redefined")
    for record in manifest.get("gallery_manifests", []):
        history.checked_read(history.archive_path(archive, record["path"]), record["sha256"])
    result[GAME]["latest"] = manifest["latest"]
    result[GAME]["changed_in_pass"] = manifest["games"][GAME]["changed_in_pass"]
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--table", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--gallery", type=Path, action="append", default=[])
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument('--revision', default=LATEST)
    parser.add_argument('--prior-archive', type=Path)
    parser.add_argument('--snapshot', action='append', default=[], metavar='NAME=PATH')
    args = parser.parse_args(argv)
    extra = {}
    for item in args.snapshot:
        name, separator, path = item.partition('=')
        if not separator or name in extra: raise ValueError('Use unique NAME=PATH snapshots')
        extra[name] = Path(path)
    print(history.encode(create_archive(args.archive, args.table, args.baseline, args.gallery,
        revision=args.revision, prior_archive=args.prior_archive, extra_snapshots=extra)).decode())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
