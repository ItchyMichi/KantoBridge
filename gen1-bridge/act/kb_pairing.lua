-- kb_pairing: online save pairing (M9). docs/PLAN.md section 4.1 "Saving and
-- sessions" ("session.bind loads or creates the save slot paired with the
-- world"), section 6 M9 and section 8 (the launcher fallback); DESIGN
-- section 8 "Saving": each multiplayer world gets its own paired online story
-- save, kept separate from the offline save.
--
--   session.bind{world, multiplayer}   M->G
--   session.bound{slot, switched}      G->M (re set): sent once the hidden
--                                      game IS on that slot; M10: switched =
--                                      how it got there: "none" (it already
--                                      was), "title" (moved at the title),
--                                      "saved" (the running game saved into
--                                      its own slot first, then the title),
--                                      "moved" (a New Game never saved went
--                                      on into the empty slot as it is)
--   session.waiting{slot, reason}      G->M (re = the bind's seq), M10: when
--                                      a bind starts waiting and whenever its
--                                      reason changes (busy, new_game,
--                                      save_failed)
--
-- The pairing, per game version: world id -> the engine's save slot id, and
-- "home", the offline slot to come back to. It lives in mod.cache
-- (installation-wide, never in a slot's own save: src/mods/ImportAccess.lua:
-- 72-122, under mod_cache/gen1-bridge/) as session/pairings.json:
--
--   {"format":1,"games":{"blue":{"home":"slot1",
--     "worlds":{"<world id>":{"slot":"slot2"}}}}}
--
-- written through a .tmp copy and kept with a .bak, so a launcher can read it
-- too: gen1recomp.exe --game=blue --slot=slot2 (the id form: a bare number is
-- a 1-based list index, src/core/LaunchOptions.lua:349-374).
--
-- What a bind does:
--   * multiplayer: the world's slot, created on its first bind (a new empty
--     slot, labelled "MULTIPLAYER <8 hex>" in Gen1Recomp's launcher). The
--     active slot, when it isn't a world's, is remembered as home first;
--   * singleplayer: the current slot, as before M9 -- unless the current slot
--     is a multiplayer world's: then home (a new empty slot when there is
--     none);
--   * then, when that isn't the active slot, the switch (kb_engine's slot
--     seams):
--       at the title (no live game)  switch now; an open title menu is
--                                    rebuilt (its CONTINUE row was the old
--                                    slot's)
--       a New Game never saved       switch now when the target is empty: the
--                                    game goes on, its first save lands there;
--                                    else wait (reason new_game) for the title
--       a saved game                 saved into its own slot first (the
--                                    engine's F1 gate; never announced as
--                                    save.begin: it belongs to the pairing
--                                    being left), switched, back to the title;
--                                    while the gate is closed: wait (reason
--                                    busy); a refused save: wait and retry
--                                    (reason save_failed)
--     While a switch waits, session.waiting says why (M10; and one warning,
--     session.switch_waiting, forwarded as a log message, for diagnostics);
--     Cobblemon's syncs for the new world are held (kb_party), bag.use is
--     refused (error session.pending) and the old game's saves aren't
--     announced (kb_save). A
--     newer bind replaces a waiting one; a dropped connection cancels it (the
--     client binds again on every link).
--   * A bind that needs a switch drops what came from Minecraft before it
--     (onPending: the previous world's syncs). The switch drops what the old
--     game still had for Minecraft (gifts, operations, a save to announce:
--     engine.dropPlaythrough); a New Game moved as it is keeps its own and
--     sends them again (onSwitch "fresh").
-- Refusals (error, re set): bad_message, session.no_game (no game version),
-- session.no_slots (no slot API, or a cart), session.no_slot (a slot can't
-- be created or made active), session.store_failed (the pairing can't be
-- written: the slot just created is taken back), session.failed.
-- Logs show codes, slot numbers, counts and the world id's length only.

local Pairing = {}

Pairing.FILE = "session/pairings.json"
Pairing.FORMAT = 1
Pairing.LABEL = "MULTIPLAYER "
Pairing.RETRY_SECONDS = 5
Pairing.MAX_SLOT = 255     -- session.bound.slot's maximum
Pairing.MAX_BYTES = 1024 * 1024

local ID = "^[A-Za-z0-9_.:-]+$"

local function validWorld(v)
  return type(v) == "string" and #v >= 1 and #v <= 64 and v:find(ID) ~= nil
end

local function slotNumber(id)
  return type(id) == "string" and tonumber(id:match("^slot(%d+)$")) or nil
end
Pairing.slotNumber = slotNumber

-- The protocol's slot: N of "slotN", 0 for none (the flat legacy save) or one
-- the protocol can't carry.
local function protocolSlot(id)
  local n = slotNumber(id)
  if not n or n < 0 or n > Pairing.MAX_SLOT then return 0 end
  return n
end

local function blank() return { format = Pairing.FORMAT, games = {} } end

-- The decoded file, checked field by field; nil when it isn't one.
function Pairing.decode(json, bytes)
  if type(bytes) ~= "string" or bytes == "" or #bytes > Pairing.MAX_BYTES then return nil end
  local t = json.decode(bytes, Pairing.MAX_BYTES)
  if type(t) ~= "table" or t.format ~= Pairing.FORMAT or type(t.games) ~= "table" then return nil end
  local out = blank()
  for game, rec in pairs(t.games) do
    if type(game) == "string" and game:find("^[a-z0-9_]+$") and type(rec) == "table" then
      local g = { worlds = {} }
      if slotNumber(rec.home) then g.home = rec.home end
      for world, w in pairs(type(rec.worlds) == "table" and rec.worlds or {}) do
        if validWorld(world) and type(w) == "table" and slotNumber(w.slot) then g.worlds[world] = { slot = w.slot } end
      end
      out.games[game] = g
    end
  end
  return out
end

-- Canonical text (kb_json sorts keys), or nil.
function Pairing.encode(json, data)
  local games = json.object({})
  for game, g in pairs(data.games) do
    local worlds = json.object({})
    for world, w in pairs(g.worlds) do worlds[world] = json.object({ slot = w.slot }) end
    games[game] = json.object({ home = g.home, worlds = worlds })
  end
  return (json.encode(json.object({ format = Pairing.FORMAT, games = games })))
end

-- opts:
--   engine   kb_engine (slotState, resolveActiveSlot, createSlot, deleteSlot,
--            labelSlot, slotRegistered, slotHasSave, activateSlot,
--            playthrough, titleMenuOpen, switchGateOpen, switchSave, toTitle,
--            dropPlaythrough, gameVersion)
--   json     kb_json
--   store    { read(rel) -> bytes|nil, write(rel, bytes) -> ok, remove(rel) }:
--            mod.cache, or nil (then only singleplayer binds can be answered)
--   hash     short hash for the slot label (kb_sha256.short)
--   clock    seconds
--   log      kb_log logger (optional)
--   onBound(world, multiplayer, slot, switched)   a bind answered (kb_save
--                    keeps it; M10: save.loaded follows it in game)
--   onPending()      a bind needs a switch: what came before it goes
--   onSwitch(kind)   a switch happened: "title", "fresh" or "saved"
function Pairing.new(opts)
  local engine, json, log, store, hash = opts.engine, opts.json, opts.log, opts.store, opts.hash
  local clock = opts.clock or function() return 0 end
  local onBound = opts.onBound or function() end
  local onPending = opts.onPending or function() end
  local onSwitch = opts.onSwitch or function() end
  local TMP, BAK = Pairing.FILE .. ".tmp", Pairing.FILE .. ".bak"

  local pairing = {}
  local data      -- the pairing file, decoded on first use
  local req       -- the bind being worked on
  local loaded = setmetatable({}, { __mode = "k" }) -- save tables a CONTINUE brought
  local counts = {
    binds = 0, answered = 0, superseded = 0, cancelled = 0, refused = 0,
    created = 0, reused = 0, homes = 0, purged = 0, relabelled = 0,
    switches = 0, switchTitle = 0, switchFresh = 0, switchSaved = 0, rebuilt = 0, homeReturns = 0,
    waits = 0, waitBusy = 0, waitNewGame = 0, waitSaveFailed = 0, saveFailed = 0, dropped = 0,
    storeWrites = 0, storeFailed = 0, storeRecovered = 0, storeUnreadable = 0,
    waitingSent = 0, -- M10: session.waiting messages
  }
  local WAIT_FIELD = { busy = "waitBusy", new_game = "waitNewGame", save_failed = "waitSaveFailed" }
  -- M10: a switch's kind as session.bound.switched (no switch: "none")
  local SWITCHED = { title = "title", saved = "saved", fresh = "moved" }

  -- ------------------------------------------------------------ the file

  local function readFile(rel)
    if not store then return nil end
    local ok, bytes = pcall(store.read, rel)
    return ok and type(bytes) == "string" and bytes or nil
  end

  local function load()
    if data then return data end
    for _, rel in ipairs({ Pairing.FILE, TMP, BAK }) do
      local okDecode, d = pcall(Pairing.decode, json, readFile(rel))
      if okDecode and d then
        data = d
        if rel ~= Pairing.FILE then
          counts.storeRecovered = counts.storeRecovered + 1
          if log then log.warn("session.store_recovered", { from = rel == TMP and "tmp" or "bak" }) end
        end
        return data
      end
    end
    if readFile(Pairing.FILE) ~= nil then
      counts.storeUnreadable = counts.storeUnreadable + 1
      if log then log.warn("session.store_unreadable", {}) end
    end
    data = blank()
    return data
  end

  -- Writes the file: the .tmp copy, read back, then the file, then the .bak.
  local function persist()
    local text = store and Pairing.encode(json, load()) or nil
    local function put(rel)
      local ok, done = pcall(store.write, rel, text)
      return ok and done ~= nil and done ~= false and readFile(rel) == text
    end
    if not (text and put(TMP) and put(Pairing.FILE)) then
      counts.storeFailed = counts.storeFailed + 1
      if log then log.warn("session.store_failed", { count = counts.storeFailed }) end
      return false
    end
    put(BAK)
    pcall(store.remove, TMP)
    counts.storeWrites = counts.storeWrites + 1
    return true
  end

  local function gameRec(version)
    local d = load()
    local g = d.games[version]
    if not g then
      g = { worlds = {} }
      d.games[version] = g
    end
    return g
  end

  local function worldSlot(g, id)
    if id == nil then return false end
    for _, w in pairs(g.worlds) do
      if w.slot == id then return true end
    end
    return false
  end

  local function hasWorlds(g) return next(g.worlds) ~= nil end

  -- ------------------------------------------------------------ deciding

  -- A new slot (labelled for a world), or nil plus the error code.
  local function newSlot(label)
    local id = engine.createSlot(label)
    if not id then return nil, "session.no_slot" end
    if (slotNumber(id) or 0) > Pairing.MAX_SLOT then
      engine.deleteSlot(id)
      return nil, "session.no_slot"
    end
    return id
  end

  -- The target slot of a bind (r.target; r.current when it is the current
  -- slot as it is) and whether it needs a switch. Slots are created here.
  -- Returns true, or nil plus an error code.
  local function decide(r)
    local s = engine.slotState()
    if not s.version then return nil, "session.no_game" end
    local usable = s.api and not s.cart
    if not r.multiplayer then
      local g = usable and gameRec(s.version) or nil
      if g and s.active and worldSlot(g, s.active) then
        -- coming back from a multiplayer world's slot: the offline one
        if not g.home or worldSlot(g, g.home) then
          local id, code = newSlot(nil)
          if not id then return nil, code end
          g.home = id
          if not persist() then
            g.home = nil
            engine.deleteSlot(id)
            return nil, "session.store_failed"
          end
          counts.homes = counts.homes + 1
        end
        r.target, r.home = g.home, true
      else
        -- as before M9: the current slot (0 for the flat legacy save); once
        -- worlds are paired, it is the home to come back to
        r.target, r.current = s.active, true
        if g and s.active and hasWorlds(g) and g.home ~= s.active then
          g.home = s.active
          persist()
        end
      end
      r.switch = r.target ~= s.active
      return true
    end
    if not usable then return nil, "session.no_slots" end
    local g = gameRec(s.version)
    local active = s.active or engine.resolveActiveSlot()
    local dirty = false
    if active and not worldSlot(g, active) and g.home ~= active then
      g.home, dirty = active, true
    end
    local w = g.worlds[r.world]
    if w then
      r.target = w.slot
      counts.reused = counts.reused + 1
      if dirty then persist() end
    else
      local id, code = newSlot(Pairing.LABEL .. (hash and hash(r.world) or ""))
      if not id then return nil, code end
      for world, other in pairs(g.worlds) do
        -- a slot the launcher deleted and createSlot handed out again
        if other.slot == id then
          g.worlds[world] = nil
          counts.purged = counts.purged + 1
        end
      end
      g.worlds[r.world] = { slot = id }
      if not persist() then
        g.worlds[r.world] = nil
        engine.deleteSlot(id)
        return nil, "session.store_failed"
      end
      r.target, r.created = id, true
      counts.created = counts.created + 1
    end
    r.switch = r.target ~= active
    return true
  end

  -- ------------------------------------------------------------ switching

  local function dropOld(kind)
    counts.dropped = counts.dropped + engine.dropPlaythrough()
    onSwitch(kind)
  end

  -- One try at the switch: "done", or "wait" plus a reason, or "error" plus
  -- a code.
  local function advance(r)
    if not r.switch then return "done" end
    local s = engine.slotState()
    if s.active == r.target then return "done" end
    local registered = engine.slotRegistered(r.target)
    local state = engine.playthrough(loaded)
    if state == "title" then
      if not engine.activateSlot(r.target) then return "error", "session.no_slot" end
      dropOld("title")
      if engine.titleMenuOpen() and engine.toTitle() then counts.rebuilt = counts.rebuilt + 1 end
      counts.switchTitle = counts.switchTitle + 1
      r.how = "title"
    elseif state == "fresh" then
      -- the game goes on into the target, so the target must be empty
      if engine.slotHasSave(r.target) then return "wait", "new_game" end
      if not engine.activateSlot(r.target) then return "error", "session.no_slot" end
      onSwitch("fresh")
      counts.switchFresh = counts.switchFresh + 1
      r.how = "fresh"
    else
      if not engine.switchGateOpen() then return "wait", "busy" end
      local res = engine.switchSave()
      if res ~= "written" then
        counts.saveFailed = counts.saveFailed + 1
        r.retryAt = clock() + Pairing.RETRY_SECONDS
        if log then log.warn("session.switch_save_failed", { result = res, count = counts.saveFailed }) end
        return "wait", "save_failed"
      end
      if not engine.activateSlot(r.target) then return "error", "session.no_slot" end
      dropOld("saved")
      engine.toTitle()
      counts.switchSaved = counts.switchSaved + 1
      r.how = "saved"
    end
    -- a world's slot the launcher deleted is registered again: label it
    if not registered and not r.home and not r.current and hash then
      if engine.labelSlot(r.target, Pairing.LABEL .. hash(r.world)) then counts.relabelled = counts.relabelled + 1 end
    end
    if r.home then counts.homeReturns = counts.homeReturns + 1 end
    counts.switches = counts.switches + 1
    return "done"
  end

  local function finish(out, r, res, why)
    if res == "done" then
      local slot = protocolSlot(r.target)
      -- M10: how the hidden game reached the slot (session.bound.switched)
      local switched = SWITCHED[r.how] or "none"
      out[#out + 1] = { "session.bound", { slot = slot, switched = switched }, re = r.seq, gen = r.gen }
      counts.answered = counts.answered + 1
      req = nil
      onBound(r.world, r.multiplayer, slot, switched)
      if log then
        log.info("session.bound", { slot = slot, multiplayer = r.multiplayer, worldLen = #r.world,
                                    switched = r.how or "no", created = r.created == true, count = counts.answered })
      end
    elseif res == "wait" then
      if r.waiting ~= why then
        r.waiting = why
        counts.waits = counts.waits + 1
        local field = WAIT_FIELD[why]
        if field then counts[field] = counts[field] + 1 end
        -- M10: the structured message, once when the wait starts and again
        -- whenever its reason changes (the log line stays for diagnostics)
        out[#out + 1] = { "session.waiting", { slot = protocolSlot(r.target), reason = why }, re = r.seq, gen = r.gen }
        counts.waitingSent = counts.waitingSent + 1
        if log then log.warn("session.switch_waiting", { reason = why, slot = protocolSlot(r.target) }) end
      end
    else
      local code = why or "session.failed"
      out[#out + 1] = { "error", { code = code, msg = code }, re = r.seq, gen = r.gen }
      counts.refused = counts.refused + 1
      req = nil
      if log then log.warn("session.refused", { code = code, count = counts.refused }) end
    end
  end

  -- ------------------------------------------------------------ the session's side

  -- session.bind from the session (role already checked): decides the target
  -- slot now, so that what arrives after it is the new world's; the switch
  -- and the answer come in step(). Returns true, or nil plus an error code.
  function pairing.bind(d, seq, generation)
    if type(d) ~= "table" or not validWorld(d.world) or type(d.multiplayer) ~= "boolean" then
      return nil, "bad_message"
    end
    counts.binds = counts.binds + 1
    if req then counts.superseded = counts.superseded + 1 end
    local r = { world = d.world, multiplayer = d.multiplayer, seq = seq, gen = generation }
    req = nil
    local ok, done, code = pcall(decide, r)
    if not ok then
      code = "session.failed"
      if log then
        log.warn("session.decide_failed", { at = type(done) == "string" and done:match("[%w_/%.%-]+%.lua:%d+") or "unknown" })
      end
    end
    if not (ok and done) then
      counts.refused = counts.refused + 1
      if log then log.warn("session.refused", { code = code, count = counts.refused }) end
      return nil, code
    end
    if r.switch then onPending() end
    req = r
    return true
  end

  -- One logic step, before anything looks at the game: the switch when it
  -- can run, and the answer. out gets session.bound or error replies
  -- ({ type, data, re = seq, gen = generation }); link = { live,
  -- generation }: the client that asked must still be there.
  function pairing.step(out, link)
    local r = req
    if not r then return end
    if not (link and link.live) or r.gen ~= link.generation then
      req = nil
      counts.cancelled = counts.cancelled + 1
      if log then log.info("session.switch_cancelled", { count = counts.cancelled }) end
      return
    end
    if r.retryAt and clock() < r.retryAt then return end
    r.retryAt = nil
    local ok, res, why = pcall(advance, r)
    if not ok then
      if log then
        log.warn("session.switch_failed", { at = type(res) == "string" and res:match("[%w_/%.%-]+%.lua:%d+") or "unknown" })
      end
      res, why = "error", "session.failed"
    end
    finish(out, r, res, why)
  end

  -- True while a bind waits for its switch: the new world's data waits too.
  function pairing.blocked() return req ~= nil and req.switch == true end

  -- save.loaded: this save table came from CONTINUE (not a fresh game).
  function pairing.loaded(save)
    if type(save) == "table" then loaded[save] = true end
  end

  -- ------------------------------------------------------------ for test drivers (exports)

  -- The slot id paired with a world (this game version), or nil.
  function pairing.slotOf(world)
    local version = engine.gameVersion()
    local g = version and load().games[version]
    local w = g and validWorld(world) and g.worlds[world] or nil
    return w and w.slot or nil
  end

  -- The home slot id (this game version), or nil.
  function pairing.home()
    local version = engine.gameVersion()
    local g = version and load().games[version]
    return g and g.home or nil
  end

  -- Forgets a world's pairing (its slot and save stay); returns the slot id
  -- it had, or nil.
  function pairing.forget(world)
    local version = engine.gameVersion()
    local g = version and load().games[version]
    local w = g and validWorld(world) and g.worlds[world] or nil
    if not w then return nil end
    g.worlds[world] = nil
    persist()
    return w.slot
  end

  function pairing.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    out.pending = pairing.blocked()
    local version = engine.gameVersion()
    local g = version and data and data.games[version] or nil
    local worlds = 0
    for _ in pairs(g and g.worlds or {}) do worlds = worlds + 1 end
    out.worlds = worlds
    out.home = g and protocolSlot(g.home) or 0
    return out
  end

  return pairing
end

return Pairing
