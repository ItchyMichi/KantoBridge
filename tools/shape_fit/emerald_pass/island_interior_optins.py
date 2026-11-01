"""Exact opted-in templates for exported mapType8 island exteriors; no map reclassification."""
from __future__ import annotations
import copy
import hashlib
import json
from pathlib import Path
from tools.model_gallery import EmeraldArt,building_catalog,write_json
from tools.shape_fit.emerald_pass import review

OUT=review.ROOT/'local/model-gallery/emerald-pass-study/island-interior-optins'
IDS={'emerald_object_birth_island_tree_east','emerald_object_birth_island_tree_north','emerald_object_birth_island_tree_west','emerald_object_island_ferry'}


def generate(table,art):
    old={e['id']:e for e in table['buildings'] if e['id'] in IDS}
    if set(old)!=IDS or any(e.get('interior') for e in old.values()):raise ValueError('expected four inactive island templates')
    catalog={e['id']:e for e in building_catalog(table,art) if e['id'] in IDS};patch={};evidence=[]
    for identifier,entry in old.items():
        new=copy.deepcopy(entry);new['interior']=True;patch[identifier]=new
        places=catalog[identifier]['occurrences'];assert places
        for p in places:assert art.maps[p['map']]['kind']=='indoor' and art.maps[p['map']]['mapType']==8
        evidence.append({'id':identifier,'field':'interior','before':None,'after':True,'reason':'The source export labels these exact island exteriors indoor/mapType8. The existing explicit parts template therefore needs the supported interior opt-in; preserve source kinds, collision, warp tags, geometry and source matching.','occurrences':places,'before_entry_sha256':review.entry_hash(entry),'after_entry_sha256':review.entry_hash(new),'geometry_unchanged':True})
    return patch,old,evidence


if __name__=='__main__':
    OUT.mkdir(exist_ok=True,parents=True);table=review.read(review.TABLE);art=EmeraldArt(review.ROOT/'local/minecraft/kantobridge/export/emerald');patch,old,evidence=generate(table,art)
    write_json(OUT/'updates.json',{'cells':{},'buildings':patch});write_json(OUT/'preconditions.json',old);write_json(OUT/'metadata-evidence.json',evidence)
    write_json(OUT/'source-cases.json',{'groups':[{'id':e['id'],'layout':e['layout'],'occurrences':e['occurrences']} for e in building_catalog(table,art) if e['id'] in IDS|{'emerald_object_dewford_house1'}]})
    print('Four exact template opt-ins; five building identities and six source occurrences')
