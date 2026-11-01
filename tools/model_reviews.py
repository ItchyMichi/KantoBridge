"""Inspect the local model-review queue and attach written repair proposals.

    python tools/model_reviews.py list
    python tools/model_reviews.py show <uuid>
    python tools/model_reviews.py propose <uuid> --method hand_model --text-file proposal.txt
    python tools/model_reviews.py status <uuid> resolved

Use --root for another client's kantobridge/model-reviews directory. This tool
only edits review notes and statuses; it never changes models or publishes files.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import json
import math
import os
from pathlib import Path
import re
import sys
import tempfile

DEFAULT_ROOT = Path(__file__).resolve().parent.parent / "local" / "minecraft" / "kantobridge" / "model-reviews"
STATUSES = ("open", "proposed", "resolved")
CATEGORIES = ("background", "missing_parts", "shape", "texture", "other")
METHODS = ("mask", "depth_rule", "hand_model", "other")
ID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")
MAX_JSON_BYTES = 2 * 1024 * 1024
FIELDS = {"version", "id", "createdAt", "updatedAt", "status", "category", "comment", "context", "proposal", "screenshot"}


def _instant(value: object) -> datetime:
    if not isinstance(value, str):
        raise ValueError("timestamp must be a string")
    try:
        instant = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError("invalid timestamp") from exc
    if instant.tzinfo is None:
        raise ValueError("timestamp needs a timezone")
    return instant


def validate(review: object, issue_id: str) -> dict:
    _json_values(review)
    if not isinstance(review, dict) or type(review.get("version")) is not int or review["version"] != 1:
        raise ValueError("unsupported review version")
    if not set(review).issubset(FIELDS):
        raise ValueError("unsupported review field")
    if review.get("id") != issue_id:
        raise ValueError("review id does not match its directory")
    if review.get("status") not in STATUSES or review.get("category") not in CATEGORIES:
        raise ValueError("unknown status or category")
    if not isinstance(review.get("comment"), str) or _text_length(review["comment"]) > 4000:
        raise ValueError("invalid comment")
    if not isinstance(review.get("context"), dict):
        raise ValueError("context must be an object")
    if _instant(review.get("updatedAt")) < _instant(review.get("createdAt")):
        raise ValueError("updatedAt precedes createdAt")
    if "screenshot" in review and review["screenshot"] != "screenshot.png":
        raise ValueError("invalid screenshot filename")
    if "proposal" in review:
        proposal = review["proposal"]
        if not isinstance(proposal, dict) or proposal.get("method", "other") not in METHODS:
            raise ValueError("invalid proposal method")
        if not set(proposal).issubset({"method", "text", "createdAt"}):
            raise ValueError("unsupported proposal field")
        if not isinstance(proposal.get("text"), str) or not proposal["text"].strip() or _text_length(proposal["text"]) > 16000:
            raise ValueError("invalid proposal text")
        _instant(proposal.get("createdAt"))
    elif review["status"] == "proposed":
        raise ValueError("a proposed review needs a proposal")
    return review


def _text_length(text: str) -> int:
    # The editor and Java record count UTF-16 code units, including two for a supplementary character.
    return len(text.encode("utf-16-le")) // 2


def _json_values(value, depth=0):
    if depth > 64:
        raise ValueError("review JSON is too deeply nested")
    if isinstance(value, dict):
        for key, item in value.items():
            _json_values(key, depth + 1)
            _json_values(item, depth + 1)
    elif isinstance(value, list):
        for item in value:
            _json_values(item, depth + 1)
    elif isinstance(value, str) and any(0xD800 <= ord(char) <= 0xDFFF for char in value):
        raise ValueError("invalid Unicode in review JSON")
    elif isinstance(value, float) and not math.isfinite(value):
        raise ValueError("non-finite number in review JSON")


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate field in review JSON")
        result[key] = value
    return result


def issue_directory(root: Path, issue_id: str) -> Path:
    if not ID.fullmatch(issue_id):
        raise ValueError("invalid review id")
    directory = root / issue_id
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("review directory is missing or is a link")
    return directory


def read_review(root: Path, issue_id: str) -> dict:
    file = issue_directory(root, issue_id) / "review.json"
    if file.is_symlink() or not file.is_file() or file.stat().st_size > MAX_JSON_BYTES:
        raise ValueError("review file is missing, linked, or too large")
    return validate(json.loads(file.read_text(encoding="utf-8"), object_pairs_hook=_unique_object), issue_id)


def read_reviews(root: Path) -> tuple[list[dict], list[tuple[str, str]]]:
    if not root.exists():
        return [], []
    reviews, failures = [], []
    for entry in sorted(root.iterdir()):
        if entry.name.startswith(".capture-"):
            continue
        if not entry.is_dir() and not entry.is_symlink():
            continue
        try:
            reviews.append(read_review(root, entry.name))
        except (OSError, ValueError, TypeError) as exc:
            failures.append((entry.name, str(exc)))
    reviews.sort(key=lambda row: (_instant(row["createdAt"]), row["id"]), reverse=True)
    return reviews, failures


def _next_timestamp(review: dict) -> str:
    now = max(datetime.now(timezone.utc), _instant(review["updatedAt"]) + timedelta(microseconds=1))
    return now.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


@contextmanager
def _write_lock(directory: Path):
    """Use the same byte-range lock as Java FileChannel; the OS releases it on process exit."""
    path = directory / ".review.lock"
    if path.is_symlink():
        raise ValueError("review lock is a link")
    with open(path, "a+b") as lock:
        lock.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.lockf(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB, 1, 0)
        except OSError as exc:
            raise ValueError("review is being updated; retry") from exc
        try:
            yield
        finally:
            lock.seek(0)
            if os.name == "nt":
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.lockf(lock.fileno(), fcntl.LOCK_UN, 1, 0)


def write_review(root: Path, review: dict, expected_updated_at: str) -> None:
    """Replace only an existing valid record, preserving it on an error or a stale edit."""
    directory = issue_directory(root, review["id"])
    with _write_lock(directory):
        _write_locked(root, directory, review, expected_updated_at)


def _write_locked(root: Path, directory: Path, review: dict, expected_updated_at: str) -> None:
    issue_id = review["id"]
    previous = read_review(root, issue_id)
    if previous["updatedAt"] != expected_updated_at:
        raise ValueError("review was changed; reload it before updating")
    for field in ("createdAt", "category", "comment", "context", "screenshot"):
        if previous.get(field) != review.get(field):
            raise ValueError("a review update cannot replace its captured evidence")
    validate(review, issue_id)
    content = (json.dumps(review, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if len(content) > MAX_JSON_BYTES:
        raise ValueError("review context is too large")
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(prefix="review.", suffix=".tmp", dir=directory, delete=False) as temp:
            temp_path = Path(temp.name)
            temp.write(content)
            temp.flush()
            os.fsync(temp.fileno())
        os.replace(temp_path, directory / "review.json")
    finally:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    result.add_argument("--root", type=Path, default=DEFAULT_ROOT, help="client's model-reviews directory")
    commands = result.add_subparsers(dest="command", required=True)
    listing = commands.add_parser("list", help="list captured issues, newest first")
    listing.add_argument("--status", choices=STATUSES)
    listing.add_argument("--json", action="store_true", help="print complete review records as JSON")
    showing = commands.add_parser("show", help="print one complete review record")
    showing.add_argument("id")
    propose = commands.add_parser("propose", help="attach a written repair proposal; no model changes")
    propose.add_argument("id")
    propose.add_argument("--method", choices=METHODS, default="other")
    text = propose.add_mutually_exclusive_group(required=True)
    text.add_argument("--text-file", type=Path, help="UTF-8 text file containing the proposal")
    text.add_argument("--text", help="proposal text")
    status = commands.add_parser("status", help="resolve, reopen, or mark an issue with a proposal as proposed")
    status.add_argument("id")
    status.add_argument("status", choices=STATUSES)
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        root = args.root.absolute()
        if args.command == "list":
            reviews, failures = read_reviews(root)
            if args.status:
                reviews = [review for review in reviews if review["status"] == args.status]
            if args.json:
                print(json.dumps(reviews, ensure_ascii=False, indent=2))
            elif not reviews:
                print("No model reviews.")
            else:
                for review in reviews:
                    context = review["context"]
                    place = "/".join(str(context.get(key, "?")) for key in ("game", "map"))
                    summary = " ".join(review["comment"].split())[:100]
                    print(f"{review['id']}  {review['status']:8}  {review['category']:13}  {place}  {summary}")
            for issue_id, message in failures:
                print(f"Unreadable review {issue_id}: {message}", file=sys.stderr)
            return 1 if failures else 0
        review = read_review(root, args.id)
        if args.command == "show":
            print(json.dumps(review, ensure_ascii=False, indent=2))
            return 0
        expected = review["updatedAt"]
        review["updatedAt"] = _next_timestamp(review)
        if args.command == "propose":
            text = args.text_file.read_text(encoding="utf-8") if args.text_file else args.text
            review["proposal"] = {"method": args.method, "text": text, "createdAt": review["updatedAt"]}
            review["status"] = "proposed"
        else:
            review["status"] = args.status
        write_review(root, review, expected)
        print(f"{review['id']}: {review['status']}")
        return 0
    except (OSError, ValueError, TypeError) as exc:
        print(f"Model review error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
