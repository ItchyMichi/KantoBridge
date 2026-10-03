-- kb_party: the shadow party, gifts, heals, the Pokedex and items used on a
-- party member (docs/PLAN.md section 4.1 "Party and Pokedex", flows 10
-- and 11; docs/PROTOCOL.md "Party, Pokedex, bag, PC").
--
--   party.give{give, dex, level, moves?, dvs?, nick?, ot?, source}   G->M
--   party.heal{}                                                      G->M
--   party.item{item, uid, slot?, move?}                               G->M
--   party.sync{party[{uid, give?, dex, level, nick?, moves, hp, maxhp, status?}], box}  M->G
--   dex.sync{seen[dex], owned[dex]}                                   M->G
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
-- place; an entry whose `give` names a pending gift is that gift (exact,
-- M6); a new uid without `give` that has a pending gift's species (dex) and
-- at least its level is that gift (M5's fallback); other new uids are
-- created. Moves go by Gen 1 number, HP is
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
-- dex.sync (M6). Cobblemon's Pokedex is added to the hidden game's
-- (kb_engine.applyDex): species from dex numbers, an owned entry is seen as
-- well, nothing is ever cleared, so Oak, his aides and the Pokedex count
-- see what the player saw and caught in Minecraft. It applies once an
-- overworld is up (the newest one wins) and again after New Game or
-- CONTINUE.
--
-- party.item (M6). A stone or RARE_CANDY used from the Gen 1 bag on a party
-- member, or a TM/HM the member learned there, is applied by Cobblemon:
-- kb_engine intercepts it (the Gen 1 effect skipped, or for a TM/HM the
-- taught move and its slot watched) and queues it; it goes out once, as
-- soon as a client is connected (party.item carries no id to apply it by
-- once, so it is never sent twice). The next party.sync brings the result.
--
-- M8 (wave 2):
--   party.take{uid, reason:"trade"}, daycare.deposit{uid},
--   daycare.withdraw{uid, steps}, party.rename{uid, nick}       G->M
-- * In-game trades: kb_engine reads the trade from the party (the member
--   sent: party.take by its uid; the one received: a gift with source
--   "trade", its nickname and OT).
-- * Native catches (P9, the Safari Zone): once the battle has left the
--   stack the catch is a gift with source "native_catch".
-- * The daycare (P15): a member with a uid boarded is daycare.deposit; back
--   in the party, daycare.withdraw with the steps walked meanwhile. The
--   boarded uid is kept in mod.save ("daycare": { uid, exp0 }) so a
--   CONTINUE finds it again.
-- * The Name Rater (P16): a nickname of a member with a uid that changes
--   outside a sync goes out as party.rename (an empty nick clears it).
-- Takes, deposits and withdrawals go out once and again after every
-- snapshot until a party.sync confirms them (the uid gone from Cobblemon's
-- party for a take or a deposit, back in it for a withdrawal), with the
-- gifts' limits for giving up. Until a take is confirmed, and while the
-- daycare holds a member, a sync never puts that uid back into the hidden
-- party; a withdrawn member stays in it until a sync names it. Renames go
-- out once each, queued while no client is connected.
--
-- M9 (save pairing, act/kb_pairing.lua): a session.bind that switches save
-- slots forgets every sync and Pokedex that came before it, waiting or
-- applied (they are the world being left's: they must never reach the
-- paired game, nor apply again after its New Game or CONTINUE); syncs that
-- arrive while the switch waits wait with it (link.blocked).
--
-- M10 (save pairs, act/kb_save.lua): every gift, take, deposit and
-- withdrawal is reported to onOp() once, when it first goes out (a repeat
-- after a snapshot is the same operation). One the hidden game made after
-- a save that is still waiting to be announced (its serial is higher than
-- that save's, engine.queuedSaveSerial) waits until that save.begin has
-- gone out, so Minecraft saves before it and counts it after, as the
-- hidden save did.
--
-- Logs show codes, kinds, ids and counts only; nicknames, species and moves
-- are never logged.

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
    if e.give ~= nil and not (type(e.give) == "string" and #e.give >= 1 and #e.give <= 64
                              and find(e.give, "^[A-Za-z0-9_.:-]+$")) then
      return false
    end
  end
  return true
end

-- dex.sync data checked against its schema's shape: two lists of distinct
-- Gen 1 dex numbers; returns true or false.
function Party.validDex(data)
  if type(data) ~= "table" then return false end
  for _, key in ipairs({ "seen", "owned" }) do
    local list = data[key]
    if type(list) ~= "table" or #list > 151 then return false end
    local seen = {}
    for i, n in pairs(list) do
      if type(i) ~= "number" or not whole(n, 1, 151) or seen[n] then return false end
      seen[n] = true
    end
  end
  return true
end

-- A queued item use as party.item's payload, or nil when it can't be one.
function Party.itemData(use)
  if type(use) ~= "table" or type(use.item) ~= "string" or not find(use.item, "^[A-Z0-9_]+$")
      or #use.item > 64 or type(use.uid) ~= "string" or not find(use.uid, UID) then
    return nil
  end
  local data = { item = use.item, uid = use.uid }
  if use.move ~= nil or use.slot ~= nil then
    -- a TM/HM: the move it taught, and the slot it took when it replaced one
    if not whole(use.move, 1, 165) then return nil end
    data.move = use.move
    if use.slot ~= nil then
      if not whole(use.slot, 0, 3) then return nil end
      data.slot = use.slot
    end
  end
  return data
end

-- opts:
--   engine   kb_engine instance (gifts, giftSent, giftSynced, giftDone,
--            takeHeals, partyUids, adoptUids, partySafe, applyParty,
--            speciesOfDex, moveIdOfNum, inGame, applyDex, takeItemUses,
--            tmWatchStep; M8: releaseCatches, daycareStep, ops, opSent,
--            opSynced, opDone, excludedUids, partyNicks, queueRename,
--            takeRenames)
--   markup   kb_ui's Ui.markup (nick and ot in the protocol's text)
--   store    { get(key), set(key, value) }: mod.save
--   clock    seconds (optional; without it gifts are given up on by count alone)
--   log      kb_log logger (optional)
--   onOp     M10: called once per Pokemon operation (give, take, deposit,
--            withdraw) as it first goes out (optional)
function Party.new(opts)
  local engine, log, store, clock = opts.engine, opts.log, opts.store, opts.clock
  local markup = opts.markup or function(s) return s, 0 end
  local onOp = opts.onOp or function() end
  local party = {}
  local pending   -- the newest party.sync waiting for a safe moment
  local lastSync  -- the last one applied (applied again after New Game / CONTINUE)
  local pendingDex, lastDex -- the same for dex.sync
  local resend = false
  local counts = {
    gives = 0, givesSent = 0, givesResent = 0, givesConfirmed = 0, givesGivenUp = 0, givesBad = 0,
    givesExact = 0,
    syncs = 0, applied = 0, deferred = 0, created = 0, matched = 0, kept = 0, failed = 0,
    nonGen1Species = 0, nonGen1Moves = 0, heals = 0,
    dexSyncs = 0, dexApplied = 0, dexAddedSeen = 0, dexAddedOwned = 0, dexUnknown = 0,
    items = 0, itemsBad = 0,
    -- M8 (wave 2)
    takes = 0, deposits = 0, withdraws = 0, opsResent = 0, opsConfirmed = 0, opsGivenUp = 0,
    excluded = 0, renames = 0, catches = 0,
    forgotten = 0, -- M9: syncs dropped by a save-slot switch
    givesHeld = 0, opsHeld = 0, -- M10: steps first sends waited for a save.begin
  }
  local deferredNow = false
  local seen = {}   -- give ids counted
  local sentAt = {} -- give id -> clock when first sent
  local opSentAt = {} -- op id -> clock when first sent
  local persistedOps = {} -- op ids whose party change the uid list was saved after
  local nicks, nickSave = {}, nil -- uid -> the nickname last seen (false: none), and its save

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

  -- The nicknames of the members with a uid, as they are now (after a
  -- sync, a reload or a new save: nothing to report).
  local function reseedNicks()
    local list, save = engine.partyNicks()
    nicks, nickSave = {}, save
    for _, e in ipairs(list) do nicks[e.uid] = e.nick end
  end

  -- M8: a sync confirms the pending operations it reflects (a take or a
  -- deposit: the uid gone; a withdrawal: the uid back); the others count
  -- one more sync, and are given up on with the gifts' limits.
  local function confirmOps(data)
    local inSync = {}
    for _, e in ipairs(data.party) do inSync[e.uid] = true end
    for _, op in ipairs(engine.ops()) do
      local done = (op.kind == "withdraw") == (inSync[op.uid] == true)
      if done then
        engine.opDone(op.id)
        counts.opsConfirmed = counts.opsConfirmed + 1
      elseif op.state == "sent" then
        if op.syncs + 1 >= Party.GIVE_UP_AFTER
            and (not clock or clock() - (opSentAt[op.id] or clock()) >= Party.GIVE_UP_SECONDS) then
          engine.opDone(op.id)
          counts.opsGivenUp = counts.opsGivenUp + 1
          if log then log.warn("party.op_unconfirmed", { kind = op.kind, count = counts.opsGivenUp }) end
        else
          engine.opSynced(op.id)
        end
      end
    end
  end

  local function apply(data)
    local _, known = engine.partyUids()
    local gifts = engine.gifts()
    local excluded = engine.excludedUids()
    confirmOps(data)
    local taken = {}
    local entries = {}
    local species0, moves0 = counts.nonGen1Species, counts.nonGen1Moves
    for _, e in ipairs(data.party) do
      local species = e.dex <= 151 and engine.speciesOfDex(e.dex) or nil
      if excluded[e.uid] then
        -- traded away, or boarded at the daycare: not the hidden party's
        counts.excluded = counts.excluded + 1
      elseif not species then
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
        if e.give ~= nil then
          -- exact: the gift Cobblemon says this Pokemon came from (M6)
          for _, g in ipairs(gifts) do
            if g.give == e.give and not taken[g.give] then
              entry.give = g.give
              taken[g.give] = true
              counts.givesExact = counts.givesExact + 1
              if g.box then counts.givesConfirmed = counts.givesConfirmed + 1 end
              break
            end
          end
        elseif not known[e.uid] then
          -- a member the hidden party doesn't know: the pending gift it is,
          -- by species and level (Cobblemon didn't name one)
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
    reseedNicks()
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

  -- dex.sync from the session (role already checked). Returns true, or
  -- false plus bad_message.
  function party.dex(data)
    if not Party.validDex(data) then return false, "bad_message" end
    counts.dexSyncs = counts.dexSyncs + 1
    pendingDex = data
    return true
  end

  local function applyDex(data)
    local res = engine.applyDex(data.seen, data.owned)
    if not res then return false end
    counts.dexApplied = counts.dexApplied + 1
    counts.dexAddedSeen = counts.dexAddedSeen + res.seen
    counts.dexAddedOwned = counts.dexAddedOwned + res.owned
    counts.dexUnknown = counts.dexUnknown + res.unknown
    lastDex = data
    if log then
      log.info("party.dex", { seen = #data.seen, owned = #data.owned, addedSeen = res.seen,
                              addedOwned = res.owned, count = counts.dexApplied })
    end
    return true
  end

  -- Items used on a party member (kb_engine's queue), once each, while live.
  local function sendItems(out)
    for _, use in ipairs(engine.takeItemUses()) do
      local data = Party.itemData(use)
      if data then
        out[#out + 1] = { "party.item", data, "party" }
        counts.items = counts.items + 1
        if log then
          log.info("party.item", { kind = data.move and "machine" or "item", slot = data.slot, count = counts.items })
        end
      else
        counts.itemsBad = counts.itemsBad + 1
        if log then log.warn("party.item_unsendable", { count = counts.itemsBad }) end
      end
    end
  end

  -- M8: the boarded member's record in mod.save, { uid, exp0 } or nil.
  local function storedDaycare()
    if not store then return nil end
    local ok, rec = pcall(store.get, "daycare")
    return ok and type(rec) == "table" and rec or nil
  end

  -- M8: the daycare watch (a deposit or a withdrawal queues its operation
  -- in kb_engine; the boarded uid is kept in mod.save).
  local function watchDaycare()
    local ok, ev = pcall(engine.daycareStep, storedDaycare())
    if not ok or type(ev) ~= "table" then return end
    if ev.kind == "deposit" then
      if store then pcall(store.set, "daycare", { uid = ev.uid, exp0 = ev.exp0 }) end
      persist()
      if log then log.info("party.daycare_in", {}) end
    elseif ev.kind == "withdraw" or ev.kind == "cleared" or ev.kind == "lost" then
      if store then pcall(store.set, "daycare", nil) end
      persist()
      if log then log.info("party.daycare_out", { kind = ev.kind, steps = ev.steps }) end
    elseif ev.kind == "unknown" and log then
      log.warn("party.daycare_no_uid", {})
    end
  end

  -- M8: nicknames of members with a uid that changed since the last look
  -- (the Name Rater) are queued as renames.
  local function watchNicks()
    local list, save = engine.partyNicks()
    if save ~= nickSave then
      reseedNicks()
      return
    end
    for _, e in ipairs(list) do
      local before = nicks[e.uid]
      if before ~= nil and before ~= e.nick then engine.queueRename(e.uid, e.nick) end
      nicks[e.uid] = e.nick
    end
  end

  -- M10: a first send made after a save still waiting to be announced waits
  -- for its save.begin (hold: that save's serial, or nil).
  local function later(item, hold)
    return hold ~= nil and item.state == "new" and type(item.serial) == "number" and item.serial > hold
  end

  -- M8: takes, deposits and withdrawals due on this connection.
  local function sendOps(out, link, hold)
    for _, op in ipairs(engine.ops()) do
      local due = op.state == "new" or (op.state == "sent" and (resend or op.sentGen ~= link.generation))
      if due and later(op, hold) then
        due = false
        counts.opsHeld = counts.opsHeld + 1
      end
      if due then
        local msg
        if op.kind == "take" then
          msg = { "party.take", { uid = op.uid, reason = op.reason or "trade" }, "party" }
        elseif op.kind == "deposit" then
          msg = { "daycare.deposit", { uid = op.uid }, "party" }
        elseif op.kind == "withdraw" then
          msg = { "daycare.withdraw", { uid = op.uid, steps = math.max(0, math.floor(op.steps or 0)) }, "party" }
        end
        if msg then
          out[#out + 1] = msg
          if op.state == "new" then
            local field = op.kind == "take" and "takes" or (op.kind == "deposit" and "deposits" or "withdraws")
            counts[field] = counts[field] + 1
            if clock and opSentAt[op.id] == nil then opSentAt[op.id] = clock() end
            if log then log.info("party." .. op.kind, { count = counts[field] }) end
            onOp()
          else
            counts.opsResent = counts.opsResent + 1
          end
          engine.opSent(op.id, link.generation)
        else
          engine.opDone(op.id)
        end
      end
    end
  end

  -- M8: the queued renames, once each.
  local function sendRenames(out)
    for _, r in ipairs(engine.takeRenames()) do
      local nick = r.nick and text(r.nick) or ""
      out[#out + 1] = { "party.rename", { uid = r.uid, nick = nick }, "party" }
      counts.renames = counts.renames + 1
      if log then log.info("party.rename", { len = #nick, count = counts.renames }) end
    end
  end

  -- One logic step. out gets party.take / daycare.* / party.give /
  -- party.heal / party.item / party.rename; link = { live, generation }.
  function party.step(out, link)
    link = link or {}
    local live = link.live
    -- a TM/HM being taught: its move and slot are queued once it lands
    engine.tmWatchStep()
    -- M8: native catches whose battle is over become gifts, then the
    -- daycare and the nicknames are looked at (before anything is sent or
    -- a sync applied)
    local released = engine.releaseCatches()
    if released > 0 then
      counts.catches = counts.catches + released
      if log then log.info("party.native_catch", { count = counts.catches }) end
    end
    watchDaycare()
    watchNicks()
    -- a trade changed the party's order: the uids by position again
    for _, op in ipairs(engine.ops()) do
      if op.state == "new" and not persistedOps[op.id] then
        persistedOps[op.id] = true
        persist()
      end
    end
    -- M10: a save the hidden game made that hasn't been announced yet
    local okHold, hold = pcall(engine.queuedSaveSerial)
    hold = okHold and type(hold) == "number" and hold or nil
    if live then sendOps(out, link, hold) end
    for _, g in ipairs(engine.gifts()) do
      if not seen[g.give] then
        seen[g.give] = true
        counts.gives = counts.gives + 1
      end
      local due = g.state == "new" or (g.state == "sent" and (resend or g.sentGen ~= link.generation))
      if due and live and later(g, hold) then
        due = false
        counts.givesHeld = counts.givesHeld + 1
      end
      if due and live then
        local data = giveData(g)
        if data then
          out[#out + 1] = { "party.give", data, "party" }
          if g.state == "new" then
            counts.givesSent = counts.givesSent + 1
            onOp()
          else
            counts.givesResent = counts.givesResent + 1
          end
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
      sendItems(out)
      sendRenames(out)
      resend = false
    end
    -- M9: while a save-slot switch waits (link.blocked), the new world's
    -- syncs wait for the game they belong to
    if pendingDex and engine.inGame() and not link.blocked then
      local data = pendingDex
      pendingDex = nil
      local ok = pcall(applyDex, data)
      if not ok and log then log.warn("party.dex_failed", { count = counts.dexSyncs }) end
    end
    if pending then
      if engine.partySafe() and not link.blocked then
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

  -- New Game or CONTINUE: the last sync applies again to the new party, and
  -- the last Pokedex to the new save.
  function party.reapply()
    if lastSync and not pending then pending = lastSync end
    if lastDex and not pendingDex then pendingDex = lastDex end
  end

  -- M9: a session.bind that switches save slots: everything Cobblemon sent
  -- before it belongs to the world being left, so it never reaches the
  -- paired game (the client sends the new world's again once it is bound).
  function party.forget()
    local dropped = (pending and 1 or 0) + (pendingDex and 1 or 0)
    pending, lastSync, pendingDex, lastDex = nil, nil, nil, nil
    deferredNow = false
    counts.forgotten = counts.forgotten + dropped
  end

  function party.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    out.waiting = pending ~= nil
    out.dexWaiting = pendingDex ~= nil
    return out
  end

  return party
end

return Party
