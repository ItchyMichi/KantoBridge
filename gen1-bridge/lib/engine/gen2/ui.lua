-- gen2 UI (GEN2_BRIDGE_PLAN.md §5.5, phase G2B): the core's UI stack
-- (observe/kb_ui.lua, act/kb_uiact.lua) read from Gen 2's state stack.
--
-- Gen 2 runs the shared StateStack (src/core/Game2.lua:124-134), and its field
-- text and yes/no are the shared Gen 1 classes, the TextBox and the ChoiceBox
-- (src/world/gen2/World.lua:4319-4364, :7992-8045), so their readers are
-- gen1.lua's (lib/engine/gen1.lua:904-973, :1106-1141, :1203-1209; the v0.3.85
-- arrow rule included). Every other Gen 2 screen is pushed through the Screens
-- registry and stamped `screenId = "Gen2<Name>"` (src/ui/Screens.lua:40-96, :174).
--
-- Classes (the core's, kb_ui.lua:144-148):
--   shared TextBox (script text, signs, Oak's lines, phone calls)  TextBox
--   shared ChoiceBox (yes/no; B answers NO)                         ChoiceBox
--   Gen2GenderSelect (Crystal, before Oak)                          ChoiceBox (B does nothing:
--                                                                   NO is the cursor and A)
--   Gen2ScriptMenu (loadmenu + verticalmenu / _2dmenu)              ListMenu (1-based; B = 0)
--   Gen2StartMenu                                                   Menu "StartMenu" without
--                                                                   QUIT and MODS
--   Gen2NamePick + its Gen2NamingScreen                             one NamingScreen: the pick's
--                                                                   names are the presets (a Menu
--                                                                   on top while it is up), NEW
--                                                                   NAME opens the keyboard
--   Gen2NamingScreen alone (nicknames, the rival, a box)            NamingScreen
--   Gen2InitClock                                                   answered here with the host's
--                                                                   time (J5); opens nothing
--   Elm's starter picture (world.waitButton, no state)              PicBox -> the mirror
--   the boot screens (copyright, intros, title, main menu)          Screen "TitleState" -> mirror
--   Gen2OakSpeech                                                   Screen "OakSpeech" -> mirror
--   Gen2BattleState (a native battle)                               BattleState -> mirror
--   any other Gen2<Name> screen                                     Screen <screenId> -> mirror
--   fades, the blank screen, the battle wipe, the caller box,
--   the park's hold                                                 Busy (nothing opens)
--
-- Keys are the state tables themselves (a small integer each, weak keys);
-- the name pick stands for itself and the keyboard it opens, so the naming
-- UI the client answers stays one UI from the presets to END. Text goes
-- through as the engine has it (the core's Ui.markup cleans it); nothing here
-- logs text.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Ui = {}

-- Start menu rows the client never picks: QUIT returns to the title after its
-- own question (src/ui/gen2/StartMenu.lua:92, :247-280), MODS
-- (src/ui/gen2/StartMenu.lua:84) is the player's own business.
Ui.HIDDEN_START = { quit = true, mods = true }
-- The boot chain (src/core/Game2.lua:360-477): one title mirror, as on Gen 1 and 3.
Ui.BOOT_SCREENS = { Gen2CopyrightSplash = true, Gen2GameFreakPresents = true, Gen2CrystalSplash = true,
                    Gen2GoldSilverIntro = true, Gen2CrystalIntro = true, Gen2TitleState = true, Gen2MainMenu = true }
-- States that take no input: the menus' fades, the blank frame, the battle's
-- wipe (an intro's own fades run inside its screen, src/ui/gen2/IntroFade.lua).
Ui.BUSY_SCREENS = { Gen2MenuFade = true, Gen2BlankScreen = true, Gen2BattleTransition = true }
-- The keyboard's END target: the bottom row, column 6 (src/ui/gen2/NamingScreen.lua:357-361).
Ui.END_COL = 6

local function utf8Len(s)
  local _, cont = s:gsub("[\128-\191]", "")
  return #s - cont
end

function Ui.install(b)
  local def = b.def

  -- ---- keys

  local stateKeys = setmetatable({}, { __mode = "k" })
  local lastKey = 0
  local function keyOf(obj)
    local k = stateKeys[obj]
    if k == nil then
      lastKey = lastKey + 1
      k = lastKey
      stateKeys[obj] = k
    end
    return k
  end
  -- the preset menu of a name pick: an object of our own per pick
  local presetProxies = setmetatable({}, { __mode = "k" })
  local function presetProxy(pick)
    local p = presetProxies[pick]
    if not p then
      p = { pick = pick }
      presetProxies[pick] = p
    end
    return p
  end
  local current = {}   -- key -> entry, from the last uiStack()

  local function int(v, default)
    v = tonumber(v)
    if v == nil or v ~= v then return default end
    return math.floor(v)
  end
  local function count(t) return type(t) == "table" and #t or 0 end

  -- The engine's string catalog (src/core/Strings.lua:211-215: Strings(text)
  -- is the translation, the text itself without one); labels pass through it
  -- when drawn.
  local function S(text)
    if type(text) ~= "string" then return nil end
    local Strings = b.mod("src.core.Strings")
    if Strings then
      local ok, out = pcall(Strings, text)
      if ok and type(out) == "string" then return out end
    end
    return text
  end

  -- ---- classes

  local function screenIdOf(s)
    local sid = s.screenId
    return type(sid) == "string" and sid:find("^[%w_]+$") and #sid <= 64 and sid or nil
  end

  local function classOf(s)
    if type(s) ~= "table" then return "Busy" end
    if s.kantoBridgeRegionHold then return "Busy" end
    local sid = screenIdOf(s)
    if sid == "ManagerState" then return "Manager", sid end
    local mt = getmetatable(s)
    local TextBox, ChoiceBox = b.mod("src.render.TextBox"), b.mod("src.ui.ChoiceBox")
    if s.isTextBox or (TextBox and mt == TextBox) then return "TextBox", nil end
    if ChoiceBox and mt == ChoiceBox then return "ChoiceBox", nil end
    if not sid then
      -- the phone's caller box is pushed raw (src/script/gen2/CallAsm.lua:257-269)
      local CallerBox = b.ui2("CallerBox")
      if CallerBox and mt == CallerBox then return "Busy" end
      if s.isMenu then return "Screen", nil end
      return "Busy"
    end
    if Ui.BUSY_SCREENS[sid] or sid == "Gen2InitClock" then return "Busy" end
    if Ui.BOOT_SCREENS[sid] then return "Screen", "TitleState" end
    if sid == "Gen2StartMenu" then return "Menu", "StartMenu" end
    if sid == "Gen2ScriptMenu" then return "ListMenu", "ListMenu" end
    if sid == "Gen2GenderSelect" then return "ChoiceBox", "GenderSelect" end
    if sid == "Gen2NamePick" or sid == "Gen2NamingScreen" then return "NamingScreen", "NamingScreen" end
    if sid == "Gen2BattleState" then return "BattleState", "BattleState" end
    if sid == "Gen2OakSpeech" then return "Screen", "OakSpeech" end
    return "Screen", sid
  end

  -- ---- the clock screen, answered with the host's time (J5)

  -- Gen2InitClock: the clock picker, or Mom's day wheel for `SetDayOfWeek`
  -- (src/world/gen2/World.lua:3184-3202). Its own deterministic path,
  -- autoConfirm (src/ui/gen2/InitClock.lua:136-148, :333-341), walks every
  -- step taking the defaults and YES. Its finish (src/ui/gen2/InitClock.lua:276-289)
  -- is handed the host's time at the moment it stores it
  -- (src/core/gen2/Clock.lua:74-87), and the offset setTime / setWeekday store
  -- (src/core/gen2/Clock.lua:125-143) is pinned to zero after it, as the boot
  -- part does for Oak's own clock.
  local function answerClock(s)
    if s.kantoBridgeAnswered then return end
    s.kantoBridgeAnswered = true
    local finish = s.finish
    if type(finish) == "function" then
      s.finish = function(self, ...)
        local Clock = b.c2("Clock")
        if self.mode == "day" then
          local day = b.ask(Clock, "hostWeekday", nil)
          if type(day) == "number" then self.day = day end
        else
          local minutes = b.ask(Clock, "hostMinutes", nil)
          if type(minutes) == "number" then self.hour, self.minute = math.floor(minutes / 60), minutes % 60 end
        end
        local r = finish(self, ...)
        local rtc = type(self.save) == "table" and self.save.rtc or nil
        if type(rtc) == "table" then
          if self.mode == "day" then rtc.startDay = 0 else rtc.startMinute = 0 end
        end
        return r
      end
    end
    s.autoConfirm = true
  end

  -- ---- the stack

  local function add(list, obj, cls, screen, src)
    local e = { key = keyOf(obj), cls = cls, screen = screen, src = src, obj = obj }
    list[#list + 1] = e
    return e
  end

  -- The boot chain is one mirror while any of it is up: one object for it.
  local bootToken = nil

  -- The stack as plain entries, bottom to top: { key, cls, screen }.
  def("uiStack", function()
    local list = {}
    local g = b.game
    local stack = type(g) == "table" and g.stack or nil
    local states = type(stack) == "table" and type(stack.states) == "table" and stack.states or {}
    if b.inField() then add(list, b.ow(), "Overworld", nil, "overworld") end
    local i, n = 1, #states
    local booting = false
    while i <= n do
      local s = states[i]
      local cls, screen = classOf(s)
      local sid = type(s) == "table" and s.screenId or nil
      if sid == "Gen2InitClock" and i == n then answerClock(s) end
      if sid == "Gen2NamePick" then
        local above = states[i + 1]
        local e = add(list, s, "NamingScreen", "NamingScreen", "naming")
        e.pick = s
        if type(above) == "table" and above.screenId == "Gen2NamingScreen" then
          -- the keyboard the pick's NEW NAME opened (src/ui/gen2/NamePick.lua:112-138):
          -- the same naming UI
          e.keyboard = above
          i = i + 1
        elseif i == n then
          add(list, presetProxy(s), "Menu", nil, "presets").pick = s
        end
      elseif sid == "Gen2NamingScreen" then
        add(list, s, "NamingScreen", "NamingScreen", "naming").keyboard = s
      elseif type(sid) == "string" and Ui.BOOT_SCREENS[sid] then
        if not booting then
          booting = true
          bootToken = bootToken or {}
          add(list, bootToken, cls, screen, "state")
        end
      else
        local src = (cls == "TextBox" and "text") or (cls == "ChoiceBox" and (screen == "GenderSelect" and "gender"
          or "choice")) or (cls == "Menu" and "start") or (cls == "ListMenu" and "scriptmenu") or "state"
        add(list, s, cls, screen, src)
      end
      i = i + 1
    end
    if not booting then bootToken = nil end
    -- a picture waiting for a press outside the stack: `waitbutton` under a
    -- pokepic (src/world/gen2/World.lua:4398-4423; Elm's starter balls), polled
    -- by World:step, which runs only with nothing on the stack
    local w = b.ow()
    if n == 0 and w and b.inField() and type(w.waitButton) == "table" then
      add(list, w.waitButton, "PicBox", "PicBox", "pic")
    end
    current = {}
    local out = {}
    for k, e in ipairs(list) do
      current[e.key] = e
      out[k] = { key = e.key, cls = e.cls, screen = e.screen }
    end
    return out
  end)

  local function entryOf(key)
    local e = current[key]
    return type(e) == "table" and e or nil
  end

  def("uiClass", function(key)
    local e = entryOf(key)
    if not e then return "Busy" end
    return e.cls, e.screen
  end)

  -- ---- the shared TextBox (gen1.lua's readers)

  -- The rows currently in the box: TextBox:visibleText's `shownSource`, else the
  -- page's lines up to lineIndex (gen1.lua:904-923).
  local function visibleLines(s)
    local pages = type(s.pages) == "table" and s.pages or {}
    local page = pages[int(s.pageIndex, 1)]
    if type(page) ~= "table" then return {} end
    local out = {}
    if type(s.shownSource) == "table" then
      for i = 1, #s.shownSource do
        local source = s.shownSource[i]
        local text = source and page[source] or nil
        out[#out + 1] = type(text) == "string" and text or ""
      end
      return out
    end
    local shown = count(s.shown)
    local line = int(s.lineIndex, 1)
    for i = math.max(1, line - shown + 1), line do
      if type(page[i]) == "string" then out[#out + 1] = page[i] end
    end
    return out
  end

  -- The box takes an A/B tap this step (TextBox:update's order; gen1.lua:936-949).
  local function textReady(s)
    if int(s.holdFrames, 0) > 0 then return false end
    if s.preSound or s.pauseFrames or s.pauseSrc or s.sfxWait then return false end
    if s.done then
      if s.stay then
        return type(s.stay) == "table" and (s.stay.prompt or s.stay.press) and not s.stayShown and true or false
      end
      if s.auto then
        return type(s.auto) == "table" and s.auto.promptFirst and not s.autoPrompted and true or false
      end
      return not s.choice
    end
    return s.waiting == true and int(s.preWait, 0) == 0
  end

  -- `waiting` for the client: a press does something now (the v0.3.85 rule,
  -- gen1.lua:962-973: a {DONE} text hides the engine's arrow while A/B still
  -- closes it).
  local function textArrow(s)
    if s.sfxWait then return false end
    if s.waiting then return true end
    if not s.done or s.choice then return false end
    if s.auto then
      return type(s.auto) == "table" and s.auto.promptFirst and not s.autoPrompted and true or false
    end
    if s.stay then
      return type(s.stay) == "table" and (s.stay.prompt or s.stay.press) and not s.stayShown and true or false
    end
    return true
  end

  local function stackIndex(state)
    local g = b.game
    local states = type(g) == "table" and type(g.stack) == "table" and g.stack.states or nil
    if type(states) ~= "table" then return nil end
    for i = #states, 1, -1 do
      if states[i] == state then return i, states end
    end
    return nil
  end

  -- ---- the start menu: items { label, value }, translated when built
  -- (src/ui/gen2/StartMenu.lua:112-146, :197-231); the cursor is its
  -- Chrome.List's index (src/ui/gen2/Chrome.lua:522-560)

  local function startRows(s)
    local out = {}
    local items = type(s.items) == "table" and s.items or {}
    for i, it in ipairs(items) do
      if type(it) == "table" and not Ui.HIDDEN_START[it.value] then
        out[#out + 1] = { index = i, label = type(it.label) == "string" and it.label or "" }
      end
    end
    return out
  end

  -- ---- the name pick and the keyboard

  local function namingModule() return b.ui2("NamingScreen") end

  local function playerType()
    local NS = namingModule()
    local types = type(NS) == "table" and type(NS.TYPES) == "table" and NS.TYPES or {}
    return type(types.player) == "table" and types.player or {}
  end

  -- The keyboard's letter pages for both cases, through its own rows()
  -- (src/ui/gen2/NamingScreen.lua:226-246, the ui.naming.grid hook included)
  -- on a copy of the fields it reads, so its case is left alone: the
  -- keyboard's, else the one NEW NAME opens (a player's,
  -- src/ui/gen2/NamePick.lua:112-138). Blank cells (a typed space) are left
  -- out; the bottom row (case, DEL, END) is no glyphs.
  local function namingGrid(kb, pick)
    local NS = namingModule()
    local rows = type(kb) == "table" and type(kb.rows) == "function" and kb.rows or (NS and NS.rows)
    if type(rows) ~= "function" then return {} end
    local player = playerType()
    local pages = {}
    for _, lower in ipairs({ false, true }) do
      local probe = { lower = lower, isBox = false, prompt = player.prompt, maxLength = player.maxLength or 7,
                      game = type(pick) == "table" and pick.game or nil }
      if type(kb) == "table" then
        probe.isBox, probe.prompt, probe.maxLength, probe.game = kb.isBox or false, kb.prompt, kb.maxLength, kb.game
      end
      local ok, grid = pcall(rows, probe)
      local out = {}
      if ok and type(grid) == "table" then
        for r = 1, #grid do
          local cells = {}
          for _, cell in ipairs(type(grid[r]) == "table" and grid[r] or {}) do
            if type(cell) == "string" and cell ~= "" and cell ~= " " then cells[#cells + 1] = cell end
          end
          if #cells > 0 then out[#out + 1] = cells end
        end
      end
      pages[#pages + 1] = out
    end
    return pages
  end

  -- What the keyboard holds after typing `glyphs` one by one, or nil when one
  -- would not go in: addCharacter takes a glyph only while the text is shorter
  -- than maxLength in bytes (src/ui/gen2/NamingScreen.lua:288-292).
  local function typed(glyphs, maxLength)
    local text = ""
    for i = 1, #glyphs do
      if type(glyphs[i]) ~= "string" or glyphs[i] == "" or #text >= maxLength then return nil end
      text = text .. glyphs[i]
    end
    return text
  end

  -- ---- facts

  def("uiOpenFacts", function(key)
    local e = entryOf(key)
    if not e then return nil end
    local s = e.obj
    local f = { cls = e.cls, screen = e.screen }
    if e.src == "text" then
      -- pages + pages.contBefore (TextBox.paginate); the opts flags (gen1.lua:1106-1133)
      local pages = type(s.pages) == "table" and s.pages or {}
      local before = type(pages.contBefore) == "table" and pages.contBefore or {}
      f.pages, f.cont = {}, {}
      for p = 1, #pages do
        local lines = type(pages[p]) == "table" and pages[p] or {}
        local conts = type(before[p]) == "table" and before[p] or {}
        local pl, pc = {}, {}
        for i = 1, #lines do
          pl[i] = type(lines[i]) == "string" and lines[i] or ""
          pc[i] = conts[i] == true
        end
        f.pages[p], f.cont[p] = pl, pc
      end
      local auto, stay = s.auto, s.stay
      f.auto = auto ~= nil and auto ~= false and not (type(auto) == "table" and (auto.promptFirst or auto.wait))
      f.stay = stay ~= nil and stay ~= false and not (type(stay) == "table" and (stay.prompt or stay.press))
      f.instant = s.instant == true
      f.choice = s.choice ~= nil
    elseif e.src == "choice" then
      -- labels default to YES/NO (src/ui/ChoiceBox.lua:33); the question is the text box under it
      f.labels = {}
      for i, label in ipairs(type(s.labels) == "table" and s.labels or {}) do f.labels[i] = S(label) or "" end
      local i, states = stackIndex(s)
      local below = i and states[i - 1]
      if type(below) == "table" and classOf(below) == "TextBox" then f.question = visibleLines(below) end
    elseif e.src == "gender" then
      -- GenderSelect.OPTIONS (src/ui/gen2/GenderSelect.lua:20-23); its question,
      -- typed above the menu (src/ui/gen2/GenderSelect.lua:70-74)
      local GS = b.ui2("GenderSelect")
      local options = type(GS) == "table" and type(GS.OPTIONS) == "table" and GS.OPTIONS or {}
      f.labels = {}
      for i, o in ipairs(options) do f.labels[i] = S(type(o) == "table" and o.label or nil) or "" end
      if type(s.text) == "string" then
        local q = {}
        for line in (s.text .. "\n"):gmatch("(.-)[\n\f]") do if line ~= "" then q[#q + 1] = line end end
        f.question = q
      end
    elseif e.src == "start" then
      f.items = {}
      for i, r in ipairs(startRows(s)) do f.items[i] = { label = r.label } end
      f.rows = #f.items
      f.wrap = type(s.list) == "table" and s.list.wrap == true
    elseif e.src == "scriptmenu" then
      -- labels, rows x cols, wrapping only with STATICMENU_WRAP
      -- (src/ui/gen2/ScriptMenu.lua:113-131)
      f.items = {}
      for i, label in ipairs(type(s.items) == "table" and s.items or {}) do
        f.items[i] = { label = type(label) == "string" and label or "" }
      end
      f.rows = #f.items
      f.wrap = s.wrap == true
    elseif e.src == "naming" then
      -- the prompt (NamingScreen.TYPES, drawn through Strings), the pick's
      -- names after its NEW NAME row (src/ui/gen2/NamePick.lua:84-87), the
      -- length, the text so far
      local kb, pick = e.keyboard, e.pick
      local player = playerType()
      local prompt = type(kb) == "table" and kb.prompt or player.prompt
      f.title = S(prompt) or ""
      f.presets = {}
      if type(pick) == "table" and type(pick.items) == "table" then
        for i = 2, #pick.items do
          if type(pick.items[i]) == "string" then f.presets[#f.presets + 1] = pick.items[i] end
        end
      end
      f.max = int(type(kb) == "table" and kb.maxLength or player.maxLength, 7)
      f.value = type(kb) == "table" and type(kb.text) == "string" and kb.text or ""
      f.grid = namingGrid(kb, pick)
    elseif e.src == "presets" then
      f.items = {}
      for i, label in ipairs(type(e.pick.items) == "table" and e.pick.items or {}) do
        f.items[i] = { label = S(label) or "" }
      end
      f.rows = #f.items
      f.wrap = true -- the pick's cursor goes round (src/ui/gen2/NamePick.lua:163-166)
    end
    return f
  end)

  def("uiCursor", function(key)
    local e = entryOf(key)
    if not e then return nil end
    local s = e.obj
    if e.src == "text" then
      return { page = int(s.pageIndex, 1), line = int(s.lineIndex, 1), done = s.done == true,
               waiting = textArrow(s), ready = textReady(s) }
    elseif e.src == "choice" then
      -- index 1 YES / 2 NO; pending during the answer hold (src/ui/ChoiceBox.lua:17, :43-52)
      return { index = int(s.index, 1), busy = s.pending ~= nil }
    elseif e.src == "gender" then
      -- the menu opens once its question is typed, and a choice runs out its
      -- exit frames (src/ui/gen2/GenderSelect.lua:89-96, :108-111); B does nothing
      local okOpen, open = b.method(s, "menuOpen")
      local busy = s.exit ~= nil or s.chosen ~= nil or (okOpen and open ~= true)
      return { index = int(s.cursor, 1), busy = busy, noByCursor = true }
    elseif e.src == "start" then
      local rows = startRows(s)
      local list = type(s.list) == "table" and s.list or {}
      local at = int(list.index, 1)
      local index = #rows
      for i, r in ipairs(rows) do
        if r.index == at then index = i break end
      end
      -- QUIT's own question is up (src/ui/gen2/StartMenu.lua:294-311)
      return { index = math.max(1, index), scroll = 0, count = #rows, busy = s.phase ~= nil }
    elseif e.src == "scriptmenu" then
      -- choiceIndex (src/ui/gen2/ScriptMenu.lua:90-92); done once answered
      local cols = math.max(1, int(s.cols, 1))
      return { index = (int(s.row, 1) - 1) * cols + int(s.col, 1), scroll = 0, count = count(s.items),
               busy = s.done == true }
    elseif e.src == "naming" then
      if e.keyboard then
        return { choosing = false, typed = utf8Len(type(e.keyboard.text) == "string" and e.keyboard.text or ""),
                 busy = false }
      end
      -- the pic slides in, and out after a preset, before input counts
      -- (src/ui/gen2/NamePick.lua:141-158)
      return { choosing = true, typed = 0, busy = e.pick.slide ~= nil }
    elseif e.src == "presets" then
      return { index = int(e.pick.cursor, 1), scroll = 0, count = count(e.pick.items), busy = e.pick.slide ~= nil }
    end
    return {}
  end)

  def("uiItems", function(key)
    local e = entryOf(key)
    if not e then return nil end
    if e.src == "start" or e.src == "scriptmenu" or e.src == "presets" then
      local f = b.adapter.uiOpenFacts(key)
      return f and f.items or nil
    end
    return nil
  end)

  -- No Gen 2 menu read through these classes shows a footer or money (the
  -- Mart is a mirror).
  def("uiMoneyFooter", function(key)
    local e = entryOf(key)
    if not e or (e.cls ~= "Menu" and e.cls ~= "ListMenu") then return nil end
    return {}
  end)

  -- Moves a cursor to `index` (1-based) as the widget's own move would.
  def("uiSetIndex", function(key, index)
    local e = entryOf(key)
    if not e or type(index) ~= "number" or index ~= math.floor(index) then return false end
    local s = e.obj
    if e.src == "choice" then
      if index ~= 1 and index ~= 2 then return false end
      s.index = index
      return true
    elseif e.src == "gender" then
      if index < 1 or index > 2 then return false end
      s.cursor = index
      return true
    elseif e.src == "start" then
      local r = startRows(s)[index]
      local list = s.list
      if not r or type(list) ~= "table" then return false end
      list.index = r.index
      b.method(list, "ensureVisible")
      return true
    elseif e.src == "scriptmenu" then
      local cols = math.max(1, int(s.cols, 1))
      if index < 1 or index > count(s.items) then return false end
      s.row, s.col = math.floor((index - 1) / cols) + 1, (index - 1) % cols + 1
      return true
    elseif e.src == "presets" then
      if index < 1 or index > count(e.pick.items) then return false end
      e.pick.cursor = index
      return true
    end
    return false
  end)

  def("uiSetQty", function() return false end)

  -- The keyboard: the typed name, and the cursor on END, so the A the core
  -- taps next accepts it (src/ui/gen2/NamingScreen.lua:303-306, :370-375); the
  -- pick then takes the name (src/ui/gen2/NamePick.lua:128-136). Returns true,
  -- "a". An empty name is the caller's own default (the pick's first preset;
  -- no nickname; the rival's default, src/world/gen2/World.lua:7777-7819).
  -- Refused while only the pick's presets are up (the core picks NEW NAME first).
  def("uiSetGlyphs", function(key, glyphs)
    local e = entryOf(key)
    if not e or e.src ~= "naming" or type(glyphs) ~= "table" then return false end
    local kb = e.keyboard
    if type(kb) ~= "table" then return false end
    local name = typed(glyphs, int(kb.maxLength, 7))
    if name == nil then return false end
    kb.text = name
    local okRow, bottom = b.method(kb, "bottomRow")
    kb.row = okRow and type(bottom) == "number" and bottom or int(kb.row, 0)
    kb.col = Ui.END_COL
    return true, "a"
  end)
end

return Ui
