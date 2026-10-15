-- kb_story: the story diffs (docs/PROTOCOL.md "Story" and "Story rules
-- (M8)", docs/PLAN.md section 4.1 "Story diffs").
--
--   story.flags{set, clear}            the diff of save.flags (flags set to
--                                      true / no longer true), every EVERY
--                                      logic steps, when a script ends and on
--                                      a map load
--   story.toggles{map, obj, visible}   one object's EFFECTIVE visibility
--                                      changed (kb_engine.storyToggles: the
--                                      engine's objectVisible over toggles,
--                                      items taken and beaten statics); same
--                                      cadence as the flags
--   story.blocks{map, changes}         the current map's live blocks that
--                                      differ from what the client holds:
--                                      on a map load every block that differs
--                                      from the map data the world was built
--                                      from (a same-map reload: every block
--                                      that changed since, so regrown trees
--                                      go out too), then every change during
--                                      the visit, checked every step
--   story.cells{map, cells}            (MG7, G3C) Gen 3's runtime terrain: the
--                                      current map's cells whose metatile
--                                      differs from what the client holds
--                                      (kb_engine.storyCells: the layout's
--                                      overrides; a cell that went back is
--                                      sent with its own metatile, cellBase),
--                                      on a map load and every step after
--   story.lit{map, lit}                a dark map lit by Flash, or reset: the
--                                      lit set is every dark map while
--                                      save.flashLit holds (the engine keeps
--                                      the light across dark floors and drops
--                                      it on any other map, OC:514-522)
--
-- The snapshot carries the same state in full (rebase): every effective
-- visibility that differs from its default (`toggles`), the current map's
-- differing blocks (`blocks`), on Gen 3 its changed cells (`cells`) and the
-- lit maps (`lit`); diffs start from it.
-- Nothing is diffed while no client or test session is connected (the next
-- one starts with a snapshot).
--
-- Every block change must be a known one: a swap site's state
-- (kb_swapsites.runtimeIndex; MG9: an adapter's own sites, engine.swapSites,
-- through kb_swapsites.indexSites) or a Cut the engine recorded (ow.cutBlocks,
-- tall grass included; Gen 2: the World's record of this visit's edits). Anything else is still reported, and counted and
-- logged as the error code story.unknown_block (map symbol and count only).
-- Logs show codes, symbols and counts only.

local Story = {}

Story.EVERY = 10 -- logic steps between flag and toggle diffs
Story.MAX_CELLS = 1024 -- cells in one story.cells (more go in the next message)

local floor = math.floor
local SYMBOL = "^[A-Za-z0-9_]+$"
local MAP_ID = "^[A-Z0-9_]+$"

local function sym(v, pattern, max)
  return type(v) == "string" and #v > 0 and #v <= (max or 64) and v:find(pattern or SYMBOL) ~= nil
end

local function sortedKeys(t)
  local out = {}
  for k in pairs(t) do out[#out + 1] = k end
  table.sort(out)
  return out
end

-- The flags set to true, as a set (save.flags: name -> true, cleared ones
-- nil or false; SaveData's skeleton, src/script/Flags.lua:1-30).
function Story.flagSet(save)
  local out = {}
  for k, v in pairs(type(save) == "table" and type(save.flags) == "table" and save.flags or {}) do
    if v == true and sym(k) then out[k] = true end
  end
  return out
end

-- The lit dark maps: all of them while save.flashLit holds.
function Story.litSet(save, darkMaps)
  local out = {}
  if type(save) == "table" and save.flashLit then
    for _, id in ipairs(darkMaps or {}) do
      if sym(id, MAP_ID) then out[id] = true end
    end
  end
  return out
end

-- opts:
--   engine     kb_engine instance (storyMap, pristineBlocks, liveBlocks,
--              liveBlockChanges, cutRecords, cutSwaps, swapField,
--              pristineBlockAt, storyToggles, save, darkMaps, gameVersion;
--              MG9: swapSites, when the adapter has sites of its own)
--   SwapSites  the kb_swapsites module
--   json       kb_json
--   log        kb_log logger (optional)
function Story.new(opts)
  local engine, SwapSites, json, log = opts.engine, opts.SwapSites, opts.json, opts.log
  local obs = {}
  local steps = 0
  local base                      -- { flags, toggles, lit }: what the client has been told
  local view = {}                 -- the current map's blocks: { map, load, width, known }
  local cellView = {}             -- MG7: the current map's cells the client holds: { map, known = { key -> metatile } }
  local sites                     -- SwapSites.runtimeIndex, built on first need
  local lastRunner = false
  local counts = {
    flagMsgs = 0, toggleMsgs = 0, blockMsgs = 0, litMsgs = 0, mapLoads = 0, blockChanges = 0,
    unknownBlocks = 0, noPristine = 0, toggleSkipped = 0, sites = 0, flagSet = 0, flagCleared = 0,
    cellMsgs = 0, cellChanges = 0,
  }

  local function siteIndex()
    if sites == nil then
      -- MG9 (G2C): an adapter with sites of its own (Gen 2's, engine.swapSites) is checked against
      -- those; else Gen 1's field tables and curated rows
      local okOwn, own = false, nil
      if type(engine.swapSites) == "function" then okOwn, own = pcall(engine.swapSites) end
      local ok, index, n
      if okOwn and type(own) == "table" then
        ok, index, n = pcall(SwapSites.indexSites, own)
      else
        ok, index, n = pcall(SwapSites.runtimeIndex, engine.swapField(), engine.gameVersion(),
          engine.pristineBlockAt)
      end
      if ok then
        sites, counts.sites = index, n or 0
      else
        sites = {}
        if log then log.once("story.sites", "warn", "story.sites_failed", {}) end
      end
    end
    return sites
  end

  -- true when the block at index i of the current map may now be `block`:
  -- back to the map data (a regrown tree, a re-closed door), a swap site's
  -- state, or what the engine's cut record explains (the cut block,
  -- OC:3430-3434)
  local function known(map, width, i, block, cuts, swaps, pristine)
    if pristine and pristine[i] == block then return true end
    local bx, by = (i - 1) % width, floor((i - 1) / width)
    if SwapSites.allows(siteIndex(), map, bx, by, block) then return true end
    for _, c in ipairs(cuts) do
      if c.bx == bx and c.by == by and (swaps[c.block] == block or c.block == block) then return true end
    end
    return false
  end

  local function checkChanges(map, width, list)
    local cuts, swaps = engine.cutRecords(map), engine.cutSwaps()
    local pristine = engine.pristineBlocks(map)
    local unknown = 0
    for _, i in ipairs(list) do
      local block = view.known[i]
      if not known(map, width, i, block, cuts, swaps, pristine) then unknown = unknown + 1 end
    end
    if unknown > 0 then
      counts.unknownBlocks = counts.unknownBlocks + unknown
      if log then
        log.once("story.unknown:" .. map, "error", "story.unknown_block",
          { map = map, count = unknown, total = counts.unknownBlocks })
      end
    end
  end

  local function blockChanges(map, width, list)
    local changes = {}
    for _, i in ipairs(list) do
      local bx, by = (i - 1) % width, floor((i - 1) / width)
      local block = view.known[i]
      if bx <= 255 and by <= 255 and type(block) == "number" and block >= 0 and block <= 255
          and block == floor(block) then
        changes[#changes + 1] = { bx = bx, by = by, block = block }
      end
    end
    return changes
  end

  -- The current map's blocks: a (re)load diffs the live blocks against what
  -- the client holds for it (the map data for a new map, the blocks it was
  -- told for a same-map reload); otherwise the changes since the last step.
  local function stepBlocks(out)
    local sm = engine.storyMap()
    if not sm then
      view = {}
      return false
    end
    local width = sm.width
    if width < 1 then return false end
    local list = {}
    local loaded = sm.load ~= view.load or sm.map ~= view.map
    if loaded then
      counts.mapLoads = counts.mapLoads + 1
      local live = engine.liveBlocks() or {}
      local belief
      if sm.map == view.map and view.known then
        belief = view.known
      else
        belief = engine.pristineBlocks(sm.map)
        if not belief then
          counts.noPristine = counts.noPristine + 1
          if log then log.once("story.no_pristine:" .. sm.map, "warn", "story.no_pristine", { map = sm.map }) end
        end
      end
      if belief then
        for i = 1, #live do
          if live[i] ~= belief[i] then list[#list + 1] = i end
        end
      end
      view = { map = sm.map, load = sm.load, width = width, known = live }
    else
      for _, c in ipairs(engine.liveBlockChanges(view.known) or {}) do
        view.known[c[1]] = c[2]
        list[#list + 1] = c[1]
      end
    end
    if #list > 0 then
      checkChanges(sm.map, width, list)
      local changes = blockChanges(sm.map, width, list)
      if #changes > 0 then
        out[#out + 1] = { "story.blocks", { map = sm.map, changes = json.array(changes) }, "story" }
        counts.blockMsgs = counts.blockMsgs + 1
        counts.blockChanges = counts.blockChanges + #changes
      end
    end
    return loaded
  end

  -- MG7 (G3C): the current map's cells (Gen 3; an engine without them gives nil). What the client holds
  -- is the map's own cells plus the ones told during this visit: a new map starts from nothing told, a
  -- same-map reload from what was told before it, so a cell its scripts no longer change goes back.
  local function cellKey(k) return k % 1024, floor(k / 1024) end
  local function readCells()
    local sc = engine.storyCells and engine.storyCells() or nil
    if type(sc) ~= "table" or not sym(sc.map, MAP_ID) or type(sc.cells) ~= "table" then return nil end
    local now = {}
    for k, mid in pairs(sc.cells) do
      k, mid = tonumber(k), tonumber(mid)
      if k and mid and k >= 0 and k == floor(k) and mid >= 0 and mid <= 1023 and mid == floor(mid) then
        now[k] = mid
      end
    end
    return sc.map, now
  end

  local function stepCells(out)
    local map, now = readCells()
    if not map then
      cellView = {}
      return
    end
    local prev = cellView.map == map and cellView.known or {}
    local keys = {}
    for k, mid in pairs(now) do
      if prev[k] ~= mid then keys[#keys + 1] = k end
    end
    for k in pairs(prev) do
      if now[k] == nil then keys[#keys + 1] = k end
    end
    cellView = { map = map, known = now }
    if #keys == 0 then return end
    table.sort(keys)
    local cells = {}
    for _, k in ipairs(keys) do
      local x, y = cellKey(k)
      local mid = now[k]
      if mid == nil then mid = tonumber(engine.cellBase(x, y)) end
      if mid and mid >= 0 and mid <= 1023 then cells[#cells + 1] = { x = x, y = y, metatile = mid } end
    end
    for i = 1, #cells, Story.MAX_CELLS do
      local part = {}
      for j = i, math.min(#cells, i + Story.MAX_CELLS - 1) do part[#part + 1] = cells[j] end
      out[#out + 1] = { "story.cells", { map = map, cells = json.array(part) }, "story" }
      counts.cellMsgs = counts.cellMsgs + 1
      counts.cellChanges = counts.cellChanges + #part
    end
  end

  local function stepLit(out, save)
    local now = Story.litSet(save, engine.darkMaps())
    for _, id in ipairs(sortedKeys(now)) do
      if not base.lit[id] then
        out[#out + 1] = { "story.lit", { map = id, lit = true }, "story" }
        counts.litMsgs = counts.litMsgs + 1
      end
    end
    for _, id in ipairs(sortedKeys(base.lit)) do
      if not now[id] then
        out[#out + 1] = { "story.lit", { map = id, lit = false }, "story" }
        counts.litMsgs = counts.litMsgs + 1
      end
    end
    base.lit = now
  end

  local function stepFlags(out, save)
    local now = Story.flagSet(save)
    local set, clear = {}, {}
    for k in pairs(now) do
      if not base.flags[k] then set[#set + 1] = k end
    end
    for k in pairs(base.flags) do
      if not now[k] then clear[#clear + 1] = k end
    end
    table.sort(set)
    table.sort(clear)
    base.flags = now
    if #set + #clear > 0 then
      out[#out + 1] = { "story.flags", { set = json.array(set), clear = json.array(clear) }, "story" }
      counts.flagMsgs = counts.flagMsgs + 1
      counts.flagSet, counts.flagCleared = counts.flagSet + #set, counts.flagCleared + #clear
    end
  end

  local function currentToggles()
    local now, skipped = engine.storyToggles()
    counts.toggleSkipped = skipped or 0
    local out = {}
    for map, names in pairs(type(now) == "table" and now or {}) do
      if sym(map, MAP_ID) and type(names) == "table" then
        local inner = {}
        for name, visible in pairs(names) do
          if sym(name, SYMBOL, 96) and type(visible) == "boolean" then inner[name] = visible end
        end
        if next(inner) ~= nil then out[map] = inner end
      end
    end
    return out
  end

  local function stepToggles(out)
    local now = currentToggles()
    local function send(map, name, visible)
      out[#out + 1] = { "story.toggles", { map = map, obj = name, visible = visible }, "story" }
      counts.toggleMsgs = counts.toggleMsgs + 1
    end
    local maps = {}
    for map in pairs(now) do maps[map] = true end
    for map in pairs(base.toggles) do maps[map] = true end
    for _, map in ipairs(sortedKeys(maps)) do
      local was, is = base.toggles[map] or {}, now[map] or {}
      local names = {}
      for name in pairs(was) do names[name] = true end
      for name in pairs(is) do names[name] = true end
      for _, name in ipairs(sortedKeys(names)) do
        if is[name] ~= nil and is[name] ~= was[name] then
          send(map, name, is[name])
        elseif is[name] == nil and was[name] ~= nil then
          send(map, name, not was[name]) -- back to its default
        end
      end
    end
    base.toggles = now
  end

  -- After a snapshot: the full state the client now holds, as the
  -- snapshot's toggles (a json object map -> name -> visible), blocks (the
  -- current map's differing blocks) and lit (sorted map ids).
  function obs.rebase()
    local save = engine.saveView()
    base = { flags = Story.flagSet(save), toggles = currentToggles(), lit = Story.litSet(save, engine.darkMaps()) }
    lastRunner = false
    local toggles = json.object()
    for _, map in ipairs(sortedKeys(base.toggles)) do
      local inner = json.object()
      for name, visible in pairs(base.toggles[map]) do inner[name] = visible end
      toggles[map] = inner
    end
    local blocks = {}
    local sm = engine.storyMap()
    view = {}
    if sm and sm.width >= 1 then
      local live = engine.liveBlocks() or {}
      local pristine = engine.pristineBlocks(sm.map)
      view = { map = sm.map, load = sm.load, width = sm.width, known = live }
      if pristine then
        local list = {}
        for i = 1, #live do
          if live[i] ~= pristine[i] then list[#list + 1] = i end
        end
        for _, c in ipairs(blockChanges(sm.map, sm.width, list)) do
          blocks[#blocks + 1] = { map = sm.map, bx = c.bx, by = c.by, block = c.block }
        end
      end
    end
    local result = { toggles = toggles, blocks = json.array(blocks), lit = json.array(sortedKeys(base.lit)) }
    -- MG7 (G3C): the current map's changed cells, in full
    cellView = {}
    local map, now = readCells()
    if map then
      local cells, keys = {}, {}
      for k in pairs(now) do keys[#keys + 1] = k end
      table.sort(keys)
      for _, k in ipairs(keys) do
        local x, y = cellKey(k)
        cells[#cells + 1] = { map = map, x = x, y = y, metatile = now[k] }
      end
      cellView = { map = map, known = now }
      result.cells = json.array(cells)
    end
    return result
  end

  -- One logic step. f: kb_engine.observe(); out: this step's messages;
  -- link: { live, generation }.
  function obs.step(f, out, link)
    steps = steps + 1
    if not (link and link.live) or base == nil then
      lastRunner = false
      return
    end
    local save = engine.saveView()
    local loaded = false
    if f.inGame then
      loaded = stepBlocks(out)
      stepCells(out)
    else
      view, cellView = {}, {}
    end
    stepLit(out, save)
    local runner = f.runner == true
    local scriptEnded = lastRunner and not runner
    lastRunner = runner
    if steps % Story.EVERY == 0 or scriptEnded or loaded then
      stepFlags(out, save)
      stepToggles(out)
    end
  end

  function obs.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    out.pristine = engine.pristineCaptured() == true
    return out
  end

  return obs
end

return Story
