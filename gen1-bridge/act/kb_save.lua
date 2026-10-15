-- kb_save: saving and session pairing (docs/PLAN.md section 4.1 "Saving and
-- sessions", flow 12; docs/PROTOCOL.md "Saving").
--
--   save.begin{save, reason:"menu"|"center", pair, at?}   G->M
--   save.done{save, ok}                                   M->G (log only)
--   session.bind{world, multiplayer}                      M->G
--   session.bound{slot, switched}                         G->M (re set)
--   save.loaded{fresh, ops, pair?, at?, daycare?, boarded?} G->M (M10)
--
-- Every hidden-game save announces itself through the engine's save.writing
-- event (Game:writeSave, src/core/Game.lua:1362-1385), and Minecraft saves
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
-- M9: the save act/kb_pairing.lua makes before a slot switch (reason
-- "switch") belongs to the pairing being left, so it is never announced;
-- while a switch waits (link.blocked), saves wait unannounced too (the
-- switch then drops them with the old game).
--
-- M10, save pairs (crash recovery: DESIGN section 9, "if one side crashes,
-- the other pauses and resyncs from the last save"). Each hidden save gets
-- a pair: 16 hex digits, new for every save, written into that save's own
-- mod data (mod.save "savePair" = { pair, at, ops }) during its
-- save.writing -- the engine serializes that with the save in one file
-- (lib/kb_engine.lua "reliability (M10)") -- and announced as
-- save.begin.pair, with at = the save's meta.savedAt (Unix seconds). A
-- CONTINUE (or F2) of that save, in this process or after a crash or a
-- restart, finds the same record in the loaded save's mod data, so
-- save.loaded.pair is that pair again. A save written before M10 (no
-- record) gets a pair derived from its own meta (savedAt, sessionStart, the
-- engine that wrote it), the same on every load.
--   ops counts the Pokemon operations gen1-bridge has SENT (party.give,
-- party.take, daycare.deposit, daycare.withdraw; each once, when it first
-- goes out: a repeat after a snapshot is the same operation) since the
-- running save was loaded or its save.begin went out (stream order: reset
-- as save.begin goes out, so it lines up with the server's count, reset
-- when it handles that save.begin). An operation the hidden game made
-- after a save that is still waiting to be announced (no client yet) waits
-- until that save.begin is out (kb_party, engine.queuedSaveSerial), so the
-- server saves before it and counts it after, as the hidden save did.
--   The save before a slot switch (never announced) keeps the pairing
-- being left's identity: its record is the last pair that world heard of
-- with ops = the operations sent since plus those the switch drops unsent,
-- so coming back to that world later compares like for like.
--   save.loaded{fresh, ops, pair, at, daycare, boarded}: after every session.bound
-- while in game (behind this step's party.* and save.begin, so the server
-- pairs a waiting save before it compares), and when the hidden game enters
-- the world from the title -- CONTINUE: fresh false with the loaded save's
-- pair; NEW GAME: fresh true, no pair -- while a client or test session is
-- connected. Never at the title. daycare is the Cobblemon uid of the
-- Pokemon in the hidden daycare, read from the save itself (its daycare and
-- the boarded uid kb_party keeps in its mod data), so it rolls back with
-- the save; boarded names them all when the daycare holds more than one
-- (Gen 3's holds two; engine.daycareUids). The running save's identity lives in the process-lifetime slot
-- (kb_engine.persistent), so a hot reload keeps it.
--
-- session.bind (M7 basics; M9's pairing is act/kb_pairing.lua): once the
-- pairing answers session.bound{slot}, the world, the multiplayer flag and
-- the slot are kept in the hidden save's mod data (mod.save "session"),
-- written again after New Game or CONTINUE (whose save brings its own mod
-- data). The world id is the client's own and is never logged, only its
-- length.
--
-- Logs show codes, reasons, ids, short pairs and counts only.

local Save = {}

Save.MAX_SENT = 16 -- save ids remembered for save.done
Save.PAIR_KEY = "savePair"
Save.PAIR_DIGITS = 16

local ID = "^[A-Za-z0-9_.:-]+$"
local UID = "^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$"
local MAX_BOARDED = 3 -- save.loaded.boarded's maxItems
local floor = math.floor

local function validId(v)
  return type(v) == "string" and #v >= 1 and #v <= 64 and v:find(ID) ~= nil
end

local function validPair(v)
  return type(v) == "string" and #v >= 8 and #v <= 64 and v:find("^[0-9a-f]+$") ~= nil
end

local function count(v)
  return type(v) == "number" and v == floor(v) and v >= 0 and v <= 9007199254740991
end

-- A save's mod-data record { pair, at, ops }, checked; nil when it isn't one.
function Save.record(v)
  if type(v) ~= "table" or not validPair(v.pair) then return nil end
  return { pair = v.pair, at = count(v.at) and v.at or nil, ops = count(v.ops) and v.ops or 0 }
end

-- opts:
--   engine   kb_engine instance (savingReason, queueSave, takeSave,
--            nurseHealed, queueHeal, armCenterSave, centerSaveStep,
--            centerSaveArmed, inGame; M10: save, metaOf, persistent,
--            bridgeToken, gifts, ops, daycareUid, daycareUids)
--   store    { get(key), set(key, value) }: mod.save
--   hex      M10: SHA-256 as lowercase hex (kb_sha256.hex), for pairs
--   seed     M10: a string unique to this start (the session id)
--   clock    seconds (love.timer.getTime), part of a pair's input
--   log      kb_log logger (optional)
function Save.new(opts)
  local engine, log, store = opts.engine, opts.log, opts.store
  local hex = opts.hex
  local seed = opts.seed or ""
  local clock = opts.clock or function() return 0 end
  local saves = {}
  local sent, order = {}, {} -- save id -> { reason, done }; ids, oldest first
  local bound                -- the last session.bind: { world, multiplayer, slot }
  local counts = {
    writes = 0, menu = 0, center = 0, collapsed = 0, begun = 0,
    done = 0, ok = 0, failed = 0, unknown = 0, repeated = 0,
    heals = 0, centerArmed = 0, centerOffline = 0, centerWritten = 0, centerRefused = 0,
    centerFailed = 0, centerDropped = 0,
    binds = 0, storeWrites = 0,
    switchWrites = 0, -- M9: saves before a slot switch (never announced)
    -- M10: save pairs
    pairs = 0, pairsKept = 0, pairStoreFailed = 0, legacyPairs = 0, loads = 0, newGames = 0,
    loadedSent = 0, opsSent = 0,
  }
  local fallback = {} -- the identity's home before game.ready (no process slot yet)

  local function remember(id, reason)
    sent[id] = { reason = reason }
    order[#order + 1] = id
    while #order > Save.MAX_SENT do
      sent[table.remove(order, 1)] = nil
    end
  end

  -- ------------------------------------------------------------ save pairs (M10)

  local function state()
    local ok, t = pcall(engine.persistent, "savePairs")
    return ok and type(t) == "table" and t or fallback
  end

  local function short(pair) return type(pair) == "string" and pair:sub(1, 8) or nil end

  -- A new pair: the first instance's token and a counter that outlives hot
  -- reloads make it unique in this process; the start's seed, the clocks and
  -- a fresh table's address across processes.
  local function newPair(meta)
    local ps = state()
    ps.made = (ps.made or 0) + 1
    local input = table.concat({
      "pair", tostring(engine.bridgeToken()), tostring(ps.made), seed, tostring(meta.savedAt),
      tostring(os.time()), ("%.6f"):format(clock()), tostring({}),
    }, "|")
    counts.pairs = counts.pairs + 1
    return hex(input):sub(1, Save.PAIR_DIGITS)
  end

  -- a save with no record of ours (written before M10, or without
  -- gen1-bridge): a pair from its own meta, the same on every load
  local function legacyPair(meta)
    counts.legacyPairs = counts.legacyPairs + 1
    return hex(table.concat({ "legacy", tostring(meta.savedAt), tostring(meta.sessionStart),
                              tostring(meta.engine) }, "|")):sub(1, Save.PAIR_DIGITS)
  end

  local function readRecord()
    if not store then return nil end
    local ok, rec = pcall(store.get, Save.PAIR_KEY)
    return ok and Save.record(rec) or nil
  end

  local function writeRecord(rec)
    if not store then return false end
    local ok = pcall(store.set, Save.PAIR_KEY, { pair = rec.pair, at = rec.at, ops = rec.ops })
    if not ok then
      counts.pairStoreFailed = counts.pairStoreFailed + 1
      if log then log.warn("save.pair_store_failed", { count = counts.pairStoreFailed }) end
    end
    return ok
  end

  -- The identity of a save table as it is (the live one: its mod data is
  -- the current mod.save bucket): fresh (a New Game never saved), or the
  -- pair and ops its record carries.
  local function fromSave(save)
    local meta = engine.metaOf(save)
    local rec = readRecord()
    if rec then
      return { save = save, fresh = false, pair = rec.pair, at = rec.at or meta.savedAt, ops = rec.ops }
    end
    if meta.savedAt == nil then return { save = save, fresh = true, ops = 0 } end
    return { save = save, fresh = false, pair = legacyPair(meta), at = meta.savedAt, ops = 0, legacy = true }
  end

  -- The running save's identity, or nil outside a playthrough.
  local function current()
    local save = engine.save()
    if type(save) ~= "table" then return nil end
    local ps = state()
    if type(ps.cur) ~= "table" or ps.cur.save ~= save then ps.cur = fromSave(save) end
    return ps.cur
  end

  -- Gifts and operations of the running save that haven't gone out yet.
  local function unsent()
    local n = 0
    for _, g in ipairs(engine.gifts()) do
      if g.state == "new" then n = n + 1 end
    end
    for _, op in ipairs(engine.ops()) do
      if op.state == "new" then n = n + 1 end
    end
    return n
  end

  -- save.created (New Game; at boot also the title's skeleton): a fresh
  -- playthrough. save is the payload's save (engine.save() isn't known yet
  -- at boot).
  function saves.created(save)
    if type(save) ~= "table" then return end
    local ps = state()
    ps.cur = { save = save, fresh = true, ops = 0 }
    ps.enterDue = true
    counts.newGames = counts.newGames + 1
  end

  -- save.loaded (CONTINUE, F2): the loaded save's own record.
  function saves.loadedSave(save)
    if type(save) ~= "table" then return end
    local ps = state()
    ps.cur = fromSave(save)
    ps.enterDue = true
    counts.loads = counts.loads + 1
    if log then
      log.info("save.loaded_game", { fresh = ps.cur.fresh, pair = short(ps.cur.pair), ops = ps.cur.ops,
                                     legacy = ps.cur.legacy == true, count = counts.loads })
    end
  end

  -- One Pokemon operation went out for the first time (kb_party).
  function saves.opSent()
    local cur = current()
    if cur then
      cur.ops = (cur.ops or 0) + 1
      counts.opsSent = counts.opsSent + 1
    end
  end

  -- ------------------------------------------------------------ saving

  -- save.writing (inside Game:writeSave, after the meta stamp, before the
  -- file is written).
  function saves.writing()
    local saving = engine.savingReason()
    local meta = engine.metaOf()
    local cur = current()
    if saving == "switch" then
      -- M9: the old game saved into its own slot before a switch; M10: it
      -- keeps the identity the world being left knows
      counts.switchWrites = counts.switchWrites + 1
      local ops = (cur and cur.ops or 0) + unsent()
      local rec
      if cur and not cur.fresh and validPair(cur.pair) then
        rec = { pair = cur.pair, at = cur.at, ops = ops }
      else
        rec = { pair = newPair(meta), at = meta.savedAt, ops = ops }
      end
      writeRecord(rec)
      counts.pairsKept = counts.pairsKept + 1
      if log then
        log.info("save.switch_writing", { count = counts.switchWrites, pair = short(rec.pair), ops = rec.ops })
      end
      return
    end
    local reason = saving == "center" and "center" or "menu"
    local pair = newPair(meta)
    writeRecord({ pair = pair, at = meta.savedAt, ops = 0 })
    local id, replaced = engine.queueSave(reason, { pair = pair, at = meta.savedAt })
    if not id then return end
    counts.writes = counts.writes + 1
    counts[reason] = counts[reason] + 1
    if replaced then counts.collapsed = counts.collapsed + 1 end
    if log then log.info("save.writing", { save = id, reason = reason, pair = short(pair), count = counts.writes }) end
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
  -- then the save waiting to be announced (live only, and not while a slot
  -- switch waits: link.blocked). out gets { "save.begin", data, "save" }.
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
    if not (link and link.live) or link.blocked then return end
    local q = engine.takeSave()
    if q then
      local pair = q.pair
      if not validPair(pair) then
        -- queued without one (cannot happen in one process): a fresh one
        pair = newPair(engine.metaOf())
        if log then log.warn("save.pair_missing", { save = q.save }) end
      end
      local data = { save = q.save, reason = q.reason, pair = pair }
      if count(q.at) then data.at = q.at end
      out[#out + 1] = { "save.begin", data, "save" }
      remember(q.save, q.reason)
      counts.begun = counts.begun + 1
      -- stream order: the operations count again from this save
      if q.current then
        local cur = current()
        if cur then cur.fresh, cur.pair, cur.at, cur.ops, cur.legacy = false, pair, data.at, 0, nil end
      end
      if log then
        log.info("save.begin", { save = q.save, reason = q.reason, pair = short(pair), count = counts.begun })
      end
    end
  end

  -- After saves.step: save.loaded when a bind was answered or the game was
  -- entered (out gets { "save.loaded", data, "save" }). In game only, live
  -- only, not while a switch waits.
  function saves.loadedStep(out, link)
    local ps = state()
    if not (ps.bindDue or ps.enterDue) then return end
    if not (link and link.live) or not engine.inGame() then
      -- no client (the next bind tells it) or at the title (the game that
      -- enters tells it)
      ps.bindDue, ps.enterDue = false, false
      return
    end
    if link.blocked then return end
    local cur = current()
    if not cur then return end
    local data = { fresh = cur.fresh == true, ops = count(cur.ops) and cur.ops or 0 }
    if not data.fresh then
      data.pair = cur.pair
      if count(cur.at) then data.at = cur.at end
    end
    local okUid, uid = pcall(engine.daycareUid)
    if okUid and type(uid) == "string" and uid:find(UID) and uid == uid:lower() then data.daycare = uid end
    -- a daycare that holds several (Gen 3's two) names them all
    local okAll, all = pcall(engine.daycareUids)
    if data.daycare and okAll and type(all) == "table" and #all > 1 then
      local list = {}
      for _, u in ipairs(all) do
        if type(u) == "string" and u:find(UID) and u == u:lower() and #list < MAX_BOARDED then list[#list + 1] = u end
      end
      if #list > 1 then data.boarded = list end
    end
    out[#out + 1] = { "save.loaded", data, "save" }
    ps.bindDue, ps.enterDue = false, false
    counts.loadedSent = counts.loadedSent + 1
    if log then
      log.info("save.loaded", { fresh = data.fresh, pair = short(data.pair), ops = data.ops,
                                daycare = data.daycare ~= nil, boarded = data.boarded and #data.boarded or nil,
                                count = counts.loadedSent })
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
  -- src/core/Game.lua:188-189, :1563).
  local function store_(anytime)
    if not (bound and store) or not (anytime or engine.inGame()) then return false end
    local ok = pcall(store.set, "session", { world = bound.world, multiplayer = bound.multiplayer, slot = bound.slot })
    if ok then counts.storeWrites = counts.storeWrites + 1 end
    return ok
  end

  -- A bind the pairing answered with session.bound{slot} (act/kb_pairing.lua):
  -- kept in the hidden save's mod data while in game. M10: save.loaded
  -- follows in this step while in game.
  function saves.bound(world, multiplayer, slot)
    state().bindDue = true
    if not validId(world) then return end
    bound = { world = world, multiplayer = multiplayer == true, slot = slot }
    counts.binds = counts.binds + 1
    store_()
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
    -- M10: the running save's identity, as counts and booleans
    local okCur, cur = pcall(current)
    if okCur and cur then
      out.fresh = cur.fresh == true
      out.ops = count(cur.ops) and cur.ops or 0
      out.legacy = cur.legacy == true
    end
    return out
  end

  return saves
end

return Save
