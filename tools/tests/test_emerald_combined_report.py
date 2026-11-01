import copy,json
import pytest
from tools.tests.test_emerald_pass_report import fixture_gallery
from tools.shape_fit.emerald_pass import report_all as r
from tools.shape_fit.emerald_pass.report import verify_gallery
from tools.model_gallery import capture_coverage

def write(path,value):path.write_text(json.dumps(value),encoding='utf-8')

def setup(tmp_path,monkeypatch):
    monkeypatch.setattr(r,'ROOT',tmp_path)
    g=tmp_path/'gallery';g.mkdir();review,table=fixture_gallery(g)
    row=dict(kind='cell',key='g3q/1-2-3-4',label='Reviewed tree',family='tree',decision='changed',reason='Supported canopy',occurrences=[])
    review.update(game='emerald',table_sha256=r.digest((g/'fitted-current.json').read_bytes()),entries=[row],pending=[],counts=dict(changed=1,retained=0,added=0,pending=0))
    rp=tmp_path/'review.json';write(rp,review)
    current=json.loads((g/'manifest.json').read_text())['entries'][0]
    return g,review,table,rp,current

def test_same_exhibit_from_two_captures_preserves_history_and_explicit_qa(tmp_path,monkeypatch):
    g,review,table,rp,current=setup(tmp_path,monkeypatch)
    g2=tmp_path/'gallery2';g2.mkdir();fixture_gallery(g2)
    qa=dict(scope='Only one inspected exhibit',entries=[dict(id='tree',status='requires_correction',observations='Detached branch')])
    for field,file in [('manifest_sha256','manifest.json'),('capture_sha256','capture.json'),('sheet_report_sha256','sheet-report.json')]:qa[field]=r.digest((g/file).read_bytes())
    write(g/'visual-qa-reviewer.json',qa)
    result=r.build(rp,[g,g2],tmp_path/'local/report',g/'fitted-current.json',current_entries=[current])
    assert result['current_exhibits_captured']==1 and result['captures']==12
    item=result['items'][0];assert len(item['history'])==2 and item['manual_qa'][0]['status']=='requires_correction'
    assert item['manual_qa'][0]['applies_to_current'] and result['status']=='in_progress'

def test_old_assembly_placement_is_stale_even_when_cell_bytes_match(tmp_path,monkeypatch):
    g,review,table,rp,current=setup(tmp_path,monkeypatch)
    current['placements']=[dict(source=[0,0],offset=[0,1,0])]
    result=r.build(rp,[g],tmp_path/'local/report',g/'fitted-current.json',current_entries=[current])
    assert result['items'][0]['captured'] and not result['items'][0]['current_capture']
    assert result['reviewed_without_capture']==1

def test_current_only_capture_never_claims_a_before_companion(tmp_path,monkeypatch):
    g,review,table,rp,current=setup(tmp_path,monkeypatch)
    m=json.loads((g/'manifest.json').read_text());m['entries']=m['entries'][:1];write(g/'manifest.json',m)
    c=json.loads((g/'capture.json').read_text());c['captures']=[x for x in c['captures'] if x['id']=='tree'];c['manifest_sha256']=r.digest((g/'manifest.json').read_bytes());write(g/'capture.json',c)
    s=json.loads((g/'sheet-report.json').read_text());s['manifest_sha256']=c['manifest_sha256'];s['capture_coverage']=capture_coverage(m['entries'],c['captures'],m['views']);s['entries'][0]['originals']=[x for x in s['entries'][0]['originals'] if '_before_' not in x['file']];write(g/'sheet-report.json',s)
    with pytest.raises(ValueError,match='baseline companion'):verify_gallery(g,review,table)
    result=r.build(rp,[g],tmp_path/'local/report',g/'fitted-current.json',current_entries=[current])
    item=result['items'][0];assert not item['baseline_present'] and item['manual_qa']==[]
    assert 'No baseline companion captured' in item['baseline_status']

def test_stale_manual_provenance_cannot_be_reported_as_review(tmp_path,monkeypatch):
    g,review,table,rp,current=setup(tmp_path,monkeypatch)
    write(g/'visual-qa-old.json',dict(manifest_sha256='wrong',entries=[dict(id='tree',status='ready')]))
    with pytest.raises(ValueError,match='stale separate visual QA'):
        r.build(rp,[g],tmp_path/'local/report',g/'fitted-current.json',current_entries=[current])

def test_native_baseline_descriptor_change_requires_new_capture():
    entry=dict(id='tree',kind='cell',source_keys=['x']);table=dict(cells={'x':{}},buildings=[])
    expected=dict(entry,baseline_native_fragments={'evidence_sha256':'new-proof'})
    assert not r.is_current(entry,table,table,expected)


@pytest.mark.parametrize('field',['current_native_fragments','native_current_evidence'])
def test_current_native_proof_change_invalidates_capture_and_revision_identity(field):
    entry=dict(id='native-tree',kind='compound',source_keys=['x'],fragments=[{'key':'cube:0|1,1,1,1,1,1'}])
    table=dict(cells={'x':{}},buildings=[]);expected=dict(entry,**{field:{'evidence_sha256':'new-source-proof'}})
    assert not r.is_current(entry,table,table,expected)
    assert r.revision_identity(entry,table)!=r.revision_identity(expected,table)


def test_different_stale_revisions_do_not_share_manual_observations(tmp_path,monkeypatch):
    g,review,table,rp,current=setup(tmp_path,monkeypatch)
    g2=tmp_path/'gallery2';g2.mkdir();fixture_gallery(g2)
    m=json.loads((g2/'manifest.json').read_text());m['entries'][0]['placements']=[dict(source=[0,0],offset=[0,1,0])];write(g2/'manifest.json',m)
    mh=r.digest((g2/'manifest.json').read_bytes())
    for name in ('capture.json','sheet-report.json'):
        obj=json.loads((g2/name).read_text());obj['manifest_sha256']=mh;write(g2/name,obj)
    for directory,note in [(g,'Detached branch in first revision'),(g2,'Different placement in second revision')]:
        qa=dict(entries=[dict(id='tree',status='requires_correction',observations=note)])
        for field,file in [('manifest_sha256','manifest.json'),('capture_sha256','capture.json'),('sheet_report_sha256','sheet-report.json')]:qa[field]=r.digest((directory/file).read_bytes())
        write(directory/'visual-qa-reviewer.json',qa)
    current['placements']=[dict(source=[0,0],offset=[0,2,0])]
    result=r.build(rp,[g,g2],tmp_path/'local/report',g/'fitted-current.json',current_entries=[current])
    item=result['items'][0]
    assert not item['current_capture']
    assert [q['observations'] for q in item['manual_qa']]==['Different placement in second revision']
    assert len(item['history'][0]['manual_qa'])==len(item['history'][1]['manual_qa'])==1
