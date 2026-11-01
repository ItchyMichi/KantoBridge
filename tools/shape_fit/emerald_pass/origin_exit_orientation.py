"""Face the complete Cave of Origin exit rim toward its actual north approach."""
import argparse,base64,copy,hashlib
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import decode,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE
from tools.shape_fit.emerald_pass.review import read,TABLE,entry_hash
from tools.shape_fit.emerald_pass.inventory import contact_pages
from tools.shape_fit.emerald_pass.ship_beams import previews
OUT=ROOT/'local/model-gallery/emerald-pass-study/origin-exit-orientation'
KEYS=('g3q/15302-15303-15244-15245','g3q/15304-15305-15244-15245','g3q/15306-15307-15244-15245')

def sources(art):
    rows=[]
    for key in KEYS:
        places=[]
        for p in art.locations[key]:
            m=art.maps[p['map']];raw=base64.b64decode(m['cells']);i=(p['y']*m['w']+p['x'])*4;assert raw[i+2]==7
            places.append(dict(p,collision=raw[i+2],elevation=raw[i+3],map_cells_sha256=hashlib.sha256(raw).hexdigest()))
        assert len(places)==2
        rows.append({'key':key,'group':'general__cave','building_source_membership':[],'maps':sorted({p['map'] for p in places}),'occurrences':places})
    return rows

def generate(art,base,pre):
    updates={};ledger=[]
    for r in sources(art):
        key=r['key'];old=pre['cells'][key];occ,paint=decode(old)
        # Reflect only world depth. Source x membership and each source-local
        # texel address stay attached to their original occupied surface.
        reflected=occ[:,::-1,:].copy();painted=paint[:,::-1,:].copy()
        if key==KEYS[1]:
            # This source's lower half is the surrounding cave stone. The rear
            # backing layer and roof exterior must not expose the white light
            # of the portal interior on their opposite voxel faces.
            yy,xx=np.indices((reflected.shape[0],16));painted[:,-1,:]=(np.minimum(15,8+yy//2)*16+xx).astype(np.uint8)
            zz,xx=np.indices((16,16));painted[-1,:,:]=(np.minimum(15,8+zz//2)*16+xx).astype(np.uint8)
        updates[key]=encode(reflected,painted,old,'hand-emerald-origin-exit-orientation')
        ledger.append({'key':key,'kind':'cell','decision':'changed','label':'North-facing Cave of Origin exit '+('west rim','bright recessed portal','east rim')[KEYS.index(key)],
            'family':'Source-oriented cave exit rim','baseline_entry_sha256':entry_hash(base['cells'][key]),'previous_entry_sha256':entry_hash(old),'occurrences':r['occurrences'],
            'reason':'Both complete source maps place the WALK114 exit warp immediately north of this three-cell SOLID rim. Whole local inspection shows a coherent existing recessed bright portal and stone jambs, but their opening faces south, away from that approach. Reverse depth for the complete three-piece rim so the recessed aperture faces its actual northern approach. Preserve source x membership, every occupied voxel, height, metadata and native source collisions; no warp is moved or invented. The center retains its bright recessed interior, while its existing rear backing layer and roof exterior are painted from the same source’s lower stone half so exit light is not projected onto the outside. Actual source fixture and Minecraft north-side inspection remain required.',
            'evidence':[str(OUT/'source-evidence.json'),str(OUT.parent/'origin-ledge-study/whole-current/page-001.png'),str(OUT/'runtime-proof.json')]})
    return updates,ledger

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args();OUT.mkdir(exist_ok=True);art=EmeraldArt(BUNDLE);base=read(BASELINE)
    if not (OUT/'preconditions.json').exists():write_json(OUT/'preconditions.json',{'cells':{k:read(TABLE)['cells'][k] for k in KEYS}})
    pre=read(OUT/'preconditions.json');updates,ledger=generate(art,base,pre);groups=read(OUT.parent/'origin-ledge-study/sources.json')['groups']
    for g in groups:
        assert g['collisions'][1][1]==114 and g['layout'][2]==list(KEYS)
    entity={'id':'emerald_origin_north_facing_exit_rim','kind':'object (model as one)','layout':[list(KEYS)],'placements':[{'source':[x,0],'offset':[x,0,0]} for x in range(3)],'occurrences':[{'map':g['map'],'x':g['x'],'y':g['y']+2} for g in groups],
        'note':'Complete three-piece rim, opening toward the source warp immediately north. Gallery default front views show its south back; native approach view must be inspected before approval.'}
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',[entity]),('source-evidence.json',{'sources':sources(art),'groups':groups,'warp_approach':'WALK114 one row north of the central SOLID rim; native collision and tags unchanged','status':'orientation candidate; exact native route and actual north-side Minecraft inspection pending'})]:write_json(OUT/name,value)
    if args.previews:
        current=copy.deepcopy(base);current['cells'].update(pre['cells']);current['cells'].update(updates);contact_pages('candidate',sources(art),current,art,OUT);previews([entity],current,art,OUT/'whole-current')
        # Study-only camera counterpart: reflect the whole back to face the
        # local preview camera, without changing the actual candidate table.
        north=copy.deepcopy(current)
        for k in KEYS:
            occ,paint=decode(current['cells'][k]);north['cells'][k]=encode(occ[:,::-1,:].copy(),paint[:,::-1,:].copy(),current['cells'][k],'study-north-view')
        previews([dict(entity,id=entity['id']+'_north_approach_study')],north,art,OUT/'north-approach-study')
    print('Three source-oriented exit rim candidates; native and north-view Minecraft checks pending')
if __name__=='__main__':main()
