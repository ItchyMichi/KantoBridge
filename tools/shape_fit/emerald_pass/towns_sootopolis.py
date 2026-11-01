"""All39 Sootopolis cells: tower seams, cave enclosure, stairs and markers."""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
import numpy as np
from PIL import Image
from tools.model_gallery import EmeraldArt
from tools.shape_fit.blue_pass.common import Model, decode, render
from tools.shape_fit.emerald_pass.towns import ROOT, BASELINE, BUNDLE, INVENTORY, rows_for, texel, digest

OUT = ROOT / 'local/model-gallery/emerald-pass-study/towns-sootopolis'
GROUP = 'sootopolis'
TOWER = {
    1:'rear west roof/cliff corner', 2:'central slate roof peak', 3:'rear east roof/cliff corner',
    4:'pointed-window upper wall and roof line', 5:'west roof against straight cliff',
    6:'west lower slate roof curve', 7:'front lower slate roof band', 8:'east lower slate roof curve',
    9:'pointed-window lower wall', 10:'rounded upper west cream wall', 11:'diamond-window upper wall',
    12:'rounded upper east cream wall', 13:'doorway lintel and upper frame', 14:'east roof against cliff corner',
    15:'rounded lower west cream wall', 16:'diamond-window lower wall', 17:'rounded lower east cream wall',
    18:'open warp doorway', 22:'west upper doorway jamb', 23:'east upper doorway jamb',
    27:'west lower doorway jamb', 28:'east lower doorway jamb',
}
RETAIN = {
    19:('cave enclosure west rail above cliff','Thin cream rail follows the west edge of the Cave of Origin landing; its high crossbar joins the corner support without turning the cliff background into a wall.'),
    20:('Cave of Origin warp opening','The existing dark thin passage frame preserves the actual warp and walk flag. The upper arch and landing rails are separate neighboring source cells.'),
    21:('cave enclosure east rail above cliff','Mirrored thin cream rail closes the east side of the cave landing, preserving the native cliff face behind it.'),
    24:('cave landing west rail over pink ground','The shallow cream beam follows the west side boundary above pink rock paving; retaining its small occupied section avoids raising the ground artwork.'),
    25:('cave landing front horizontal rail','The high cream crossbar spans the cave landing and joins both side rails. It leaves the lower pink ground empty.'),
    26:('cave landing east rail over pink ground','Mirrored narrow rail retains the east boundary and matches the front horizontal crossbar.'),
    29:('west rail endpoint post','Existing grounded cream post terminates the landing boundary above the marker court; its top joins the preceding side rail.'),
    30:('east rail endpoint post','The matching grounded post closes the opposite rail endpoint at the marker court.'),
    34:('cave enclosure upper west corner','The grounded corner return and thin top beam preserve the raised cave boundary against the native cliff.'),
    35:('cave enclosure upper horizontal cliff face','Solid stone front beneath the cream top band is the actual cave cliff enclosure, not a freestanding furniture prop; existing full-depth mass matches that wall.'),
    36:('cave enclosure upper east corner','Opposite grounded return meets the cave enclosure top beam and vertical side rail without closing the central passage.'),
    37:('Cave of Origin arch crown','The dark semicircular opening beneath the cream cliff cap continues the lower warp frame; retain its actual overhead stone and open lower profile.'),
    38:('Sootopolis Mart upper blue roof strip','The narrow blue glazed roof seam belongs to the reviewed complete Mart; its shallow fallback keeps the native white cliff margin outside physical roof geometry.'),
}


def decisions():
    result = {n:('retained', 'cone tower '+label,
                 'This exact native '+label+' is part of the source-proven complete cone towers b113..b122 (or its adjacent flat annex where shared). Keep the source-specific fallback and metadata; the new whole-tower geometry supplies the continuous wall/roof instead of treating this fragment as an isolated object.')
              for n,label in TOWER.items()}
    result.update({n:('retained',label,reason) for n,(label,reason) in RETAIN.items()})
    result[25] = ('changed','Cave of Origin overhead front crossbar','Preserve the coherent existing overhead beam geometry and add only walk:true. Its three exact source positions are WALK0 beneath an overhead crossbar; without that decorative flag runtime suppresses the beam while the solid side rails and posts remain. Source collision and the passage stay unchanged.')
    result[31] = ('changed','cliff behind projected cone apex','Replace the malformed isolated pedestal/roof-tip extrusion with the full native cliff carrier. All eight source occurrences are SOLID cliff background behind newly reconstructed cones; paint uses only the own-source upper cliff pixels, never the projected roof triangle. The pure adjacent cliff has no fitted override and uses ordinary solid terrain.')
    result[32] = ('changed','left end of cream stair flight','Replace the flat slab with three actual broad stone treads, preserving the small beveled outer left corner and native top-surface bands; join adjacent middle treads at full cell width. Add the exact source-proven walk:true flag so these thirty WALK0 stairs render on decorative carriers while preserving source movement.')
    result[33] = ('changed','middle and right cream stair flight','Three full-width stone treads replace the flat slab, with identical heights to the left end so the complete stair has no gaps or height discontinuity. Add walk:true for its twenty-two exact WALK0 source continuations; source movement stays unchanged.')
    result[39] = ('changed','paired pale stone court markers','Two separately grounded stone markers with broad low feet, narrow shafts, rounded pale heads and small dark front marks replace the stretched hourglass extrusion. The actual 2x3 court places four pairs around a clear central gap.')
    assert set(result) == set(range(1,40))
    return result


def stair(sprite, left=False):
    m=Model()
    for z in range(16):
        height=12 if z<5 else 8 if z<11 else 4
        lo=2 if left and z<2 else 1 if left and z<4 else 0
        m.box((lo,16),(0,height),(z,z+1))
    y,z,x=np.indices(m.occ.shape)
    m.paint[:]=((15-y)*16+x).astype(np.uint8)
    above=np.roll(m.occ,-1,axis=0);above[-1]=False
    exposed=m.occ&~above;m.paint[exposed]=(z*16+x).astype(np.uint8)[exposed]
    return m


def markers(sprite):
    m=Model();stone=texel(sprite,(181,181,148));pale=texel(sprite,(222,222,181));dark=texel(sprite,(66,74,107));gray=texel(sprite,(132,132,140))
    for left in (1,9):
        m.box((left,left+6),(0,2),(5,12),gray)
        m.box((left+1,left+5),(2,12),(6,11),stone)
        m.ellipsoid((left+3,12,8.5),(3,4,3.5),pale)
        m.box((left+2,left+4),(7,9),(10,12),dark)
    return m


def cliff_background(sprite):
    m=Model();m.box((0,16),(0,16),(0,16))
    y,z,x=np.indices(m.occ.shape)
    # The own source's upper eight rows are unoccluded native cliff. Lower rows
    # contain a drawn roof apex, supplied by the complete tower, not the cliff.
    m.paint[:]=(((15-y)%8)*16+x).astype(np.uint8)
    m.paint[15]=(z[15]%8*16+x[15]).astype(np.uint8)
    return m


def generate(table=None,art=None):
    table=table or json.loads(BASELINE.read_text());art=art or EmeraldArt(BUNDLE);rows=rows_for(GROUP);out={}
    for n in (31,32,33,39):
        key=rows[n-1]['key'];sprite=art.cell(key,'BAKED')
        model=cliff_background(sprite) if n==31 else markers(sprite) if n==39 else stair(sprite,n==32)
        out[key]=model.entry(table['cells'][key],'hand-emerald-sootopolis-review')
        if n in (32,33):out[key]['walk']=True
    key=rows[24]['key'];out[key]={**table['cells'][key],'walk':True}
    return out


def assemblies(art):
    rows=rows_for(GROUP);grid=art.map_cells(art.maps['EM_SOOTOPOLIS_CITY']);result=[]
    stairs={}
    for location in rows[31]['occurrences']:
        x,y=location['x'],location['y'];width=1
        while x+width<len(grid[y]) and grid[y][x+width]==rows[32]['key']:width+=1
        stairs.setdefault(width,[]).append(location)
    for width,locations in sorted(stairs.items()):
        p=locations[0];result.append({'id':f'emerald_sootopolis_stair_width_{width}','kind':'object (model as one)',
            'note':'complete cream stair flight','layout':[grid[p['y']][p['x']:p['x']+width]],'occurrences':locations})
    for name,x,y,w,d in [('marker_court',30,19,3,2),('cave_enclosure',29,15,5,4)]:
        result.append({'id':'emerald_sootopolis_'+name,'kind':'object (model as one)','note':name.replace('_',' '),
            'layout':[row[x:x+w] for row in grid[y:y+d]],'occurrences':[{'map':'EM_SOOTOPOLIS_CITY','x':x,'y':y,'palette':'BAKED'}]})
        if name=='marker_court':
            result[-1]['reference_layout']=result[-1]['layout']
            result[-1]['layout']=[[row[0],None,row[2]] for row in result[-1]['layout']]
            result[-1]['note']='Four source pairs of stone markers around the two WALK0 central ground cells. The central source ground stays in reference art and is excluded from fitted-object geometry; its shared cliff key is unchanged.'
    return result


def cliff_evidence(art,table):
    row=rows_for(GROUP)[30];source=art.maps['EM_SOOTOPOLIS_CITY'];grid=art.map_cells(source);raw=base64.b64decode(source['cells']);out=[]
    for p in row['occurrences']:
        x,y=p['x'],p['y'];i=(y*source['w']+x)*4
        assert raw[i+2]==7
        out.append({**p,'collision_byte':raw[i+2],'elevation':raw[i+3],
                    'neighbors':[{**{'x':nx,'y':y,'key':grid[y][nx]},'has_fitted_override':grid[y][nx] in table['cells']} for nx in (x-1,x+1) if 0<=nx<source['w']]})
    assert len(out)==8
    return {'mixed_key':row['key'],'pure_cliff_reference':'g3q/21924-21925-21932-21933',
            'pure_reference_has_fitted_override':'g3q/21924-21925-21932-21933' in table['cells'],
            'native_cliff_source_rectangle':[0,0,16,8],'occurrences':out,'runtime_followup':'Compare the cliff/tower interface in actual Sootopolis; preserve ordinary cliff carrier, never claim the background top row in the tower.'}


def main():
    from tools.shape_fit.hand_check import picture,front
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args()
    table=json.loads(BASELINE.read_text());art=EmeraldArt(BUNDLE);rows=rows_for(GROUP);updates=generate(table,art);ledger=[];OUT.mkdir(parents=True,exist_ok=True)
    for n,(status,label,reason) in sorted(decisions().items()):
        row=rows[n-1];key=row['key'];sprite=art.cell(key,'BAKED');target=OUT/key.removeprefix('g3q/');target.mkdir(exist_ok=True);sprite.save(target/'source.png')
        evidence=[{'source_tilesets':row['tileset_membership'],'map_occurrences':row['occurrences'],
                   'contact_sheet':str((INVENTORY/row['contact_page']).relative_to(ROOT)),'contact_position':n},
                  {'source':str((target/'source.png').relative_to(ROOT)),'source_pixel_sha256':hashlib.sha256(sprite.tobytes()).hexdigest()},
                  {'context_sheet':f'local/model-gallery/emerald-pass-study/towns/general__sootopolis/contexts/page-{(n-1)//16+1:03}.png'}]
        if n in TOWER:evidence.append({'complete_tower_candidates':'local/model-gallery/emerald-pass-study/buildings-sootopolis/added-ledger.json'})
        if n==31:evidence.append({'cliff_background_proof':str((OUT/'cliff-background-proof.json').relative_to(ROOT))})
        if args.previews and key in updates:
            render(table['cells'][key],sprite,target/'before');render(updates[key],sprite,target/'after')
        ledger.append({'key':key,'kind':'cell','label':label,'family':'sootopolis-town','decision':status,'reason':reason,
                       'baseline_entry_sha256':digest(table['cells'][key]),'evidence':evidence,
                       'visual_status':'source/map/orthographic reviewed; candidate Minecraft capture pending'})
    entities=assemblies(art)
    if args.previews:
        for item in entities:
            target=OUT/'assemblies'/item['id'];target.mkdir(parents=True,exist_ok=True);layout=item['layout']
            art.drawing(item.get('reference_layout',layout),'BAKED').resize((len(layout[0])*96,len(layout)*96),Image.Resampling.NEAREST).save(target/'source.png')
            source=art.maps[item['occurrences'][0]['map']];raw=base64.b64decode(source['cells']);placement=item['occurrences'][0]
            for label,entries in [('before',table['cells']),('after',{**table['cells'],**updates})]:
                masks=[];colors=[]
                for rz,row in enumerate(layout):
                    os=[];cs=[]
                    for rx,key in enumerate(row):
                        if key is None:
                            os.append(np.zeros((16,16,16),bool));cs.append(np.zeros((16,16,16,3),np.uint8));continue
                        entry=entries.get(key,{})
                        i=((placement['y']+rz)*source['w']+placement['x']+rx)*4
                        suppressed=raw[i+2]==0 and not any(entry.get(flag) for flag in ('walk','seat','on'))
                        if entry and not suppressed:o,p=decode(entry)
                        else:
                            o=np.zeros((16,16,16),bool);p=np.zeros_like(o,np.uint8)
                        rgb=np.asarray(art.cell(key,'BAKED').convert('RGB'));os.append(o);cs.append(rgb[p//16,p%16])
                    masks.append(np.concatenate(os,axis=2));colors.append(np.concatenate(cs,axis=2))
                o=np.concatenate(masks,axis=1);c=np.concatenate(colors,axis=1)
                picture(o,c,target/f'{label}-angled.png',7);front(o,c,target/f'{label}-front.png',8)
    for name,value in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('cliff-background-proof.json',cliff_evidence(art,table))]:
        (OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    source=art.maps['EM_SOOTOPOLIS_CITY'];raw=base64.b64decode(source['cells']);corrections=[]
    for n in (25,32,33):
        row=rows[n-1];key=row['key'];occurrences=[]
        for place in row['occurrences']:
            i=(place['y']*source['w']+place['x'])*4
            assert raw[i+2]==0
            occurrences.append({**place,'collision':raw[i+2],'collision_byte':raw[i+2],'elevation':raw[i+3]})
        metadata=lambda entry:{k:v for k,v in entry.items() if k not in ('v','t','up','from')}
        corrections.append({'key':key,'baseline_entry_sha256':digest(table['cells'][key]),'before':metadata(table['cells'][key]),
                            'after':metadata(updates[key]),'occurrences':occurrences,'witnesses':occurrences,
                            'source_pixel_sha256':hashlib.sha256(art.cell(key,'BAKED').convert('RGB').tobytes()).hexdigest(),
                            'source_pixel_mode':'RGB','geometry_unchanged':n==25,'reason':decisions()[n][2]})
    (OUT/'metadata-corrections.json').write_text(json.dumps(corrections,indent=2)+'\n')
    print(json.dumps({'reviewed':39,'changed':5,'retained':34,'assemblies':len(entities)}))


if __name__=='__main__':main()
