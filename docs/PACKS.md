# Texture packs

Kanto Bridge draws Kanto from **your own game's art**: the generated resource pack "Kanto Bridge (generated)" and the
shapes your client builds from it. A **texture pack** is an ordinary Minecraft resource pack that changes that look on
your machine only: a tile's texture, a character's sheet, how a class of things is shaped, or a whole unit as a `.vox`
model ([specs/VOXEL_BLOCKS_PLAN.md](specs/VOXEL_BLOCKS_PLAN.md) §4.9, §5.10, milestone V6).

- A pack is **client-local**. It never reaches the server or other players, and it can't change collision, outlines,
  story cells or anything the server decides.
- A pack changes geometry only **inside the blocks the world has**; it can't add positions.
- No pack ships with the mod, and none is needed.
- Enable your packs **above** "Kanto Bridge (generated)". Among your own packs a higher one wins per key, as with any
  resource pack. **F3+T** reloads them.

## Getting started: the template

On the title screen, **Pack template** (beside "Create Kanto world") writes a ready-to-edit pack made from your own
game to `<.minecraft>/kantobridge/pack-template/<game>/`, then opens that folder. `/kbpack template` in a world does the
same. The template holds:

| Path | What it is |
|---|---|
| `pack.mcmeta`, `README.txt` | A resource pack, ready to copy to `resourcepacks/` and rename |
| `assets/kantobridge/kb_pack/template.json` | The manifest skeleton (below): edit its id, name and sections |
| `keys.json` | Every key your game's export has, with counts (below). **Made from your game** |
| `reference/textures/tile/` | The generated textures by atlas, palette and tile, to trace over. **Made from your game** |
| `reference/models/*.vox` | Each generated tree or prop unit as a `.vox` model in the four Game Boy shades, to open in MagicaVoxel. **Made from your game** |
| `reference/calibration.vox` | The axes check (below). Made by the mod |
| `generated.json` | The SHA-256 of every generated file |

The key index is also written to `<.minecraft>/kantobridge/keys/<game>.json` whenever your generated pack is made, and
whenever you write a template. The pack check uses it to tell an unknown key; without it, keys aren't checked.

## Keys

The game is not part of a key: `targets.games` names the games a manifest applies to.

| Key | Names | Used in |
|---|---|---|
| `tile:<atlas>/<tile>` | One 8 × 8 art tile, in every palette (tile 0–1023) | `textures`, `rules` (a class pin) |
| `tile:<atlas>/<tile>@<PALETTE>` | The same tile in one palette only | `textures` |
| `sprite:<SPRITE_ID>` | A character's sheet | `textures`, `models` |
| `cell:<atlas>/<t0>-<t1>-<t2>-<t3>` | A cell-sized unit (a tree, a sign, a statue) by its four tiles, north-west, north-east, south-west, south-east | `models` |
| `struct:<atlas>/<16 hex digits>` | A building template by its signature (V7; `keys.json` lists them under `structs`) | `models`, `rules` |
| `place:<MAP_ID>/<n>` | One placement of a structure: read and checked, but never drawn (every placement of a structure shares its shapes) | `models` |
| `class:<name>` | A class of tiles: `ground`, `water`, `ledge`, `wall`, `roof`, `tree`, `grass`, `flower`, `counter`, `prop`, `door`, `void` | `rules` |
| `map:<MAP_ID>` | A map | `dressing` (V7) |

Keys are canonical: no leading zeros, nothing around the separators. `keys.json` lists the keys your export has:
`tiles` (each with its palettes, generated sprite id, the block columns that show it as `uses` and the maps as
`maps`), `sprites` (frames), `cells` (what each unit became: `hull`, `object`, `columns`, or with the profile `stair`
or `relief`; its class, `tree` for
foliage else `prop`; the cells that use it), `maps` and `classes`.

For one face or shape the more specific entry wins: `cell:` over `tile:…@PALETTE` over `tile:` over `class:` over the
generated base.

## The manifest

`assets/kantobridge/kb_pack/<name>.json`, any number per pack (a pack's manifests apply in file-name order). The schema
is [`protocol/packs/kb_pack.schema.json`](../protocol/packs/kb_pack.schema.json), with a synthetic example beside it; a
manifest that passes it applies without a warning.

```json
{
  "kantobridge_pack": 1,
  "id": "my_pack",
  "name": "My pack",
  "version": "1.0.0",
  "targets": {"games": ["red", "blue"], "geometry": 1},
  "textures": {
    "tile:tilesets.test/4": "my_pack:block/roof",
    "tile:tilesets.test/7@TEST_PALETTE": "my_pack:block/flower",
    "sprite:SPRITE_TEST_GIRL": "my_pack:textures/npc/girl.png"
  },
  "models": {
    "cell:tilesets.test/2-2-2-2": {"vox": "my_pack:kb_models/tree.vox", "voxelsPerBlock": 8}
  },
  "rules": {
    "class:tree": {"style": "hull"},
    "class:prop": {"depth": 3},
    "class:grass": {"style": "flat"},
    "tile:tilesets.test/5": {"class": "prop"}
  }
}
```

| Field | Meaning |
|---|---|
| `kantobridge_pack` | The format, `1` |
| `id` | Lower-case letters, digits, `_`, `.`, `-`; at most 64 |
| `targets.games` | The games it applies to (`red`, `blue`); a world of another game skips it silently |
| `targets.geometry` | The geometry it was made for, `1` (optional) |
| `textures` | A `tile:` key → a **block sprite id** (`namespace:block/…`, the file `assets/<namespace>/textures/block/….png`): a square power of two, at least 16 px, or a strip of such frames with its `.png.mcmeta` animation. A `sprite:` key → a **texture file** (`namespace:textures/….png`) in the generated sheet's layout (64 × 32: columns facing down, up, left and right; rows standing and walking; 16 × 16 each), at that size or any whole multiple of it |
| `models` | A `cell:`, `struct:` or `sprite:` key → `{"vox": "namespace:path.vox", "voxelsPerBlock": 8, "origin": [0, 0, 0], "turn": 0, "flip": false}` (below) |
| `rules` | `class:tree` and `class:prop`: `style` `hull` (round), `object` (a slab on its own drawing) or `columns` (plain blocks), and an object's `depth` 1–8 (Game Boy pixels; 0 keeps the default 2). `class:grass` and `class:flower`: `style` `tuft` (standing strokes) or `flat` (none). `tile:` → `{"class": "<name>"}` pins the tile to a class: a unit with a pinned tile takes that class's rule. `struct:` → `{"bands": {"roofRows": 0–6}}`: how many of the building's top tile rows are roof (instead of the rows above its highest window) |
| `characters` | `style` `thick`, `carved` or `rigged`: a suggestion (a player's `characters.style` wins); `sprites`: per sprite id, a style or `model` (its `sprite:` model). A sprite that can't take its style falls back down the chain rigged → carved → thick → flat |
| `dressing` | Per `map:` key, `ceiling` and `headroom` 2–8: read and checked, but not applied. A world's dressing is part of its build, which a client-local pack can't change; retexture the room's wall tile instead |

A unit's class is `tree` when the planner marked it foliage (tree walls, lone trees, cut trees), else `prop`.

## `.vox` models

[MagicaVoxel](https://ephtracy.github.io/)'s format: the first model of the file is used, at most 256 voxels along
each axis; palette index 0 is empty.

- **Axes:** vox **z is up**, vox **x is east**, vox **y is north**, so MagicaVoxel's default front view shows the
  model's **south** face, the side the game draws.
- **Scale:** `voxelsPerBlock` 8 (one voxel per Game Boy pixel, the default), 16 (one per 1/16 block) or 32 (halved to
  16: each 2 × 2 × 2 group becomes one voxel of its most frequent colour where at least half of it is filled).
- **Placement:** the model's minimum corner sits at the target's north-west-bottom corner. `origin` moves it by voxels
  (x east, y up, z south; after turning). `turn` (0, 90, 180, 270) turns it clockwise seen from above; `flip` mirrors it
  east-west before turning.
- **A `cell:` model** must fit its unit: 2 × 2 blocks and 2 blocks high (16 × 16 × 16 voxels at 8 per block). It
  replaces the unit wherever those four tiles stand, in every context. Collision doesn't change.
- **A `struct:` model** replaces a building template wherever it stands (V7). Its minimum corner is the template's
  north-west bottom corner (its grid includes one cell row south of the building when the door lies there), and every
  block it fills must lie in the template's volume (`keys.json`: `structs.<key>.blocks` is the grid's width, the
  tallest column and the depth); one that doesn't is not used, and the generated building is drawn.
  `reference/models/struct_*.vox` are the generated buildings to start from.
- **A `sprite:` model** replaces that character (V8): its footprint's centre stands on the character's feet, its
  front (vox's south) along the way it faces; it neither walks nor swings. A player's `"characters": {"packs": false}`
  leaves pack models and styles out.
- **Colours:** each voxel keeps its palette colour, lit and shaded like the terrain.
- **Budget:** at most 96 quads in any one block after merging faces of one colour; a busier model is refused.

**Check the axes once** with `reference/calibration.vox`: an 8 × 8 plate with a red post in its north-east corner and
a blue strip on its south edge. Name any tree's `cell:` key with it (`"voxelsPerBlock": 8`) and look north from south
of it: the post stands at the back right, the strip along the front.

## Packs and the profile

When Dramaless Shape's profile is installed (docs/specs/PROFILE_PLAN.md), it decides many shapes, and a pack still
changes only its own client's look:

- **`struct:` models** replace any template by its signature: a V7 building, and the profile's band-table buildings,
  furniture and two-cell hulls (canopies, planters). `keys.json` lists them all under `structs`, with their size and
  height; a model may fill every block that holds the template's keys, a doorway's or an awning's decor blocks too.
  The `struct:` `bands` rule applies to V7's buildings only (a profile building has its own band table).
- **`cell:` models** replace a unit by its four tiles, the profile's pinned ones too (round trees, standees, posts,
  stumps, cans, stairs, reliefs).
- **`class:tree` and `class:prop` rules** reach the profile's pinned hulls and standees as they reach ours; a stair or
  a relief is neither and keeps its shape (both stand on walkable ground).
- **The profile's class boxes** (cliffs, counters, tables, desks, bookcases, ledges, beds) have no pack key of their
  own: retexture their tiles.

## Validation

Every entry is checked. One that fails is skipped with a counted code and the generated base is used, so a pack can
never leave a hole. F7's **Packs** line and `/kbpack` show what applied and what was skipped, by code.

| Code | Why |
|---|---|
| `pack.format` | The manifest isn't JSON, has another format, no valid `id`, no `targets.games`, or a newer geometry: it is skipped whole |
| `pack.unknown_key` | The key doesn't parse, can't be used in that section, or names something your export doesn't have |
| `pack.texture_missing` | The texture isn't there, or a block face isn't a `block/` sprite (a sheet not a `textures/….png` file) |
| `pack.texture_size` | A block face isn't a square power of two of at least 16 px (or a strip without its animation); a sheet isn't a multiple of the generated layout |
| `pack.vox_format` | The `.vox` file is missing, isn't one, is broken, or its entry's fields are wrong |
| `pack.vox_size` | The model is empty or larger than 256 along an axis, or doesn't fit its target |
| `pack.rule` | A rule names a class that takes no rule, or a number out of range |
| `pack.model_budget` | More than 96 quads in a block |
| `pack.atlas_size` | The pack's block textures would take more than half of an 8192² atlas: all of its block textures are refused, because a failed stitch would crash the game |

## Sharing

Share only **your own** drawings, models and rules. Everything the template writes from your game (`keys.json`,
`reference/`) is listed by hash in `generated.json`. `/kbpack share <folder>` copies `resourcepacks/<folder>` to
`<.minecraft>/kantobridge/shared/<folder>` without those files, even moved or renamed, and without `generated.json`;
share that copy.

## Commands

| Command | What it does |
|---|---|
| `/kbpack` or `/kbpack check` | The packs applied, their entries, what was skipped by code; whether a key index exists |
| `/kbpack template` | Writes the template and the key index, then opens the folder |
| `/kbpack share <folder>` | The copy of `resourcepacks/<folder>` to share |
