"""Aggregate immutable Emerald captures without promoting stale or missing QA.

The report verifies every original image, keeps older revisions accessible,
compares current geometry AND assembly placement, and links only explicit
per-exhibit manual observations. It never creates visual approval.
"""
import argparse,json,os
from collections import defaultdict
from pathlib import Path
from urllib.parse import quote
from tools.model_gallery import EmeraldArt,catalog,image_record
from tools.shape_fit.emerald_pass.report import verify_gallery
from tools.shape_fit.emerald_pass.review import ROOT,REPORT,TABLE,read,digest
from tools.shape_fit.emerald_pass.scenery import BUNDLE

SIGNATURE_FIELDS=('kind','layout','reference_layout','placements','placement_mode','model_hashes','bounds',
                  'baseline_layout','baseline_placements','baseline_native_cells','baseline_native_fragments','baseline_building','building_baseline',
                  'current_native_fragments','native_current_evidence')

def signature(entry):
    return {field:entry.get(field) for field in SIGNATURE_FIELDS}

def revision_identity(entry,table):
    models=next((b for b in table['buildings'] if b['id']==entry['id']),None) if entry['kind']=='building' else {k:table['cells'].get(k) for k in entry['source_keys']}
    return digest(json.dumps([signature(entry),models],sort_keys=True).encode())

def is_current(exhibit,snapshot,current,expected):
    if expected is None or signature(exhibit)!=signature(expected):return False
    if exhibit['kind']=='building':
        old={b['id']:b for b in snapshot['buildings']};new={b['id']:b for b in current['buildings']}
        return old.get(exhibit['id'])==new.get(exhibit['id']) and exhibit['id'] in new
    return all(snapshot['cells'].get(k)==current['cells'].get(k) for k in exhibit['source_keys'])

def manual_records(directory,provenance,known_ids):
    """Only explicit rows count as manual records; no gallery-wide inference."""
    records=defaultdict(list);documents=[]
    for path in sorted(directory.glob('visual-qa*.json')):
        doc=read(path)
        for field in ('manifest_sha256','capture_sha256','sheet_report_sha256'):
            if doc.get(field)!=provenance[field]:raise ValueError('stale separate visual QA: '+str(path))
        seen=set()
        for row in doc.get('entries',[]):
            ident=row['id']
            if ident not in known_ids or ident in seen:raise ValueError('invalid manual QA identity: '+ident)
            seen.add(ident)
            for field in ('original_images','inspected_images'):
                for old in row.get(field,[]):
                    if not isinstance(old,dict) or 'file' not in old or 'sha256' not in old:continue
                    file=Path(old['file'])
                    if not file.is_absolute():file=ROOT/file
                    if image_record(file,old.get('role','manual evidence'))['sha256']!=old['sha256']:
                        raise ValueError('stale manual image: '+str(file))
            records[ident].append(dict(path=str(path),sha256=digest(path.read_bytes()),status=row.get('status','manual observations recorded'),
                observations=row.get('observations',row.get('observation',row.get('notes',row.get('finding','See linked manual record.')))),scope=doc.get('scope','Per-exhibit manual review'),user_approved=row.get('user_approved',doc.get('user_approved',False))))
        documents.append(dict(path=str(path),sha256=digest(path.read_bytes()),status=doc.get('status'),entries=len(seen)))
    return records,documents

def choose(candidates):
    # Prefer current evidence with explicit manual observations. A capture's
    # success never supplies those observations. Keep every alternative below.
    return max(candidates,key=lambda c:(c['current'],bool(c['manual_qa']),c['order']))

def build(review_path,galleries,out,table_path=TABLE,*,current_entries=None):
    out=Path(out).resolve();review_path=Path(review_path).resolve();table_path=Path(table_path).resolve()
    if not out.is_relative_to((ROOT/'local').resolve()):raise ValueError('review artifacts belong under local/')
    review=read(review_path);table=read(table_path)
    if review.get('game')!='emerald' or digest(table_path.read_bytes())!=review['table_sha256']:raise ValueError('review does not describe current Emerald table')
    if current_entries is None:
        entities=read(ROOT/'tools/shape_fit/entities/emerald.json');art=EmeraldArt(BUNDLE)
        current_entries=catalog(table,entities,art,'all')+catalog(table,entities,art,'buildings')
    expected={alias:e for e in current_entries for alias in [e['id'],*e.get('aliases',[])]}
    decisions={(r['kind'],r['key']):r for r in review['entries']};candidates=defaultdict(list);evidence=[]
    link=lambda p:quote(Path(os.path.relpath(Path(p).resolve(),out)).as_posix(),safe='/.')
    for order,directory in enumerate(dict.fromkeys(Path(p).resolve() for p in galleries)):
        manifest,capture,sheets=verify_gallery(directory,review,table,allow_stale=True,allow_current_only=True)
        snapshot=read(directory/'fitted-current.json');baseline=read(directory/'fitted-baseline.json');old_building_ids={b['id'] for b in baseline['buildings']};sheet_by_id={e['id']:e for e in sheets['entries']}
        currents=[e for e in manifest['entries'] if e.get('revision')!='before'];before={e['comparison_id'] for e in manifest['entries'] if e.get('revision')=='before'}
        provenance={f:digest((directory/name).read_bytes()) for f,name in [('manifest_sha256','manifest.json'),('capture_sha256','capture.json'),('sheet_report_sha256','sheet-report.json')]}
        qa,qa_docs=manual_records(directory,provenance,{e['id'] for e in currents})
        for e in currents:
            target=expected.get(e['id']);identifier=target['id'] if target else e['id'];record=sheet_by_id[e['id']]
            original_absent=e['kind']=='building' and e['id'] not in old_building_ids
            candidates[identifier].append(dict(id=e['id'],order=order,current=is_current(e,snapshot,table,target),revision_identity=revision_identity(e,snapshot),
                kind=e['kind'],entry=e,manual_qa=qa.get(e['id'],[]),gallery=link(directory/'index.html'),
                manifest_sha256=provenance['manifest_sha256'],table_sha256=manifest['source']['table_sha256'],
                sheet=link(record['sheet']),detail=link(record['detail_sheet']),baseline_present=e['id'] in before,
                baseline_status='Before companion captured' if e['id'] in before else 'No original template existed; current-only capture' if original_absent else 'No baseline companion captured; original snapshot preserved'))
        evidence.append(dict(gallery=link(directory/'index.html'),manifest=link(directory/'manifest.json'),**provenance,
            models=len(currents),captures=len(capture['captures']),manual_qa=[{**d,'path':link(d['path'])} for d in qa_docs]))
    items=[];captured_decisions=set();all_entries={e['id']:e for e in current_entries}
    for identifier,cs in candidates.items():
        if identifier not in all_entries:all_entries[identifier]=cs[-1]['entry']
    for identifier,e in all_entries.items():
        identities=[('building',identifier)] if e['kind']=='building' else [('cell',k) for k in set(e['source_keys'])]
        rows=[decisions[k] for k in identities if k in decisions];cs=candidates.get(identifier,[]);best=choose(cs) if cs else None
        current=bool(best and best['current']);historical=identifier not in expected
        if current:captured_decisions.update(k for k in identities if k in decisions)
        decision='pending' if len(rows)!=len(identities) else 'added' if any(r['decision']=='added' for r in rows) else 'changed' if any(r['decision']=='changed' for r in rows) else 'retained'
        label=rows[0]['label'] if len(identities)==1 and rows else e.get('label',identifier)
        if label.startswith('Stack:'):label=' + '.join(dict.fromkeys(r['label'] for r in rows)) or label
        item=dict(id=identifier,label=label,kind=e['kind'],decision=decision,keys=e.get('source_keys',[]),
            family=sorted({r.get('family','pending identification') for r in rows}),maps=sorted({loc['map'] for loc in e.get('occurrences',[])}),
            reasons=list(dict.fromkeys(r.get('reason','Semantic review pending.') for r in rows)) or ['Semantic review remains pending.'],
            captured=best is not None,current_capture=current,historical=historical,manual_qa=[],history=[])
        if e.get('placement_mode')=='source-map-current' and e.get('native_current_evidence'):
            item['pending_native_source_identities']=[dict(kind=kind,key=key) for kind,key in identities if (kind,key) not in decisions]
            if item['pending_native_source_identities']:
                item['reasons'].append('Native map context has source identities without individual ledger decisions; capture availability does not approve them.')
        if best:
            item.update({f:best[f] for f in ('sheet','detail','gallery','baseline_present','baseline_status','table_sha256')})
            # All matching-current QA is shown, including unresolved defects.
            qas=[q for c in cs if c['revision_identity']==best['revision_identity'] for q in c['manual_qa']]
            unique={(q['path'],q['sha256'],q['status']):q for q in qas}
            item['manual_qa']=[{**q,'path':link(q['path']),'applies_to_current':current} for q in unique.values()]
            item['history']=[{**{f:c[f] for f in ('id','current','sheet','detail','gallery','table_sha256','baseline_status')},'manual_qa':[{**q,'path':link(q['path'])} for q in c['manual_qa']]} for c in cs]
        items.append(item)
    represented={(e['kind'],e['id']) if e['kind']=='building' else ('cell',e['keys'][0]) for e in items if e['kind']=='building' or (e['kind']=='cell' and len(e['keys'])==1)}
    for row in [*review.get('pending',[]),*review['entries']]:
        if (row['kind'],row['key']) in represented:continue
        items.append(dict(id=row['key'],kind=row['kind'],label=row.get('label',row['key']),decision=row['decision'],keys=[row['key']],family=[row.get('family','pending identification')],maps=[x['map'] for x in row.get('occurrences',[])],reasons=[row.get('reason','Semantic and visual review remain pending.')],captured=False,current_capture=False,historical=False,manual_qa=[],history=[]))
    payload=dict(status='in_progress',counts=review['counts'],table_sha256=review['table_sha256'],review_source=link(review_path),review_sha256=digest(review_path.read_bytes()),
        captures=sum(g['captures'] for g in evidence),current_exhibits_captured=sum(i['current_capture'] for i in items),reviewed_without_capture=len(set(decisions)-captured_decisions),
        current_exhibits_with_manual_notes=sum(i['current_capture'] and bool(i['manual_qa']) for i in items),items=items,galleries=evidence,
        notice='Work in progress. Verified capture coverage does not establish visual approval, normal-map behavior or complete Emerald coverage. Manual notes apply only to the shown revision. Historical captures and original snapshots are preserved.')
    out.mkdir(parents=True,exist_ok=True);(out/'review-data.json').write_text(json.dumps(payload,indent=2)+'\n',encoding='utf-8')
    (out/'review.html').write_text(TEMPLATE.replace('__DATA__',json.dumps(payload,ensure_ascii=True).replace('<','\\u003c')),encoding='utf-8')
    return payload

TEMPLATE=r'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Emerald model review</title>
<style>:root{color-scheme:dark;font:16px system-ui;background:#111e23;color:#ecf3ef}body{max-width:1400px;margin:auto;padding:24px}p,li{line-height:1.6}a{color:#8edfc6}input,select,button{font:inherit;padding:10px;background:#1d343c;color:inherit;border:1px solid #58747d;border-radius:5px}.controls{display:flex;flex-wrap:wrap;gap:10px;position:sticky;top:0;background:#111e23;padding:12px 0}input{flex:1;min-width:240px}article{background:#1b2c33;border:1px solid #36515a;padding:20px;margin:20px 0;border-radius:8px}.tag{display:inline-block;background:#294a4c;padding:4px 9px;margin:0 7px 7px 0;border-radius:4px}.warn{background:#574a30}img{width:100%;height:auto}code{overflow-wrap:anywhere;font-size:12px}summary{cursor:pointer}.notice{border-left:4px solid #ddb866;padding-left:16px}.pager{display:flex;gap:20px;align-items:center}</style>
<h1>Emerald model review</h1><p class="notice" id="notice"></p><p id="stats"></p><p id="links"></p>
<div class="controls"><input id="q" aria-label="Search models" placeholder="Search model, source key, family, map or observations"><select id="scope" aria-label="Evidence status"><option value="current">Current captures</option><option value="all">All models</option><option value="missing">Needs current capture</option><option value="manual">Current manual review notes</option><option value="historical">Retired exhibits</option></select><select id="kind" aria-label="Model kind"><option value="">All kinds</option><option>building</option><option>cell</option><option>compound</option></select><select id="decision" aria-label="Decision"><option value="">All decisions</option><option>changed</option><option>retained</option><option>added</option><option>pending</option></select></div>
<div class="pager"><button id="prev">Previous</button><span id="page"></span><button id="next">Next</button></div><main id="cards"></main>
<script>const d=__DATA__,$=s=>document.getElementById(s),esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));let page=0;const size=12;
$('notice').textContent=d.notice;$('stats').textContent=`${d.counts.changed} changed · ${d.counts.retained} retained · ${d.counts.added} added · ${d.counts.pending} pending · ${d.current_exhibits_captured} current exhibits captured · ${d.current_exhibits_with_manual_notes} with explicit manual notes · ${d.captures} verified screenshots across saved revisions`;
$('links').innerHTML=`<a href="${esc(d.review_source)}">Decision ledger</a> · <a href="review-data.json">Complete provenance and gallery links</a>`;
function match(x){let s=$('scope').value;return (!x.historical||s==='historical'||s==='all')&&(s==='all'||s==='current'&&x.current_capture||s==='missing'&&!x.current_capture||s==='manual'&&x.current_capture&&x.manual_qa.length||s==='historical'&&x.historical)&&(!$('kind').value||x.kind===$('kind').value)&&(!$('decision').value||x.decision===$('decision').value)&&JSON.stringify(x).toLowerCase().includes($('q').value.toLowerCase())}
function draw(){let a=d.items.filter(match),pages=Math.max(1,Math.ceil(a.length/size));page=Math.min(page,pages-1);$('prev').disabled=page===0;$('next').disabled=page===pages-1;$('page').textContent=`${a.length} matches · Page ${page+1}/${pages}`;$('cards').innerHTML=a.slice(page*size,(page+1)*size).map(x=>`<article><h2>${esc(x.label)}</h2><span class="tag">${esc(x.kind)}</span><span class="tag">${esc(x.decision)}</span><span class="tag ${x.current_capture?'':'warn'}">${x.current_capture?'Current capture verified':x.captured?'Older capture · recapture needed':'Capture pending'}</span>${x.historical?'<span class="tag warn">Retired exhibit</span>':''}<p>${x.reasons.map(esc).join('<br>')}</p><p>${x.captured?esc(x.baseline_status):''}</p>${x.manual_qa.length?x.manual_qa.map(q=>`<p><a href="${esc(q.path)}">Manual review: ${esc(q.status)}</a>${q.applies_to_current?'':' · applies to older capture'}<br>${esc(typeof q.observations==='string'?q.observations:JSON.stringify(q.observations))}</p>`).join(''):'<p>Manual visual review pending for this exhibit and revision.</p>'}<details><summary>Source keys and map context</summary><code>${x.keys.map(esc).join('<br>')}</code><p>${x.family.map(esc).join(', ')}</p><p>${x.maps.map(esc).join(', ')}</p></details>${x.captured?`<p><a href="${esc(x.detail)}">Angled detail</a> · <a href="${esc(x.gallery)}">Saved gallery</a></p><a href="${esc(x.sheet)}"><img loading="lazy" src="${esc(x.sheet)}" alt="${esc(x.label)} source and Minecraft comparison"></a>`:''}${x.history.length>1?`<details><summary>${x.history.length} preserved capture revisions</summary>${x.history.map(h=>`<p><a href="${esc(h.sheet)}">${h.current?'Matches current model':'Historical model'}</a> · <code>${esc(h.table_sha256.slice(0,12))}</code> · ${esc(h.baseline_status)}</p>`).join('')}</details>`:''}</article>`).join('')||'<p>No matching models.</p>'}
for(let id of ['q','scope','kind','decision'])$(id).addEventListener(id==='q'?'input':'change',()=>{page=0;draw()});$('prev').onclick=()=>{page--;draw()};$('next').onclick=()=>{page++;draw()};draw();</script></html>'''

if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--review',type=Path,default=REPORT);ap.add_argument('--table',type=Path,default=TABLE);ap.add_argument('--gallery',type=Path,action='append',required=True);ap.add_argument('--out',type=Path,default=ROOT/'local/model-gallery/emerald-combined-review')
    args=ap.parse_args();result=build(args.review,args.gallery,args.out,args.table)
    print(result['current_exhibits_captured'],'current exhibits;',result['current_exhibits_with_manual_notes'],'with manual notes;',result['reviewed_without_capture'],'reviewed entries needing current captures')
