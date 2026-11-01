"""Archive fitted-model revisions and selectively restore complete model entries.

Uses only the Python standard library. No ROM, sprite pixels or Minecraft process
is needed. Restore previews by default; --write is required to change a table.
"""
from __future__ import annotations

import argparse
import copy
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from datetime import datetime, timezone
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ARCHIVE = ROOT / "local/model-history/2026-10-31"
TABLES = ROOT / "fabric-bridge/src/main/resources/kantobridge/fitted_shapes"
# Exact captured files, independently checked against the approved resource tables.
SOURCES = {
    "blue": {
        "original": ("baseline-blue.json", "1427252527f219c5811a5a1f41af93ec72aa07740cfd51fe7b9ec65de1156758"),
        "tree-approved": ("bedroom-console-baseline.json", "828189174466e4582d46bc352b8934fd6b122f19bdc2409b326aaca92700384f"),
        "pilots-approved": ("blue-rollout-baseline.json", "67efff901203e61cee4d262d317744aebe1917ba8ab25c0e4d55746a44ee1529"),
        "approved": ("blue-review/fitted-current.json", "53222c138194f382cca00d7786001ac97892bfb01058c912736cb199c2a06c40"),
    },
    "emerald": {
        "original": ("emerald-trees-baseline.json", "84bbd903995b9a0aeb4bf57f4262cd69dc7f44f8e7493615dbbc65fc7bb18f61"),
        "trees-approved": ("buildings-baseline-emerald.json", "8a3c79fc6a37db84b6a5fc2ff6dec68d95db9348b1038c07c65831157ea685d8"),
        "approved": ("emerald-buildings/fitted-current.json", "7958d43cb8545ae006b3568bb6b86d809c7def79cb128a7789c36016756797d6"),
    },
    "crystal": {
        "original": ("buildings-baseline-crystal.json", "ee446c32686a3866088238f53e5512c22d4763e68c84973daeef32a927a81733"),
        "approved": ("crystal-buildings/fitted-current.json", "390a39cfe65969d0999eb3d272325d05b87c4e574aa72e6c2eba82e19177a422"),
    },
}
GALLERIES = {"blue": ["blue-review"], "emerald": ["emerald-trees", "emerald-buildings"],
             "crystal": ["crystal-buildings"]}


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def encode(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def decode(data: bytes):
    return json.loads(data.decode("utf-8-sig"))


def safe_id(value: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_-]+", "_", value).strip("_")


def immutable_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("xb") as stream:
            stream.write(data)
    except FileExistsError:
        if path.read_bytes() != data:
            raise ValueError(f"Refusing to replace existing archive file: {path}")


def checked_read(path: Path, expected: str) -> bytes:
    data = path.read_bytes()
    if digest(data) != expected:
        raise ValueError(f"Checksum mismatch: {path}")
    return data


def archive_path(archive: Path, relative: str) -> Path:
    path = (archive / relative).resolve()
    if not path.is_relative_to(archive.resolve()):
        raise ValueError("Archive path escapes its directory")
    return path


def store_snapshot(archive: Path, game: str, revision: str, data: bytes) -> dict:
    table = decode(data)
    validate_table(table, game)
    relative = f"snapshots/{game}/{revision}.json.gz"
    packed = gzip.compress(data, mtime=0)
    immutable_write(archive / relative, packed)
    return {"path": relative, "sha256": digest(data), "gzip_sha256": digest(packed),
            "bytes": len(data), "cells": len(table["cells"]),
            "buildings": len(table.get("buildings", []))}


def validate_table(table: dict, game: str) -> None:
    if table.get("game") != game or not isinstance(table.get("cells"), dict):
        raise ValueError(f"Not a fitted table for {game}")
    ids = [b["id"] for b in table.get("buildings", [])]
    if len(set(ids)) != len(ids):
        raise ValueError("Duplicate building IDs")


def load_manifest(archive: Path) -> dict:
    expected = (archive / "manifest.sha256").read_text(encoding="ascii").split()[0]
    manifest = decode(checked_read(archive / "manifest.json", expected))
    if manifest.get("schema") != 1:
        raise ValueError("Unsupported model-history schema")
    return manifest


def load_revision(archive: Path, manifest: dict, game: str, revision: str) -> dict:
    try:
        record = manifest["games"][game]["revisions"][revision]
    except KeyError as exc:
        raise ValueError(f"Unknown revision {game}/{revision}") from exc
    packed = checked_read(archive_path(archive, record["path"]), record["gzip_sha256"])
    data = gzip.decompress(packed)
    if digest(data) != record["sha256"]:
        raise ValueError(f"Uncompressed checksum mismatch: {game}/{revision}")
    table = decode(data)
    validate_table(table, game)
    return table


def load_index(archive: Path, manifest: dict, game: str) -> list[dict]:
    record = manifest["games"][game]["index"]
    return decode(checked_read(archive_path(archive, record["path"]), record["sha256"]))


def build_index(game: str, revisions: dict[str, dict], galleries: list[dict],
                labels: dict[str, str]) -> list[dict]:
    keys = sorted(set().union(*(table["cells"] for table in revisions.values())))
    buildings = sorted(set().union(*({b["id"] for b in table.get("buildings", [])}
                                    for table in revisions.values())))
    entries = {f"{game}_{safe_id(key)}": {
        "id": f"{game}_{safe_id(key)}", "label": labels.get(key, key),
        "kind": "cell", "source_keys": [key], "building_ids": [], "maps": []}
        for key in keys}
    if len(entries) != len(keys):
        raise ValueError("Cell IDs collide after normalization")
    entries.update({key: {"id": key, "label": key, "kind": "building",
                         "source_keys": [], "building_ids": [key], "maps": []}
                    for key in buildings})
    for gallery in galleries:
        for item in gallery["entries"]:
            if item["id"].endswith("_before"):
                continue
            model_id = item["id"]
            sources = list(dict.fromkeys(item.get("source_keys", [])))
            kind = item["kind"]
            label = item["label"]
            if kind == "cell":
                label = labels.get(sources[0], label)
            elif kind == "compound" and (label.startswith("Stack:") or label == model_id):
                label = " + ".join(dict.fromkeys(labels.get(key, key) for key in sources))
            entries[model_id] = {
                "id": model_id, "label": label, "kind": kind,
                "source_keys": [] if kind == "building" else sources,
                "building_ids": [model_id] if kind == "building" else [],
                "maps": sorted({o["map"] for o in item.get("occurrences", [])}),
            }
            if kind == "compound":
                entries[model_id]["layout"] = item["layout"]
                entries[model_id]["placement_mode"] = item.get("placement_mode")
    building_tables = {rev: {b["id"]: b for b in table.get("buildings", [])}
                       for rev, table in revisions.items()}
    for entry in entries.values():
        entry["versions"] = {}
        for rev, table in revisions.items():
            values = {"cells": {key: table["cells"].get(key) for key in entry["source_keys"]},
                      "buildings": {key: building_tables[rev].get(key) for key in entry["building_ids"]}}
            entry["versions"][rev] = digest(encode(values))
        entry["changed"] = entry["versions"]["original"] != entry["versions"]["approved"]
    return sorted(entries.values(), key=lambda e: e["id"])


def initialize(archive: Path) -> dict:
    if (archive / "manifest.json").exists():
        verify(archive, check_portable=False)
        ensure_portable_backup(archive)
        return verify(archive)
    base = ROOT / "local/model-gallery"
    manifest = {"schema": 1, "date": "2026-10-31", "games": {}}
    for game, sources in SOURCES.items():
        revisions, records = {}, {}
        for revision, (relative, expected) in sources.items():
            data = checked_read(base / relative, expected)
            revisions[revision] = decode(data)
            records[revision] = store_snapshot(archive, game, revision, data)
            records[revision]["source"] = f"local/model-gallery/{relative}"
        galleries = [decode((base / name / "manifest.json").read_bytes()) for name in GALLERIES[game]]
        labels = {}
        if game == "blue":
            ledger = decode((base / "blue-review/review-ledger.json").read_bytes())
            labels = {e["key"]: e["label"] for e in ledger["entries"]}
        index = build_index(game, revisions, galleries, labels)
        index_data = encode(index)
        index_path = f"index/{game}.json"
        immutable_write(archive / index_path, index_data)
        manifest["games"][game] = {"revisions": records,
                                   "index": {"path": index_path, "sha256": digest(index_data)},
                                   "models": len(index), "changed": sum(e["changed"] for e in index)}
    immutable_write(archive / "manifest.json", encode(manifest))
    immutable_write(archive / "manifest.sha256", (digest(encode(manifest)) + "  manifest.json\n").encode("ascii"))
    immutable_write(archive / "README.md", (
        "# Model history — 31 October 2026\n\n"
        "Original, pilot and approved fitted models for Blue, Emerald and Crystal. "
        "Snapshots preserve the exact original table bytes (gzip compressed). "
        "Indexes include all cells, building IDs and reviewed complete objects.\n\n"
        "Use `python tools/model_history.py verify` to check integrity. "
        "See `docs/MODEL_HISTORY.md` in the project for selective restore commands. "
        "No live model was reverted when this archive was created.\n\n"
        "Keep this directory or the adjacent ZIP when cleaning local galleries. "
        "These are local backups, not an off-device copy. "
        "Every later write creates a full before-table backup under switches/.\n"
    ).encode("utf-8"))
    ensure_portable_backup(archive)
    return verify(archive)


def ensure_portable_backup(archive: Path) -> None:
    """Complete the portable backup when init previously stopped after the manifest."""
    zip_path = archive.with_suffix(".zip")
    if zip_path.exists():
        checksum_path = zip_path.with_suffix(".zip.sha256")
        if checksum_path.exists():
            checked_read(zip_path, checksum_path.read_text(encoding="ascii").split()[0])
            return
        # A crash may leave a complete ZIP before its checksum was written. Only
        # recover that case after comparing all archived snapshots and indexes.
        with zipfile.ZipFile(zip_path) as bundle:
            if bundle.testzip():
                raise ValueError(f"Incomplete portable backup: {zip_path}")
            for path in sorted(archive.rglob("*")):
                relative = path.relative_to(archive)
                if path.is_file() and relative.parts[0] != "switches":
                    if bundle.read(f"{archive.name}/{relative.as_posix()}") != path.read_bytes():
                        raise ValueError(f"Portable backup differs: {relative}")
        immutable_write(checksum_path, (digest(zip_path.read_bytes()) + f"  {zip_path.name}\n").encode("ascii"))
        return
    with zipfile.ZipFile(zip_path, "x", compression=zipfile.ZIP_DEFLATED) as bundle:
        for path in sorted(archive.rglob("*")):
            if path.is_file():
                bundle.write(path, f"{archive.name}/{path.relative_to(archive).as_posix()}")
        bundle.write(Path(__file__), "tools/model_history.py")
        guide = ROOT / "docs/MODEL_HISTORY.md"
        if guide.exists():
            bundle.write(guide, "docs/MODEL_HISTORY.md")
    immutable_write(zip_path.with_suffix(".zip.sha256"),
                    (digest(zip_path.read_bytes()) + f"  {zip_path.name}\n").encode("ascii"))


def verify(archive: Path, check_portable: bool = True) -> dict:
    manifest = load_manifest(archive)
    result = {}
    for game, record in manifest["games"].items():
        for revision in record["revisions"]:
            load_revision(archive, manifest, game, revision)
        index = load_index(archive, manifest, game)
        result[game] = {"revisions": list(record["revisions"]), "models": len(index),
                        "changed": record["changed"]}
    zip_path = archive.with_suffix(".zip")
    if check_portable and zip_path.exists() and zip_path.with_suffix(".zip.sha256").exists():
        expected = zip_path.with_suffix(".zip.sha256").read_text(encoding="ascii").split()[0]
        checked_read(zip_path, expected)
        with zipfile.ZipFile(zip_path) as bundle:
            if bundle.testzip():
                raise ValueError("Invalid backup ZIP member")
    elif check_portable and zip_path.exists():
        raise ValueError("Portable backup lacks checksum; run init to recover it")
    for path in sorted((archive / "switches").glob("*/transaction.json")):
        record = decode(path.read_bytes())
        before = gzip.decompress((path.parent / "before.json.gz").read_bytes())
        if digest(before) != record["before_sha256"]:
            raise ValueError(f"Switch backup checksum mismatch: {path.parent}")
    return result


def resolve_models(index: list[dict], selectors: list[str]) -> list[dict]:
    selected = {}
    for selector in selectors:
        matches = [e for e in index if e["id"] == selector or
                   (e["kind"] == "cell" and selector in e["source_keys"])]
        if not matches:
            matches = [e for e in index if e["label"].casefold() == selector.casefold()]
        if len(matches) != 1:
            ids = ", ".join(e["id"] for e in matches)
            raise ValueError(f"{'Ambiguous' if matches else 'Unknown'} model: {selector}. {ids}")
        selected[matches[0]["id"]] = matches[0]
    return list(selected.values())


def restore_table(current: dict, source: dict, selected: list[dict]) -> tuple[dict, dict]:
    """Restore entire selected entries; absence restores the prior fallback behavior."""
    result = copy.deepcopy(current)
    keys = sorted({key for e in selected for key in e["source_keys"]})
    ids = sorted({key for e in selected for key in e["building_ids"]})
    changed, removed = [], []
    for key in keys:
        if key in source["cells"]:
            if result["cells"].get(key) != source["cells"][key]:
                changed.append(key)
            result["cells"][key] = copy.deepcopy(source["cells"][key])
        elif key in result["cells"]:
            del result["cells"][key]
            removed.append(key)
    source_buildings = {b["id"]: b for b in source.get("buildings", [])}
    current_buildings = {b["id"]: b for b in current.get("buildings", [])}
    for key in ids:
        if key not in source_buildings and key in current_buildings:
            removed.append(key)
        elif source_buildings.get(key) != current_buildings.get(key):
            changed.append(key)
    if ids:
        result["buildings"] = [copy.deepcopy(source_buildings[b["id"]]) if b["id"] in ids else b
                               for b in result.get("buildings", [])
                               if b["id"] not in ids or b["id"] in source_buildings]
        source_order=source.get('buildings',[])
        for position,building in enumerate(source_order):
            if building['id'] not in ids or building['id'] in current_buildings:
                continue
            # Template precedence is meaningful. Reinsert a previously absent
            # selected template before its next surviving source neighbour,
            # while leaving every unselected template's relative order intact.
            positions={b['id']:i for i,b in enumerate(result['buildings'])}
            successor=next((b['id'] for b in source_order[position+1:] if b['id'] in positions),None)
            insertion=positions[successor] if successor is not None else len(result['buildings'])
            result['buildings'].insert(insertion,copy.deepcopy(building))
    return result, {"source_keys": keys, "building_ids": ids, "changed": changed, "removed": removed}


def restore(archive: Path, game: str, selectors: list[str], revision: str,
            table_path: Path, write: bool = False) -> dict:
    manifest = load_manifest(archive)
    index = load_index(archive, manifest, game)
    source = load_revision(archive, manifest, game, revision)
    selected = resolve_models(index, selectors)
    before = table_path.read_bytes()
    current = decode(before)
    validate_table(current, game)
    after, report = restore_table(current, source, selected)
    selected_ids = {e["id"] for e in selected}
    report.update({"game": game, "revision": revision, "models": sorted(selected_ids),
                   "table": str(table_path.resolve()), "written": False,
                   "maps": sorted({m for e in selected for m in e.get("maps", [])})})
    keys = set(report["source_keys"])
    report["shared_objects"] = [{"id": e["id"], "label": e["label"]} for e in index
                                if e["kind"] == "compound" and e["id"] not in selected_ids
                                and keys.intersection(e["source_keys"])]
    if not write or not (report["changed"] or report["removed"]):
        return report
    # All unaffected table fields remain semantically identical. Exact previous bytes
    # are retained as well, so formatting and later user edits remain recoverable.
    after_bytes = encode(after)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ") + "-" + uuid.uuid4().hex[:8]
    transaction = archive / "switches" / stamp
    immutable_write(transaction / "before.json.gz", gzip.compress(before, mtime=0))
    record = dict(report, before_sha256=digest(before), after_sha256=digest(after_bytes),
                  backup="before.json.gz", status="prepared")
    immutable_write(transaction / "transaction.json", encode(record))
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=table_path.parent, prefix=".model-history-", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(after_bytes)
            stream.flush()
            os.fsync(stream.fileno())
        if table_path.read_bytes() != before:
            raise ValueError("Target changed during restore; refusing to overwrite newer edits")
        os.replace(temporary, table_path)
        immutable_write(transaction / "completed.json", encode({"after_sha256": digest(after_bytes)}))
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()
    report.update(written=True, backup=str(transaction.resolve()))
    return report


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["init", "verify", "list", "restore"])
    parser.add_argument("--archive", type=Path, default=DEFAULT_ARCHIVE)
    parser.add_argument("--game", choices=list(SOURCES), default="blue")
    parser.add_argument("--query", default="")
    parser.add_argument("--model", action="append", default=[])
    parser.add_argument("--revision", default="original")
    parser.add_argument("--table", type=Path, help="Override target fitted table (e.g. a scratch copy)")
    parser.add_argument("--write", action="store_true", help="Apply a restore; otherwise preview only")
    args = parser.parse_args(argv)
    try:
        if args.command == "init":
            result = initialize(args.archive)
        elif args.command == "verify":
            result = verify(args.archive)
        elif args.command == "list":
            index = load_index(args.archive, load_manifest(args.archive), args.game)
            query = args.query.casefold()
            result = [{k: e[k] for k in ("id", "label", "kind", "changed", "maps")}
                      for e in index if query in " ".join([e["id"], e["label"],
                          *e["source_keys"], *e["building_ids"], *e["maps"]]).casefold()]
        else:
            if not args.model:
                parser.error("restore requires at least one --model")
            result = restore(args.archive, args.game, args.model, args.revision,
                             args.table or TABLES / f"{args.game}.json", args.write)
        print(json.dumps(result, ensure_ascii=True, indent=2))
        return 0
    except (ValueError, OSError, KeyError, EOFError, zipfile.BadZipFile) as exc:
        parser.exit(1, f"Model history: {exc}\n")


if __name__ == "__main__":
    raise SystemExit(main())
