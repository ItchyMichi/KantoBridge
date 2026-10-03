-- kb_bag: the bag's battle items mirrored into Minecraft (docs/PLAN.md P17,
-- section 4.1; docs/PROTOCOL.md "Party, Pokedex, bag, PC").
--
--   bag.sync{items{item:qty}}   G->M   the battle-usable subset of the bag
--   bag.use{item, qty}          M->G   used up in Minecraft
--
-- The bag stays the hidden game's (P17). Its battle items -- the balls, the
-- medicine and the X items of PLAN Appendix B, by their engine symbols --
-- are mirrored into Minecraft as Cobblemon items: bag.sync carries every one
-- of them the bag holds (1 or more; an item that isn't listed is 0), never a
-- key item. It goes out after every snapshot and whenever one of those
-- quantities changes, only while an overworld is up (at the title the bag
-- is the boot skeleton's, not the player's). Which Cobblemon item each one
-- becomes is Minecraft's business; anything it can't map stays Gen 1-only.
--
-- bag.use: Cobblemon used items up (a ball thrown, a potion given), so the
-- hidden game's bag loses them through the engine's Bag.remove
-- (kb_engine.bagRemove), never below zero: asking for more than the bag
-- holds takes what is there and counts a mismatch (a warning). Only
-- mirrored items can be used up this way (error bag.not_mirrored); uses
-- wait for an overworld, then apply in order, and the bag.sync that follows
-- shows the result.
--
-- Logs show codes, item symbols and counts only.

local Bag = {}

-- PLAN Appendix B's battle items (Gen 1 symbols): balls, medicine, X items.
Bag.ITEMS = {
  "POKE_BALL", "GREAT_BALL", "ULTRA_BALL", "MASTER_BALL", "SAFARI_BALL",
  "POTION", "SUPER_POTION", "HYPER_POTION", "MAX_POTION", "FULL_RESTORE", "REVIVE", "MAX_REVIVE",
  "ANTIDOTE", "BURN_HEAL", "ICE_HEAL", "AWAKENING", "PARLYZ_HEAL", "FULL_HEAL",
  "ETHER", "MAX_ETHER", "ELIXER", "MAX_ELIXER",
  "X_ATTACK", "X_ACCURACY", "X_SPEED", "X_DEFEND", "X_SPECIAL", "DIRE_HIT", "GUARD_SPEC",
}
Bag.MAX_QUEUE = 32  -- bag.use requests waiting for an overworld

local MIRRORED = {}
for _, id in ipairs(Bag.ITEMS) do MIRRORED[id] = true end
Bag.MIRRORED = MIRRORED

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

-- opts:
--   engine   kb_engine instance (bagCounts, bagRemove, inGame)
--   json     kb_json (bag.sync's items is an object even when empty)
--   log      kb_log logger (optional)
function Bag.new(opts)
  local engine, json, log = opts.engine, opts.json, opts.log
  local bag = {}
  local sent, sentGen   -- the items last sent, and on which connection
  local resend = false  -- a snapshot went out: send them again
  local queue = {}      -- bag.use requests waiting to apply
  local counts = { syncs = 0, uses = 0, removed = 0, mismatch = 0, refused = 0, dropped = 0 }

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
  -- mirrored quantities changed or a snapshot asked for everything again.
  -- out gets { "bag.sync", data, tag }; link = { live, generation }.
  function bag.step(out, link)
    local inGame = engine.inGame()
    if inGame and #queue > 0 then
      local list = queue
      queue = {}
      for _, u in ipairs(list) do apply(u) end
    end
    if not (link and link.live and inGame) then return end
    local items = engine.bagCounts(Bag.ITEMS)
    if resend or sentGen ~= link.generation or not same(items, sent) then
      out[#out + 1] = { "bag.sync", { items = json.object(items) }, "bag" }
      sent, sentGen = items, link.generation
      counts.syncs = counts.syncs + 1
    end
    resend = false
  end

  -- After a snapshot (hello or resync): the mirrored items go out again.
  function bag.rebase() resend = true end

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
