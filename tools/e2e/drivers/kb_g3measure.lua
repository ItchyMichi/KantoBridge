-- Kanto Bridge measurement driver (MG7 G3D, docs/specs/GEN3_BRIDGE_PLAN.md §5
-- "measure first"): sizes the Gen 3 export before it is written. Runs at the
-- title of the test install's Emerald import (no save is touched) and reads
-- the engine's own prepared data, the way the field renderer gets it:
--
--   maps     game.data.maps (src/core/game3/dataset.lua:430-447), each map's
--            layout through its lazy midLayout (:357-372): size in cells, the
--            tileset pair, the metatile of every cell and of the border
--   pairs    AssetDecode.pair (src/core/game3/asset_decode.lua:24-87): the
--            pair's metatile atlases as RGBA, one per plane (under, middle,
--            over: imageData, midImageData, overImageData), 16x16 per slot,
--            midToSlot from metatile id to slot; prepared on the CPU only, so
--            the engine's resident textures stay as they are
--   quarters each metatile slot cut into four 8x8 quarters per plane, each
--            hashed (MD5 of its RGBA bytes) to count distinct ones per pair and
--            plane, across all pairs, and the ones Hoenn's maps use (fully
--            transparent quarters counted apart)
--   sprites  the object-event sheets (src/core/game3/ow_sprites.lua's
--            manifest): frame sizes and counts
--   encounters  src/core/game3/encounters.lua's tables: maps and kinds
--
-- Output: KB_MEASURE_* lines of numbers only (pairs by their index in sorted
-- order, never a name or a pixel), then KB_MEASURE_DONE and a quit. KB_FAIL
-- <reason> on a failure. Runs unsandboxed as a POKEPORT_DRIVER coroutine,
-- resumed once per frame with the Game (refs/gen1recomp/main.lua:755-759).

io.stdout:setvbuf("no")

local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 1500
local PLANES = { { key = "imageData", name = "under" }, { key = "midImageData", name = "middle" },
                 { key = "overImageData", name = "over" } }

local function fail(reason)
  print("KB_FAIL " .. tostring(reason):gsub("[^%w_.:+%-]", "?"):sub(1, 60))
  love.event.quit(1)
  while true do coroutine.yield() end
end

local function count(t)
  local n = 0
  for _ in pairs(t) do n = n + 1 end
  return n
end

local function sortedKeys(t)
  local out = {}
  for k in pairs(t) do out[#out + 1] = k end
  table.sort(out, function(a, b) return tostring(a) < tostring(b) end)
  return out
end

-- The four 8x8 quarters of a 16x16 slot as (hash, transparent) pairs.
local function quarters(raw, atlasW, slot, cols, md5)
  local out = {}
  local sx, sy = (slot % cols) * 16, math.floor(slot / cols) * 16
  for q = 0, 3 do
    local qx, qy = sx + (q % 2) * 8, sy + math.floor(q / 2) * 8
    local rows = {}
    for r = 0, 7 do
      local at = ((qy + r) * atlasW + qx) * 4 + 1
      rows[r + 1] = raw:sub(at, at + 31)
    end
    local bytes = table.concat(rows)
    local clear = true
    for i = 4, #bytes, 4 do
      if bytes:byte(i) ~= 0 then clear = false break end
    end
    out[q + 1] = { md5(bytes), clear }
  end
  return out
end

return function(game)
  local started = love.timer.getTime()
  -- wait for the title: the dataset hydrated (Game3.lua:153)
  while not (game and game.phase == "boot" and game.data and type(game.data.maps) == "table") do
    if love.timer.getTime() - started > 120 then fail("no_dataset") end
    game = coroutine.yield() or game
  end
  for _ = 1, 30 do game = coroutine.yield() or game end

  local Dataset = require("src.core.game3.dataset")
  local Decode = require("src.core.game3.asset_decode")
  local Extract = require("src.import.gba.extract_island1")
  local cache = Dataset.cache()
  local nativeRoot = Extract.NATIVE_ROOT or ((Extract.CACHE_ROOT or "data/generated/gba") .. "/native")
  local md5 = function(s) return love.data.hash("md5", s) end

  -- ---- maps
  local byPair, mapsN, cellsN, maxW, maxH, noLayout = {}, 0, 0, 0, 0, 0
  for _, mapId in ipairs(sortedKeys(game.data.maps)) do
    local def = game.data.maps[mapId]
    local ok, lay = pcall(function() return def.midLayout end)
    if ok and type(lay) == "table" then
      local w = tonumber(lay.trueWidth or lay.width) or 0
      local h = tonumber(lay.trueHeight or lay.height) or 0
      local pair = def.pair or lay.pair
      if pair and w > 0 and h > 0 then
        mapsN, cellsN = mapsN + 1, cellsN + w * h
        if w > maxW then maxW = w end
        if h > maxH then maxH = h end
        byPair[pair] = byPair[pair] or {}
        table.insert(byPair[pair], { lay = lay, w = w, h = h })
      else
        noLayout = noLayout + 1
      end
    else
      noLayout = noLayout + 1
    end
    if mapsN % 25 == 0 then game = coroutine.yield() or game end
  end
  print(("KB_MEASURE_MAPS maps=%d cells=%d maxW=%d maxH=%d noLayout=%d pairs=%d"):format(mapsN, cellsN, maxW, maxH,
    noLayout, count(byPair)))

  -- ---- pairs and quarters
  local all, used, allClear, usedClear = {}, {}, {}, {}
  for _, p in ipairs(PLANES) do all[p.name], used[p.name], allClear[p.name], usedClear[p.name] = {}, {}, 0, 0 end
  local usedMetatiles, failedPairs = 0, 0
  -- the three planes flattened per quarter (over, else middle, else under: GBA pixels are opaque or clear),
  -- the art a flat first build draws (GEN3_BRIDGE_PLAN §10 E5): distinct over the used metatiles
  local composite, compositeAll = {}, 0
  for i, pair in ipairs(sortedKeys(byPair)) do
    local ok, out = pcall(Decode.pair, cache, nativeRoot, pair)
    if not ok or type(out) ~= "table" then
      failedPairs = failedPairs + 1
    else
      local cols, slots = out.cols, out.midCount or 0
      local atlasW = cols * 16
      local hashes = {} -- plane name -> slot -> quarters
      local raws = {}   -- plane name -> the atlas's RGBA bytes
      local line = { ("KB_MEASURE_PAIR i=%d mids=%d maps=%d"):format(i, slots, #byPair[pair]) }
      for _, layer in ipairs(out.layers or {}) do
        local plane
        for _, p in ipairs(PLANES) do if p.key == layer.dataKey then plane = p.name end end
        if plane then
          local raw = layer.data:getString()
          raws[plane] = raw
          local mine, clear = {}, 0
          hashes[plane] = {}
          for s = 0, slots - 1 do
            local qs = quarters(raw, atlasW, s, cols, md5)
            hashes[plane][s] = qs
            for _, q in ipairs(qs) do
              if q[2] then clear = clear + 1 else
                mine[q[1]] = true
                all[plane][q[1]] = true
              end
            end
            if s % 64 == 63 then game = coroutine.yield() or game end
          end
          allClear[plane] = allClear[plane] + clear
          line[#line + 1] = ("%s=%d %sClear=%d"):format(plane, count(mine), plane, clear)
        end
      end
      -- the quarters this pair's maps use (cells and borders)
      local usedMids = {}
      for _, m in ipairs(byPair[pair]) do
        for y = 0, m.h - 1 do
          for x = 0, m.w - 1 do usedMids[m.lay:midAt(x, y)] = true end
        end
        for _, mid in ipairs(type(m.lay.borderMids) == "table" and m.lay.borderMids or {}) do usedMids[mid] = true end
      end
      usedMetatiles = usedMetatiles + count(usedMids)
      local under, middle, over = raws.under, raws.middle, raws.over
      for mid in pairs(usedMids) do
        local slot = out.midToSlot and out.midToSlot[mid]
        if slot and under then
          local sx, sy = (slot % cols) * 16, math.floor(slot / cols) * 16
          for q = 0, 3 do
            local qx, qy = sx + (q % 2) * 8, sy + math.floor(q / 2) * 8
            local px = {}
            for r = 0, 7 do
              for c = 0, 7 do
                local at = ((qy + r) * atlasW + qx + c) * 4 + 1
                local src = under
                if over and over:byte(at + 3) ~= 0 then src = over
                elseif middle and middle:byte(at + 3) ~= 0 then src = middle end
                px[#px + 1] = src:sub(at, at + 3)
              end
            end
            local h = md5(table.concat(px))
            if not composite[h] then
              composite[h] = true
              compositeAll = compositeAll + 1
            end
          end
        end
        if slot then
          for plane, bySlot in pairs(hashes) do
            for _, q in ipairs(bySlot[slot] or {}) do
              if q[2] then usedClear[plane] = usedClear[plane] + 1 else used[plane][q[1]] = true end
            end
          end
        end
      end
      line[#line + 1] = ("usedMids=%d layers=%d"):format(count(usedMids), #(out.layers or {}))
      print(table.concat(line, " "))
      out = nil
      collectgarbage()
    end
    game = coroutine.yield() or game
  end
  for _, p in ipairs(PLANES) do
    print(("KB_MEASURE_QUARTERS plane=%s distinct=%d used=%d clearAll=%d clearUsed=%d"):format(p.name,
      count(all[p.name]), count(used[p.name]), allClear[p.name], usedClear[p.name]))
  end
  local union = {}
  for _, p in ipairs(PLANES) do for h in pairs(used[p.name]) do union[h] = true end end
  print(("KB_MEASURE_TOTAL usedMetatiles=%d usedQuartersAllPlanes=%d usedComposite=%d failedPairs=%d"):format(
    usedMetatiles, count(union), compositeAll, failedPairs))

  -- ---- sprites: the object-event sheets' manifest (src/import/gba/ow_extract.lua:619-667:
  -- sprites[gid] = { width, height, frameCount, ... }, count)
  local Ow = require("src.core.game3.ow_sprites")
  local manifest = type(Ow._manifest) == "table" and Ow._manifest or nil
  if not manifest then
    local src = cache:read(((Extract.CACHE_ROOT or "data/generated/gba") .. "/ow") .. "/manifest.lua")
    local chunk = src and load(src, "@ow/manifest.lua", "t", {})
    local ok, m = pcall(chunk or function() return nil end)
    manifest = ok and type(m) == "table" and m or {}
  end
  local sizes, sheets, frames, maxFrames = {}, 0, 0, 0
  for _, gid in ipairs(sortedKeys(type(manifest.sprites) == "table" and manifest.sprites or {})) do
    local spr = manifest.sprites[gid]
    if type(spr) == "table" then
      sheets = sheets + 1
      local n = tonumber(spr.frameCount) or 0
      frames = frames + n
      if n > maxFrames then maxFrames = n end
      local key = ("%dx%d"):format(tonumber(spr.width) or 0, tonumber(spr.height) or 0)
      sizes[key] = (sizes[key] or 0) + 1
    end
  end
  local sizeLine = {}
  for _, k in ipairs(sortedKeys(sizes)) do sizeLine[#sizeLine + 1] = k .. ":" .. sizes[k] end
  print(("KB_MEASURE_SPRITES sheets=%d frames=%d maxFrames=%d sizes=%s"):format(sheets, frames, maxFrames,
    table.concat(sizeLine, ",")))

  -- ---- encounters
  local Enc = require("src.core.game3.encounters")
  pcall(Enc.ensureLoaded)
  local tables = type(Enc._tables) == "table" and Enc._tables or {}
  local kinds = {}
  for _, area in pairs(tables) do
    if type(area) == "table" then
      for kind in pairs(area) do kinds[tostring(kind)] = (kinds[tostring(kind)] or 0) + 1 end
    end
  end
  local kindLine = {}
  for _, k in ipairs(sortedKeys(kinds)) do
    if tostring(k):match("^[%w_]+$") then kindLine[#kindLine + 1] = k .. ":" .. kinds[k] end
  end
  print(("KB_MEASURE_ENCOUNTERS maps=%d kinds=%s"):format(count(tables), table.concat(kindLine, ",")))

  print(("KB_MEASURE_DONE seconds=%d"):format(math.floor(love.timer.getTime() - started)))
  if love.timer.getTime() - started > SECONDS_LIMIT then fail("timeout") end
  love.event.quit(0)
  while true do coroutine.yield() end
end
