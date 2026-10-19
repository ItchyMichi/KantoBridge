# Buildings as parts (trial, 2026-10-19)

The generated buildings (HAND_MODELS.md section 7: one cube and a roof topper) often don't match a building's real
shape: a domed house, a lighthouse, a treehouse or a tower comes out as a box with its drawing stretched over it. Here
an agent describes a building as **parts** (boxes, cylinders, gable and hip roofs, domes, cones, and cuts), and
`tools/shape_fit/parts_model.py` builds and paints it.

## Status (the user, 2026-10-19)

- A trial only: **not run game-wide**. The user picks more tricky buildings to try first.
- **Blue keeps the box system** (HAND_MODELS.md section 7): its simple sprites suit it. Parts are for Crystal and
  Emerald, where the trial was an improvement (the Olivine lighthouse, an Ecruteak house, Littleroot's house, a
  Fortree treehouse, Sootopolis's dome house, the Radio Tower).
- Lesson (Sootopolis's dome house): the marked area can include background (rock, ground, trees) behind or beside the
  building; leave it out of the parts. The building's footprint is only the building, and a dome or roof on top is as
  wide as the building under it.
- Shapes the agents asked for: roofs curving up at the eaves (Ecruteak), hip-and-gable roofs, gable ends with their
  own paint, open balconies (a lighthouse's gallery), a trunk's own colour. Added (2026-10-19): `barrel` and
  `profile` roofs (rounded and curved roofs, upswept eaves), their paint following the curve.

## Reading the drawing

- The drawing is the building as the game draws it, top-down in three-quarter view, **16 px a cell**; `drawing.png`
  is W x D px, `reference_grid.png` shows it at 10x with a line every 4 px (magenta every 16) and pixel numbers
  every 8 along the top (x) and left (y).
- The lower rows are the **front (south) face**; the upper rows show the **roof or top seen from above and behind**.
  The sides and back aren't drawn: make them plausible.
- Leave out background the marked area includes (rock, ground, trees behind or beside the building): model only the
  building, and make a dome or roof on top as wide as the building under it.
- A part's ground footprint lies under its drawing: x is the drawing's x; z (north to south) runs over the drawing's
  rows, z = 0 its top row, z = D its bottom row. A building's front wall normally stands a few pixels north of the
  bottom (z about D - 4) so its roof's eaves can reach the edge.
- Heights are in voxels, **8 a block** (a player is 16). The user's scale: **1.5 voxels for each drawn row of
  facade** (a door about 2 blocks; a two-storey house about 6 blocks; Celadon's Department Store about 20).

## The spec

`spec.json` (parts_model.py's docstring has the full reference):

```json
{"height": 64,
 "parts": [
   {"shape": "box", "x": [4, 60], "z": [10, 44], "y": [0, 30],
    "paint": {"front": [4, 20, 60, 48], "top": [4, 0, 60, 20]}},
   {"shape": "hip", "x": [2, 62], "z": [6, 46], "y": [30, 46], "paint": {"top": [2, 0, 62, 20], "front": [2, 14, 62, 20]}},
   {"shape": "box", "x": [24, 34], "z": [41, 45], "y": [0, 16], "carve": true}
 ]}
```

- Walls don't have to be boxes: if the building's lower part is round or oval (a round tower, a rounded front, a
  rotunda), use a `cylinder` for it; `"radius"` is a number or `[rx, rz]` for an oval, and its front art wraps round
  the curve. Mix freely: a box with a cylinder at one end, a cylinder under a dome or a cone.
- Shapes: `box`, `cylinder` (center, radius), `gable` (a ridge along "x" or "z"), `hip`, `dome` (a half sphere),
  `cone`, `barrel` (a half-round vault running "along" x or z), `profile` (a roof cross-section extruded "along" x or
  z: `"profile": [heights above y[0], evenly spaced, north to south for along x]`, optional `"cross"` the same along
  the run, the roof the lower of the two: rounded roofs, curved hips, eaves sweeping up, steps); `"carve": true` cuts
  (a recessed door, an alcove). Later parts add to or cut from earlier ones.
- On a barrel or profile roof the `top` rectangle follows the curve (its rows laid along the surface, the last row at
  the front edge): give it all the roof's rows, from the top of the drawing down to the eave, so a band drawn across a
  rounded roof's front (a Pokémon Center's Poké Ball band) wraps round it.
- Ranges are in pixels (x, z) and voxels (y), the first included, the last excluded.
- Paint: rectangles of the drawing `[px0, py0, px1, py1]` (pixels, last excluded): `front` over the part's south face
  (stretched to fit), `top` over its upward faces seen from above, `side` over east and west (default: the front's
  outer columns), `back` over the north face. Pick rectangles so each face shows the right art: a facade's rows on its
  front, the roof's rows on the roof.
- Keep it simple: a building is usually 2 to 8 parts. Details (windows, signs) stay painted, not modelled.

## Building and checking

```
docker run --rm -m 800m -u $(id -u):$(id -g) -v /home/claude/kantobridge/tools/shape_fit:/sf:ro \
  -v <package dir>:/pkg kb-pyimg:1 python /sf/parts_model.py /pkg/spec.json /pkg/drawing.png /pkg/out --name model
```

writes `out/model_view.png` (3D from the south-east, above) and `out/model_front.png` (straight from the south). Look
at both: the front view should read like the drawing's facade; the 3D view should look like the building (its roof
shape, its towers, its dome). Fix and rebuild; three rounds at most.
