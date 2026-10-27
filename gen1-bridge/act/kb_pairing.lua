-- kb_pairing: online save pairing (M9). docs/PLAN.md section 4.1 "Saving and
-- sessions" ("session.bind loads or creates the save slot paired with the
-- world"), section 6 M9 and section 8 (the launcher fallback); DESIGN
-- section 8 "Saving": each multiplayer world gets its own paired online story
-- save, kept separate from the offline save.
--
--   session.bind{world, multiplayer, incompatible?}   M->G
--
-- FP1 (docs/specs/FOREIGN_POKEMON_PLAN.md section 4.15): `incompatible` is the
-- link's region record's rules.compat.onIncompatible ("visitor", the default,
-- or "leave_out"); the latest bind's value is kept for the party
-- (pairing.incompatible()).
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
-- 72-122, under mod_cache/gen1-bridge/), one file per game since MG1
-- (docs/specs/MULTI_GAME_PLAN.md §4.3: one process per game, each writing
-- its own file, so two processes of one install never rewrite each other's),
-- session/pairings.<game>.json, holding that game's entry only:
--
--   {"format":1,"games":{"blue":{"home":"slot1",
--     "worlds":{"<world id>":{"slot":"slot2"}}}}}
--
-- written through a .tmp copy and kept with a .bak, so a launcher can read it
-- too: gen1recomp.exe --game=blue --slot=slot2 (the id form: a bare number is
-- a 1-based list index, src/core/LaunchOptions.lua:349-374). The legacy
-- install-wide file, session/pairings.json (every game's entries in one
-- file), is read ONCE, when the game's own file doesn't exist yet, for this
-- game's entry; the entry lands in the per-game file on the next write. The
-- legacy file is never rewritten or deleted.
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
-- WORLD_SESSIONS_PLAN WS2 (§2.3; D3, D13, D14): a bind may name the slot the
-- server recorded for this player and world (`slot`) and the player's
-- first-join choice (`save`):
--   * slot      that slot, singleplayer or multiplayer (a multiplayer world's
--               pairing is pointed at it); one the engine no longer has is
--               refused session.no_slot (the client clears the choice and asks
--               again);
--   * current   the engine's current (offline) slot, as a singleplayer bind
--               always did;
--   * copy      the world's own slot written from a copy of the offline slot
--               (the active slot when it isn't a world's, else home) with the engine's own
--               duplicateSlot (DESIGN §8: online and offline stay separate);
--               an offline slot without a save gives an empty slot ("new"); a
--               copy the engine refuses falls back to pairing the offline slot
--               itself (D14; applied "current", counted copyFallbacks);
--   * new       an empty slot of the world's own (multiplayer: as before WS2).
-- The world's pairing records what was chosen, so later binds without a
-- choice reuse it; session.bound says the choice applied (`save`). The switch
-- seams below are unchanged. `continue` (WS3) is checked and kept for the
-- continue step (act/kb_continue.lua).
--
-- WORLD_SESSIONS_PLAN WS5 (§2.5, D6): session.close{} M->G after Minecraft
-- left the world (sent only for a game whose quit save worked, D7): the
-- region hold is released, a game in play goes back to its title
-- (engine.toTitle) and, when the active slot is a world's, the home (offline)
-- slot is made active again (PROGRESS question 3's first option): answered
-- session.closed{title, home?} (home: the slot switched back to).
--
-- Refusals (error, re set): bad_message, session.no_game (no game version),
-- session.no_slots (no slot API, or a cart), session.no_slot (a slot can't
-- be created or made active), session.store_failed (the pairing can't be
-- written: the slot just created is taken back), session.failed.
-- Logs show codes, slot numbers, counts and the world id's length only.

local Pairing = {}

-- the legacy install-wide file (before MG1), read once for the game's entry
Pairing.LEGACY_FILE = "session/pairings.json"
Pairing.FORMAT = 1
Pairing.LABEL = "MULTIPLAYER "
Pairing.RETRY_SECONDS = 5
Pairing.MAX_SLOT = 255     -- session.bound.slot's maximum
Pairing.MAX_BYTES = 1024 * 1024

local ID = "^[A-Za-z0-9_.:-]+$"
local INCOMPATIBLE = { visitor = true, leave_out = true } -- FP1: session.bind.incompatible
local SAVES = { current = true, copy = true, new = true }  -- WS2: session.bind.save
local CONTINUES = { play = true, park = true, title = true } -- WS3: session.bind.continue

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

-- MG1: the game's own pairing file (its id is a version id, [a-z0-9_]+).
function Pairing.fileFor(game)
  return "session/pairings." .. game .. ".json"
end

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
--   onContinue(r, bound, done)  WS3: a settled bind whose continue is play or
--                    park; session.bound waits for done(bound) (kb_continue)"
function Pairing.new(opts)
  local engine, json, log, store, hash = opts.engine, opts.json, opts.log, opts.store, opts.hash
  local clock = opts.clock or function() return 0 end
  local onBound = opts.onBound or function() end
  local onPending = opts.onPending or function() end
  local onSwitch = opts.onSwitch or function() end
  local onContinue = opts.onContinue -- WS3
  local deferred = {} -- WS3: session.bound messages kb_continue finished, sent at the next step

  local pairing = {}
  local data      -- the pairing file, decoded on first use
  local FILE, TMP, BAK -- MG1: the game's own file, named on first use (the game is known by then)
  local req       -- the bind being worked on
  local incompatible = "visitor" -- FP1: the latest bind's onIncompatible
  local loaded = setmetatable({}, { __mode = "k" }) -- save tables a CONTINUE brought
  local counts = {
    binds = 0, answered = 0, superseded = 0, cancelled = 0, refused = 0,
    created = 0, reused = 0, homes = 0, purged = 0, relabelled = 0,
    switches = 0, switchTitle = 0, switchFresh = 0, switchSaved = 0, rebuilt = 0, homeReturns = 0,
    waits = 0, waitBusy = 0, waitNewGame = 0, waitSaveFailed = 0, saveFailed = 0, dropped = 0,
    storeWrites = 0, storeFailed = 0, storeRecovered = 0, storeUnreadable = 0,
    waitingSent = 0, -- M10: session.waiting messages
    storeLegacy = 0, -- MG1: the legacy install-wide file gave this game's entry
    recorded = 0, recordedMissing = 0, copies = 0, copyFallbacks = 0, copyEmpty = 0, newSlots = 0, -- WS2
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

  -- MG1: the game's own file, once the engine names the game (a version id,
  -- src/core/GameVersion.lua:200); nil before that.
  local function fileName()
    if FILE then return FILE end
    local version = engine.gameVersion()
    if type(version) ~= "string" or not version:find("^[a-z0-9_]+$") or #version > 16 then return nil end
    FILE = Pairing.fileFor(version)
    TMP, BAK = FILE .. ".tmp", FILE .. ".bak"
    return FILE
  end

  local function load()
    if data then return data end
    local file = fileName()
    if file then
      for _, rel in ipairs({ file, TMP, BAK }) do
        local okDecode, d = pcall(Pairing.decode, json, readFile(rel))
        if okDecode and d then
          data = d
          if rel ~= file then
            counts.storeRecovered = counts.storeRecovered + 1
            if log then log.warn("session.store_recovered", { from = rel == TMP and "tmp" or "bak" }) end
          end
          return data
        end
      end
      if readFile(file) ~= nil then
        counts.storeUnreadable = counts.storeUnreadable + 1
        if log then log.warn("session.store_unreadable", {}) end
      else
        -- MG1: no file of its own yet: the legacy install-wide file's entry
        -- for this game, read once (the file itself is left as it is)
        local okLegacy, legacy = pcall(Pairing.decode, json, readFile(Pairing.LEGACY_FILE))
        local version = engine.gameVersion()
        local entry = okLegacy and legacy and legacy.games[version] or nil
        if entry then
          data = blank()
          data.games[version] = entry
          counts.storeLegacy = counts.storeLegacy + 1
          if log then log.info("session.store_legacy", { worlds = (function()
            local n = 0
            for _ in pairs(entry.worlds) do n = n + 1 end
            return n
          end)() }) end
          return data
        end
      end
    end
    data = blank()
    return data
  end

  -- Writes the file: the .tmp copy, read back, then the file, then the .bak.
  local function persist()
    local file = fileName()
    local text = store and file and Pairing.encode(json, load()) or nil
    local function put(rel)
      local ok, done = pcall(store.write, rel, text)
      return ok and done ~= nil and done ~= false and readFile(rel) == text
    end
    if not (text and put(TMP) and put(file)) then
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
    -- WS2: a recorded slot, or a first-join choice
    if r.slot ~= nil or r.save ~= nil then
      if not usable then return nil, "session.no_slots" end
      local g = gameRec(s.version)
      local active = s.active or engine.resolveActiveSlot()
      -- the offline save: the active slot when it isn't a world's (what the player plays offline now), else home
      local offline = (active and not worldSlot(g, active) and active)
        or (g.home and not worldSlot(g, g.home) and g.home) or nil
      if r.slot ~= nil then
        local id = "slot" .. r.slot
        if not engine.slotRegistered(id) then
          counts.recordedMissing = counts.recordedMissing + 1
          return nil, "session.no_slot"
        end
        r.target, r.applied = id, r.save
        counts.recorded = counts.recorded + 1
      elseif r.save == "current" then
        r.target, r.current, r.applied = offline or active, true, "current"
      else
        local w = g.worlds[r.world]
        if w and engine.slotRegistered(w.slot) then
          -- the world was paired before on this install: its slot again
          r.target, r.applied = w.slot, r.save
          counts.reused = counts.reused + 1
        else
          local label = Pairing.LABEL .. (hash and hash(r.world) or "")
          local id
          if r.save == "copy" and offline and engine.slotHasSave(offline) then
            local copied = engine.copySlot(offline, label)
            if copied and (slotNumber(copied) or 0) <= Pairing.MAX_SLOT then
              id, r.applied = copied, "copy"
              counts.copies = counts.copies + 1
            else
              if copied then engine.deleteSlot(copied) end
              -- D14's fallback: the offline slot itself
              r.target, r.current, r.applied = offline, true, "current"
              counts.copyFallbacks = counts.copyFallbacks + 1
              if log then log.warn("session.copy_fallback", { count = counts.copyFallbacks }) end
            end
          end
          if not r.target then
            if not id then
              local code
              id, code = newSlot(label)
              if not id then return nil, code end
              r.applied = "new"
              if r.save == "copy" then counts.copyEmpty = counts.copyEmpty + 1 end
              counts.newSlots = counts.newSlots + 1
            end
            for world, other in pairs(g.worlds) do
              if other.slot == id then g.worlds[world] = nil end
            end
            g.worlds[r.world] = { slot = id }
            r.target, r.created = id, true
          end
        end
      end
      -- the offline slot is home while worlds are paired; a chosen world slot is this world's pairing
      local dirty = false
      if offline and g.home ~= offline then g.home, dirty = offline, true end
      if not r.current and not (g.worlds[r.world] and g.worlds[r.world].slot == r.target) then
        g.worlds[r.world], dirty = { slot = r.target }, true
      end
      if (dirty or r.created) and not persist() then
        if r.created then engine.deleteSlot(r.target) end
        return nil, "session.store_failed"
      end
      if r.created then counts.created = counts.created + 1 end
      r.switch = r.target ~= active
      return true
    end
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
      local bound = { slot = slot, switched = switched }
      if r.applied then bound.save = r.applied end -- WS2: the choice applied
      req = nil
      local function send(data)
        deferred[#deferred + 1] = { "session.bound", data, re = r.seq, gen = r.gen }
        counts.answered = counts.answered + 1
        onBound(r.world, r.multiplayer, slot, switched, r)
      end
      if onContinue and (r.continue == "play" or r.continue == "park") then
        -- WS3: the game is continued (or held) first; session.bound says what happened
        onContinue(r, bound, send)
      else
        if r.continue == "title" then bound.started = "none" end
        out[#out + 1] = { "session.bound", bound, re = r.seq, gen = r.gen }
        counts.answered = counts.answered + 1
        onBound(r.world, r.multiplayer, slot, switched, r)
      end
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
    if type(d) ~= "table" or not validWorld(d.world) or type(d.multiplayer) ~= "boolean"
        or d.incompatible ~= nil and not INCOMPATIBLE[d.incompatible]
        or d.save ~= nil and not SAVES[d.save] or d.continue ~= nil and not CONTINUES[d.continue]
        or d.slot ~= nil and (type(d.slot) ~= "number" or d.slot ~= math.floor(d.slot) or d.slot < 1
                              or d.slot > Pairing.MAX_SLOT) then
      return nil, "bad_message"
    end
    incompatible = d.incompatible or "visitor"
    counts.binds = counts.binds + 1
    if req then counts.superseded = counts.superseded + 1 end
    local r = { world = d.world, multiplayer = d.multiplayer, seq = seq, gen = generation,
                slot = d.slot, save = d.save, continue = d.continue }
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
  -- WS5: session.close from the session (role already checked); done in step().
  local closeReq
  function pairing.close(d, seq, generation)
    if type(d) ~= "table" or next(d) ~= nil and not (next(d) == "region" and next(d, "region") == nil) then
      return nil, "bad_message"
    end
    closeReq = { seq = seq, gen = generation }
    counts.closes = (counts.closes or 0) + 1
    return true
  end

  local function close(out, c)
    if engine.awayHeld and engine.awayHeld() then engine.releaseAway() end
    local title = true
    if engine.playthrough(loaded) ~= "title" then title = engine.toTitle() == true end
    local data = { title = title }
    local s = engine.slotState()
    local g = s.version and s.api and not s.cart and gameRec(s.version) or nil
    local home = g and g.home
    if g and s.active and worldSlot(g, s.active) and home and not worldSlot(g, home) and engine.slotRegistered(home) then
      if engine.activateSlot(home) then
        data.home = protocolSlot(home)
        counts.closeHomes = (counts.closeHomes or 0) + 1
        if engine.titleMenuOpen() then engine.toTitle() end
      end
    end
    out[#out + 1] = { "session.closed", data, re = c.seq, gen = c.gen }
    if log then log.info("session.closed", { title = title, home = data.home ~= nil }) end
  end

  function pairing.step(out, link)
    -- WS5: a session.close (after Minecraft left the world), before anything else
    if closeReq then
      local c = closeReq
      closeReq = nil
      if link and link.live and c.gen == link.generation then
        local ok = pcall(close, out, c)
        if not ok then out[#out + 1] = { "error", { code = "session.failed", msg = "session.failed" }, re = c.seq, gen = c.gen } end
      end
    end
    -- WS3: answers kb_continue finished since the last step (still for this connection)
    if #deferred > 0 then
      for _, m in ipairs(deferred) do
        if link and link.live and m.gen == link.generation then out[#out + 1] = m end
      end
      deferred = {}
    end
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

  -- FP1: what the latest bind said to do with a member the game lacks:
  -- "visitor" (the default, also before any bind) or "leave_out".
  function pairing.incompatible() return incompatible end

  -- MG2: the live playthrough as the pairing judges it (kb_engine.playthrough
  -- with the save tables CONTINUE brought): "title", "fresh" or "saved", for
  -- observe/kb_progress.lua's game.status.
  function pairing.playthrough() return engine.playthrough(loaded) end

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
    out.leaveOut = incompatible == "leave_out" -- FP1
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
