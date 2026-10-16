-- kb_snapshot: the full story view sent as `snapshot` after hello.ok and on
-- resync (docs/PROTOCOL.md "Session", protocol/messages/snapshot.schema.json).
--
-- Built from plain inputs (kb_engine facts and readers, the save table, two
-- lookups), so it never touches the engine. Every value is checked against
-- the schema's shape on the way in; anything that doesn't fit is left out
-- and counted, never sent malformed.
--
-- toggles, blocks and lit come from the story observer (M8,
-- observe/kb_story.lua, docs/PROTOCOL.md "Story rules (M8)"): every object
-- whose effective visibility differs from its default, the current map's
-- live blocks that differ from the map data, the dark maps Flash lit. ui is
-- never filled (open UIs follow as ui.open). The party is the hidden game's
-- own party; members a party.sync named carry their Cobblemon uid (M5),
-- gifts Cobblemon hasn't confirmed yet don't.

local Snapshot = {}

local SYMBOL = "^[A-Za-z0-9_]+$"
local UPPER = "^[A-Z0-9_]+$"
local DIRS = { up = true, down = true, left = true, right = true }
-- MG7 (G3F): Gen 3's bad poison (TOX) is poison to the protocol
local STATUS = { SLP = "slp", PSN = "psn", BRN = "brn", FRZ = "frz", PAR = "par", TOX = "psn" }

local floor = math.floor

local function sym(v, pattern)
  return type(v) == "string" and #v > 0 and #v <= 64 and v:find(pattern or SYMBOL) ~= nil
end

local function int(v, lo, hi)
  if type(v) ~= "number" or v ~= v or v ~= floor(v) then return nil end
  if v < lo then return lo end
  if v > hi then return hi end
  return v
end

-- an id-like number (dex, move, level): out of range is wrong, not clamped
local function exact(v, lo, hi)
  if type(v) ~= "number" or v ~= floor(v) or v < lo or v > hi then return nil end
  return v
end

local function coord(v)
  return type(v) == "number" and v == floor(v) and v >= -4096 and v <= 4096
end

local function place(p)
  return type(p) == "table" and sym(p.map, UPPER) and coord(p.x) and coord(p.y) and DIRS[p.facing] ~= nil
end

local function sortedKeys(t, accept)
  local out = {}
  for k, v in pairs(type(t) == "table" and t or {}) do
    if accept(k, v) then out[#out + 1] = k end
  end
  table.sort(out)
  return out
end

-- ctx:
--   json, Player            kb_json and the kb_player module
--   facts                   kb_engine.observe()
--   save                    the live save table, or nil
--   darkMaps, badgeItems    lists from kb_engine
--   npcs, npcMap            kb_engine.npcs(), or nil
--   dexOf(species)          national dex number of a species id, or nil
--   moveNum(moveId)         Gen 1 move number of a move id, or nil
--   uidOf(mon)              a party member's Cobblemon uid, or nil
--   story                   kb_story's rebase() parts { toggles, blocks, lit },
--                           or nil (then the raw save.objectToggles, no
--                           blocks, and every dark map while flashLit)
--   routes                  Wild Routes (WR2): act/kb_route.lua's progress(),
--                           already shaped (a json object), or nil
--   away                    MG2: act/kb_region.lua's marker { map, x, y }, the
--                           cell the hidden player was parked on (present
--                           while the hold is up, or left in the save by a
--                           link drop), or nil
-- Returns the snapshot data and the number of values left out.
local UID = "^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$"

function Snapshot.build(ctx)
  local json, facts = ctx.json, ctx.facts
  local save = type(ctx.save) == "table" and ctx.save or {}
  local dropped = 0
  local dexOf = ctx.dexOf or function() return nil end
  -- MG7 (G3F): the running game's highest dex number (its compat; Gen 1's 151)
  local maxDex = type(ctx.compat) == "table" and tonumber(ctx.compat.maxDex) or 151
  local moveNum = ctx.moveNum or function() return nil end
  local uidOf = ctx.uidOf or function() return nil end

  -- Outside the overworld (title screen), fall back to the save's position:
  -- the boot skeleton's spawn, or the loaded save's. ingame tells the client
  -- whether the position is live, so it never moves the player to the
  -- fallback.
  local player = ctx.Player.position(facts)
  local ingame = place(player) and true or false
  if not ingame then
    local sp = save.player
    player = type(sp) == "table"
      and { map = sp.map, x = sp.x, y = sp.y, facing = sp.facing } or nil
  end
  if not place(player) then
    dropped = dropped + 1
    player = { map = "NONE", x = 0, y = 0, facing = "down" }
  end

  local flags = sortedKeys(save.flags, function(k, v) return v == true and sym(k) end)

  local story = type(ctx.story) == "table" and ctx.story or nil
  local toggles = story and story.toggles or json.object()
  if not story then
    for map, objects in pairs(type(save.objectToggles) == "table" and save.objectToggles or {}) do
      if sym(map, UPPER) and type(objects) == "table" then
        local inner = json.object()
        for name, visible in pairs(objects) do
          if sym(name) and type(visible) == "boolean" then inner[name] = visible
          else dropped = dropped + 1 end
        end
        toggles[map] = inner
      else
        dropped = dropped + 1
      end
    end
  end

  local badgeSet, badges = {}, {}
  local inventory = type(save.inventory) == "table" and save.inventory or {}
  for _, item in ipairs(ctx.badgeItems or {}) do
    badgeSet[item] = true
    -- MG9: Gen 2 has 16 (Johto's 8 and Kanto's 8)
    if inventory[item] and #badges < 16 then badges[#badges + 1] = item end
  end
  local bag = json.object()
  for item, qty in pairs(inventory) do
    if not badgeSet[item] then
      local n = int(qty, 0, 9999)
      if sym(item, UPPER) and n then bag[item] = n else dropped = dropped + 1 end
    end
  end

  local lit = {}
  if story then
    lit = story.lit
  else
    -- every dark map while flashLit, a map the save's litMaps names as that says (SE7, kb_story's litSet)
    local per = type(save.litMaps) == "table" and save.litMaps or nil
    for _, id in ipairs(ctx.darkMaps or {}) do
      local on = save.flashLit == true
      if per and per[id] ~= nil then on = per[id] == true end
      if on then lit[#lit + 1] = id end
    end
  end

  local party = {}
  for _, mon in ipairs(type(save.party) == "table" and save.party or {}) do
    if #party >= 6 then break end
    local dex = exact(type(mon) == "table" and dexOf(mon.species) or nil, 1, 2000)
    local level = type(mon) == "table" and exact(mon.level, 1, 100) or nil
    if dex and level then
      local moves = {}
      for _, mv in ipairs(type(mon.moves) == "table" and mon.moves or {}) do
        -- Gen 1 keeps { id = ... } per move; MG7 (G3F): Gen 3 the move numbers themselves
        local id = type(mv) == "table" and mv.id or mv
        local num = exact(id ~= nil and moveNum(id) or nil, 1, 2000)
        if num and #moves < 4 then moves[#moves + 1] = num end
      end
      local entry = { dex = dex, level = level, moves = json.array(moves) }
      local uid = uidOf(mon)
      if type(uid) == "string" and uid:find(UID) and uid == uid:lower() then entry.uid = uid end
      local hp = int(mon.hp, 0, 9999)
      local maxhp = int(type(mon.stats) == "table" and mon.stats.hp or mon.maxHp, 1, 9999)
      if hp and maxhp then entry.hp, entry.maxhp = hp, maxhp end
      if STATUS[mon.status] then entry.status = STATUS[mon.status] end
      party[#party + 1] = entry
    else
      dropped = dropped + 1
    end
  end

  local function dexList(set)
    local seen, out = {}, {}
    for species, v in pairs(type(set) == "table" and set or {}) do
      local n = v and exact(dexOf(species), 1, maxDex) or nil
      if n and not seen[n] then
        seen[n] = true
        out[#out + 1] = n
      end
    end
    table.sort(out)
    return json.array(out)
  end
  local pokedex = type(save.pokedex) == "table" and save.pokedex or {}

  local npcList = {}
  for _, npc in ipairs(ctx.npcs or {}) do
    if #npcList < 64 and sym(npc.id) and sym(npc.sprite, UPPER) and coord(npc.x) and coord(npc.y)
        and DIRS[npc.facing] then
      npcList[#npcList + 1] = {
        id = npc.id, sprite = npc.sprite, kind = npc.kind,
        x = npc.x, y = npc.y, facing = npc.facing, moving = npc.moving == true,
      }
    elseif npc then
      dropped = dropped + 1
    end
  end
  local npcMap = sym(ctx.npcMap, UPPER) and ctx.npcMap or player.map

  local data = json.object({
    ingame = ingame,
    player = player,
    state = ctx.Player.state(facts),
    flags = json.array(flags),
    toggles = toggles,
    blocks = story and story.blocks or json.array({}),
    -- MG7 (G3C): Gen 3's changed cells of the current map (absent on Gen 1)
    cells = story and story.cells or nil,
    lit = json.array(lit),
    bag = bag,
    money = int(save.money, 0, 999999) or 0,
    coins = int(save.coins, 0, 9999) or 0,
    badges = json.array(badges),
    party = json.array(party),
    dex = { seen = dexList(pokedex.seen), owned = dexList(pokedex.owned) },
    npcs = { map = npcMap, list = json.array(npcList) },
  })
  if type(ctx.routes) == "table" then data.routes = ctx.routes end
  local away = ctx.away
  if type(away) == "table" then
    if sym(away.map, UPPER) and coord(away.x) and coord(away.y) then
      data.away = { map = away.map, x = away.x, y = away.y }
    else
      dropped = dropped + 1
    end
  end
  return data, dropped
end

return Snapshot
