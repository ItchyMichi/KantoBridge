"""The exact Route108 abandoned ship exterior, preserving sea and rock cells."""
import argparse,base64,hashlib,json
import numpy as np
from PIL import Image,ImageDraw
from tools.model_gallery import EmeraldArt,building_catalog
from tools.shape_fit.emerald import parts_model
from tools.shape_fit.emerald_pass.buildings_west import box
from tools.shape_fit.emerald_pass.buildings_sootopolis import quarters
from tools.shape_fit.emerald_pass.buildings_slateport_objects import color
from tools.shape_fit.emerald_pass.towns import ROOT,BASELINE,BUNDLE,digest
OUT=ROOT/'local/model-gallery/emerald-pass-study/building-abandoned-ship'
IDENT='emerald_object_abandoned_ship_exterior'
CASE={'name':'Route108 Abandoned Ship exterior','map':'EM_ROUTE108','x':27,'y':3,'w':6,'d':4,'source_sha256':'f782e5194654dc33d744778b639b9d1c56cecb7d8e6d89a45b01093380089f51'}
TERRAIN=[[0,0],[5,0],[0,3],[1,3],[3,3],[4,3],[5,3]]

def layout_for(art):
    c=CASE;g=art.map_cells(art.maps[c['map']]);return [r[c['x']:c['x']+c['w']] for r in g[c['y']:c['y']+c['d']]]

def geometry(draw):
    olive=color(draw,(132,132,90));rim=color(draw,(189,189,156));pale=color(draw,(230,230,206));timber=color(draw,(165,156,132));dark=color(draw,(66,74,107));gray=color(draw,(115,115,123));moss=color(draw,(99,148,123));parts=[]
    # Rounded foredeck at west and blunt narrow stern at east. The hull stays
    # entirely within the twelve source SOLID body cells, above the sea level.
    controls=[(0,5),(5,11),(12,15),(80,15),(88,13),(94,8)]
    for x in range(1,94):
        r=float(np.interp(x+.5,[p[0] for p in controls],[p[1] for p in controls]));lo=max(16,int(np.ceil(32-r)));hi=min(48,int(np.floor(32+r)))
        parts.append(box((x,x+1),(lo+2,hi-2),(0,3),dark));parts.append(box((x,x+1),(lo,hi),(3,6),moss));parts.append(box((x,x+1),(lo,hi),(6,15),olive));parts.append(box((x,x+1),(lo,hi),(15,17),rim))
    # Exposed bow and stern decks have real native slats and low pale edges.
    for x0,x1 in ((5,24),(79,89)):
        parts.append(box((x0,x1),(22,41),(17,18),olive))
        for z in range(23,40,4):parts.append(box((x0,x1),(z,z+1),(18,19),timber))
        parts.append(box((x0,x1),(20,22),(17,21),rim));parts.append(box((x0,x1),(41,43),(17,21),rim))
    parts.append(box((3,6),(25,37),(17,21),pale));parts.append(box((89,92),(22,41),(17,21),rim))
    # Main raised cabin and lower starboard annex. The true boarding warp is
    # south of the main cabin, with a low flush gangway and a recessed opening.
    parts.append(box((24,63),(19,43),(17,38),olive));parts.append(box((63,79),(22,43),(17,33),olive))
    parts.append(box((33,47),(40,48),(2,20),carve=True));parts.append(box((33,47),(39,40),(2,20),dark))
    parts.append(box((32,48),(40,58),(0,1),gray));parts.append(box((32,48),(56,58),(1,2),rim))
    # Source front windows are recessed dark panes in two separate groups;
    # their support walls remain continuous beneath the frames.
    for x in (29,35,41,47):
        parts.append(box((x,x+5),(42,44),(24,31),rim));parts.append(box((x+1,x+4),(43,44),(26,30),dark));parts.append(box((x+1,x+4),(43,44),(25,26),gray))
    for x in (65,69,73):
        parts.append(box((x,x+3),(42,44),(22,28),timber));parts.append(box((x+1,x+3),(43,44),(24,27),dark))
    # Native source slats and one broken patch on the flat main cabin roof.
    # The north overhang occupies only y>=38 above the WALK projection row.
    parts.append(box((24,63),(10,43),(38,40),timber))
    for z in range(12,41,4):parts.append(box((27,60),(z,z+2),(40,41),olive))
    parts.append(box((24,27),(10,43),(40,43),rim));parts.append(box((60,63),(10,43),(40,43),rim));parts.append(box((27,60),(10,13),(40,43),rim));parts.append(box((27,60),(40,43),(40,43),rim))
    parts.append(box((47,52),(31,37),(38,43),carve=True));parts.append(box((47,52),(31,37),(37,38),dark));parts.append(box((53,56),(14,17),(40,42),pale));parts.append(box((57,60),(14,16),(40,42),pale))
    parts.append(box((63,79),(18,43),(33,35),rim))
    for x in range(65,78,3):parts.append(box((x,x+1),(19,42),(35,36),pale))
    return {'height':48,'parts':parts}

def generate(table=None,art=None):
    art=art or EmeraldArt(BUNDLE);layout=layout_for(art);draw=np.asarray(art.drawing(layout,'BAKED').convert('RGB'));assert hashlib.sha256(draw.tobytes()).hexdigest()==CASE['source_sha256'];return {IDENT:{'id':IDENT,'atlas':'g3q','tiles':quarters(layout),'terrain':TERRAIN,**geometry(draw)}}

def evidence(art,e):
    c=CASE;places=building_catalog({'game':'emerald','cells':{},'buildings':[e]},art)[0]['occurrences'];assert places==[{'map':c['map'],'x':c['x'],'y':c['y'],'palette':'BAKED'}];m=art.maps[c['map']];raw=base64.b64decode(m['cells']);cells=[]
    for dz,row in enumerate(layout_for(art)):
        for dx,k in enumerate(row):
            x,y=c['x']+dx,c['y']+dz;i=(y*m['w']+x)*4;cells.append({'key':k,'x':x,'y':y,'local':[dx,dz],'metatile':int.from_bytes(raw[i:i+2],'little'),'collision':raw[i+2],'elevation':raw[i+3],'terrain_excluded':[dx,dz] in TERRAIN})
    warps=[q for q in m['warps'] if c['x']<=q['x']<c['x']+c['w'] and c['y']<=q['y']<c['y']+c['d']];assert warps==[{'to':'EM_ABANDONED_SHIP_DECK','warp':0,'x':29,'y':6}];return [{**places[0],'width':c['w'],'depth':c['d'],'cells':cells,'warps':warps}]

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previews',action='store_true');args=parser.parse_args();art=EmeraldArt(BUNDLE);entries=generate(art=art);e=entries[IDENT];OUT.mkdir(parents=True,exist_ok=True);dest=OUT/IDENT;dest.mkdir(exist_ok=True);c=CASE;source=art.drawing(layout_for(art),'BAKED').convert('RGB');source.save(dest/'source.png');source.resize((source.width*6,source.height*6),Image.Resampling.NEAREST).save(dest/'source-enlarged.png');im=art.map_image(c['map']).crop(((c['x']-1)*16,(c['y']-1)*16,(c['x']+c['w']+1)*16,(c['y']+c['d']+1)*16));ImageDraw.Draw(im).rectangle((16,16,(c['w']+1)*16-1,(c['d']+1)*16-1),outline='red');im.resize((im.width*4,im.height*4),Image.Resampling.NEAREST).save(dest/'context.png')
    if args.previews:
        o,colors=parts_model.build(e,np.asarray(source));parts_model.picture(o,colors,dest/'view.png',5);parts_model.front_view(o,colors,dest/'front.png',6);parts_model.front_view(np.transpose(o,(0,2,1))[:,:,::-1],np.transpose(colors,(0,2,1,3))[:,:,::-1],dest/'side.png',6)
    reason='Reconstruct the exact Route108 Abandoned Ship as one grounded flaring olive hull with native moss waterline, exposed slatted bow and stern decks, raised damaged flat-roof cabin, lower pale-slatted annex, two distinct front window groups and a clear boarding warp with a thin threshold. Exclude three actual WATER cells, the unrelated northeast rock and three pure/edge sea projection WALK cells. The four claimed north WALK roof projections carry only overhead geometry at y>=38. No new collision or warp metadata; exact native terrain interfaces still require source fixture verification.'
    ledger=[{'key':IDENT,'kind':'building','label':c['name'],'family':'route108-complete-source-ship','decision':'added','baseline_absent':True,'baseline_entry_sha256':None,'proposed_entry_sha256':digest(e),'source_pixel_sha256':c['source_sha256'],'source_pixel_mode':'RGB','occurrences':evidence(art,e),'terrain_exclusions':TERRAIN,'reason':reason,'evidence':[{'source':(dest/'source.png').relative_to(ROOT).as_posix(),'context':(dest/'context.png').relative_to(ROOT).as_posix(),'source_coverage_audit':'local/model-gallery/emerald-pass-study/slateport-missing-wholes/candidate-source-footprints-v2.json'}],'visual_status':'source/context inspected; Python and runtime pending','user_approved':False}]
    for name,value in [('updates.json',{'cells':{},'buildings':entries}),('added-ledger.json',ledger)]:(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
    (dest/'spec.json').write_text(json.dumps(e,indent=2)+'\n');print(json.dumps({'new_objects':1,'parts':len(e['parts'])}))

if __name__=='__main__':main()

