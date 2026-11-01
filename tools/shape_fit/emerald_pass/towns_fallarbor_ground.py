"""Source-reviewed Fallarbor ash/grass ledges and Route114 bridge landings."""
from __future__ import annotations
import argparse
import hashlib
import json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model,decode,render
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,INVENTORY,rows_for,texel,digest
from tools.shape_fit.emerald_pass.towns_lilycove import witnesses,preview
from tools.shape_fit.emerald_pass.towns_fortree_ground import fitted_preview

OUT=ROOT/'local/model-gallery/emerald-pass-study/towns-fallarbor-ground'
ASH=set(range(15,20))|set(range(21,27))
GREEN=set(range(83,96))
BRIDGES={57,58,62,63}
CHANGED=ASH|GREEN|BRIDGES
OWNED=CHANGED


def decisions():
    labels={1:'ash ledge small southwest stone return',15:'ash ledge southwest foot',16:'ash ledge south straight edge',17:'ash ledge southeast foot',18:'ash ledge west straight edge',19:'ash ledge east straight edge',21:'ash ledge northwest inner corner',22:'ash ledge northeast inner corner',23:'ash ledge west outer bend',24:'ash ledge east outer bend',25:'ash ledge western sloping cap',26:'ash ledge eastern sloping cap',57:'Route114 bridge upper west landing',58:'Route114 bridge upper east landing',62:'Route114 bridge lower west landing',63:'Route114 bridge lower east landing',83:'olive ledge west edge beside dirt',84:'olive ledge northwest inner corner',85:'olive ledge northeast inner corner',86:'olive ledge west lower dirt bend',87:'olive ledge southwest foot',88:'olive ledge south straight edge',89:'olive ledge southeast foot',90:'olive ledge west straight edge',91:'olive ledge east straight edge',92:'olive ledge east outer bend',93:'olive ledge west upper dirt bend',94:'olive ledge western sloping cap',95:'olive ledge eastern sloping cap'}
    out={}
    for n in sorted(OWNED):
        if n==1:
            decision='retained';reason='The small southwest ash-cliff return already has a grounded stepped pink-stone wedge, with eight occupied height layers and no ash-colored pedestal. It closes the narrow ledge in the Route113 context; preserve this distinct short return rather than raising it to a full cliff block.'
        elif n in BRIDGES:
            decision='changed';reason='Keep the inspected four-voxel landing occupancy and source-specific edge orientation. Paint native planks across x/z on upward faces and only brown timber on the thin vertical sides. All occurrences are WALK0 and keep native bridge-floor routing/elevation without adding walk decoration or new supports.'
        else:
            decision='changed';reason='This is a shallow source ledge adjoining the same four-voxel edge family, including native directional jump collisions. Preserve every occupied voxel and movement field. Correct the top to native x/z ash/grass/stone art, and use only native pink stone' if n in ASH else 'This is a shallow source ledge adjoining the same four-voxel edge family, including native directional jump collisions. Preserve every occupied voxel and movement field. Correct the top to native x/z grass/dirt/stone art, and use only native olive stone'
            reason+=' on its thin vertical sides so ground colors do not become raised stripes.'
        out[n]=(decision,labels[n],reason)
    assert set(out)==OWNED
    return out


def surface(original,sprite,n):
    o,_=decode(original);m=Model(height=len(o));m.occ[:]=o
    colors=((99,66,82),(132,90,90),(189,148,140)) if n in ASH|BRIDGES else ((90,90,58),(115,115,74),(148,140,115))
    rgb=np.asarray(sprite.convert('RGB'));palette=[]
    for color in colors:
        hits=np.argwhere((rgb==color).all(axis=2));assert len(hits),(n,color)
        palette.append(int(hits[0,0]*16+hits[0,1]))
    y,z,x=np.indices(o.shape);m.paint[:]=np.asarray(palette,np.uint8)[np.minimum(y,2)]
    above=np.concatenate((o[1:],np.zeros_like(o[:1])),axis=0);top=o&~above
    m.paint[top]=(z*16+x).astype(np.uint8)[top]
    return m.entry(original,'hand-emerald-fallarbor-ground',trim=True)


def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for('fallarbor')
    return {rows[n-1]['key']:surface(table['cells'][rows[n-1]['key']],art.cell(rows[n-1]['key'],'BAKED'),n) for n in sorted(CHANGED)}


def assemblies(art):
    result=[]
    cases=[('ash_return','EM_ROUTE113',50,3,3,3,'Small retained ash-stone return and its actual shallow adjoining ledges.'),('ash_ledge_bends','EM_ROUTE113',51,7,6,4,'Actual ash plateau border, with pink stone and directional ledge cells.'),('bridge_north','EM_ROUTE114',19,21,4,2,'Complete native river bridge and all four exact landing ends; actual map uses bridge floors.'),('olive_ledge','EM_ROUTE114',14,36,12,2,'Whole southern olive ledge including both inside corners and the eastern return beside the house.'),('olive_dirt_bends','EM_ROUTE114',28,38,3,10,'Actual long western ledge with dirt-path upper/lower bends and its southern sloping cap.')]
    for ident,mid,x,y,w,d,note in cases:
        g=art.map_cells(art.maps[mid]);result.append({'id':'emerald_fallarbor_ground_'+ident,'kind':'object (model as one)','note':note,'layout':[r[x:x+w] for r in g[y:y+d]],'occurrences':[{'map':mid,'x':x,'y':y,'palette':'BAKED'}]})
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args()
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for('fallarbor');updates=generate(table,art);OUT.mkdir(parents=True,exist_ok=True);ledger=[]
    for n,(decision,label,reason) in decisions().items():
        row=rows[n-1];k=row['key'];sprite=art.cell(k,'BAKED');target=OUT/k[4:];target.mkdir(exist_ok=True);sprite.save(target/'source.png')
        if args.previews:render(table['cells'][k],sprite,target/'before');render(updates.get(k,table['cells'][k]),sprite,target/'after')
        ledger.append({'key':k,'kind':'cell','label':label,'family':'fallarbor-ash-olive-ledge-bridge','decision':decision,'reason':reason,'baseline_entry_sha256':digest(table['cells'][k]),'evidence':[{'source_tilesets':row['tileset_membership'],'map_occurrences':witnesses(art,row),'contact_sheet':(INVENTORY/row['contact_page']).relative_to(ROOT).as_posix(),'contact_position':n},{'source':(target/'source.png').relative_to(ROOT).as_posix(),'source_pixel_sha256':hashlib.sha256(sprite.convert('RGB').tobytes()).hexdigest(),'source_pixel_mode':'RGB'},{'context_sheet':f'local/model-gallery/emerald-pass-study/towns/general__fallarbor/contexts/page-{(n-1)//16+1:03}.png'}],'visual_status':'source/context inspected; candidate preview/runtime pending'})
    live=ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json';current=json.loads(live.read_text());entities=assemblies(art)
    if args.previews:
        for item in entities:
            target=OUT/'assemblies'/item['id'];target.mkdir(parents=True,exist_ok=True);layout=item['layout'];art.drawing(layout,'BAKED').resize((len(layout[0])*48,len(layout)*48),Image.Resampling.NEAREST).save(target/'source.png')
            fitted_preview(item,art,table['cells'],target,'before');fitted_preview(item,art,{**current['cells'],**updates},target,'after');preview(item,art,{**current['cells'],**updates},target,'runtime-floor')
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('preview-provenance.json',{'cumulative_table_sha256':hashlib.sha256(live.read_bytes()).hexdigest(),'bridge_policy':'Native WorldPlan bridge floor; no new walk flag.','elevation_simulated':False})]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'reviewed':len(OWNED),'changed':len(updates),'retained':0,'assemblies':len(entities)}))


if __name__=='__main__':main()
