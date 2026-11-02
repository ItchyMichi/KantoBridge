"""Record the visual QA of a captured Crystal review gallery, bound to its exact evidence.

    python -m tools.shape_fit.crystal_pass.qa <gallery folder> --notes notes.json

`notes.json`: {"scope": "...", "method": "...", "default": "observation for every unlisted model",
"entries": {"<id>": {"status": "ready" | "repaired" | "superseded", "observations": "...", "see": "<later gallery>"}}}.
Refuses a gallery whose capture is incomplete or whose sheet report lacks exact coverage, and lists every
comparison of the manifest (without the `_before` companions) so none is silently skipped.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("gallery", type=Path)
    ap.add_argument("--notes", type=Path, required=True)
    args = ap.parse_args()
    g = args.gallery
    manifest, capture, report = (json.loads((g / n).read_text(encoding="utf-8")) for n in
                                 ("manifest.json", "capture.json", "sheet-report.json"))
    if capture.get("status") != "complete" or report.get("capture_status") != "complete":
        raise SystemExit("capture incomplete")
    if report.get("complete") != report.get("models"):
        raise SystemExit("sheet coverage incomplete")
    notes = json.loads(args.notes.read_text(encoding="utf-8"))
    ids = [e["id"] for e in manifest["entries"] if not e["id"].endswith("_before")]
    unknown = set(notes.get("entries", {})) - set(ids)
    if unknown:
        raise SystemExit(f"notes for models not in this gallery: {sorted(unknown)}")
    entries = []
    for i in ids:
        note = notes.get("entries", {}).get(i, {})
        entries.append({"id": i, "status": note.get("status", "ready"),
                        "observations": note.get("observations", notes["default"]),
                        **({"see": note["see"]} if "see" in note else {}),
                        "sheets": [f"sheets/{i}.png", f"sheets/{i}_detail.png"]})
    out = {"schema": 1, "scope": notes["scope"], "method": notes["method"],
           "manifest_sha256": sha(g / "manifest.json"), "capture_sha256": sha(g / "capture.json"),
           "sheet_report_sha256": sha(g / "sheet-report.json"),
           "current_table_sha256": sha(g / "fitted-current.json"),
           "baseline_table_sha256": sha(g / "fitted-baseline.json"),
           "models_inspected": len(ids), "entries": entries}
    (g / "visual-qa.json").write_text(json.dumps(out, indent=1) + "\n", encoding="utf-8")
    from collections import Counter
    print(g / "visual-qa.json", dict(Counter(e["status"] for e in entries)))


if __name__ == "__main__":
    main()
