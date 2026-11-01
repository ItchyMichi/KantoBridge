"""Preserve explicitly inspected shared interior architecture and furniture.

The registry contains manual per-key findings. No source-tag or geometry-group
rule is used to infer a review decision.
"""
from pathlib import Path

from tools.model_gallery import EmeraldArt, write_json
from tools.shape_fit.emerald_pass.review import BASELINE, ROOT, entry_hash, read
from tools.shape_fit.emerald_pass.scenery import BUNDLE

REGISTRY = Path(__file__).with_name('interior_retained_registry.json')
OUT = ROOT / 'local/model-gallery/emerald-pass-study/interior-retained'


def main():
    table, registry, art = read(BASELINE), read(REGISTRY), EmeraldArt(BUNDLE)
    rows = []
    for key, spec in registry.items():
        if entry_hash(table['cells'][key]) != spec['baseline_entry_sha256']:
            raise ValueError('Reviewed interior changed: ' + key)
        rows.append(dict(spec, key=key, kind='cell', decision='retained',
                         occurrences=art.locations.get(key, [])))
    write_json(OUT / 'updates.json', {'cells': {}, 'buildings': {}})
    write_json(OUT / 'ledger.json', rows)
    print(f'{len(rows)} explicitly retained interior sections; runtime QA still required')


if __name__ == '__main__':
    main()
