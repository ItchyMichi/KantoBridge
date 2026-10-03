-- Fake Pokemon Center, PC, Mart, saving and trainer sight for the fake
-- engine (M7). Written for Kanto Bridge from the engine behaviour
-- lib/kb_engine.lua cites: Game:writeSave / quickSaveAllowed
-- (src/core/Game.lua:1328-1369), the nurse (OverworldState:nurseHeal,
-- OC:3806-3912), the PC menu (OverworldState:openPC, OC:3568-3700, with the
-- ui.pc.items hook), the Mart (src/ui/ShopMenu.lua), trainer sight
-- (OverworldState:checkTrainerSight, OC:4372-4420); the code is our own,
-- and every text, price and map the tests install is made up. The PC
-- menu's box-row labels are the engine's English source labels, which
-- kb_engine matches.
--
--   FakeCenter.attach(E)      after FakeWild.attach
--   E.game:writeSave()        captureSave, save.writing, then the write;
--                             E.saveVeto (the save.write veto: false, no
--                             event) and E.saveFails (the write fails after
--                             the event); E.saveWrites counts the events
--   E.game:quickSaveAllowed() the F1 gate
--   E.activeSlot              what SaveData.activeSlot returns ("slot2", nil):
--                             the process cache of the active slot
--   M9, SaveData's save slots (src/core/SaveData.lua:1222-1872) over a
--   made-up registry -- E.slotReg = { list, active, names } (options.lua's
--   saveSlots[version]); E.slotFiles[id] (the slot holds a save: a write
--   into the active slot marks it; E.slotWrites[id or "flat"] counts them);
--   E.legacySave (a flat save: refreshSlotResolution + activeSlot migrate it
--   into slot1); E.cart (getCart); E.createFails; E.slotCalls (every
--   call's name) -- and the title: E.game:returnToTitle() (everything
--   popped, a TitleState screen pushed; E.titleRebuilds), E.titleState(),
--   E.startNewGame() (a fresh save table without meta.savedAt, save.created,
--   the overworld), E.continueGame() (a loaded save table, the overworld,
--   save.loaded)
--   E.musicRestores           Music.restoreMap calls
--   E.nurseHeal(npc, onDone)  welcome + HEAL/CANCEL, the machine (heal,
--                             lastHeal, healAnim of E.healFrames), the bow,
--                             the farewell
--   E.openPC(onDone)          the PC menu: the box row, the player's row,
--                             LOG OFF (rows through ui.pc.items); its
--                             sub-screens (BoxMenu, PlayerPC) are made-up
--                             menus recorded in E.subScreens
--   E.shopMenu(stock, onQuit) the Mart: BUY/SELL/QUIT, dialogue lists with
--                             money, the keepOpen quantity box, the YES/NO
--                             confirm, the "anything else?" loop
--   E.trainerSight = true     trainers sight the player in the simulated
--                             overworld (a header's range; E.sightCheck)
-- A map's NPC with nurse = true is a nurse, with mart = { items } a clerk;
-- a map's pcs = { { x, y, facing } } are PC tiles (interact kind "hidden").

local FakeCenter = {}

local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local OPPOSITE = { up = "down", down = "up", left = "right", right = "left" }

function FakeCenter.attach(E)
  local ow = E.ow
  E.saveWrites, E.musicRestores, E.subScreens = 0, 0, {}
  E.healFrames = 40

  -- ------------------------------------------------------------ the Game's save

  -- Game:writeSave (Game.lua:1328-1351): the save.write veto, captureSave,
  -- save.writing, SaveData.save's result
  function E.game.writeSave(_)
    if E.saveVeto then return false end
    local p, m = ow.player, ow.map
    if p and m then
      E.save.player.map, E.save.player.x, E.save.player.y = m.id, p.cellX, p.cellY
      E.save.player.facing = p.facing
    end
    E.save.meta = { savedAt = E.saveWrites + 1 }
    E.runtime.emit("save.writing", { save = E.save, meta = E.save.meta })
    E.saveWrites = E.saveWrites + 1
    if E.saveFails then return false end
    -- SaveData.save lands in the active slot (the flat legacy file without one)
    local key = E.activeSlot or "flat"
    E.slotWrites[key] = (E.slotWrites[key] or 0) + 1
    if E.activeSlot then E.slotFiles[E.activeSlot] = true else E.legacySave = true end
    return true
  end

  -- Game:quickSaveAllowed (Game.lua:1353-1369)
  function E.game.quickSaveAllowed(_)
    if not (ow and ow.player) then return true end
    local top = E.stack:top()
    if top ~= ow then
      for i = #E.stack.states - 1, 1, -1 do
        if E.stack.states[i] == ow then return false end
      end
      return true
    end
    if ow.transitioning then return false end
    if ow.runner and ow.runner:isRunning() then return false end
    if ow.scriptMoves and #ow.scriptMoves > 0 then return false end
    if ow.engaging or ow.emote then return false end
    if ow.player.moving then return false end
    return true
  end

  E.modules["src.core.Music"] = { restoreMap = function() E.musicRestores = E.musicRestores + 1 end }

  -- ------------------------------------------------------------ save slots (M9)

  E.slotReg = { list = {}, names = {} }
  E.slotFiles, E.slotWrites, E.slotCalls = {}, {}, {}
  E.titleRebuilds, E.newGames, E.migrations = 0, 0, 0
  local function call(name) E.slotCalls[#E.slotCalls + 1] = name end
  local function registered(id)
    for _, v in ipairs(E.slotReg.list) do
      if v == id then return true end
    end
    return false
  end
  local SaveData = {}
  -- activeSlot (:1717-1731): resolved once per process (ensureSlots
  -- :1407-1438); refreshSlotResolution asks for it again
  function SaveData.activeSlot()
    call("activeSlot")
    if E.slotsUnchecked then
      E.slotsUnchecked = false
      local reg = E.slotReg
      if #reg.list > 0 then
        E.activeSlot = reg.active or reg.list[1]
      elseif E.legacySave then
        -- tryMigrateLegacy (:1338-1373): the flat save becomes slot1
        E.legacySave = false
        E.slotFiles.slot1 = true
        reg.list, reg.active = { "slot1" }, "slot1"
        E.activeSlot = "slot1"
        E.migrations = E.migrations + 1
      else
        E.activeSlot = nil
      end
    end
    return E.activeSlot
  end
  function SaveData.refreshSlotResolution() call("refreshSlotResolution") E.slotsUnchecked = true end
  -- setActiveSlot (:1661-1686): registered if new, active in the registry
  -- and in the process cache
  function SaveData.setActiveSlot(_, id)
    call("setActiveSlot")
    if not registered(id) then E.slotReg.list[#E.slotReg.list + 1] = id end
    E.slotReg.active = id
    E.activeSlot = id
    return id
  end
  -- createSlot (:1688-1715): one past the highest number, not active
  function SaveData.createSlot()
    call("createSlot")
    if E.createFails then return nil end
    local max = 0
    for _, id in ipairs(E.slotReg.list) do
      local n = tonumber(id:match("^slot(%d+)$"))
      if n and n > max then max = n end
    end
    local id = "slot" .. (max + (E.slotGap or 1))
    E.slotReg.list[#E.slotReg.list + 1] = id
    return id
  end
  function SaveData.renameSlot(_, id, label)
    call("renameSlot")
    if not registered(id) then return false, "slot not registered" end
    E.slotReg.names[id] = label
    return true
  end
  -- deleteSlot (:1800-1856): files and row gone; an active one moves to list[1]
  function SaveData.deleteSlot(_, id)
    call("deleteSlot")
    if not registered(id) then return false, "slot not registered" end
    for i, v in ipairs(E.slotReg.list) do
      if v == id then table.remove(E.slotReg.list, i) break end
    end
    E.slotFiles[id], E.slotReg.names[id] = nil, nil
    if E.slotReg.active == id then E.slotReg.active = E.slotReg.list[1] end
    if E.activeSlot == id then E.activeSlot = E.slotReg.active end
    return true
  end
  function SaveData.getCart() return E.cart end
  function SaveData.loadOptions()
    local list, names = {}, {}
    for i, v in ipairs(E.slotReg.list) do list[i] = v end
    for k, v in pairs(E.slotReg.names) do names[k] = v end
    return { saveSlots = { [E.GameVersion.current] = { list = list, active = E.slotReg.active, names = names } } }
  end
  -- persistenceFs (:565-567) with the slot files (saves/<version>/<id>.lua)
  function SaveData.persistenceFs()
    return { getInfo = function(path)
      local version, id = path:match("^saves/([%w_]+)/(slot%d+)%.lua$")
      if version == E.GameVersion.current and E.slotFiles[id] then return { type = "file" } end
      return nil
    end }
  end
  E.SaveData = SaveData
  E.modules["src.core.SaveData"] = SaveData

  -- Game:makeTitleState's screen (Game.lua:222-240)
  function E.titleState() return { screenId = "TitleState" } end
  -- Game:returnToTitle (Game.lua:245-249)
  function E.game.returnToTitle(_)
    E.titleRebuilds = E.titleRebuilds + 1
    while #E.stack.states > 0 do E.uiPop() end
    E.uiPush(E.titleState())
  end

  local function freshSave()
    local save = {}
    for k, v in pairs(E.save) do save[k] = v end
    save.player = { map = E.save.player.map, x = E.save.player.x, y = E.save.player.y,
                    facing = E.save.player.facing, name = "NEWTEST", rival = "RIVALNEW", id = 1234 }
    save.party, save.flags, save.objectToggles = {}, {}, {}
    save.pokedex = { seen = {}, owned = {} }
    save.modData = {}
    return save
  end
  -- Game:startNewGame (Game.lua:179-198) without the intro: SaveData.newGame's
  -- skeleton has no meta.savedAt (SaveData.lua:3026-3036)
  function E.startNewGame()
    local save = freshSave()
    save.meta = { format = 1, mods = {} }
    E.save, E.game.save = save, save
    E.newGames = E.newGames + 1
    E.runtime.emit("save.created", { save = save })
    E.stack.states = { ow }
    return save
  end
  -- CONTINUE (Game:restoreSave, Game.lua:1475-1537): the active slot's save
  function E.continueGame()
    local save = freshSave()
    save.meta = { format = 1, savedAt = 100 }
    E.save, E.game.save = save, save
    E.stack.states = { ow }
    E.runtime.emit("save.loaded", { save = save, meta = save.meta })
    return save
  end

  -- Bag.order (src/inventory/Bag.lua): the acquisition order
  function E.Bag.order(save)
    save.bagOrder = save.bagOrder or {}
    return save.bagOrder
  end

  -- ------------------------------------------------------------ the nurse

  function E.nurseHeal(npc, onDone)
    local hello = "WELCOME TO THE\nTEST CENTER!"
    if not E.save.usedPokecenter then
      E.save.usedPokecenter = true
      hello = hello .. "\fHEAL YOUR\nTEAM?"
    end
    local bye = "COME AGAIN!"
    local function finish()
      if onDone then onDone() end
    end
    E.uiPush(E.textBox(hello, { choiceLabels = { "HEAL", "CANCEL" }, choice = function(yes)
      if not yes then
        E.uiPush(E.textBox(bye, { onDone = finish }))
        return
      end
      E.uiPush(E.textBox("ONE MOMENT.", { onDone = function()
        npc.facing = "left"
        ow.emote = { npc = npc, frames = 3, bubble = false, onDone = function()
          for _, mon in ipairs(E.save.party) do E.Pokemon.heal(mon) end
          local out = ow.lastOutdoor
          E.save.lastHeal = { map = ow.map.id, x = ow.player.cellX, y = ow.player.cellY,
                              outdoor = out and { id = out.id, x = out.x, y = out.y } or nil }
          E.healMachines = (E.healMachines or 0) + 1
          ow.healAnim = { balls = #E.save.party, frames = E.healFrames, onDone = function()
            E.uiPush(E.textBox("ALL BETTER!", { onDone = function()
              ow.emote = { npc = npc, frames = 20, bubble = false, onDone = function()
                E.uiPush(E.textBox(bye, { onDone = finish }))
              end }
            end }))
          end }
        end }
      end }))
    end }))
  end

  -- ------------------------------------------------------------ the PC

  function E.openPC(onDone)
    local done = onDone or function() end
    local metBill = E.save.flags.EVENT_MET_BILL
    local menu
    local function openSub(screenId)
      if E.stack:top() == menu then E.uiPop() end
      E.subScreens[#E.subScreens + 1] = screenId
      local sub = E.menuState({ { label = "TAKE", keepOpen = true }, { label = "LEAVE" } }, { screenId = screenId })
      sub.exit = function()
        menu.index = 1
        E.uiPush(menu)
      end
      E.uiPush(sub)
      return sub
    end
    local items = {
      { label = metBill and "BILL'S PC" or "SOMEONE'S PC", keepOpen = true, onSelect = function()
        E.uiPush(E.textBox("A PC OPENS.\fSTORAGE ON.", { onDone = function() openSub("BoxMenu") end }))
        done()
      end },
      { label = "TESTER's PC", keepOpen = true, onSelect = function()
        E.uiPush(E.textBox("MY PC OPENS.", { onDone = function() openSub("PlayerPC") end }))
        done()
      end },
    }
    items = E.runtime.call("ui.pc.items", function(_, list) return list end, E.game, items)
    items[#items + 1] = { label = "LOG OFF", onSelect = function() done() end }
    menu = E.menuState(items, { onCancel = function() done() end })
    menu.isMenu = true
    E.uiPush(E.textBox("TESTER TURNED\nON THE PC.", { onDone = function() E.uiPush(menu) end }))
  end

  -- ------------------------------------------------------------ the Mart

  function E.shopMenu(stock, onQuit)
    local save, data = E.save, E.data
    local menu
    local function say(text, after)
      local box
      box = E.textBox(text, { stay = { onShown = function()
        if E.stack:top() == box then E.uiPop() end
        menu.footer = text
        if after then after() end
      end } })
      E.uiPush(box)
    end
    local function backToMenu()
      say("ANYTHING\nELSE?", function() menu.index = 1 end)
    end
    local function refuse(list, text, under)
      E.uiPush(E.textBox(text, { onDone = function()
        if under and E.stack:top() == under then E.uiPop() end
        if list then list:close() end
        backToMenu()
      end }))
    end
    local function quantity(list, greet, max, unit, confirmText, apply)
      local qty
      local function drop()
        if E.stack:top() == qty then E.uiPop() end
      end
      qty = E.quantityBox({ max = max, unitPrice = unit, keepOpen = true, onDone = function(n)
        if not n then
          drop()
          list.footer = greet
          return
        end
        E.uiPush(E.textBox(confirmText(n), { choice = function(yes)
          if not yes then
            drop()
            list.footer = greet
            return
          end
          apply(n, drop, qty)
        end }))
      end })
      E.uiPush(qty)
    end

    local function buy()
      local items = {}
      for _, id in ipairs(stock) do
        local def = data.items[id]
        if def then items[#items + 1] = { value = id, label = def.name, price = "\194\165" .. tostring(def.price) } end
      end
      items[#items + 1] = { cancel = true, label = "CANCEL" }
      local greet = "PICK AWAY."
      local list
      list = E.listMenu(nil, items, { dialogue = true, money = function() return save.money end, footer = greet,
        rows = 4, cursorRows = 3, onCancel = backToMenu,
        onChoose = function(item)
          if item.cancel then
            list:close()
            backToMenu()
            return
          end
          local def = data.items[item.value]
          quantity(list, greet, 99, def.price, function(n) return "THAT'S " .. (n * def.price) .. ".\nOK?" end,
            function(n, drop, qty)
              local cost = n * def.price
              if save.money < cost then return refuse(list, "TOO POOR.", qty) end
              E.Bag.add(save, item.value, n)
              save.money = save.money - cost
              E.uiPush(E.textBox("HERE YOU GO!", { onDone = function()
                drop()
                list.footer = greet
              end }))
            end)
        end })
      say(greet, function() E.uiPush(list) end)
    end

    local function sellItems()
      local items = {}
      for _, id in ipairs(E.Bag.order(save)) do
        local def = data.items[id]
        local keyed = def and def.keyItem
        items[#items + 1] = { value = id, label = def and def.name or id, count = (not keyed) and save.inventory[id] or nil }
      end
      items[#items + 1] = { cancel = true, label = "CANCEL" }
      return items
    end

    local function sell()
      local greet = "WHAT TO SELL?"
      if #E.Bag.order(save) == 0 then return refuse(nil, "NOTHING TO SELL.") end
      local list
      list = E.listMenu(nil, sellItems(), { dialogue = true, money = function() return save.money end,
        footer = greet, rows = 4, cursorRows = 3, onCancel = backToMenu,
        onChoose = function(item)
          if item.cancel then
            list:close()
            backToMenu()
            return
          end
          local def = data.items[item.value]
          if not def or def.keyItem then return refuse(list, "NO PRICE ON THAT.") end
          local unit = math.floor(def.price / 2)
          quantity(list, greet, save.inventory[item.value] or 1, unit,
            function(n) return "I PAY " .. (unit * n) .. "." end,
            function(n, drop)
              drop()
              save.money = save.money + unit * n
              E.Bag.remove(save, item.value, n)
              local left = save.inventory[item.value]
              if left then item.count = left else list:removeCurrent() end
              list.footer = greet
            end)
        end })
      say(greet, function() E.uiPush(list) end)
    end

    local function farewell()
      E.uiPush(E.textBox("BYE NOW!", { onDone = function()
        if E.stack:top() == menu then E.uiPop() end
        if onQuit then onQuit() end
      end }))
    end
    menu = E.menuState({
      { label = "BUY", keepOpen = true, onSelect = buy },
      { label = "SELL", keepOpen = true, onSelect = sell },
      { label = "QUIT", keepOpen = true, onSelect = farewell },
    }, { screenId = "ShopMenu", keepOnCancel = true, onCancel = farewell })
    menu.isMenu = true
    function menu.enter(self)
      self.hidden = true
      say("HELLO!\nCAN I HELP?", function() self.hidden = false end)
    end
    E.shopVisits = (E.shopVisits or 0) + 1
    return menu
  end

  -- ------------------------------------------------------------ the A press

  -- the nurse, the clerk and PC tiles first; anything else as fake_ui does
  local baseInteract = E.interact
  function E.interact()
    local w, p = ow, ow.player
    local d = DELTA[p.facing]
    local fx, fy = p.cellX + d[1], p.cellY + d[2]
    local function interacted(kind, target)
      E.interactions = (E.interactions or 0) + 1
      E.runtime.emit("world.interacted", { mapId = w.map.id, x = fx, y = fy, kind = kind, target = target })
    end
    for _, pc in ipairs(w.map.spec.pcs or {}) do
      if pc.x == fx and pc.y == fy and (pc.facing == nil or pc.facing == p.facing) then
        E.openPC()
        return interacted("hidden", nil)
      end
    end
    local tx, ty = fx, fy
    if w.map:isCounterCell(fx, fy) then tx, ty = fx + d[1], fy + d[2] end
    for _, n in ipairs(w.npcs) do
      if n.cellX == tx and n.cellY == ty and not n.moving and (n.def.nurse or n.def.mart) then
        n.frozen = true
        n.facing = OPPOSITE[p.facing]
        local unfreeze = function() n.frozen = false end
        if n.def.nurse then
          E.nurseHeal(n, unfreeze)
        else
          E.uiPush(E.shopMenu(n.def.mart, unfreeze))
        end
        return interacted("npc", n)
      end
    end
    return baseInteract()
  end

  -- ------------------------------------------------------------ trainer sight

  -- OverworldState:checkTrainerSight (OC:4372-4420), on cells: an
  -- undefeated trainer facing the standing player in a line within its
  -- header's range walks up and engages (fake_script's startTrainerApproach
  -- with the distance); a cancelled sighting waits until the player moves.
  function E.sightCheck()
    if not E.trainerSight then return false end
    local p = ow.player
    if p.moving or ow.engaging or E.stack:top() ~= ow then return false end
    local cancelled = ow.cancelledTrainerSight
    if cancelled and (cancelled.playerX ~= p.cellX or cancelled.playerY ~= p.cellY) then
      ow.cancelledTrainerSight, cancelled = nil, nil
    end
    for _, npc in ipairs(ow.npcs) do
      local d = npc.def
      if d.trainerClass and not npc.moving and not (cancelled and cancelled.npcId == npc.id)
          and not E.trainerDefeated(npc) then
        local header = E.data:trainerHeader(ow.map.def.label, d.index)
        local range = header and header.range or 0
        local v = DELTA[npc.facing]
        local dist
        if v[1] ~= 0 and npc.cellY == p.cellY then
          dist = (p.cellX - npc.cellX) * v[1]
        elseif v[2] ~= 0 and npc.cellX == p.cellX then
          dist = (p.cellY - npc.cellY) * v[2]
        end
        if dist and dist >= 1 and dist <= range then
          E.startTrainerApproach(npc, dist)
          return true
        end
      end
    end
    return false
  end
end

return FakeCenter
