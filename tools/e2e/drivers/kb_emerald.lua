-- Kanto Bridge test driver (MG7, docs/specs/GEN3_BRIDGE_PLAN.md §8.3): the
-- Gen 3 adapter on the real Emerald engine, driven over TCP by
-- tools/e2e/test_emerald.py. Every scene first registers a fresh empty save
-- slot labelled KB_EMERALD_TEST and makes it active (the engine's slot API,
-- src/core/SaveData.lua:1642-1900, shared by every engine), so nothing
-- touches the install's own saves; at the end the test slots are deleted and
-- the slot active at boot is active again. KB_SCENE picks the part:
--
--   field           a new game straight into the field (the boot action
--                   Birch's speech ends with, Game3:_handleBootAction, without
--                   the truck), then a warp into Littleroot onto the cell
--                   below its first door, facing down; serves. wall= names
--                   the side step after which a step up is blocked (a wall
--                   beside the door), or none. Once a client
--                   is connected and the player has stepped one cell down,
--                   the game saves itself once (Game3:saveGame), so the test
--                   sees a save.begin.
--   sign            (G3B) the same new game, then onto the cell below one of
--                   Littleroot's signs (its first bg event with a script whose
--                   cell below is open ground), facing up; serves: the test
--                   reads the sign through act.interact and the UI.
--                   KB_SERVING map= x= y= sx= sy= (the sign's cell).
--   story           (G3C) the field scene's start; once a client watches, the
--                   driver changes one cell near the player through the
--                   engine's own Field.setMetatile (to the metatile of a cell
--                   beside it), puts it back two seconds later, then sets the
--                   hide flag of one of Littleroot's objects that is showing
--                   (Flags.setFlag on the live store, by its number).
--                   KB_CELL x= y= metatile= base=, KB_CELL_BACK,
--                   KB_FLAG lid= id=.
--   battle          (G3F) the field scene's start; once a client watches and its
--                   party.sync has filled the shadow party, three battles
--                   through the engine's own BattleBridge (as trainerbattle
--                   and the wild rolls start them): a trainer's (the first
--                   single, non-double trainer of the engine's table), a wild
--                   one (national dex 263, level 3), then the next such
--                   trainer's; each starts once the last one's done ran and
--                   the field is idle. KB_TRAINER n= id= members=, KB_WILD
--                   dex= level=, KB_BATTLE_DONE n= result=.
--   route           (MG8) the same new game, then onto the exit cell of the
--                   Wild Routes test pass's Hoenn end (data/routes.json:
--                   EM_LITTLEROOT_TOWN (18, 14), facing down); serves: the
--                   test walks the clearing into the route map and back.
--   spot            the same new game, then onto KB_SPOT_MAP (KB_SPOT_X,
--                   KB_SPOT_Y) facing KB_SPOT_FACING; serves (a seam's check:
--                   tools/e2e/seam_check.py walks across it in the real client).
--   party           (SEVERAL_GAMES_FOLLOWUPS.md item 7) the field scene's
--                   start; once a client watches and its party.sync has filled
--                   the shadow party with four, through the engine's own
--                   functions: an in-game trade of the fourth member
--                   (Trade.createTradeMon of trade 0, Trade.tradeMons); once
--                   Cobblemon's sync confirmed it, the members of national dex
--                   263 and 265 boarded (Daycare.deposit); once confirmed, six
--                   seconds for the test's session.bind, then a save and back
--                   to the title (the test CONTINUEs); in the loaded game ten
--                   daycare steps (Daycare.step) and the first boarded taken
--                   back (Daycare.take); once confirmed, a gift egg of national
--                   dex 360 (Party.giveEgg) primed to hatch on the player's
--                   next step (its cycles 0, the daycare's step counter 254).
--                   KB_TRADE dex=, KB_BOARDED n=, KB_SAVED, KB_WITHDRAWN
--                   steps=, KB_EGG dex=, KB_HATCHED.
--   title_continue  the same new game saved into the test slot, then back to
--                   the title (Game3:returnToTitle); serves: region.start
--                   CONTINUEs.
--   title_new       the empty test slot active at the title; serves:
--                   region.start starts NEW GAME, and the test answers Birch's
--                   speech through the UI into the field (G3B).
--   cleanup         only the clean-up (after a failed run), then quits.
--
-- Markers: KB_BOOTED scene= slot= home= precleaned=, KB_SERVING map= x= y=
-- wall= (or sx= sy=), KB_SAVED slot= ok=, KB_CELL / KB_CELL_BACK / KB_FLAG, KB_TRAINER / KB_WILD /
-- KB_BATTLE_DONE, KB_TRADE / KB_BOARDED / KB_WITHDRAWN / KB_EGG / KB_HATCHED, KB_TITLE slot= hasSave=,
-- KB_CLEANED deleted= active=, KB_QUIT sessions= code=; KB_FAIL
-- <reason> on a timeout. Slot numbers, map symbols, cell numbers, trainer ids, directions, booleans and
-- counts only.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- with the Game (refs/gen1recomp/main.lua:755-759, :1136-1157).

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27220
local SCENE = os.getenv("KB_SCENE") or "field"
local EXTRA_SECONDS = tonumber(os.getenv("KB_DRIVER_EXTRA") or "") or 0
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (300 + EXTRA_SECONDS)
local LABEL = "KB_EMERALD_TEST"
local TOWN = "EM_LITTLEROOT_TOWN"
-- scene spot: another map and cell to serve from
if SCENE == "spot" then TOWN = os.getenv("KB_SPOT_MAP") or TOWN end
-- scene fp7 (FOREIGN_POKEMON_PLAN FP7): a map with water, a land cell found beside it, Surf and Lanette's PC
if SCENE == "fp7" then TOWN = os.getenv("KB_FP7_MAP") or "EM_ROUTE103" end
local fp7 = { step = 0 }
local SPOT_X, SPOT_Y = tonumber(os.getenv("KB_SPOT_X") or ""), tonumber(os.getenv("KB_SPOT_Y") or "")
local SPOT_FACING = os.getenv("KB_SPOT_FACING") or "down"

local SaveData = require("src.core.SaveData")
local GameVersion = require("src.core.GameVersion")

local function word(v) return (tostring(v):gsub("[^%w_.:+%-]", "?")) end
local function num(id) return tonumber(tostring(id):match("^slot(%d+)$")) or 0 end
local function version() return GameVersion.get() end
local function active() return SaveData.activeSlot(version()) end

local function slots()
  local ok, list = pcall(SaveData.listSlots, version())
  return ok and type(list) == "table" and list or {}
end
local function slotHasSave(id)
  for _, s in ipairs(slots()) do
    if type(s) == "table" and s.id == id then return s.exists == true end
  end
  return false
end

-- Deletes every test slot (never another), making `home` active first.
local function cleanup(home)
  local deleted, v = 0, version()
  for _, s in ipairs(slots()) do
    if type(s) == "table" and s.label == LABEL then
      if active() == s.id then
        local back = home
        if not back then
          for _, o in ipairs(slots()) do
            if type(o) == "table" and o.label ~= LABEL then back = o.id break end
          end
        end
        if back and back ~= s.id then SaveData.setActiveSlot(v, back) end
      end
      if SaveData.deleteSlot(v, s.id) then deleted = deleted + 1 end
    end
  end
  if home and active() ~= home then SaveData.setActiveSlot(v, home) end
  return deleted, active()
end

local homeRef
local function fail(reason)
  local ok, deleted = pcall(cleanup, homeRef)
  print(("KB_FAIL %s cleaned=%s"):format(reason, word(ok and deleted or "error")))
  love.event.quit(1)
  while true do coroutine.yield() end
end

local function mod3(name) return package.loaded["src.core.game3." .. name] end

-- The first `count` trainers of the engine's table that fight alone (no double battle), as
-- { id, foe } (scripting/trainers.lua:169-233).
local function singleTrainers(count)
  local Trainers, out = mod3("scripting.trainers"), {}
  if not (Trainers and Trainers.foeFromId) then return out end
  for id = 1, 1023 do
    local ok, foe = pcall(Trainers.foeFromId, id)
    if ok and type(foe) == "table" and type(foe.party) == "table" and #foe.party >= 1 and not foe.doubleBattle then
      out[#out + 1] = { id = id, foe = foe }
      if #out >= count then break end
    end
  end
  return out
end

-- The field is up and settled: no lock, no warp, no fade, no step.
local function fieldReady(game)
  local F, W, P = mod3("field"), mod3("warp"), mod3("player")
  local Fade = package.loaded["src.ui.game3.fade"]
  if not (game and game.phase == "field" and F and F.running) then return false end
  if F.isLocked and F.isLocked() then return false end
  if W and W.isBusy and W.isBusy() then return false end
  if Fade and Fade.lockInput then return false end
  return not (P and P.moving)
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local slot, start, wall, sign
  local storyStep, storyAt, cell = 0, nil, nil
  local battleStep, battleDone, trainers = 0, {}, nil
  local partyStep, partyAt, partyOld, partyHatching = 0, nil, nil, false
  local saved = false
  local waitUntil = 0
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      if status and status.ready and game.phase == "boot" then
        status.configure({ port = PORT })
        homeRef = active()
        if SCENE == "cleanup" then
          local deleted, now = cleanup(homeRef)
          print(("KB_CLEANED deleted=%d active=%d"):format(deleted, num(now)))
          print("KB_QUIT sessions=0 code=0")
          return
        end
        local precleaned = cleanup(homeRef)
        local v = version()
        slot = SaveData.createSlot(v)
        if not slot then fail("no_slot") end
        SaveData.renameSlot(v, slot, LABEL)
        if SaveData.setActiveSlot(v, slot) ~= slot then fail("activate_failed") end
        print(("KB_BOOTED scene=%s slot=%d home=%d precleaned=%d"):format(word(SCENE), num(slot), num(homeRef),
          precleaned))
        if SCENE == "title_new" then
          print(("KB_TITLE slot=%d hasSave=%s"):format(num(active()), word(slotHasSave(active()))))
          phase = "serve"
        elseif SCENE == "field" or SCENE == "title_continue" or SCENE == "sign" or SCENE == "story"
            or SCENE == "battle" or SCENE == "route" or SCENE == "spot" or SCENE == "party" or SCENE == "fp7" then
          local ok, err = pcall(game._handleBootAction, game, { action = "new_game", name = "TEST", gender = 0 })
          if not ok then fail("new_game " .. word(err):sub(1, 40)) end
          phase = "newgame"
        else
          fail("unknown_scene")
        end
      end
    elseif phase == "newgame" then
      if fieldReady(game) then
        -- onto the cell below Littleroot's first warp (a door), facing down
        local def = game.data and game.data.maps and game.data.maps[TOWN]
        local w = def and def.warps and def.warps[1]
        if not w and SCENE ~= "fp7" then fail("no_town_warp") end
        start = w and { x = tonumber(w.x), y = tonumber(w.y) + 1 } or { x = 8, y = 8 }
        -- MG8: the test pass's exit cell (gen1-bridge/data/routes.json's `to` end)
        if SCENE == "route" then start = { x = 18, y = 14 } end
        if SCENE == "spot" then
          if not (SPOT_X and SPOT_Y) then fail("no_spot") end
          start = { x = SPOT_X, y = SPOT_Y }
        end
        local Warp = mod3("warp")
        local ok = pcall(Warp.request, nil, game, TOWN, start.x, start.y, SCENE == "spot" and SPOT_FACING or "down",
          { fade = false })
        if not ok then fail("warp_request") end
        phase = "town"
      end
    elseif phase == "town" then
      local Map, P = mod3("map"), mod3("player")
      if SCENE == "fp7" and fieldReady(game) and Map and Map.current == TOWN and fp7.step == 0 then
        -- a walkable cell with water beside it (the engine's own collision), then onto it facing the water
        local C = mod3("collision")
        local DIRS = { { "up", 0, -1 }, { "down", 0, 1 }, { "left", -1, 0 }, { "right", 1, 0 } }
        for y = 1, 80 do
          for x = 1, 80 do
            if not fp7.cell and C.isWalkable(x, y) and not C.isWater(x, y) and not (C.warpAt and C.warpAt(x, y)) then
              for _, d in ipairs(DIRS) do
                if not fp7.cell and C.isWater(x + d[2], y + d[3]) then
                  fp7.cell, fp7.water, fp7.facing = { x = x, y = y }, { x = x + d[2], y = y + d[3] }, d[1]
                end
              end
            end
          end
        end
        if not fp7.cell then fail("fp7_no_water") end
        local Warp = mod3("warp")
        if not pcall(Warp.request, nil, game, TOWN, fp7.cell.x, fp7.cell.y, fp7.facing, { fade = false }) then fail("fp7_warp") end
        start = fp7.cell
        fp7.step = 1
      elseif SCENE == "fp7" and fp7.step == 1 and fieldReady(game) and P.cellX == start.x and P.cellY == start.y then
        -- the Surf badge (FieldMoves.badgeFlag, field_moves.lua:131), so the game's own check passes
        local FM, Flags, Space = mod3("field_moves"), mod3("scripting.flags"), mod3("scripting.space")
        local flag = FM and FM.badgeFlag("SURF")
        if flag and Flags and Space then pcall(Flags.setFlag, Space.store, nil, flag, true) end
        print(("KB_SERVING map=%s x=%d y=%d wall=none wx=%d wy=%d facing=%s badge=%s"):format(TOWN, start.x, start.y,
          fp7.water.x, fp7.water.y, fp7.facing, word(flag ~= nil)))
        fp7.step = 2
        phase = "serve"
      elseif fieldReady(game) and Map and Map.current == TOWN and P.cellX == start.x and P.cellY == start.y then
        -- a blocked step to test: a step left or right onto open ground, then up into something solid
        -- (a house wall beside the door); "none" when neither side has one
        local C = mod3("collision")
        wall = "none"
        for _, d in ipairs({ { "left", -1 }, { "right", 1 } }) do
          local sx = start.x + d[2]
          if wall == "none" and C and C.isWalkable and C.isWalkable(sx, start.y) and not C.isWalkable(sx, start.y - 1)
              and not (C.warpAt and C.warpAt(sx, start.y - 1)) then
            wall = d[1]
          end
        end
        if SCENE == "field" or SCENE == "story" or SCENE == "battle" or SCENE == "route" or SCENE == "spot"
            or SCENE == "party" then
          print(("KB_SERVING map=%s x=%d y=%d wall=%s"):format(TOWN, start.x, start.y, wall))
          phase = "serve"
        elseif SCENE == "sign" then
          -- a sign: a bg event with a script (field.lua:332-345, game.data.maps or the script bundle's events)
          -- whose cell below is open ground; the player onto that cell, facing up
          local def = game.data and game.data.maps and game.data.maps[TOWN]
          local events = def and def.bgEvents
          if not events then
            local Space = mod3("scripting.space")
            local ev = Space and Space.bundle and Space.bundle.events and Space.bundle.events[TOWN]
            events = ev and ev.bgEvents
          end
          for _, ev in ipairs(type(events) == "table" and events or {}) do
            local x, y = tonumber(ev.x), tonumber(ev.y)
            if not sign and x and y and ev.scriptKey and C and C.isWalkable and C.isWalkable(x, y + 1)
                and not (C.warpAt and C.warpAt(x, y + 1)) then
              sign = { x = x, y = y }
            end
          end
          if not sign then fail("no_sign") end
          local ok = pcall(mod3("warp").request, nil, game, TOWN, sign.x, sign.y + 1, "up", { fade = false })
          if not ok then fail("warp_request") end
          phase = "signspot"
        else
          local ok, written = pcall(game.saveGame, game)
          print(("KB_SAVED slot=%d ok=%s"):format(num(active()), word(ok and written == true)))
          if not (ok and written) then fail("save_failed") end
          pcall(game.returnToTitle, game, { skipIntro = true })
          waitUntil = love.timer.getTime() + 1
          phase = "title"
        end
      end
    elseif phase == "signspot" then
      local Map, P = mod3("map"), mod3("player")
      if fieldReady(game) and Map and Map.current == TOWN and P.cellX == sign.x and P.cellY == sign.y + 1 then
        print(("KB_SERVING map=%s x=%d y=%d sx=%d sy=%d"):format(TOWN, P.cellX, P.cellY, sign.x, sign.y))
        phase = "serve"
      end
    elseif phase == "title" then
      if game.phase == "boot" and love.timer.getTime() >= waitUntil then
        print(("KB_TITLE slot=%d hasSave=%s"):format(num(active()), word(slotHasSave(active()))))
        phase = "serve"
      end
    elseif phase == "serve" then
      -- (diagnostics every 2 s while the field scene waits for its save: counts and booleans only)
      if SCENE == "field" and not saved and love.timer.getTime() >= waitUntil then
        waitUntil = love.timer.getTime() + 2
        local P, F, W = mod3("player"), mod3("field"), mod3("warp")
        local Fade = package.loaded["src.ui.game3.fade"]
        print(("KB_DEBUG sessions=%s x=%s y=%s ready=%s locked=%s busy=%s fade=%s moving=%s phase=%s"):format(
          word(status.sessions), word(P and P.cellX), word(P and P.cellY), word(fieldReady(game)),
          word(F and F.isLocked and F.isLocked()), word(W and W.isBusy and W.isBusy()),
          word(Fade and Fade.lockInput), word(P and P.moving), word(game.phase)))
      end
      -- the field scene's one save, once a client watches and the player stepped down
      if SCENE == "field" and not saved and (tonumber(status.sessions) or 0) >= 1 then
        local P = mod3("player")
        if P and P.cellX == start.x and P.cellY == start.y + 1 and fieldReady(game) then
          saved = true
          local ok, written = pcall(game.saveGame, game)
          print(("KB_SAVED slot=%d ok=%s"):format(num(active()), word(ok and written == true)))
        end
      end
      -- the story scene's changes, once a client watches: a cell, the cell back, a hide flag
      if SCENE == "story" and (tonumber(status.sessions) or 0) >= 1 then
        local now = love.timer.getTime()
        storyAt = storyAt or (now + 3)
        local Field, Map = mod3("field"), mod3("map")
        local def = Map and Map.currentDef and Map.currentDef()
        local lay = def and def.midLayout
        if storyStep == 0 and now >= storyAt and lay then
          -- a cell two to the right of the player and one down, given the metatile of the cell left of it
          cell = { x = start.x + 2, y = start.y + 1 }
          cell.base = lay:midAt(cell.x, cell.y)
          local other = lay:midAt(cell.x - 1, cell.y)
          if other == cell.base then other = lay:midAt(cell.x, cell.y - 1) end
          if other == cell.base then other = (cell.base + 1) % 512 end
          cell.mid = other
          local ok = pcall(Field.setMetatile, cell.x, cell.y, cell.mid, false)
          if not ok then fail("set_metatile") end
          print(("KB_CELL x=%d y=%d metatile=%d base=%d"):format(cell.x, cell.y, cell.mid, cell.base))
          storyStep, storyAt = 1, now + 2
        elseif storyStep == 1 and now >= storyAt then
          local ok = pcall(Field.setMetatile, cell.x, cell.y, cell.base, false)
          if not ok then fail("set_metatile_back") end
          print("KB_CELL_BACK")
          storyStep, storyAt = 2, now + 1
        elseif storyStep == 2 and now >= storyAt then
          -- an object of the town with a hide flag that is clear now (scripting/space.lua:663-671)
          local Space, Flags = mod3("scripting.space"), mod3("scripting.flags")
          local ev = Space and Space.bundle and Space.bundle.events and Space.bundle.events[TOWN]
          local defs = ev and (ev.objects or ev.objectEvents) or (game.data.maps[TOWN] or {}).objects or {}
          local pick
          for _, d in ipairs(defs) do
            local lid, flag = tonumber(d.localId or d.index), tonumber(d.flag or d.flagId)
            if not pick and lid and lid > 0 and lid < 127 and flag and flag ~= 0 and flag ~= 0xFFFF
                and not Flags.getFlag(Space.store, nil, flag) then
              pick = { lid = lid, flag = flag }
            end
          end
          if not pick then fail("no_hideable_object") end
          Flags.setFlag(Space.store, nil, pick.flag, true)
          print(("KB_FLAG lid=%d id=%d"):format(pick.lid, pick.flag))
          storyStep = 3
        end
      end
      -- the battle scene's three battles, once a client watches and its party is the hidden game's
      if SCENE == "battle" and (tonumber(status.sessions) or 0) >= 1 then
        local Runtime, BB, Bt = mod3("runtime"), mod3("battle_bridge"), mod3("battle")
        local session = Runtime and Runtime.getSession and Runtime.getSession()
        local idle = fieldReady(game) and not (Bt and Bt.isActive and Bt.isActive())
        local mod = Runtime and Runtime._mod
        local function doneFor(n)
          return function(result)
            battleDone[n] = true
            print(("KB_BATTLE_DONE n=%d result=%s"):format(n, word(result)))
          end
        end
        local function trainerBattle(n, t)
          print(("KB_TRAINER n=%d id=%d members=%d"):format(n, t.id, #t.foe.party))
          local ok, err = pcall(BB.start, mod, game, t.foe, { wild = false, trainerId = t.id, done = doneFor(n) })
          if not ok then fail("trainer_start " .. word(err):sub(1, 40)) end
        end
        if battleStep == 0 and idle and session and type(session.party) == "table" and #session.party >= 1 then
          trainers = singleTrainers(2)
          if #trainers < 2 then fail("no_trainers") end
          trainerBattle(1, trainers[1])
          battleStep = 1
        elseif battleStep == 1 and battleDone[1] and idle then
          local Pokemon = mod3("pokemon")
          local species = Pokemon and Pokemon.speciesFromNational and Pokemon.speciesFromNational(263)
          if not species then fail("no_species") end
          print("KB_WILD dex=263 level=3")
          local ok, err = pcall(BB.startWild, mod, game, { species = species, level = 3, item = 0 }, { done = doneFor(2) })
          if not ok then fail("wild_start " .. word(err):sub(1, 40)) end
          battleStep = 2
        elseif battleStep == 2 and battleDone[2] and idle then
          trainerBattle(3, trainers[2])
          battleStep = 3
        end
      end
      -- the party scene: a trade, two boarded, a save and CONTINUE, a withdrawal, an egg (follow-up item 7)
      if SCENE == "party" and (tonumber(status.sessions) or 0) >= 1 then
        local Runtime, P = mod3("runtime"), mod3("pokemon")
        local session = Runtime and Runtime.getSession and Runtime.getSession()
        local party = session and type(session.party) == "table" and session.party or {}
        -- the bridge's operations and gifts all confirmed by Cobblemon's syncs (its process-lifetime state)
        local st = game and rawget(game, "kantoBridgeState")
        local quiet = type(st) == "table" and #(type(st.ops) == "table" and st.ops or {}) == 0
          and #(type(st.gifts) == "table" and st.gifts or {}) == 0
        local now = love.timer.getTime()
        local function slotOf(dex)
          for i, mon in ipairs(party) do
            if P.national(mon.species) == dex then return i end
          end
          return nil
        end
        if partyStep == 0 and #party == 4 and fieldReady(game) then
          local T = mod3("scripting.natives_trade")
          local offered = T.createTradeMon(0, T.levelOfSlot(3))
          if not (offered and T.tradeMons(session, 3, offered)) then fail("trade") end
          print(("KB_TRADE dex=%d"):format(P.national(offered.species)))
          partyStep = 1
        elseif partyStep == 1 and quiet and #party == 4 and fieldReady(game) then
          local Daycare = mod3("daycare")
          for _, dex in ipairs({ 263, 265 }) do
            local i = slotOf(dex)
            if not (i and Daycare.deposit(session, i)) then fail("deposit_" .. dex) end
          end
          print("KB_BOARDED n=2")
          partyStep = 2
        elseif partyStep == 2 and quiet then
          -- the deposits confirmed: time for the test's session.bind, then a save and back to the title
          partyAt = partyAt or (now + 6)
          if now >= partyAt and fieldReady(game) then
            local ok, written = pcall(game.saveGame, game)
            print(("KB_SAVED slot=%d ok=%s"):format(num(active()), word(ok and written == true)))
            if not (ok and written) then fail("save_failed") end
            partyOld = session
            pcall(game.returnToTitle, game, { skipIntro = true })
            partyStep = 3
          end
        elseif partyStep == 3 and session and session ~= partyOld and quiet and fieldReady(game) then
          -- CONTINUEd: ten steps in the daycare, then the first one boarded comes back
          local Daycare = mod3("daycare")
          for _ = 1, 10 do Daycare.step(session) end
          if (tonumber(Daycare.take(session, 1)) or 0) == 0 then fail("take") end
          print("KB_WITHDRAWN steps=10")
          partyStep = 4
        elseif partyStep == 4 and quiet and fieldReady(game) then
          -- a gift egg, primed to hatch on the player's next step (daycare.lua's tickEggCycles at counter 255)
          local ok, _, egg = mod3("party").giveEgg(session, P.speciesFromNational(360), {})
          if not (ok and egg) then fail("egg") end
          egg.friendship, egg.eggCycles = 0, 0
          mod3("daycare").stateOf(session).stepCounter = 254
          print("KB_EGG dex=360")
          partyStep = 5
        elseif partyStep == 5 then
          local EggHatch = package.loaded["src.ui.game3.egg_hatch"]
          local open = EggHatch and EggHatch.isOpen and EggHatch.isOpen()
          if open then partyHatching = true end
          if partyHatching and not open and fieldReady(game) then
            print("KB_HATCHED")
            partyStep = 6
          end
        end
      end
      -- scene fp7: once the test has surfed out and back (the player on land again after surfing), Lanette's PC's
      -- storage special through the script.command hook; it waits until Cobblemon's PC closes
      if SCENE == "fp7" and (tonumber(status.sessions) or 0) >= 1 then
        local P = mod3("player")
        if fp7.step == 2 and P.surfing then fp7.step = 3 end
        if fp7.step == 3 and not P.surfing and fieldReady(game) then
          local Space = mod3("scripting.space")
          local vm = Space and Space.vm
          local Ops = mod3("scripting.ops_a")
          if not (vm and vm.ctx and Ops and Ops.dispatch) then
            print("KB_PC yielded=none")
            fp7.step = 5
          else
            local ok, yielded = pcall(Ops.dispatch, vm, { op = "special", id = 63 })
            fp7.ctx = vm.ctx
            print(("KB_PC yielded=%s"):format(word(ok and yielded)))
            fp7.step = 4
          end
        elseif fp7.step == 4 then
          -- the wait is over once its poll says done (or the script's own loop has already taken it down)
          local poll = fp7.ctx.nativePoll
          if poll == nil or fp7.ctx.mode ~= "native" or (type(poll) == "function" and poll()) then
            print("KB_PC_DONE")
            if fp7.ctx.mode == "native" then fp7.ctx.mode, fp7.ctx.status, fp7.ctx.nativePoll = nil, nil, nil end
            fp7.step = 5
          end
        end
      end
      if status.quitRequested then
        local deleted, now = cleanup(homeRef)
        print(("KB_CLEANED deleted=%d active=%d"):format(deleted, num(now)))
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
