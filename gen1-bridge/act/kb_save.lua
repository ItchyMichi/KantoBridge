-- kb_save: saving and session pairing (docs/PLAN.md section 4.1 "Saving and
-- sessions", flow 12; docs/PROTOCOL.md "Saving").
--
--   save.begin{save, reason:"menu"|"center"}    G->M
--   save.done{save, ok}                         M->G (log only)
--   session.bind{world, multiplayer}            M->G
--   session.bound{slot}                         G->M (re set)
--
-- Every hidden-game save announces itself through the engine's save.writing
-- event (Game:writeSave, src/core/Game.lua:1328-1351), and Minecraft saves
-- with it: save.begin goes out with an id unique per gen1-bridge process
-- ("v-<token>-<n>") and a reason. "center" is the save gen1-bridge makes
-- itself after the nurse's heal; every other one (the start menu's SAVE,
-- F1, the Hall of Fame, the cable club) is "menu". A save made while no
-- client or test session is connected waits, the newest only (one
-- Minecraft save covers several hidden ones), and goes out once one is.
--
-- The Pokemon Center (PLAN 4.1, section 8's spike): kb_engine notices the
-- nurse's heal machine; Cobblemon's party heals (party.heal, through
-- kb_engine's heal queue, before anything else), and -- while a client or
-- test session is connected -- the hidden game saves itself (kb_engine
-- .centerSaveStep: Game:writeSave behind the engine's own quick-save gate)
-- once the nurse's lines are done and the player stands, which sends
-- save.begin{reason:"center"}. A save that is vetoed or fails is a counted
-- warning; the hidden game's own start-menu SAVE always works as before.
--
-- save.done is log only: the hidden game's save already happened and is
-- never blocked or rolled back. ok false is a counted warning
-- (save.minecraft_failed); a save id gen1-bridge never sent, or one already
-- answered, is counted and logged, never answered with an error.
--
-- session.bind (M7 basics; multiplayer pairing is M9): answered with the
-- hidden game's active save slot (kb_engine.saveSlot: N of "slotN", 0 for
-- the flat legacy save file). The world and the multiplayer flag are kept
-- in the hidden save's mod data (mod.save "session"), written again after
-- New Game or CONTINUE (whose save brings its own mod data). The world id
-- is the client's own and is never logged, only its length.
--
-- Logs show codes, reasons, ids and counts only.

local Save = {}

Save.MAX_SENT = 16 -- save ids remembered for save.done

local ID = "^[A-Za-z0-9_.:-]+$"

local function validId(v)
  return type(v) == "string" and #v >= 1 and #v <= 64 and v:find(ID) ~= nil
end

-- opts:
--   engine   kb_engine instance (savingReason, queueSave, takeSave,
--            nurseHealed, queueHeal, armCenterSave, centerSaveStep,
--            centerSaveArmed, saveSlot, inGame)
--   store    { get(key), set(key, value) }: mod.save
--   log      kb_log logger (optional)
function Save.new(opts)
  local engine, log, store = opts.engine, opts.log, opts.store
  local saves = {}
  local sent, order = {}, {} -- save id -> { reason, done }; ids, oldest first
  local bound                -- the last session.bind: { world, multiplayer, slot }
  local counts = {
    writes = 0, menu = 0, center = 0, collapsed = 0, begun = 0,
    done = 0, ok = 0, failed = 0, unknown = 0, repeated = 0,
    heals = 0, centerArmed = 0, centerOffline = 0, centerWritten = 0, centerRefused = 0,
    centerFailed = 0, centerDropped = 0,
    binds = 0, storeWrites = 0,
  }

  local function remember(id, reason)
    sent[id] = { reason = reason }
    order[#order + 1] = id
    while #order > Save.MAX_SENT do
      sent[table.remove(order, 1)] = nil
    end
  end

  -- save.writing (inside Game:writeSave, before the file is written).
  function saves.writing()
    local reason = engine.savingReason() == "center" and "center" or "menu"
    local id, replaced = engine.queueSave(reason)
    if not id then return end
    counts.writes = counts.writes + 1
    counts[reason] = counts[reason] + 1
    if replaced then counts.collapsed = counts.collapsed + 1 end
    if log then log.info("save.writing", { save = id, reason = reason, count = counts.writes }) end
  end

  -- Before the step's party.* messages: a nurse heal heals Cobblemon's
  -- party (party.heal goes out this very step) and, while live, arms the
  -- center save. link = { live, generation }.
  function saves.watch(link)
    local heal = engine.nurseHealed()
    if not heal then return end
    counts.heals = counts.heals + 1
    engine.queueHeal()
    local armed = false
    if link and link.live then
      armed = engine.armCenterSave()
      if armed then counts.centerArmed = counts.centerArmed + 1 end
    else
      counts.centerOffline = counts.centerOffline + 1
    end
    if log then
      log.info("save.nurse_heal", { map = heal.map, town = heal.outdoor, armed = armed, count = counts.heals })
    end
  end

  -- After the step's party.* and bag.sync: the center save when it may run,
  -- then the save waiting to be announced (live only). out gets
  -- { "save.begin", data, "save" }.
  function saves.step(out, link)
    local res = engine.centerSaveStep()
    if res == "written" then
      counts.centerWritten = counts.centerWritten + 1
      if log then log.info("save.center_written", { count = counts.centerWritten }) end
    elseif res == "refused" then
      counts.centerRefused = counts.centerRefused + 1
      if log then log.warn("save.center_refused", { count = counts.centerRefused }) end
    elseif res == "error" then
      counts.centerFailed = counts.centerFailed + 1
      if log then log.warn("save.center_failed", { count = counts.centerFailed }) end
    elseif res == "dropped" then
      counts.centerDropped = counts.centerDropped + 1
      if log then log.info("save.center_dropped", { count = counts.centerDropped }) end
    end
    if not (link and link.live) then return end
    local q = engine.takeSave()
    if q then
      out[#out + 1] = { "save.begin", { save = q.save, reason = q.reason }, "save" }
      remember(q.save, q.reason)
      counts.begun = counts.begun + 1
      if log then log.info("save.begin", { save = q.save, reason = q.reason, count = counts.begun }) end
    end
  end

  -- save.done from the session (role already checked). Returns true, or
  -- false plus bad_message.
  function saves.done(data)
    if type(data) ~= "table" or not validId(data.save) or type(data.ok) ~= "boolean" then
      return false, "bad_message"
    end
    local rec = sent[data.save]
    if not rec then
      counts.unknown = counts.unknown + 1
      if log then log.warn("save.unknown_done", { count = counts.unknown }) end
      return true
    end
    if rec.done then
      counts.repeated = counts.repeated + 1
      if log then log.debug("save.repeated_done", { count = counts.repeated }) end
      return true
    end
    rec.done = true
    counts.done = counts.done + 1
    if data.ok then
      counts.ok = counts.ok + 1
      if log then log.info("save.done", { save = data.save, reason = rec.reason, ok = true }) end
    else
      counts.failed = counts.failed + 1
      if log then log.warn("save.minecraft_failed", { save = data.save, reason = rec.reason, count = counts.failed }) end
    end
    return true
  end

  -- Keeps the bound world in the hidden save's mod data. From a bind only
  -- in game: at the title the bucket is the boot skeleton's, and the save
  -- New Game makes or CONTINUE loads brings its own, which restore() then
  -- fills (its bucket is adopted before save.created / save.loaded,
  -- src/core/Game.lua:186-187, :1500).
  local function store_(anytime)
    if not (bound and store) or not (anytime or engine.inGame()) then return false end
    local ok = pcall(store.set, "session", { world = bound.world, multiplayer = bound.multiplayer, slot = bound.slot })
    if ok then counts.storeWrites = counts.storeWrites + 1 end
    return ok
  end

  -- session.bind from the session (role already checked). Returns the
  -- slot, or nil plus bad_message.
  function saves.bind(data)
    if type(data) ~= "table" or not validId(data.world) or type(data.multiplayer) ~= "boolean" then
      return nil, "bad_message"
    end
    local slot = engine.saveSlot()
    bound = { world = data.world, multiplayer = data.multiplayer, slot = slot }
    counts.binds = counts.binds + 1
    local stored = store_()
    if log then
      log.info("session.bound", { slot = slot, multiplayer = data.multiplayer, worldLen = #data.world,
                                  stored = stored, count = counts.binds })
    end
    return slot
  end

  -- New Game or CONTINUE: the bound world goes into the new save's mod data.
  function saves.restore() store_(true) end

  function saves.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    out.bound = bound ~= nil
    out.slot = bound and bound.slot or 0
    out.multiplayer = bound ~= nil and bound.multiplayer == true
    local ok, rec = false, nil
    if store then ok, rec = pcall(store.get, "session") end
    out.stored = ok and type(rec) == "table" and bound ~= nil and rec.world == bound.world or false
    out.centerPending = engine.centerSaveArmed()
    return out
  end

  return saves
end

return Save
