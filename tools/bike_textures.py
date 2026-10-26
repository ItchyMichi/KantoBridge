"""Draws the bike's three placeholder textures (docs/specs/BIKE_PLAN.md B4, D7): 64 x 32 RGBA PNGs laid out for
fabric-bridge's BikeModel (its texOffs and box sizes), by hand-chosen colours only; nothing comes from a game. Run from
the repo root:

    python tools/bike_textures.py        # writes fabric-bridge/src/main/resources/assets/kantobridge/textures/entity/bike_*.png

A resource pack may replace the three files; the layout below is the contract (the boxes' UV islands, Minecraft's usual
unfolding: top and bottom at (u + d, v), the four sides from (u, v + d)).
"""
from __future__ import annotations

import struct
import zlib
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "fabric-bridge/src/main/resources/assets/kantobridge/textures/entity"
W, H = 64, 32

# part: (u, v, w, h, d) as BikeModel's texOffs and addBox sizes
PARTS = {
    "wheel_front": (0, 0, 2, 8, 8),
    "wheel_rear": (20, 0, 2, 8, 8),
    "top_tube": (0, 16, 1, 1, 10),
    "down_tube": (22, 16, 1, 1, 9),
    "rear_stay": (44, 16, 1, 1, 6),
    "seat_post": (52, 0, 1, 6, 1),
    "saddle": (40, 0, 2, 1, 4),
    "fork": (60, 0, 1, 6, 1),
    "stem": (56, 0, 1, 6, 1),
    "bar": (40, 8, 8, 1, 1),
    "pegs": (40, 12, 6, 1, 1),
}

LIVERIES = {
    # name: frame, frame highlight, hub/spokes, saddle, bar
    "bike_mach": ((200, 30, 45), (250, 120, 120), (230, 230, 235), (30, 30, 34), (190, 190, 198)),
    "bike_acro": ((60, 160, 80), (250, 210, 60), (250, 210, 60), (40, 40, 44), (205, 205, 212)),
    "bike_plain": ((40, 90, 190), (120, 170, 240), (215, 215, 220), (90, 60, 40), (200, 200, 205)),
}
TYRE = (34, 34, 38)
TREAD = (58, 58, 62)
RIM = (170, 172, 180)


def island(u, v, w, h, d):
    """The six faces' rectangles: (x, y, width, height)."""
    return [(u + d, v, w, d), (u + d + w, v, w, d),          # top, bottom
            (u, v + d, d, h), (u + d, v + d, w, h),          # east side, north
            (u + d + w, v + d, d, h), (u + 2 * d + w, v + d, w, h)]  # west side, south


def draw(name: str) -> bytes:
    frame, light, hub, saddle, bar = LIVERIES[name]
    px = [[(0, 0, 0, 0)] * W for _ in range(H)]

    def fill(x, y, w, h, c, a=255):
        for j in range(y, min(H, y + h)):
            for i in range(x, min(W, x + w)):
                px[j][i] = (*c, a)

    for part, (u, v, w, h, d) in PARTS.items():
        faces = island(u, v, w, h, d)
        if part.startswith("wheel"):
            for (x, y, fw, fh) in faces:
                fill(x, y, fw, fh, TREAD)
            # the two discs (d x h = 8 x 8): a round tyre, a rim, spokes and a hub; the corners clear
            for (x, y, fw, fh) in (faces[2], faces[4]):
                for j in range(fh):
                    for i in range(fw):
                        dx, dy = i - 3.5, j - 3.5
                        r = (dx * dx + dy * dy) ** 0.5
                        if r > 4.0:
                            px[y + j][x + i] = (0, 0, 0, 0)
                        elif r > 3.0:
                            px[y + j][x + i] = (*TYRE, 255)
                        elif r > 2.4:
                            px[y + j][x + i] = (*RIM, 255)
                        elif r < 1.0 or abs(dx) < 0.6 or abs(dy) < 0.6:
                            px[y + j][x + i] = (*hub, 255)
                        else:
                            px[y + j][x + i] = (0, 0, 0, 0)
        else:
            colour = saddle if part == "saddle" else bar if part in ("bar", "stem", "pegs") else frame
            for (x, y, fw, fh) in faces:
                fill(x, y, fw, fh, colour)
            if colour == frame:
                # a highlight along the top face
                x, y, fw, fh = faces[0]
                fill(x, y, fw, 1, light)
    rows = b"".join(b"\x00" + bytes(c for p in row for c in p) for row in px)

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for name in LIVERIES:
        (OUT / f"{name}.png").write_bytes(draw(name))
        print("wrote", (OUT / f"{name}.png").relative_to(OUT.parents[5]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
