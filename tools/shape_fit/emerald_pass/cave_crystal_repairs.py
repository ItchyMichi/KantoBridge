"""Supported native cave-wall crystals and the three-lobed Origin boulder."""
from __future__ import annotations
import argparse,copy,hashlib
import numpy as np
from tools.model_gallery import EmeraldArt,write_json
from tools.shape_fit.blue_pass.common import Model,encode
from tools.shape_fit.emerald_pass.coastal import ROOT,BASELINE,BUNDLE
from tools.shape_fit.emerald_pass.review import read,entry_hash
from tools.shape_fit.emerald_pass.interior_props import material
from tools.shape_fit.emerald_pass.inventory import contact_pages,compact_context_pages
from tools.shape_fit.emerald_pass.ship_beams import previews

OUT=ROOT/'local/model-gallery/emerald-pass-study/cave-crystal-repairs'
STUDY=ROOT/'local/model-gallery/emerald-pass-study/environment-repairs'
# Explicit source-inspected centers/radii in x/y-up; the rock is a separate
# continuous volume. Neither crystal colors nor source shadows cut geometry.
CRYSTALS={50:('blue',[(4.5,13,2,3),(9,8.5,3.5,3),(4,3.5,3,2.5)]),
          51:('red',[(11,12.5,3.5,3),(8.5,6,3.5,3.5)]),
          55:('red',[(9,9,3.5,3),(10,3,3.5,2.5)]),
          56:('blue',[(4,10,2.5,4),(9,10,4,3),(8,5,3,3)]),
          57:('red',[(8,13,4,2.8),(11,6,3,4),(5,6,3,3)])}
OWNED=set(CRYSTALS)|{52}

def rows():return [r for r in read(STUDY/'entries.json') if r['repair_ordinal'] in OWNED]

def stone_paint(m,sprite,boulder=False):
    yy,zz,xx=np.indices(m.occ.shape)
    uv=np.asarray([material(sprite,c,(0,0,16,16) if boulder else (0,0,3,16)) for c in ((99,74,66),(132,99,90),(173,140,132),(206,173,165))],np.uint8)
    facets=((xx//3*73856093)^(zz//3*19349663)^(yy//3*83492791))%100
    shade=np.where(facets<12,0,np.where(facets<65,1,2));shade=np.where((yy>=11)&(xx<9),3,shade)
    m.paint[:]=uv[shade]

def model(sprite,n):
    m=Model();yy,zz,xx=np.indices(m.occ.shape)
    if n==52:
        m.ellipsoid((7.5,7,7),(6.8,7.3,6));m.ellipsoid((4,3,12),(3.8,3.5,3.5));m.ellipsoid((12,3.5,11),(3.3,4,3.8));stone_paint(m,sprite,True)
    else:
        m.box((0,16),(0,16),(0,12));stone_paint(m,sprite)
        color,facets=CRYSTALS[n]
        targets=((0,0,222),(0,66,255),(107,206,255),(255,255,255)) if color=='blue' else ((173,0,0),(255,0,49),(255,107,140),(255,255,255))
        uv=np.asarray([material(sprite,c) for c in targets],np.uint8)
        for cx,cy,rx,ry in facets:
            crystal=(abs(xx+.5-cx)/rx+abs(yy+.5-cy)/ry+abs(zz+.5-12.5)/4<=1)
            m.occ|=crystal
            shade=np.clip(1+(xx<cx).astype(int)+(yy>cy).astype(int)+(zz>=14).astype(int),0,3)
            m.paint[crystal]=uv[shade][crystal]
    return m

def generate(art,base,pre):
    updates={};ledger=[]
    for r in rows():
        n=r['repair_ordinal'];k=r['key'];old=pre['cells'][k];m=model(art.cell(k,'BAKED'),n);updates[k]=encode(m.occ,m.paint,old,'hand-emerald-cave-crystal-repair')
        assert {p['collision'] for p in r['occurrences']}=={7}
        reason=('The full Cave of Origin source context identifies a free-standing three-lobed boulder. Replace the previous cliff-like extrusion with one grounded rounded main stone and two connected smaller stones, using only its native pink-brown rock material and leaving surrounding air clear.' if n==52 else
                'The actual Cave of Origin source positions show colored crystals attached to a continuous stone wall. Minecraft QA exposed broken supports or floating fragments. Author a solid grounded rock backing and separate joined faceted crystal lobes at the inspected native centers. Crystal colors paint only crystal geometry; they do not wrap around the stone or determine empty holes. Native blue/red/white facets remain local source addresses.')
        ledger.append({'key':k,'kind':'cell','decision':'changed','label':'Three-lobed Cave of Origin boulder' if n==52 else CRYSTALS[n][0].title()+' crystals on continuous cave stone',
            'family':'Cave rocks and supported crystals','baseline_entry_sha256':entry_hash(base['cells'][k]),'previous_entry_sha256':entry_hash(old),'reason':reason+' Preserve source SOLID7 collision and all entry metadata. Actual Minecraft QA remains pending.',
            'occurrences':r['occurrences'],'captured_findings':r['captured_findings'],'evidence':[str(STUDY/'crystal-source.png'),str(OUT/'source-evidence.json'),str(OUT/'preview/contexts/index.json'),str(OUT/'preview/assemblies/index.json')]})
    return updates,ledger

def assemblies():
    return [{'id':'emerald_cave_crystal_'+r['key'].split('/')[1],'kind':'object (model as one)','note':'Source cave-wall crystal cluster' if r['repair_ordinal']!=52 else 'Complete three-lobed Cave of Origin boulder',
             'layout':[[r['key']]],'placements':[{'source':[0,0],'offset':[0,0,0]}],'occurrences':r['occurrences']} for r in rows()]

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--previews',action='store_true');args=ap.parse_args();art=EmeraldArt(BUNDLE);base=read(BASELINE);pre=read(STUDY/'preconditions.json');updates,ledger=generate(art,base,pre);entities=assemblies()
    for n,v in [('updates.json',{'cells':updates,'buildings':{}}),('ledger.json',ledger),('entities.json',entities),('preconditions.json',{'cells':{k:pre['cells'][k] for k in updates}}),('source-evidence.json',{'baseline_sha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'sources':rows(),'groups':entities,'limits':'Explicit native rock/crystal geometry and local source evidence; actual Minecraft QA pending.'})]:write_json(OUT/n,v)
    if args.previews:
        cur=copy.deepcopy(base);cur['cells'].update(updates);contact_pages('current',copy.deepcopy(rows()),cur,art,OUT/'preview');compact_context_pages(rows(),art,OUT/'preview/contexts');previews(entities,cur,art,OUT/'preview/assemblies')
    print('6 explicit crystal/boulder repairs;metadata unchanged')
if __name__=='__main__':main()
