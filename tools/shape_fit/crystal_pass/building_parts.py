"""Shared helpers for Crystal building parts specs (docs/MODEL_AUTHORING_WORKFLOW.md section 5).

A spec is the fitted table's parts form: x west->east and z north->south in the building
drawing's pixels (one voxel each, eight a block), y up in voxels; later parts add or carve.
Paint rectangles are drawing pixels [x0, y0, x1, y1] (last excluded). Only dimensions and
source rectangles are emitted; the art itself stays in the user's export under local/.
"""
from __future__ import annotations

import base64
import io
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BUNDLE = ROOT / "local/minecraft/kantobridge/export/crystal"
LIVE = ROOT / "fabric-bridge/src/main/resources/kantobridge/fitted_shapes/crystal.json"


def paint(front, top=None, side=None, back=None, wrap=None):
    p = {"front": list(front), "top": list(top or front), "side": list(side or front),
         "back": list(back or side or front)}
    if wrap is not None:
        p["wrap"] = wrap
    return p


def box(x, z, y, p=None, carve=False):
    part = {"shape": "box", "x": list(x), "z": list(z), "y": list(y)}
    if carve:
        part["carve"] = True
    elif p is not None:
        part["paint"] = p
    return part


def profile(x, z, y, along, prof, p, cross=None):
    part = {"shape": "profile", "along": along, "x": list(x), "z": list(z), "y": list(y),
            "profile": [round(float(v), 2) for v in prof], "paint": p}
    if cross is not None:
        part["cross"] = [round(float(v), 2) for v in cross]
    return part


def round_part(shape, center, radius, y, p=None, carve=False):
    part = {"shape": shape, "center": list(center), "radius": radius, "y": list(y)}
    if carve:
        part["carve"] = True
    elif p is not None:
        part["paint"] = p
    return part


def swept_eave(span: int, rise: float, flat: float = 0.0, lip: float = 3.0, dip: float = 1.5, step: int = 2):
    """Heights across a roof of `span` voxels, sampled every `step`: an upturned lip at both
    eaves, a shallow dip behind it, then a straight rise to `rise` (a ridge), or to a flat
    crown `flat` voxels wide (a skirt roof ending at the storey above)."""
    n = span // step + 1
    xs = [i * step for i in range(n)]
    half = (span - flat) / 2
    out = []
    for x in xs:
        d = min(x, span - x)  # distance from the nearer eave
        if d <= step * .5:
            h = lip
        elif d <= step * 1.5:
            h = dip
        elif d >= half:
            h = rise
        else:
            t = (d - step * 1.5) / max(1e-6, half - step * 1.5)
            h = dip + (rise - dip) * t
        out.append(h)
    return out


def hip_and_gable(span: int, rise: float, hip_rise: float, hip_run: int, lip: float = 3.0, dip: float = 1.5, step: int = 2):
    """Heights along a hip-and-gable (irimoya) roof's run: the lower hip slopes from an
    upturned lip to `hip_rise` over `hip_run` voxels, then the gable face stands nearly
    vertical up to the ridge height `rise`."""
    n = span // step + 1
    out = []
    for i in range(n):
        x = i * step
        d = min(x, span - x)
        if d <= step * .5:
            h = lip
        elif d <= step * 1.5:
            h = dip
        elif d < hip_run:
            h = dip + (hip_rise - dip) * (d - step * 1.5) / max(1e-6, hip_run - step * 1.5)
        else:
            h = rise
        out.append(h)
    return out


def drawing(entry, bundle: Path = BUNDLE):
    """The building's full-colour drawing, 8 px a quarter tile, from the user's g2image pages."""
    from PIL import Image
    rows = entry["tiles"]
    out = Image.new("RGB", (len(rows[0]) * 8, len(rows) * 8))
    pages = {}
    for y, row in enumerate(rows):
        for x, q in enumerate(row):
            page, i = divmod(q, 1024)
            if page not in pages:
                raw = json.loads((bundle / "g2image" / f"g2q.{page}.json").read_text())
                pages[page] = Image.open(io.BytesIO(base64.b64decode(raw["png"]))).convert("RGB")
            sx, sy = i % 32 * 8, i // 32 * 8
            out.paste(pages[page].crop((sx, sy, sx + 8, sy + 8)), (x * 8, y * 8))
    return out


def check_spec(spec: dict, width: int, depth: int) -> list[str]:
    """Bounds problems in a spec: parts outside the drawing, paint outside it, empty ranges."""
    problems = []
    h = spec["height"]
    for i, part in enumerate(spec["parts"]):
        if "x" in part:
            (x0, x1), (z0, z1) = part["x"], part["z"]
            if not (0 <= x0 < x1 <= width and 0 <= z0 < z1 <= depth):
                problems.append(f"part {i} footprint {part['x']} {part['z']} outside {width}x{depth}")
        else:
            cx, cz = part["center"]
            r = part["radius"]
            rx, rz = (r, r) if not isinstance(r, list) else r
            if cx - rx < 0 or cx + rx > width or cz - rz < 0 or cz + rz > depth:
                problems.append(f"part {i} round footprint outside {width}x{depth}")
        y0, y1 = part["y"]
        if not (0 <= y0 < y1 <= h):
            problems.append(f"part {i} y {part['y']} outside height {h}")
        for name, rect in part.get("paint", {}).items():
            if name == "wrap":
                continue
            a, b, c, d = rect
            if not (0 <= a < c <= width and 0 <= b < d <= depth):
                problems.append(f"part {i} paint {name} {rect} outside drawing")
    return problems
