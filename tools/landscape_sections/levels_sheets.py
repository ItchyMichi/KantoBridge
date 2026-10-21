#!/usr/bin/env python3
"""Phone sheets of the picture spots (docs/specs/LEVELS_PIPELINE.md, step 8): per spot, stacked, the game's own picture
and the same view in one or more builds.

    python levels_sheets.py <out dir> <game shots dir> "<label>=<shots dir>" ...

Each shots dir holds <game>/<spot>_game.png and <game>/<spot>_mc.png (tools/e2e/context_shots.py); the first gives the
game's picture, each labelled one a build's. The levels pilot's: "Models, flat ground (before)=shots_models" "Models on
the levels (new)=shots_levels". Writes <out dir>/<spot>.jpg. Private output: the pictures show the user's own games.
Needs Pillow (kb-pyimg:1).
"""
import os
import sys

from PIL import Image, ImageDraw

W, H, BAR = 720, 405, 26


def panel(path, label):
    img = Image.new("RGB", (W, H + BAR), (60, 60, 60))
    if path and os.path.exists(path):
        pic = Image.open(path).convert("RGB")
        pic.thumbnail((W, H))
        img.paste(pic, ((W - pic.width) // 2, BAR + (H - pic.height) // 2))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, BAR - 1], fill=(255, 255, 255))
    d.text((8, 7), label, fill=(0, 0, 0))
    return img


def main(argv):
    out, game_dir = argv[1], argv[2]
    builds = [a.split("=", 1) for a in argv[3:]]
    os.makedirs(out, exist_ok=True)
    for game in sorted(os.listdir(game_dir)):
        for f in sorted(os.listdir(os.path.join(game_dir, game))):
            if not f.endswith("_game.png"):
                continue
            spot = f[:-len("_game.png")]
            rows = [(os.path.join(game_dir, game, f), "The game")]
            rows += [(os.path.join(d, game, spot + "_mc.png"), label) for label, d in builds]
            sheet = Image.new("RGB", (W, len(rows) * (H + BAR + 4)), (255, 255, 255))
            for k, (path, label) in enumerate(rows):
                sheet.paste(panel(path, label), (0, k * (H + BAR + 4)))
            sheet.save(os.path.join(out, spot + ".jpg"), quality=85)
            print(spot, ["ok" if os.path.exists(p) else "missing" for p, _ in rows])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
