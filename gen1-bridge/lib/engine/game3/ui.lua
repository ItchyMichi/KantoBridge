-- game3 UI (GEN3_BRIDGE_PLAN.md §4.5, phase G3B): the core's UI stack
-- (observe/kb_ui.lua, act/kb_uiact.lua) built from Gen 3's scattered state.
-- Gen 3 keeps only screens on its stack (src/ui/game3/stack.lua:7-46); the
-- message box (message.lua) and the yes/no and multichoice (choice.lua) are
-- module singletons, and the boot phase (title, main menu, Birch's speech)
-- lives outside both (src/ui/game3/boot_modules.lua). The HUD gives input to
-- the naming layer, then the stack's top, then a choice, then the message
-- box (hud.lua:205-313), so the entries go bottom to top: the overworld, the
-- message box, a choice, the stack's layers.
--
-- Classes (the core's, kb_ui.lua:144-148):
--   the message box (field, sign)      TextBox
--   Choice "yesno"                     ChoiceBox
--   Choice "multi"                     ListMenu (its options, wrapping)
--   stack "script_list_menu"           ListMenu (the scrollable multichoice,
--                                      scripting/natives_listmenu.lua)
--   stack "start"                      Menu (StartMenu.ENTRIES without EXIT,
--                                      which asks to quit to the title, and
--                                      MODS, the player's own business)
--   stack "naming"                     NamingScreen (Naming._state)
--   any other layer, a battle          Screen / BattleState -> the mirror
--   the title and main menu            Screen "TitleState" -> the mirror
--   Birch's speech (boot phase "oak")  Screen "BirchSpeech" (its slide show)
--                                      with its printer as a TextBox, its
--                                      gender menu and name yes/no as
--                                      ChoiceBoxes, its naming screen
-- A battle is one mirror (its own text and menus included) until G3F makes
-- battles headless; the Mart is a mirror (G3F's bag projection).
--
-- Keys are stable per instance: the message box's page table (a new one per
-- Message.show, message.lua:243), the choice's callback (a new closure per
-- prompt), a layer's table (stack.lua:39-46), a printer or menu object of
-- Birch's. A new instance is a new UI to the client.
--
-- Text is the engine's own (TextIR.toTextBox pages: two lines a page, "\n"
-- between them, scripting/text_ir.lua:800-862), with its 0xFC control codes
-- (TextIR.EXT_ARGS, :246-250) and colour tags taken out and the {PK}/{MN}
-- ligatures as the protocol's <PK>/<MN>. Nothing here logs text.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Ui = {}

-- Stack layer ids (UI note §2) -> the screen name the mirror carries.
Ui.SCREENS = {
  start = "StartMenu", party = "PartyMenu", summary = "SummaryMenu", bag = "BagMenu", save = "SaveMenu",
  option = "OptionMenu", trainer = "TrainerCard", pc_menu = "PCMenu", box_storage = "BoxMenu", shop = "ShopMenu",
  naming = "NamingScreen", script_list_menu = "ListMenu", rse_pokedex = "PokedexMenu", rse_region_map = "TownMap",
  rse_item_storage = "ItemStorage", rse_mailbox = "Mailbox", daycare_level_menu = "DaycareMenu",
  prize_corner = "PrizeCorner", evolution_scene = "EvolutionScene", move_relearner = "MoveRelearner",
  starter_choose = "StarterChoose", wall_clock = "WallClock", rse_pokenav = "PokeNav",
  rse_match_call_window = "MatchCall",
}
-- Start menu rows the client never picks (start_menu.lua:107-111, rse/start_menu_data.lua:143)
Ui.HIDDEN_START = { exit = true, mods = true }
-- The screen sizes (src/core/game3/display.lua: 240x160, the GBA's)
Ui.WIDTH, Ui.HEIGHT = 240, 160

-- the scene-kit printer's waiting states (rse/scene_kit.lua:395-420)
local PRINTER_WAITS = { clear = true, scroll_start = true, wait = true }
local BREAKS = { para = true, scroll = true, wait = true }
local BOOT_SCREENS = { intro = true, copyright = true, title = true, title_restart = true, title_cry = true,
                       menu = true, title_combo = true, mystery_gift = true, controls = true }

local function utf8Len(s)
  local _, cont = s:gsub("[\128-\191]", "")
  return #s - cont
end

function Ui.install(b)
  local def = b.def

  -- ---- text

  local EXT_ARGS_FALLBACK = { -- scripting/text_ir.lua:246-250
    [0x01] = 1, [0x02] = 1, [0x03] = 1, [0x04] = 3, [0x05] = 1, [0x06] = 1, [0x08] = 1,
    [0x0B] = 2, [0x0C] = 1, [0x0D] = 1, [0x0E] = 1, [0x10] = 2, [0x11] = 1, [0x12] = 1,
    [0x13] = 1, [0x14] = 1,
  }
  local function extArgs()
    local TextIR = b.g3("scripting.text_ir")
    return type(TextIR) == "table" and type(TextIR.EXT_ARGS) == "table" and TextIR.EXT_ARGS or EXT_ARGS_FALLBACK
  end

  -- The script's string variables (Space.vm.ctx.stringVars, message.lua:79-83)
  local function stringVar(n)
    local Space = b.g3("scripting.space")
    local vm = type(Space) == "table" and Space.vm or nil
    local vars = type(vm) == "table" and type(vm.ctx) == "table" and vm.ctx.stringVars or nil
    if type(vars) ~= "table" then return "" end
    local v = vars[n] or vars[tostring(n)] or vars["STR_VAR_" .. n]
    return type(v) == "string" and v or ""
  end

  -- One engine string as plain text: control codes out, ligatures as tokens,
  -- a raw {STR_VAR_n} or {PLAYER} (multichoice labels) filled in.
  local function plain(s)
    if type(s) ~= "string" then return "" end
    if s:find("\252", 1, true) then
      local args, out, i, n = extArgs(), {}, 1, #s
      while i <= n do
        if s:byte(i) == 0xFC then
          -- the code byte and its arguments (a lone 0xFC at the end goes too)
          i = i + 2 + (i < n and args[s:byte(i + 1)] or 0)
        else
          local j = s:find("\252", i, true) or (n + 1)
          out[#out + 1] = s:sub(i, j - 1)
          i = j
        end
      end
      s = table.concat(out)
    end
    s = s:gsub("{PK}", "<PK>"):gsub("{MN}", "<MN>")
    s = s:gsub("{STR_VAR_(%d)}", function(d) return stringVar(tonumber(d)) end)
    s = s:gsub("{PLAYER}", function()
      local sess = b.session()
      return type(sess) == "table" and type(sess.name) == "string" and sess.name or ""
    end)
    -- colour, shadow, font and other tags (rse/option_menu.lua:43-48 strips them the same way)
    s = s:gsub("{[^{}]*}", "")
    return s
  end

  local function lines(page)
    local out = {}
    for line in (tostring(page or "") .. "\n"):gmatch("(.-)\n") do out[#out + 1] = plain(line) end
    while #out > 1 and out[#out] == "" do out[#out] = nil end
    return out
  end

  local function romText(label)
    local ok, s = b.call(b.g3("rom_text"), "plain", label)
    return ok and type(s) == "string" and plain(s) or ""
  end

  -- ---- keys

  local keys = setmetatable({}, { __mode = "k" })
  local nextKey = 0
  local function keyOf(obj)
    local k = keys[obj]
    if not k then
      nextKey = nextKey + 1
      k = nextKey
      keys[obj] = k
    end
    return k
  end
  local current = {}    -- key -> entry, from the last uiStack()
  local battleToken     -- one per battle (Battle.isActive's rising edge)

  -- ---- the sources

  local function birch()
    local g = b.game
    local boot = type(g) == "table" and g.phase == "boot" and type(g.boot) == "table" and g.boot or nil
    if not boot or boot.phase ~= "oak" then return nil end
    local custom = type(boot.custom) == "table" and boot.custom or nil
    local ng = custom and custom.newGame or nil
    return type(ng) == "table" and ng or nil
  end

  -- The message box takes an A press now (hud.lua:283-304): its page shown
  -- in full, not a page an auto-scroll turns itself, not a held box already
  -- answered; a stay box's last page only once the script's waitbuttonpress
  -- has armed Hud._waitButton (ops_a.lua:616-648; Emerald draws no arrow
  -- there, message.lua:492-498).
  local function messageState()
    local M, Hud = b.ui3("message"), b.ui3("hud")
    local pages = type(M) == "table" and type(M._pages) == "table" and M._pages or {}
    local page = math.max(1, math.floor(tonumber(M and M._page) or 1))
    local waiting = b.ask(M, "isWaiting", false) == true
    local last = page >= #pages
    local ready = waiting and not M._held and not M._choice
    if ready and M._autoScroll and not last then ready = false end
    if ready and M._stay and last then ready = type(Hud) == "table" and Hud._waitButton ~= nil end
    local ls = lines(pages[page])
    local line = #ls
    if not waiting and #ls > 1 then
      local revealed = tonumber(M._revealed) or 0
      line = revealed > utf8Len(ls[1]) and 2 or 1
    end
    return { page = page, line = math.max(1, line), done = waiting and last, waiting = ready, ready = ready }
  end

  -- A printer's text as pages (rse/scene_kit.lua:279-310 tokens: c, nl,
  -- para, scroll, pause, wait): a para, a scroll or a wait ends a page when
  -- more text follows it.
  local function printerPages(p)
    local pages, cur, line = {}, { "" }, 1
    local pendingBreak = false
    for _, tok in ipairs(type(p.toks) == "table" and p.toks or {}) do
      local t = tok.t
      if t == "c" then
        if pendingBreak then
          pages[#pages + 1] = cur
          cur, line, pendingBreak = { "" }, 1, false
        end
        cur[line] = cur[line] .. (type(tok.s) == "string" and tok.s or "")
      elseif t == "nl" then
        line = line + 1
        cur[line] = cur[line] or ""
      elseif BREAKS[t] then
        pendingBreak = true
      end
    end
    pages[#pages + 1] = cur
    for _, pl in ipairs(pages) do
      for i = 1, #pl do pl[i] = plain(pl[i]) end
      while #pl > 1 and pl[#pl] == "" do pl[#pl] = nil end
    end
    return pages
  end

  -- Where a printer is: the page and line of its next token; a break it is
  -- waiting on (clear, scroll_start, wait) still shows the page before it.
  local function printerState(p, owner)
    local toks = type(p.toks) == "table" and p.toks or {}
    local pos = math.max(1, math.floor(tonumber(p.pos) or 1))
    local page, line, pending = 1, 1, false
    for i = 1, math.min(pos - 1, #toks) do
      local t = toks[i].t
      if t == "c" then
        if pending then page, line, pending = page + 1, 1, false end
      elseif t == "nl" then
        line = line + 1
      elseif BREAKS[t] then
        pending = true
      end
    end
    local active = p.active ~= false
    local waiting = active and PRINTER_WAITS[p.state] == true
    -- a finished printer Birch waits on for a press (main_menu.c's WaitPressBeforeNameChoice, birch_speech.lua:644-649)
    if not active and owner and type(owner.main) == "table" and owner.main.func == "WaitPressBeforeNameChoice" then
      waiting = true
    end
    return { page = page, line = line, done = not active, waiting = waiting, ready = waiting }
  end

  local function startRows()
    local SM = b.ui3("start_menu")
    local out = {}
    for i, e in ipairs(type(SM) == "table" and type(SM.ENTRIES) == "table" and SM.ENTRIES or {}) do
      if type(e) == "table" and not Ui.HIDDEN_START[e.id] then
        out[#out + 1] = { index = i, label = plain(e.label) }
      end
    end
    return out, SM
  end

  -- ---- the stack

  local function entry(list, obj, cls, screen, src)
    local e = { key = keyOf(obj), cls = cls, screen = screen, src = src, obj = obj }
    list[#list + 1] = e
    return e
  end

  local function layers(list)
    local Stack = b.ui3("stack")
    local Naming = b.ui3("naming")
    for _, layer in ipairs(type(Stack) == "table" and type(Stack._layers) == "table" and Stack._layers or {}) do
      if type(layer) == "table" then
        local id = tostring(layer.id or "")
        local screen = Ui.SCREENS[id] or id:upper():gsub("[^A-Z0-9_]", "_"):sub(1, 64)
        if id == "start" then
          entry(list, layer, "Menu", "StartMenu", "start")
        elseif id == "script_list_menu" then
          entry(list, layer, "ListMenu", "ListMenu", "listmenu")
        elseif id == "naming" then
          local st = type(Naming) == "table" and Naming._state or nil
          if type(st) == "table" and type(st.pcPages) == "table" then
            -- the "sent to the PC" pages after a nickname (naming.lua:647-654): pictures and A
            entry(list, st.pcPages, "Screen", "NamingScreen", "screen")
          elseif type(st) == "table" then
            entry(list, layer, "NamingScreen", "NamingScreen", "naming")
          end
        else
          entry(list, layer, "Screen", screen ~= "" and screen or nil, "screen")
        end
      end
    end
  end

  -- The stack as plain entries, bottom to top: { key, cls, screen }.
  def("uiStack", function()
    local list = {}
    local g = b.game
    local Battle = b.g3("battle")
    local inBattle = b.ask(Battle, "isActive", false) == true
    if inBattle then
      battleToken = battleToken or {}
    else
      battleToken = nil
    end
    if b.inField() then
      entry(list, b.session(), "Overworld", nil, "overworld")
      if inBattle and type(b.heldBattle) == "function" and b.heldBattle() then
        -- G3F: a battle held for Cobblemon is no screen of ours (the world is busy)
        battleToken = nil
      elseif inBattle then
        entry(list, battleToken, "BattleState", "BattleState", "screen")
      else
        local M, Choice = b.ui3("message"), b.ui3("choice")
        if b.ask(M, "isOpen", false) == true and type(M._pages) == "table" then
          entry(list, M._pages, "TextBox", nil, "message")
        end
        if type(Choice) == "table" and Choice.active then
          local obj = Choice.done or Choice.options
          if obj ~= nil then
            if Choice.kind == "yesno" then
              entry(list, obj, "ChoiceBox", nil, "choice")
            else
              entry(list, obj, "ListMenu", "ListMenu", "multi")
            end
          end
        end
        layers(list)
      end
    elseif type(g) == "table" and g.phase == "boot" and type(g.boot) == "table" then
      local boot = g.boot
      local br = birch()
      if br then
        entry(list, br, "Screen", "BirchSpeech", "screen")
        if br.showDialogue and type(br.printer) == "table" and not br.naming then
          entry(list, br.printer, "TextBox", nil, "printer").owner = br
        end
        if type(br.genderMenu) == "table" then entry(list, br.genderMenu, "ChoiceBox", nil, "gender").owner = br end
        if type(br.yesNo) == "table" then entry(list, br.yesNo, "ChoiceBox", nil, "yesno").owner = br end
        if br.naming then layers(list) end
      elseif BOOT_SCREENS[boot.phase] then
        local custom = type(boot.custom) == "table" and boot.custom or {}
        local obj = custom.title or custom.menu or custom.intro or custom.combo or boot
        entry(list, obj, "Screen", "TitleState", "screen")
      end
    end
    current = {}
    local out = {}
    for i, e in ipairs(list) do
      current[e.key] = e
      out[i] = { key = e.key, cls = e.cls, screen = e.screen }
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

  -- ---- facts

  local function choiceLabels(e)
    if e.src == "gender" then
      return { romText("gText_BirchBoy"), romText("gText_BirchGirl") }
    elseif e.src == "yesno" then
      -- Kit.yesNo draws gText_YesNo's two lines (rse/scene_kit.lua:592-600)
      local yes, no = romText("gText_YesNo"):match("^(.-)\n(.*)$")
      if not yes then yes, no = romText("gText_Yes"), romText("gText_No") end
      return { yes, no }
    end
    local Choice = b.ui3("choice")
    local out = {}
    for i, label in ipairs(type(Choice) == "table" and type(Choice.options) == "table" and Choice.options or {}) do
      out[i] = plain(type(label) == "table" and label.text or label)
    end
    return out
  end

  local function listLabels()
    local Menu = b.g3("scripting.natives_listmenu")
    local out = {}
    for i, label in ipairs(type(Menu) == "table" and type(Menu.labels) == "table" and Menu.labels or {}) do
      if type(label) == "table" then
        out[i] = { label = plain(label.text), right = type(label.tail) == "string" and plain(label.tail) or nil }
      else
        out[i] = { label = plain(label) }
      end
    end
    return out, Menu
  end

  local function namingGrid(st)
    local pages = {}
    for _, page in ipairs(type(st.pages) == "table" and st.pages or {}) do
      local rows = {}
      for _, row in ipairs(type(page) == "table" and type(page.rows) == "table" and page.rows or {}) do
        local cells = {}
        for _, cell in ipairs(type(row) == "table" and row or {}) do
          if type(cell) == "string" and cell ~= "" and cell ~= " " then cells[#cells + 1] = plain(cell) end
        end
        if #cells > 0 then rows[#rows + 1] = cells end
      end
      pages[#pages + 1] = rows
    end
    return pages
  end

  def("uiOpenFacts", function(key)
    local e = entryOf(key)
    if not e then return nil end
    local f = { cls = e.cls, screen = e.screen }
    if e.src == "message" then
      f.pages, f.cont = {}, {}
      for p, page in ipairs(e.obj) do
        f.pages[p] = lines(page)
        f.cont[p] = {}
        for i = 1, #f.pages[p] do f.cont[p][i] = false end
      end
      f.auto, f.stay, f.instant, f.choice = false, false, false, false
    elseif e.src == "printer" then
      f.pages, f.cont = printerPages(e.obj), {}
      for p = 1, #f.pages do
        f.cont[p] = {}
        for i = 1, #f.pages[p] do f.cont[p][i] = false end
      end
      f.auto, f.stay, f.instant, f.choice = false, false, false, false
    elseif e.cls == "ChoiceBox" then
      f.labels = choiceLabels(e)
      -- the question: the text box's page under it
      if e.src == "choice" then
        local M = b.ui3("message")
        if b.ask(M, "isOpen", false) == true then f.question = lines(b.ask(M, "currentPage", "")) end
      elseif type(e.owner) == "table" and type(e.owner.printer) == "table" then
        local q = {}
        for i, l in ipairs(type(e.owner.printer.lines) == "table" and e.owner.printer.lines or {}) do q[i] = plain(l) end
        f.question = q
      end
    elseif e.src == "multi" then
      f.items = {}
      for i, label in ipairs(choiceLabels(e)) do f.items[i] = { label = label } end
      f.rows = #f.items
      f.wrap = true -- Choice.move goes round (choice.lua:107-119)
    elseif e.src == "listmenu" then
      local items, Menu = listLabels()
      f.items = items
      f.rows = math.max(1, math.floor(tonumber(Menu and Menu.maxShowed) or #items))
      f.wrap = false -- Menu.move stops at both ends (natives_listmenu.lua:200-218)
    elseif e.src == "start" then
      local rows = startRows()
      f.items = {}
      for i, r in ipairs(rows) do f.items[i] = { label = r.label } end
      f.rows = #f.items
      f.wrap = true -- StartMenu.move goes round (start_menu.lua:236-248)
    elseif e.src == "naming" then
      local Naming = b.ui3("naming")
      local st = type(Naming) == "table" and type(Naming._state) == "table" and Naming._state or {}
      f.title = plain(st.title)
      f.presets = {}
      f.max = math.max(1, math.floor(tonumber(st.maxLen) or 7))
      f.value = plain(st.name)
      f.grid = namingGrid(st)
    end
    return f
  end)

  local function choiceIndex(e)
    if e.src == "gender" or e.src == "yesno" then return math.floor(tonumber(e.obj.cursor) or 0) + 1 end
    local Choice = b.ui3("choice")
    return math.floor(tonumber(Choice and Choice.cursor) or 1)
  end

  def("uiCursor", function(key)
    local e = entryOf(key)
    if not e then return nil end
    if e.src == "message" then return messageState() end
    if e.src == "printer" then return printerState(e.obj, e.owner) end
    if e.cls == "ChoiceBox" then
      -- noByCursor: the second answer is the cursor on it and A (B does nothing on Birch's menus and on a
      -- yes/no with ignoreBPress, choice.lua:165-169, rse/scene_kit.lua:574)
      return { index = choiceIndex(e), busy = false, noByCursor = true }
    end
    if e.src == "multi" then
      local Choice = b.ui3("choice")
      local n = type(Choice) == "table" and type(Choice.options) == "table" and #Choice.options or 0
      return { index = choiceIndex(e), scroll = 0, count = n }
    end
    if e.src == "listmenu" then
      local items, Menu = listLabels()
      local scroll = math.floor(tonumber(Menu and Menu.scroll) or 0)
      local row = math.floor(tonumber(Menu and Menu.row) or 1)
      return { index = scroll + row, scroll = scroll, count = #items }
    end
    if e.src == "start" then
      local rows, SM = startRows()
      local cursor = math.floor(tonumber(SM and SM.cursor) or 1)
      local index = #rows
      for i, r in ipairs(rows) do
        if r.index == cursor then index = i break end
      end
      return { index = math.max(1, index), scroll = 0, count = #rows, busy = SM and SM._confirmExit == true or false }
    end
    if e.src == "naming" then
      local Naming = b.ui3("naming")
      local st = type(Naming) == "table" and type(Naming._state) == "table" and Naming._state or {}
      local busy = st.finished == true or st.swapT ~= nil or st.pcPages ~= nil
      local br = birch()
      -- Birch's naming takes input only in its "input" stage (birch_speech.lua:687-706)
      if br and type(br.naming) == "table" and br.naming.stage ~= "input" then busy = true end
      return { choosing = false, typed = utf8Len(tostring(st.name or "")), busy = busy }
    end
    return {}
  end)

  def("uiItems", function(key)
    local e = entryOf(key)
    if not e then return nil end
    if e.src == "multi" then
      local out = {}
      for i, label in ipairs(choiceLabels(e)) do out[i] = { label = label } end
      return out
    elseif e.src == "listmenu" then
      return (listLabels())
    elseif e.src == "start" then
      local out = {}
      for i, r in ipairs(startRows()) do out[i] = { label = r.label } end
      return out
    end
    return nil
  end)

  -- No Gen 3 menu shows a footer or money through these classes (the Mart
  -- is a mirror until G3F).
  def("uiMoneyFooter", function(key)
    local e = entryOf(key)
    if not e or (e.cls ~= "Menu" and e.cls ~= "ListMenu") then return nil end
    return {}
  end)

  -- Moves a cursor to `index` (1-based) as the widget's own move would.
  def("uiSetIndex", function(key, index)
    local e = entryOf(key)
    if not e or type(index) ~= "number" or index ~= math.floor(index) then return false end
    if e.src == "gender" or e.src == "yesno" then
      if index ~= 1 and index ~= 2 then return false end
      e.obj.cursor = index - 1
      return true
    elseif e.src == "choice" or e.src == "multi" then
      local Choice = b.ui3("choice")
      local n = type(Choice) == "table" and type(Choice.options) == "table" and #Choice.options or 0
      if index < 1 or index > n then return false end
      Choice.cursor = index
      return true
    elseif e.src == "listmenu" then
      local items, Menu = listLabels()
      local count = math.floor(tonumber(Menu and Menu.count) or #items)
      local shown = math.max(1, math.floor(tonumber(Menu and Menu.maxShowed) or 1))
      if type(Menu) ~= "table" or index < 1 or index > count then return false end
      local scroll = math.floor(tonumber(Menu.scroll) or 0)
      if index - 1 < scroll then
        scroll = index - 1
      elseif index - 1 >= scroll + shown then
        scroll = index - shown
      end
      Menu.scroll, Menu.row = scroll, index - scroll
      return true
    elseif e.src == "start" then
      local rows, SM = startRows()
      local r = rows[index]
      if not r or type(SM) ~= "table" then return false end
      SM.cursor = r.index
      b.call(SM, "clampScroll")
      return true
    end
    return false
  end)

  def("uiSetQty", function() return false end)

  -- The naming screen: the typed name, and the cursor on OK, so the A the
  -- core taps next finishes it through the screen's own OK (naming.lua:742-
  -- 760: confirm, then Naming.close or the PC pages). Returns true, "a".
  def("uiSetGlyphs", function(key, glyphs)
    local e = entryOf(key)
    if not e or e.src ~= "naming" or type(glyphs) ~= "table" then return false end
    local Naming = b.ui3("naming")
    local st = type(Naming) == "table" and type(Naming._state) == "table" and Naming._state or nil
    if not st then return false end
    local parts = {}
    for i = 1, #glyphs do
      if type(glyphs[i]) ~= "string" then return false end
      parts[i] = glyphs[i]
    end
    local name = table.concat(parts)
    if utf8Len(name) > math.floor(tonumber(st.maxLen) or 7) then return false end
    st.name = name
    local page = type(st.pages) == "table" and st.pages[st.page] or nil
    local rows = type(page) == "table" and type(page.rows) == "table" and page.rows or nil
    st.row, st.btn = 4, 3
    st.col = (rows and type(rows[4]) == "table" and #rows[4] or 0) + 1
    return true, "a"
  end)

  -- The mirror's frame: the GBA's 240x160 screen.
  def("screenSize", function() return Ui.WIDTH, Ui.HEIGHT end)
end

return Ui
