-- kb_bag: the bag mirrored into Minecraft (docs/PLAN.md P17, section 4.1;
-- docs/PROTOCOL.md "Party, Pokedex, bag, PC"; MG6: docs/specs/
-- MULTI_GAME_PLAN.md §4.9 "One trainer economy", docs/PROTOCOL.md "One
-- economy (MG6)").
--
--   bag.sync{items{item:qty}, money, stamp?}   G->M   the bag as the ledger reads it
--   bag.use{item, qty}                         M->G   used up in Minecraft
--
-- The items come from the canonical item catalogue (lib/kb_items.lua,
-- generated from protocol/items.json), by this game's family's symbols:
--   * the SYNCED set is every catalogue item that isn't class `key` (balls,
--     medicine, X items, field items, stones, valuables, TMs): bag.sync
--     carries each one the bag holds (1 or more; an item that isn't listed
--     is 0), so the server's ledger reads purchases, prizes and found items
--     back; never a key item, an HM or a badge (kb_engine.isKeyItem), and
--     never a story item the catalogue doesn't know;
--   * the MIRRORED set is the synced items with a Cobblemon item and a
--     battle-usable class (ball, medicine, battle: PLAN Appendix B's 29):
--     the ones Minecraft creates stacks for, and the only ones bag.use may
--     take out (error bag.not_mirrored otherwise).
-- bag.sync also carries save.money and, once the server has projected its
-- ledger into this save (bag.set, act/kb_ledger.lua), the projection's
-- stamp (mod.save "ledger"), so the server can tell a diff from a save it
-- has never seen. It goes out after every snapshot and whenever an item,
-- the money or the stamp changes, only while an overworld is up (at the
-- title the bag is the boot skeleton's, not the player's).
--
-- bag.use: Cobblemon used items up (a ball thrown, a potion given), so the
-- hidden game's bag loses them through the engine's Bag.remove
-- (kb_engine.bagRemove), never below zero: asking for more than the bag
-- holds takes what is there and counts a mismatch (a warning). Uses wait
-- for an overworld, then apply in order, and the bag.sync that follows
-- shows the result.
--
-- Logs show codes, item symbols and counts only.

local Bag = {}

-- the classes Minecraft mirrors (PLAN Appendix B: balls, medicine, X items)
Bag.MIRROR_CLASSES = { ball = true, medicine = true, battle = true }
Bag.MAX_QUEUE = 32  -- bag.use requests waiting for an overworld
Bag.MAX_MONEY = 999999

local floor = math.floor

local function whole(v, lo, hi)
  return type(v) == "number" and v == floor(v) and v >= lo and v <= hi
end

local function same(a, b)
  if a == nil or b == nil then return false end
  for k, v in pairs(a) do if b[k] ~= v then return false end end
  for k, v in pairs(b) do if a[k] ~= v then return false end end
  return true
end

-- The catalogue's sets for a family: mirrored (symbol -> true) and synced
-- (a sorted list of symbols).
function Bag.sets(items, family)
  local mirrored, synced = {}, {}
  local cat = type(items) == "table" and type(items.items) == "table" and items.items or {}
  for _, entry in pairs(cat) do
    local sym = type(entry) == "table" and type(entry.symbols) == "table" and entry.symbols[family] or nil
    if type(sym) == "string" and entry.class ~= "key" then
      synced[#synced + 1] = sym
      if type(entry.cobblemon) == "string" and Bag.MIRROR_CLASSES[entry.class] then mirrored[sym] = true end
    end
  end
  table.sort(synced)
  return mirrored, synced
end

-- opts:
--   engine   kb_engine instance (bagCounts, bagRemove, inGame, save, family)
--   json     kb_json (bag.sync's items is an object even when empty)
--   items    the item catalogue (lib/kb_items.lua); nil mirrors nothing
--   store    { get(key) }: mod.save, for the ledger stamp (optional)
--   family   the game's family (default: engine.family(), else "gen1")
--   log      kb_log logger (optional)
function Bag.new(opts)
  local engine, json, log, store = opts.engine, opts.json, opts.log, opts.store
  local family = opts.family or (type(engine.family) == "function" and engine.family()) or "gen1"
  local MIRRORED, SYNCED = Bag.sets(opts.items, family)
  local bag = {}
  local sent, sentGen        -- the items last sent, and on which connection
  local sentMoney, sentStamp -- the money and stamp last sent
  local resend = false       -- a snapshot went out: send them again
  local queue = {}           -- bag.use requests waiting to apply
  local counts = { syncs = 0, uses = 0, removed = 0, mismatch = 0, refused = 0, dropped = 0 }

  -- The projection stamp in the save's mod data (act/kb_ledger.lua), or nil.
  local function ledgerStamp()
    local rec = store and store.get("ledger") or nil
    local s = type(rec) == "table" and rec.stamp or nil
    if type(s) == "string" and #s >= 8 and #s <= 64 and s:find("^[0-9a-f]+$") then return s end
    return nil
  end

  -- bag.use from the session (role already checked). Returns true, or false
  -- plus an error code (bad_message, bag.not_mirrored).
  function bag.use(data)
    local item, qty = data.item, data.qty
    if not (type(item) == "string" and #item <= 64 and item:find("^[A-Z0-9_]+$") and whole(qty, 1, 999)) then
      return false, "bad_message"
    end
    if not MIRRORED[item] then
      counts.refused = counts.refused + 1
      if log then log.warn("bag.use_refused", { count = counts.refused }) end
      return false, "bag.not_mirrored"
    end
    if #queue >= Bag.MAX_QUEUE then
      table.remove(queue, 1)
      counts.dropped = counts.dropped + 1
      if log then log.warn("bag.use_dropped", { count = counts.dropped }) end
    end
    queue[#queue + 1] = { item = item, qty = qty }
    return true
  end

  local function apply(u)
    counts.uses = counts.uses + 1
    local removed, held = engine.bagRemove(u.item, u.qty)
    counts.removed = counts.removed + removed
    if removed < u.qty then
      counts.mismatch = counts.mismatch + 1
      if log then
        log.warn("bag.use_mismatch", { item = u.item, asked = u.qty, held = held, count = counts.mismatch })
      end
    end
  end

  -- One logic step: the waiting uses apply, then bag.sync goes out when the
  -- synced quantities, the money or the stamp changed, or a snapshot asked
  -- for everything again. out gets { "bag.sync", data, tag }; link = { live,
  -- generation }.
  function bag.step(out, link)
    local inGame = engine.inGame()
    if inGame and #queue > 0 then
      local list = queue
      queue = {}
      for _, u in ipairs(list) do apply(u) end
    end
    if not (link and link.live and inGame) then return end
    local items = engine.bagCounts(SYNCED)
    local save = engine.saveView()
    local money = type(save) == "table" and tonumber(save.money) or 0
    money = math.max(0, math.min(Bag.MAX_MONEY, floor(money)))
    local stamp = ledgerStamp()
    if resend or sentGen ~= link.generation or not same(items, sent) or money ~= sentMoney or stamp ~= sentStamp then
      local data = { items = json.object(items), money = money }
      if stamp then data.stamp = stamp end
      out[#out + 1] = { "bag.sync", data, "bag" }
      sent, sentGen, sentMoney, sentStamp = items, link.generation, money, stamp
      counts.syncs = counts.syncs + 1
    end
    resend = false
  end

  -- After a snapshot (hello or resync): the bag goes out again.
  function bag.rebase() resend = true end

  -- The sets this game uses (sorted lists), for tests and drivers.
  function bag.mirrored()
    local out = {}
    for sym in pairs(MIRRORED) do out[#out + 1] = sym end
    table.sort(out)
    return out
  end
  function bag.synced()
    local out = {}
    for i, sym in ipairs(SYNCED) do out[i] = sym end
    return out
  end

  function bag.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    out.waiting = #queue
    local kinds, total = 0, 0
    for _, n in pairs(sent or {}) do
      kinds = kinds + 1
      total = total + n
    end
    out.items, out.total = kinds, total
    return out
  end

  return bag
end

return Bag
