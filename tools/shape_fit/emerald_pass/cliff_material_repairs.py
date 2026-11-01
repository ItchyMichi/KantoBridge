"""Source-local brown cliff paint and two source-proven shoreline corner repairs."""
from __future__ import annotations
import argparse,copy,hashlib,json
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import decode,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.inventory import contact_pages
from tools.shape_fit.emerald_pass.ship_beams import previews

OUT=ROOT/'local/model-gallery/emerald-pass-study/cliff-material-repairs'
STUDY=ROOT/'local/model-gallery/emerald-pass-study/environment-repairs'
ORDINALS={1,3,4,5,6,7,8,9,10,11,12,13,22,26,28,29,30,31}
SEAM_KEYS={'g3q/10701-10990-10682-10703','g3q/10719-10720-10721-11944',
           'g3q/11808-10698-11816-10700','g3q/11808-10714-11844-10716',
           'g3q/11921-10698-11894-10700','g3q/11921-10714-11935-10716'}
BROWN=((99,66,82),(132,90,90),(156,115,115),(189,148,140),(222,181,165),(239,214,206))
# The same native cliff artwork has sand/foam background variants; the hand
# sand variants provide an existing coherent diagonal volume, never foam art.
CORNER_SIBLINGS={'g3q/11399-11718-11727-10676':'g3q/10728-10732-10733-10676',
                 'g3q/11736-11408-10682-11745':'g3q/10740-10729-10682-10741'}

def rows():
    original=[r for r in read(STUDY/'entries.json') if r['repair_ordinal'] in ORDINALS]
    seams=[dict(r,captured_findings=[],repair_ordinal=None) for r in read(OUT/'seam-source.json') if r['key'] in SEAM_KEYS]
    return original+seams

def rock_palette(sprite):
    a=np.asarray(sprite.convert('RGB')).reshape(-1,3)
    return a,np.asarray([int(np.flatnonzero((a==c).all(axis=1))[0]) for c in BROWN if np.any((a==c).all(axis=1))],np.uint8)

def repaint(entry,sprite,source_sprite=None):
    occ,p=decode(entry);a,uv=rock_palette(sprite);assert len(uv)>=3
    origin=np.asarray((source_sprite or sprite).convert('RGB')).reshape(-1,3)
    # Only source addresses supply colors; preserve every native brown texel.
    distance=((origin[:,None,:].astype(int)-a[uv][None,:,:].astype(int))**2).sum(axis=2)
    lookup=uv[distance.argmin(axis=1)]
    return occ,lookup[p]

def generate(art,baseline,pre):
    pre={**pre,'cells':{**pre['cells'],**read(OUT/'seam-preconditions.json')['cells']}}
    updates={};ledger=[]
    for r in rows():
        k=r['key'];old=pre['cells'][k];sibling=CORNER_SIBLINGS.get(k);shape=pre['cells'][sibling] if sibling else old
        occ,p=repaint(shape,art.cell(k,'BAKED'),art.cell(sibling,'BAKED') if sibling else None)
        updates[k]=encode(occ,p,old,'hand-emerald-cliff-material-repair')
        reason=('The exact native cliff face is shared with the listed sand-background sibling. Minecraft QA found detached foam geometry above the stone; use that sibling’s coherent existing diagonal hand-authored cliff volume, painted from this key’s own native brown rock pixels. Foam remains source background, not suspended voxels. ' if sibling else
                'Minecraft QA and reconstructed map context identify the raised yellow/blue/gray marks as sand, water/sky, foam or outline colors outside the native brown cliff face. Preserve the complete existing occupied cliff volume and all metadata; remap only paint outside the six source-native rock colors to this cell’s closest available brown rock shade. No background material is wrapped over stone sides or tops. ')
        if k in SEAM_KEYS:reason='The complete source/current cliff contexts exposed the same blue/gray background bands in this adjoining stone cell. '+reason
        reason+='This is a material/terrain-component correction; source collision, height placement and neighboring model geometry remain unchanged. Fresh complete-context Minecraft QA is pending.'
        ledger.append({'key':k,'kind':'cell','label':'Brown cliff face with source-local rock paint','family':'Cliff material repair','decision':'changed',
            'baseline_entry_sha256':entry_hash(baseline['cells'][k]),'previous_entry_sha256':entry_hash(old),'geometry_sibling':sibling,
            'reason':reason,'occurrences':r['occurrences'],'captured_findings':r['captured_findings'],
            'evidence':[str(STUDY/'contexts'/f'page-{(r["repair_ordinal"]-1)//8+1:02d}.png') if r['repair_ordinal'] else str(OUT/'study/contexts/page-001.png'),str(OUT/'source-evidence.json'),str(OUT/'preview/assemblies/index.json')]})
    return updates,ledger

def assemblies(art):
    # Exact native two-cell rock/cliff cap pairs. Their lower row is preserved
    # in place to expose material joins and show complete isolated mounds where
    # that is their source context. Broader cliffs remain component contexts.
    pairs=[('g3q/10728-10732-10733-10676','g3q/10740-10729-10682-10741'),
           ('g3q/10771-10772-10773-10676','g3q/10776-10771-10682-10777'),
           ('g3q/11399-11718-11727-10676','g3q/11736-11408-10682-11745'),
           ('g3q/11858-11859-11860-10676','g3q/11877-11878-10682-11879')]
    found={}
    for left,right in pairs:
        for p in art.locations[left]:
            m=art.maps[p['map']];grid=art.map_cells(m);x,z=p['x'],p['y']
            if x+1>=m['w'] or z+1>=m['h'] or grid[z][x+1]!=right:continue
            layout=[row[x:x+2] for row in grid[z:z+2]]
            ident='emerald_cliff_material_'+hashlib.sha256(json.dumps(layout,separators=(',',':')).encode()).hexdigest()[:12]
            e=found.setdefault(ident,{'id':ident,'kind':'object (model as one)','note':'Exact two-by-two cliff cap/mound source context; lower native sections preserved',
                'layout':layout,'placements':[{'source':[x,z],'offset':[x,0,z]} for z in range(2) for x in range(2)],'occurrences':[]})
            e['occurrences'].append(p)
    return list(found.values())

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    art=EmeraldArt(BUNDLE);base=read(BASELINE);pre=read(STUDY/'preconditions.json');updates,ledger=generate(art,base,pre);entities=assemblies(art)
    for e in entities:
        for row in e['layout']:
            for k in row:assert k in base['cells'],k
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('source-evidence.json',{'original_baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'source_palette_rgb':BROWN,'geometry_siblings':CORNER_SIBLINGS,'groups':entities,'limits':'Twenty-two paint-only corrections plus two coherent sibling-volume repairs. Collision/metadata unchanged; actual Minecraft QA pending.'})]:write_json(OUT/name,value)
    if args.previews:
        cur=copy.deepcopy(base);cur['cells'].update(updates);contact_pages('current',rows(),cur,art,OUT/'preview');previews(entities,cur,art,OUT/'preview/assemblies')
    print(len(updates),'changes;',len(entities),'source contexts')
if __name__=='__main__':main()
