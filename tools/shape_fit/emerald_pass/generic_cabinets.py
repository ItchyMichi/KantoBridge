"""Exact generic-room drawer cabinets, blue bookcases and pale sideboards."""
from __future__ import annotations
import argparse,copy,hashlib,json
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE,INVENTORY
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.inventory import contact_pages
from tools.shape_fit.emerald_pass.ship_beams import previews
from tools.shape_fit.emerald_pass.generic_soft_furnishings import occurrences

OUT=ROOT/'local/model-gallery/emerald-pass-study/generic-cabinets';GROUP='building__generic_building'
SMALL={47:53,48:54,88:92,89:93,180:186,181:187,227:233,228:234,263:267}
GREEN={48,54,89,93,181,187,228,234}
WIDE=[[[65,66],[72,73]],[[101,102],[109,110]],[[243,244],[251,252]],[[264,265],[268,269]]]
BOOKS=[[[55,56],foot] for foot in ([62,63],[99,100],[120,121],[219,220])]
PALE=[[[182,183],[188,189]]]
PATTERNS=[('green' if upper in GREEN else 'small',[[upper],[lower]]) for upper,lower in SMALL.items()]+[('wide',a) for a in WIDE]+[('books',a) for a in BOOKS]+[('pale',a) for a in PALE]
OWNED={n for _,a in PATTERNS for row in a for n in row}
WALK={n for _,a in PATTERNS for n in a[1]}-{268,269}


def rows():return [dict(r,source_ordinal=n) for n,r in enumerate(read(INVENTORY/GROUP/'entries.json'),1) if n in OWNED]


def cabinet(sprite,n):
    family,layout=next((f,a) for f,a in PATTERNS if any(n in row for row in a));w=len(layout[0])*16;m=Model(width=w,depth=32)
    # Common physical volume spans the actual northern SOLID position and its
    # shallow southern front projection. The lower source is floor depth, not
    # another vertical carrier beneath the body.
    if family=='books':
        m.box((1,w-1),(2,16),(4,6),0)
        for x in (1,w-3):m.box((x,x+2),(0,16),(4,23),1)
        for y in (2,8,14):m.box((1,w-1),(y,y+2),(4,23),1)
        m.box((1,w-1),(15,16),(4,16),4)
        for y in (4,10):
            for j,x in enumerate(range(4,w-4,3)):
                h=3+(j%2);m.box((x,x+2),(y,y+h),(17,22),2 if j%3 else 3)
    elif family=='green':
        m.box((1,w-1),(2,16),(4,22),1)
        m.box((3,w-3),(9,14),(17,23),cut=True)
        m.box((3,w-3),(9,14),(16,17),0)
        for j,x in enumerate(range(4,w-3,3)):m.box((x,x+2),(9,13+(j%2)),(19,23),2)
        m.box((3,w-3),(3,7),(22,23),2).box((6,w-6),(5,6),(23,24),0)
        m.box((1,w-1),(15,16),(4,14),4)
    elif family in ('small','wide'):
        m.box((1,w-1),(2,16),(4,22),1)
        if family=='small':
            for y in (3,9):
                m.box((3,w-3),(y,y+4),(22,23),2).box((7,w-7),(y+2,y+3),(23,24),0)
        else:
            for y in (3,7,11):
                m.box((3,w-3),(y,y+3),(22,23),1)
                for x in (7,22):m.box((x,x+3),(y+1,y+2),(23,24),2)
                m.box((2,w-2),(y-1,y),(22,23),0)
            m.box((15,16),(11,14),(22,23),0)
    else:
        m.box((1,w-1),(2,10),(4,22),2).box((1,w-1),(10,12),(4,23),2)
        m.box((1,w-1),(11,12),(4,16),4)
        m.box((3,15),(4,9),(22,23),1).box((6,11),(6,7),(23,24),0)
        m.box((17,w-3),(3,9),(22,23),0)
        for x in (18,22,26):
            m.box((x,x+2),(4,9),(22,24),2).box((x,x+2),(6,7),(23,24),3)
    for x in (1,w-3):
        for z in (5,20):m.box((x,x+2),(0,3),(z,z+2),0)
    z,x=next((z,x) for z,row in enumerate(layout) for x,v in enumerate(row) if v==n)
    swatch=(0,0,16,5 if family=='pale' else 7) if z else (0,0,16,16)
    colors=[(65,70,100),(105,165,145) if family=='books' else (150,185,75) if family=='green' else (175,150,65),
            (156,148,16) if family=='books' else (200,185,75) if family in ('green','wide') else (195,195,170),
            (255,206,140) if family=='pale' else (181,181,165),
            (210,245,245) if family=='books' else (165,206,107) if family=='green' else (250,250,240)]
    if family=='pale':colors[1]=(140,140,140)
    native=np.asarray([material(sprite,c,swatch) for c in colors],np.uint8)
    if family in ('green','books'):
        native[1]=material(sprite,(107,140,49) if family=='green' else (99,156,140),swatch,family='green')
    s=(slice(None),slice(z*16,z*16+16),slice(x*16,x*16+16))
    return m.occ[s],native[m.paint[s]],family


def generate(baseline,art,owned):
    at={r['source_ordinal']:r['key'] for r in owned};updates={};ledger=[];meta=[]
    labels={'small':'Two-drawer bedside cabinet','green':'Green open-shelf and drawer cabinet','wide':'Wide three-drawer cabinet','books':'Two-tier blue bookcase','pale':'Pale low storage cabinet with white top'}
    for r in owned:
        n=r['source_ordinal'];key=r['key'];old=baseline['cells'][key];sprite=art.cell(key,'BAKED');o,p,family=cabinet(sprite,n)
        new=encode(o,p,old,'hand-emerald-generic-cabinets')
        if n in WALK:new['walk']=True
        if n in SMALL:
            assert old.get('on')==at[SMALL[n]];del new['on']
        reason='The whole source shows '+labels[family].lower()+'. Build one grounded floor-depth cabinet before slicing: connected sides/top, framed drawers or open shelves and native book/handle details. The northern source is SOLID; the southern front/feet projection is WALK except the exact two Fortree lower drawers268/269, which remain SOLID. Enable only the evidenced WALK projection pieces as noncolliding decor. Nine small-body on links incorrectly point to those WALK front projections; remove only those exact links so enabling the fronts cannot turn them into invalid vertical stacks. Exclude striped floor and orange wall pixels from the cabinet materials.'
        for field in ('walk','on'):
            if old.get(field)!=new.get(field):meta.append({'key':key,'field':field,'old_value':old.get(field),'new_value':new.get(field),
                'baseline_entry_sha256':entry_hash(old),'proposed_entry_sha256':entry_hash(new),'source_pixel_mode':'RGBA',
                'source_pixel_sha256':hashlib.sha256(sprite.tobytes()).hexdigest(),'occurrences':occurrences(art,key),'reason':reason})
        updates[key]=new
        ledger.append({'key':key,'kind':'cell','label':labels[family]+' '+('projected front/foot' if n in {q for _,a in PATTERNS for q in a[1]} else 'body'),
            'family':'Generic storage furniture','decision':'changed','reason':reason,'baseline_entry_sha256':entry_hash(old),
            'occurrences':occurrences(art,key),'evidence':[str(OUT/'source-evidence.json'),str(OUT/'preview/assemblies/index.json')]})
    return updates,ledger,meta


def assemblies(art,owned,baseline):
    at={r['source_ordinal']:r['key'] for r in owned};entities=[]
    for family,a in PATTERNS:
        layout=[[at[n] for n in row] for row in a];places=[]
        for p in art.locations[layout[0][0]]:
            g=art.map_cells(art.maps[p['map']]);x,y=p['x'],p['y']
            if [row[x:x+len(a[0])] for row in g[y:y+2]]==layout:places.append(p)
        assert places,(family,a)
        now=[{'source':[x,z],'offset':[x,0,z]} for z,row in enumerate(layout) for x,k in enumerate(row)]
        before=[p for p in now if a[p['source'][1]][p['source'][0]] not in WALK]
        old=[[k if a[z][x] not in WALK else None for x,k in enumerate(row)] for z,row in enumerate(layout)]
        ident='emerald_generic_cabinet_'+hashlib.sha256(json.dumps(layout,separators=(',',':')).encode()).hexdigest()[:12]
        entities.append({'id':ident,'kind':'object (model as one)','note':'Complete source '+family+' cabinet with projected front/feet',
            'layout':layout,'placements':now,'baseline_layout':old,'baseline_placements':before,'occurrences':places})
    return entities


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args()
    art=EmeraldArt(BUNDLE);baseline=read(BASELINE);owned=rows();updates,ledger,meta=generate(baseline,art,owned);entities=assemblies(art,owned,baseline)
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('metadata-evidence.json',meta),('entities.json',entities),
        ('source-evidence.json',{'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'sources':[{'key':r['key'],'ordinal':r['source_ordinal'],'occurrences':occurrences(art,r['key'])} for r in owned],
            'limits':'Actual source runtime fixture and Minecraft QA pending; local geometry alone does not establish runtime visibility.'})]:write_json(OUT/name,value)
    if args.previews:
        current=read(ROOT/'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json');current['cells'].update(updates)
        contact_pages('current',copy.deepcopy(owned),current,art,OUT/'preview');previews(entities,current,art,OUT/'preview/assemblies')
    print(len(updates),'changed source cells;',len(meta),'exact metadata amendments;',len(entities),'complete source cabinets')


if __name__=='__main__':main()
