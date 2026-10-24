-- kb_battle: battles the hidden game starts, fought in Cobblemon
-- (docs/PLAN.md section 4.1 "Battles", flows 8 and 9; docs/PROTOCOL.md
-- "Battles").
--
--   battle.start{battle, kind, trainer?, team?, wild?, rules}   G->M
--   battle.result{battle, outcome, caught?}                     M->G
--
-- kb_engine holds the hidden game at the battle (a scripted battle row waits
-- inside its script; a map trainer's encounter is deferred) and asks this
-- module for the battle.start payload (begin). The payload goes out at the
-- next step, and again after every snapshot (a reconnect, a resync) with the
-- same id, until a battle.result for that id arrives. While it waits the
-- classifier says busy, and nothing else in the hidden game moves on.
-- Battles are taken over only while a client or test session is connected;
-- otherwise the hidden game fights them itself (kb_engine asks `live`).
--
-- Kinds: "rival" (the rival classes OPP_RIVAL1-3, scripted or not),
-- "trainer" (a map trainer: sight or talk), "scripted" (another trainer a
-- script starts; M8: also Giovanni's Rocket Hideout battle, which its talk
-- script pushes directly), "static" (a scripted wild battle: static_battle,
-- start_battle "wild"; M8: a static Pokemon object talked to and the ghost
-- Marowak with the SILPH_SCOPE, pushed directly: kb_engine's direct
-- battles), and from M6 "surf" and "fish" (a wild Pokemon the hidden game
-- rolled while surfing or fishing, PLAN P8). Trainer teams come
-- resolved from kb_engine as the engine would build them: species as dex,
-- level, 1-4 Gen 1 move numbers, DVs; trainer.party is the engine's 1-based
-- party index. Wild ones carry wild{dex, level, x, y}: for static the
-- object's cell, for surf and fish the water in front of the player.
-- Rules: trainers { catch = false, flee = false }, wild Pokemon
-- { catch = true, flee = true } (catch false for a Pokemon the engine
-- marks uncatchable: the ghost Marowak). A team the protocol can't express exactly
-- is declined: the hidden game fights that battle itself (shown on the
-- mirror).
--
-- Outcomes -> the engine's results: win -> win, lose -> lose, fled -> run,
-- caught -> caught, aborted -> nothing happens (a scripted battle's script
-- stops there, so whatever triggered it can run it again; a map trainer's
-- encounter is cancelled, so it challenges again once the player moves or
-- talks to it; a surf or fish encounter just ends; M8: a direct battle ends
-- through its own continuation as a flight, so its object or trigger stays
-- for another try). A trainer battle can't
-- be fled or caught: those count as aborted. A caught Pokemon is
-- Cobblemon's: it reaches the hidden game through party.sync and dex.sync.
-- A result for any other id is answered with error battle.unknown.
--
-- battle.native{kind, phase} (G->M): the special battles that stay in the
-- hidden game (PLAN P9: the Safari Zone, the old man's demo, the
-- unidentified ghost) are announced when one appears on the stack ("start")
-- and when it leaves ("end"), and a running one again after a snapshot.
--
-- Logs show codes, kinds, ids and counts only; trainer names, teams and wild
-- species are ROM-derived and never logged.

local Battle = {}

Battle.MAX_NAME = 32 -- trainer.name maxLength

local floor = math.floor
local byte, gsub, sub = string.byte, string.gsub, string.sub

local OUTCOMES = { win = "win", lose = "lose", fled = "run", caught = "caught", aborted = "abort" }
local RIVALS = { OPP_RIVAL1 = true, OPP_RIVAL2 = true, OPP_RIVAL3 = true }
local WILD_KINDS = { surf = "surf", fish = "fish", contest = "contest" } -- (FP5: Crystal's Bug-Catching Contest)
-- (MG9 G2F: Gen 2's Bug-Catching Contest and Battle Tower; its catch tutorial is old_man)
local NATIVE_KINDS = { safari = true, old_man = true, ghost = true, contest = true, tower = true, facility = true }
local GENDERS = { male = true, female = true, genderless = true }
local DV_KEYS = { "atk", "def", "spd", "spc" }
local IV_KEYS = { "hp", "atk", "def", "spa", "spd", "spe" }
-- MG7 (G3F): the running game's ceilings (the catalogue's compat); Gen 1's
-- when the game didn't say
Battle.GEN1 = { maxDex = 151, maxMove = 165 }
-- Trainer ids: Gen 1's party index is 0-255; Emerald's trainer ids reach 854
Battle.MAX_PARTY = 1023
local UID = "^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$"

local function whole(v, lo, hi)
  return type(v) == "number" and v == floor(v) and v >= lo and v <= hi
end

local function sym(v, pattern, max)
  return type(v) == "string" and #v > 0 and #v <= (max or 64) and v:find(pattern) ~= nil
end

local function coord(v) return whole(v, -4096, 4096) end

-- At most `max` code points, cut on a character boundary.
local function capChars(s, max)
  local _, cont = gsub(s, "[\128-\191]", "")
  if #s - cont <= max then return s end
  local chars, i = 0, 1
  while i <= #s do
    local c = byte(s, i)
    if c < 128 or c >= 192 then
      chars = chars + 1
      if chars > max then return sub(s, 1, i - 1) end
    end
    i = i + 1
  end
  return s
end
Battle.capChars = capChars

-- The highest dex and move numbers a game's compat allows (Gen 1's without one).
local function ceilings(compat)
  compat = type(compat) == "table" and compat or Battle.GEN1
  local maxDex = whole(compat.maxDex, 1, 2000) and compat.maxDex or Battle.GEN1.maxDex
  local maxMove = whole(compat.maxMove, 1, 2000) and compat.maxMove or Battle.GEN1.maxMove
  return maxDex, maxMove
end

-- MG9 (G2F): a Pokemon's details where its game has them (Gen 2 on): the held
-- item (the catalogue's canonical id, protocol/items.json), shininess, gender
-- and form copied into `out` when well formed; anything else is left out (the
-- member still goes).
function Battle.details(m, out)
  if type(m) ~= "table" then return out end
  if sym(m.item, "^[a-z][a-z0-9_]*$", 32) then out.item = m.item end
  if type(m.shiny) == "boolean" then out.shiny = m.shiny end
  if GENDERS[m.gender] then out.gender = m.gender end
  if sym(m.form, "^[a-z0-9_]+$", 24) then out.form = m.form end
  return out
end

-- One team member as the protocol's teamMon, or nil when the engine's data
-- can't be expressed: dex and 1-4 moves within the game's ceilings (Gen 1:
-- 151 and 165), level 1-100, and Gen 1's DVs (0-15) or, MG7 (G3F), Gen 3's
-- IVs (0-31, all six stats).
function Battle.teamMon(m, compat)
  local maxDex, maxMove = ceilings(compat)
  if type(m) ~= "table" or not (whole(m.dex, 1, maxDex) and whole(m.level, 1, 100)) then return nil end
  local moves = {}
  for _, n in ipairs(type(m.moves) == "table" and m.moves or {}) do
    if not whole(n, 1, maxMove) or #moves >= 4 then return nil end
    moves[#moves + 1] = n
  end
  if #moves == 0 then return nil end
  if type(m.ivs) == "table" then
    local ivs = {}
    for _, k in ipairs(IV_KEYS) do
      if not whole(m.ivs[k], 0, 31) then return nil end
      ivs[k] = m.ivs[k]
    end
    return Battle.details(m, { dex = m.dex, level = m.level, moves = moves, ivs = ivs })
  end
  local d = type(m.dvs) == "table" and m.dvs or {}
  local dvs = {}
  for _, k in ipairs(DV_KEYS) do
    if not whole(d[k], 0, 15) then return nil end
    dvs[k] = d[k]
  end
  return Battle.details(m, { dex = m.dex, level = m.level, moves = moves, dvs = dvs })
end

-- opts:
--   engine   kb_engine instance (trainerTeam, battlePending, battleSent,
--            battleStale, battleFinish, bridgeToken, nextSerial,
--            nativeBattle)
--   markup   kb_ui's Ui.markup (the trainer's name in the protocol's text)
--   log      kb_log logger (optional)
function Battle.new(opts)
  local engine, log = opts.engine, opts.log
  -- MG7 (G3F): the running game's compat (maxDex, maxMove); Gen 1's without one
  local compat = type(opts.compat) == "table" and opts.compat or Battle.GEN1
  local maxDex = ceilings(compat)
  local markup = opts.markup or function(s) return s, 0 end
  local battles = {}
  local resend = false -- a snapshot went out: send the pending battle again
  local nativeSent, nativeGen -- the special battle announced on this connection
  local counts = {
    started = 0, declined = 0, sent = 0, resent = 0, results = 0, win = 0, lose = 0, run = 0,
    caught = 0, abort = 0, refused = 0, unknown = 0, dropped = 0,
    static = 0, surf = 0, fish = 0, contest = 0, native = 0, nativeEnded = 0, direct = 0,
  }

  local function decline(why, fields)
    counts.declined = counts.declined + 1
    if log then
      fields = fields or {}
      fields.why, fields.count = why, counts.declined
      log.warn("battle.declined", fields)
    end
    return nil
  end

  -- kb_engine's facts about a battle it is holding (plain values) -> the
  -- plan { id, kind, data = battle.start payload }, or nil to let the
  -- hidden game fight it. facts: path ("script"|"trainer"|"wild"|"direct"),
  -- kind (wild: "surf"|"fish", else static), command, cls, party, wild,
  -- species, dex, level, map, npc, x, y, catch (false: the Pokemon can't be
  -- caught, the ghost Marowak). MG7 (G3F): flee (false: the battle can't be
  -- fled, Birch's), rival (the trainer is the rival whatever its class).
  function battles.begin(facts)
    if type(facts) ~= "table" then return decline("facts") end
    local data, kind = {}, nil
    if facts.path == "direct" then counts.direct = counts.direct + 1 end
    if facts.wild then
      if not (whole(facts.dex, 1, maxDex) and whole(facts.level, 1, 100)) then return decline("wild") end
      kind = WILD_KINDS[facts.kind] or "static"
      counts[kind] = counts[kind] + 1
      data.wild = { dex = facts.dex, level = facts.level }
      if coord(facts.x) and coord(facts.y) then data.wild.x, data.wild.y = facts.x, facts.y end
      -- MG9 (G2F): a Gen 2 wild Pokemon's DVs and details, so Cobblemon's matches it
      local d = type(facts.dvs) == "table" and facts.dvs or nil
      if d and whole(d.atk, 0, 15) and whole(d.def, 0, 15) and whole(d.spd, 0, 15) and whole(d.spc, 0, 15) then
        data.wild.dvs = { atk = d.atk, def = d.def, spd = d.spd, spc = d.spc }
      end
      Battle.details(facts, data.wild)
      data.rules = { catch = facts.catch ~= false, flee = facts.flee ~= false }
    else
      if not (sym(facts.cls, "^[A-Za-z0-9_]+$") and whole(facts.party, 0, Battle.MAX_PARTY)) then return decline("trainer") end
      local team, name = engine.trainerTeam(facts.cls, facts.party)
      if type(team) ~= "table" or #team == 0 or #team > 6 then
        return decline("team", { cls = facts.cls })
      end
      local shaped = {}
      for i = 1, #team do
        local t = Battle.teamMon(team[i], compat)
        if not t then return decline("team_member", { cls = facts.cls, index = i }) end
        shaped[i] = t
      end
      kind = (RIVALS[facts.cls] or facts.rival == true) and "rival" or (facts.path == "trainer" and "trainer" or "scripted")
      local trainer = { cls = facts.cls, party = facts.party }
      if type(name) == "string" then
        local shown = capChars((markup(name)), Battle.MAX_NAME)
        if shown ~= "" then trainer.name = shown end
      end
      if sym(facts.npc, "^[A-Za-z0-9_]+$", 96) then trainer.npc = facts.npc end
      if sym(facts.map, "^[A-Z0-9_]+$") then trainer.map = facts.map end
      if coord(facts.x) and coord(facts.y) then trainer.x, trainer.y = facts.x, facts.y end
      data.trainer, data.team = trainer, shaped
      data.rules = { catch = false, flee = false }
      -- FP5 (§4.12): a multi battle (Steven's): the ally's team, the player's half by uid, where the first foe's
      -- Pokemon end in the team
      if facts.format == "multi" and type(facts.allyTeam) == "table" then
        local ally = {}
        for i = 1, math.min(#facts.allyTeam, 6) do
          local t = Battle.teamMon(facts.allyTeam[i], compat)
          if not t then return decline("ally_member", { index = i }) end
          ally[#ally + 1] = t
        end
        if #ally == 0 then return decline("ally") end
        data.format, data.ally = "multi", { team = ally }
        if type(facts.allyName) == "string" then
          local shown = capChars((markup(facts.allyName)), Battle.MAX_NAME)
          if shown ~= "" then data.ally.name = shown end
        end
        if whole(facts.split, 1, #shaped - 1) then data.split = facts.split end
      end
    end
    if type(facts.use) == "table" and not data.use then
      local use, seen = {}, {}
      for _, uid in ipairs(facts.use) do
        if type(uid) == "string" and uid:find(UID) and uid == uid:lower() and not seen[uid] and #use < 6 then
          use[#use + 1], seen[uid] = uid, true
        end
      end
      if #use == 0 then return decline("use") end
      data.use = use
    end
    local id = ("b-%s-%d"):format(engine.bridgeToken(), engine.nextSerial())
    data.battle, data.kind = id, kind
    counts.started = counts.started + 1
    if log then
      log.info("battle.held", { battle = id, kind = kind, path = facts.path,
                                team = data.team and #data.team or 0, count = counts.started })
    end
    return { id = id, kind = kind, data = data }
  end

  -- A native special battle appearing or leaving (battle.native), told to
  -- this connection once; after a snapshot a running one is told again.
  local function native(out, link)
    local kind = engine.nativeBattle and engine.nativeBattle() or nil
    if not NATIVE_KINDS[kind] then kind = nil end
    if not (link and link.live) then return end
    if nativeGen ~= link.generation or resend then nativeSent, nativeGen = nil, link.generation end
    if kind == nativeSent then return end
    if nativeSent then
      out[#out + 1] = { "battle.native", { kind = nativeSent, phase = "end" }, "battle" }
      counts.nativeEnded = counts.nativeEnded + 1
      if log then log.info("battle.native", { kind = nativeSent, phase = "end" }) end
    end
    if kind then
      out[#out + 1] = { "battle.native", { kind = kind, phase = "start" }, "battle" }
      counts.native = counts.native + 1
      if log then log.info("battle.native", { kind = kind, phase = "start", count = counts.native }) end
    end
    nativeSent = kind
  end

  -- One logic step: drops a battle whose world is gone, announces a native
  -- special battle, and sends the pending battle.start when this connection
  -- hasn't had it (or a snapshot asked for everything again). out gets
  -- { "battle.native" | "battle.start", data, tag }. link = { live,
  -- generation }.
  function battles.step(out, link)
    if engine.battleStale() then
      counts.dropped = counts.dropped + 1
      if log then log.warn("battle.dropped", { count = counts.dropped }) end
    end
    native(out, link)
    local rec = engine.battlePending()
    if rec and rec.state == "waiting" and link and link.live and type(rec.data) == "table"
        and (rec.sentGen ~= link.generation or resend) then
      out[#out + 1] = { "battle.start", rec.data, "battle" }
      if rec.sentGen == nil then counts.sent = counts.sent + 1 else counts.resent = counts.resent + 1 end
      engine.battleSent(rec.id, link.generation)
    end
    if link and link.live then resend = false end
  end

  -- battle.result from the session (role already checked). Returns true, or
  -- false plus an error code (bad_message, battle.unknown).
  function battles.result(data)
    local outcome = OUTCOMES[data.outcome]
    if not sym(data.battle, "^[A-Za-z0-9_.:-]+$") or not outcome then return false, "bad_message" end
    if data.outcome == "caught" then
      local c = data.caught
      if not (type(c) == "table" and type(c.uid) == "string" and c.uid:find(UID) and c.uid == c.uid:lower()
              and whole(c.dex, 1, 2000) and whole(c.level, 1, 100)) then
        return false, "bad_message"
      end
    end
    local rec = engine.battlePending()
    if not (rec and rec.id == data.battle and rec.state == "waiting") then
      counts.unknown = counts.unknown + 1
      if log then log.warn("battle.unknown_result", { count = counts.unknown }) end
      return false, "battle.unknown"
    end
    if not rec.wild and (outcome == "run" or outcome == "caught") then
      counts.refused = counts.refused + 1
      if log then log.warn("battle.trainer_outcome", { outcome = data.outcome, count = counts.refused }) end
      outcome = "abort"
    end
    if not engine.battleFinish(rec.id, outcome, data.outcome == "caught" and data.caught or nil) then
      return false, "battle.unknown"
    end
    counts.results = counts.results + 1
    counts[outcome] = counts[outcome] + 1
    if log then log.info("battle.result", { battle = rec.id, outcome = data.outcome, applied = outcome }) end
    return true
  end

  -- After a snapshot (hello or resync): the pending battle goes out again.
  function battles.rebase() resend = true end

  function battles.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    local rec = engine.battlePending()
    out.pending = rec ~= nil and rec.state == "waiting"
    out.nativeActive = nativeSent ~= nil
    return out
  end

  return battles
end

return Battle
