"""Full-pass coverage and preservation safeguards, independent of model choices."""
import base64
import copy
import importlib.util
from pathlib import Path

import pytest

SPEC = importlib.util.spec_from_file_location("blue_review", Path(__file__).parents[1] / "shape_fit/blue_pass/review.py")
review = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(review)


def entry(value=1, **extra):
    return {"class": "voxels", "v": base64.b64encode(bytes([value]) + bytes(511)).decode(),
            "t": base64.b64encode(bytes(4096)).decode(), **extra}


def decision(key, changed=True):
    return {"key": key, "label": "Reviewed prop", "decision": "changed" if changed else "retained",
            "reason": "Explicit review of source and Minecraft views"}


def test_merge_requires_every_voxel_review_and_exact_agreement_with_edits():
    before = {"game": "blue", "cells": {"a": entry(), "b": entry()}, "buildings": [{"id": "keep"}]}
    with pytest.raises(ValueError, match="review coverage"):
        review.merge(before, [{"a": entry(3)}], [[decision("a")]])
    with pytest.raises(ValueError, match="disagrees"):
        review.merge(before, [{"a": entry(3)}], [[decision("a"), decision("b")]])
    after, report = review.merge(before, [{"a": entry(3)}], [[decision("a"), decision("b", False)]])
    assert after["buildings"] == before["buildings"]
    assert after["cells"]["b"] == before["cells"]["b"]
    assert (report["changed"], report["retained"]) == (1, 1)
    assert before["cells"]["a"] == entry()


def test_merge_rejects_protected_pilot_and_unrelated_interaction_changes():
    key = "tilesets.overworld/42-43-58-59"
    before = {"game": "blue", "cells": {key: entry()}, "buildings": []}
    with pytest.raises(ValueError, match="approved pilot"):
        review.merge(before, [{key: entry(3)}], [[decision(key)]])
    with pytest.raises(ValueError, match="interaction"):
        review.validate_entry("a", entry(seat=True), entry(3))
    with pytest.raises(ValueError, match="interaction"):
        review.validate_entry("a", entry(on="base"), entry(3))


def test_only_confirmed_incorrect_stack_links_may_be_removed():
    for key, base in review.CORRECTED_STACKS.items():
        review.validate_entry(key, entry(on=base), entry(3))
        with pytest.raises(ValueError, match="interaction"):
            review.validate_entry(key, entry(on=base), entry(3, on="other"))


def test_invalid_paint_and_empty_replacements_cannot_pass():
    with pytest.raises(ValueError, match="paint"):
        review.validate_entry("a", entry(), {**entry(3), "t": ""})
    with pytest.raises(ValueError, match="empty fallback"):
        review.validate_entry("a", entry(), entry(0))


def test_duplicate_reviews_or_cross_owner_edits_are_rejected():
    before = {"game": "blue", "cells": {"a": entry()}, "buildings": []}
    with pytest.raises(ValueError, match="conflicting"):
        review.merge(before, [{"a": entry(3)}, {"a": entry(7)}], [[decision("a")]])
    with pytest.raises(ValueError, match="duplicate review"):
        review.merge(before, [{"a": entry(3)}], [[decision("a")], [decision("a")]])


def test_missing_museum_sections_are_explicit_additions_not_baseline_revisions():
    key = sorted(review.ADDITIONS)[0]
    before = {"game": "blue", "cells": {"a": entry()}, "buildings": []}
    added = {**decision(key), "decision": "added"}
    after, report = review.merge(before, [{key: entry(3)}], [[decision("a", False), added]])
    assert report["added"] == 1 and report["changed"] == 0 and report["retained"] == 1
    assert key in after["cells"] and key not in before["cells"]
    with pytest.raises(ValueError, match="disagrees"):
        review.merge(before, [{key: entry(3)}], [[decision("a", False), decision(key)]])
    with pytest.raises(ValueError, match="outside fitted voxel scope"):
        review.merge(before, [{"unreviewed": entry(3)}], [[]])
