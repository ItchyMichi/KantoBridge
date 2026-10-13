-- kb_swapsites: runtime block-change sites the world builder prepares story
-- cells for (docs/PROTOCOL.md "Export resources", protocol/export/
-- swaps.schema.json), and the runtime check that every block change the
-- hidden game makes is one of them (docs/PROTOCOL.md "Story rules (M8)").
-- A site is one 32-px block of one map whose block id the hidden game can
-- change at run time: { map, bx, by, kind, before, after, source? }.
--
-- `before` is the CLOSED state (the block that bars the way or shows the
-- thing that goes away: the tree, the shut door, the barrier, the ship) and
-- `after` the OPEN state the builder builds; a tile story cell is closed
-- while the live block is `before`. The map data ships one of the two:
-- cut trees, the Elite Four exits, Lance's doorway, the Victory Road
-- barriers and the S.S. Anne ship `before`; the card-key doors and the
-- Rocket Hideout lift gates ship `after` (the engine stamps them shut on map
-- load, OC:385-428), and so may the Pokemon Mansion rows whose switched-off
-- state is the open floor. So the exported block equals `before` or `after`,
-- never something else (tools/e2e/test_export.py counts the three cases).
--
-- The audit of the engine's runtime block changes (v0.3.85, every
-- Map:setBlock caller: src/world/Map.lua:244-249 is the only runtime write
-- into a Gen 1 map's blocks; OverworldState:replaceBlock, OC:5803-5806, and
-- the script command replace_block, src/script/Commands.lua:1313-1319, go
-- through it; no data script uses that command. Two writers outside it are
-- not runtime changes of a Gen 1 map: the Union Room patches map DEFINITIONS
-- at data load, src/world/gen1/UnionCenters.lua:64, :347, which decision U3
-- keeps off; src/core/game3/field.lua:2238 is Gen 3's own map).
-- OC = src/world/OverworldController.lua.
--
--   cut       OverworldState:tryCut (OC:3404-3442) swaps a tree block by its
--             field.cutTreeSwaps row; the regrowth on the map's next load
--             (OC:486-494) puts the block back. Sites: every block holding
--             a cuttable tree cell (kb_cells' cut bit). Cutting tall grass
--             ($52, the same function) is a cosmetic change with no site:
--             the runtime check knows it from the engine's own cut record
--             (ow.cutBlocks).
--   card_key  field.cardKeyDoors.closedDoors on the card-key floors:
--             stampClosedDoors on every map load and after a battle
--             (OC:397-428, :495, :5369), the CARD KEY's opening
--             (tryCardKeyDoor, OC:3022-3067; its open block is
--             cardKeyDoors.openBlock, or silphCo11F.openBlock on 11F).
--   script    the closedDoors rows of other floors (the Rocket Hideout lift
--             gates; FieldDefaults.FIELD.cardKeyDoors seeds them,
--             src/world/FieldDefaults.lua:150-180); the Game Corner poster
--             (field.gameCornerPoster, data/scripts/story3.lua:526-567); the
--             Vermilion Gym door (closed $24 on entry until
--             EVENT_2ND_LOCK_OPENED, data/scripts/seafoam.lua:18-24; opened
--             to hiddenExtras.trashCans.doorBlock by the trash-can puzzle,
--             OC:3136-3148); the Elite Four exits and Lance's doorway
--             (data/scripts/story4.lua:862-985); the Pokemon Mansion switch
--             blocks and the Cinnabar Gym quiz gates
--             (data/scripts/story6.lua:25-170); the S.S. Anne's departure
--             (data/scripts/story3.lua:947-1000, OC:2010-2028: her hull and
--             deck blocks become water).
--   boulder   the Victory Road boulder-switch barriers
--             (data/scripts/story.lua:945-1030).
-- Nothing else in the engine changes a Gen 1 map's blocks (src/world/gen2,
-- src/core/game3 are other generations).

local SwapSites = {}

-- The curated script and boulder sites: { map, bx, by, kind, before, after,
-- source }. Block ids are the engine's own literals at the cited lines (the
-- pokered values they port); never ROM data.
SwapSites.CURATED = {
  -- Victory Road switch barriers (data/scripts/story.lua:957-1015): closed
  -- $25/$37 until a boulder rests on the floor's switch
  { "VICTORY_ROAD_1F", 4, 6, "boulder", 0x25, 0x1D, "EVENT_VICTORY_ROAD_1_BOULDER_ON_SWITCH" },
  { "VICTORY_ROAD_2F", 3, 4, "boulder", 0x37, 0x15, "EVENT_VICTORY_ROAD_2_BOULDER_ON_SWITCH1" },
  { "VICTORY_ROAD_2F", 11, 7, "boulder", 0x25, 0x1D, "EVENT_VICTORY_ROAD_2_BOULDER_ON_SWITCH2" },
  { "VICTORY_ROAD_3F", 3, 5, "boulder", 0x25, 0x1D, "EVENT_VICTORY_ROAD_3_BOULDER_ON_SWITCH1" },
  -- Elite Four exit seals, the block above the exit warp (story4.lua:872,
  -- :920-932)
  { "LORELEIS_ROOM", 2, 0, "script", 0x24, 0x05, "EVENT_BEAT_LORELEIS_ROOM_TRAINER_0" },
  { "BRUNOS_ROOM", 2, 0, "script", 0x24, 0x05, "EVENT_BEAT_BRUNOS_ROOM_TRAINER_0" },
  { "AGATHAS_ROOM", 2, 0, "script", 0x3b, 0x0e, "EVENT_BEAT_AGATHAS_ROOM_TRAINER_0" },
  -- Lance's arena doorway: open on every load until the first crossing
  -- locks it (story4.lua:971-976)
  { "LANCES_ROOM", 2, 6, "script", 0x72, 0x31, "EVENT_LANCES_ROOM_LOCK_DOOR" },
  { "LANCES_ROOM", 3, 6, "script", 0x73, 0x32, "EVENT_LANCES_ROOM_LOCK_DOOR" },
  -- Pokemon Mansion switch blocks (story6.lua:37-58): each row flips
  -- between a gate ($2d/$54/$5f) and the open floor $0e with
  -- EVENT_MANSION_SWITCH_ON; the gate is the closed state
  { "POKEMON_MANSION_1F", 12, 6, "script", 0x2d, 0x0e, "EVENT_MANSION_SWITCH_ON" },
  { "POKEMON_MANSION_1F", 8, 3, "script", 0x2d, 0x0e, "EVENT_MANSION_SWITCH_ON" },
  { "POKEMON_MANSION_1F", 10, 8, "script", 0x2d, 0x0e, "EVENT_MANSION_SWITCH_ON" },
  { "POKEMON_MANSION_1F", 13, 13, "script", 0x2d, 0x0e, "EVENT_MANSION_SWITCH_ON" },
  { "POKEMON_MANSION_2F", 4, 2, "script", 0x5f, 0x0e, "EVENT_MANSION_SWITCH_ON" },
  { "POKEMON_MANSION_2F", 9, 4, "script", 0x54, 0x0e, "EVENT_MANSION_SWITCH_ON" },
  { "POKEMON_MANSION_2F", 3, 11, "script", 0x5f, 0x0e, "EVENT_MANSION_SWITCH_ON" },
  { "POKEMON_MANSION_3F", 7, 2, "script", 0x5f, 0x0e, "EVENT_MANSION_SWITCH_ON" },
  { "POKEMON_MANSION_3F", 7, 5, "script", 0x5f, 0x0e, "EVENT_MANSION_SWITCH_ON" },
  { "POKEMON_MANSION_B1F", 13, 8, "script", 0x2d, 0x0e, "EVENT_MANSION_SWITCH_ON" },
  { "POKEMON_MANSION_B1F", 6, 11, "script", 0x5f, 0x0e, "EVENT_MANSION_SWITCH_ON" },
  { "POKEMON_MANSION_B1F", 4, 3, "script", 0x5f, 0x0e, "EVENT_MANSION_SWITCH_ON" },
  { "POKEMON_MANSION_B1F", 8, 8, "script", 0x54, 0x0e, "EVENT_MANSION_SWITCH_ON" },
  -- Cinnabar Gym quiz gates (story6.lua:132-149): closed until the quiz is
  -- answered or the room's trainer is beaten; open is $0e
  { "CINNABAR_GYM", 9, 3, "script", 0x54, 0x0e, "EVENT_CINNABAR_GYM_GATE0_UNLOCKED" },
  { "CINNABAR_GYM", 6, 3, "script", 0x54, 0x0e, "EVENT_CINNABAR_GYM_GATE1_UNLOCKED" },
  { "CINNABAR_GYM", 6, 6, "script", 0x54, 0x0e, "EVENT_CINNABAR_GYM_GATE2_UNLOCKED" },
  { "CINNABAR_GYM", 3, 8, "script", 0x5f, 0x0e, "EVENT_CINNABAR_GYM_GATE3_UNLOCKED" },
  { "CINNABAR_GYM", 2, 6, "script", 0x54, 0x0e, "EVENT_CINNABAR_GYM_GATE4_UNLOCKED" },
  { "CINNABAR_GYM", 2, 3, "script", 0x54, 0x0e, "EVENT_CINNABAR_GYM_GATE5_UNLOCKED" },
}

-- The Vermilion Gym's closed double door, stamped on entry until
-- EVENT_2ND_LOCK_OPENED (data/scripts/seafoam.lua:21: $24).
SwapSites.VERMILION_GYM_CLOSED = 0x24

-- The S.S. Anne's hull and deck blocks and the water each becomes when she
-- sails (data/scripts/story3.lua:947-954; the same box as OC:183-192).
-- `before` is the dock's own block there (the ship), read from the map.
SwapSites.SHIP = {
  map = "VERMILION_DOCK", source = "EVENT_SS_ANNE_LEFT",
  blocks = { { 5, 1, 1 }, { 6, 1, 1 }, { 7, 1, 1 }, { 8, 1, 1 },
             { 5, 2, 13 }, { 6, 2, 13 }, { 7, 2, 13 }, { 8, 2, 13 } },
}

local MAP_ID = "^[A-Z0-9_]+$"
local SYMBOL = "^[A-Za-z0-9_]+$"
local KINDS = { cut = true, card_key = true, script = true, boulder = true }
local floor = math.floor

local function byte(v)
  return type(v) == "number" and v == floor(v) and v >= 0 and v <= 255
end

local function sym(v, pattern)
  return type(v) == "string" and #v > 0 and #v <= 64 and v:find(pattern or SYMBOL) ~= nil
end

-- A checked site, or nil plus a warning code. before == after is no change
-- at all, so it is refused.
function SwapSites.site(map, bx, by, kind, before, after, source)
  if not (sym(map, MAP_ID) and byte(bx) and byte(by) and byte(before) and byte(after) and KINDS[kind]) then
    return nil, "swap_bad_site"
  end
  if before == after then return nil, "swap_no_change" end
  local out = { map = map, bx = bx, by = by, kind = kind, before = before, after = after }
  if source ~= nil then
    if sym(source) then out.source = source else return nil, "swap_bad_source" end
  end
  return out
end

-- Cut sites of one map from its cell flags: each block with a cut-tree cell.
--   rows       kb_cells rows of the map
--   blockAt    fn(bx, by) -> the map record's block id
--   swapAfter  table: block id -> the block it becomes (field.cutTreeSwaps)
--   isCut      fn(flags) -> true for a cut-tree cell
function SwapSites.cutSites(mapId, rows, blockAt, swapAfter, isCut)
  local out, seen = {}, {}
  for cy = 1, #rows do
    local row = rows[cy]
    for cx = 1, #row do
      if isCut(row[cx]) then
        local bx, by = floor((cx - 1) / 2), floor((cy - 1) / 2)
        local key = by * 1024 + bx
        if not seen[key] then
          seen[key] = true
          local before = blockAt(bx, by)
          local after = swapAfter[before]
          if after ~= nil then
            out[#out + 1] = { mapId, bx, by, "cut", before, after }
          end
        end
      end
    end
  end
  return out
end

-- The data-driven door and switch sites.
--   field     table: key -> the field table (cardKeyDoors, gameCornerPoster,
--             hiddenExtras)
--   version   "red" / "blue" (cardKeyDoors.skipMaps is per version)
--   blockAt   fn(mapId, bx, by) -> the map record's block id, or nil
function SwapSites.doorSites(field, version, blockAt)
  local out = {}
  local ck = type(field.cardKeyDoors) == "table" and field.cardKeyDoors or {}
  local cardKeyMaps = {}
  for _, m in ipairs(type(ck.maps) == "table" and ck.maps or {}) do cardKeyMaps[m] = true end
  local skips = type(ck.skipMaps) == "table" and ck.skipMaps[version] or nil
  local closed = type(ck.closedDoors) == "table" and ck.closedDoors or {}
  local maps = {}
  for mapId in pairs(closed) do
    if type(mapId) == "string" and not (type(skips) == "table" and skips[mapId]) then maps[#maps + 1] = mapId end
  end
  table.sort(maps)
  for _, mapId in ipairs(maps) do
    for _, door in ipairs(type(closed[mapId]) == "table" and closed[mapId] or {}) do
      if type(door) == "table" then
        local source = door.event
        if source == nil and type(door.events) == "table" and #door.events == 1 then source = door.events[1] end
        out[#out + 1] = { mapId, door.bx, door.by, cardKeyMaps[mapId] and "card_key" or "script",
                          door.block, door.open, source }
      end
    end
  end
  local poster = field.gameCornerPoster
  if type(poster) == "table" then
    out[#out + 1] = { poster.map, poster.x, poster.y, "script", poster.closedBlock, poster.openBlock, poster.event }
  end
  local extras = type(field.hiddenExtras) == "table" and field.hiddenExtras or {}
  local cans = type(extras.trashCans) == "table" and extras.trashCans or nil
  local door = cans and type(cans.doorBlock) == "table" and cans.doorBlock or nil
  if door and type(cans.map) == "string" then
    out[#out + 1] = { cans.map, door.bx, door.by, "script", SwapSites.VERMILION_GYM_CLOSED, door.block,
                      "EVENT_2ND_LOCK_OPENED" }
  end
  return out
end

-- The curated rows plus the S.S. Anne's (her `before` blocks are the dock's
-- own: blockAt(mapId, bx, by), nil when the map isn't there).
function SwapSites.scriptSites(blockAt)
  local out = {}
  for _, r in ipairs(SwapSites.CURATED) do out[#out + 1] = { r[1], r[2], r[3], r[4], r[5], r[6], r[7] } end
  local ship = SwapSites.SHIP
  for _, b in ipairs(ship.blocks) do
    local before = blockAt and blockAt(ship.map, b[1], b[2]) or nil
    if before ~= nil then out[#out + 1] = { ship.map, b[1], b[2], "script", before, b[3], ship.source } end
  end
  return out
end

-- Every site, checked, de-duplicated and sorted by map, by, bx, kind.
-- rows: arrays { map, bx, by, kind, before, after, source? }.
-- warn(code) counts what is left out.
function SwapSites.collect(rows, warn)
  warn = warn or function() end
  local out, seen = {}, {}
  for _, r in ipairs(rows) do
    local s, why = SwapSites.site(r[1], r[2], r[3], r[4], r[5], r[6], r[7])
    if not s then
      warn(why)
    else
      local key = table.concat({ s.map, s.bx, s.by, s.kind, s.before }, ":")
      if not seen[key] then
        seen[key] = true
        out[#out + 1] = s
      end
    end
  end
  table.sort(out, function(a, b)
    if a.map ~= b.map then return a.map < b.map end
    if a.by ~= b.by then return a.by < b.by end
    if a.bx ~= b.bx then return a.bx < b.bx end
    if a.kind ~= b.kind then return a.kind < b.kind end
    return a.before < b.before
  end)
  return out
end

-- ------------------------------------------------------------ the runtime check (M8)

-- The sites the runtime check knows, by map: index[map][by * 256 + bx] =
-- the set of block ids a site lets that block take (its before and after;
-- a card-key door also the CARD KEY's open block, which tryCardKeyDoor
-- writes: cardKeyDoors.openBlock, or silphCo11F.openBlock on SILPH_CO_11F,
-- OC:3030-3039). Cut sites are not listed: the engine's own cut record
-- covers every cut (kb_story).
--   field, version, blockAt: as doorSites; blockAt also feeds the ship rows
-- Returns the index and the number of sites in it.
function SwapSites.runtimeIndex(field, version, blockAt)
  local rows = SwapSites.doorSites(field, version, blockAt)
  for _, r in ipairs(SwapSites.scriptSites(blockAt)) do rows[#rows + 1] = r end
  local ck = type(field.cardKeyDoors) == "table" and field.cardKeyDoors or {}
  local eleventh = type(ck.silphCo11F) == "table" and ck.silphCo11F or {}
  local index, n = {}, 0
  for _, r in ipairs(rows) do
    local s = SwapSites.site(r[1], r[2], r[3], r[4], r[5], r[6], r[7])
    if s then
      local byMap = index[s.map] or {}
      index[s.map] = byMap
      local key = s.by * 256 + s.bx
      local allowed = byMap[key] or {}
      byMap[key] = allowed
      allowed[s.before], allowed[s.after] = true, true
      if s.kind == "card_key" then
        local open = s.map == "SILPH_CO_11F" and eleventh.openBlock or ck.openBlock
        if byte(open) then allowed[open] = true end
      end
      n = n + 1
    end
  end
  return index, n
end

-- True when the block at (bx, by) of map may become `block` through a site.
function SwapSites.allows(index, map, bx, by, block)
  local byMap = index and index[map]
  local allowed = byMap and byMap[by * 256 + bx]
  return allowed ~= nil and allowed[block] == true
end

return SwapSites
