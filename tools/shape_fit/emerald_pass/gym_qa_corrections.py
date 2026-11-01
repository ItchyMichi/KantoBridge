"""Source-specific corrections found in the first actual gym comparison run."""
from __future__ import annotations
import argparse
import copy
from pathlib import Path
import numpy as np
from tools.shape_fit.emerald_pass import building_interiors as c, review
from tools.shape_fit.emerald_pass import gyms_west, mauville_gym, gyms_rooms
from tools.shape_fit.blue_pass.common import Model, encode, render
from tools.model_gallery import write_json

OUT=c.OUT.parent/'gym-qa-corrections'
STATUES=(
    'emerald_gym_rustboro_statue', 'emerald_gym_dewford_statue',
    'emerald_gym_lavaridge_statue', 'emerald_mauville_statue',
    'emerald_mossdeep_entry_statue', 'emerald_petalburg_entry_statue',
)
DAIS='emerald_gym_dewford_dais'
LEDGE='emerald_gym_lavaridge_ledge'


def statue():
    """One connected pedestal, collar, and genuinely spherical stone finial."""
    m=Model(height=32)
    m.box((0,16),(0,2),(2,14),0)
    m.box((1,15),(2,3),(2,14),1)
    m.box((2,14),(3,14),(3,13),0)
    m.box((0,16),(14,16),(1,15),1)
    m.box((1,15),(16,17),(2,14),1)
    m.cylinder((8,8),4,(17,20),1)
    m.ellipsoid((8,25,8),(6,6,6),3)
    y,z,x=np.indices(m.occ.shape)
    light=(-.45*(x+.5-8)+.65*(y+.5-25)+.6*(z+.5-8))/6
    orb=m.paint==3
    m.paint[orb&(light>.48)]=4
    m.paint[orb&(light>.87)]=5
    m.paint[orb&(light<-.36)]=2
    return m


def dewford_dais():
    """Continuous lavender arena with four small timber posts and two stairs."""
    m=Model(width=64,depth=32)
    m.box((0,64),(0,3),(0,32),0)
    m.box((0,2),(3,5),(0,32),1)
    m.box((62,64),(3,5),(0,32),1)
    m.box((0,64),(3,5),(0,2),1)
    for x in (1,60):
        for z in (1,27):m.box((x,x+3),(3,7),(z,z+4),2)
    for x in (5,47):
        m.box((x,x+12),(1,3),(30,32),cut=True)
        m.box((x,x+12),(2,3),(28,30),cut=True)
    return m


def selection():
    rows={};assemblies={}
    for module in (gyms_west,mauville_gym,gyms_rooms):
        registry=review.read(module.REGISTRY)
        rows.update({r['key']:r for r in registry['entries']})
        assemblies.update({s['id']:s for s in registry['assemblies']})
    specs=[assemblies[s] for s in (*STATUES,DAIS,LEDGE)]
    selected=[]
    for spec in specs:
        for z,line in enumerate(spec['source_layout']):
            for x,key in enumerate(line):
                if any(r['key']==key for r in selected):continue
                row=copy.deepcopy(rows[key])
                if spec['id'] in STATUES:
                    recipe=f'statue_{1-z}'
                    reason=('Replace the stepped or incomplete source extrusion with one native-gray pedestal, collar and round stone globe; source room-floor pixels are excluded. Both slices share the exact plinth seam and existing on relationship.')
                elif spec['id']==DAIS:
                    recipe=f'dais_{x}_{z}'
                    reason=('Actual complete-object capture exposed a deep trench between tall corner cells and thin center cells. This slice belongs to one continuous three-voxel lavender floor, narrow side/rear trim, small timber corner posts and shallow front steps.')
                else:
                    recipe='ledge'
                    reason=('Retain the source collision-163 ledge height of four voxels; map the native brick course onto its front face instead of the old featureless brown stripe.')
                row.update(recipe=recipe,reason=reason,assemblies=[spec['id']])
                selected.append(row)
    return selected,specs


def generate(table,art,rows=None):
    rows=rows or selection()[0]
    updates={};full_statue=statue();floor=dewford_dais()
    for row in rows:
        key=row['key'];old=table['cells'][key]
        if review.entry_hash(old)!=row['baseline_entry_sha256']:raise ValueError('Stale source entry '+key)
        sprite=art.cell(key,'BAKED');recipe=row['recipe']
        if recipe.startswith('statue_'):
            layer=int(recipe.split('_')[1]);start=16*layer
            occ=full_statue.occ[start:start+16].copy()
            semantic=full_statue.paint[start:start+16]
            palette=[c.material(sprite,rgb) for rgb in [(148,148,148),(198,198,189),(99,99,115),(123,123,99),(189,189,173),(247,247,222)]]
            paint=np.asarray(palette,np.uint8)[semantic]
            if layer==0:
                y,z,x=np.indices(occ.shape)
                front=(z==12)&(y>=3)&(y<14)&(x>=2)&(x<14)
                paint[front]=((15-y)*16+x)[front]
        elif recipe.startswith('dais_'):
            _,x,z=recipe.split('_');x,z=int(x)*16,int(z)*16
            occ=floor.occ[:,z:z+16,x:x+16].copy()
            semantic=floor.paint[:,z:z+16,x:x+16]
            palette=[c.material(sprite,rgb) for rgb in [(156,148,214),(90,90,132),(156,74,33)]]
            paint=np.asarray(palette,np.uint8)[semantic]
            yy,zz,xx=np.indices(occ.shape)
            top=occ&~np.concatenate((occ[1:],np.zeros_like(occ[:1])),axis=0)
            paint[top]=(zz*16+xx)[top]
            # The thin olive strip outside the dark perimeter is room floor,
            # not timber. Keep the authored rim in its native blue-gray trim.
            pixels=np.asarray(sprite.convert('RGB')).astype(int)
            floor_pixel=(pixels[:,:,0]==pixels[:,:,1])&(pixels[:,:,1]-pixels[:,:,2]>=20)
            paint[top&floor_pixel[zz,xx]]=palette[1]
        elif recipe=='ledge':
            m=Model();m.box((0,16),(0,4),(0,16),c.material(sprite,(115,74,57)))
            occ,paint=m.occ,m.paint
            y,z,x=np.indices(occ.shape);front=(z==15)&(y<4)
            source_y=np.where(y==0,14,(3-y)*3)
            paint[front]=(source_y*16+x)[front]
        else:raise ValueError(recipe)
        updates[key]=encode(occ,paint,old,source='hand-emerald-gym-runtime-correction',trim=True)
    return updates


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args()
    rows,specs=selection();table=review.read(review.BASELINE);art=c.EmeraldArt(c.BUNDLE)
    patch=generate(table,art,rows);current=copy.deepcopy(table);current['cells'].update(patch)
    args.out.mkdir(parents=True,exist_ok=True)
    ledger=[dict(r,kind='cell',decision='changed',occurrences=art.locations[r['key']],visual_status='Actual Minecraft defect identified; source and corrected Python geometry inspected; recapture pending',evidence=['../../emerald-batch-02/scenery-01/visual-qa-gallery-bounds.json','assemblies/'+r['assemblies'][0]+'.png']) for r in rows]
    folder=args.out/'assemblies';folder.mkdir(exist_ok=True)
    entities=[c.assembly_evidence(s,current,art,folder) for s in specs]
    for row in rows:render(patch[row['key']],art.cell(row['key'],'BAKED'),args.out/'cells'/row['key'].replace('/','_'))
    write_json(args.out/'updates.json',dict(cells=patch,buildings={}))
    write_json(args.out/'ledger.json',ledger);write_json(args.out/'entities.json',entities)
    print(f'{len(rows)} corrected cells; {len(entities)} complete source-map assemblies')


if __name__=='__main__':main()
