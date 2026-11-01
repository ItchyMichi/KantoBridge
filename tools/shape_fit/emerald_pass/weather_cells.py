"""Individually inspected Weather Institute source fragments and rooftop assemblies.

The complete architecture is b130. These source-cell models retain their exact
identities, with native material, deliberate shallow facade sections and complete
rooftop instruments, instead of folding room-sized facades onto random box tops.
"""
from __future__ import annotations
import argparse
import copy
import hashlib
import base64
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
from tools.shape_fit.blue_pass.common import Model,encode,render
from tools.shape_fit.emerald_pass import building_interiors as c,review,building_weather_institute as building
from tools.model_gallery import write_json

OUT=c.OUT.parent/'weather-cells'
GROUP='general__fortree'
# Contact positions were inspected individually in all source/orthographic and
# five angled pages. Five forest variants remain the town agent's ownership.
ROLES={
    'cliff': [73,74,75,76,77,82,109,110],
    'roof_grid':[78], 'roof_left':[79,86], 'roof_right':[115,120],
    'roof_fascia':[83,84,85],
    'instrument_right_0_0':[80], 'instrument_right_1_0':[81],
    'instrument_right_0_1':[87], 'instrument_right_1_1':[88],
    'instrument_left_0_0':[113], 'instrument_left_1_0':[114],
    'instrument_left_0_1':[118], 'instrument_left_1_1':[119],
    'facade':[90,91,92,96,97,98,102,103],
    'masonry':[93,99,104,107,123,126,129,131],
    'glass':[94,100,105,122,125,128],
    'edge_right':[112,117,121,124], 'edge_left':[89,95,101,106],
}
LABELS={
73:'rear roof ridge left with cliff backdrop',74:'rear roof ridge right with cliff backdrop',75:'rear ridge joint with cliff backdrop',
76:'east radome northwest cliff overlap',77:'east radome northeast cliff overlap',78:'central four-vent roof panel',
79:'east radome left vent border',80:'east white radome left half',81:'east white radome right half',82:'east roof cliff return',
83:'roof fascia over left pilaster',84:'roof fascia over right pilaster',85:'plain central roof fascia',86:'east instrument vent border',
87:'east inner small instrument',88:'east outer small instrument',89:'east roof edge over grass',90:'upper left window and pilaster',
91:'upper right window and pilaster',92:'upper middle window',93:'east upper diagonal masonry return',94:'east upper curved glazing',
95:'east upper glass edge over grass',96:'central door left jamb',97:'central door right jamb',98:'entrance light transom',
99:'east lower diagonal masonry',100:'east middle glass course',101:'east middle glazing edge',102:'lower left window and pilaster',
103:'lower right window and pilaster',104:'east lower masonry return',105:'east bottom glass course',106:'east lower glass edge over grass',
107:'east wall foot above grass',109:'west radome northwest cliff overlap',110:'west radome northeast cliff overlap',
112:'west roof edge over grass',113:'west white radome left half',114:'west white radome right half',115:'west radome right vent border',
117:'west instrument roof edge over grass',118:'west outer small instrument',119:'west inner small instrument',120:'west instrument vent border',
121:'west upper glass edge over grass',122:'west upper curved glazing',123:'west upper diagonal masonry return',124:'west middle glass edge',
125:'west middle glass course',126:'west lower diagonal masonry',128:'west bottom glass course',129:'west lower masonry return',131:'west wall foot above grass',
}


def native(sprite,target,cliff=False):
    rgb=np.asarray(sprite.convert('RGB')).astype(float).reshape(-1,3)
    eligible=~((rgb[:,1]>rgb[:,0]+10)&(rgb[:,1]>rgb[:,2]+10))
    if cliff:eligible&=(rgb[:,0]>rgb[:,1]+10)&(rgb[:,2]>rgb[:,1]-10)&(rgb[:,0]<200)
    if not eligible.any():raise ValueError('No inspected native material for '+str(target))
    distance=((rgb-target)**2).sum(1);distance[~eligible]=np.inf
    return int(distance.argmin())


def hood(m,x,z):
    m.box((x,x+6),(2,5),(z,z+4),1)
    m.box((x+1,x+5),(2,4),(z+2,z+5),cut=True)
    m.box((x+1,x+5),(2,4),(z+1,z+2),2)
    m.box((x,x+6),(4,5),(z,z+4),0)


def instruments(side):
    """One roof platform, radome and two instruments, then four source slices."""
    m=Model(width=32,depth=32,height=32)
    m.cylinder((16,16),16,(0,2),0)
    m.cylinder((16,8),(13,8),(2,4),1)
    yy,zz,xx=np.indices(m.occ.shape)
    dome=((xx+.5-16)/12)**2+((zz+.5-8)/8)**2+((yy+.5-4)/16)**2<=1
    m.put(dome&(yy>=4),3)
    centers=[(8,24,6),(24,25,4)] if side=='left' else [(8,25,4),(24,24,6)]
    for x,z,r in centers:
        m.cylinder((x,z),r+1,(2,3),2)
        dome=((xx+.5-x)/r)**2+((zz+.5-z)/r)**2+((yy+.5-3)/r)**2<=1
        m.put(dome&(yy>=3),4)
    return m


def model(role,sprite):
    m=Model();colors=[native(sprite,rgb) for rgb in [(230,214,173),(189,165,132),(66,74,107),(255,255,255),(247,239,189),(123,197,222)]]
    if role=='cliff':
        # The white domes/vent roofs in these rear cells are projection from b130.
        # Keep only a grounded rock return at the actual north cliff boundary.
        m.box((0,16),(0,16),(0,5),0).box((0,16),(0,5),(5,10),1)
        m.box((0,4),(5,9),(5,8),2).box((11,16),(5,7),(5,8),1)
        colors=[native(sprite,rgb,True) for rgb in [(123,90,99),(148,115,115),(99,66,82)]]
    elif role.startswith('roof_') and role!='roof_fascia':
        m.box((0,16),(0,2),(0,16),0)
        xs=(1,9) if role=='roof_grid' else (1,) if role=='roof_left' else (9,)
        for x in xs:
            for z in (1,9):hood(m,x,z)
    elif role.startswith('instrument_'):
        _,side,x,z=role.split('_');x,z=int(x)*16,int(z)*16;full=instruments(side)
        m.occ=full.occ[:,z:z+16,x:x+16].copy();m.paint=full.paint[:,z:z+16,x:x+16].copy()
    elif role=='glass':
        yy,zz,xx=np.indices(m.occ.shape)
        front=15-2*((xx+.5-8)/8)**2
        m.put((zz+.5<=front)&(zz+.5>front-3),5)
        m.paint[m.occ&((yy<2)|(yy>=14))]=1
    else:
        left,right=(8,16) if role=='edge_right' else (0,8) if role=='edge_left' else (0,16)
        m.box((left,right),(0,16),(11,15),1 if role=='masonry' else 2)
        m.box((left,right),(14,16),(10,16),0)
        m.box((left,right),(0,2),(10,16),1)
        if role=='roof_fascia':
            for x in (2,10):m.box((x,x+4),(10,13),(13,16),cut=True)
    paint=np.asarray(colors,np.uint8)[m.paint]
    if role in ('facade','masonry','glass','edge_left','edge_right','roof_fascia'):
        yy,zz,xx=np.indices(m.occ.shape);neighbor=np.roll(m.occ,-1,axis=1);neighbor[:,-1]=False
        front=m.occ&~neighbor
        source=((15-yy)*16+xx).astype(np.uint8)
        rgb=np.asarray(sprite.convert('RGB')).reshape(-1,3).astype(int)[source]
        grass=(rgb[...,1]>rgb[...,0]+10)&(rgb[...,1]>rgb[...,2]+10)
        paint[front&~grass]=source[front&~grass]
    return m.occ,paint


def generate(table,art):
    inventory=review.read(c.OUT.parent/'inventory'/GROUP/'entries.json')
    owned=review.read(c.OUT.parent/'towns-fortree/weather-ownership.json')['keys']
    roles={n:r for r,nums in ROLES.items() for n in nums}
    assert set(roles)==set(LABELS)=={r['position'] for r in owned}
    patch={};rows=[]
    for item in owned:
        n,key=item['position'],item['key'];old=table['cells'][key];record=inventory[n-1]
        assert key==record['key'] and review.entry_hash(old)==record['baseline_entry_sha256']
        role=roles[n];occ,paint=model(role,art.cell(key,'BAKED'))
        patch[key]=encode(occ,paint,old,source='hand-emerald-weather-fragments',trim=True)
        reason={
            'cliff':'The source mixes the rear cliff with projected roof/radome pixels. Keep a grounded shallow rock return using only native cliff browns; b130 supplies the complete supported rooftop geometry inside its footprint.',
            'roof_grid':'Replace the repeated vent drawing wrapped around a block with a low roof panel carrying four real recessed hood openings and clean native cream sides.',
            'roof_left':'The two source vents lie to the left of the rounded instrument border. Keep them as two open hoods on a low roof panel, excluding the old repeated diagonal side paint.',
            'roof_right':'The two source vents lie to the right of the rounded instrument border. Author the two open hoods on that side of the low roof panel.',
            'roof_fascia':'Replace the tall textured box with a shallow architectural fascia, two actual inset vent openings and a projecting cream coping; clean side material replaces wrapped facade details.',
            'facade':'Keep the source window/pilaster or entrance light on the front of a shallow wall panel, with a separate top coping and lower sill. Side and top surfaces use named native materials instead of duplicated window bands.',
            'masonry':'This diagonal stripe is the source perspective of the wing return. Put its masonry course on a shallow grounded wall section with plain native side/top material; grass at the drawn wall foot is excluded.',
            'glass':'Give the glazed wing fragment a shallow convex window panel with separate top and lower bands. Native window paint remains on the front, with clean cyan sides rather than scrambled facade pixels.',
            'edge_left':'Only the west half contains the building edge; preserve that partial width in a shallow framed panel and exclude the east grass background from all occupied surfaces.',
            'edge_right':'Only the east half contains the building edge; preserve that partial width in a shallow framed panel and exclude the west grass background from all occupied surfaces.',
        }.get(role,'This slice belongs to one complete two-by-two rooftop instrument platform: a white rounded radome and two smaller native-tan domes. Geometry is authored together before slicing, replacing disconnected textured boxes.')
        rows.append(dict(key=key,kind='cell',group=GROUP,source_ordinal=n,label='Weather Institute '+LABELS[n],family='weather-architecture-fragment',recipe=role,decision='changed',baseline_entry_sha256=review.entry_hash(old),reason=reason,occurrences=art.locations[key],evidence=[f'../inventory/{GROUP}/page-{(n-1)//32+1:03}.png',f'original-angles-{next(i for i,r in enumerate(owned) if r["key"]==key)//12+1:02}.png','../towns-fortree/weather-institute-context.png'],visual_status='Source/orthographic/baseline angled inspected; corrected geometry and Minecraft pending'))
    return patch,rows


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args()
    art=c.EmeraldArt(c.BUNDLE);table=review.read(review.BASELINE);patch,rows=generate(table,art)
    args.out.mkdir(parents=True,exist_ok=True);current=copy.deepcopy(table);current['cells'].update(patch)
    for row in rows:render(patch[row['key']],art.cell(row['key'],'BAKED'),args.out/'models'/str(row['source_ordinal']))
    proof={}
    for row in rows:
        key=row['key'];places=[]
        for place in art.locations[key]:
            m=art.maps[place['map']];raw=base64.b64decode(m['cells']);places.append(dict(place,collision=raw[(place['y']*m['w']+place['x'])*4+2],map_cells_sha256=hashlib.sha256(raw).hexdigest()))
        proof[key]=dict(source_pixel_sha256=hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest(),occurrences=places)
    entities=[];specs=[];folder=args.out/'assemblies';folder.mkdir(exist_ok=True)
    for name,rect in [('west_rooftop_instruments',[2,27,2,2]),('east_rooftop_instruments',[9,27,2,2]),('central_roof_vents',[5,27,3,2])]:
        x,y,w,h=rect;grid=art.map_cells(art.maps['EM_ROUTE119']);layout=[r[x:x+w] for r in grid[y:y+h]]
        spec=dict(id='emerald_weather_'+name,kind='scenery',map='EM_ROUTE119',rect=rect,source_layout=layout,note='Actual Weather Institute roof source adjacency. Complete building geometry is separately registered as b130.')
        specs.append(spec);entities.append(c.assembly_evidence(spec,current,art,folder))
    for page in range((len(rows)+11)//12):
        sheet=Image.new('RGB',(1440,1200),'#292d33');d=ImageDraw.Draw(sheet)
        for i,row in enumerate(rows[page*12:page*12+12]):
            x,y=i%3*480,i//3*300;d.text((x+5,y+5),str(row['source_ordinal'])+' '+row['label'],fill='white')
            sprite=art.cell(row['key'],'BAKED').convert('RGB').resize((128,128),Image.Resampling.NEAREST);sheet.paste(sprite,(x+4,y+60))
            view=Image.open(args.out/'models'/str(row['source_ordinal'])/'angled.png').convert('RGB');view.thumbnail((330,265));sheet.paste(view,(x+142,y+28))
        sheet.save(args.out/f'current-angles-{page+1:02}.png')
    for name,value in [('updates',dict(cells=patch,buildings={})),('ledger',rows),('source-evidence',proof),('entities',entities),('assemblies',specs)]:write_json(args.out/(name+'.json'),value)
    print(f'{len(rows)} individually assigned fragments; {len(entities)} actual-map roof assemblies')


if __name__=='__main__':main()
