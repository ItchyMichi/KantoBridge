-- kb_cells: the per-cell flags of one map (docs/PROTOCOL.md "Export
-- resources", protocol/export/map.schema.json `cells`).
--
-- One integer per 16-px cell, rows top to bottom. Every tile question goes
-- to the engine's own runtime map through kb_engine.mapQuery (Map.new and
-- its queries), so the builder never copies engine constants:
--
--   bit  0     1  walkable                 q.walkable
--   bit  1     2  water (surfable, + shore) q.water, only on field.waterTilesets
--   bit  2     4  shore                    water, and a shore tile
--   bit  3     8  tall grass               q.grass
--   bit  4    16  door tile                q.door
--   bit  5    32  warp tile (stairs, mats)  a tile in the tileset's warpTiles
--   bit  6    64  counter                  q.counter
--   bit  7   128  cut tree                 a cuttable tree tile, not walkable,
--                                          in a block field.cutTreeSwaps swaps
--   bit  8   256  warp pad                 q.padOrHole == "pad", on a warp entry
--   bit  9   512  hole                     q.padOrHole == "hole", on a warp entry
--                                          (the engine asks only when a warp is
--                                          taken or reached, src/world/
--                                          OverworldController.lua:5470, :5666;
--                                          elsewhere those tiles are floor art)
--   bit 10  1024  spinner tile             a field.spinners cell of this map
--   bit 11  2048  a warp entry is here     the map record's warps
--   bit 12  4096  a sign is here           the map record's signs
--   bit 13  8192  ledge                    field.ledges (below)
--   bits 14-15    ledge hop direction      0 down, 1 up, 2 left, 3 right
--
-- Ledges (OverworldState:checkLedgeHop, src/world/OverworldController.lua:
-- 1985-2045): a row { facing, input, standingTile, ledgeTile, tileset? }
-- (no tileset = OVERWORLD) makes a hop when the player stands on a cell
-- whose tile is standingTile and presses `input` == `facing` toward a cell
-- whose tile is ledgeTile. That second cell is the ledge cell; its hop
-- direction is `facing`. Pure: works on the query and plain tables only.

local Cells = {}

Cells.WALK, Cells.WATER, Cells.SHORE, Cells.GRASS = 1, 2, 4, 8
Cells.DOOR, Cells.WARP_TILE, Cells.COUNTER, Cells.CUT = 16, 32, 64, 128
Cells.PAD, Cells.HOLE, Cells.SPINNER, Cells.WARP = 256, 512, 1024, 2048
Cells.SIGN, Cells.LEDGE = 4096, 8192
Cells.LEDGE_UNIT = 16384 -- the hop direction is bits 14-15
Cells.LEDGE_DIRS = { down = 0, up = 1, left = 2, right = 3 }

local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local floor = math.floor

local function set(list)
  local out = {}
  for _, v in ipairs(type(list) == "table" and list or {}) do out[v] = true end
  return out
end
Cells.set = set

local function int(v)
  return type(v) == "number" and v == floor(v)
end

-- The ledge rows that apply to a tileset, with their hop deltas.
function Cells.ledgeRows(ledges, tilesetId)
  local out = {}
  for _, row in ipairs(type(ledges) == "table" and ledges or {}) do
    if type(row) == "table" and (row.tileset or "OVERWORLD") == tilesetId
        and row.facing == row.input and DELTA[row.facing]
        and int(row.ledgeTile) and int(row.standingTile) then
      out[#out + 1] = { dir = row.facing, d = DELTA[row.facing],
                        ledge = row.ledgeTile, standing = row.standingTile }
    end
  end
  return out
end

-- q:   kb_engine.mapQuery(def, tileset)
-- ctx: surfable   the tileset is in field.waterTilesets
--      shore      set of shore tile ids
--      cutTiles   set of cuttable tree tile ids for this tileset
--      cutBlocks  set of block ids some field.cutTreeSwaps row swaps
--      warpTiles  set of the tileset's warp tile ids
--      ledges     Cells.ledgeRows(...)
--      warps, signs, spinners   lists of { x, y } (record cells)
--      checkpoint optional, called after each row
--      warn       optional fn(code): a counted warning
-- Returns rows (an array of arrays of integers).
function Cells.compute(q, ctx)
  local w, h = q.w, q.h
  local warn = ctx.warn or function() end
  local marks = {}
  local function mark(list, bit, code)
    for _, p in ipairs(type(list) == "table" and list or {}) do
      if type(p) == "table" and int(p.x) and int(p.y) and p.x >= 0 and p.y >= 0 and p.x < w and p.y < h then
        local key = p.y * w + p.x
        local current = marks[key] or 0
        if floor(current / bit) % 2 == 0 then marks[key] = current + bit end
      else
        warn(code)
      end
    end
  end
  mark(ctx.warps, Cells.WARP, "warp_off_map")
  mark(ctx.signs, Cells.SIGN, "sign_off_map")
  mark(ctx.spinners, Cells.SPINNER, "spinner_off_map")

  local shore, cutTiles, cutBlocks = ctx.shore or {}, ctx.cutTiles or {}, ctx.cutBlocks or {}
  local warpTiles, ledges, surfable = ctx.warpTiles or {}, ctx.ledges or {}, ctx.surfable
  local rows = {}
  for cy = 0, h - 1 do
    local row = {}
    for cx = 0, w - 1 do
      local tile = q.tile(cx, cy)
      local f = marks[cy * w + cx] or 0
      local walkable = q.walkable(cx, cy)
      if walkable then f = f + Cells.WALK end
      if surfable and q.water(cx, cy) then
        f = f + Cells.WATER
        if shore[tile] then f = f + Cells.SHORE end
      end
      if q.grass(cx, cy) then f = f + Cells.GRASS end
      if q.door(cx, cy) then f = f + Cells.DOOR end
      if warpTiles[tile] then f = f + Cells.WARP_TILE end
      if q.counter(cx, cy) then f = f + Cells.COUNTER end
      if cutTiles[tile] and not walkable and cutBlocks[q.block(floor(cx / 2), floor(cy / 2))] then
        f = f + Cells.CUT
      end
      if floor(f / Cells.WARP) % 2 == 1 then
        local pad = q.padOrHole(cx, cy)
        if pad == "pad" then f = f + Cells.PAD elseif pad == "hole" then f = f + Cells.HOLE end
      end
      local hop
      for i = 1, #ledges do
        local L = ledges[i]
        if tile == L.ledge then
          local sx, sy = cx - L.d[1], cy - L.d[2]
          if sx >= 0 and sy >= 0 and sx < w and sy < h and q.tile(sx, sy) == L.standing then
            if hop == nil then
              hop = L.dir
            elseif hop ~= L.dir then
              warn("ledge_multi")
            end
          end
        end
      end
      if hop then f = f + Cells.LEDGE + Cells.LEDGE_DIRS[hop] * Cells.LEDGE_UNIT end
      row[cx + 1] = f
    end
    rows[cy + 1] = row
    if ctx.checkpoint then ctx.checkpoint() end
  end
  return rows
end

-- True when a cell's flags carry the bit (one of the single-bit values above).
function Cells.has(flags, bit) return floor(flags / bit) % 2 == 1 end

return Cells
