# Landscape marking rules (the sections pilot)

How an agent marks one map's landscape sections for [LANDSCAPE_SECTIONS_PLAN.md](LANDSCAPE_SECTIONS_PLAN.md) (stage
2.2). The marks say what each part of the map's solid ground is, so the planner can model it as a whole.

## What you get

- **Chunk images** of the map (`<MAP>_r<row>_c<col>.png`): the map's art at 48 pixels a cell, a faint line round
  every cell, a stronger line and the cell's number every 8 cells along the top (x) and left (y) edges. Each **region**
  (4-connected solid cells: the player can't walk on them) is outlined in its own colour and numbered at one of its
  cells. `<MAP>_chunks.json` gives each chunk's cell window `[x0, y0, x1, y1]`.
- **The regions** (`<MAP>.json`): `w`, `h`, `grid` (the region id of every cell, row by row; -1 for cells you can
  walk, swim or hop), and per region: `id`, `cells`, `bbox` `[x0, y0, x1, y1]`, `floor_sides` (how many of its cells
  touch floor on each side), `front_rows` (per column x, the rows of its south face: 2 a cell), the shares of its tiles
  that repeat (`pure_repeat_share`, `repeat_read_share`), are marked as foliage (`foliage_share`) or hold a reviewed
  cell model (`fitted_cells`), and `doors_below` (door cells right under it).

Cells: x grows to the east (right), y to the south (down); a cell is 2 x 2 tiles. The drawing is the game's
three-quarter view: the lower rows of a thing are its front (south) face, its upper rows the top seen from above.

## What to mark

Every region, as one **section** or split into several. A section:

| Field | What it holds |
|---|---|
| `kind` | `face`, `mass`, `objects`, `trees`, `mouth` or `building` (below) |
| `region` | the region id it lies in (split a region into several sections when it holds several things) |
| `boxes` | cell boxes `[x0, y0, x1, y1]` (inclusive) whose cells of that region are the section; as many as needed |
| `top` | for a `mass` (or a `face` whose top is drawn): boxes of the cells drawn from above, the rest is its front face |
| `rows` | optional: the face's height in tile rows as drawn, when you read it differently from `front_rows` |
| `landmark` | optional, `true`: a unique feature worth a model of its own (a volcano, a crater's rim, a huge rock) |
| `note` | a few words: what it is ("cliff band under the plateau", "Rock Tunnel's mountain", "boulder line") |

A cell in two sections' boxes belongs to the first listed. Cells you leave out keep today's default.

### The kinds

- **`face`**: a vertical face seen from the front: a band of the same rock, earth or wall drawing across its width,
  often with a rim on top, standing between a floor in front (south) and higher ground or the map's edge behind. Cliff
  bands, a ledge's wall, a town's rock wall, a dam's wall. It stands as tall as its drawn rows.
- **`mass`**: a large solid area drawn as a front face along its south edge and a top seen from above: a mountain, a
  rock island, a hill or plateau nobody walks on (Rock Tunnel's mountain, Route 127's islands). Mark its `top`.
- **`objects`**: separate things on the ground, each a cell or two: boulders, rocks in a line, statues, posts,
  bollards, signs, fence posts. A line of them going north is **not** a face: each stands on the ground.
- **`trees`**: a single tree, a tree line, a forest mass, the tree border of the map, hedges and bushes.
- **`mouth`**: the face round a cave mouth or a wall exit (a door into rock): the cells above and beside the mouth
  that belong to the same face. A region with `doors_below` is where to look.
- **`building`**: a house, a gate, a Pokémon Center, a Mart, a gym or a tower, walls and roof: the buildings have
  their own models; mark them so the landscape leaves them alone.

Leave unmarked: water's edges, fences of one continuous drawing, anything you can't tell (say so in `notes`).

### Telling them apart

- A **face** is one continuous drawing across its width; **objects** repeat as separate shapes with ground showing
  between or around them; **trees** have round canopies and trunks.
- In the three-quarter view a face's rows stack up the screen, but so do objects in a line and trees in a forest: what
  decides is whether the drawing is one band (a face) or separate shapes (objects, trees).
- The shares help but don't decide: rock drawn as a repeating texture can be a face; reviewed cell models are found
  in every kind.

## What to write

One JSON file per map, `<MAP>_sections.json`:

```json
{"map": "SOME_ROUTE",
 "sections": [
  {"kind": "mass", "region": 0, "boxes": [[4, 2, 15, 9]], "top": [[5, 2, 14, 7]], "note": "a mountain"},
  {"kind": "mouth", "region": 0, "boxes": [[8, 8, 11, 9]], "note": "the cave mouth in the mountain's front"},
  {"kind": "trees", "region": 0, "boxes": [[0, 0, 3, 30]], "note": "the tree border along the west edge"},
  {"kind": "objects", "region": 5, "boxes": [[12, 20, 12, 24]], "note": "a line of boulders"}],
 "unmarked": [7],
 "notes": "what you couldn't tell, and why"}
```

Check before you finish: every region either in a section or in `unmarked`, and the big regions split where they
hold different things.
