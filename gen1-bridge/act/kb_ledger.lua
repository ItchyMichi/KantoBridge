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
function Ledger.new(opts)
  local engine, json, store, log = opts.engine, opts.json, opts.store, opts.log
  local clock = opts.clock or function() return 0 end
  local act = {}
  local queue = {}     -- requests waiting, oldest first
  local current        -- the request at the head, waiting for a free world
  local steps = 0
  local counts = { requests = 0, sets = 0, ok = 0, tooMany = 0, left = 0, refused = 0, busy = 0, unknown = 0,
                   dropped = 0 }

  -- bag.set from the session (role already checked). Returns true, or false
  -- plus "bad_message".
  function act.request(data, seq, gen)
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
    local r = { items = items, money = data.money, stamp = data.stamp, seq = seq, gen = gen, since = steps }
    if #queue >= Ledger.QUEUE then
      r.refused = true
      counts.busy = counts.busy + 1
    end
    queue[#queue + 1] = r
    return true
  end

  local function answer(out, r, ok, reason, left)
    local data = { ok = ok, kind = "bag.set" }
    if reason then data.reason = reason end
    if left then data.left = left end
    out[#out + 1] = { "act.result", data, "answer", re = r.seq, gen = r.gen }
    if ok then counts.ok = counts.ok + 1 else counts.refused = counts.refused + 1 end
    if log then log.debug("bag.set", { ok = ok, reason = reason }) end
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
    local ok, left = engine.bagSet(r.items, r.money)
    if not ok then
      answer(out, r, false, "failed")
      return
    end
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
