-- gen2 story (GEN2_BRIDGE_PLAN.md §5.6, phase G2C): who stands where, the
-- current map's changed blocks and the swap sites those changes are checked
-- against. The flags are the save view's (boot.lua: every set event flag as
-- E<number>) and so are the markers (region.lua's progressMarkers), so
-- story.flags and game.status need nothing here.
--
-- Toggles. An object of a map (def.objects[i], id O<i> as the NPC observer
-- names it, RomExtractorGen2.lua:1322-1351) stands on the map while its mask
-- is clear. A map load works every mask out from the object's event flag (set
-- hides it, Events:objectVisible, src/world/gen2/Events.lua:75-79) and its
-- hour window (World:objectTimeVisible, World.lua:8154-8173); after that only
-- appear and disappear move one (World:setObjectMask, :8182-8188;
-- World:loadObjectMasks, :8201-8215), and the hourly poll re-derives the
-- unscripted ones (World:pollTimeOfDay, :11284-11316). So the current map's
-- objects follow the live masks (World.objectMasks, keyed by
-- World:objectMaskKey, :8175-8177; nil derives) and every other map's the flag
-- and the hour, as its next load will. The client's default is visible: the
-- toggles are the objects hidden now, { G2_<MAP> = { O<i> = false } }.
--
-- Blocks. Gen 1's model (observe/kb_story.lua's story.blocks): the current
-- map's live blocks are its record's own array, which World:replaceBlock edits
-- in place (:5844-5875: every changeblock, Cut, Whirlpool and map callback
-- goes through it, the originals kept in World.blockEdits until the next
-- load's restoreBlocks, :5943-5958) and Crystal's changemapblocks repaints
-- (World:changeMapBlocks, :2306-2335); MAPCALLBACK_TILES re-applies the
-- story's edits on every load. The map data the client was built from is
-- copied at mods.loaded, before any World stands (the shared records keep a
-- dropped World's edits: Game2's continueGame and returnToTitle drop it
-- unrestored, src/core/Game2.lua:343-344, :374-379), else read from the
-- dataset view, which decodes the cache afresh (src/mods/DatasetViews.lua:440-480).
--
-- Swap sites (the export's, §6). Cut trees and Whirlpools by the tileset's
-- tables over each map's blocks (FieldMoves.CUT_BLOCKS, WHIRLPOOL_BLOCKS,
-- src/world/gen2/FieldMoves.lua:301-333; animation 0 is a tree, 1 the grass
-- swirl); the changeblock and changemapblocks rows of the scripts a map's
-- callbacks, scenes, events and objects reach (data.gen2Scripts: decoded rows,
-- each naming the next script it may run by key, RomExtractorGen2.lua:3850-3910,
-- :3967-3995; the VM's block commands, src/script/gen2/Vm.lua:1286-1328); and
-- the bedroom's decorations (World:toggleMaptileDecorations, World.lua:7621-7631;
-- Decorations.tiles, src/core/gen2/Decorations.lua:504-527). Cutting grass is
-- cosmetic and grows back on the next load: no site; the runtime check knows it
-- from the World's own record of this visit's edits (cutRecords, cutSwaps), as
-- it knows Gen 1's tall grass.
--
-- Map ids leave as G2_<id> (J4) and come back without the prefix. Logs show
-- counts only.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Story = {}

-- The map whose callbacks paint the decorations (src/core/gen2/Decorations.lua:16-20)
-- and the four slots that are blocks (the other four are objects).
Story.DECORATION_MAP = "PLAYERS_HOUSE_2F"
Story.TILE_SLOTS = { bed = true, carpet = true, plant = true, poster = true }
-- A CUT_BLOCKS row is { after, animation }; animation 0 is the falling tree (FieldMoves.lua:299-300).
Story.TREE = 0
-- The most scripts one map's walk visits (a bound; the decoded table is finite).
Story.WALK_LIMIT = 4096

local floor = math.floor

local function isKey(k) return type(k) == "string" or type(k) == "number" end

function Story.install(b)
  local def = b.def

  -- ---- the map data

  local pristine = { maps = {}, captured = false, serial = 0 }
  local dsView, dsTried

  local function copyMap(mdef)
    if type(mdef) ~= "table" or type(mdef.blocks) ~= "table" then return nil end
    local w, h = tonumber(mdef.width), tonumber(mdef.height)
    if not (w and h and w >= 1 and h >= 1) then return nil end
    local blocks = {}
    for i = 1, #mdef.blocks do blocks[i] = mdef.blocks[i] end
    local bd = type(mdef.blockdata) == "table" and mdef.blockdata or nil
    return { width = floor(w), height = floor(h), blocks = blocks,
             blockdata = bd and { bank = tonumber(bd.bank), address = tonumber(bd.address) } or nil }
  end

  -- mods.loaded's payload data (src/mods/Loader.lua:2237): Game2's data, its
  -- maps in gen2Maps (src/core/Game2.lua:1136). Returns how many were copied.
  def("capturePristine", function(data)
    local maps = type(data) == "table" and data.gen2Maps or nil
    if type(maps) ~= "table" then return 0 end
    local out, n = {}, 0
    for id, mdef in pairs(maps) do
      local copy = type(id) == "string" and copyMap(mdef) or nil
      if copy then
        out[id] = copy
        n = n + 1
      end
    end
    pristine = { maps = out, captured = true, serial = pristine.serial + 1 }
    return n
  end)
  def("pristineCaptured", function() return pristine.captured end)

  -- One map's record from the dataset view (mod.datasets:open, Loader.lua:1435-1443;
  -- its registry hands out detached copies), opened once.
  local function datasetMap(id)
    if not dsTried then
      dsTried = true
      local ds = b.opts.datasets
      if type(ds) == "table" and type(ds.open) == "function" then
        local ok, view = pcall(ds.open, ds, b.adapter.gameVersion())
        if ok and type(view) == "table" and type(view.content) == "table" then dsView = view end
      end
    end
    local reg = dsView and dsView.content.maps or nil
    if not (type(reg) == "table" and type(reg.get) == "function") then return nil end
    local ok, mdef = pcall(reg.get, reg, id)
    return ok and copyMap(mdef) or nil
  end

  -- One map's pristine record by the engine's id: the boot copy, else the
  -- dataset view's (kept). Callers only read it.
  local function pristineMap(id)
    if type(id) ~= "string" then return nil end
    local m = pristine.maps[id]
    if m then return m end
    m = datasetMap(id)
    if m then pristine.maps[id] = m end
    return m
  end

  -- G2D: the export reads the same map data
  b.pristineMap = pristineMap

  local function blockAt(m, bx, by)
    if not (m and bx and by) or bx < 0 or by < 0 or bx >= m.width or by >= m.height then return nil end
    return m.blocks[by * m.width + bx + 1]
  end

  def("pristineBlocks", function(mapId)
    local m = pristineMap(b.int(mapId))
    return m and m.blocks or nil, m and m.width or nil
  end)
  def("pristineBlockAt", function(mapId, bx, by)
    if type(bx) ~= "number" or type(by) ~= "number" then return nil end
    return blockAt(pristineMap(b.int(mapId)), bx, by)
  end)

  -- ---- the current map's blocks

  -- The current map for the story: its id, size in blocks and a load serial
  -- that changes on every setMap (a new Map object each load, World.lua:10236).
  local loadMap, loadSerial = nil, 0
  def("storyMap", function()
    if not b.inField() then return nil end
    local m = b.ow().map
    local mdef = type(m.def) == "table" and m.def or nil
    local id = b.ext(m.id)
    if not (mdef and type(mdef.blocks) == "table" and id) then return nil end
    if m ~= loadMap then loadMap, loadSerial = m, loadSerial + 1 end
    return { map = id, width = tonumber(m.width) or tonumber(mdef.width) or 0,
             height = tonumber(m.height) or tonumber(mdef.height) or 0, load = loadSerial }
  end)

  local function live()
    if not b.inField() then return nil end
    local m = b.ow().map
    local blocks = type(m.def) == "table" and m.def.blocks or nil
    return type(blocks) == "table" and blocks or nil
  end

  -- A copy of the current map's live blocks (Map:blockId reads the same array).
  def("liveBlocks", function()
    local blocks = live()
    if not blocks then return nil end
    local out = {}
    for i = 1, #blocks do out[i] = blocks[i] end
    return out
  end)

  -- The live blocks that differ from `known` (the caller's plain array), as
  -- { { index, block }, ... }, or nil when none do; allocates nothing then.
  def("liveBlockChanges", function(known)
    local blocks = live()
    if not blocks or type(known) ~= "table" then return nil end
    local out
    for i = 1, #blocks do
      local v = blocks[i]
      if v ~= known[i] then
        out = out or {}
        out[#out + 1] = { i, v }
      end
    end
    return out
  end)

  -- The current tileset's Cut and Whirlpool rows (before -> { after, animation }).
  local function tilesetRows()
    local w = b.ow()
    local mdef = w and type(w.map) == "table" and type(w.map.def) == "table" and w.map.def or nil
    local FM = b.w2("FieldMoves")
    local ts = mdef and mdef.tileset
    local cut = ts and type(FM) == "table" and type(FM.CUT_BLOCKS) == "table" and FM.CUT_BLOCKS[ts] or nil
    local whirl = ts and type(FM) == "table" and type(FM.WHIRLPOOL_BLOCKS) == "table" and FM.WHIRLPOOL_BLOCKS[ts] or nil
    return type(cut) == "table" and cut or {}, type(whirl) == "table" and whirl or {}
  end

  -- This visit's Cut and Whirlpool edits on the current map, { bx, by, block =
  -- the block before }: the World's own record (blockEdits[map]: index -> the
  -- original), the edits whose original the tileset's rows swap.
  def("cutRecords", function(mapId)
    local out = {}
    if not b.inField() then return out end
    local w = b.ow()
    if b.ext(w.map.id) ~= mapId then return out end
    local edits = type(w.blockEdits) == "table" and w.blockEdits[w.map.id] or nil
    local width = tonumber(w.map.width) or 0
    if type(edits) ~= "table" or width < 1 then return out end
    local cut, whirl = tilesetRows()
    local list = {}
    for i, original in pairs(edits) do
      if type(i) == "number" and (cut[original] ~= nil or whirl[original] ~= nil) then list[#list + 1] = i end
    end
    table.sort(list)
    for _, i in ipairs(list) do
      out[#out + 1] = { bx = (i - 1) % width, by = floor((i - 1) / width), block = edits[i] }
    end
    return out
  end)

  -- The current tileset's swaps, before -> after (FieldMoves.blockReplacement's rows).
  def("cutSwaps", function()
    local out = {}
    local cut, whirl = tilesetRows()
    for before, row in pairs(whirl) do if type(row) == "table" then out[before] = row[1] end end
    for before, row in pairs(cut) do if type(row) == "table" then out[before] = row[1] end end
    return out
  end)

  -- Gen 1's field tables (card-key doors, the poster...): none here; the sites are swapSites'.
  def("swapField", function() return {} end)

  -- ---- toggles

  -- An object a flag or an hour window can take off its map.
  local function canHide(obj)
    local flag = obj.eventFlag
    if flag ~= nil and flag ~= 0xFFFF then return true end
    local hours = type(obj.hours) == "table" and obj.hours or nil
    if not hours then return false end
    local h1, h2 = tonumber(hours[1]) or -1, tonumber(hours[2]) or -1
    if h1 == -1 then return h2 ~= -1 end
    return h1 ~= h2
  end

  -- Every hideable object of every map, once per data table: { map, ext, name, obj }.
  local hideCache = { maps = nil, list = nil }
  local function hideable()
    local g = b.game
    local maps = type(g) == "table" and type(g.data) == "table" and g.data.gen2Maps or nil
    if hideCache.list and hideCache.maps == maps then return hideCache.list end
    local list = {}
    for id, mdef in pairs(type(maps) == "table" and maps or {}) do
      local ext = type(id) == "string" and b.ext(id) or nil
      if ext and type(mdef) == "table" and type(mdef.objects) == "table" then
        for i, obj in ipairs(mdef.objects) do
          if type(obj) == "table" and canHide(obj) then
            list[#list + 1] = { map = id, ext = ext, name = ("O%d"):format(tonumber(obj.index) or i), obj = obj }
          end
        end
      end
    end
    hideCache.maps, hideCache.list = maps, list
    return list
  end

  -- The clock as World:objectTimeVisible asks it, read once per call:
  -- World:hour and World:clockTimeMask (:1653-1659, :8146-8152).
  local function clockOf(w)
    local okH, hour = b.method(w, "hour")
    local okM, mask = b.method(w, "clockTimeMask")
    hour, mask = okH and tonumber(hour) or 12, okM and tonumber(mask) or 2
    return { hour = function() return hour end, clockTimeMask = function() return mask end }
  end

  def("storyToggles", function()
    local out = {}
    if not b.inField() then return out, 0 end
    local w = b.ow()
    local World = b.w2("World")
    local timeFn = type(World) == "table" and type(World.objectTimeVisible) == "function"
      and World.objectTimeVisible or nil
    local clock = clockOf(w)
    local events = w.events
    local function visible(obj)
      local okF, byFlag = b.method(events, "objectVisible", obj.eventFlag)
      if okF and byFlag == false then return false end
      if not timeFn then return true end
      local okT, byTime = pcall(timeFn, clock, obj)
      return not (okT and byTime == false)
    end
    local function hide(ext, name)
      local m = out[ext]
      if not m then
        m = {}
        out[ext] = m
      end
      m[name] = false
    end
    -- the current map: the live masks (nil derives, as World:loadObjectMasks does)
    local cur, mdef = w.map.id, type(w.map.def) == "table" and w.map.def or nil
    local curExt = b.ext(cur)
    local masks = type(w.objectMasks) == "table" and w.objectMasks or {}
    if curExt and mdef then
      for i, obj in ipairs(type(mdef.objects) == "table" and mdef.objects or {}) do
        if type(obj) == "table" then
          local ok, key = b.method(w, "objectMaskKey", obj, i)
          if not ok or type(key) ~= "string" then key = ("%s:%d"):format(cur, tonumber(obj.index) or i) end
          local masked = masks[key]
          if masked == nil then masked = not visible(obj) end
          if masked == true then hide(curExt, ("O%d"):format(tonumber(obj.index) or i)) end
        end
      end
    end
    -- every other map: the flag and the hour
    for _, e in ipairs(hideable()) do
      if e.map ~= cur and not visible(e.obj) then hide(e.ext, e.name) end
    end
    return out, 0
  end)

  -- ---- swap sites

  -- The keys of the scripts one map runs itself: its callbacks, scene scripts,
  -- coord and bg events and objects (trainers' included).
  local function scriptRoots(mdef)
    local keys = {}
    local function each(list, f)
      for _, v in pairs(type(list) == "table" and list or {}) do
        if type(v) == "table" then f(v) end
      end
    end
    local function add(k) if isKey(k) then keys[#keys + 1] = k end end
    each(mdef.callbacks, function(cb) add(cb.scriptKey) end)
    each(mdef.sceneScripts, function(sc) add(sc.scriptKey) end)
    each(mdef.coordEvents, function(ev) add(ev.scriptKey) end)
    each(mdef.bgEvents, function(ev) add(ev.scriptKey) end)
    each(mdef.objects, function(o)
      add(o.scriptKey)
      if type(o.trainer) == "table" then add(o.trainer.scriptKey) end
    end)
    return keys
  end

  -- Every command of the scripts `roots` reach (a command's `script` names one
  -- it may run next: jumps, calls, branches, the standard scripts).
  local function walkScripts(scripts, roots, visit)
    local seen, queue, head = {}, {}, 1
    for _, k in ipairs(roots) do
      if not seen[k] then
        seen[k] = true
        queue[#queue + 1] = k
      end
    end
    while head <= #queue and head <= Story.WALK_LIMIT do
      local list = scripts[queue[head]]
      head = head + 1
      if type(list) == "table" then
        for _, cmd in ipairs(list) do
          if type(cmd) == "table" then
            visit(cmd)
            local k = cmd.script
            if isKey(k) and not seen[k] then
              seen[k] = true
              queue[#queue + 1] = k
            end
          end
        end
      end
    end
  end

  -- World:blockdataAt's lookup (World.lua:2277-2287) over the map data: the
  -- blocks a changemapblocks pointer lands in, and the offset into them.
  local function blockdataAt(ids, bank, address)
    for _, id in ipairs(ids) do
      local m = pristineMap(id)
      local bd = m and m.blockdata
      if bd and bd.bank == bank and bd.address and address >= bd.address and address < bd.address + #m.blocks then
        return m.blocks, address - bd.address
      end
    end
    return nil
  end

  local siteCache = {}
  -- The swap sites as { map, bx, by, kind, before, after } (before the map
  -- data's block, after the block the change writes), sorted; kinds cut,
  -- whirlpool and script. nil out of a game. Worked out once per data.
  def("swapSites", function()
    local g = b.game
    local data = type(g) == "table" and type(g.data) == "table" and g.data or nil
    local maps = data and data.gen2Maps or nil
    if type(maps) ~= "table" then return nil end
    local scripts = type(data.gen2Scripts) == "table" and data.gen2Scripts or {}
    if siteCache.list and siteCache.maps == maps and siteCache.scripts == scripts
        and siteCache.serial == pristine.serial then
      return siteCache.list
    end
    local FM = b.w2("FieldMoves")
    local cutT = type(FM) == "table" and type(FM.CUT_BLOCKS) == "table" and FM.CUT_BLOCKS or {}
    local whirlT = type(FM) == "table" and type(FM.WHIRLPOOL_BLOCKS) == "table" and FM.WHIRLPOOL_BLOCKS or {}
    local ids = {}
    for id in pairs(maps) do
      if type(id) == "string" then ids[#ids + 1] = id end
    end
    table.sort(ids)
    local list, seen = {}, {}
    local counts = { cut = 0, whirlpool = 0, script = 0, decoration = 0, mapBlocks = 0, maps = 0 }
    local function add(id, m, bx, by, kind, after, tag)
      bx, by, after = tonumber(bx), tonumber(by), tonumber(after)
      local before = blockAt(m, bx, by)
      if not (before and after) or before == after then return end
      local ext = b.ext(id)
      local k = table.concat({ tostring(ext), bx, by, kind, before, after }, ":")
      if ext and not seen[k] then
        seen[k] = true
        list[#list + 1] = { map = ext, bx = bx, by = by, kind = kind, before = before, after = after }
        counts[tag or kind] = counts[tag or kind] + 1
      end
    end
    for _, id in ipairs(ids) do
      local mdef, m = maps[id], pristineMap(id)
      if type(mdef) == "table" and m then
        counts.maps = counts.maps + 1
        -- Cut trees and Whirlpools: every block of the map the tileset's rows swap
        local cut, whirl = cutT[mdef.tileset], whirlT[mdef.tileset]
        for i, block in ipairs(m.blocks) do
          local bx, by = (i - 1) % m.width, floor((i - 1) / m.width)
          local c = type(cut) == "table" and cut[block] or nil
          if type(c) == "table" and tonumber(c[2]) == Story.TREE then add(id, m, bx, by, "cut", c[1]) end
          local wp = type(whirl) == "table" and whirl[block] or nil
          if type(wp) == "table" then add(id, m, bx, by, "whirlpool", wp[1]) end
        end
        -- the scripts' block changes (changeblock's x and y are cells, Vm.lua:1287-1306)
        walkScripts(scripts, scriptRoots(mdef), function(cmd)
          local args = type(cmd.args) == "table" and cmd.args or {}
          if cmd.op == "changeblock" then
            local x, y = tonumber(cmd.x or args[1]), tonumber(cmd.y or args[2])
            if x and y then add(id, m, floor(x / 2), floor(y / 2), "script", cmd.block or args[3]) end
          elseif cmd.op == "changemapblocks" then
            local bank = tonumber(cmd.bank or args[1])
            local address = tonumber(cmd.address) or ((tonumber(args[2]) or 0) + (tonumber(args[3]) or 0) * 0x100)
            local src, offset = blockdataAt(ids, bank, address)
            local count = m.width * m.height
            if src and offset + count <= #src then
              for i = 1, count do
                add(id, m, (i - 1) % m.width, floor((i - 1) / m.width), "script", src[offset + i], "mapBlocks")
              end
            end
          end
        end)
      end
    end
    -- the bedroom's decorations: every block a maptile slot's set-up writes
    local D = b.mod("src.core.gen2.Decorations")
    local room = maps[Story.DECORATION_MAP] ~= nil and pristineMap(Story.DECORATION_MAP) or nil
    if room and type(D) == "table" and type(D.tiles) == "function" and type(D.ATTRIBUTES) == "table" then
      local actions = type(D.ACTIONS) == "table" and D.ACTIONS or {}
      for decoId, attr in pairs(D.ATTRIBUTES) do
        local act = type(attr) == "table" and actions[attr.action] or nil
        local slot = type(act) == "table" and not act.put and act.slot or nil
        if type(decoId) == "number" and Story.TILE_SLOTS[slot] then
          local ok, tiles = pcall(D.tiles, { [slot] = decoId })
          for _, t in ipairs(ok and type(tiles) == "table" and tiles or {}) do
            if type(t) == "table" then add(Story.DECORATION_MAP, room, t.x, t.y, "script", t.block, "decoration") end
          end
        end
      end
    end
    table.sort(list, function(x, y)
      if x.map ~= y.map then return x.map < y.map end
      if x.by ~= y.by then return x.by < y.by end
      if x.bx ~= y.bx then return x.bx < y.bx end
      if x.kind ~= y.kind then return x.kind < y.kind end
      if x.before ~= y.before then return x.before < y.before end
      return x.after < y.after
    end)
    siteCache = { maps = maps, scripts = scripts, serial = pristine.serial, list = list }
    if b.log then
      b.log.info("story.sites", { sites = #list, cut = counts.cut, whirlpool = counts.whirlpool,
                                  script = counts.script, decoration = counts.decoration,
                                  mapBlocks = counts.mapBlocks, maps = counts.maps,
                                  pristine = pristine.captured })
    end
    return list
  end)
end

return Story
