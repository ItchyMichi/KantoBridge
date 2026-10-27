-- Kanto Bridge test driver (Wild Routes check C6, docs/specs/WILD_ROUTES_PLAN.md
-- §8): moves gen1-bridge to the test port, starts a new game without the
-- intro (src/core/Game.lua:173-200), gives the player a starter (as
-- kb_route1.lua does: Pokemon.new + Party.add, the OT stamped) and warps onto
-- the clearing's spot in its town through the supported mod.world facade
-- (WorldAPI:warpTo, src/world/WorldAPI.lua:358-371), facing its trees. The
-- spot comes from the test (KB_SPOT_MAP, KB_SPOT_X, KB_SPOT_Y,
-- KB_SPOT_FACING: gen1-bridge/data/routes.json's). Then it prints
-- KB_WILD_READY and serves until gen1-bridge reports a debug.quit (exit 0) or
-- the time limit passes (exit 1); the test (tools/e2e/test_wild_routes.py)
-- does everything else over the bridge.
--
-- It prints KB_MAP_ENTERED map=<symbol> via=<word> on every map.entered, and
-- at the end KB_END with the map, the party's size and the bag's POTION count.
--
-- KB_PACE=<n> paces the serving to n frames a second (a driver turns the
-- engine's own pacing off, main.lua:1776-1781, so a real client playing
-- against it -- tools/e2e/showcase.py -- would see the hidden game run several
-- times too fast): the driver sleeps out the rest of each frame.
--
-- KB_DATASET_MEMO=<seconds> (the pod): the engine checks its import cache
-- before every record a dataset view hands out (DatasetViews:_ready re-reads
-- every decoded module, src/mods/DatasetViews.lua:209-241, :335-337), which
-- the pod's network disk turns into half a second a record and gen1-bridge's
-- first export into a quarter of an hour; the driver lets a passed check
-- stand that many seconds. The test install's cache never changes mid-run.
--
-- WORLD_SESSIONS_PLAN WS0 (the save/reload bug inside a wild route): KB_SCENE
-- picks the launch (default: the C6 scene above, unchanged).
--   save      before the new game a scratch slot of the driver's own is made
--             the active one (SaveData.createSlot + setActiveSlot; KB_SLOT
--             slot=<n> home=<n>), so no save the install already holds is
--             touched; the rest as above, and once the hidden player has stood
--             idle on KB_ROUTE_MAP for a second the driver saves the game the
--             way the start menu's SAVE does (Game:writeSave,
--             src/core/Game.lua:1362-1385): KB_SAVED map x y ok. At debug.quit
--             the slot stays active for the next launch.
--   continue  the same install after a relaunch: the game at its title until a
--             session has bound (KB_CONTINUE_AT=bind, the default) or at once
--             (KB_CONTINUE_AT=now); then CONTINUE as the title's menu runs it
--             (SaveData.load + Game:restoreSave, src/core/Game.lua:224-242,
--             :1537-1592; as kb_crash.lua does): KB_LOADED with the map the
--             save file holds, then KB_CONTINUED with where the player stands
--             and the load report's remapped fields (scrubMaps,
--             src/core/SaveData.lua:3037-3060). With KB_HOME_SLOT and
--             KB_SCRATCH_SLOT at debug.quit the home slot is made active again
--             and the scratch slot deleted (KB_CLEANED); with KB_CONTINUE_AT=
--             never the game stays at its title (a client's own CONTINUE).
--   cleanup   only that clean-up, then quits (after a failed run).
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- (refs/gen1recomp/main.lua:755-759, :1136-1157). Data safety: prints only
-- markers, map symbols, cells and counts -- never a species, a name or any
-- text.

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27206
local EXTRA_SECONDS = tonumber(os.getenv("KB_DRIVER_EXTRA") or "") or 0 -- the runner's export allowance
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (600 + EXTRA_SECONDS)
local MAP = os.getenv("KB_SPOT_MAP") or "VIRIDIAN_CITY"
local SPOT_X = tonumber(os.getenv("KB_SPOT_X") or "")
local SPOT_Y = tonumber(os.getenv("KB_SPOT_Y") or "")
local FACING = os.getenv("KB_SPOT_FACING") or ""
local STARTER_DEX = 4
local PACE = tonumber(os.getenv("KB_PACE") or "") or 0
local DATASET_MEMO = tonumber(os.getenv("KB_DATASET_MEMO") or "") or 0
local SCENE = os.getenv("KB_SCENE") or "c6"
local ROUTE_MAP = os.getenv("KB_ROUTE_MAP") or "KB_ROUTE_VIRIDIAN_FOREST_ZONE"
local CONTINUE_AT = os.getenv("KB_CONTINUE_AT") or "bind"
local HOME_SLOT = os.getenv("KB_HOME_SLOT")
local SCRATCH_SLOT = os.getenv("KB_SCRATCH_SLOT")

local function slotNum(id)
  return tonumber(tostring(id):match("^slot(%d+)$")) or 0
end

-- WS0: makes the home slot active again and deletes the scratch slot (never the home one).
local function cleanSlots()
  if not (HOME_SLOT and SCRATCH_SLOT) or HOME_SLOT == SCRATCH_SLOT then return 0 end
  local SaveData = require("src.core.SaveData")
  local version = require("src.core.GameVersion").get()
  pcall(SaveData.setActiveSlot, version, HOME_SLOT)
  local ok, done = pcall(SaveData.deleteSlot, version, SCRATCH_SLOT)
  return (ok and done) and 1 or 0
end

local function memoDatasetChecks(seconds)
  local ok, DatasetViews = pcall(require, "src.mods.DatasetViews")
  if not ok or type(DatasetViews) ~= "table" or type(DatasetViews._ready) ~= "function" then return false end
  local ready = DatasetViews._ready
  DatasetViews._ready = function(self, view)
    local now = love.timer.getTime()
    if type(view) == "table" and view.kbReadyAt and now - view.kbReadyAt < seconds and not view.invalid
        and not view.unavailable then
      return true
    end
    local passed = ready(self, view)
    if passed and type(view) == "table" then view.kbReadyAt = now end
    return passed
  end
  return true
end

local function word(v)
  local s = tostring(v)
  return (s:gsub("[^%w_.:+%-]", "?"))
end

local function fail(reason)
  print("KB_FAIL " .. reason)
  love.event.quit(1)
  while true do coroutine.yield() end
end

local function overworld(game)
  local states = game.stack and game.stack.states or {}
  for i = #states, 1, -1 do
    local s = states[i]
    if type(s) == "table" and s.isOverworld then return s end
  end
  return nil
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local arrived = false
  local nextFrame
  local savedAt, idleFrames, continued = nil, 0, false
  if SCENE ~= "continue" and SCENE ~= "cleanup"
      and not (SPOT_X and SPOT_Y and ({ up = true, down = true, left = true, right = true })[FACING]) then
    fail("no_spot")
  end
  if DATASET_MEMO > 0 then
    print(("KB_DATASET_MEMO seconds=%d ok=%s"):format(DATASET_MEMO, tostring(memoDatasetChecks(DATASET_MEMO))))
  end
  local Runtime = require("src.mods.Runtime")
  Runtime.events:on("map.entered", function(payload)
    local id = type(payload) == "table" and payload.mapId or nil
    local via = type(payload) == "table" and payload.via or nil
    print(("KB_MAP_ENTERED map=%s via=%s"):format(word(id), word(via)))
  end, 0, "kb-wild-driver")
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      if status and status.ready then
        status.configure({ port = PORT })
        if SCENE == "cleanup" then
          print(("KB_CLEANED deleted=%d"):format(cleanSlots()))
          print("KB_QUIT sessions=0 code=0")
          return
        elseif SCENE == "continue" then
          local top = game.stack:top()
          print(("KB_BOOTED scene=continue top=%s"):format(word(top and top.screenId)))
          phase = "serve"
        else
          if SCENE == "save" then
            local SaveData = require("src.core.SaveData")
            local version = require("src.core.GameVersion").get()
            local home = SaveData.activeSlot(version)
            local ok, id = pcall(SaveData.createSlot, version)
            if not (ok and id) then fail("create_slot") end
            SaveData.setActiveSlot(version, id)
            print(("KB_SLOT slot=%d home=%d"):format(slotNum(id), slotNum(home)))
          end
          game:startNewGame({ intro = false })
          phase = "world"
        end
      end
    elseif phase == "world" then
      local ow = overworld(game)
      if ow and game.stack:top() == ow and ow.map then
        local save, data = game.save, game.data
        local species
        for id, def in pairs(data.pokemon) do
          if type(def) == "table" and def.dex == STARTER_DEX and (species == nil or id < species) then species = id end
        end
        if not species then fail("no_starter_species") end
        local mon = require("src.pokemon.Pokemon").new(data, species, 5)
        require("src.battle.BattleState").stampOT(save, mon)
        if not require("src.pokemon.Party").add(save.party, mon) then fail("party_full") end
        local world = require("src.world.WorldAPI").new(game, "kb-wild-driver")
        local ok, err = world:warpTo(MAP, SPOT_X, SPOT_Y, FACING, { onDone = function() arrived = true end })
        if not ok then fail("warp " .. word(err)) end
        phase = "warping"
      end
    elseif phase == "warping" then
      local ow = overworld(game)
      if arrived and ow and game.stack:top() == ow and ow.map and ow.map.id == MAP then
        local p = ow.player
        print(("KB_WILD_READY map=%s x=%s y=%s facing=%s party=%d port=%s"):format(
          word(ow.map.id), word(p.cellX), word(p.cellY), word(p.facing), #game.save.party, word(status.port)))
        phase = "serve"
      end
    elseif phase == "serve" then
      if PACE > 0 then
        local now = love.timer.getTime()
        nextFrame = math.max((nextFrame or now) + 1 / PACE, now - 0.1)
        if nextFrame > now then love.timer.sleep(nextFrame - now) end
      end
      local ow = overworld(game)
      if SCENE == "save" and not savedAt then
        local p = ow and ow.player
        local still = ow and game.stack:top() == ow and ow.map and ow.map.id == ROUTE_MAP and p and not p.moving
          and not (ow.runner and ow.runner:isRunning())
        idleFrames = still and idleFrames + 1 or 0
        if idleFrames >= 60 then
          local written = game:writeSave()
          savedAt = love.timer.getTime()
          print(("KB_SAVED map=%s x=%s y=%s facing=%s ok=%s slot=%d"):format(word(ow.map.id), word(p.cellX),
            word(p.cellY), word(p.facing), word(written == true),
            slotNum(require("src.core.SaveData").activeSlot(require("src.core.GameVersion").get()))))
        end
      elseif SCENE == "continue" and not continued and CONTINUE_AT ~= "never"
          and (CONTINUE_AT == "now" or (status.sessionBinds or 0) >= 1) and not ow then
        local SaveData = require("src.core.SaveData")
        local loaded, recovered = SaveData.load()
        if not loaded then fail("no_save") end
        local pl = loaded.player or {}
        local heal = loaded.lastHeal or {}
        print(("KB_LOADED map=%s x=%s y=%s heal=%s slot=%d recovered=%s"):format(word(pl.map), word(pl.x), word(pl.y),
          word(heal.map), slotNum(SaveData.activeSlot(require("src.core.GameVersion").get())), word(recovered)))
        game:restoreSave(loaded, recovered, { freshBoot = true, continued = true })
        continued = true
        local now = overworld(game)
        local fields = {}
        for _, r in ipairs((game.saveReport and game.saveReport.remappedMaps) or {}) do
          fields[#fields + 1] = word(r.field) .. ":" .. word(r.id) .. ">" .. word(r.to)
        end
        print(("KB_CONTINUED map=%s x=%s y=%s remapped=%d %s"):format(word(now and now.map and now.map.id),
          word(now and now.player and now.player.cellX), word(now and now.player and now.player.cellY), #fields,
          table.concat(fields, ",")))
      end
      if status.quitRequested then
        local sv = game.save or {}
        print(("KB_END map=%s party=%d potions=%d"):format(word(ow and ow.map and ow.map.id), #(sv.party or {}),
          (sv.inventory or {}).POTION or 0))
        if SCENE == "continue" then print(("KB_CLEANED deleted=%d"):format(cleanSlots())) end
        print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
        return
      end
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase)
    end
    game = coroutine.yield() or game
  end
end
