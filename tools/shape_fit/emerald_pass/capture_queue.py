"""Freeze the reviewed Emerald exhibits that still need current captures.

Every reused gallery is validated against original screenshot bytes. Matching
requires the exact table entries and assembly/baseline descriptors. This is
capture scheduling, never visual approval.
"""
import argparse,json
from pathlib import Path
from tools.model_gallery import EmeraldArt,catalog,write_json
from tools.shape_fit.emerald_pass.review import ROOT,TABLE,REPORT,read,digest
from tools.shape_fit.emerald_pass.report import verify_gallery
from tools.shape_fit.emerald_pass.report_all import is_current
from tools.shape_fit.emerald_pass.scenery import BUNDLE

def schedule_entries(entries,reviewed,known):
    """Catalog-validated native contexts may be captured while their source review stays pending."""
    selected=[];covered=[];pending_contexts=[]
    for entry in entries:
        identities={('building',entry['id'])} if entry['kind']=='building' else {('cell',key) for key in entry['source_keys']}
        evidence=entry.get('native_current_evidence',{})
        native=(entry.get('placement_mode')=='source-map-current' and isinstance(evidence.get('record'),dict)
                and isinstance(entry.get('current_native_fragments'),dict)
                and evidence.get('descriptor')==entry['current_native_fragments'])
        pending=identities-reviewed
        if native and pending:
            pending_contexts.append(dict(id=entry['id'],pending_source_identities=[dict(kind=k,key=v) for k,v in sorted(pending)],
                reason='Source-validated native context scheduled for capture; these source identities retain pending semantic review.'))
        if not identities or (pending and not native):continue
        if entry['id'] in known:covered.append(dict(id=entry['id'],galleries=known[entry['id']]))
        else:selected.append(entry)
    return selected,covered,pending_contexts

def build(out,galleries,batch_size=160):
    out=Path(out).resolve()
    if not out.is_relative_to((ROOT/'local').resolve()) or out.exists():raise ValueError('Use a new local queue directory')
    raw=TABLE.read_bytes();table=json.loads(raw);review_raw=REPORT.read_bytes();review=json.loads(review_raw)
    if digest(raw)!=review['table_sha256']:raise ValueError('Live table differs from review')
    ep=ROOT/'tools/shape_fit/entities/emerald.json';entity_raw=ep.read_bytes();entities=json.loads(entity_raw);art=EmeraldArt(BUNDLE)
    entries=catalog(table,entities,art,'all')+catalog(table,entities,art,'buildings')
    expected={alias:e for e in entries for alias in [e['id'],*e.get('aliases',[])]}
    reviewed={(r['kind'],r['key']) for r in review['entries']};known={};verified=[]
    for gallery in galleries:
        m,c,s=verify_gallery(gallery,review,table,allow_stale=True,allow_current_only=True)
        snapshot=read(gallery/'fitted-current.json')
        for entry in m['entries']:
            if entry.get('revision')=='before':continue
            target=expected.get(entry['id'])
            if target and is_current(entry,snapshot,table,target):known.setdefault(target['id'],[]).append(str(gallery))
        verified.append(dict(path=str(gallery),manifest_sha256=digest((gallery/'manifest.json').read_bytes()),capture_sha256=digest((gallery/'capture.json').read_bytes()),sheet_report_sha256=digest((gallery/'sheet-report.json').read_bytes())))
    selected,covered,pending_contexts=schedule_entries(entries,reviewed,known)
    out.mkdir();(out/'fitted-current.json').write_bytes(raw);(out/'entities-current.json').write_bytes(entity_raw);(out/'review-current.json').write_bytes(review_raw)
    write_json(out/'manifest.json',dict(schema=1,game='emerald',source=dict(table_sha256=digest(raw),entities_sha256=digest(entity_raw)),entries=entries,purpose='Capture queue snapshot and rollback membership; no visual approval'))
    for kind in ('all','buildings'):
        ids=[e['id'] for e in selected if (e['kind']=='building')==(kind=='buildings')]
        for start in range(0,len(ids),batch_size):write_json(out/f'{kind}-{start//batch_size+1:02}.json',ids[start:start+batch_size])
    write_json(out/'queue-evidence.json',dict(selected=[dict(id=e['id'],kind=e['kind']) for e in selected],covered=covered,pending_native_contexts=pending_contexts,verified_galleries=verified,table_sha256=digest(raw),review_sha256=digest(review_raw),notice='Exact capture reuse only. No automatic visual approval. Native source identities without ledger decisions remain pending.'))
    return len(selected),len(covered),len(verified)

def complete_galleries():
    result=[]
    for batch in ('emerald-batch-01','emerald-batch-02'):
        for p in sorted((ROOT/'local/model-gallery'/batch).iterdir()):
            if all((p/name).exists() for name in ('manifest.json','capture.json','sheet-report.json','fitted-baseline.json')):
                if read(p/'capture.json').get('status')==read(p/'sheet-report.json').get('capture_status')=='complete':result.append(p)
    return result

if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,required=True);ap.add_argument('--batch-size',type=int,default=160);args=ap.parse_args()
    print('Queued, reused, verified galleries:',build(args.out,complete_galleries(),args.batch_size))
