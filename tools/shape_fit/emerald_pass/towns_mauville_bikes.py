"""Complete Mauville bicycle pairs and source-native cut timber boundary posts."""
from __future__ import annotations
import argparse,hashlib,json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model,decode,encode,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-mauville-bikes'
WOOD={15,17,23,25,31,55,59};BIKES={37,38,39};OWNED=CHANGED=WOOD|BIKES
POSTS={15:((3,4),(11,4),(3,12)),17:((3,4),(11,4),(11,12)),23:((3,4),(3,12)),25:((11,4),(11,12)),31:((3,4),(11,4)),55:((11,4),),59:((11,4),(11,12))}
WOOD_COLORS=((107,90,90),(165,99,90),(222,148,115),(255,197,148))
BIKE_COLORS=((66,74,107),(132,132,140),(156,181,222),(222,230,239),(214,181,107),(239,230,165))

def address(sprite,color):
    h=np.argwhere((np.asarray(sprite.convert('RGB'))==color).all(axis=2));assert len(h),color
    return int(h[0,0]*16+h[0,1])

def posts(n):
    m=Model();y,z,x=np.indices(m.occ.shape)
    for cx,cz in POSTS[n]:
        # Cut logs have circular end grain and straight bark, rather than
        # a ground-colored hourglass around a billboard front face.
        m.cylinder((cx,cz),2.7,(0,13),1)
        side=m.occ&(y<13)&(((x-cx)>0)|((z-cz)>0));m.paint[side]=0
        m.cylinder((cx,cz),2.7,(13,14),2)
        m.cylinder((cx-.3,cz-.3),1.6,(13,14),3)
    return m

def bicycles():
    m=Model(height=24,width=48);y,z,x=np.indices(m.occ.shape);points=np.stack((x+.5,y+.5,z+.5),axis=-1)
    def tube(a,b,r,material):
        a,b=np.asarray(a),np.asarray(b);v=b-a;u=np.clip(((points-a)*v).sum(axis=-1)/(v*v).sum(),0,1);m.put(((points-a-u[...,None]*v)**2).sum(axis=-1)<=r*r,material)
    for origin in (0,24):
        # Both wheels, the two frame triangles and handlebars are one shared
        # three-cell object before slicing. Wheel centers stay six voxels high.
        for cx in (origin+6,origin+18):
            radius=((x+.5-cx)**2+(y+.5-5.5)**2)**.5
            m.put((radius<=5.5)&(radius>=3.6)&(z>=7)&(z<10),0)
            m.put((radius<4.8)&(radius>=3.6)&((z==7)|(z==9)),4)
            tube((cx,5.5,8.5),(cx,5.5,8.5+.1),1.0,3)
            tube((cx-3.6,5.5,8.5),(cx+3.6,5.5,8.5),.45,1)
            tube((cx,1.9,8.5),(cx,9.1,8.5),.45,1)
        back=(origin+6,5.5,8.5);crank=(origin+12,5.5,8.5);seat=(origin+10,13,8.5);neck=(origin+16.2,13,8.5);front=(origin+18,5.5,8.5)
        for a,b in ((back,seat),(seat,crank),(back,crank),(seat,neck),(neck,crank),(neck,front)):
            tube(a,b,.8,2)
        tube(seat,(origin+9.5,15,8.5),.85,1);m.box((origin+7,origin+12),(15,16),(7,10),0)
        tube(neck,(origin+16,17,8.5),.8,3);tube((origin+16,17,5.5),(origin+16,17,11.5),.8,1)
        m.box((origin+15,origin+17),(17,18),(5,7),0);m.box((origin+15,origin+17),(17,18),(10,12),0)
        tube(crank,(origin+12,5.5,11.5),.7,1);m.box((origin+11,origin+14),(5,6),(11,13),0)
        tube((origin+12,6,8.5),(origin+14,0.6,12),.65,0)
    # The source handlebars are left of each saddle. Mirror each complete
    # bicycle independently, before its three source-cell slices are cut.
    for start in (0,24):
        m.occ[:,:,start:start+24]=m.occ[:,:,start:start+24][:,:,::-1].copy()
        m.paint[:,:,start:start+24]=m.paint[:,:,start:start+24][:,:,::-1].copy()
    return m

def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('mauville');out={};whole=bicycles()
    for n in sorted(OWNED):
        k=rows[n-1]['key'];s=art.cell(k,'BAKED')
        if n in WOOD:m=posts(n);colors=WOOD_COLORS
        else:
            m=Model(height=24);start=(n-37)*16;m.occ=whole.occ[:,:,start:start+16].copy();m.paint=whole.paint[:,:,start:start+16].copy();colors=BIKE_COLORS
        kinds=m.paint.copy()
        for i,color in enumerate(colors):m.paint[kinds==i]=address(s,color)
        out[k]=m.entry(table['cells'][k],'hand-emerald-mauville-bicycles-and-posts',trim=True)
    return out

def decisions():
    labels={15:'timber northwest corner',17:'timber northeast corner',23:'timber west boundary',25:'timber east boundary',31:'paired horizontal cut logs',55:'timber southwest terminal',59:'timber upper terminal',37:'bicycle pair left section',38:'bicycle pair middle wheels',39:'bicycle pair right section'}
    out={}
    for n,label in labels.items():
        reason=('Hand-author the complete pair of parked bicycles at each of four exact three-cell source occurrences: four hollow wheels, native gold rims, spokes, triangular frames, saddles, handlebars, pedals and narrow grounded stands. Slice one coherent 48-voxel-wide object into its original source keys and repaint each slice from its own sprite; no grass platform or green side faces.' if n in BIKES else 'Native source shows separate cut timber posts along the boundary. Author straight grounded cylindrical logs with light end grain, a small central growth ring and darker bark; preserve the explicit corner/edge post positions and open gaps. Exclude green grass and cast-shadow colors, and keep all source placement and SOLID metadata.')
        out[n]=('changed',label,reason)
    return out

def fence_components(art):
    rows=rows_for('mauville');keys={rows[n-1]['key'] for n in WOOD};result=[]
    for mid in sorted({p['map'] for n in WOOD for p in rows[n-1]['occurrences']}):
        g=art.map_cells(art.maps[mid]);unseen={(x,z) for z,row in enumerate(g) for x,k in enumerate(row) if k in keys}
        while unseen:
            todo=[min(unseen,key=lambda p:(p[1],p[0]))];members=set()
            while todo:
                p=todo.pop()
                if p not in unseen:continue
                unseen.remove(p);members.add(p);x,z=p;todo.extend(((x-1,z),(x+1,z),(x,z-1),(x,z+1)))
            x0=min(x for x,z in members);z0=min(z for x,z in members);x1=max(x for x,z in members);z1=max(z for x,z in members)
            result.append({'map':mid,'origin':[x0,z0],'size':[x1-x0+1,z1-z0+1],'members':sorted([list(p) for p in members],key=lambda p:(p[1],p[0]))})
    return result

def assemblies(art):
    result=[]
    for i,c in enumerate(fence_components(art)):
        g=art.map_cells(art.maps[c['map']]);x,z=c['origin'];w,d=c['size'];members={tuple(p) for p in c['members']};reference=[r[x:x+w] for r in g[z:z+d]];layout=[[k if (x+rx,z+rz) in members else None for rx,k in enumerate(row)] for rz,row in enumerate(reference)]
        result.append({'id':f'emerald_mauville_timber_boundary_{i:02d}','kind':'object (model as one)','note':'Complete source-connected timber boundary; separate posts have intentional open gaps, and null cells exclude unrelated scenery. Native elevations remain source-controlled.','layout':layout,'reference_layout':reference,'occurrences':[{'map':c['map'],'x':x,'y':z,'palette':'BAKED'}]})
    rows=rows_for('mauville');layout=[[rows[n-1]['key'] for n in (37,38,39)]]
    result.append({'id':'emerald_mauville_parked_bicycle_pair','kind':'object (model as one)','note':'Complete pair of parked bicycles, four exact source occurrences in the Mauville bicycle yard.','layout':layout,'occurrences':rows[36]['occurrences']})
    return result

def render_assembly(item,art,entries,target,label):
    from tools.shape_fit.hand_check import picture,front
    layout=item['layout'];height=max(len(decode(entries[k])[0]) for row in layout for k in row if k);o=np.zeros((height,len(layout)*16,len(layout[0])*16),bool);c=np.zeros((*o.shape,3),np.uint8)
    for rz,row in enumerate(layout):
        for rx,k in enumerate(row):
            if not k:continue
            oo,pp=decode(entries[k]);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'));s=(slice(0,len(oo)),slice(rz*16,rz*16+16),slice(rx*16,rx*16+16));o[s]=oo;c[s]=rgb[pp//16,pp%16]
    picture(o,c,target/f'{label}-angled.png',4);front(o,c,target/f'{label}-front.png',5);front(np.rot90(o,1,axes=(1,2)).copy(),np.rot90(c,1,axes=(1,2)).copy(),target/f'{label}-side.png',5)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('mauville');updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n,(_,label,reason) in decisions().items():
        r=rows[n-1];k=r['key'];source=art.cell(k,'BAKED');target=OUT/k[4:];target.mkdir(exist_ok=True);source.save(target/'source.png')
        if args.previews:render(table['cells'][k],source,target/'before');render(updates[k],source,target/'after')
        ledger.append({'key':k,'kind':'cell','label':label,'family':'mauville-bicycles' if n in BIKES else 'mauville-timber-boundary','decision':'changed','reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(source.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__mauville/contexts/page-{(n-1)//16+1:03}.png'}],'visual_status':'source/context reviewed; Python/runtime candidate pending'})
    live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';current=json.loads(live.read_text());entities=assemblies(art)
    if args.previews:
        for e in entities:
            target=OUT/'assemblies'/e['id'];target.mkdir(parents=True,exist_ok=True);source=art.drawing(e.get('reference_layout',e['layout']),'BAKED');source.resize((source.width*3,source.height*3),Image.Resampling.NEAREST).save(target/'source.png');render_assembly(e,art,table['cells'],target,'before');render_assembly(e,art,{**current['cells'],**updates},target,'after')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('fence-components.json',fence_components(art)),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'elevation_simulated':False,'metadata_changed':False})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'reviewed':len(OWNED),'changed':len(updates),'entities':len(entities),'fence_components':len(entities)-1}))

if __name__=='__main__':main()
