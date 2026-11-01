"""Individually reviewed Lilycove Museum paintings and carved green exhibits.

The existing reception desk, broad relief platform, five display walls and
stair surrounds are retained after complete source/front/side/angled review.
Paintings are shallow native-pixel panels, with exact vertical source pairs.
The three curved figurines are whole sculptures before their source slices.
"""
import base64,copy,hashlib
from pathlib import Path
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,render
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.review import ROOT,BASELINE,GEOMETRY,entry_hash,read
from tools.shape_fit.emerald_pass.scenery import BUNDLE

REGISTRY=Path(__file__).with_name('lilycove_museum_registry.json')
OUT=ROOT/'local/model-gallery/emerald-pass-study/lilycove-museum'

def whole(role,spec=None):
    spec=spec or {}
    if role=='painting':
        h=spec['height'];w=spec['width'];x0,y0,x1,y1=spec['crop']
        m=Model(height=h,width=w)
        m.box((x0,x1),(h-y1,h-y0),(2,4),0)
        m.box((x0,x1),(h-y1,h-y0),(4,5),1)
        m.box((x0+1,x1-1),(h-y1+1,h-y0-1),(4,5),cut=True)
        return m
    if role=='figurine':
        m=Model(height=32)
        m.box((2,14),(0,3),(2,14),0).box((3,13),(3,5),(3,13),1)
        m.ellipsoid((8,7,8),(5,3,4),2)
        m.ellipsoid((9,13,8),(4.5,7,3.8),2)
        m.ellipsoid((7.5,21,8),(3,6,3.2),1)
        m.ellipsoid((7,27,8),(5,4,4),1)
        m.box((2,6),(24,28),(6,10),2)
        m.ellipsoid((9,29,8),(3.5,2,3),3)
        m.ellipsoid((11,15,8),(3,5,2.5),3)
        # A shallow carved eye and a curled tail are attached to the body.
        m.box((4,6),(27,28),(11,12),0)
        m.ellipsoid((5,7,10),(3,2,2),1)
        if spec.get('mirror'):
            m.occ=m.occ[:,:,::-1].copy();m.paint=m.paint[:,:,::-1].copy()
        return m
    if role=='round_exhibit':
        m=Model()
        m.box((2,14),(0,4),(2,14),0).box((3,13),(4,5),(3,13),1)
        m.box((6,10),(4,9),(6,10),2)
        yy,zz,xx=np.indices(m.occ.shape)
        disc=((xx+.5-8)**2+(yy+.5-10)**2<=5.4**2)&(zz>=6)&(zz<10)
        m.put(disc,1)
        ring=((xx+.5-8)**2+(yy+.5-10)**2>=3.5**2)&disc&(zz>=9)
        m.put(ring,3)
        m.box((7,9),(9,12),(9,11),2)
        return m
    raise ValueError(role)

def green_material(sprite,target):
    rgb=np.asarray(sprite.convert('RGB')).reshape(-1,3).astype(float)
    valid=(rgb[:,1]>rgb[:,0]+8)&(rgb[:,1]>=rgb[:,2])
    assert valid.any(),'Source has no native green sculpture material'
    distance=((rgb-np.asarray(target))**2).sum(1);distance[~valid]=np.inf
    return int(distance.argmin())

def model(sprite,spec):
    full=whole(spec['role'],spec);x=spec.get('x',0)*16;y=spec.get('layer',0)*16
    m=Model();m.occ=full.occ[y:y+16,:,x:x+16].copy();semantic=full.paint[y:y+16,:,x:x+16]
    if spec['role']=='painting':
        x0,y0,x1,y1=spec['crop'];sy=(spec['height']//16-1-spec.get('layer',0))*16
        rect=(max(0,x0-x),max(0,y0-sy),min(16,x1-x),min(16,y1-sy))
        gold=material(sprite,(126,113,20),rect);m.paint[m.occ]=gold
        # Every visible front uses its exact local source coordinate; no
        # stretching or copy of surrounding wall/cornice/skirting pixels.
        neighbor=np.roll(m.occ,-1,axis=1);neighbor[:,-1,:]=False
        yy,zz,xx=np.indices(m.occ.shape);front=m.occ&~neighbor
        m.paint[front]=((15-yy)*16+xx)[front]
    else:
        addresses=np.array([green_material(sprite,c) for c in [(70,100,85),(130,165,145),(100,140,120),(165,195,180)]],np.uint8)
        m.paint=addresses[semantic]
    return m

def generate(base,art):
    patch={};ledger=[];metadata=[]
    for key,spec in read(REGISTRY).items():
        old=base['cells'][key];assert entry_hash(old)==spec['baseline_entry_sha256']
        if spec.get('role'):
            new=model(art.cell(key,'BAKED'),spec).entry(old,source='hand-emerald-lilycove-museum')
            if spec.get('added_on'):new['on']=spec['added_on']
            patch[key]=new
            before={k:v for k,v in old.items() if k not in GEOMETRY};after={k:v for k,v in new.items() if k not in GEOMETRY}
            if before!=after:
                places=[]
                for loc in art.locations[key]:
                    src=art.maps[loc['map']];x,y=loc['x'],loc['y'];grid=art.map_cells(src)
                    places.append(dict(loc,collision=base64.b64decode(src['cells'])[(y*src['w']+x)*4+2],below_key=grid[y+1][x]))
                metadata.append(dict(key=key,before=before,after=after,source_pixel_sha256=hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest(),occurrences=places))
        ledger.append(dict(spec,key=key,kind='cell',decision='changed' if key in patch else 'retained',occurrences=art.locations[key],evidence=['museums-clubs-study/context/building__lilycove_museum','lilycove-museum-study/baseline-wholes','lilycove-museum/assemblies'],visual_status='Local authoring review; Minecraft comparison pending'))
    return patch,ledger,metadata

def main():
    art=EmeraldArt(BUNDLE);base=read(BASELINE);patch,rows,metadata=generate(base,art)
    write_json(OUT/'updates.json',dict(cells=patch,buildings={}))
    write_json(OUT/'ledger.json',rows);write_json(OUT/'metadata-evidence.json',metadata)
    entities=[]
    for s in read(REGISTRY.with_name('lilycove_museum_assemblies.json')):
        grid=art.map_cells(art.maps[s['map']]);x,y,w,h=s['rect'];assert [r[x:x+w] for r in grid[y:y+h]]==s['source_layout']
        e={k:copy.deepcopy(s[k]) for k in ('id','kind','note','layout','placements','baseline_layout','baseline_placements')}
        if s['source_layout']!=s['layout']:e['reference_layout']=s['source_layout']
        entities.append(e)
    write_json(OUT/'entities.json',entities)
    for key,new in patch.items():render(new,art.cell(key,'BAKED'),OUT/'models'/key.split('/')[1])
    print(len(patch),'changes;',len(rows),'decisions;',len(entities),'complete objects;',len(metadata),'metadata amendments')

if __name__=='__main__':main()
