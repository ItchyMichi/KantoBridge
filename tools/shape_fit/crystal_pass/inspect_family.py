"""Print a family's keys with metadata, census, stack-placement matches and first placements (study helper)."""
import json
import sys
from collections import Counter

from tools.shape_fit.crystal_pass import census
from tools.shape_fit.crystal_pass.common import STUDY, art


def main():
    c = census.load()["keys"]
    a = art()
    grids = {n: a.map_cells(s) for n, s in a.maps.items()}
    for fam in sys.argv[1:]:
        m = json.load(open(STUDY / "groups" / fam / "cells.json"))
        for k, v in sorted(m.items(), key=lambda a: a[1]["index"]):
            md = {x: y for x, y in v["metadata"].items() if x not in ("class", "from")}
            st = ""
            if "on" in md:
                cc = Counter()
                bases = {md["on"], *md.get("alsoOn", [])}
                for p in a.locations.get(k, []):
                    g = grids[p["map"]]
                    below = g[p["y"] + 1][p["x"]] if p["y"] + 1 < len(g) else None
                    cc["stacked" if below in bases else f"in place over {str(below)[4:]}"] += 1
                st = dict(cc)
            pl = [(p["map"][3:], p["x"], p["y"], p["collision"]) for p in v["placements"][:2]]
            print(fam[8:], v["index"], k[4:], md, census.label(c[k]), st or "", pl)


if __name__ == "__main__":
    main()
