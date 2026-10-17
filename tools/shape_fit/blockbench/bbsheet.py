"""Compact review sheets for many cells: python bbsheet.py <batch no> <first number> <out dir> <sprites root> <model.bbmodel> ...
Renders each model (bbrender.py beside this file) and puts 12 cells a sheet, two a row (KB_SHEET_PER, KB_SHEET_COLS,
KB_SHEET_TILE change that), each as the whole sprite, the front
and the three-quarter view, labelled #n game cell."""
import subprocess, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

import os
T = int(os.environ.get("KB_SHEET_TILE", "210"))
COLS = int(os.environ.get("KB_SHEET_COLS", "2"))
PER = int(os.environ.get("KB_SHEET_PER", "12"))
FONT = ImageFont.load_default(size=max(12, T // 12))
batch, first, out, sprites, models = int(sys.argv[1]), int(sys.argv[2]), Path(sys.argv[3]), sys.argv[4], sys.argv[5:]
tmp = out / f"r{batch:03d}"
subprocess.run([sys.executable, str(Path(__file__).with_name("bbrender.py")), str(tmp), sprites, *models], check=True, capture_output=True)
cells = []
for n, m in enumerate(models, first):
    game, stem = Path(m).parent.name, Path(m).stem
    src = Image.open(tmp / f"{game}_{stem}.png").convert("RGB")
    cell = Image.new("RGB", (3 * T, T + 26), (25, 25, 25))
    for i, x in enumerate((0, 320, 960)):  # sprite, front, three-quarter
        cell.paste(src.crop((x, 0, x + 320, 320)).resize((T, T)), (i * T, 26))
    ImageDraw.Draw(cell).text((6, 3), f"#{n}  {game}  {stem.split('_', 1)[-1]}", fill=(255, 255, 80), font=FONT)
    cells.append(cell)
for i in range(0, len(cells), PER):
    part = cells[i:i + PER]
    rows = (len(part) + COLS - 1) // COLS
    sheet = Image.new("RGB", (COLS * (3 * T + 12), rows * (T + 30)), (10, 10, 10))
    for j, c in enumerate(part):
        sheet.paste(c, ((j % COLS) * (3 * T + 12), (j // COLS) * (T + 30)))
    name = out / f"cand{batch:03d}_{first + i:04d}-{first + i + len(part) - 1:04d}.png"
    sheet.save(name)
    print(name)
