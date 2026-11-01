"""Remove projected neighboring crown-tip geometry from terrain source variants.

Map context shows these cells are terrain north of complete broadleaf objects.
They retain fitted identity/interaction metadata: low terrain remains low, grass
tufts keep grass geometry, and cliff faces use the matching leaf-free cliff
section. No override is deleted and no empty fallback is introduced.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.blue_pass.common import decode, encode, Model, render
from tools.shape_fit.emerald_pass.review import read, entry_hash
from tools.shape_fit.emerald_pass.vegetation import BASELINE, BUNDLE, ROOT, LEAVES, GRASS, grass, repaint_template

OUT=ROOT/'local/model-gallery/emerald-pass-study/projected-terrain'
TUFTS=('g3q/10736-10737-10738-12305','g3q/10736-10737-12306-10739')
SURFACES={
    'g3q/10658-10659-10659-12318':'grass', 'g3q/10658-10659-12319-10658':'grass',
    'g3q/11001-11010-11010-12406':'wave water', 'g3q/11001-11010-12415-11001':'wave water',
    'g3q/10771-10771-10771-12430':'calm water', 'g3q/10771-10771-12431-10771':'calm water',
    'g3q/10728-10729-11304-19701':'sand path', 'g3q/10728-10729-19702-11304':'sand path',
}
MATCHES={
    'g3q/10879-10880-10659-12318':'g3q/10879-10880-10659-10658',
    'g3q/10879-10880-12319-10658':'g3q/10879-10880-10659-10658',
    'g3q/10724-10725-10717-12388':'g3q/10724-10725-10717-10718',
    'g3q/10724-10725-12389-10718':'g3q/10724-10725-10717-10718',
    'g3q/10664-10665-10717-12388':'g3q/10664-10665-10717-10718',
    'g3q/10664-10665-12389-10718':'g3q/10664-10665-10717-10718',
}


def surface(sprite):
    pixels=np.asarray(sprite.convert('RGB'))
    allowed=~np.any(np.all(pixels[:,:,None,:]==np.array(LEAVES)[None,None,:,:],axis=3),axis=2)
    candidates=np.argwhere(allowed)
    if not len(candidates):raise ValueError('source has no terrain material')
    uv=np.arange(256,dtype=np.uint8).reshape(16,16)
    for z,x in np.argwhere(~allowed):
        # Fill only projected foliage addresses from nearest real terrain texel.
        sz,sx=candidates[np.argmin(((candidates-[z,x])**2).sum(axis=1))]
        uv[z,x]=sz*16+sx
    model=Model(height=1)
    model.occ[:]=True;model.paint[0]=uv
    return model


def generate(table,art):
    updates={};reasons={}
    for key,kind in SURFACES.items():
        m=surface(art.cell(key,'BAKED'))
        updates[key]=m.entry(table['cells'][key],source='hand-emerald-projected-terrain')
        reasons[key]=(f'{kind.title()} surface below projected broadleaf crown',kind,
            'Reconstructed maps show a neighboring broadleaf crown tip projected into this terrain cell. Replace the false plant fragment/empty fallback with a one-voxel terrain surface; paint follows source x/z with only crown-tip pixels replaced by nearest source-native terrain texels. The actual tree remains in its solid two-by-two footprint.')
    for key in TUFTS:
        m=grass(art.cell(key,'BAKED'))
        updates[key]=m.entry(table['cells'][key],source='hand-emerald-projected-terrain')
        reasons[key]=('Tall grass beneath projected broadleaf crown','grass',
            'Map context shows ordinary tall grass with a neighboring tree tip in one corner. Reuse the explicitly modeled rooted grass cluster and source-native grass material; do not create a separate crown fragment.')
    for key,base in MATCHES.items():
        updates[key]=repaint_template(table['cells'][base],art.cell(base,'BAKED'),art.cell(key,'BAKED'),table['cells'][key])
        updates[key]['from']='hand-emerald-projected-terrain'
        kind='bank edge' if '10879-10880' in key else 'cliff face'
        reasons[key]=(f'{kind.title()} beneath projected broadleaf crown',kind,
            f'Map context identifies terrain behind the tree, not foliage support. Preserve the matching leaf-free terrain section {base} and remap its used source colors locally, removing the unrelated projected green corner. No terrain collision/interaction metadata is changed.')
    return updates,reasons


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,default=OUT);args=ap.parse_args()
    table=read(BASELINE);art=EmeraldArt(BUNDLE);updates,reasons=generate(table,art);ledger=[]
    ownership=read(ROOT/'local/model-gallery/emerald-pass-study/inventory/vegetation-ownership.json')['entries'];positions={r['key']:i for i,r in enumerate(ownership)}
    for key,entry in updates.items():
        label,family,reason=reasons[key];ledger.append({'key':key,'kind':'cell','label':label,'family':family,'decision':'changed','reason':reason,'baseline_entry_sha256':entry_hash(table['cells'][key]),'occurrences':art.locations[key],
            'evidence':[f'inventory/vegetation-context/page-{positions[key]//8+1:02d}.png','fabric-bridge/src/main/java/dev/kantobridge/builder/shape/Units.java']})
        render(entry,art.cell(key,'BAKED'),args.out/'models'/key.split('/')[1])
    write_json(args.out/'updates.json',{'cells':updates,'buildings':{}});write_json(args.out/'ledger.json',ledger)
    print(f'{len(updates)} projected-terrain cases have explicit source-ground models, preserving class/interaction metadata')


if __name__=='__main__':main()
