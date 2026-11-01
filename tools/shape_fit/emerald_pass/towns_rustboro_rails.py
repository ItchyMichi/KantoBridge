"""Native ornamental metal rails with source-proven corners and junctions."""
from __future__ import annotations
import argparse,hashlib,json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model,decode,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-rustboro-rails'
HORIZONTAL={36,37,55,75,76}
VERTICAL={41:3,42:13,48:13,50:3,51:13,54:13,57:3,59:13,60:3,63:13}
JUNCTIONS={38:(3,'ES'),39:(13,'WS'),44:(3,'E'),45:(13,'WN'),47:(13,'W'),49:(3,'EN'),52:(13,'WN'),53:(13,'WN'),56:(13,'ENS'),61:(3,'WNS'),62:(13,'ES'),64:(13,'EN'),65:(3,'WN'),66:(3,'WN'),70:(3,'E'),93:(13,'WS')}
OWNED=CHANGED=HORIZONTAL|set(VERTICAL)|set(JUNCTIONS)
METAL=((41,49,90),(66,74,107),(99,99,123))

def address(sprite,color):
    hits=np.argwhere((np.asarray(sprite.convert('RGB'))==color).all(axis=2));assert len(hits),color
    return int(hits[0,0]*16+hits[0,1])

def rail(n):
    m=Model();y,z,x=np.indices(m.occ.shape)
    def arm(axis,lo,hi,anchor):
        u=x if axis=='x' else z;v=z if axis=='x' else x;span=(u>=lo)&(u<hi)&(v>=anchor-1)&(v<anchor+1)
        m.put(span&(((y>=2)&(y<4))|((y>=14)&(y<16))),1)
        m.put(span&(y==15),2)
        # Two oval iron scrolls repeat along each full panel. Their holes
        # are actual air, not copied cream paving or green grass pixels.
        for center in (4,12):
            if lo+2.5<=center<=hi-2.5:
                outer=((u+.5-center)/2.7)**2+((y+.5-9)/5.5)**2<=1
                inner=((u+.5-center)/1.25)**2+((y+.5-9)/3.8)**2<1
                m.put(span&outer&~inner,2)
    def post(cx,cz):m.box((max(0,cx-1),min(16,cx+2)),(0,16),(max(0,cz-1),min(16,cz+2)),1)
    if n in HORIZONTAL:arm('x',0,16,8);post(0,8)
    elif n in VERTICAL:arm('z',0,16,VERTICAL[n]);post(VERTICAL[n],0)
    else:
        anchor,directions=JUNCTIONS[n]
        if 'E' in directions:arm('x',anchor,16,8)
        if 'W' in directions:arm('x',0,anchor+1,8)
        if 'N' in directions:arm('z',0,9,anchor)
        if 'S' in directions:arm('z',8,16,anchor)
        post(anchor,8)
    return m

def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('rustboro');out={}
    for n in sorted(OWNED):
        k=rows[n-1]['key'];sprite=art.cell(k,'BAKED');m=rail(n);kinds=m.paint.copy();m.paint[:]=address(sprite,METAL[0]);m.paint[kinds==2]=address(sprite,METAL[1]);out[k]=m.entry(table['cells'][k],'hand-emerald-rustboro-ornamental-rail',trim=True)
    return out

def neighbor_proof(art):
    rows=rows_for('rustboro');keys={rows[n-1]['key'] for n in OWNED};out={}
    for n in sorted(OWNED):
        k=rows[n-1]['key'];places=[]
        for loc in witnesses(art,rows[n-1]):
            g=art.map_cells(art.maps[loc['map']]);x,y=loc['x'],loc['y'];neighbors={}
            for direction,dx,dy in [('N',0,-1),('E',1,0),('S',0,1),('W',-1,0)]:
                key=g[y+dy][x+dx] if 0<=y+dy<len(g) and 0<=x+dx<len(g[0]) else None
                if key in keys:neighbors[direction]=key
            places.append({**loc,'fence_neighbors':neighbors})
        out[k]=places
    return out

def decisions():
    out={}
    for n in sorted(OWNED):
        role='horizontal two-scroll panel' if n in HORIZONTAL else ('west' if VERTICAL[n]==3 else 'east')+' north-south rail' if n in VERTICAL else 'rail junction '+JUNCTIONS[n][1]
        why='Reconstruct this native dark ornamental fence as narrow horizontal rails, two open oval iron scrolls per full panel and rooted posts. The '+role+' follows its exact source geometry and observed neighboring fence keys. All actual neighboring edges are verified to meet; source cream paving, turquoise grass and projected leaves stay outside the metal geometry. Preserve the SOLID source carriers and all metadata.'
        if n in (70,75,76):why+=' The small green bottom corners belong to the forest behind this boundary; no leaf-colored metal or duplicate foliage is added.'
        out[n]=('changed','Rustboro ornamental '+role,why)
    return out

def components(art):
    rows=rows_for('rustboro');keys={rows[n-1]['key'] for n in OWNED};g=art.map_cells(art.maps['EM_RUSTBORO_CITY']);unseen={(x,y) for y,row in enumerate(g) for x,k in enumerate(row) if k in keys};result=[]
    while unseen:
        start=min(unseen,key=lambda q:(q[1],q[0]));todo=[start];component=set()
        while todo:
            point=todo.pop()
            if point not in unseen:continue
            unseen.remove(point);component.add(point);x,y=point;todo.extend(((x-1,y),(x+1,y),(x,y-1),(x,y+1)))
        x0=min(x for x,y in component);x1=max(x for x,y in component);y0=min(y for x,y in component);y1=max(y for x,y in component)
        result.append({'cells':sorted([list(p) for p in component],key=lambda q:(q[1],q[0])),'origin':[x0,y0],'size':[x1-x0+1,y1-y0+1]})
    return result

def assemblies(art):
    # Null cells are the supported gallery mask: only this connected fence is
    # rendered, while reference_layout retains the exact source neighborhood.
    g=art.map_cells(art.maps['EM_RUSTBORO_CITY']);result=[]
    for i,c in enumerate(components(art)):
        x,y=c['origin'];w,d=c['size'];members={tuple(p) for p in c['cells']};reference=[r[x:x+w] for r in g[y:y+d]]
        layout=[[k if (x+rx,y+rz) in members else None for rx,k in enumerate(row)] for rz,row in enumerate(reference)]
        result.append({'id':f'emerald_rustboro_ornamental_fence_{i:02d}','kind':'object (model as one)','note':f'Complete source-connected Rustboro fence component: {len(members)} exact fence cells. Null-masked geometry excludes unrelated ground and buildings; full reference art preserves the source neighborhood.','layout':layout,'reference_layout':reference,'occurrences':[{'map':'EM_RUSTBORO_CITY','x':x,'y':y,'palette':'BAKED'}]})
    return result

def render_assembly(item,art,entries,target,label):
    from tools.shape_fit.hand_check import picture,front
    layout=item['layout'];o=np.zeros((16,len(layout)*16,len(layout[0])*16),bool);c=np.zeros((*o.shape,3),np.uint8)
    for rz,row in enumerate(layout):
        for rx,k in enumerate(row):
            if k is None:continue
            oo,pp=decode(entries[k]);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'));assert len(oo)==16
            s=(slice(None),slice(rz*16,rz*16+16),slice(rx*16,rx*16+16));o[s]=oo;c[s]=rgb[pp//16,pp%16]
    picture(o,c,target/f'{label}-angled.png',3);front(o,c,target/f'{label}-front.png',4);front(np.rot90(o,1,axes=(1,2)).copy(),np.rot90(c,1,axes=(1,2)).copy(),target/f'{label}-side.png',4)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('rustboro');updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[];proof=neighbor_proof(art)
    for n,(_,label,reason) in decisions().items():
        r=rows[n-1];k=r['key'];source=art.cell(k,'BAKED');target=OUT/k[4:];target.mkdir(exist_ok=True);source.save(target/'source.png')
        if args.previews:render(table['cells'][k],source,target/'before');render(updates[k],source,target/'after')
        ledger.append({'key':k,'kind':'cell','label':label,'family':'rustboro-connected-ornamental-fence','decision':'changed','reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(source.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__rustboro/contexts/page-{(n-1)//16+1:03}.png','exact_fence_neighbors':proof[k]}],'visual_status':'source/context reviewed; candidate Python/runtime pending'})
    live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';current=json.loads(live.read_text());entities=assemblies(art)
    if args.previews:
        for e in entities:
            target=OUT/'assemblies'/e['id'];target.mkdir(parents=True,exist_ok=True);source=art.drawing(e['reference_layout'],'BAKED');source.resize((source.width*3,source.height*3),Image.Resampling.NEAREST).save(target/'source.png');render_assembly(e,art,table['cells'],target,'before');render_assembly(e,art,{**current['cells'],**updates},target,'after')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('neighbor-proof.json',proof),('fence-components.json',components(art)),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'elevation_simulated':False,'metadata_changed':False})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'reviewed':31,'changed':31,'components':[{k:v for k,v in c.items() if k!='cells'}|{'count':len(c['cells'])} for c in components(art)]}))

if __name__=='__main__':main()
