"""Searchable partial Emerald review bound to verified Minecraft evidence.

Capture completeness is distinct from visual approval. This writer never
creates or marks a visual QA record and never marks the full pass complete.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from urllib.parse import quote

from tools.model_gallery import capture_coverage, capture_views, image_record
from tools.shape_fit.emerald_pass.review import ROOT, REPORT, TABLE, read, digest


def verify_gallery(gallery, review, current_table, *, allow_stale=False, allow_current_only=False):
    gallery = Path(gallery).resolve()
    path = gallery / "manifest.json"
    manifest, capture, sheets = (read(path), read(gallery / "capture.json"), read(gallery / "sheet-report.json"))
    manifest_hash = digest(path.read_bytes())
    if manifest.get("game") != "emerald":
        raise ValueError("Emerald gallery required")
    if any(record.get("manifest_sha256") != manifest_hash for record in (capture, sheets)):
        raise ValueError("stale manifest provenance")
    if capture.get("status") != "complete" or sheets.get("capture_status") != "complete":
        raise ValueError("Minecraft capture incomplete")
    views = capture_views(manifest)
    if views not in (["front", "side", "three_quarter"], ["front", "side", "three_quarter", "north"]):
        raise ValueError("Emerald review requires front, side and angled comparisons")
    ids = [e["id"] for e in manifest["entries"]]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate exhibit identity")
    coverage = capture_coverage(manifest["entries"], capture.get("captures", []), views)
    if not coverage["exact"] or sheets.get("capture_coverage") != coverage:
        raise ValueError("capture coverage is not exact")
    snapshot_path = gallery / "fitted-current.json"
    baseline_path = gallery / "fitted-baseline.json"
    if digest(snapshot_path.read_bytes()) != manifest["source"].get("table_sha256"):
        raise ValueError("current snapshot provenance differs")
    if digest(baseline_path.read_bytes()) != review["baseline_sha256"] or review["baseline_sha256"] != manifest["source"].get("baseline_table_sha256"):
        raise ValueError("baseline snapshot provenance differs")
    snapshot = read(snapshot_path)
    current = [e for e in manifest["entries"] if e.get("revision") != "before"]
    before = {e.get("comparison_id"): e for e in manifest["entries"] if e.get("revision") == "before"}
    current_ids={e['id'] for e in current}
    if not set(before)<=current_ids or len(before)+len(current)!=len(manifest['entries']):
        raise ValueError('invalid baseline companion identity')
    if not allow_current_only and (set(before) != current_ids or len(before) * 2 != len(manifest["entries"])):
        raise ValueError("each exhibit requires exactly one baseline companion")
    records = {e["id"]: e for e in sheets["entries"]}
    if len(records) != len(sheets["entries"]) or set(records) != {e["id"] for e in current}:
        raise ValueError("sheet coverage differs from manifest")
    if sheets.get("complete") != len(current) or sheets.get("models") != len(current):
        raise ValueError("comparison sheets incomplete")
    old_buildings = {b["id"]: b for b in snapshot["buildings"]}
    buildings = {b["id"]: b for b in current_table["buildings"]}
    for exhibit in current:
        identifier = exhibit["id"]
        if not allow_stale and exhibit["kind"] == "building":
            if old_buildings[identifier] != buildings[identifier]:
                raise ValueError("captured building is stale: " + identifier)
        elif not allow_stale and any(snapshot["cells"].get(key) != current_table["cells"].get(key) for key in exhibit["source_keys"]):
            raise ValueError("captured cell is stale: " + identifier)
        record = records[identifier]
        if not record.get("complete") or any(record.get(k) for k in ("missing", "invalid_files", "provenance_errors")):
            raise ValueError("comparison sheet has errors: " + identifier)
        expected = {(gallery / exhibit["sprite"]).resolve()}
        for shot_id in [identifier]+([before[identifier]['id']] if identifier in before else []):
            expected.update((gallery / "screenshots" / f"{shot_id}_{view}.png").resolve() for view in views)
        actual = set()
        for original in record["originals"]:
            file = Path(original["file"]).resolve()
            actual.add(file)
            fresh = image_record(file, original["role"])
            if any(fresh[k] != original[k] for k in ("sha256", "width", "height")):
                raise ValueError("stale screenshot/source evidence: " + str(file))
        if actual != expected:
            raise ValueError("sheet sources differ from expected views")
        # Read every output pixel too; preserve the saved sheet rather than rewriting it.
        for field in ("sheet", "detail_sheet") + (("north_sheet",) if "north" in views else ()):
            if not record.get(field):
                raise ValueError("missing angled comparison sheet")
            image_record(Path(record[field]), field)
    return manifest, capture, sheets


def build(review_path, galleries, out, table_path=TABLE):
    review_path, out = Path(review_path).resolve(), Path(out).resolve()
    if not out.is_relative_to((ROOT / "local").resolve()):
        raise ValueError("review artifacts belong under local/")
    review, table = read(review_path), read(table_path)
    if digest(Path(table_path).read_bytes()) != review["table_sha256"]:
        raise ValueError("review does not describe the current table")
    if review.get("game") != "emerald":
        raise ValueError("Emerald review required")
    out.mkdir(parents=True, exist_ok=True)
    link = lambda path: quote(Path(os.path.relpath(Path(path).resolve(), out)).as_posix(), safe="/.")
    decisions = {(e["kind"], e["key"]): e for e in review["entries"]}
    items, evidence, captured_decisions = [], [], set()
    seen = set()
    for directory in galleries:
        directory = Path(directory).resolve()
        manifest, capture, sheets = verify_gallery(directory, review, table)
        records = {e["id"]: e for e in sheets["entries"]}
        for exhibit in manifest["entries"]:
            if exhibit.get("revision") == "before":
                continue
            identifier = exhibit["id"]
            if identifier in seen:
                raise ValueError("duplicate current exhibit across galleries: " + identifier)
            seen.add(identifier)
            identities = [("building", identifier)] if exhibit["kind"] == "building" else [("cell", k) for k in set(exhibit["source_keys"])]
            rows = [decisions[i] for i in identities if i in decisions]
            captured_decisions.update(i for i in identities if i in decisions)
            label = rows[0]["label"] if len(identities) == 1 and rows else exhibit["label"]
            if label.startswith("Stack:"):
                label = " + ".join(dict.fromkeys(row["label"] for row in rows)) or label
            record = records[identifier]
            items.append({"id": identifier, "kind": exhibit["kind"], "label": label,
                          "decision": "pending" if len(rows) != len(identities) else "changed" if any(r["decision"] == "changed" for r in rows) else "retained",
                          "keys": exhibit["source_keys"], "family": sorted({r["family"] for r in rows}),
                          "maps": sorted({p["map"] for p in exhibit.get("occurrences", [])}),
                          "reasons": list(dict.fromkeys(r["reason"] for r in rows)) or ["Captured for inspection; semantic decision remains pending."],
                          "sheet": link(record["sheet"]), "detail": link(record["detail_sheet"]),
                          "gallery": link(directory / "index.html"), "captured": True})
        evidence.append({"gallery": link(directory / "index.html"), "manifest": link(directory / "manifest.json"),
                         "manifest_sha256": digest((directory / "manifest.json").read_bytes()),
                         "capture_sha256": digest((directory / "capture.json").read_bytes()),
                         "sheet_report_sha256": digest((directory / "sheet-report.json").read_bytes()),
                         "models": sheets["models"], "captures": len(capture["captures"])})
        qa_path = directory / "visual-qa.json"
        if qa_path.exists():
            qa = read(qa_path)
            for key in ("manifest_sha256", "capture_sha256", "sheet_report_sha256"):
                if qa.get(key) != evidence[-1][key]:
                    raise ValueError("stale separate visual QA record: " + str(qa_path))
            evidence[-1].update(visual_qa=link(qa_path), visual_qa_sha256=digest(qa_path.read_bytes()),
                                visual_qa_status=qa.get("status", "unspecified"))
    missing = set(decisions) - captured_decisions
    for row in [*review.get("pending", []), *(decisions[i] for i in sorted(missing))]:
        items.append({"id": row["key"], "label": row.get("label", row["key"]), "kind": row["kind"],
                      "decision": row["decision"], "keys": [row["key"]], "family": [row.get("family", "pending identification")],
                      "maps": [p["map"] for p in row.get("occurrences", [])],
                      "reasons": [row.get("reason", "Semantic and visual review remain pending.")], "captured": False})
    payload = {"status": "in_progress", "counts": review["counts"], "items": items, "galleries": evidence,
               "reviewed_without_capture": len(missing), "captures": sum(g["captures"] for g in evidence),
               "visual_qa": "Visual QA is recorded separately when linked below. Capture verification does not establish visual approval, gameplay collision or full Emerald coverage.",
               "review_source": link(review_path), "review_sha256": digest(review_path.read_bytes())}
    (out / "review-data.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    html = TEMPLATE.replace("__DATA__", json.dumps(payload, ensure_ascii=True).replace("<", "\\u003c"))
    (out / "review.html").write_text(html, encoding="utf-8")
    return payload


TEMPLATE = r'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Emerald model pass · Progress review</title>
<style>:root{color-scheme:dark;font:16px system-ui;background:#101b22;color:#e6f0f3}body{max-width:1380px;margin:auto;padding:24px}h1{margin-bottom:8px}p,li{line-height:1.6}a{color:#87d7c7}input,select,button{font:inherit;padding:10px;color:inherit;background:#1b343d;border:1px solid #45626b;border-radius:6px}.controls{display:flex;gap:12px;flex-wrap:wrap;position:sticky;top:0;padding:14px 0;background:#101b22}input{flex:1}article{background:#192b34;border:1px solid #34505d;border-radius:9px;padding:20px;margin:20px 0}img{width:100%;height:auto}code{overflow-wrap:anywhere;font-size:12px}.tag{display:inline-block;padding:5px 9px;background:#294c50;margin-right:8px;border-radius:4px}.pending{background:#534635}.pages{display:flex;gap:16px;align-items:center}.notice{border-left:4px solid #d7b97a;padding-left:16px}summary{cursor:pointer}</style>
<h1>Emerald model pass · Progress review</h1><p class="notice">This pass is in progress. The comparisons below cover completed capture batches. Pending models remain in the inventory; full Emerald coverage and visual approval are not claimed.</p><p id="stats"></p><p id="qa"></p><p id="links"></p>
<div class="controls"><input id="q" aria-label="Search models" placeholder="Search label, source key, family, map or reason"><select id="scope" aria-label="Evidence"><option value="captured">Captured comparisons</option><option value="all">All inventory including pending</option><option value="uncaptured">Without captures</option></select><select id="kind" aria-label="Kind"><option value="">All kinds</option><option>building</option><option>cell</option><option>compound</option></select><select id="decision" aria-label="Decision"><option value="">All decisions</option><option>changed</option><option>retained</option><option>pending</option></select></div>
<div class="pages"><button id="prev">Previous</button><span id="page"></span><button id="next">Next</button></div><main id="cards"></main>
<script>const d=__DATA__,$=s=>document.getElementById(s),esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));let page=0;const size=16;
$('stats').textContent=`${d.counts.changed||0} entries changed · ${d.counts.retained||0} retained · ${d.counts.pending||0} pending · ${d.captures} verified Minecraft screenshots · ${d.reviewed_without_capture} reviewed entries without current capture`;
$('qa').textContent=d.visual_qa;$('links').innerHTML=`<a href="${esc(d.review_source)}">Semantic decisions</a> · <a href="review-data.json">Report data and provenance</a>`+d.galleries.map((g,i)=>` · <a href="${esc(g.gallery)}">Batch ${i+1} gallery</a>${g.visual_qa?` · <a href="${esc(g.visual_qa)}">Batch ${i+1} visual QA</a>`:''}`).join('');
function draw(){let q=$('q').value.toLowerCase(),a=d.items.filter(x=>(!$('kind').value||x.kind===$('kind').value)&&(!$('decision').value||x.decision===$('decision').value)&&($('scope').value==='all'||($('scope').value==='captured')===x.captured)&&JSON.stringify(x).toLowerCase().includes(q));let pages=Math.max(1,Math.ceil(a.length/size));page=Math.min(page,pages-1);$('prev').disabled=page===0;$('next').disabled=page===pages-1;$('page').textContent=`${a.length} matches · Page ${page+1}/${pages}`;
$('cards').innerHTML=a.slice(page*size,(page+1)*size).map(x=>`<article><h2>${esc(x.label)}</h2><span class="tag ${esc(x.decision)}">${esc(x.decision)}</span><span class="tag">${esc(x.kind)}</span><span class="tag">${x.captured?'Capture verified':'Capture pending'}</span><p>${x.reasons.map(esc).join('<br>')}</p><details><summary>Source identity and map context</summary><p>${x.family.map(esc).join(', ')}</p><code>${x.keys.map(esc).join('<br>')}</code><p>${x.maps.map(esc).join(', ')||'No map context recorded in this inventory row.'}</p></details>${x.captured?`<p><a href="${esc(x.detail)}">Angled before/current detail</a> · <a href="${esc(x.gallery)}">Comparison gallery</a></p><a href="${esc(x.sheet)}"><img loading="lazy" src="${esc(x.sheet)}" alt="${esc(x.label)} source sprite and before/current Minecraft views"></a>`:''}</article>`).join('')||'<p>No matching models.</p>';}
for(let id of ['q','scope','kind','decision'])$(id).addEventListener(id==='q'?'input':'change',()=>{page=0;draw()});$('prev').onclick=()=>{page--;draw()};$('next').onclick=()=>{page++;draw()};draw();</script></html>'''


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--review", type=Path, default=REPORT)
    parser.add_argument("--gallery", type=Path, action="append", required=True)
    parser.add_argument("--out", type=Path, default=ROOT / "local/model-gallery/emerald-pass-review")
    parser.add_argument("--table", type=Path, default=TABLE)
    args = parser.parse_args()
    result = build(args.review, args.gallery, args.out, args.table)
    print(f"Partial report: {result['captures']} verified captures; {result['counts'].get('pending', 0)} entries pending")
