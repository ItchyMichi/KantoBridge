"""Numbered review sheets for a batch: python bbbatch.py <batch no> <first number> <out dir> <sprites root> <model.bbmodel> ...
Renders each model (bbrender.py beside this file) and puts 5 cells a sheet, each labelled #n game cell."""
import subprocess, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

FONT = ImageFont.load_default(size=22)
batch, first, out, sprites, models = int(sys.argv[1]), int(sys.argv[2]), Path(sys.argv[3]), sys.argv[4], sys.argv[5:]
tmp = out / f"r{batch:02d}"
subprocess.run([sys.executable, str(Path(__file__).with_name("bbrender.py")), str(tmp), sprites, *models], check=True, capture_output=True)
rows = []
for n, m in enumerate(models, first):
    game = Path(m).parent.name
    img = Image.open(tmp / f"{game}_{Path(m).stem}.png").convert("RGB")
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 1600, 30], fill=(0, 0, 0))
    d.text((8, 3), f"#{n}   {game}   {Path(m).stem}", fill=(255, 255, 80), font=FONT)
    rows.append(img)
for i in range(0, len(rows), 5):
    part = rows[i:i + 5]
    sheet = Image.new("RGB", (1600, 320 * len(part)), (30, 30, 30))
    for j, im in enumerate(part):
        sheet.paste(im, (0, 320 * j))
    name = out / f"batch{batch:02d}_{first + i:03d}-{first + i + len(part) - 1:03d}.png"
    sheet.save(name)
    print(name)
