-- Fake hidden-game UI for the fake engine: widgets that behave like the
-- engine's (text box, YES/NO, menus, lists, quantity, naming, the adapted
-- screens and a graphical one), the A press and START in the simulated
-- overworld, and the stack events. Written for Kanto Bridge from the
-- behaviour documented in refs/_research/P2_gen1_ui.md sections 2-6 and
-- P1_gen1_movement.md section 5; the code is our own and every text is
-- made up.
--
--   FakeUi.attach(E)          after FakeWorld.attach
--   E.uiPush(state) / E.uiPop()   push/pop with enter/exit and the
--                             screen.pushed / screen.popped events
--   E.textBox(text, opts)     \f pages, \n lines, \v scrolled lines;
--                             opts: auto, stay, instant, choice, onDone
--   E.choiceBox(onChoose, opts), E.menuState(items, opts),
--   E.listMenu(title, items, opts), E.quantityBox(opts),
--   E.namingScreen(opts), E.pokedexMenu(items), E.prizeCounter(prizes, opts),
--   E.townMap(opts), E.screenState(screenId), E.startMenu()
--   E.talk[npcId] = text      what an NPC says (made up)
-- Every widget reads E.game.input edges in its update, which E.tick runs
-- for the top state.

local FakeUi = {}

local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local OPPOSITE = { up = "down", down = "up", left = "right", right = "left" }

-- a small made-up letter grid in the engine's shape: letter rows, an ED
-- cell, a one-cell case-switch row
local GRID_UPPER = {
  { "A", "B", "C", "D" },
  { "<PK>", "<MN>", "\195\169", "\195\151" },  -- é ×
  { "-", "?", " ", "ED" },
  { "lower case" },
}
local GRID_LOWER = {
  { "a", "b", "c", "d" },
  { "\226\153\130", "\226\153\128", ".", "," }, -- ♂ ♀
  { "!", "/", " ", "ED" },
  { "UPPER CASE" },
}

local function pressed(input, btn) return input:wasPressed(btn) end

function FakeUi.attach(E)
  local stack = E.stack
  E.talk = {}

  function E.uiPush(state)
    stack.states[#stack.states + 1] = state
    if type(state.enter) == "function" then state:enter() end
    E.runtime.emit("screen.pushed", { state = state })
    return state
  end

  function E.uiPop()
    local state = table.remove(stack.states)
    if state and type(state.exit) == "function" then state:exit() end
    if state then E.runtime.emit("screen.popped", { state = state }) end
    return state
  end

  -- ------------------------------------------------------------ text box

  local function paginate(text)
    local pages, cont = {}, {}
    for pageText in (text .. "\f"):gmatch("(.-)\f") do
      if pageText ~= "" then
        local lines, conts, wait = {}, {}, false
        local pos = 1
        while true do
          local at = pageText:find("[\n\v]", pos)
          lines[#lines + 1] = pageText:sub(pos, (at or 0) - 1)
          conts[#conts + 1] = wait
          if not at then break end
          wait = pageText:sub(at, at) == "\v"
          pos = at + 1
        end
        pages[#pages + 1], cont[#cont + 1] = lines, conts
      end
    end
    if #pages == 0 then pages, cont = { { "" } }, { { false } } end
    pages.contBefore = cont
    return pages
  end

  function E.textBox(text, opts)
    opts = opts or {}
    local s = setmetatable({ isTextBox = true }, E.ui.TextBox)
    s.pages = paginate(text)
    s.pageIndex, s.lineIndex, s.charIndex = 1, 1, 0
    s.shown = { {} }
    s.waiting, s.done = false, false
    s.auto, s.stay, s.choice, s.instant = opts.auto, opts.stay, opts.choice, opts.instant
    s.choiceLabels, s.defaultNo = opts.choiceLabels, opts.defaultNo
    s.onDone = opts.onDone
    -- the lines in view, at most two (TextBox:beginLine keeps them so)
    local function beginLine(self)
      self.charIndex = 0
      if #self.shown >= 2 then table.remove(self.shown, 1) end
      self.shown[#self.shown + 1] = {}
    end
    if s.instant then
      s.pageIndex = #s.pages
      s.lineIndex = #s.pages[s.pageIndex]
      s.charIndex = #s.pages[s.pageIndex][s.lineIndex]
      s.shown = { {}, {} }
      s.done = true
    end
    function s.update(self)
      local input = E.game.input
      if (self.holdFrames or 0) > 0 then
        self.holdFrames = self.holdFrames - 1
        return
      end
      if self.done then
        if self.stay then
          if not self.stayShown then
            if type(self.stay) == "table" and self.stay.prompt
                and not (pressed(input, "a") or pressed(input, "b")) then
              return
            end
            self.stayShown = true
            if type(self.stay) == "table" and self.stay.onShown then self.stay.onShown(self) end
          end
          return
        end
        if self.auto then
          -- M8 wave 3: auto.sound plays once at the end (TextBox.lua:535-540);
          -- auto.wait then hands the box to the plain A/B wait (:569-572)
          if type(self.auto) == "table" and self.auto.sound and not self.autoStarted then
            self.autoStarted = true
            self.auto.sound()
          end
          if type(self.auto) == "table" and self.auto.wait then
            self.auto = nil
            return
          end
          self.autoTimer = (self.autoTimer or 0) + 1
          if self.autoTimer >= ((type(self.auto) == "table" and self.auto.delay) or 3) then
            E.uiPop()
            if self.onDone then self.onDone() end
          end
          return
        end
        if self.choice then
          if not self.choicePushed then
            self.choicePushed = true
            E.uiPush(E.choiceBox(function(yes)
              E.uiPop() -- this text box, under the choice
              self.choice(yes)
            end, { labels = self.choiceLabels, defaultNo = self.defaultNo }))
          end
          return
        end
        if pressed(input, "a") or pressed(input, "b") then
          E.uiPop()
          if self.onDone then self.onDone() end
        end
        return
      end
      if self.waiting then
        if (self.preWait or 0) > 0 then
          self.preWait = self.preWait - 1
          return
        end
        if pressed(input, "a") or pressed(input, "b") then
          self.waiting = false
          if self.contAdvance then
            self.contAdvance = false
            self.lineIndex = self.lineIndex + 1
            beginLine(self)
            self.holdFrames = 10
          else
            self.pageIndex, self.lineIndex = self.pageIndex + 1, 1
            self.shown = {}
            beginLine(self)
            self.holdFrames = 20
          end
        end
        return
      end
      -- typing, one character a frame (text speed FAST)
      local page = self.pages[self.pageIndex]
      if self.charIndex < #page[self.lineIndex] then
        self.charIndex = self.charIndex + 1
        return
      end
      if self.lineIndex < #page then
        if self.pages.contBefore[self.pageIndex][self.lineIndex + 1] then
          self.waiting, self.preWait, self.contAdvance = true, 3, true
        else
          self.lineIndex = self.lineIndex + 1
          beginLine(self)
        end
      elseif self.pageIndex < #self.pages then
        self.waiting, self.preWait, self.contAdvance = true, 3, false
      else
        self.done = true
      end
    end
    return s
  end

  -- ------------------------------------------------------------ YES/NO

  function E.choiceBox(onChoose, opts)
    opts = opts or {}
    local s = setmetatable({}, E.ui.ChoiceBox)
    s.index = opts.defaultNo and 2 or 1
    s.labels = opts.labels or { "YES", "NO" }
    function s.update(self)
      local input = E.game.input
      if self.pending ~= nil then
        self.holdFrames = self.holdFrames - 1
        if self.holdFrames <= 0 then
          local yes = self.pending
          self.pending = nil
          E.uiPop()
          onChoose(yes)
        end
        return
      end
      if pressed(input, "up") or pressed(input, "down") then
        self.index = self.index == 1 and 2 or 1
      elseif pressed(input, "a") then
        self.pending, self.holdFrames = self.index == 1, 15
      elseif pressed(input, "b") then
        self.index, self.pending, self.holdFrames = 2, false, 15
      end
    end
    return s
  end

  -- ------------------------------------------------------------ menus

  function E.menuState(items, opts)
    opts = opts or {}
    local s = setmetatable({}, E.ui.Menu)
    s.items, s.index, s.scroll = items, 1, 0
    s.maxVisible, s.title, s.screenId, s.footer = opts.maxVisible, opts.title, opts.screenId, opts.footer
    s.cancelable = opts.cancelable ~= false
    s.startCloses, s.onCancel, s.isMenu, s.noWrap = opts.startCloses, opts.onCancel, opts.isMenu, opts.noWrap
    s.keepOnCancel = opts.keepOnCancel -- B calls onCancel without popping (Menu.lua:115)
    local function clamp(self)
      if not (self.maxVisible and #self.items > self.maxVisible) then self.scroll = 0 return end
      if self.index - self.scroll > self.maxVisible then self.scroll = self.index - self.maxVisible
      elseif self.index - self.scroll < 1 then self.scroll = self.index - 1 end
    end
    function s.update(self)
      local input = E.game.input
      if self.frozen then return end
      if pressed(input, "up") then
        self.index = self.index > 1 and self.index - 1 or (self.noWrap and 1 or #self.items)
      elseif pressed(input, "down") then
        self.index = self.index < #self.items and self.index + 1 or (self.noWrap and #self.items or 1)
      elseif pressed(input, "a") then
        local item = self.items[self.index]
        if not item.keepOpen then E.uiPop() end
        if item.onSelect then item.onSelect() end
      elseif self.cancelable and (pressed(input, "b") or (self.startCloses and pressed(input, "start"))) then
        if not self.keepOnCancel then E.uiPop() end
        if self.onCancel then self.onCancel() end
      end
      clamp(self)
    end
    return s
  end

  function E.listMenu(title, items, opts)
    opts = opts or {}
    local s = setmetatable({}, E.ui.ListMenu)
    s.title, s.items, s.index, s.scroll = title, items, 1, 0
    s.rows = opts.rows or 7
    s.cursorRows, s.footer, s.dialogue, s.money = opts.cursorRows, opts.footer, opts.dialogue, opts.money
    s.screenId, s.kind, s.script, s.wrap = opts.screenId, opts.kind or title, opts.script, opts.wrap
    s.onChoose, s.onCancel = opts.onChoose, opts.onCancel
    -- ListMenu:removeCurrent / :close (src/ui/ListMenu.lua:263-271)
    function s.removeCurrent(self)
      table.remove(self.items, self.index)
      self.index = math.max(1, math.min(self.index, #self.items))
    end
    function s.close(self)
      if E.stack:top() == self then E.uiPop() end
    end
    local function sync(self)
      local maxRow = self.cursorRows or self.rows
      if self.index - self.scroll > maxRow then self.scroll = self.index - maxRow end
      if self.index - self.scroll < 1 then self.scroll = self.index - 1 end
    end
    function s.update(self)
      if self.script then return end
      local input = E.game.input
      if pressed(input, "up") then
        self.index = math.max(1, self.index - 1)
      elseif pressed(input, "down") then
        self.index = math.min(#self.items, self.index + 1)
      elseif pressed(input, "b") then
        E.uiPop()
        if self.onCancel then self.onCancel() end
        return
      elseif pressed(input, "a") then
        if self.onChoose then self.onChoose(self.items[self.index], self) end
        return
      end
      sync(self)
    end
    return s
  end

  function E.quantityBox(opts)
    local s = setmetatable({}, E.ui.QuantityBox)
    s.max = math.max(1, opts.max or 99)
    s.qty = math.min(opts.start or 1, s.max)
    s.unitPrice, s.onDone = opts.unitPrice, opts.onDone
    s.keepOpen = opts.keepOpen -- the Mart's: its caller pops it (QuantityBox.lua:41-46)
    function s.update(self)
      local input = E.game.input
      if pressed(input, "up") then
        self.qty = self.qty < self.max and self.qty + 1 or 1
      elseif pressed(input, "down") then
        self.qty = self.qty > 1 and self.qty - 1 or self.max
      elseif pressed(input, "a") then
        if not self.keepOpen then E.uiPop() end
        if self.onDone then self.onDone(self.qty) end
      elseif pressed(input, "b") then
        if not self.keepOpen then E.uiPop() end
        if self.onDone then self.onDone(nil) end
      end
    end
    return s
  end

  -- ------------------------------------------------------------ naming

  function E.namingScreen(opts)
    opts = opts or {}
    local s = setmetatable({ isMenu = true }, E.ui.NamingScreen)
    s.title = opts.title or "NAME?"
    s.presets, s.maxLen, s.default, s.onDone, s.mon = opts.presets, opts.maxLen or 7, opts.default, opts.onDone, opts.mon
    s.glyphs, s.row, s.col, s.lower = {}, 1, 1, false
    s.gridCalls = 0
    function s.grid(self)
      self.gridCalls = self.gridCalls + 1
      return self.lower and GRID_LOWER or GRID_UPPER
    end
    function s.enter(self)
      if self.presets and #self.presets > 0 then
        self.choosing = true
        local items = { { label = "NEW NAME", onSelect = function() self.choosing = nil end } }
        for _, preset in ipairs(self.presets) do
          items[#items + 1] = { label = preset, onSelect = function()
            E.uiPop() -- the naming screen
            if self.onDone then self.onDone(preset, false) end
          end }
        end
        E.uiPush(E.menuState(items, { cancelable = false, title = "NAME" }))
      end
    end
    function s.confirm(self)
      local name = table.concat(self.glyphs)
      if name == "" then name = (self.presets and self.presets[1]) or self.default or "" end
      E.uiPop()
      if self.onDone then self.onDone(name, true) end
    end
    function s.update(self)
      local input = E.game.input
      local grid = self:grid()
      if pressed(input, "start") then return self:confirm() end
      if pressed(input, "select") then self.lower = not self.lower return end
      if pressed(input, "b") then table.remove(self.glyphs) return end
      if pressed(input, "a") then
        local cell = grid[self.row][self.col]
        if cell == "ED" then return self:confirm() end
        if #grid[self.row] == 1 then self.lower = not self.lower return end
        if #self.glyphs < self.maxLen then self.glyphs[#self.glyphs + 1] = cell end
      end
    end
    return s
  end

  -- ------------------------------------------------------------ adapted screens

  local Pokedex = { isMenu = true }
  Pokedex.__index = Pokedex
  function Pokedex:rows() return math.min(7, #self.items) end
  function Pokedex:syncScroll()
    local rows = self:rows()
    if self.index - self.scroll > rows then self.scroll = self.index - rows end
    if self.index - self.scroll < 1 then self.scroll = self.index - 1 end
  end
  function Pokedex:update()
    local input = E.game.input
    if pressed(input, "up") then self.index = math.max(1, self.index - 1)
    elseif pressed(input, "down") then self.index = math.min(#self.items, self.index + 1)
    elseif pressed(input, "b") then E.uiPop() return
    elseif pressed(input, "a") then
      if self.onChoose then self.onChoose(self.items[self.index], self) end
      return
    end
    self:syncScroll()
  end
  function E.pokedexMenu(items, opts)
    opts = opts or {}
    return setmetatable({ screenId = "PokedexMenu", items = items, index = 1, scroll = 0,
                          footer = opts.footer, onChoose = opts.onChoose }, Pokedex)
  end

  local Prize = { isMenu = true }
  Prize.__index = Prize
  E.modules["src.ui.PrizeCounter"] = Prize
  E.modules["src.ui.PartyMenu"] = { isMenu = true }
  E.modules["src.ui.PokedexMenu"] = Pokedex
  function Prize:update()
    local input = E.game.input
    if pressed(input, "up") then
      if self.index > 1 then self.index = self.index - 1 end
    elseif pressed(input, "down") then
      if self.index < 4 then self.index = self.index + 1 end
    elseif pressed(input, "a") or pressed(input, "b") then
      if pressed(input, "b") or self.index >= 4 then
        E.uiPop()
        if self.onCancel then self.onCancel() end
      elseif self.onPick then
        self.onPick(self.prizes[self.index], self.index)
      end
    end
  end
  function E.prizeCounter(prizes, opts)
    opts = opts or {}
    return setmetatable({ prizes = prizes, index = 1, onPick = opts.onPick, onCancel = opts.onCancel }, Prize)
  end

  local Town = { isMenu = true }
  Town.__index = Town
  function Town:update()
    local input = E.game.input
    if pressed(input, "b") then E.uiPop() return end
    if not self.fly then return end
    if pressed(input, "a") then
      local id = self.flyMapIds[self.sel]
      E.uiPop()
      if self.onFly then self.onFly(id) end
    elseif pressed(input, "up") then
      self.sel = self.sel % #self.locs + 1
    elseif pressed(input, "down") then
      self.sel = (self.sel - 2) % #self.locs + 1
    end
  end
  function E.townMap(opts)
    opts = opts or {}
    local s = setmetatable({ screenId = "TownMap", sel = 1, fly = opts.fly, locs = {}, flyMapIds = {},
                             onFly = opts.onFly }, Town)
    for i, town in ipairs(opts.towns or {}) do
      s.locs[i] = { name = town.name }
      s.flyMapIds[i] = town.map
    end
    return s
  end

  -- a graphical screen with no list: A or B closes it
  function E.screenState(screenId, opts)
    opts = opts or {}
    local s = { screenId = screenId, isMenu = opts.isMenu, presses = {} }
    function s.update(self)
      local input = E.game.input
      for _, btn in ipairs({ "up", "down", "left", "right", "select", "start" }) do
        if pressed(input, btn) then self.presses[#self.presses + 1] = btn end
      end
      if not opts.sticky and (pressed(input, "a") or pressed(input, "b")) then
        E.uiPop()
        if opts.onClose then opts.onClose() end
      end
    end
    return s
  end

  -- a made-up start menu: a bag list, a graphical card, QUIT with a YES/NO
  E.startMenuIndex = 1
  E.answers = {}
  function E.startMenu()
    local menu
    local function reopen() E.uiPush(E.startMenu()) end
    local items = {
      { label = "ITEM", keepOpen = true, onSelect = function()
        E.uiPush(E.listMenu("ITEMS", { { label = "TEST BALL", count = 3 }, { label = "CANCEL" } }, {
          screenId = "BagMenu", kind = "bag", rows = 4, cursorRows = 3,
          onCancel = function() if E.stack:top() == menu then E.uiPop() end end,
        }))
      end },
      { label = "TESTER", onSelect = function() E.uiPush(E.screenState("TrainerCard", { onClose = reopen })) end },
      { label = "QUIT", onSelect = function()
        E.uiPush(E.textBox("QUIT\nTHE TEST?", { defaultNo = true, choice = function(yes)
          E.answers[#E.answers + 1] = yes
        end }))
      end },
    }
    -- M7: a SAVE row before QUIT (opt-in), StartMenu.lua:63-139's shape: a
    -- panel with no input, a YES/NO, "saving" (auto) whose end writes the
    -- save, then "saved" (auto); the panel and the menu close after it
    if E.startMenuSave then
      table.insert(items, #items, { label = "SAVE", keepOpen = true, onSelect = function()
        local panel = { delay = 0 }
        local function closePanel()
          if E.stack:top() == panel then E.uiPop() end
          if E.stack:top() == menu then E.uiPop() end
        end
        function panel.update(self)
          self.delay = self.delay + 1
          if self.delay ~= 30 then return end
          E.uiPush(E.textBox("SAVE THE\nTEST?", { choice = function(yes)
            if not yes then closePanel() return end
            E.uiPush(E.textBox("SAVING...", { auto = { delay = 12 }, onDone = function()
              E.game:writeSave()
              E.uiPush(E.textBox("SAVED!", { auto = { delay = 6 }, onDone = closePanel }))
            end }))
          end }))
        end
        E.uiPush(panel)
      end })
    end
    menu = E.menuState(items, { screenId = "StartMenu", startCloses = true, maxVisible = 8 })
    menu.index = math.min(E.startMenuIndex, #items)
    return menu
  end

  -- ------------------------------------------------------------ the A press

  local function npcAt(x, y)
    for _, n in ipairs(E.ow.npcs or {}) do
      if (n.cellX == x and n.cellY == y) or (n.targetX == x and n.targetY == y) then return n end
    end
    return nil
  end

  -- OverworldState:talkTo (OC:3440-3560), shortened: the NPC freezes and
  -- turns to the player; an undefeated trainer battles, anything else talks
  -- (fake_content.lua adds statics and talk scripts, M8)
  function E.talkTo(npc)
    local p = E.ow.player
    npc.frozen = true
    npc.facing = OPPOSITE[p.facing]
    local unfreeze = function() npc.frozen = false end
    if npc.def and npc.def.trainerClass and E.engageTrainer and not E.trainerDefeated(npc) then
      -- an undefeated trainer battles (OC:3516-3519; fake_script.lua)
      E.engageTrainer(npc, unfreeze)
    else
      E.uiPush(E.textBox(E.talk[npc.id] or "HELLO!\fGOOD LUCK!", { onDone = unfreeze }))
    end
  end

  -- OverworldState:interact's order: an NPC (across a counter too), a
  -- sign, otherwise nothing; world.interacted after the handler
  function E.interact()
    local w, p = E.ow, E.ow.player
    local d = DELTA[p.facing]
    local fx, fy = p.cellX + d[1], p.cellY + d[2]
    local npc = npcAt(fx, fy)
    if not npc and w.map.isCounterCell and w.map:isCounterCell(fx, fy) then
      npc = npcAt(fx + d[1], fy + d[2])
    end
    local function interacted(kind, target)
      E.interactions = (E.interactions or 0) + 1
      E.runtime.emit("world.interacted", { mapId = w.map.id, x = fx, y = fy, kind = kind, target = target })
    end
    if npc then
      if not npc.moving then
        -- the world.talk hook around talkTo (OC:2561-2566; M8)
        E.runtime.call("world.talk", function(_, target) return E.talkTo(target) end, w, npc)
      end
      return interacted("npc", npc)
    end
    for _, sign in ipairs(w.map.spec.signs or {}) do
      if sign.x == fx and sign.y == fy then
        E.uiPush(E.textBox(sign.text or "A TEST SIGN."))
        return interacted("sign", sign)
      end
    end
    return interacted("none")
  end

  -- the overworld's standing poll: A interacts, START opens the menu
  -- (OC:1712-1721)
  function E.overworldButtons(input)
    if input:wasPressed("a") then
      E.interact()
      return true
    end
    if input:wasPressed("start") then
      E.uiPush(E.startMenu())
      return true
    end
    return false
  end
end

return FakeUi
