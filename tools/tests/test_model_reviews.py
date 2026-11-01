"""The local model-review queue: synthetic captures only; no game data is opened."""

import copy
import json
from pathlib import Path
import subprocess
import sys

import pytest

import model_reviews


REVIEW_ID = "cf9d27ed-475a-440f-9651-51d0f0f5d4f0"
SCRIPT = Path(model_reviews.__file__)


def capture(root):
    review = {
        "version": 1, "id": REVIEW_ID,
        "createdAt": "2026-10-31T12:00:00Z", "updatedAt": "2026-10-31T12:00:00Z",
        "status": "open", "category": "missing_parts", "comment": "Keep both posts.\nThe second is missing.",
        "context": {"game": "test", "map": "SYNTHETIC_ROOM", "shapeKey": "unit:synthetic", "cx": 3, "cy": 4},
        "screenshot": "screenshot.png",
    }
    directory = root / REVIEW_ID
    directory.mkdir(parents=True)
    (directory / "review.json").write_text(json.dumps(review), encoding="utf-8")
    (directory / "screenshot.png").write_bytes(b"synthetic screenshot")
    return review


def run(root, *args):
    return subprocess.run([sys.executable, str(SCRIPT), "--root", str(root), *args], capture_output=True, text=True, timeout=20)


def test_proposal_and_resolution_preserve_the_captured_evidence(tmp_path):
    original = capture(tmp_path)
    text = "Use a hand model.\nRetain both posts and the rail between them."
    proposal_file = tmp_path / "proposal.txt"
    proposal_file.write_text(text, encoding="utf-8")
    done = run(tmp_path, "propose", REVIEW_ID, "--method", "hand_model", "--text-file", str(proposal_file))
    assert done.returncode == 0, done.stderr
    proposed = model_reviews.read_review(tmp_path, REVIEW_ID)
    assert proposed["status"] == "proposed"
    assert proposed["proposal"]["text"] == text
    assert proposed["proposal"]["method"] == "hand_model"
    for field in ("context", "comment", "category", "createdAt", "screenshot"):
        assert proposed[field] == original[field]
    assert (tmp_path / REVIEW_ID / "screenshot.png").read_bytes() == b"synthetic screenshot"
    done = run(tmp_path, "status", REVIEW_ID, "resolved")
    assert done.returncode == 0, done.stderr
    resolved = model_reviews.read_review(tmp_path, REVIEW_ID)
    assert resolved["status"] == "resolved"
    assert resolved["proposal"] == proposed["proposal"]
    assert not list((tmp_path / REVIEW_ID).glob("*.tmp"))


def test_list_filter_and_show_have_matching_records(tmp_path):
    original = capture(tmp_path)
    listing = run(tmp_path, "list", "--status", "open", "--json")
    assert listing.returncode == 0, listing.stderr
    assert json.loads(listing.stdout) == [original]
    showing = run(tmp_path, "show", REVIEW_ID)
    assert showing.returncode == 0, showing.stderr
    assert json.loads(showing.stdout) == original
    filtered = run(tmp_path, "list", "--status", "resolved", "--json")
    assert json.loads(filtered.stdout) == []


def test_malformed_record_does_not_hide_the_valid_queue_or_get_overwritten(tmp_path):
    original = capture(tmp_path)
    bad_id = "a87dfae4-174d-424f-97a8-b9ae25ce37ac"
    bad_dir = tmp_path / bad_id
    bad_dir.mkdir()
    bad_file = bad_dir / "review.json"
    bad_file.write_text("broken json", encoding="utf-8")
    listing = run(tmp_path, "list", "--json")
    assert listing.returncode == 1
    assert json.loads(listing.stdout) == [original]
    assert bad_id in listing.stderr
    assert run(tmp_path, "status", bad_id, "resolved").returncode == 1
    assert bad_file.read_text() == "broken json"


def test_invalid_and_stale_updates_leave_the_original_untouched(tmp_path):
    original = capture(tmp_path)
    file = tmp_path / REVIEW_ID / "review.json"
    before = file.read_bytes()
    assert run(tmp_path, "status", REVIEW_ID, "proposed").returncode == 1
    assert run(tmp_path, "propose", REVIEW_ID, "--text", " ").returncode == 1
    assert run(tmp_path, "show", "../outside").returncode == 1
    assert file.read_bytes() == before
    modified = copy.deepcopy(original)
    modified["context"]["map"] = "DIFFERENT_ROOM"
    with pytest.raises(ValueError, match="captured evidence"):
        model_reviews.write_review(tmp_path, modified, original["updatedAt"])
    with pytest.raises(ValueError, match="reload"):
        model_reviews.write_review(tmp_path, original, "2020-01-01T00:00:00Z")
    assert file.read_bytes() == before


def test_empty_queue_does_not_create_files(tmp_path):
    root = tmp_path / "missing"
    done = run(root, "list", "--json")
    assert done.returncode == 0 and json.loads(done.stdout) == []
    assert not root.exists()


def test_a_competing_writer_retries_and_a_crash_staging_directory_is_not_an_issue(tmp_path):
    original = capture(tmp_path)
    (tmp_path / ".capture-interrupted").mkdir()
    with model_reviews._write_lock(tmp_path / REVIEW_ID):
        competing = run(tmp_path, "status", REVIEW_ID, "resolved")
        assert competing.returncode == 1
        assert "being updated" in competing.stderr
    assert model_reviews.read_review(tmp_path, REVIEW_ID) == original
    assert run(tmp_path, "status", REVIEW_ID, "resolved").returncode == 0
    reviews, failures = model_reviews.read_reviews(tmp_path)
    assert len(reviews) == 1 and not failures


@pytest.mark.parametrize("damage", ["duplicate", "nonfinite", "unsupported"])
def test_ambiguous_or_unsupported_json_is_not_silently_rewritten(tmp_path, damage):
    original = capture(tmp_path)
    file = tmp_path / REVIEW_ID / "review.json"
    if damage == "duplicate":
        content = '{"version":1,' + json.dumps(original)[1:]
    else:
        if damage == "nonfinite":
            original["context"]["x"] = float("nan")
        else:
            original["futureField"] = "must not be discarded"
        content = json.dumps(original)
    file.write_text(content, encoding="utf-8")
    before = file.read_bytes()
    done = run(tmp_path, "status", REVIEW_ID, "resolved")
    assert done.returncode == 1
    assert file.read_bytes() == before


def test_proposal_length_matches_the_java_editors_utf16_limit(tmp_path):
    original = capture(tmp_path)
    original["proposal"] = {"method": "other", "text": "\U0001f333" * 8001, "createdAt": original["createdAt"]}
    with pytest.raises(ValueError, match="proposal text"):
        model_reviews.validate(original, REVIEW_ID)
