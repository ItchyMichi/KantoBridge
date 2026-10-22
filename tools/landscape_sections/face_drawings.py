#!/usr/bin/env python3
"""Every solid drawing the levels solver could read as a cliff's face on a game's outdoor maps, with sheets to look at them
(docs/specs/LEVELS_PIPELINE.md, step 4: the game's faces table, floor_kinds/<game>.json "faces").

    python face_drawings.py <survey.json> <art dir> <out dir> [--per-sheet 24]

<survey.json>: the shapes survey of the game's maps (tools/shape_fit: per map "w", "h", "outdoor", per cell "kind" ('W'
walked or water, 'S' solid, 'L' ledge), "why" ('water' for water) and "cell", an index into "keys", each cell's drawing
keyed as the fitted shapes are); <art dir>/<map>_art.png: each map's art, 48 px a cell. RaisedRegions reads a run of 1 to 3
solid cells straight north of a ground cell, with ground beyond it, as a face. Writes <out>/<game>_face_keys.json (each
drawing in such a run: index, key, cells, an example [map, x, y]) and <out>/<game>_faces_NN.png: each drawing boxed in a
5 x 5 cell window of its example map, its index, cells and map under it. A person (or an agent) marks the indexes that are
a cliff's face (a rim, a band of rock); every other one (a house, a fence, a post, a tree) holds no ground up. The sheets
show the user's game: keep them in kb-hub's work folder. Needs Pillow (kb-pyimg).
"""
import json
import os
import sys
from collections import Counter

CELL = 48


def face_drawings(survey):
    """[{i, key, n, ex}] most cells first: the drawings in a face run on the survey's outdoor maps."""
    keys = survey["keys"]
    seen, example = Counter(), {}
    for m in survey["maps"]:
        if not m.get("outdoor"):
            continue
        w, h, kind, why, cell = m["w"], m["h"], m["kind"], m["why"], m["cell"]

        def ground(c):
            return kind[c] in "WL" and why[c] != "water"

        for cy in range(h):
            for cx in range(w):
                if not ground(cy * w + cx):
                    continue
                y, run = cy - 1, []
                while y >= 0 and kind[y * w + cx] == "S" and len(run) <= 3:
                    run.append(y * w + cx)
                    y -= 1
                if not run or len(run) > 3 or y < 0 or not ground(y * w + cx):
                    continue
                for c in run:
                    k = keys[cell[c]]
                    seen[k] += 1
                    example.setdefault(k, [m["map"], c % w, c // w])
    return [{"i": i, "key": k, "n": n, "ex": example[k]} for i, (k, n) in enumerate(seen.most_common())]


def sheets(rows, art_dir, out_prefix, per_sheet=24, cols=6, size=160, window=5):
    """Contact sheets of the drawings, each in its example map's window; the paths written."""
    from PIL import Image, ImageDraw  # only the sheets need Pillow (kb-pyimg), not the list

    arts, paths = {}, []
    for s in range(0, len(rows), per_sheet):
        chunk = rows[s:s + per_sheet]
        sheet = Image.new("RGB", (cols * (size + 6), ((len(chunk) + cols - 1) // cols) * (size + 18)), (255, 255, 255))
        draw = ImageDraw.Draw(sheet)
        for j, r in enumerate(chunk):
            name, cx, cy = r["ex"]
            if name not in arts:
                arts[name] = Image.open(os.path.join(art_dir, f"{name}_art.png")).convert("RGB")
            art, half = arts[name], window // 2
            box = ((cx - half) * CELL, (cy - half) * CELL, (cx + half + 1) * CELL, (cy + half + 1) * CELL)
            crop = Image.new("RGB", (CELL * window, CELL * window), (255, 0, 255))
            crop.paste(art.crop((max(0, box[0]), max(0, box[1]), min(art.width, box[2]), min(art.height, box[3]))),
                       (max(0, -box[0]), max(0, -box[1])))
            x0, y0 = (j % cols) * (size + 6), (j // cols) * (size + 18)
            sheet.paste(crop.resize((size, size), Image.LANCZOS), (x0, y0))
            k = size / window
            draw.rectangle((x0 + int(k * half), y0 + int(k * half), x0 + int(k * (half + 1)), y0 + int(k * (half + 1))),
                           outline=(255, 0, 0), width=2)
            draw.text((x0 + 2, y0 + size + 3), f"{r['i']} n{r['n']} {name[:16]}", fill=(0, 0, 0))
        path = f"{out_prefix}_{s // per_sheet:02d}.png"
        sheet.save(path)
        paths.append(path)
    return paths


def main(argv):
    survey_path, art_dir, out = argv[1], argv[2], argv[3]
    per = int(argv[argv.index("--per-sheet") + 1]) if "--per-sheet" in argv else 24
    survey = json.load(open(survey_path))
    game = survey["game"]
    rows = face_drawings(survey)
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, f"{game}_face_keys.json"), "w") as fh:
        json.dump(rows, fh, indent=0)
    paths = sheets(rows, art_dir, os.path.join(out, f"{game}_faces"), per)
    print(game, len(rows), "drawings,", sum(r["n"] for r in rows), "cells,", len(paths), "sheets")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
