-- kb_swapsites: runtime block-change sites the world builder prepares story
-- cells for (docs/PROTOCOL.md "Export resources", protocol/export/
-- swaps.schema.json). A site is one 32-px block of one map whose block id
-- the hidden game can change at run time: { map, bx, by, kind, before,
-- after, source? }.
--
-- M2's table is the part that follows from data:
--   cut       every block holding a cuttable tree cell (kb_cells' cut bit),
--             swapped by its field.cutTreeSwaps row (OverworldState:tryCut,
--             src/world/OverworldController.lua:3292-3330);
--   card_key  field.cardKeyDoors.closedDoors on the card-key floors: stamped
--             shut on map load until the door's event is set, opened by the
--             CARD KEY (stampClosedDoors :390-421, tryCardKeyDoor :2912-2955);
--   script    the closedDoors rows on other floors (the Rocket Hideout lift
--             gates open on their trainers' events), the Game Corner poster
--             (field.gameCornerPoster, data/scripts/story3.lua:529-535,
--             :553-567) and the Vermilion Gym trash-can door
--             (field.hiddenExtras.trashCans.doorBlock, OverworldController
--             :3024-3033).
-- Swaps that live only in hand-written scripts (gym barriers, the Elite Four
-- doors, the Pokemon Mansion switches, the S.S. Anne) come with M8's swap
-- audit, as curated rows in CURATED below.

local SwapSites = {}

-- M8: { map, bx, by, kind = "script", before, after, source? } rows found by
-- the audit of the engine's runtime block changes.
SwapSites.CURATED = {}

local MAP_ID = "^[A-Z0-9_]+$"
local SYMBOL = "^[A-Za-z0-9_]+$"
local floor = math.floor

local function byte(v)
  return type(v) == "number" and v == floor(v) and v >= 0 and v <= 255
end

local function sym(v, pattern)
  return type(v) == "string" and #v > 0 and #v <= 64 and v:find(pattern or SYMBOL) ~= nil
end

-- A checked site, or nil plus a warning code.
function SwapSites.site(map, bx, by, kind, before, after, source)
  if not (sym(map, MAP_ID) and byte(bx) and byte(by) and byte(before) and byte(after)) then
    return nil, "swap_bad_site"
  end
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
    out[#out + 1] = { cans.map, door.bx, door.by, "script", blockAt(cans.map, door.bx, door.by), door.block }
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
  for _, r in ipairs(SwapSites.CURATED) do
    local s, why = SwapSites.site(r.map, r.bx, r.by, r.kind, r.before, r.after, r.source)
    if s then out[#out + 1] = s else warn(why) end
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

return SwapSites
