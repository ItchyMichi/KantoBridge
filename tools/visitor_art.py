#!/usr/bin/env python3
"""The visitor stand-in's original art (FOREIGN_POKEMON_PLAN §3.1, Q3; first drawn for the FP0 spike). Pure Python
PNG writer, no PIL. Nothing ROM-derived: a drawn
silhouette (a rounded blob with two eyes and a question mark) in four greys, written at each engine's size:
  gen1: front 40x40 (frontSize 5), back 32x32, icon 16x32 (two 16x16 frames)
  gs:   front 40x40 (picSize 5), back 32x32, icon 16x32
  game3: front 64x64, back 64x64, icon 32x64 (two 32x32 frames)
"""
from __future__ import annotations

import struct
import sys
import zlib
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "gen1-bridge" / "assets" / "visitor"

WHITE, LIGHT, DARK, BLACK = (255, 255, 255, 255), (168, 168, 168, 255), (88, 88, 88, 255), (0, 0, 0, 255)


def png(path: Path, w: int, h: int, px) -> None:
    raw = b"".join(b"\x00" + b"".join(bytes(px(x, y)) for x in range(w)) for y in range(h))

    def chunk(tag: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + tag + body + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)

    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    data += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    path.write_bytes(data)


def blob(size: int, back: bool):
    cx, cy, r = size / 2, size * 0.55, size * 0.38

    def px(x, y):
        dx, dy = (x + 0.5 - cx) / r, (y + 0.5 - cy) / (r * 1.05)
        d = dx * dx + dy * dy
        if d > 1.0:
            return (0, 0, 0, 0)
        if d > 0.86:
            return BLACK
        if not back:
            # eyes and a question mark on the front
            for ex in (-0.42, 0.42):
                if (dx - ex) ** 2 + (dy + 0.25) ** 2 < 0.045:
                    return BLACK
            qx, qy = dx, dy - 0.25
            if abs(qx) < 0.09 and 0.0 < qy < 0.32:
                return WHITE
            if abs(qx) < 0.09 and 0.42 < qy < 0.56:
                return WHITE
            if 0.09 <= qx < 0.28 and -0.2 < qy < -0.05:
                return WHITE
            if abs(qx) < 0.28 and -0.32 < qy < -0.2:
                return WHITE
        return DARK if dy > 0.35 else LIGHT

    return px


def icon(frames: int, size: int):
    def px(x, y):
        f = y // size
        yy = y % size
        bounce = 1 if f == 1 else 0
        cx, cy, r = size / 2, size * 0.55 + bounce, size * 0.4
        dx, dy = (x + 0.5 - cx) / r, (yy + 0.5 - cy) / r
        d = dx * dx + dy * dy
        if d > 1.0:
            return (0, 0, 0, 0)
        if d > 0.8:
            return BLACK
        if (dx - 0.4) ** 2 + (dy + 0.3) ** 2 < 0.06 or (dx + 0.4) ** 2 + (dy + 0.3) ** 2 < 0.06:
            return BLACK
        return LIGHT

    return px


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    png(OUT / "gen1_front.png", 40, 40, blob(40, False))
    png(OUT / "gen1_back.png", 32, 32, blob(32, True))
    png(OUT / "gen1_icon.png", 16, 32, icon(2, 16))
    png(OUT / "gs_front.png", 40, 40, blob(40, False))
    png(OUT / "gs_back.png", 32, 32, blob(32, True))
    png(OUT / "gs_icon.png", 16, 32, icon(2, 16))
    png(OUT / "game3_front.png", 64, 64, blob(64, False))
    png(OUT / "game3_back.png", 64, 64, blob(64, True))
    png(OUT / "game3_icon.png", 32, 64, icon(2, 32))
    for p in sorted(OUT.iterdir()):
        print(p.name, p.stat().st_size)
    return 0


if __name__ == "__main__":
    sys.exit(main())
