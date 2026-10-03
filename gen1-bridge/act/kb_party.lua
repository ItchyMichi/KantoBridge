-- kb_party: the shadow party, gifts and heals (docs/PLAN.md section 4.1
-- "Party and Pokedex", flow 11; docs/PROTOCOL.md "Party, Pokedex, bag, PC").
--
--   party.give{give, dex, level, moves?, dvs?, nick?, ot?, source}   G->M
--   party.heal{}                                                      G->M
--   party.sync{party[{uid, dex, level, nick?, moves, hp, maxhp, status?}], box}  M->G
--
-- Gifts. give_pokemon runs as usual in the hidden game -- its nickname prompt
-- included, which reaches Minecraft as a text box, a YES/NO and the naming
-- screen -- and kb_engine captures the gift once it returns. It goes out as
-- party.give (source "starter" for the first Pokemon before
-- EVENT_GOT_STARTER, "prize" in the Game Corner prize room, else "gift";
-- nick and ot in the protocol's text markup) at the next step while a client
-- is connected, else as soon as one is, and again after every snapshot until
-- a party.sync shows it, always with the same give id (Minecraft applies a
-- give id once). Until then the engine's own copy stays in the hidden party,
-- so scripts that look at the party (the rival battle, HealParty) still find
-- it. A gift that went to a Gen 1 box (a full party) is taken out of the box:
-- Cobblemon's PC keeps it; any later party.sync confirms it.
--
-- party.sync. Cobblemon is authoritative: the hidden party is rebuilt from it
-- (kb_engine.applyParty). A known uid keeps its party member, updated in
-- place; a new uid with a pending gift's species (dex) and at least its level
-- is that gift; other new uids are created. Moves go by Gen 1 number, HP is
-- scaled to the Gen 1 maximum, status and nickname follow. Species beyond
-- Gen 1 and moves beyond 165 are left out and counted, with a warning each
-- time the count grows. A sync waits while applying it isn't safe
-- (kb_engine.partySafe: no overworld yet, a give under way, a native battle
-- on the stack); the newest one wins, and the last one applied is applied
-- again after New Game or CONTINUE. A gift that five syncs, the last one at
-- least two minutes after it was first sent, have not shown is given up on
-- (a warning), so the hidden party follows Cobblemon's in the end; Minecraft
-- re-sends its latest sync after every snapshot, so a sync older than the
-- gift is normal and the limits are generous.
-- The uids are kept in mod.save ("partyUids", by party position) for hot
-- reloads and CONTINUE.
--
-- Heals. HealParty (Mom, the lab after the rival battle, ...) and a blackout
-- heal the hidden party; Cobblemon's party heals as well: one party.heal, as
-- soon as a client is connected (several collapse into one).
--
-- Logs show codes, kinds, ids and counts only; nicknames are never logged.

local Party = {}

Party.GIVE_UP_AFTER = 5     -- syncs a sent gift may miss before it is given up on ...
Party.GIVE_UP_SECONDS = 120 -- ... the last of them at least this long after it was first sent
Party.MAX_NICK = 10       -- Gen 1 nickname glyphs (NamingScreen maxLen 10)
Party.MAX_TEXT = 32       -- party.give nick / ot maxLength

local floor = math.floor
local byte, find, gsub, sub = string.byte, string.find, string.gsub, string.sub

local UID = "^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$"
local STATUS = { slp = "SLP", psn = "PSN", brn = "BRN", frz = "FRZ", par = "PAR" }
local DV_KEYS = { "atk", "def", "spd", "spc" }
local SOURCES = { starter = true, gift = true, trade = true, prize = true, native_catch = true }

local function whole(v, lo, hi)
  return type(v) == "number" and v == floor(v) and v >= lo and v <= hi
end

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

-- A Cobblemon nickname as the hidden game keeps one: control characters
-- out, at most 10 glyphs (<PK> and <MN> count as one, as on the naming
-- grid). nil when nothing is left (no nickname).
function Party.engineNick(s)
  if type(s) ~= "string" then return nil end
  s = gsub(s, "[%z\1-\31\127]", "")
  local out, glyphs, i = {}, 0, 1
  while i <= #s and glyphs < Party.MAX_NICK do
    local token = s:match("^<[PM][KN]>", i)
    if token == "<PK>" or token == "<MN>" then
      out[#out + 1] = token
      i = i + 4
    else
      local c = byte(s, i)
      local len = (c >= 240 and 4) or (c >= 224 and 3) or (c >= 192 and 2) or 1
      out[#out + 1] = sub(s, i, i + len - 1)
      i = i + len
    end
    glyphs = glyphs + 1
  end
  local nick = table.concat(out)
  return nick ~= "" and nick or nil
end

-- party.sync data checked against its schema's shape (the protocol is
-- strict); returns true or false.
function Party.validSync(data)
  if type(data) ~= "table" or type(data.party) ~= "table" or #data.party > 6 then return false end
  if type(data.box) ~= "table" or not whole(data.box.count, 0, 2147483647) then return false end
  local uids = {}
  for _, e in ipairs(data.party) do
    if type(e) ~= "table" or type(e.uid) ~= "string" or not find(e.uid, UID) or e.uid ~= e.uid:lower()
        or uids[e.uid] then
      return false
    end
    uids[e.uid] = true
    if not (whole(e.dex, 1, 2000) and whole(e.level, 1, 100) and whole(e.hp, 0, 9999)
            and whole(e.maxhp, 1, 9999)) then
      return false
    end
    if type(e.moves) ~= "table" or #e.moves > 4 then return false end
    for _, n in ipairs(e.moves) do
      if not whole(n, 1, 2000) then return false end
    end
    if e.status ~= nil and not STATUS[e.status] then return false end
    if e.nick ~= nil and type(e.nick) ~= "string" then return false end
  end
  return true
end

-- opts:
--   engine   kb_engine instance (gifts, giftSent, giftSynced, giftDone,
--            takeHeals, partyUids, adoptUids, partySafe, applyParty,
--            speciesOfDex, moveIdOfNum)
--   markup   kb_ui's Ui.markup (nick and ot in the protocol's text)
--   store    { get(key), set(key, value) }: mod.save
--   clock    seconds (optional; without it gifts are given up on by count alone)
--   log      kb_log logger (optional)
function Party.new(opts)
  local engine, log, store, clock = opts.engine, opts.log, opts.store, opts.clock
  local markup = opts.markup or function(s) return s, 0 end
  local party = {}
  local pending   -- the newest party.sync waiting for a safe moment
  local lastSync  -- the last one applied (applied again after New Game / CONTINUE)
  local resend = false
  local counts = {
    gives = 0, givesSent = 0, givesResent = 0, givesConfirmed = 0, givesGivenUp = 0, givesBad = 0,
    syncs = 0, applied = 0, deferred = 0, created = 0, matched = 0, kept = 0, failed = 0,
    nonGen1Species = 0, nonGen1Moves = 0, heals = 0,
  }
  local deferredNow = false
  local seen = {}   -- give ids counted
  local sentAt = {} -- give id -> clock when first sent

  local function text(s)
    if type(s) ~= "string" then return nil end
    local out = capChars((markup(s)), Party.MAX_TEXT)
    return out ~= "" and out or nil
  end

  -- The party.give payload of a captured gift, or nil when it can't be sent.
  local function giveData(g)
    if not (type(g.give) == "string" and whole(g.dex, 1, 151) and whole(g.level, 1, 100)
            and SOURCES[g.source]) then
      return nil
    end
    local data = { give = g.give, dex = g.dex, level = g.level, source = g.source }
    local moves = {}
    for _, n in ipairs(type(g.moves) == "table" and g.moves or {}) do
      if whole(n, 1, 165) and #moves < 4 then moves[#moves + 1] = n end
    end
    if #moves > 0 then data.moves = moves end
    local d = type(g.dvs) == "table" and g.dvs or {}
    local dvs, ok = {}, true
    for _, k in ipairs(DV_KEYS) do
      if whole(d[k], 0, 15) then dvs[k] = d[k] else ok = false end
    end
    if ok then data.dvs = dvs end
    data.nick, data.ot = text(g.nick), text(g.ot)
    return data
  end

  local function persist()
    if not store then return end
    local list = engine.partyUids()
    pcall(store.set, "partyUids", list)
  end

  local function warnGrowth(code, field)
    if log then log.warn(code, { count = counts[field] }) end
  end

  local function apply(data)
    local _, known = engine.partyUids()
    local gifts = engine.gifts()
    local taken = {}
    local entries = {}
    local species0, moves0 = counts.nonGen1Species, counts.nonGen1Moves
    for _, e in ipairs(data.party) do
      local species = e.dex <= 151 and engine.speciesOfDex(e.dex) or nil
      if not species then
        counts.nonGen1Species = counts.nonGen1Species + 1
      else
        local moves = {}
        for _, n in ipairs(e.moves) do
          local id = n <= 165 and engine.moveIdOfNum(n) or nil
          if id then moves[#moves + 1] = id else counts.nonGen1Moves = counts.nonGen1Moves + 1 end
        end
        local entry = {
          uid = e.uid, species = species, level = e.level, moves = moves, hp = e.hp, maxhp = e.maxhp,
          status = STATUS[e.status], nick = Party.engineNick(e.nick),
        }
        if not known[e.uid] then
          -- a member the hidden party doesn't know: the pending gift it is?
          for _, g in ipairs(gifts) do
            if not g.box and not taken[g.give] and g.dex == e.dex and e.level >= (g.level or 1) then
              entry.give = g.give
              taken[g.give] = true
              break
            end
          end
        end
        entries[#entries + 1] = entry
      end
    end
    if counts.nonGen1Species > species0 then warnGrowth("party.non_gen1_species", "nonGen1Species") end
    if counts.nonGen1Moves > moves0 then warnGrowth("party.non_gen1_moves", "nonGen1Moves") end
    -- gifts this sync didn't show: a box gift is in Cobblemon's PC by now;
    -- a party gift waits a little longer, then is given up on
    for _, g in ipairs(gifts) do
      if not taken[g.give] and g.state == "sent" then
        if g.box then
          engine.giftDone(g.give)
          counts.givesConfirmed = counts.givesConfirmed + 1
        elseif (g.syncs or 0) + 1 >= Party.GIVE_UP_AFTER
            and (not clock or clock() - (sentAt[g.give] or clock()) >= Party.GIVE_UP_SECONDS) then
          engine.giftDone(g.give)
          counts.givesGivenUp = counts.givesGivenUp + 1
          if log then log.warn("party.gift_unconfirmed", { give = g.give, count = counts.givesGivenUp }) end
        else
          engine.giftSynced(g.give)
        end
      end
    end
    local res = engine.applyParty(entries)
    if not res then return false end
    counts.applied = counts.applied + 1
    counts.created = counts.created + res.created
    counts.matched = counts.matched + res.matched
    counts.givesConfirmed = counts.givesConfirmed + res.matched
    counts.kept = counts.kept + res.kept
    counts.failed = counts.failed + res.failed
    persist()
    lastSync = data
    if log then
      log.info("party.synced", { size = res.size, created = res.created, matched = res.matched,
                                 kept = res.kept, count = counts.applied })
    end
    return true
  end

  -- party.sync from the session (role already checked). Returns true, or
  -- false plus bad_message.
  function party.sync(data)
    if not Party.validSync(data) then return false, "bad_message" end
    counts.syncs = counts.syncs + 1
    pending = data
    return true
  end

  -- One logic step. out gets party.give / party.heal; link = { live, generation }.
  function party.step(out, link)
    link = link or {}
    local live = link.live
    for _, g in ipairs(engine.gifts()) do
      if not seen[g.give] then
        seen[g.give] = true
        counts.gives = counts.gives + 1
      end
      local due = g.state == "new" or (g.state == "sent" and (resend or g.sentGen ~= link.generation))
      if due and live then
        local data = giveData(g)
        if data then
          out[#out + 1] = { "party.give", data, "party" }
          if g.state == "new" then counts.givesSent = counts.givesSent + 1 else counts.givesResent = counts.givesResent + 1 end
          if clock and sentAt[g.give] == nil then sentAt[g.give] = clock() end
          engine.giftSent(g.give, link.generation)
          if log then log.info("party.give", { give = g.give, source = g.source, count = counts.givesSent }) end
        else
          engine.giftDone(g.give)
          counts.givesBad = counts.givesBad + 1
          if log then log.warn("party.gift_unsendable", { count = counts.givesBad }) end
        end
      end
    end
    if live then
      local heals = engine.takeHeals()
      if heals > 0 then
        out[#out + 1] = { "party.heal", {}, "party" }
        counts.heals = counts.heals + 1
      end
      resend = false
    end
    if pending then
      if engine.partySafe() then
        local data = pending
        pending = nil
        deferredNow = false
        local ok = pcall(apply, data)
        if not ok and log then log.warn("party.sync_failed", { count = counts.syncs }) end
      elseif not deferredNow then
        deferredNow = true
        counts.deferred = counts.deferred + 1
      end
    end
  end

  -- After a snapshot (hello or resync): gifts not yet shown go out again.
  function party.rebase() resend = true end

  -- game.ready (a hot reload too) and CONTINUE: the uids saved with the
  -- last sync, by party position.
  function party.adopt()
    if not store then return 0 end
    local ok, list = pcall(store.get, "partyUids")
    return ok and engine.adoptUids(list) or 0
  end

  -- New Game or CONTINUE: the last sync applies again to the new party.
  function party.reapply()
    if lastSync and not pending then pending = lastSync end
  end

  function party.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    out.waiting = pending ~= nil
    return out
  end

  return party
end

return Party
