-- Fake field moves, live map blocks and forced movement for the fake engine
-- (M8). Written for Kanto Bridge from the engine behaviour lib/kb_engine.lua
-- cites (OC = src/world/OverworldController.lua) and
-- refs/_research/P1_gen1_movement.md sections 7-9; the code is our own, and
-- every map, block, item and text the tests install is made up.
--
--   FakeField.attach(E)     after FakeWorld and FakeUi (FakeScript and
--                           FakeWild may follow; fishing uses E.goFishing)
--
-- OverworldState methods on E.ow, the ones kb_engine.fieldAction calls:
--   partyKnows(move)        the badge of constants.hmBadges in the bag, then
--                           a party member knowing the move (OC:2214-2237)
--   useCutFieldMove()       "ok" facing a T (tree) or g (grass) cell whose
--                           block has a field.cutTreeSwaps row, else
--                           "nothing" / "no_badge" (OC:3415-3438)
--   tryCut(fx, fy)          the text box, then the cut: ow.cutBlocks gets
--                           { bx, by, block } and the block is swapped
--                           (OC:3292-3347)
--   useSurfFieldMove()      "ok" facing water ('w'), "dismount" facing land
--                           while surfing, "no_place", "no_water",
--                           "forced_bike", "no_badge" (OC:3352-3404)
--   trySurf / stopSurfing   the text box, then p.surfing and one scripted
--                           step forward (OC:3237-3280)
--   facingIsShoreOrWater()  the cell in front is water
--   useStrengthFieldMove()  strengthActive and two text boxes (OC:1074-1095)
--   useFlashFieldMove()     flashLit while dark, ow.dark off (OC:1057-1072)
--   beginTeleportOut()      the 135-frame spin, then the heal point's warp
--                           (save.lastHeal = { map, x, y }), nothing without
--                           one (OC:2470-2499, :5387-5404)
--   flyTo(mapId)            flyFade, then the warp to field.flyWarps[mapId]
--   useBicycle(), bikeAllowed(mapId)   (OC:1021-1052)
--   useFishingRod(rod)      E.goFishing (fake_wild.lua) facing water
--   replaceBlock(bx, by, b) Map:setBlock on the current map (OC:5644-5649)
-- Map loads (E.onMapLoad, in setMap's order, OC:479-515): Cut regrowth from
-- ow.cutBlocks, the card-key stamps (field.cardKeyDoors.closedDoors),
-- darkness (field.darkMaps and save.flashLit), the slope flag (a map
-- spec's `slope`). E.mapScripts[map] = { onEnter = fn(E, ow), onStep =
-- fn(E, ow, x, y) } stand for the hand-ported map scripts.
-- Steps (E.stepHooks, onStepComplete's order, OC:4689-4710): the map's
-- onStep, spinners (field.spinners: runSpinnerMoves sets p.spinning, 48
-- frames a cell, then onStepComplete again), Seafoam currents
-- (field.seafoam: currents, entryCurrent, forcedExit; noSpin).
-- Modules: src.world.FieldDefaults (constant, fieldValue, field),
-- src.ui.Screens (push: TownMap's fly list), and on the existing fakes
-- OverworldController.objectVisible (OC:227-243) and Map.isFlyTown.

local FakeField = {}

local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local OPPOSITE = { up = "down", down = "up", left = "right", right = "left" }
local HM_BADGES = { CUT = { badge = "CASCADEBADGE" }, SURF = { badge = "SOULBADGE" },
                    STRENGTH = { badge = "RAINBOWBADGE" }, FLY = { badge = "THUNDERBADGE" },
                    FLASH = { badge = "BOULDERBADGE" } }

function FakeField.attach(E)
  local ow = E.ow
  E.mapScripts = {}
  E.fieldCalls = {}

  local function record(name) E.fieldCalls[#E.fieldCalls + 1] = name end
  local function field() return E.data.field or {} end
  local function save() return E.save end
  local function text(s, onDone) return E.uiPush(E.textBox(s, { onDone = onDone })) end

  -- ------------------------------------------------------------ modules

  -- FieldDefaults (src/world/FieldDefaults.lua:230-259): data first, then
  -- the engine's own seeds (only hmBadges here)
  local FieldDefaults = { FIELD = {}, CONSTANTS = { hmBadges = HM_BADGES } }
  function FieldDefaults.field(data, key)
    local f = data and data.field
    if f and f[key] ~= nil then return f[key] end
    return FieldDefaults.FIELD[key]
  end
  function FieldDefaults.fieldValue(data, key, ...)
    local node = data and data.field and data.field[key]
    for _, k in ipairs({ ... }) do node = type(node) == "table" and node[k] or nil end
    return node
  end
  function FieldDefaults.constant(data, key)
    local c = data and data.constants
    if c and c[key] ~= nil then return c[key] end
    return FieldDefaults.CONSTANTS[key]
  end
  E.modules["src.world.FieldDefaults"] = FieldDefaults

  -- Bag.remove (src/inventory/Bag.lua:166-181), unless fake_wild.lua's
  -- fuller Bag follows
  E.modules["src.inventory.Bag"] = E.modules["src.inventory.Bag"] or {
    remove = function(s, id, qty)
      local inv = s.inventory
      inv[id] = (inv[id] or 0) - (qty or 1)
      if inv[id] <= 0 then inv[id] = nil end
    end,
  }

  -- Screens.push (src/ui/Screens.lua): only the Town Map's fly list here
  E.screenPushes = {}
  E.modules["src.ui.Screens"] = {
    push = function(_, id, opts)
      E.screenPushes[#E.screenPushes + 1] = id
      if id ~= "TownMap" then error("fake Screens: no screen " .. tostring(id)) end
      local towns = {}
      for _, mapId in ipairs(field().flyOrder or {}) do
        if (save().visited or {})[mapId] and (field().flyWarps or {})[mapId] then
          towns[#towns + 1] = { name = "TOWN " .. #towns + 1, map = mapId }
        end
      end
      return E.uiPush(E.townMap({ fly = opts.fly and #towns > 0, onFly = opts.onFly, towns = towns }))
    end,
  }

  -- objectVisible (OC:227-243), exposed as OverworldState.objectVisible
  local OC = E.modules["src.world.OverworldController"]
  function OC.objectVisible(s, mapId, obj)
    local toggles = s.objectToggles and s.objectToggles[mapId] or {}
    local visible = not obj.hidden
    if obj.name and toggles[obj.name] ~= nil then visible = toggles[obj.name] end
    if obj.item and s.itemsTaken and s.itemsTaken[mapId .. "_obj_" .. obj.index] then visible = false end
    if obj.pokemon and s.defeatedTrainers[mapId .. "_obj_" .. obj.index] then visible = false end
    return visible
  end
  E.save.defeatedTrainers = E.save.defeatedTrainers or {}

  -- Map.isFlyTown: an OVERWORLD map (made-up rule; the engine's is
  -- BuildFlyLocationsList's walk)
  function E.Map.isFlyTown(def) return def.tileset == "OVERWORLD" end

  -- ------------------------------------------------------------ helpers

  local function facing()
    local p = ow.player
    local d = DELTA[p.facing]
    return p.cellX + d[1], p.cellY + d[2]
  end

  local function swapOf(block)
    for _, sw in ipairs(field().cutTreeSwaps or {}) do
      if sw.before == block then return sw end
    end
    return nil
  end

  -- partyKnowsVanilla (OC:2214-2228)
  function ow.partyKnows(_, move)
    local gate = FieldDefaults.constant(E.data, "hmBadges")[move]
    if gate and not (save().inventory or {})[gate.badge] then return nil end
    for _, mon in ipairs(save().party or {}) do
      for _, mv in ipairs(mon.moves or {}) do
        if mv.id == move then return mon end
      end
    end
    return nil
  end

  -- ------------------------------------------------------------ Cut

  local function cuttable(fx, fy)
    local m = ow.map
    if not m:inBounds(fx, fy) then return nil end
    local c = m:char(fx, fy)
    if c ~= "T" and c ~= "g" then return nil end
    local bx, by = math.floor(fx / 2), math.floor(fy / 2)
    local block = m:blockAt(bx, by)
    local sw = block and swapOf(block)
    if not sw then return nil end
    return bx, by, block, sw
  end

  function ow.useCutFieldMove(self)
    record("useCutFieldMove")
    if not self:partyKnows("CUT") then return "no_badge" end
    if not cuttable(facing()) then return "nothing" end
    return "ok"
  end

  function ow.tryCut(self, fx, fy)
    record("tryCut")
    local bx, by, block, sw = cuttable(fx, fy)
    if not bx or not self:partyKnows("CUT") then return false end
    text("CUT!", function()
      self.cutBlocks = self.cutBlocks or {}
      local id = self.map.id
      self.cutBlocks[id] = self.cutBlocks[id] or {}
      table.insert(self.cutBlocks[id], { bx = bx, by = by, block = block })
      self.map:setBlock(bx, by, sw.after)
      self.cutAnim = { frames = 8 }
    end)
    return true
  end

  -- ------------------------------------------------------------ Surf

  function ow.facingIsShoreOrWater(self)
    local fx, fy = facing()
    return self.map:isWaterCell(fx, fy)
  end

  function ow.useSurfFieldMove(self)
    record("useSurfFieldMove")
    if not self:partyKnows("SURF") then return "no_badge" end
    if save().forcedBike then return "forced_bike" end
    local p = self.player
    local fx, fy = facing()
    if p.surfing then
      if self.map:isWalkableCell(fx, fy) then return "dismount" end
      return "no_place"
    end
    if not self.map:isWaterCell(fx, fy) then return "no_water" end
    return "ok"
  end

  function ow.trySurf(self)
    record("trySurf")
    if not self:partyKnows("SURF") then return end
    save().onBike = false
    text("SURFING!", function()
      self.player.surfing = true
      E.scriptMove(self.player, self.player.facing, 1)
    end)
  end

  function ow.stopSurfing(self)
    record("stopSurfing")
    self.player.surfing = false
    E.scriptMove(self.player, self.player.facing, 1)
  end

  -- ------------------------------------------------------------ Strength, Flash

  function ow.useStrengthFieldMove(self)
    record("useStrengthFieldMove")
    if not self:partyKnows("STRENGTH") then return false end
    self.strengthActive = true
    text("STRENGTH!", function() text("BOULDERS!") end)
    return true
  end

  function ow.useFlashFieldMove(self)
    record("useFlashFieldMove")
    local wasDark = self.dark
    if wasDark then save().flashLit = true end
    text("FLASH!", function() if wasDark then self.dark = false end end)
    return true
  end

  -- ------------------------------------------------------------ Dig, Teleport, the Escape Rope, Fly

  function ow.beginTeleportOut(self)
    record("beginTeleportOut")
    local heal = save().lastHeal
    if not heal then return end
    local p = self.player
    p.inputLocked, p.spinning, p.spinFrames = true, true, 135
    self.teleportOut = { frames = 135, go = function()
      p.spinFrames = nil
      E.startWarpTo(heal.map, heal.x, heal.y, "down")
    end }
  end

  function ow.flyTo(self, mapId)
    record("flyTo")
    local spot = (field().flyWarps or {})[mapId]
    if not spot then return end
    save().onBike = false
    self.player.surfing = false
    self.flyFade = 10
    self.flyDest = { map = mapId, x = spot.x, y = spot.y }
    self.player.inputLocked = true
  end

  -- ------------------------------------------------------------ the bicycle, the rods

  function ow.bikeAllowed(self, mapId)
    local br = field().bikeRiding
    if not br then return self.map.def.tileset == "OVERWORLD" end
    for _, m in ipairs(br.maps or {}) do if m == mapId then return true end end
    for _, t in ipairs(br.tilesets or {}) do if t == self.map.def.tileset then return true end end
    return false
  end

  function ow.useBicycle(self)
    record("useBicycle")
    local s = save()
    if s.onBike then
      if s.forcedBike then return false end
      s.onBike = false
      text("BIKE OFF.")
    elseif self:bikeAllowed(self.map.id) and not self.player.surfing then
      s.onBike = true
      text("BIKE ON!")
    else
      return false
    end
    return true
  end

  function ow.useFishingRod(self, rod)
    record("useFishingRod")
    if self.player.surfing or not self:facingIsShoreOrWater() then return false end
    E.fishedWith = rod
    if E.goFishing then E.goFishing(rod) end
    return true
  end

  -- ------------------------------------------------------------ blocks

  function ow.replaceBlock(self, bx, by, block)
    self.map:setBlock(bx, by, block)
  end

  -- setMap's part (OC:479-515): regrowth, the stamps, the darkness, the slope
  function E.onMapLoad(mapId)
    local m = ow.map
    if ow.cutBlocks and ow.cutBlocks[mapId] then
      for _, c in ipairs(ow.cutBlocks[mapId]) do m:setBlock(c.bx, c.by, c.block) end
      ow.cutBlocks[mapId] = nil
    end
    local ck = field().cardKeyDoors
    for _, door in ipairs(ck and ck.closedDoors and ck.closedDoors[mapId] or {}) do
      local open = save().flags[door.event]
      local want = open and door.open or door.block
      if m:blockAt(door.bx, door.by) ~= want then m:setBlock(door.bx, door.by, want) end
    end
    local dark = false
    for _, id in ipairs((field().darkMaps or {}).maps or {}) do
      if id == mapId then dark = true end
    end
    if dark then
      ow.dark = not save().flashLit
    else
      save().flashLit = nil
      ow.dark = false
    end
    if m.spec.slope ~= nil and ow.player then ow.player.slopeMap = m.spec.slope end
  end

  function E.onMapEnter(mapId)
    local hooks = E.mapScripts[mapId]
    if hooks and hooks.onEnter then hooks.onEnter(E, ow) end
  end

  -- a card key opening a door (tryCardKeyDoor's openDoor, OC:2937-2948)
  function E.openCardKeyDoor(bx, by, openBlock)
    ow:replaceBlock(bx, by, openBlock)
    local ck = field().cardKeyDoors
    for _, door in ipairs(ck and ck.closedDoors and ck.closedDoors[ow.map.id] or {}) do
      if door.bx == bx and door.by == by then save().flags[door.event] = true end
    end
  end

  -- ------------------------------------------------------------ spinners and currents

  local function runSpinnerMoves(moves, i, noSpin)
    local p = ow.player
    local mv = moves[i]
    if not mv then
      p.spinning = false
      ow.spinnerSliding = nil
      E.onStepComplete()
      return
    end
    if not noSpin then
      p.spinning = true
      ow.spinnerSliding = true
    end
    E.scriptMove(p, mv.dir, mv.count or 1, function() runSpinnerMoves(moves, i + 1, noSpin) end)
  end
  E.runSpinnerMoves = runSpinnerMoves

  local function allSet(events)
    for _, e in ipairs(events or {}) do
      if not save().flags[e] then return false end
    end
    return true
  end

  local function seafoamCurrent()
    local sf = (field().seafoam or {})[ow.map.id]
    local p = ow.player
    if not sf then return false end
    if sf.forcedExit and p.surfing and not allSet(sf.forcedExit.activeUntilEvents) then
      for _, c in ipairs(sf.forcedExit.coords or {}) do
        if p.cellX == c.x and p.cellY == c.y then
          E.scriptMove(p, "up", 1)
          return true
        end
      end
    end
    if not p.surfing then return false end
    local active = {}
    if not allSet(sf.currentsDisabledByEvents) then
      for _, c in ipairs(sf.currents or {}) do active[#active + 1] = c end
    end
    if sf.entryCurrent then active[#active + 1] = sf.entryCurrent end
    for _, c in ipairs(active) do
      if p.cellX == c.x and p.cellY == c.y then
        runSpinnerMoves(c.moves, 1, true)
        return true
      end
    end
    return false
  end

  function E.stepHooks()
    local p = ow.player
    local hooks = E.mapScripts[ow.map.id]
    if hooks and hooks.onStep and hooks.onStep(E, ow, p.cellX, p.cellY) then return true end
    for _, sp in ipairs((field().spinners or {})[ow.map.id] or {}) do
      if sp.x == p.cellX and sp.y == p.cellY then
        runSpinnerMoves(sp.moves, 1)
        return true
      end
    end
    return seafoamCurrent()
  end

  E.OPPOSITE = OPPOSITE
end

return FakeField
