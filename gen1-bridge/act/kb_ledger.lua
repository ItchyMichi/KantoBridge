-- kb_ledger: the one trainer economy's projection into this game
-- (docs/specs/MULTI_GAME_PLAN.md §4.9, D7; docs/PROTOCOL.md "One economy
-- (MG6)"). Money and items are the player's, kept server-side in a ledger;
-- when this game is resumed or started the server projects the ledger into
-- its save, and every bag.sync afterwards (observe/kb_bag.lua: items, money,
-- the stamp) reads the changes back.
--
--   bag.set{items{symbol:qty}, money, stamp}   M->G
--     -> act.result{ok, kind:"bag.set", reason?, left?}   (re = the request)
--
-- The act waits for a free world (in the game, standing, nothing scripted, no
-- warp, no battle, no UI: a bag menu already open caches its rows) and for no
-- save-slot switch waiting (the bag isn't the new world's yet), at most WAIT
-- logic steps, else `busy`; `not_ingame` at the title. Then the adapter's
-- bagSet replaces the bag's non-key items with the projection within the
-- game's limits (20 slots, 99 a stack) and writes save.money; what did not
-- fit comes back as `too_many` with `left` (the bag is written anyway: those
-- items stay in the server's ledger, "in your trunk"). The stamp goes into
-- the save's mod data (mod.save "ledger", save.modData, so it loads and
-- saves with the hidden game) and the next bag.sync echoes it, in this very
-- step, so the server knows the projection landed. Symbols the game's item
-- table doesn't know are dropped and counted (bag.set_unknown, once).
--
-- One request at a time, like the Wild Routes acts (act/kb_route.lua): more
-- wait (at most QUEUE; beyond that, busy); the requests of an old connection
-- are dropped. Logs show counts and codes only.
--
-- What happens while a request waits stays. The server worked the
-- projection out when it sent it; the game keeps playing until the world is
-- free: a bag.use arrives and applies at once (kb_bag), the player picks up
-- an item ball, wins prize money. So the bag is noted when the request
-- arrives (after every bag.use that arrived before it: the projection
-- already counts those, so they apply first, opts.applyUses), and the
-- landing writes the projection plus what changed in the bag and the money
-- since then; the bag.sync that confirms it reads those changes back. A
-- landing is no change of the game's own for the requests still waiting
-- (their notes move by it). A save other than the noted one (CONTINUE in
-- between) keeps nothing.

local Ledger = {}

Ledger.WAIT = 120            -- logic steps a request waits for a free world
Ledger.QUEUE = 8             -- requests waiting at most
Ledger.STORE_KEY = "ledger"  -- mod.save key: { stamp, at }
Ledger.MAX_ITEMS = 128       -- symbols in one bag.set (the schema's maxProperties)
Ledger.MAX_QTY = 9999
Ledger.MAX_MONEY = 999999

local floor = math.floor

local function whole(v, lo, hi)
  return type(v) == "number" and v == floor(v) and v >= lo and v <= hi
end
local function symbol(v) return type(v) == "string" and #v >= 1 and #v <= 64 and v:find("^[A-Z0-9_]+$") ~= nil end
local function stampText(v) return type(v) == "string" and #v >= 8 and #v <= 64 and v:find("^[0-9a-f]+$") ~= nil end

-- opts:
--   engine   the engine adapter (inGame, bagSet, itemTable)
--   json     kb_json (isObject: bag.set's items must be an object, never a list)
--   store    { get(key), set(key, value) }: mod.save
--   clock    seconds (love.timer.getTime)
--   log      kb_log logger (optional)
--   applyUses  function(through): kb_bag's waiting bag.uses that arrived
--              up to `through` apply now (optional)
function Ledger.new(opts)
  local engine, json, store, log = opts.engine, opts.json, opts.store, opts.log
  local clock = opts.clock or function() return 0 end
  local applyUses = opts.applyUses
  local act = {}
  local queue = {}     -- requests waiting, oldest first
  local current        -- the request at the head, waiting for a free world
  local steps = 0
  local counts = { requests = 0, sets = 0, ok = 0, tooMany = 0, left = 0, refused = 0, busy = 0, unknown = 0,
                   dropped = 0, kept = 0 }

  -- The bag trace (beside kb_bag's): bagtrace.set_* lines, item symbols as
  -- fields, the wall clock as t where the sandbox has one.
  local function trace(code, fields, items)
    if not log then return end
    fields.t = type(log.wall) == "function" and log.wall() or nil
    for sym, n in pairs(items or {}) do fields[sym] = n end
    log.info("bagtrace." .. code, fields)
  end

  local function short(stamp)
    return type(stamp) == "string" and stamp:sub(1, 6) or "-"
  end

  -- The bag's non-key catalogue items and the money now, and the save they
  -- belong to.
  local function note()
    local syms = {}
    for sym in pairs(engine.itemTable()) do syms[#syms + 1] = sym end
    table.sort(syms)
    local items = {}
    for sym, n in pairs(engine.bagCounts(syms) or {}) do items[sym] = n end
    local view = engine.saveView and engine.saveView() or nil
    local money = type(view) == "table" and tonumber(view.money) or 0
    return { items = items, money = floor(money), save = engine.save and engine.save() or nil }
  end

  -- The projection plus what changed between two notes (`base` then `now`),
  -- and how many items changed.
  local function withChanges(items, money, base, now)
    local out, changed = {}, 0
    for id, qty in pairs(items) do out[id] = qty end
    for id, n in pairs(now.items) do
      local d = n - (base.items[id] or 0)
      if d ~= 0 then out[id] = (out[id] or 0) + d; changed = changed + 1 end
    end
    for id, n in pairs(base.items) do
      if now.items[id] == nil then out[id] = (out[id] or 0) - n; changed = changed + 1 end
    end
    for id, qty in pairs(out) do
      if qty <= 0 then out[id] = nil end
    end
    local m = math.max(0, math.min(Ledger.MAX_MONEY, money + now.money - base.money))
    if m ~= money then changed = changed + 1 end
    return out, m, changed
  end

  -- A landing moved the bag from `before` to `after`: that isn't a change of
  -- the game's own for a request still waiting.
  local function shift(r, before, after)
    if not (r and r.base) then return end
    for id, n in pairs(after.items) do
      local d = n - (before.items[id] or 0)
      if d ~= 0 then r.base.items[id] = (r.base.items[id] or 0) + d end
    end
    for id, n in pairs(before.items) do
      if after.items[id] == nil then r.base.items[id] = (r.base.items[id] or 0) - n end
    end
    r.base.money = r.base.money + after.money - before.money
  end

  -- The note a request's landing starts from: the bag once every bag.use
  -- that arrived before the request has applied.
  local function noteFor(r)
    if applyUses and r.at then applyUses(r.at - 1) end
    r.base = note()
  end

  -- bag.set from the session (role already checked); `at` orders it among
  -- the bag.uses (main.lua's arrival count). Returns true, or false plus
  -- "bad_message".
  function act.request(data, seq, gen, at)
    if type(data) ~= "table" or type(data.items) ~= "table" or not whole(data.money, 0, Ledger.MAX_MONEY)
        or not stampText(data.stamp) then
      return false, "bad_message"
    end
    -- a list is not an object (an empty one decodes to an empty table too: kb_json tags them apart)
    if json and type(json.isObject) == "function" and not json.isObject(data.items) then return false, "bad_message" end
    local items, n = {}, 0
    for id, qty in pairs(data.items) do
      if not (symbol(id) and whole(qty, 0, Ledger.MAX_QTY)) then return false, "bad_message" end
      n = n + 1
      if n > Ledger.MAX_ITEMS then return false, "bad_message" end
      items[id] = qty
    end
    counts.requests = counts.requests + 1
    local r = { items = items, money = data.money, stamp = data.stamp, seq = seq, gen = gen, since = steps, at = at }
    if #queue >= Ledger.QUEUE then
      r.refused = true
      counts.busy = counts.busy + 1
    elseif engine.inGame() then
      noteFor(r)
    end
    queue[#queue + 1] = r
    trace("set_in", { seq = seq, at = at, stamp = short(r.stamp), money = r.money, noted = r.base ~= nil,
                      refused = r.refused == true, waiting = #queue + (current and 1 or 0) }, r.items)
    return true
  end

  local function answer(out, r, ok, reason, left)
    local data = { ok = ok, kind = "bag.set" }
    if reason then data.reason = reason end
    if left then data.left = left end
    out[#out + 1] = { "act.result", data, "answer", re = r.seq, gen = r.gen }
    if ok then counts.ok = counts.ok + 1 else counts.refused = counts.refused + 1 end
    if log then log.debug("bag.set", { ok = ok, reason = reason }) end
    trace("set_answer", { seq = r.seq, stamp = short(r.stamp), ok = ok, reason = reason or "-" }, left)
  end

  -- The world is free for the bag: in the game, standing, nothing scripted,
  -- no warp, no battle, no screen (act/kb_route.lua's rule).
  local function idle(f, mode)
    return f.inGame and mode == "idle" and not f.moving and not f.scripted and not f.transitioning
      and not f.battle and not f.forcedPhase
  end

  -- One logic step. out gets the act.result; link = { live, generation,
  -- blocked } (blocked: a save-slot switch waits, the bag isn't this
  -- world's yet).
  function act.step(facts, mode, out, link)
    steps = steps + 1
    while current == nil and #queue > 0 do
      local r = table.remove(queue, 1)
      if r.gen ~= link.generation then
        counts.dropped = counts.dropped + 1
        trace("set_dropped", { seq = r.seq, why = "old_connection" })
      elseif r.refused then
        answer(out, r, false, "busy")
      else
        current = r
        current.since = steps
      end
    end
    local r = current
    if not r then return end
    if r.gen ~= link.generation then
      current = nil
      counts.dropped = counts.dropped + 1
      trace("set_dropped", { seq = r.seq, why = "old_connection" })
      return
    end
    if not facts.inGame then
      current = nil
      answer(out, r, false, "not_ingame")
      return
    end
    if link.blocked or not idle(facts, mode) then
      if steps - r.since > Ledger.WAIT then
        current = nil
        counts.busy = counts.busy + 1
        answer(out, r, false, "busy")
      end
      return
    end
    current = nil
    local table_ = engine.itemTable()
    local unknown = 0
    for id in pairs(r.items) do
      if not table_[id] then unknown = unknown + 1 end
    end
    if unknown > 0 then
      counts.unknown = counts.unknown + unknown
      if log then log.once("bag.set_unknown", "warn", "bag.set_unknown", { count = unknown }) end
    end
    if not r.base then noteFor(r) end
    local before = note()
    local items, money = r.items, r.money
    local changed, sameSave = 0, r.base.save == before.save
    if sameSave then
      items, money, changed = withChanges(r.items, r.money, r.base, before)
      if changed > 0 then counts.kept = counts.kept + 1 end
    end
    -- the bag just before the landing, then what it lands as
    trace("set_before", { seq = r.seq, money = before.money, noteMoney = r.base.money }, before.items)
    local ok, left = engine.bagSet(items, money)
    if not ok then
      answer(out, r, false, "failed")
      return
    end
    local after = note()
    trace("set_land", { seq = r.seq, stamp = short(r.stamp), kept = changed, sameSave = sameSave, money = after.money,
                        stillWaiting = #queue }, after.items)
    for _, w in ipairs(queue) do shift(w, before, after) end
    counts.sets = counts.sets + 1
    if store then store.set(Ledger.STORE_KEY, { stamp = r.stamp, at = floor(clock()) }) end
    local leftCount = 0
    for _ in pairs(left) do leftCount = leftCount + 1 end
    if leftCount > 0 then
      counts.tooMany = counts.tooMany + 1
      counts.left = counts.left + leftCount
      answer(out, r, false, "too_many", left)
    else
      answer(out, r, true)
    end
  end

  -- The stamp of the last projection into this save, or nil.
  function act.stamp()
    local rec = store and store.get(Ledger.STORE_KEY) or nil
    return type(rec) == "table" and stampText(rec.stamp) and rec.stamp or nil
  end

  function act.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    out.waiting = #queue + (current and 1 or 0)
    return out
  end

  return act
end

return Ledger
