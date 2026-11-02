"""Shared authoring helpers for the Crystal model pass (docs/MODEL_AUTHORING_WORKFLOW.md).

A family module owns an explicit set of baseline keys/buildings. It authors geometry with
blue_pass.common.Model / numpy masks, paints every voxel with a source address of that
cell's own 16x16 drawing (CellPaint.pick on exact source colours), and records a ledger row
for every owned key: changed or retained, with a semantic label and a concrete reason.

    out = Pass("players-house")          # local/model-gallery/crystal-pass-study/players-house/
    out.cell(key, occ, paint, label=..., family=..., reason=...)      # changed
    out.keep(key, label=..., family=..., reason=...)                   # retained
    out.building(bid, spec, label=..., family=..., reason=...)         # changed building
    out.entity(id, layout, note)                                       # complete multi-cell object
    out.write(owned=keys)                # updates.json, ledger.json, entities.json, compare sheets

Nothing here writes the live table; crystal_pass.review merges every module's patch.
"""
from __future__ import annotations

import copy
import functools
import hashlib
import json
import os
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

from tools.model_gallery import CrystalArt, write_json
from tools.shape_fit.blue_pass.common import Model, decode, encode  # noqa: F401  (re-exported for modules)
from tools.shape_fit.emerald_pass.claude_source_paint import (  # noqa: F401  (game-neutral: they read art.cell)
    CellPaint, assemble, previews, slice_object, stack_object)

ROOT = Path(__file__).resolve().parents[3]
BUNDLE = ROOT / "local/minecraft/kantobridge/export/crystal"
BASELINE = ROOT / "local/model-gallery/crystal-full-pass-baseline.json"
STUDY = ROOT / "local/model-gallery/crystal-pass-study"
K = lambda s: s if s.startswith("g3q/") else "g3q/" + s  # noqa: E731


@functools.lru_cache(maxsize=1)
def art() -> CrystalArt:
    return CrystalArt(BUNDLE)


@functools.lru_cache(maxsize=1)
def baseline() -> dict:
    return json.loads(BASELINE.read_text(encoding="utf-8-sig"))


def entry_hash(entry) -> str:
    return hashlib.sha256(json.dumps(entry, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def pixels(key) -> np.ndarray:
    """The cell's own 16x16 drawing as an (16, 16, 3) int array, indexed [v, u]."""
    return np.asarray(art().cell(K(key), "BAKED").convert("RGB")).astype(int)


def colours(key) -> list[tuple[int, int, int]]:
    """The cell's distinct source colours, most frequent first."""
    px = pixels(key).reshape(-1, 3)
    values, counts = np.unique(px, axis=0, return_counts=True)
    return [tuple(int(c) for c in values[i]) for i in np.argsort(-counts)]


def faces(occ):
    """Masks of voxels whose top / any side is exposed (for top-vs-side paint choices)."""
    pad = np.pad(occ, 1)
    up = occ & ~pad[2:, 1:-1, 1:-1]
    side = occ & (~pad[1:-1, 2:, 1:-1] | ~pad[1:-1, :-2, 1:-1] | ~pad[1:-1, 1:-1, 2:] | ~pad[1:-1, 1:-1, :-2])
    return up, side


def project_paint(occ, cell: CellPaint, *, top=None, side=None, front_rows=(0, 16)):
    """Default source-local paint: each voxel shows the drawing pixel at its (x, z) for the top,
    and the front art projected by height for south faces (rows front_rows mapped over the
    model's height). `top`/`side` are optional colour lists replacing those faces' texels with
    the nearest pixel of that material (so a side wall never shows the outline or floor)."""
    h = occ.shape[0]
    yy, zz, xx = np.indices(occ.shape)
    ys = np.nonzero(occ.any(axis=(1, 2)))[0]
    top_y = (ys.max() + 1) if len(ys) else h
    r0, r1 = front_rows
    v = np.clip(r0 + ((top_y - 1 - yy) * (r1 - r0)) // max(1, top_y), r0, r1 - 1)
    paint = (v * 16 + xx).astype(np.uint8)
    up, sides = faces(occ)
    paint[up] = (zz * 16 + xx)[up]
    if side is not None:
        for y, z, x in np.argwhere(sides & ~up):
            paint[y, z, x] = cell.pick(side, x + .5, z + .5)
    if top is not None:
        for y, z, x in np.argwhere(up):
            paint[y, z, x] = cell.pick(top, x + .5, z + .5)
    return paint


def render(occ, rgb, size=150, mode="angled"):
    from tools.shape_fit.hand_check import front, picture
    tmp = STUDY / f"_render-{os.getpid()}.png"  # one per process: agents render in parallel
    tmp.parent.mkdir(parents=True, exist_ok=True)
    if not occ.any():
        return Image.new("RGB", (size, size), (90, 40, 40))
    if mode == "angled":
        picture(occ, rgb, tmp, 3)
    else:
        front(occ, rgb, tmp, 3)
    im = Image.open(tmp).convert("RGB")
    im.thumbnail((size, size))
    return im


def entry_rgb(entry, key):
    occ, paint = decode(entry)
    px = pixels(key).astype(np.uint8)
    return occ, px[paint // 16, paint % 16]


class Pass:
    """One family module's outputs: patch, ledger, complete objects and review sheets."""

    def __init__(self, name: str, source: str | None = None):
        self.name = name
        self.out = STUDY / name
        self.source = source or f"hand-crystal-{name}"
        self.table = baseline()
        self.cells, self.buildings, self.rows, self.entities = {}, {}, [], []
        self.amendments = {"stacks": {}, "metadata": {}}

    def _row(self, kind, key, decision, label, family, reason, evidence=None):
        if any(r["key"] == key and r["kind"] == kind for r in self.rows):
            raise ValueError(f"{key} reviewed twice in {self.name}")
        if not (label and family and reason):
            raise ValueError(f"{key}: label, family and reason are required")
        old = self.table["cells"][key] if kind == "cell" else {b["id"]: b for b in self.table["buildings"]}[key]
        self.rows.append({"key": key, "kind": kind, "label": label, "family": family, "decision": decision,
                          "reason": reason, "baseline_entry_sha256": entry_hash(old),
                          "occurrences": art().locations.get(key, []) if kind == "cell" else [],
                          "evidence": evidence or [f"{self.name}/compare-*.png", "study/groups"]})

    def cell(self, key, occ, paint, *, label, family, reason, evidence=None):
        key = K(key)
        entry = encode(occ, paint, copy.deepcopy(self.table["cells"][key]), source=self.source, trim=True)
        self.cells[key] = entry
        self._row("cell", key, "changed", label, family, reason, evidence)

    def cell_entry(self, key, entry, *, label, family, reason, evidence=None):
        """A complete prepared entry (e.g. from slice_object), metadata already preserved."""
        key = K(key)
        self.cells[key] = entry
        self._row("cell", key, "changed", label, family, reason, evidence)

    def drop_stack(self, key, evidence):
        """Remove a false vertical `on` link from an already authored cell (a proposal: the merge
        accepts it only once the same before/after is in source_amendments.json "stacks")."""
        key = K(key)
        old = self.table["cells"][key]
        before = {k: old[k] for k in ("on", "alsoOn") if k in old}
        if not before:
            raise ValueError(f"{key} has no stack link")
        for k in before:
            self.cells[key].pop(k, None)
        witnesses = [{"map": p["map"], "x": p["x"], "y": p["y"]} for p in art().locations.get(key, [])]
        self.amendments["stacks"][key] = {"before": before, "after": {}, "evidence": evidence, "witnesses": witnesses}

    def also_on(self, key, bases, evidence):
        """Let a stack top also stand on base variants (the runtime's `alsoOn`): a proposal, accepted by the merge
        only once the same before/after is in source_amendments.json "stacks". Call after the cell is authored."""
        key = K(key)
        old = self.table["cells"][key]
        if "on" not in old:
            raise ValueError(f"{key} has no primary stack link")
        before = {k: old[k] for k in ("on", "alsoOn") if k in old}
        also = sorted(set(old.get("alsoOn", [])) | {K(b) for b in bases})
        self.cells[key]["alsoOn"] = also
        witnesses = []
        grids = {}
        for p in art().locations.get(key, []):
            g = grids.setdefault(p["map"], art().map_cells(art().maps[p["map"]]))
            below = g[p["y"] + 1][p["x"]] if p["y"] + 1 < len(g) else None
            if below in also:
                witnesses.append({"map": p["map"], "x": p["x"], "y": p["y"], "base": below})
        if not witnesses:
            raise ValueError(f"{key}: no placement stands over {bases}")
        self.amendments["stacks"][key] = {"before": before, "after": {"on": old["on"], "alsoOn": also},
                                          "evidence": evidence, "witnesses": witnesses}

    def set_metadata(self, key, fields, evidence):
        """Change an authored cell's interaction metadata (e.g. `walk`; a None value removes the field): a proposal,
        accepted by the merge only once the same before/after (every non-geometry field) is in source_amendments.json
        "metadata". Call after the cell is authored."""
        geometry = {"v", "t", "up", "from"}  # review.GEOMETRY
        key = K(key)
        old = self.table["cells"][key]
        entry = self.cells[key]
        before = {k: v for k, v in old.items() if k not in geometry}
        for k, v in fields.items():
            if v is None:
                entry.pop(k, None)
            else:
                entry[k] = v
        after = {k: v for k, v in entry.items() if k not in geometry}
        witnesses = [{"map": p["map"], "x": p["x"], "y": p["y"]} for p in art().locations.get(key, [])]
        self.amendments["metadata"][key] = {"before": before, "after": after, "evidence": evidence, "witnesses": witnesses}

    def keep_warp(self, key, *, label, family):
        """A walkable warp cell (stairs, a door, a shutter): its entry is the docs/specs/WARP_MODELS.md model (stairs up:
        four steps rising toward the wall; stairs down and doors: the drawn floor behind a doorway frame with a dark
        opening), the user's design, so it is kept unchanged."""
        self.keep(key, label=label, family=family,
                  reason="A walkable warp cell: its entry is the WARP_MODELS.md model (stairs up rise in four steps toward "
                         "the wall; stairs down and doors keep the drawn floor behind a doorway frame with a dark opening), "
                         "the user's design, so it is kept.")

    def keep(self, key, *, label, family, reason):
        key = K(key)
        self._row("cell", key, "retained", label, family, reason)

    def building(self, bid, spec, *, label, family, reason):
        old = {b["id"]: b for b in self.table["buildings"]}[bid]
        new = copy.deepcopy(old)
        for field in ("roofRows", "roof", "bands", "parts", "height"):
            new.pop(field, None)
        new.update({k: v for k, v in spec.items() if k in ("height", "parts")})
        new.setdefault("terrain", old.get("terrain", []))
        self.buildings[bid] = new
        self._row("building", bid, "changed", label, family, reason)

    def keep_building(self, bid, *, label, family, reason):
        self._row("building", bid, "retained", label, family, reason)

    def entity(self, eid, layout, note, kind="object (model as one)", **extra):
        self.entities.append({"id": eid, "kind": kind, "note": note,
                              "layout": [[K(k) if k else None for k in row] for row in layout], **extra})

    def write(self, owned=None, sheets=True, families=None):
        reviewed = {r["key"] for r in self.rows}
        if families:
            fam = json.loads((STUDY / "families.json").read_text(encoding="utf-8"))
            want = {k for f in families for k in fam[f]}
            if want != reviewed:
                raise ValueError(f"{self.name}: family keys not reviewed {sorted(want - reviewed)[:5]}, "
                                 f"reviewed outside the families {sorted(reviewed - want)[:5]}")
        if owned is not None:
            owned = {K(k) if not str(k).startswith("crystal_b") else k for k in owned}
            if owned != reviewed:
                raise ValueError(f"{self.name}: unreviewed {sorted(owned - reviewed)[:5]}, "
                                 f"unowned {sorted(reviewed - owned)[:5]}")
        write_json(self.out / "updates.json", {"cells": self.cells, "buildings": self.buildings})
        write_json(self.out / "ledger.json", self.rows)
        write_json(self.out / "entities.json", self.entities)
        write_json(self.out / "amendments.json", self.amendments)
        if sheets:
            self.sheets()
        changed = sum(r["decision"] == "changed" for r in self.rows)
        print(f"{self.name}: {changed} changed, {len(self.rows) - changed} retained, "
              f"{len(self.entities)} objects -> {self.out}")

    def building_sheets(self):
        """Per changed building: its source drawing, the parts model from the south-east and from the south."""
        import numpy as np_
        from tools.shape_fit.crystal import parts_model
        from tools.shape_fit.crystal_pass.building_parts import check_spec, drawing
        for bid, entry in self.buildings.items():
            src = drawing(entry)
            problems = check_spec(entry, src.width, src.height)
            if problems:
                raise ValueError(f"{bid}: " + "; ".join(problems))
            occ, col = parts_model.build(entry, np_.asarray(src))
            folder = self.out / "buildings"
            folder.mkdir(parents=True, exist_ok=True)
            src.resize((src.width * 4, src.height * 4), Image.Resampling.NEAREST).save(folder / f"{bid}-source.png")
            parts_model.picture(occ, col, folder / f"{bid}-view.png", s=3)
            parts_model.front_view(occ, col, folder / f"{bid}-front.png", scale=4)
            parts_model.front_view(occ.transpose(0, 2, 1)[:, :, ::-1], col.transpose(0, 2, 1, 3)[:, :, ::-1],
                                   folder / f"{bid}-east.png", scale=4)

    def sheets(self):
        """Local before/after review sheets: sprite, old model, new model (angled and front)."""
        if self.buildings:
            self.building_sheets()
        keys = [r["key"] for r in self.rows if r["kind"] == "cell"]
        cols, cw, ch = 3, 520, 170
        for page in range((len(keys) + 17) // 18):
            chunk = keys[page * 18:(page + 1) * 18]
            sheet = Image.new("RGB", (cols * cw, ((len(chunk) + cols - 1) // cols) * ch), "white")
            d = ImageDraw.Draw(sheet)
            for j, key in enumerate(chunk):
                x0, y0 = j % cols * cw, j // cols * ch
                row = next(r for r in self.rows if r["key"] == key)
                sheet.paste(art().cell(key, "BAKED").convert("RGB").resize((96, 96), Image.Resampling.NEAREST), (x0 + 4, y0 + 20))
                sheet.paste(render(*entry_rgb(self.table["cells"][key], key), size=130), (x0 + 104, y0 + 18))
                new = self.cells.get(key)
                if new is not None:
                    sheet.paste(render(*entry_rgb(new, key), size=130), (x0 + 240, y0 + 18))
                    sheet.paste(render(*entry_rgb(new, key), size=130, mode="front"), (x0 + 376, y0 + 18))
                d.text((x0 + 4, y0 + 3), f"{row['decision'][:4]} {row['label']}"[:70], fill="black")
                d.text((x0 + 4, y0 + 150), key[4:], fill=(90, 90, 90))
            sheet.save(self.out / f"compare-{page + 1}.png")
        for ent in self.entities:
            layout = ent["layout"]
            stack = ent.get("kind", "").startswith("stack")
            # A stack's layout rows run top to bottom; assemble() wants its keys bottom-up.
            order = [k for row in reversed(layout) for k in row if k] if stack else layout
            before = assemble({k: self.table["cells"][k] for row in layout for k in row if k}, order, art(), stack=stack)
            merged = {k: self.cells.get(k, self.table["cells"][k]) for row in layout for k in row if k}
            after = assemble(merged, order, art(), stack=stack)
            sheet = Image.new("RGB", (900, 300), "white")
            d = ImageDraw.Draw(sheet)
            src = object_drawing(layout)
            src.thumbnail((260, 260))
            sheet.paste(src, (4, 24))
            sheet.paste(render(*before, size=280), (300, 18))
            sheet.paste(render(*after, size=280), (600, 18))
            d.text((4, 4), f"{ent['id']}: {ent['note']}"[:120], fill="black")
            sheet.save(self.out / f"object-{ent['id']}.png")


def object_drawing(layout, scale=8):
    rows, cols = len(layout), len(layout[0])
    img = Image.new("RGB", (cols * 16, rows * 16), (40, 40, 40))
    for r, keys in enumerate(layout):
        for c, key in enumerate(keys):
            if key:
                img.paste(art().cell(K(key), "BAKED").convert("RGB"), (c * 16, r * 16))
    return img.resize((img.width * scale, img.height * scale), Image.Resampling.NEAREST)
