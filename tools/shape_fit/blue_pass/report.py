#!/usr/bin/env python3
"""Build the searchable Blue quality-review page from verified Minecraft captures."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def build(gallery, ledger_path):
    manifest_path = gallery / "manifest.json"
    manifest, proof, sheets, ledger = [read(p) for p in (
        manifest_path, gallery / "capture.json", gallery / "sheet-report.json", ledger_path)]
    manifest_hash = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
    table_hash = hashlib.sha256((gallery / "fitted-current.json").read_bytes()).hexdigest()
    baseline_hash = hashlib.sha256((gallery / "fitted-baseline.json").read_bytes()).hexdigest()
    if (proof.get("status") != "complete" or sheets.get("capture_status") != "complete"
            or sheets.get("manifest_sha256") != manifest_hash
            or proof.get("manifest_sha256") != manifest_hash
            or table_hash != ledger["table_sha256"]
            or table_hash != manifest["source"].get("table_sha256")
            or baseline_hash != ledger["baseline_sha256"]
            or baseline_hash != manifest["source"].get("baseline_table_sha256")
            or not sheets.get("capture_coverage", {}).get("exact")
            or sheets["complete"] != sheets["models"]):
        raise ValueError("review page requires complete, current, verified Minecraft comparisons")
    decisions = {r["key"]: r for r in ledger["entries"]}
    current = [e for e in manifest["entries"] if e.get("revision") != "before"]
    before_ids = {e.get("comparison_id") for e in manifest["entries"] if e.get("revision") == "before"}
    required_before = {e["id"] for e in current if not set(e["source_keys"]) & set(ledger.get("added_keys", []))}
    if before_ids != required_before:
        raise ValueError("every previously fitted exhibit requires its preserved baseline companion")
    if {e["source_keys"][0] for e in current if e["kind"] == "cell"} != set(decisions):
        raise ValueError("the final review must include every reviewed Blue cell")
    sheet_by_id = {e["id"]: e for e in sheets["entries"]}
    poses = {p["id"]: p for p in proof["captures"] if p["direction"] == "three_quarter"}
    def teleport(identifier):
        pose = poses.get(identifier)
        if not pose:
            return None
        x, y, z = pose.get("gallery_eye", pose["eye"])
        return f"/tp @s {x:.2f} {y-1.62:.2f} {z:.2f} {pose['yaw']:.1f} {pose['pitch']:.1f}"
    items = []
    for exhibit in current:
        identifier, keys = exhibit["id"], exhibit["source_keys"]
        rows = [decisions[k] for k in keys]
        changed = any(r["decision"] in ("changed", "added") for r in rows)
        decision = "added" if exhibit["kind"] == "cell" and rows[0]["decision"] == "added" else "changed" if changed else "retained"
        label = rows[0]["label"] if exhibit["kind"] == "cell" else exhibit["label"]
        if label.startswith("Stack:") or label == identifier:
            label = " + ".join(dict.fromkeys(r["label"] for r in rows))
        record = sheet_by_id[identifier]
        items.append({"id": identifier, "label": label, "kind": exhibit["kind"],
                      "atlas": keys[0].split("/")[0].removeprefix("tilesets."),
                      "decision": decision, "keys": keys, "has_before": identifier in before_ids,
                      "teleport": teleport(identifier), "teleport_before": teleport(identifier + "_before"),
                      "reasons": list(dict.fromkeys(r["reason"] for r in rows)),
                      "maps": sorted({p["map"] for p in exhibit.get("occurrences", [])}),
                      "sheet": "sheets/" + Path(record["sheet"]).name,
                      "detail": "sheets/" + Path(record["detail_sheet"]).name if record.get("detail_sheet") else None})
    items.sort(key=lambda e: (e["kind"] != "compound", e["decision"] != "changed", e["atlas"], e["label"]))
    payload = {"summary": {k: ledger[k] for k in ("voxel_cells", "changed", "added", "retained", "building_templates_preserved")},
               "captures": sheets["capture_coverage"]["recorded"], "items": items,
               "compounds": sum(e["kind"] == "compound" for e in items)}
    body = TEMPLATE.replace("__DATA__", json.dumps(payload, ensure_ascii=True).replace("<", "\\u003c"))
    (gallery / "review.html").write_text(body, encoding="utf-8")
    (gallery / "review-ledger.json").write_text(json.dumps(ledger, indent=2)+"\n", encoding="utf-8")
    make_overview(gallery, sheet_by_id)
    print(f"Review ready: {len(items)} exhibits, {payload['captures']} verified screenshots; {gallery / 'review.html'}")


def make_overview(gallery, sheets):
    from PIL import Image, ImageDraw, ImageFont
    identifiers = ("blue_SAFARI_ZONE_tree", "blue_BILLS_HOUSE_transporter", "blue_MUSEUM_shuttle_case",
                   "blue_CELADON_POKECENTER_healer", "blue_REDS_HOUSE_bed", "blue_BIKE_SHOP_00")
    panels = []
    for identifier in identifiers:
        if sheets.get(identifier, {}).get("detail_sheet"):
            with Image.open(sheets[identifier]["detail_sheet"]) as image:
                width = 1280
                panels.append(image.convert("RGB").resize((width, round(image.height*width/image.width)), Image.Resampling.LANCZOS))
    if not panels:
        return
    output = Image.new("RGB", (1280, 88+sum(p.height for p in panels)), "#e9edf0")
    draw = ImageDraw.Draw(output)
    font_path = Path("C:/Windows/Fonts/segoeui.ttf")
    font = ImageFont.truetype(str(font_path), 27) if font_path.exists() else ImageFont.load_default(size=27)
    draw.text((22, 20), "Pokemon Blue — selected model improvements", fill="#16334c", font=font)
    y = 88
    for panel in panels:
        output.paste(panel, (0, y)); y += panel.height
    output.save(gallery / "overview.png")


TEMPLATE = r'''<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pokémon Blue · Model review</title>
<style>
:root{color-scheme:dark;font-family:system-ui,sans-serif;color:#e7edf8;background:#111722}
*{box-sizing:border-box}body{margin:0}header,main{max-width:1440px;margin:auto;padding:24px}
header h1{font-size:32px;margin:0 0 8px}p{line-height:1.6;color:#b7c5d9}a{color:#8dc9ff}
.stats{display:flex;gap:12px;flex-wrap:wrap;margin:20px 0}.stat{background:#1e2b40;padding:12px 18px;border-radius:10px}.stat b{font-size:24px;display:block}
.controls{position:sticky;top:0;background:#111722f5;border-block:1px solid #31405a;padding:16px 24px;z-index:1;display:flex;flex-wrap:wrap;gap:12px;align-items:center}
input,select,button{font:inherit;color:inherit;background:#1e2b40;border:1px solid #52627c;border-radius:6px;padding:9px}input{flex:1;min-width:230px}button{cursor:pointer}button:disabled{opacity:.4;cursor:default}
article{border:1px solid #34445d;border-radius:12px;background:#182235;margin:0 0 24px;overflow:hidden}article .text{padding:18px 22px}h2{margin:0 0 8px;font-size:21px}.badge{display:inline-block;border-radius:5px;background:#2a4057;padding:4px 8px;margin:0 7px 8px 0;font-size:13px}.changed{background:#205a4f}.retained{background:#4c4560}
article img{width:100%;height:auto;display:block;background:#fff}article ul{padding-left:22px;line-height:1.5;color:#c6d1e2}code{overflow-wrap:anywhere;font-size:12px;color:#aebed6}summary{cursor:pointer;color:#a9cfff}details{margin-top:12px}.pagination{display:flex;gap:18px;align-items:center;justify-content:center;margin:24px 0}.empty{padding:50px;text-align:center}
</style>
<header><h1>Pokémon Blue · Model review</h1><p>Source sprites beside actual Minecraft front and side views, with labeled before-and-after panels for previously fitted models. Newly modeled sections show their source and current views. Open a comparison image for full resolution.</p><div class="stats" id="stats"></div><p>All 34 building templates and the two approved pilot models are preserved. Retained cells include correct geometry, connected terrain and shared building fragments. The single non-voxel cemetery entry is preserved separately in the ledger.</p><p><a href="overview.png">Selected improvements</a> · <a href="index.html">Complete comparison gallery & contact sheets</a> · <a href="review-ledger.json">Review decisions</a> · <a href="sheet-report.json">Capture verification</a> · <a href="quality-audit.json">Quality audit</a></p></header>
<div class="controls"><input id="query" aria-label="Search models" placeholder="Search model, map, source key or review reason…"><select id="kind" aria-label="Exhibit type"><option value="">All exhibits</option><option value="compound">Complete objects</option><option value="cell">Individual cells</option></select><select id="decision" aria-label="Review decision"><option value="">All review decisions</option><option value="changed">Changed</option><option value="added">Newly modeled</option><option value="retained">Retained</option></select><select id="atlas" aria-label="Tileset"><option value="">All tilesets</option></select><span id="count"></span></div>
<main><div class="pagination"><button id="prev">Previous</button><span id="page"></span><button id="next">Next</button></div><div id="cards"></div><div class="pagination"><button id="prev2">Previous</button><span id="page2"></span><button id="next2">Next</button></div></main>
<script>
const data=__DATA__, $=id=>document.getElementById(id);let page=0;const size=16;
const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
$('stats').innerHTML=[[data.summary.voxel_cells,'cells reviewed'],[data.summary.changed,'cells revised'],[data.summary.added,'cells newly modeled'],[data.summary.retained,'cells retained'],[data.compounds,'complete objects'],[data.captures,'verified Minecraft screenshots']].map(([n,label])=>`<div class="stat"><b>${n}</b>${label}</div>`).join('');
for(const a of [...new Set(data.items.map(x=>x.atlas))].sort()){$('atlas').add(new Option(a,a));}
function draw(){
 const q=$('query').value.toLowerCase(),filtered=data.items.filter(x=>(!$('kind').value||x.kind===$('kind').value)&&(!$('decision').value||x.decision===$('decision').value)&&(!$('atlas').value||x.atlas===$('atlas').value)&&JSON.stringify(x).toLowerCase().includes(q));
 const pages=Math.max(1,Math.ceil(filtered.length/size));page=Math.min(page,pages-1);$('count').textContent=`${filtered.length} exhibits`;
 for(const suffix of ['', '2']){$('prev'+suffix).disabled=page===0;$('next'+suffix).disabled=page===pages-1;$('page'+suffix).textContent=`Page ${page+1} / ${pages}`;}
 $('cards').innerHTML=filtered.slice(page*size,(page+1)*size).map(x=>`<article id="${esc(x.id)}"><div class="text"><h2>${esc(x.label)}</h2><span class="badge ${x.decision}">${x.decision==='changed'?'Revised':x.decision==='added'?'Newly modeled':'Retained'}</span><span class="badge">${x.kind==='compound'?'Complete object':'Individual cell'}</span><span class="badge">${esc(x.atlas)}</span>${!x.has_before?'<p>Includes sections with no previous fitted model. Source and current views are shown; no before comparison is claimed.</p>':''}<details><summary>Review reason & source cells</summary><ul>${x.reasons.map(r=>`<li>${esc(r)}</li>`).join('')}</ul><code>${x.keys.map(esc).join('<br>')}</code><p>Maps using these source cells: ${x.maps.map(esc).join(', ')||'No exported occurrence'}</p>${x.teleport?`<p>View in the Blue comparison world:<br><code>${esc(x.teleport)}</code></p>`:''}${x.teleport_before?`<p>Preserved model viewpoint:<br><code>${esc(x.teleport_before)}</code></p>`:''}</details></div><a href="${esc(x.sheet)}" target="_blank"><img loading="lazy" src="${esc(x.sheet)}" alt="${esc(x.label)} sprite and before/after Minecraft comparison"></a>${x.detail?`<div class="text"><a href="${esc(x.detail)}" target="_blank">Open angled detail</a></div>`:''}</article>`).join('')||'<div class="empty">No matching models.</div>';
}
for(const id of ['query','kind','decision','atlas'])$(id).addEventListener(id==='query'?'input':'change',()=>{page=0;draw();});
for(const suffix of ['', '2']){for(const [id,delta] of [['prev',-1],['next',1]])$(id+suffix).onclick=()=>{page+=delta;draw();document.querySelector('main').scrollIntoView({behavior:'smooth'});};}
draw();
</script></html>'''


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gallery", type=Path, default=ROOT / "local/model-gallery/blue-review")
    parser.add_argument("--ledger", type=Path, default=ROOT / "local/model-gallery/blue-rollout-study/review.json")
    args = parser.parse_args()
    build(args.gallery.resolve(), args.ledger.resolve())
