"""Rustboro lamp/sign stacks, Southern Island stone and source-local foliage."""
from __future__ import annotations
import argparse,hashlib,json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model,decode,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses
from tools.shape_fit.emerald_pass import towns_dewford,scenery,vegetation
OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-rustboro-props'
OWNED=CHANGED={1,2,3,4,40,43,46,58}
LABELS={1:'Route104 prototype ornamental lamp panel',2:'Rustboro lantern crown on pale paving',3:'Rustboro lantern crown on shaded paving',4:'Rustboro slim lamp pole and foot',40:'Southern Island moss-covered standing stone',43:'Rustboro Gym sign rounded crown',46:'Rustboro Gym sign body and foot',58:'Petalburg Woods entrance dense conifer crown'}
METAL=((41,49,90),(66,74,107),(132,132,140));GLASS=((156,181,222),(206,214,230),(222,230,239))

def address(sprite,color):
    hits=np.argwhere((np.asarray(sprite.convert('RGB'))==color).all(axis=2));assert len(hits),color
    return int(hits[0,0]*16+hits[0,1])

def lamp():
    m=Model(height=32);m.cylinder((8,8),3.5,(0,3),1);m.cylinder((8,8),2.4,(3,5),2);m.cylinder((8,8),1.1,(4,20),2);m.cylinder((8,8),2.5,(18,20),1)
    m.ellipsoid((8,24,8),(4.2,5,4.2),3)
    for x,z in ((4,8),(11,8),(8,4),(8,11)):m.box((x,x+1),(20,28),(z,z+1),1)
    m.cylinder((8,8),4.6,(27,29),1)
    for y,r in ((29,3.5),(30,2.4),(31,1.1)):m.cylinder((8,8),r,(y,y+1),2)
    return m

def lamp_section(sprite,upper):
    whole=lamp();start=16 if upper else 0;m=Model();m.occ[:]=whole.occ[start:start+16];kind=whole.paint[start:start+16];y,z,x=np.indices(m.occ.shape)
    m.paint[:]=address(sprite,METAL[1]);m.paint[kind==1]=address(sprite,METAL[0]);m.paint[(kind==2)&(x<8)]=address(sprite,METAL[2])
    if upper:
        palette=np.asarray([address(sprite,c) for c in GLASS],np.uint8);m.paint[kind==3]=palette[np.where(x<7,2,np.where(z>9,0,1))][kind==3]
    return m

def prototype(sprite):
    m=Model();dark=address(sprite,METAL[0]);gray=address(sprite,METAL[1]);glass=address(sprite,GLASS[0]);light=address(sprite,GLASS[2]);
    for y in (0,14):m.box((0,16),(y,y+2),(7,10),dark)
    for x in (0,14):m.box((x,x+2),(0,16),(7,10),dark)
    m.ellipsoid((8,8,8.5),(5.4,6,2),gray);m.ellipsoid((8,8,10),(3.8,4.3,1),glass);m.box((6,7),(7,11),(10,11),light)
    return m

def stone(sprite):
    m=Model();m.ellipsoid((8,7.3,8),(6.5,7.6,6.1));m.ellipsoid((6.5,12,8),(3.6,3.8,4.2));
    for z in range(16):
        for x in range(16):
            yy=np.flatnonzero(m.occ[:,z,x])
            if len(yy):m.occ[:yy.max()+1,z,x]=True
    y,z,x=np.indices(m.occ.shape);palette=np.asarray([address(sprite,c) for c in ((66,74,107),(99,99,123),(132,132,140))],np.uint8);noise=(x//2*3+z//2*7+y//2*11)%17;tone=np.where(y>=11,2,np.where((x>10)&(z>8),0,1));tone=np.where((noise<3)&(y>3),np.minimum(tone+1,2),tone);m.paint[:]=palette[tone]
    moss=(((x-7.5)/5)**2+((y-7)/3.3)**2<1)&(z>9)
    moss|=(((z-7)/4.5)**2+((y-11)/3.4)**2<1)&(x<6)
    moss&=noise!=6;m.paint[moss]=address(sprite,(123,165,74))
    m.paint[moss&(y>=12)&(x<7)]=address(sprite,(165,206,173));return m

def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('rustboro');result={}
    for n in sorted(OWNED):
        k=rows[n-1]['key'];sprite=art.cell(k,'BAKED')
        if n in (2,3,4):m=lamp_section(sprite,n!=4)
        elif n==1:m=prototype(sprite)
        elif n==40:m=stone(sprite)
        elif n==58:
            o,_=scenery.tree();m=Model();m.occ[:]=o[16:32];m.paint[:]=vegetation.leaf_paint(m.occ.shape,sprite,(16,0,0))
        else:
            m=towns_dewford.gym_sign(sprite,n==43);rgb=np.asarray(sprite.convert('RGB'));colors=rgb[m.paint//16,m.paint%16];ground=np.any(np.all(colors[...,None,:]==np.asarray(((239,239,214),(206,206,165),(189,189,132))),axis=-1),axis=-1);m.paint[m.occ&ground]=address(sprite,(222,230,239))
        result[k]=m.entry(table['cells'][k],'hand-emerald-rustboro-source-prop',trim=True)
    return result

def decisions():
    result={}
    for n,label in LABELS.items():
        if n in (2,3,4):why='Build one32-high traditional street lamp before slicing its existing on-linked halves. A broad grounded metal foot supports a narrow shaft, faceted pale-blue glass lantern, dark mullions and stepped cap. All17 exact upper/lower source pairs are verified; pale pavement pixels are excluded and both crown variants retain their native glass/metal. Existing on relation and movement stay unchanged.'
        elif n in (43,46):why='Use the same native rounded gym-sign construction as the reviewed Dewford sign: grounded foot and slim stem, bounded face with red GYM lettering, and rounded cap. This exact two-cell source pair keeps its existing on link. Remove surrounding cream pavement from all sign surfaces; no new walk flag or generic wall is added.'
        elif n==40:why='The isolated Southern Island clearing contains a moss-covered rounded standing stone. Author a broad grounded gray rock with an irregular rounded summit and source-native olive moss patches; exclude turquoise grass/shadow from its material. Preserve its one SOLID source carrier and avoid the original pinched pedestal.'
        elif n==58:why='This source-local dense conifer crown occurs twice immediately south of the Petalburg Woods entrance, beside reviewed native conifer sections. Use the reviewed conifer upper-foliage profile, repainted with this exact native green palette. It is an unlinked solid forest mass: no trunk, stack alias or displacement of neighboring forest sections is invented. All protected Littleroot keys remain unchanged.'
        else:why='The four occurrences are confined to Route104 prototype source cells. Rebuild the visible ornamental square lamp panel as a narrow dark metal frame around a raised blue-glass center, excluding cream floor pixels and the old deep hourglass mass. Preserve prototype WALK metadata and do not place this experimental panel in ordinary maps.'
        result[n]=('changed',label,why)
    return result

def stack_proof(art):
    rows=rows_for('rustboro');result=[]
    for n,base in ((2,4),(3,4),(43,46)):
        for loc in witnesses(art,rows[n-1]):
            g=art.map_cells(art.maps[loc['map']]);assert g[loc['y']+1][loc['x']]==rows[base-1]['key'];result.append({**loc,'upper_key':rows[n-1]['key'],'base_key':rows[base-1]['key'],'base_position':[loc['x'],loc['y']+1]})
    assert len(result)==18;return result

def assemblies(art):
    rows=rows_for('rustboro');result=[]
    for n,base,name in ((2,4,'pale_lantern'),(3,4,'shaded_lantern'),(43,46,'gym_sign')):
        result.append({'id':'emerald_rustboro_props_'+name,'kind':'stack (model as one)','note':'Exact source upper/base pair using its unchanged on link; native floor and source collisions remain unchanged.','layout':[[rows[n-1]['key']],[rows[base-1]['key']]],'occurrences':rows[n-1]['occurrences']})
    for name,mid,x,y,w,d,note in [('prototype_panel','EM_ROUTE104_PROTOTYPE',0,0,2,2,'Allfour prototype lamp-panel sources; no normal map placement is inferred.'),('southern_island_stone','EM_SOUTHERN_ISLAND_INTERIOR',12,10,3,3,'Actual isolated island clearing and its one moss-covered standing stone.'),('woods_entrance_foliage','EM_ROUTE104',9,30,4,4,'Actual forest gateway context; two source-local crown masses sit south of warp cells beside reviewed native conifer sections, with their own source identities preserved.')]:
        g=art.map_cells(art.maps[mid]);result.append({'id':'emerald_rustboro_props_'+name,'kind':'object (model as one)','note':note,'layout':[r[x:x+w] for r in g[y:y+d]],'occurrences':[{'map':mid,'x':x,'y':y,'palette':'BAKED'}]})
    return result

def render_assembly(item,art,entries,target,label):
    from tools.shape_fit.hand_check import picture,front
    layout=item['layout'];o=np.zeros((64,len(layout)*16,len(layout[0])*16),bool);c=np.zeros((*o.shape,3),np.uint8)
    for rz,row in enumerate(layout):
        for rx,k in enumerate(row):
            entry=entries.get(k);rgb=np.asarray(art.cell(k,'BAKED').convert('RGB'));ybase=0;destz=rz*16
            if entry and entry.get('on') and rz+1<len(layout) and layout[rz+1][rx] in [entry['on'],*entry.get('alsoOn',[])]:ybase=16;destz+=16
            if entry:oo,pp=decode(entry)
            else:oo=np.zeros((16,16,16),bool);oo[0]=True;pp=np.broadcast_to(np.arange(256,dtype=np.uint8).reshape(1,16,16),oo.shape)
            h=min(len(oo),64-ybase);s=(slice(ybase,ybase+h),slice(destz,destz+16),slice(rx*16,rx*16+16));o[s]|=oo[:h];cc=c[s];cc[oo[:h]]=rgb[pp[:h][oo[:h]]//16,pp[:h][oo[:h]]%16]
    picture(o,c,target/f'{label}-angled.png',4);front(o,c,target/f'{label}-front.png',5);front(np.rot90(o,1,axes=(1,2)).copy(),np.rot90(c,1,axes=(1,2)).copy(),target/f'{label}-side.png',5)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('rustboro');updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[];proof=stack_proof(art)
    for n,(_,label,reason) in decisions().items():
        r=rows[n-1];k=r['key'];source=art.cell(k,'BAKED');target=OUT/k[4:];target.mkdir(exist_ok=True);source.save(target/'source.png')
        if args.previews:render(table['cells'][k],source,target/'before');render(updates[k],source,target/'after')
        ledger.append({'key':k,'kind':'cell','label':label,'family':'rustboro-source-specific-props','decision':'changed','reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':r['tileset_membership'],'map_occurrences':witnesses(art,r),'contact_position':n,'contact_sheet':(INVENTORY/r['contact_page']).relative_to(ROOT).as_posix()},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(source.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'actual_context':f'local/model-gallery/emerald-pass-study/towns/general__rustboro/contexts/page-{(n-1)//16+1:03}.png','preserved_stack_witnesses':[q for q in proof if k in (q['upper_key'],q['base_key'])]}],'visual_status':'source/context inspected; Python/runtime candidate pending'})
    live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';current=json.loads(live.read_text());entities=assemblies(art)
    if args.previews:
        for e in entities:
            target=OUT/'assemblies'/e['id'];target.mkdir(parents=True,exist_ok=True);layout=e['layout'];art.drawing(layout,'BAKED').resize((len(layout[0])*64,len(layout)*64),Image.Resampling.NEAREST).save(target/'source.png');render_assembly(e,art,table['cells'],target,'before');render_assembly(e,art,{**current['cells'],**updates},target,'after')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('stack-pair-proof.json',proof),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'elevation_simulated':False,'metadata_changed':False,'existing_on_pairs_stacked':True})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'reviewed':8,'changed':8,'assemblies':6,'exact_stack_pairs':18}))

if __name__=='__main__':main()
