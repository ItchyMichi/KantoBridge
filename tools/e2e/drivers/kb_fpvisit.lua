-- Kanto Bridge test driver (FOREIGN_POKEMON_PLAN FP3/FP8, tools/e2e/fp_visitors.py): a visitor in each hidden game,
-- built by gen1-bridge itself from a party.sync, on the real engine. KB_FPV_VERSION picks the game (blue, crystal,
-- emerald). gen1-bridge moves to KB_TEST_PORT; the driver makes a throwaway save slot, starts a new game straight
-- into the field (KB_FPV_FIELD) and waits for the test's party.sync to give the party a stand-in record. Then it
-- checks, on that record (the engine seams FP0 proved, PROGRESS.md "Foreign Pokemon: spike FP0"):
--   PIN      the engine's own recompute (gen1 Stats.ensure, gs Mon.refreshStats, game3 Pokemon.applyStats) keeps
--            Cobblemon's stats
--   SCREENS  the party screen, the summary and the Hall of Fame screen draw with it (opened, stepped, closed)
--   FIELD    Cut is usable by it (the engine's own move check)
--   EXPORT   after SAVE, the slot exported as the game does it (gen1-bridge leaves visitors out); control:
--            the same with the visitors taken out by hand
--   SAVE / CONTINUE  the engine's save and CONTINUE; the record comes back with its stats and name
-- and prints KB_FPV_DONE; at debug.quit it deletes the slot and makes the previous one active again.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine. Data safety: KB_FPV_ lines carry engine ids, numbers, booleans,
-- counts and short hashes only; engine error text is reduced to a file:line location.

io.stdout:setvbuf("no")

local VERSION = os.getenv("KB_FPV_VERSION") or "blue"
local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27244
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 900
local DATASET_MEMO = tonumber(os.getenv("KB_DATASET_MEMO") or "") or 0
local FAM = VERSION == "blue" and "gen1" or (VERSION == "crystal" and "gs" or "game3")
local STAND_IN = FAM == "game3" and 440 or "KB_VISITOR"
local started = love.timer.getTime()
local game
local frame = 0

local function word(v)
  local s = tostring(v)
  return (s:gsub("[^%w_.:+%-/]", "?"))
end
local function out(tag, kv)
  local parts = { "KB_FPV_" .. tag }
  for i = 1, #kv, 2 do parts[#parts + 1] = word(kv[i]) .. "=" .. word(kv[i + 1]) end
  print(table.concat(parts, " "))
end
local function errcode(msg)
  local s = tostring(msg)
  return s:match("[%w_/%.%-]+%.lua:%d+") or "error"
end
local function res(ok, err) return ok and "ok" or ("threw:" .. errcode(err)) end
local function fail(reason)
  print("KB_FAIL " .. reason)
  love.event.quit(1)
  while true do coroutine.yield() end
end
local function yield()
  frame = frame + 1
  if love.timer.getTime() - started > SECONDS_LIMIT then fail("timeout") end
  game = coroutine.yield() or game
end
local function waitUntil(pred, maxFrames)
  for i = 1, maxFrames do
    if pred() then return true, i end
    yield()
  end
  return false, maxFrames
end
local function top() return game and game.stack and game.stack:top() end
local function topId()
  local t = top()
  if t == nil then return "-" end
  return type(t) == "table" and (t.screenId or t.id) or "?"
end
local tapSeq = 0
local function tap(btn)
  tapSeq = tapSeq + 1
  local s = "kb-fpv:tap" .. tapSeq
  game.input:sourcePress(btn, s)
  game.input:sourceRelease(btn, s)
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

-- ---------------------------------------------------------------- per-engine seams

local E = {}

function E.party()
  if FAM == "game3" then
    local R = package.loaded["src.core.game3.runtime"]
    local s = R and R.getSession and R.getSession() or nil
    return s and s.party or nil, s
  end
  return game.save and game.save.party or nil, game.save
end

function E.fieldReady()
  if FAM == "gen1" then
    if not (game and game.overworld and game.save) then return false end
    if top() ~= game.overworld then return false end
    local r = game.overworld.runner
    return not (r and r.isRunning and r:isRunning())
  elseif FAM == "gs" then
    local w = game and game.world
    if not (game.phase == "play" and w and w.map and w.player) then return false end
    if top() then return false end
    local okA, acc = pcall(w.acceptsMenuInput, w)
    return okA and acc and not (w.player.moving or (w.player.turnTimer or 0) > 0 or w.player.jumping)
  else
    local F, W = package.loaded["src.core.game3.field"], package.loaded["src.core.game3.warp"]
    local Fade = package.loaded["src.ui.game3.fade"]
    if not (game and game.phase == "field" and F and F.running) then return false end
    if F.isLocked and F.isLocked() then return false end
    if W and W.isBusy and W.isBusy() then return false end
    if Fade and Fade.lockInput then return false end
    local _, s = E.party()
    return s ~= nil
  end
end

-- Crystal's intro: its text boxes, choices and the naming screens are answered with A, then START (a default name)
local function introStep()
  local id = topId()
  if id == "-" then return end
  if id:find("Naming") then
    if frame % 20 == 0 then tap("start") elseif frame % 20 == 10 then tap("a") end
  elseif frame % 8 == 0 then
    tap("a")
  end
end

function E.newGame()
  if FAM == "gen1" then return pcall(game.startNewGame, game, { intro = false }) end
  if FAM == "gs" then return pcall(game.newGame, game) end
  return pcall(game._handleBootAction, game, { action = "new_game", name = "TESTER", gender = 0 })
end

function E.bootWait()
  if FAM == "gs" then
    return waitUntil(function()
      local id = topId()
      if id == "Gen2TitleState" and frame % 10 == 0 then tap("start") end
      return id == "Gen2MainMenu"
    end, 12000)
  elseif FAM == "gen1" then
    return waitUntil(function() return top() ~= nil end, 1200)
  end
  local ok = waitUntil(function() return game ~= nil and game.phase ~= nil end, 1200)
  for _ = 1, 60 do yield() end
  return ok
end

local function isVisitor(mon) return type(mon) == "table" and (mon.species == STAND_IN or mon.speciesId == STAND_IN) end
local function visitor()
  local party = E.party() or {}
  for i, mon in ipairs(party) do
    if isVisitor(mon) then return mon, i end
  end
  return nil
end
-- the record's max HP and attack as the engine keeps them
local function stats(mon)
  if type(mon) ~= "table" then return nil end
  if FAM == "game3" then return mon.maxHp, mon.attack, mon.spDef end
  local s = mon.stats or {}
  return s.hp, s.attack, FAM == "gs" and s.specialDefense or s.special
end

function E.recompute(mon)
  if FAM == "gen1" then return pcall(require("src.pokemon.Stats").ensure, game.data.pokemon[mon.species], mon) end
  if FAM == "gs" then return pcall(require("src.battle.gen2.Mon").refreshStats, mon, game.data) end
  local _, s = E.party()
  return pcall(require("src.core.game3.pokemon").applyStats, mon, s)
end

local function screen(open, close, n)
  local okO, errO = pcall(open)
  if not okO then return "open:" .. errcode(errO) end
  local okS, errS = pcall(function() for _ = 1, n do yield() end end)
  local okC, errC = pcall(close)
  if not okS then return "step:" .. errcode(errS) end
  if not okC then return "close:" .. errcode(errC) end
  return "ok"
end

function E.screens(mon, index)
  local r, inst = {}, nil
  local party = E.party()
  local function popIt() if top() == inst then game.stack:pop() end end
  if FAM == "gen1" then
    r.party = screen(function() inst = require("src.ui.PartyMenu").new(game, { onCancel = function() end }); game.stack:push(inst) end, popIt, 60)
    r.summary = screen(function() inst = require("src.ui.SummaryMenu").new(game, mon); game.stack:push(inst) end, popIt, 60)
    r.hof = screen(function() inst = require("src.ui.HallOfFame").new(game, function() end); game.stack:push(inst) end, popIt, 240)
  elseif FAM == "gs" then
    r.party = screen(function() inst = require("src.ui.gen2.PartyMenu").new(game, { onCancel = function() end }); game.stack:push(inst) end, popIt, 60)
    r.summary = screen(function() inst = require("src.ui.gen2.SummaryMenu").new(game, { party = party, index = index }); game.stack:push(inst) end, popIt, 60)
    r.hof = screen(function() inst = require("src.ui.gen2.HallOfFame").new(game, { mode = "induct", onDone = function() end }); game.stack:push(inst) end, popIt, 240)
  else
    local PartyMenu, SummaryMenu = require("src.ui.game3.party_menu"), require("src.ui.game3.summary_menu")
    local HallOfFame = require("src.ui.game3.hall_of_fame")
    local _, s = E.party()
    r.party = screen(function() PartyMenu.show(party, nil, {}) end, function() PartyMenu.close() end, 60)
    r.summary = screen(function() SummaryMenu.openMenu(party, index, {}) end, function() SummaryMenu.close() end, 60)
    r.hof = screen(function() HallOfFame.start({ session = s, dontSave = true, warp = false, credits = false, onDone = function() end }) end,
      function() HallOfFame.close() end, 240)
  end
  return r
end

function E.cutUser()
  local ok, mon
  if FAM == "gen1" then
    local FieldDefaults = require("src.world.FieldDefaults")
    local gate = (FieldDefaults.constant(game.data, "hmBadges") or {}).CUT
    local badge = gate and gate.badge
    local had = badge and game.save.inventory[badge]
    if badge then game.save.inventory[badge] = 1 end
    ok, mon = pcall(game.overworld.partyKnows, game.overworld, "CUT")
    if badge then game.save.inventory[badge] = had end
  elseif FAM == "gs" then
    ok, mon = pcall(require("src.world.gen2.FieldMoves").partyMoveUser, game.save.party, "CUT")
  else
    ok, mon = pcall(require("src.core.game3.field_moves").partyMoveUser, E.party(), 15)
  end
  if not ok then return "threw:" .. errcode(mon) end
  return mon == nil and "none" or (isVisitor(mon) and "visitor" or "other")
end

-- The cartridge export as the game runs it (SaveFileIO.exportActiveSlot, src/import/SaveFileIO.lua:299-312: the saved
-- slot loaded, then SaveConvert.exportSav); gen1-bridge's wrappers sit under it. `strip` first takes the visitors out
-- of the loaded party by hand: the control, so a refusal for another reason shows as both failing.
function E.export(strip)
  local SaveData = require("src.core.SaveData")
  local okL, save = pcall(SaveData.load, VERSION)
  if not (okL and type(save) == "table") then return "load:" .. errcode(save) end
  if strip then
    local party = FAM == "game3" and type(save.party) ~= "table" and save.session and save.session.party or save.party
    if type(party) == "table" then
      for i = #party, 1, -1 do
        if isVisitor(party[i]) then table.remove(party, i) end
      end
    end
  end
  local ok, bytes, err = pcall(require("src.save_convert.SaveConvert").exportSav, save, VERSION)
  if not ok then return "threw:" .. errcode(bytes) end
  return bytes ~= nil and "encoded" or ("refused:" .. errcode(err))
end

function E.save()
  if FAM == "game3" then return pcall(game.saveGame, game) end
  return pcall(game.writeSave, game)
end

function E.continue()
  local SaveData = require("src.core.SaveData")
  if FAM == "gen1" then
    local loaded, recovered = SaveData.load(VERSION)
    if not loaded then return false, "no_save" end
    return pcall(game.restoreSave, game, loaded, recovered, { freshBoot = true, continued = true })
  elseif FAM == "gs" then
    local saved = require("src.core.gen2.Save").load(VERSION)
    if not saved then return false, "no_save" end
    return pcall(game.continueGame, game, saved)
  end
  return pcall(game._handleBootAction, game, { action = "continue" })
end

local function report(tag, extra)
  local mon, at = visitor()
  local hp, atk, sp = stats(mon)
  local kv = { "party", #(E.party() or {}), "visitor", mon ~= nil, "at", at or "-", "hp", hp or "-", "atk", atk or "-",
               "special", sp or "-", "nick", mon and type(mon.nickname) == "string" and #mon.nickname or "-",
               "name", FAM == "gs" and mon and tostring(mon.name) or "-" }
  for _, v in ipairs(extra or {}) do kv[#kv + 1] = v end
  out(tag, kv)
  return mon, at
end

-- ---------------------------------------------------------------- the run

return function(g)
  game = g
  if DATASET_MEMO > 0 then memoDatasetChecks(DATASET_MEMO) end
  local booted = E.bootWait()
  out("BOOT", { "version", VERSION, "family", FAM, "booted", booted, "top", topId() })
  if not booted then fail("no_boot") end
  local status
  waitUntil(function()
    status = game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    return status and status.ready
  end, 1200)
  if not (status and status.ready) then fail("no_bridge") end
  status.configure({ port = PORT })
  local SaveData = require("src.core.SaveData")
  local prev = SaveData.activeSlot(VERSION)
  local slot = SaveData.createSlot(VERSION)
  SaveData.setActiveSlot(VERSION, slot)
  local okN, errN = E.newGame()
  local inField = waitUntil(function()
    if FAM == "gs" then introStep() end
    return E.fieldReady()
  end, 40000)
  out("FIELD", { "call", res(okN, errN), "field", inField, "top", topId(), "port", status.port })
  if not inField then fail("no_field") end

  -- the test's party.sync makes the stand-in record
  local got = waitUntil(function() return visitor() ~= nil end, 60 * 120)
  if not got then fail("no_visitor") end
  for _ = 1, 30 do yield() end
  local mon, at = report("VISITOR", {})
  local hp0, atk0 = stats(mon)
  local okR, errR = E.recompute(mon)
  local hp1, atk1 = stats(mon)
  out("PIN", { "recompute", res(okR, errR), "kept", hp1 == hp0 and atk1 == atk0 })
  local shots = E.screens(mon, at)
  local hp2, atk2 = stats(mon)
  out("SCREENS", { "party", shots.party, "summary", shots.summary, "hof", shots.hof, "kept", hp2 == hp0 and atk2 == atk0,
                   "top", topId() })
  out("CUT", { "user", E.cutUser() })
  local okW, errW = E.save()
  out("SAVE", { "call", res(okW, errW) })
  out("EXPORT", { "result", E.export(false), "control", E.export(true) })
  local okC, errC = E.continue()
  local back = waitUntil(function() return E.fieldReady() end, 6000)
  for _ = 1, 30 do yield() end
  local after = report("AFTER_CONTINUE", { "call", res(okC, errC), "field", back })
  local hp3, atk3 = stats(after)
  out("DONE", { "kept", after ~= nil and hp3 == hp0 and atk3 == atk0, "seconds", math.floor(love.timer.getTime() - started) })

  waitUntil(function() return status.quitRequested end, 60 * 300)
  local okD = SaveData.deleteSlot(VERSION, slot)
  if prev then SaveData.setActiveSlot(VERSION, prev) end
  out("CLEANED", { "deleted", okD and true or false })
  print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
  love.event.quit(0)
  while true do coroutine.yield() end
end
