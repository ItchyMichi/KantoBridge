-- Fake wild encounters, fishing, the bag and items used on a party member
-- for the fake engine (M6). Written for Kanto Bridge from the engine
-- behaviour lib/kb_engine.lua cites (OverworldController's onStepComplete
-- encounter roll and goFishing/fishVerdict, src/inventory/Bag.lua,
-- src/ui/BagMenu.lua's use-flow, src/ui/MoveLearnMenu.lua); the code is our
-- own, and every species, item and text the tests install is made up.
--
--   FakeWild.attach(E)        after FakeScript.attach
--   E.rolls[terrain]          what the vanilla roll draws ("grass", "water",
--                             "indoor"): { species, level }, or nil for none
--   E.rollCalls[terrain]      vanilla rolls made (a suppressed one makes none)
--   E.fishPools[rod]          the rod's candidates ({ { species, level } ...},
--                             or { always = { species, level } }); E.fishBites
--                             false makes the vanilla pick bite nothing
--   E.goFishing(rod)          OverworldState:goFishing's shape: the roll
--                             through encounter.fishing, the cast box (auto),
--                             then the verdict: no bite / the bite and a
--                             native battle
--   E.useItem(id, mon, opts)  BagMenu's useOn for a party member (the
--                             item.use hook around a fake vanillaUseOn)
--   E.bagList()               a BagMenu-like list of the bag's rows
--   E.forgetSlot              the move slot the fake MoveLearnMenu replaces
--   E.nativeItems             what the fake vanillaUseOn did, in order
--
-- Engine modules it provides: src.inventory.Bag (add, remove),
-- src.inventory.ItemEffects (isStone), src.render.Transition (whiteFlash).

local FakeWild = {}

local STONES = { FIRE_STONE = true, WATER_STONE = true, THUNDER_STONE = true, LEAF_STONE = true,
                 MOON_STONE = true }

function FakeWild.attach(E)
  local ow = E.ow
  E.rolls, E.rollCalls, E.fishPools = {}, {}, {}
  E.fishBites = true
  E.nativeItems = {}
  E.forgetSlot = 1
  E.save.repelSteps = 0

  -- ------------------------------------------------------------ modules

  -- Bag.add / Bag.remove (src/inventory/Bag.lua:139-181): bagOrder keeps the
  -- acquisition order; remove clears the slot at zero (never negative)
  local Bag = {}
  function Bag.add(save, id, qty)
    save.inventory = save.inventory or {}
    save.bagOrder = save.bagOrder or {}
    if not save.inventory[id] then save.bagOrder[#save.bagOrder + 1] = id end
    save.inventory[id] = (save.inventory[id] or 0) + (qty or 1)
    return true
  end
  function Bag.remove(save, id, qty)
    local inv = save.inventory
    inv[id] = (inv[id] or 0) - (qty or 1)
    if inv[id] <= 0 then
      inv[id] = nil
      for i, oid in ipairs(save.bagOrder or {}) do
        if oid == id then table.remove(save.bagOrder, i) break end
      end
    end
  end
  E.modules["src.inventory.Bag"] = Bag
  E.Bag = Bag

  E.modules["src.inventory.ItemEffects"] = { isStone = function(id) return STONES[id] or false end }

  -- Transition.whiteFlash (src/render/Transition.lua:226-238): a state that
  -- pops itself after `frames` steps
  E.flashes = 0
  E.modules["src.render.Transition"] = {
    whiteFlash = function(_, frames)
      E.flashes = E.flashes + 1
      local s = { isFlash = true, t = 0, frames = frames or 8 }
      function s.update(self)
        self.t = self.t + 1
        if self.t >= self.frames then E.uiPop() end
      end
      return s
    end,
  }

  local function safariHere()
    local def = ow.map and ow.map.def
    return E.save.safari ~= nil and def ~= nil and E.Map.inRegion(def, "SAFARI", "SAFARI_ZONE")
  end
  local function ghostHere()
    local def = ow.map and ow.map.def
    local ghost = def and E.Map.ghostBattles(def)
    return ghost ~= nil and not (ghost.unlessItem and E.save.inventory[ghost.unlessItem])
  end

  local function wildBattle(enc, hooked)
    local info = { kind = "wild", species = enc.species, level = enc.level, hooked = hooked,
                   ghost = (not hooked and ghostHere()) or nil, safari = safariHere() or nil }
    local fake = { kind = "wild" }
    E.nativeBattle(info, function(result) ow:afterBattle(result, fake) end)
  end

  -- ------------------------------------------------------------ the step's encounter roll

  -- onStepComplete's tail (OC:4655-4664, :4768-4823): the grace steps after
  -- a wild battle, then grass, water while surfing, or any cell of an
  -- indoor map; the roll through encounter.roll and encounter.species;
  -- REPEL; the native battle (ghost and Safari variants)
  function E.afterStep()
    local grace = ow.wildEncounterGraceSteps or 0
    if grace > 0 then
      ow.wildEncounterGraceSteps = grace - 1
      return
    end
    local p, m = ow.player, ow.map
    local terrain
    if m:isGrassCell(p.cellX, p.cellY) then
      terrain = "grass"
    elseif p.surfing and m:isWaterCell(p.cellX, p.cellY) then
      terrain = "water"
    elseif m.def.indoor then
      terrain = "indoor"
    end
    if not terrain then return end
    local encDef = E.data.encounters and E.data.encounters[m.id] or nil
    local ctx = { mapId = m.id, terrain = terrain }
    local function vanilla(_, c)
      E.rollCalls[c.terrain] = (E.rollCalls[c.terrain] or 0) + 1
      local r = E.rolls[c.terrain]
      return r and { species = r.species, level = r.level } or nil
    end
    local enc
    if E.runtime.wants("encounter.roll") then
      enc = E.runtime.call("encounter.roll", vanilla, encDef, ctx)
    else
      enc = vanilla(encDef, ctx)
    end
    if not enc then return end
    local lead = E.save.party[1]
    if (E.save.repelSteps or 0) > 0 and lead and enc.level < lead.level then return end
    wildBattle(enc, false)
  end

  -- ------------------------------------------------------------ fishing

  -- goFishing (OC:2375-2407): the roll, then the cast box whose
  -- continuation is the verdict (fishVerdict, OC:2409-2431)
  function E.goFishing(rod)
    local pool = E.fishPools[rod]
    local always = pool and pool.always
    local function vanilla(_, _, candidates)
      if always then return { species = always.species, level = always.level } end
      local first = type(candidates) == "table" and candidates[1] or nil
      if not E.fishBites or not first then return nil end
      return { species = first.species, level = first.level }
    end
    local enc
    if E.runtime.wants("encounter.fishing") then
      enc = E.runtime.call("encounter.fishing", vanilla, rod, ow.map.id, pool)
    else
      enc = vanilla(rod, ow.map.id, pool)
    end
    E.fishCasts = (E.fishCasts or 0) + 1
    ow.fishing = { facing = ow.player.facing }
    ow.player.fishing = true
    E.uiPush(E.textBox("CAST THE ROD!", { auto = { delay = 20 }, onDone = function() E.fishVerdict(enc) end }))
  end

  function E.fishVerdict(enc)
    if not enc then
      E.uiPush(E.textBox("NO NIBBLE.", { onDone = function()
        ow.fishing = nil
        ow.player.fishing = nil
      end }))
      return
    end
    E.uiPush(E.textBox("A BITE!", { onDone = function()
      ow.fishing = nil
      ow.player.fishing = nil
      wildBattle(enc, true)
    end }))
  end

  -- the old man's catching demo (Commands.old_man_demo, src/script/Commands.lua:975-983)
  function E.oldManDemo(species, level, onDone)
    E.nativeBattle({ kind = "wild", species = species, level = level, demo = true }, onDone)
  end

  -- ------------------------------------------------------------ items on a party member

  -- BagMenu's list rows (buildItems, src/ui/BagMenu.lua:20-37): one per item
  -- in bag order, with its count, then CANCEL
  function E.bagList()
    local items = {}
    for _, id in ipairs(E.save.bagOrder or {}) do
      if E.save.inventory[id] then items[#items + 1] = { value = id, label = id, count = E.save.inventory[id] } end
    end
    items[#items + 1] = { cancel = true, label = "CANCEL" }
    return { items = items, index = 1, scroll = 0, isFakeList = true }
  end

  -- the item-use party menu (PartyMenu with keepOpen): close() pops it when
  -- it is on top (PartyMenu:close, src/ui/PartyMenu.lua:439-446)
  local PartyMenu = E.modules["src.ui.PartyMenu"]
  function E.partyPicker(opts)
    local s = setmetatable({ keepOpen = opts == nil or opts.keepOpen ~= false, picks = 0 }, { __index = PartyMenu })
    s.screenId = "PartyMenu"
    function s.close(self)
      if E.stack:top() == self then E.uiPop() end
      self.closed = true
    end
    return s
  end

  local function consume(id, list)
    Bag.remove(E.save, id, 1)
    for i, it in ipairs(list and list.items or {}) do
      if it.value == id then
        local left = E.save.inventory[id]
        if left then it.count = left else table.remove(list.items, i) end
        break
      end
    end
  end

  -- MoveLearnMenu (src/ui/MoveLearnMenu.lua): A writes the new move over
  -- slot E.forgetSlot (:111-112) and finishes learned; B gives up
  local function moveLearnMenu(mon, moveId, onDone)
    local s = { screenId = "MoveLearnMenu", isMenu = true }
    function s.update(self)
      local input = E.game.input
      if input:wasPressed("a") then
        E.uiPop()
        local md = E.data.moves[moveId]
        mon.moves[E.forgetSlot] = { id = moveId, pp = md and md.pp or 0 }
        onDone(true)
      elseif input:wasPressed("b") then
        E.uiPop()
        onDone(false)
      end
    end
    return s
  end

  -- vanillaUseOn's outcomes for the items the tests use (BagMenu.lua:89-511
  -- over ItemEffects.use): a stone evolves the member, RARE_CANDY levels it
  -- up, a TM/HM teaches its move (the forget menu with four moves; TMs are
  -- used up, HMs stay), anything else has no effect.
  local function vanillaUseOn(game, battle, id, mon, list, _, picker)
    local def = E.data.items[id] or {}
    local sdef = E.data.pokemon[mon.species] or {}
    local function closePicker()
      if not picker then return end
      picker:close()
      if not battle then E.uiPush(E.modules["src.render.Transition"].whiteFlash(game, 19)) end
    end
    local function failed()
      E.nativeItems[#E.nativeItems + 1] = { kind = "failed", item = id }
      E.uiPush(E.textBox("NO EFFECT.", { onDone = closePicker }))
    end
    if battle then return failed() end
    if STONES[id] then
      for _, evo in ipairs(sdef.evolutions or {}) do
        if evo.method == "ITEM" and evo.item == id then
          consume(id, list)
          mon.species = evo.species
          E.nativeItems[#E.nativeItems + 1] = { kind = "evolved", item = id }
          closePicker()
          return
        end
      end
      return failed()
    end
    if id == "RARE_CANDY" then
      if mon.level >= 100 then return failed() end
      mon.level = mon.level + 1
      consume(id, list)
      E.nativeItems[#E.nativeItems + 1] = { kind = "leveled", item = id }
      closePicker()
      return
    end
    local machine = def.machine
    if machine then
      local ok = false
      for _, m in ipairs(sdef.tmhm or {}) do if m == machine.move then ok = true end end
      for _, mv in ipairs(mon.moves) do if mv.id == machine.move then ok = false end end
      if not ok then return failed() end
      local function taught(learned)
        if learned then
          if machine.kind ~= "HM" then consume(id, list) end
          E.nativeItems[#E.nativeItems + 1] = { kind = "learned", item = id }
        else
          E.nativeItems[#E.nativeItems + 1] = { kind = "declined", item = id }
        end
        closePicker()
      end
      if #mon.moves < 4 then
        local md = E.data.moves[machine.move]
        table.insert(mon.moves, { id = machine.move, pp = md and md.pp or 0 })
        taught(true)
      else
        E.uiPush(moveLearnMenu(mon, machine.move, taught))
      end
      return
    end
    return failed()
  end

  -- useOn (BagMenu.lua:513-516) from the item-use party menu: the picker is
  -- on top while the use runs (keepOpen), unless opts.picker says otherwise.
  -- opts: battle, list, picker (false: none)
  function E.useItem(id, mon, opts)
    opts = opts or {}
    local list = opts.list or E.bagList()
    local picker = opts.picker
    if picker == nil then
      picker = E.partyPicker()
      E.uiPush(picker)
    elseif picker == false then
      picker = nil
    end
    E.lastBagList = list
    return E.runtime.call("item.use", vanillaUseOn, E.game, opts.battle, id, mon, list, nil, picker)
  end
end

return FakeWild
