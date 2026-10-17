"""Renders .bbmodel files in a running Blockbench (remote debugging on 127.0.0.1:9222; start_bb.sh): per model one sheet
PNG with the whole sprite and four views (front = south, side = east, three-quarter, top), named <game>_<cell>.png.

    python bbrender.py <out dir> <sprites root> <models/<game>/<cell>.bbmodel> ...
<sprites root>/<game>/<cell>/sprite_full.png is the unmasked drawing.
"""
import asyncio, base64, io, json, sys
from pathlib import Path
from PIL import Image, ImageDraw
from playwright.async_api import async_playwright

VIEWS = [("front", (0, 16, 100)), ("side", (100, 16, 0)), ("3/4", (62, 66, 62)), ("top", (0.5, 105, 12))]
LOAD = """async ([model, path]) => {
  if (Project) { try { Project.close(true); } catch (e) {} }
  setupProject(Formats.free);
  Codecs.project.parse(model, path);
  await new Promise(r => setTimeout(r, 400));
  return Cube.all.length;
}"""
SHOT = """async ([pos]) => {
  const p = Preview.selected;
  p.setProjectionMode(false);
  if (typeof scene !== "undefined" && scene) scene.background = new THREE.Color("#8fae8f");
  p.camera.position.set(pos[0], pos[1], pos[2]);
  p.controls.target.set(0, 16, 0);
  p.controls.update();
  p.render();
  return p.canvas.toDataURL("image/png");
}"""


async def main():
    out, sprites, models = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3:]
    out.mkdir(parents=True, exist_ok=True)
    async with async_playwright() as p:
        b = await p.chromium.connect_over_cdp("http://127.0.0.1:9222")
        page = [pg for c in b.contexts for pg in c.pages if "devtools" not in pg.url][0]
        for m in models:
            model = json.loads(Path(m).read_text())
            n = await page.evaluate(LOAD, [model, m])
            tiles = []
            for name, pos in VIEWS:
                url = await page.evaluate(SHOT, [list(pos)])
                img = Image.open(io.BytesIO(base64.b64decode(url.split(",", 1)[1]))).convert("RGB")
                s = min(img.size)
                img = img.crop(((img.width - s) // 2, (img.height - s) // 2, (img.width + s) // 2, (img.height + s) // 2)).resize((320, 320))
                ImageDraw.Draw(img).text((6, 4), name, fill=(255, 255, 0))
                tiles.append(img)
            game, stem = Path(m).parent.name, Path(m).stem
            sp = sprites / game / stem / "sprite_full.png"
            sheet = Image.new("RGB", (320 * 5, 320), (40, 40, 40))
            if sp.is_file():
                s = Image.open(sp).convert("RGBA").resize((320, 320), Image.NEAREST)
                bg = Image.new("RGBA", s.size, (90, 140, 90, 255))
                bg.alpha_composite(s)
                sheet.paste(bg.convert("RGB"), (0, 0))
            for i, t in enumerate(tiles):
                sheet.paste(t, (320 * (i + 1), 0))
            sheet.save(out / f"{game}_{stem}.png")
            print(stem, "cubes", n, flush=True)

asyncio.run(main())
