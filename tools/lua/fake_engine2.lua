-- A stand-in for the parts of Gen1Recomp v0.3.85's Gen 2 engine (`gs` for
-- Gold and Silver, `crystal`) that gen1-bridge's gen2 adapter reads
-- (lib/engine/gen2.lua and its parts, lib/engine/gen2/*.lua and
-- lib/engine/common/*.lua; docs/specs/GEN2_BRIDGE_PLAN.md §10): the Game2
-- object (phase, world, save, stack, input, data and the methods the adapter
-- calls), the World with its map, player and people, the engine modules the
-- adapter requires, mod.world's Gen 2 facade, and a field step so main.lua can
-- be stepped on it. Written for Kanto Bridge; module and field names follow
-- the engine (file:line of each below, refs/gen1recomp at v0.3.85).
-- Everything is synthetic: made-up maps (TEST_*), collision bytes, flags by
-- number, items and sprites.
--
--   local E = FakeEngine2.new({ game = "crystal" })
--   bridge modules: E.modules (require name -> table); the Game2 object: E.game
--   E.enterWorld("TEST_TOWN", 5, 6, "down")   CONTINUE's or NEW GAME's result (map.entered)
--   E.leaveWorld()                            back to the title (Game2:returnToTitle's state)
--   E.coll("TEST_TOWN", x, y, 0x07)           a cell's COLL byte (0x00 floor by default)
--   E.addWarp("TEST_TOWN", x, y, "TEST_HOUSE", destX, destY)
--   E.addNpc(index, { x =, y =, sprite =, trainer =, itemball =, movement = })
--   E.walkNpc(index, dir)                     an NPC step: movement.collision with the NPC as mover
--   E.setFlag(id, on)                         an event flag by number (World.events)
--   E.openTextBox(), E.openChoice(), E.pushScreen("Gen2StartMenu")
--   E.openYesNo(pages, cb), E.openScriptMenu(items, {cols, wrap}), E.openStartMenu(),
--   E.openNaming({type}), E.openInitClock("day"), E.openCall(pages)   Gen 2's UI states
--   E.newSave(), E.slotSave("slot1", save)    a Gen 2 save, one in a slot (what CONTINUE loads)
--   E.bindMod(FakeMod, mod)                   events and hooks go to that mod object
--   E.tick()                                  the engine's part of a logic step (after input.step)
--
-- Test switches: E.saveResult (nil: written, "refuse", "error"), E.quickSaveOk
-- (false: the field can't save), E.unionApplied, E.newGameFails,
-- E.continueFails, E.refuseWarp; records: E.newGames, E.continues, E.saveCalls,
-- E.titleCalls, E.musicCalls, E.warps, E.queued, E.replaced, E.slotCalls,
-- E.tryMoves, E.worldSteps, E.named, E.clockAnswers, E.menuAnswers,
-- E.startChoices.

local FakeEngine2 = {}

local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local DIR_ORDER = { "up", "down", "left", "right" } -- heldDirection's order (World.lua:10368-10381)
local TURN_FRAMES, WALK_FRAMES = 4, 16             -- Player.lua:12-13

-- The COLL bytes a step may land on (Permissions.isWalkable's LAND set and the
-- warps; src/core/CollPermissions.lua:9-52): floor, tall and long grass, ice,
-- the warps, the ledges (a refused step, then a hop: not simulated here).
local WALKABLE = { [0x00] = true, [0x18] = true, [0x14] = true, [0x23] = true, [0x60] = true, [0x68] = true,
                   [0x70] = true, [0x71] = true, [0x72] = true, [0x76] = true, [0x78] = true, [0x7a] = true,
                   [0x7b] = true, [0x7c] = true, [0x7e] = true }
-- the warp bytes a walked step takes (Permissions.lua:300-345)
local WARP = { [0x60] = true, [0x68] = true, [0x70] = true, [0x71] = true, [0x72] = true, [0x76] = true,
               [0x78] = true, [0x7a] = true, [0x7b] = true, [0x7c] = true, [0x7e] = true }

local function cellKey(x, y) return x .. "," .. y end

local function deepCopy(v)
  if type(v) ~= "table" then return v end
  local out = {}
  for k, x in pairs(v) do out[k] = deepCopy(x) end
  return out
end

-- ------------------------------------------------------------ input

-- The engine's shared Input as mod.input presses into it (src/core/Input.lua;
-- src/mods/Loader.lua:1466-1503): sources per button; a press sets the held
-- state at once and queues an edge; Input:step turns the queued edges into one
-- step of wasPressed (as fake_engine3.lua's).
local function newInput()
  local input = { state = {}, sources = {}, pressQueue = {}, pressed = {} }
  function input:sourcePress(btn, source)
    local s = self.sources[btn] or {}
    self.sources[btn] = s
    if not s[source] then
      s[source] = true
      self.pressQueue[#self.pressQueue + 1] = btn
    end
    self.state[btn] = true
  end
  function input:sourceRelease(btn, source)
    local s = self.sources[btn]
    if s then
      s[source] = nil
      if next(s) == nil then self.state[btn] = false end
    else
      self.state[btn] = false
    end
  end
  function input:step()
    self.pressed = {}
    for _, btn in ipairs(self.pressQueue) do
      self.pressed[btn] = true
      local s = self.sources[btn]
      if s == nil or next(s) ~= nil then self.state[btn] = true end
    end
    for btn, s in pairs(self.sources) do
      if next(s) == nil then self.sources[btn] = nil end
    end
    self.pressQueue = {}
  end
  function input:isDown(btn) return self.state[btn] == true end
  function input:wasPressed(btn) return self.pressed[btn] == true end
  return input
end

-- ------------------------------------------------------------ the engine

function FakeEngine2.new(opts)
  opts = opts or {}
  local E = {
    newGames = {}, continues = {}, saveCalls = {}, titleCalls = {}, musicCalls = {}, warps = {}, queued = {},
    replaced = {}, slotCalls = {}, tryMoves = {}, worldSteps = 0, ticks = 0,
  }
  local emit = function() end
  local callHook = function(_, vanilla, ...) return vanilla(...) end

  -- src/core/Version.lua:6-23: the release, the native-shell contract, the mod
  -- API major and the save format
  E.Version = { engine = opts.engine or "0.3.85", shell = 3, modApi = 2, saveFormat = 5 }
  -- src/core/GameVersion.lua:59-111: Gold and Silver are engine "gs", Crystal
  -- "crystal"; get (:209), generation (:231-233), engine (:235-238)
  E.GameVersion = { current = opts.game or "crystal" }
  E.engineId = opts.engineId or (E.GameVersion.current == "crystal" and "crystal" or "gs")
  function E.GameVersion.get() return E.GameVersion.current end
  function E.GameVersion.generation() return 2 end
  function E.GameVersion.engine() return E.engineId end

  -- ---- save slots: SaveData's registry (src/core/SaveData.lua:1642-1900; Gen 2
  -- saves resolve the active slot the same way, src/core/gen2/Save.lua:109-152),
  -- keyed by version (as fake_engine3.lua's)
  E.slotReg = { list = {}, names = {} }
  E.slotFiles, E.slotSaves = {}, {}
  E.activeSlot = nil
  local function call(name, version)
    E.slotCalls[#E.slotCalls + 1] = { name = name, version = version }
  end
  local function known(version) return version == E.GameVersion.current end
  local function registered(id)
    for _, v in ipairs(E.slotReg.list) do
      if v == id then return true end
    end
    return false
  end
  local SaveData = {}
  function SaveData.activeSlot(version)
    call("activeSlot", version)
    if not known(version) then return nil end
    return E.activeSlot
  end
  function SaveData.setActiveSlot(version, id)
    call("setActiveSlot", version)
    if not known(version) then return nil end
    if not registered(id) then E.slotReg.list[#E.slotReg.list + 1] = id end
    E.slotReg.active, E.activeSlot = id, id
    return id
  end
  function SaveData.createSlot(version)
    call("createSlot", version)
    if not known(version) then return nil end
    local max = 0
    for _, id in ipairs(E.slotReg.list) do
      local n = tonumber(id:match("^slot(%d+)$"))
      if n and n > max then max = n end
    end
    local id = "slot" .. (max + 1)
    E.slotReg.list[#E.slotReg.list + 1] = id
    return id
  end
  function SaveData.renameSlot(version, id, label)
    call("renameSlot", version)
    if not known(version) then return false, "unknown version" end
    if not registered(id) then return false, "slot not registered" end
    E.slotReg.names[id] = label
    return true
  end
  function SaveData.deleteSlot(version, id)
    call("deleteSlot", version)
    if not known(version) then return false, "unknown version" end
    if not registered(id) then return false, "slot not registered" end
    for i, v in ipairs(E.slotReg.list) do
      if v == id then table.remove(E.slotReg.list, i) break end
    end
    E.slotFiles[id], E.slotSaves[id], E.slotReg.names[id] = nil, nil, nil
    if E.slotReg.active == id then E.slotReg.active = E.slotReg.list[1] end
    if E.activeSlot == id then E.activeSlot = E.slotReg.active end
    return true
  end
  function SaveData.listSlots(version)
    call("listSlots", version)
    local out = {}
    for i, v in ipairs(known(version) and E.slotReg.list or {}) do out[i] = v end
    return out
  end
  function SaveData.loadOptions()
    local list, names = {}, {}
    for i, v in ipairs(E.slotReg.list) do list[i] = v end
    for k, v in pairs(E.slotReg.names) do names[k] = v end
    return { saveSlots = { [E.GameVersion.current] = { list = list, active = E.slotReg.active, names = names } } }
  end
  function SaveData.persistenceFs()
    return { getInfo = function(path)
      local version, id = path:match("^saves/([%w_]+)/(slot%d+)%.lua")
      if version == E.GameVersion.current and E.slotFiles[id] then return { type = "file" } end
      return nil
    end }
  end
  E.SaveData = SaveData

  -- ---- saves (src/core/gen2/Save.lua): a new game's table (Save.newGame,
  -- :192-310, made-up values), exists/load (:882-934: nil without a save)

  function E.newSave()
    return {
      version = E.GameVersion.current,
      player = { name = "TESTER", id = 4321, gender = "male", money = 3000, coins = 0, badges = {}, kantoBadges = {} },
      rival = { name = "???" },
      position = nil, inventory = {}, pcItems = {}, party = {}, boxes = {}, pokedex = { seen = {}, caught = {} },
      events = { flags = {} }, engineFlags = {}, mapScenes = {}, hallOfFame = { count = 0, teams = {} },
      rtc = {}, options = { textSpeed = "fast" }, modData = {},
    }
  end
  -- A Gen 2 save in a slot (nil removes it).
  function E.slotSave(id, save)
    if save ~= nil and not registered(id) then E.slotReg.list[#E.slotReg.list + 1] = id end
    E.slotFiles[id] = save ~= nil or nil
    E.slotSaves[id] = save
  end
  local Save = {}
  function Save.exists(version)
    return known(version) and E.slotSaves[E.activeSlot or "flat"] ~= nil
  end
  function Save.load(version)
    if not known(version) then return nil end
    local save = E.slotSaves[E.activeSlot or "flat"]
    return save ~= nil and deepCopy(save) or nil
  end
  E.Save = Save

  -- src/core/Music.lua: fadeOut (:363), MAP_FADE (:418), restoreMap (:545), recorded
  E.Music = { MAP_FADE = 10 }
  function E.Music.fadeOut(frames) E.musicCalls[#E.musicCalls + 1] = { op = "fadeOut", frames = frames } end
  function E.Music.restoreMap() E.musicCalls[#E.musicCalls + 1] = { op = "restoreMap" } end

  -- the shared text box and yes/no, told apart by their metatables: a box's
  -- pages are lists of lines (a string page is split at "\n"), every line
  -- shown; a page before the last waits for a press (`waiting`), the last is
  -- `done` (src/render/TextBox.lua:705-726); A or B turns the page or, on the
  -- last, pops the box and calls onDone (src/render/TextBox.lua:636-646). A box
  -- with a `choice` pushes the yes/no over itself once it is done
  -- (src/render/TextBox.lua:622-634).
  E.TextBox = {}
  E.TextBox.__index = E.TextBox
  function E.TextBox.new(pages, onDone, opts)
    local norm = {}
    for i, page in ipairs(pages or { "TEST LINE" }) do
      if type(page) == "table" then
        norm[i] = page
      else
        local lines = {}
        for line in (page .. "\n"):gmatch("(.-)\n") do lines[#lines + 1] = line end
        norm[i] = lines
      end
    end
    local s = setmetatable({ pages = norm, pageIndex = 1, onDone = onDone }, E.TextBox)
    for k, v in pairs(opts or {}) do s[k] = v end
    s:showPage()
    return s
  end
  function E.TextBox:showPage()
    local page = self.pages[self.pageIndex]
    self.lineIndex = #page
    self.shown = {} -- the rows in the box: the page's last two lines
    for i = math.max(1, #page - 1), #page do self.shown[#self.shown + 1] = page[i] end
    self.done = self.pageIndex >= #self.pages
    self.waiting = not self.done
  end
  function E.TextBox:update()
    local g = E.game
    if self.done and self.choice then
      if not self.choicePushed then
        self.choicePushed = true
        g.stack:push(E.ChoiceBox.new(function(yes)
          g.stack:pop() -- this text box, under the choice
          self.choice(yes)
        end))
      end
      return
    end
    if not (g.input:wasPressed("a") or g.input:wasPressed("b")) then return end
    if not self.done then
      self.pageIndex = self.pageIndex + 1
      self:showPage()
      return
    end
    g.stack:pop()
    if self.onDone then self.onDone() end
  end
  -- the yes/no: index 1 YES / 2 NO, up/down flip it, A answers by the cursor,
  -- B answers NO; it pops itself and calls onChoose(yes) (src/ui/ChoiceBox.lua:17-70)
  E.ChoiceBox = {}
  E.ChoiceBox.__index = E.ChoiceBox
  function E.ChoiceBox.new(onChoose)
    return setmetatable({ index = 1, labels = { "YES", "NO" }, onChoose = onChoose }, E.ChoiceBox)
  end
  function E.ChoiceBox:update()
    local input = E.game.input
    if input:wasPressed("up") or input:wasPressed("down") then
      self.index = self.index == 1 and 2 or 1
    elseif input:wasPressed("a") or input:wasPressed("b") then
      local yes = input:wasPressed("a") and self.index == 1
      E.game.stack:pop()
      if self.onChoose then self.onChoose(yes) end
    end
  end

  -- ---- Gen 2's own question screens (src/ui/gen2/*.lua), pushed through the
  -- Screens registry with their screenIds (src/ui/Screens.lua:40-96, :174): the
  -- fields the adapter reads and the keys each answers. Timings a test may
  -- set (frames; 0, the default, answers at once): E.genderTyping (before the
  -- gender menu opens), E.genderExitFrames (after a choice), E.namePickSlide
  -- (the name pick's pic sliding in, and out after a preset).
  E.genderTyping, E.genderExitFrames, E.namePickSlide = 0, 0, 0

  -- src/core/Strings.lua:211-215: Strings(text) is the catalog's translation,
  -- the text itself without one (E.translations: text -> translation)
  E.translations = {}
  E.Strings = setmetatable({ source = function(text) return text end },
                           { __call = function(_, text) return E.translations[text] or text end })

  -- the host's clock (E.hostMinutes, E.hostWeekday; src/core/gen2/Clock.lua:74-87)
  -- and the offsets setTime and setWeekday store against it (src/core/gen2/Clock.lua:125-143)
  E.hostMinutes, E.hostWeekday = 10 * 60 + 15, 3
  E.Clock = {}
  function E.Clock.hostMinutes() return E.hostMinutes end
  function E.Clock.hostWeekday() return E.hostWeekday end
  function E.Clock.setTime(save, hour, minute)
    save.rtc = save.rtc or {}
    save.rtc.startMinute = ((hour % 24) * 60 + minute % 60 - E.hostMinutes) % 1440
    return true
  end
  function E.Clock.setWeekday(save, day)
    save.rtc = save.rtc or {}
    save.rtc.startDay = (day - E.hostWeekday) % 7
    save.rtc.dayOfWeek = day % 7
    return true
  end

  -- src/ui/gen2/GenderSelect.lua: OPTIONS (:20-23), the cursor on the first
  -- (src/ui/gen2/GenderSelect.lua:67), the question typed above the menu
  -- (src/ui/gen2/GenderSelect.lua:70-74, :108-111 menuOpen), up/down, A or
  -- START chooses (the save's gender), B does nothing, the exit frames, then
  -- onDone(gender) (src/ui/gen2/GenderSelect.lua:89-105, :113-146)
  E.GenderSelect = { OPTIONS = { { label = "TEST BOY", gender = "male" }, { label = "TEST GIRL", gender = "female" } } }
  E.GenderSelect.__index = E.GenderSelect
  function E.GenderSelect.new(opts)
    opts = opts or {}
    return setmetatable({ screenId = "Gen2GenderSelect", save = E.game.save, onDone = opts.onDone, cursor = 1,
                          exit = nil, text = "TEST QUESTION\nOF TWO LINES", typing = E.genderTyping },
                        E.GenderSelect)
  end
  function E.GenderSelect:menuOpen() return self.typing <= 0 end
  function E.GenderSelect:update()
    if self.exit then
      self.exit = self.exit - 1
      if self.exit > 0 then return end
      self.exit = nil
      if self.onDone then self.onDone(self.chosen) end
      return
    end
    if self.chosen then return end
    if not self:menuOpen() then
      self.typing = self.typing - 1
      return
    end
    local input = E.game.input
    if input:wasPressed("up") or input:wasPressed("down") then
      self.cursor = self.cursor == 1 and 2 or 1
    elseif input:wasPressed("a") or input:wasPressed("start") then
      local option = E.GenderSelect.OPTIONS[self.cursor]
      if self.save and self.save.player then self.save.player.gender = option.gender end
      self.chosen = option.gender
      if E.genderExitFrames > 0 then
        self.exit = E.genderExitFrames
      elseif self.onDone then
        self.onDone(self.chosen)
      end
    end
  end

  -- src/ui/gen2/NamingScreen.lua: TYPES (prompt, maxLength; :91-98), the text,
  -- the case, the cursor (col 0-8, row 0 to bottomRow), rows() for the case
  -- showing and a box's extra row (src/ui/gen2/NamingScreen.lua:226-246), the
  -- bottom row's case / DEL / END targets (src/ui/gen2/NamingScreen.lua:256-271);
  -- A types the cell or works the target, B deletes, START parks the cursor
  -- on END, SELECT flips the case (src/ui/gen2/NamingScreen.lua:336-395); a
  -- glyph goes in only while the text is shorter than maxLength in bytes
  -- (src/ui/gen2/NamingScreen.lua:288-292)
  local NamingScreen = {}
  NamingScreen.__index = NamingScreen
  E.NamingScreen = NamingScreen
  NamingScreen.TYPES = {
    player = { prompt = "TEST NAME?", maxLength = 7 }, rival = { prompt = "TEST RIVAL?", maxLength = 7 },
    box = { prompt = "TEST BOX?", maxLength = 8, isBox = true }, nickname = { prompt = nil, maxLength = 10 },
  }
  local KB_UPPER = { { "A", "B", "C", "D", "E", "F", "G", "H", "I" }, { "J", "K", "L", "M", "N", "O", "P", "Q", "R" },
                     { "S", "T", "U", "V", "W", "X", "Y", "Z", " " } }
  local KB_LOWER = { { "a", "b", "c", "d", "e", "f", "g", "h", "i" }, { "j", "k", "l", "m", "n", "o", "p", "q", "r" },
                     { "s", "t", "u", "v", "w", "x", "y", "z", "<PK>" } }
  local KB_BOX = { "1", "2", "3", "4", "5", "6", "7", "8", "9" }
  E.keyboards = 0 -- keyboards opened
  function NamingScreen.new(opts)
    opts = opts or {}
    E.keyboards = E.keyboards + 1
    local kind = NamingScreen.TYPES[opts.type or "player"] or NamingScreen.TYPES.player
    return setmetatable({ screenId = "Gen2NamingScreen", game = E.game, kind = kind, isBox = kind.isBox or false,
                          maxLength = opts.maxLength or kind.maxLength, prompt = opts.prompt or kind.prompt
                          or "TEST NICK?", onDone = opts.onDone, lower = false, text = opts.initial or "", col = 0,
                          row = 0 }, NamingScreen)
  end
  function NamingScreen:rows()
    local base = self.lower and KB_LOWER or KB_UPPER
    if not self.isBox then return base end
    return { base[1], base[2], base[3], KB_BOX }
  end
  function NamingScreen:bottomRow() return #self:rows() end
  function NamingScreen:accept() if self.onDone then self.onDone(self.text) end end
  function NamingScreen:update()
    local input = E.game.input
    if input:wasPressed("start") then
      self.row, self.col = self:bottomRow(), 6
    elseif input:wasPressed("select") then
      self.lower = not self.lower
    elseif input:wasPressed("b") then
      self.text = self.text:sub(1, #self.text - 1)
    elseif input:wasPressed("a") then
      if self.row == self:bottomRow() then
        if self.col < 3 then
          self.lower = not self.lower
        elseif self.col < 6 then
          self.text = self.text:sub(1, #self.text - 1)
        else
          self:accept()
        end
        return
      end
      local line = self:rows()[self.row + 1]
      local ch = line and line[self.col + 1]
      if ch and ch ~= "" and #self.text < self.maxLength then self.text = self.text .. ch end
    end
  end
  -- a keyboard of its own (a nickname, the rival, a box): onDone pops it and
  -- records the name (E.named)
  E.named = {}
  function E.openNaming(opts)
    opts = opts or {}
    local s = NamingScreen.new({ type = opts.type, maxLength = opts.maxLength, initial = opts.initial,
                                 onDone = function(name)
                                   E.game.stack:pop()
                                   E.named[#E.named + 1] = name
                                 end })
    E.game.stack:push(s)
    return s
  end

  -- src/ui/gen2/NamePick.lua: items NEW NAME then the presets (:77-89), the
  -- cursor on NEW NAME, the pic sliding in and, after a preset, out (input
  -- waits for it; src/ui/gen2/NamePick.lua:141-158), up/down go round; A or
  -- START on NEW NAME opens the keyboard (a player's; an empty name is the
  -- first preset, src/ui/gen2/NamePick.lua:112-138), on a preset slides out and
  -- answers it; B does nothing (src/ui/gen2/NamePick.lua:161-184)
  local NamePick = {}
  NamePick.__index = NamePick
  E.NamePick = NamePick
  function NamePick.new(opts)
    opts = opts or {}
    local items = { "NEW NAME" }
    for _, name in ipairs(opts.presets or { "TESTA", "TESTB", "TESTC" }) do items[#items + 1] = name end
    local s = setmetatable({ screenId = "Gen2NamePick", game = E.game, items = items, cursor = 1, onDone = opts.onDone,
                             slide = nil, slideLeft = 0 }, NamePick)
    if E.namePickSlide > 0 then s.slide, s.slideLeft = "in", E.namePickSlide end
    return s
  end
  function NamePick:choose(name) if self.onDone then self.onDone(name) end end
  function NamePick:openNaming()
    local pick = self
    E.game.stack:push(NamingScreen.new({ type = "player", onDone = function(name)
      E.game.stack:pop() -- the naming screen
      if name and #name > 0 then pick:choose(name) else pick:choose(pick.items[2]) end
    end }))
  end
  function NamePick:update()
    if self.slide then
      self.slideLeft = self.slideLeft - 1
      if self.slideLeft > 0 then return end
      local out = self.slide == "out"
      self.slide = nil
      if out then self:choose(self.pendingName) end
      return
    end
    local input = E.game.input
    if input:wasPressed("up") then
      self.cursor = self.cursor > 1 and self.cursor - 1 or #self.items
    elseif input:wasPressed("down") then
      self.cursor = self.cursor < #self.items and self.cursor + 1 or 1
    elseif input:wasPressed("a") or input:wasPressed("start") then
      if self.cursor == 1 then return self:openNaming() end
      self.pendingName = self.items[self.cursor]
      if E.namePickSlide > 0 then
        self.slide, self.slideLeft = "out", E.namePickSlide
      else
        self:choose(self.pendingName)
      end
    end
  end

  -- src/ui/gen2/InitClock.lua: the clock (mode "clock": the intro page, hour,
  -- its YES, minute, its YES, Oak's answer) or Mom's day wheel (mode "day": the
  -- day, its YES), every phase walked by A (src/ui/gen2/InitClock.lua:293-320);
  -- autoConfirm walks them on its own (src/ui/gen2/InitClock.lua:333-341);
  -- finish stores the answer through Clock and calls onDone
  -- (src/ui/gen2/InitClock.lua:276-289). E.openInitClock records the answers
  -- (E.clockAnswers).
  local InitClock = {}
  InitClock.__index = InitClock
  E.InitClock = InitClock
  local CLOCK_NEXT = { intro = "hour", hour = "confirm-hour", ["confirm-hour"] = "minute",
                       minute = "confirm-minute", ["confirm-minute"] = "response", day = "confirm-day" }
  function InitClock.new(opts)
    opts = opts or {}
    local mode = opts.mode == "day" and "day" or "clock"
    return setmetatable({ screenId = "Gen2InitClock", mode = mode, save = E.game.save, onDone = opts.onDone,
                          autoConfirm = opts.autoConfirm or false, hour = 10, minute = 0, day = 0,
                          phase = mode == "day" and "day" or "intro" }, InitClock)
  end
  function InitClock:finish()
    if self.mode == "day" then
      E.Clock.setWeekday(self.save, self.day)
      if self.onDone then self.onDone(self.day) end
      return
    end
    E.Clock.setTime(self.save, self.hour, self.minute)
    if self.onDone then self.onDone(self.hour, self.minute) end
  end
  function InitClock:accept()
    if self.phase == "response" or self.phase == "confirm-day" then return self:finish() end
    self.phase = CLOCK_NEXT[self.phase]
  end
  function InitClock:update()
    if self.autoConfirm then
      self:accept()
      return
    end
    if E.game.input:wasPressed("a") then self:accept() end
  end
  E.clockAnswers = {}
  function E.openInitClock(mode)
    local s = InitClock.new({ mode = mode, onDone = function(a, b)
      E.game.stack:pop()
      E.clockAnswers[#E.clockAnswers + 1] = { a, b }
    end })
    E.game.stack:push(s)
    return s
  end

  -- src/ui/gen2/ScriptMenu.lua: items (labels), rows x cols, the cursor's row
  -- and col (1-based), wrap (:99-166); A finishes with (row-1)*cols+col, B with
  -- 0 unless the header disables it (src/ui/gen2/ScriptMenu.lua:90-92, :214-255);
  -- the answer is recorded (E.menuAnswers)
  local ScriptMenu = {}
  ScriptMenu.__index = ScriptMenu
  E.menuAnswers = {}
  function E.openScriptMenu(items, opts)
    opts = opts or {}
    local cols = opts.cols or 1
    local s = setmetatable({ screenId = "Gen2ScriptMenu", items = items, cols = cols,
                             rows = math.ceil(#items / cols), row = 1, col = 1, wrap = opts.wrap or false,
                             disableB = opts.disableB or false, done = false }, ScriptMenu)
    E.game.stack:push(s)
    return s
  end
  function ScriptMenu:finish(index)
    if self.done then return end
    self.done = true
    E.game.stack:pop()
    E.menuAnswers[#E.menuAnswers + 1] = index
  end
  function ScriptMenu:update()
    if self.done then return end
    local input = E.game.input
    if input:wasPressed("a") then
      self:finish((self.row - 1) * self.cols + self.col)
    elseif input:wasPressed("b") and not self.disableB then
      self:finish(0)
    end
  end

  -- src/ui/gen2/StartMenu.lua: items { label, value } (:197-231) in a wrapping
  -- Chrome.List (index, scroll, rows, ensureVisible; src/ui/gen2/Chrome.lua:522-575);
  -- QUIT asks first (phase "confirm", src/ui/gen2/StartMenu.lua:247-270); START
  -- or B closes it, A opens the row's screen (E.startChoices records the row)
  local StartMenu = {}
  StartMenu.__index = StartMenu
  local List = {}
  List.__index = List
  function List:ensureVisible()
    if self.index <= self.scroll then
      self.scroll = self.index - 1
    elseif self.index > self.scroll + self.rows then
      self.scroll = self.index - self.rows
    end
    self.scroll = math.max(0, math.min(self.scroll, #self.items - self.rows))
  end
  E.startChoices = {}
  function E.openStartMenu(values)
    local items = {}
    for _, v in ipairs(values or { "pokedex", "pokemon", "pack", "status", "save", "option", "mods", "quit" }) do
      items[#items + 1] = { label = "TEST " .. v:upper(), value = v }
    end
    local s = setmetatable({ screenId = "Gen2StartMenu", items = items, phase = nil }, StartMenu)
    s.list = setmetatable({ items = items, index = 1, scroll = 0, rows = math.min(#items, 8), wrap = true }, List)
    E.game.stack:push(s)
    return s
  end
  function StartMenu:update()
    local input = E.game.input
    if self.phase == "confirm" then
      if input:wasPressed("a") or input:wasPressed("b") then self.phase = nil end
      return
    end
    if input:wasPressed("start") or input:wasPressed("b") then
      E.game.stack:pop()
    elseif input:wasPressed("a") then
      local item = self.items[self.list.index]
      if item.value == "quit" then
        self.phase = "confirm"
        return
      end
      E.startChoices[#E.startChoices + 1] = item.value
      E.pushScreen(item.value == "pack" and "Gen2PackMenu" or "Gen2TestMenu")
    end
  end

  -- src/ui/gen2/CallerBox.lua: the caller's strip, pushed raw (no screenId)
  -- under a call's text (src/script/gen2/CallAsm.lua:257-269)
  E.CallerBox = {}
  E.CallerBox.__index = E.CallerBox
  function E.openCall(pages)
    local box = setmetatable({ name = "TEST CALLER", isOpaque = false }, E.CallerBox)
    E.game.stack:push(box)
    local text = E.TextBox.new(pages or { "TEST RING" })
    E.game.stack:push(text)
    return box, text
  end

  -- src/world/gen2/FieldMoves.lua:136-150 (the badges' order)
  E.FieldMoves = {
    JOHTO_BADGES = { "ZEPHYR", "HIVE", "PLAIN", "FOG", "MINERAL", "STORM", "GLACIER", "RISING" },
    KANTO_BADGES = { "BOULDER", "CASCADE", "THUNDER", "RAINBOW", "SOUL", "MARSH", "VOLCANO", "EARTH" },
  }
  -- src/world/gen2/UnionCenter2F.lua:222-224 (E.unionApplied: the option on)
  E.UnionCenter2F = {}
  function E.UnionCenter2F.isApplied(maps) return E.unionApplied == true and maps ~= nil end

  -- The cell predicates the observer asks about forced movement (src/world/gen2/Permissions.lua:111,
  -- :135-160): ice (0x23 and its alias 0x2b), the currents 0x38-0x3b, no door pushes on the fake.
  E.Permissions = {}
  function E.Permissions.isIce(c) return c == 0x23 or c == 0x2b end
  function E.Permissions.currentDirection(c)
    return ({ [0x38] = "right", [0x39] = "left", [0x3a] = "up", [0x3b] = "down" })[c]
  end
  function E.Permissions.doorForcedDirection() return nil end

  -- ---- maps (src/world/gen2/Map.lua): made-up records in data.gen2Maps (blocks
  -- of 2x2 cells), the COLL bytes per cell in E.colls[map][x,y] (0x00 floor)

  E.maps = {}
  E.colls = {}
  function E.newMap(id, w, h, o)
    o = o or {}
    E.maps[id] = { id = id, width = w, height = h, blocks = {}, borderBlock = 1, tileset = "TILESET_TEST",
                   environment = o.environment or "TOWN", palette = o.palette or "PALETTE_AUTO", warps = {},
                   objects = {}, connections = {} }
    E.colls[id] = {}
    for i = 1, w * h do E.maps[id].blocks[i] = 1 end
    return E.maps[id]
  end
  E.newMap("TEST_TOWN", 10, 10)
  E.newMap("TEST_HOUSE", 5, 4, { environment = "INDOOR", palette = "PALETTE_DAY" })
  E.newMap("TEST_HOUSE_2F", 4, 4, { environment = "INDOOR", palette = "PALETTE_DAY" })
  E.newMap("TEST_CAVE", 8, 8, { environment = "CAVE", palette = "PALETTE_DARK" })
  E.newMap("TEST_CENTER", 5, 4, { environment = "INDOOR", palette = "PALETTE_DAY" })
  E.newGameStart = { map = "TEST_HOUSE_2F", x = 3, y = 3, facing = "down" } -- World.lua:1468-1519

  function E.coll(map, x, y, byte) E.colls[map][cellKey(x, y)] = byte end
  -- a walked warp (x, y in cells; the destination's cell given directly here)
  function E.addWarp(map, x, y, dest, destX, destY, byte)
    local w = E.maps[map].warps
    w[#w + 1] = { x = x, y = y, destMap = dest, destWarp = 1, destX = destX, destY = destY }
    E.coll(map, x, y, byte or 0x71)
  end

  local Map = {}
  Map.__index = Map
  function Map.new(def)
    return setmetatable({ id = def.id, def = def, width = def.width, height = def.height,
                          widthCells = def.width * 2, heightCells = def.height * 2, blocks = def.blocks,
                          borderBlock = def.borderBlock }, Map)
  end
  function Map:inBounds(cx, cy) -- :41-44
    return cx >= 0 and cy >= 0 and cx < self.widthCells and cy < self.heightCells
  end
  function Map:cellCollision(cx, cy) -- :55-63 (0xff off the map)
    if not self:inBounds(cx, cy) then return 0xff end
    return (E.colls[self.id] or {})[cellKey(cx, cy)] or 0x00
  end
  function Map:isWalkable(cx, cy) -- :65-68
    return self:inBounds(cx, cy) and WALKABLE[self:cellCollision(cx, cy)] == true
  end
  function Map:warpAt(cx, cy) -- :113-117
    for _, w in ipairs(self.def.warps or {}) do
      if w.x == cx and w.y == cy then return w end
    end
    return nil
  end
  function Map:isCounterCell(cx, cy) return self:cellCollision(cx, cy) == 0x90 end -- :123
  function Map:blockId(bx, by) -- :46-51
    if bx < 0 or by < 0 or bx >= self.width or by >= self.height then return self.borderBlock end
    return self.blocks[by * self.width + bx + 1] or 0
  end
  E.Map = Map

  -- ---- the player (src/world/gen2/Player.lua): fields (:34-58), tryMove (:97-136:
  -- a different facing turns first when armed, 4 frames; the verdict goes
  -- through movement.collision; "edge" off the map), scriptFace (:143-145)

  local Player = {}
  Player.__index = Player
  function Player.new(cx, cy, facing)
    return setmetatable({ cellX = cx, cellY = cy, facing = facing or "down", moving = false, targetX = cx,
                          targetY = cy, progress = 0, stepFrames = WALK_FRAMES, jumping = false, turnTimer = 0,
                          turnArmed = true }, Player)
  end
  function Player:scriptFace(dir) if dir then self.facing = dir end end
  local function passthrough(allowed) return allowed end
  function Player:tryMove(dir, map, world)
    E.tryMoves[#E.tryMoves + 1] = { dir = dir, mover = "player" }
    if self.moving then return nil end
    if self.facing ~= dir then
      self.facing = dir
      self.bumpFrames = nil
      if self.turnArmed then
        self.turnArmed = false
        self.turnTimer = TURN_FRAMES
        return "turned"
      end
    end
    if self.turnTimer > 0 then return nil end
    local d = DELTA[dir]
    local tx, ty = self.cellX + d[1], self.cellY + d[2]
    local allowed, why = true, nil
    if not map:inBounds(tx, ty) then
      allowed, why = false, "bounds"
    elseif not map:isWalkable(tx, ty) then
      allowed, why = false, "tile"
    elseif world:npcAt(tx, ty) then
      allowed, why = false, "entity"
    end
    local ctx = { map = map, mover = self, dir = dir, fromX = self.cellX, fromY = self.cellY, toX = tx, toY = ty,
                  reason = why }
    allowed = callHook("movement.collision", passthrough, allowed, ctx)
    if not allowed then
      self.bumpFrames = 1
      return ctx.reason == "bounds" and "edge" or "blocked"
    end
    self.targetX, self.targetY, self.moving, self.progress, self.bumpFrames = tx, ty, true, 0, nil
    return "moved"
  end
  E.Player = Player

  -- ---- the event flags (src/world/gen2/Events.lua:11-58): byte -> bits; set
  -- emits flag.changed { name = number, value } on a real transition
  local Events = {}
  Events.__index = Events
  function Events.new(flags) return setmetatable({ flags = flags or {} }, Events) end
  function Events:get(id)
    if not id or id < 0 then return false end
    local row = self.flags[math.floor(id / 8)] or 0
    return math.floor(row / (2 ^ (id % 8))) % 2 == 1
  end
  function Events:set(id, value)
    local before = self:get(id)
    local byte, mask = math.floor(id / 8), 2 ^ (id % 8)
    local row = self.flags[byte] or 0
    if value and not before then self.flags[byte] = row + mask end
    if not value and before then self.flags[byte] = row - mask end
    if before ~= (value and true or false) then emit("flag.changed", { name = id, value = value and true or false }) end
  end
  E.Events = Events

  -- ---- the World (src/world/gen2/World.lua): the overworld of one map, its
  -- player and people, rebuilt on every CONTINUE (Game2.lua:347-348)

  local World = {}
  World.__index = World
  E.World = World
  function World.new(mapId, cx, cy, facing, save)
    local w = setmetatable({ game = E.game, npcs = {}, blockEdits = {}, mapSetup = nil, pendingWarp = nil,
                             battleActive = nil, moveState = nil, waitButton = nil, playerState = "normal",
                             flashUsed = false, pendingSceneScript = false, vmRunning = false, clockHour = 12,
                             heldDir = nil }, World)
    w.events = Events.new(type(save) == "table" and type(save.events) == "table" and save.events.flags or nil)
    w.player = Player.new(cx, cy, facing)
    w:setMap(mapId, cx, cy, facing)
    return w
  end
  function World:setMap(mapId, cx, cy, facing)
    self.map = Map.new(E.maps[mapId])
    self.player.cellX, self.player.cellY, self.player.targetX, self.player.targetY = cx, cy, cx, cy
    self.player.moving, self.player.progress = false, 0
    if facing then self.player.facing = facing end
    self.npcs = {}
    for _, n in ipairs(E.npcDefs[mapId] or {}) do
      self.npcs[#self.npcs + 1] = { def = n.def, id = mapId .. "_obj_" .. n.def.index, mapId = mapId, cellX = n.x,
                                   cellY = n.y, facing = n.facing or "down", moving = false, targetX = n.x,
                                   targetY = n.y, progress = 0 }
    end
    self.blockEdits = {}
  end
  function World:npcAt(cx, cy)
    for _, n in ipairs(self.npcs) do
      if n.cellX == cx and n.cellY == cy then return n end
    end
    return nil
  end
  function World:scriptRunning() return self.vmRunning == true end -- :1527-1529
  function World:busy() -- :1531-1565
    return self.vmRunning or self.mapSetup ~= nil or self.moveState ~= nil or self.waitButton ~= nil
  end
  function World:acceptsMenuInput() -- :1567-1578
    if self.battleActive or self:busy() then return false end
    if self.player.moving then return false end
    return true
  end
  function World:downhill() return E.downhill == true end
  -- `waitbutton` under a pokepic (:4398-4423): armed on one tick, answered by
  -- the next A or B; polled by World:step (:11346-11348)
  function World:waitForButton(done) self.waitButton = { done = done, fresh = true } end
  function World:pollWaitButton()
    local wb = self.waitButton
    if not wb then return end
    if wb.fresh then
      wb.fresh = false
      return
    end
    local input = E.game.input
    if not (input:wasPressed("a") or input:wasPressed("b")) then return end
    self.waitButton = nil
    if wb.done then wb.done() end
  end
  function World:playerCollision() return self.map:cellCollision(self.player.cellX, self.player.cellY) end -- :5247-5251
  function World:hour() return self.clockHour end
  function World:restoreMapMusic() E.musicCalls[#E.musicCalls + 1] = { op = "restoreMapMusic" } end -- :2760
  function World:pollInput(input) -- :11553-11557: directions are read with isDown only
    self.heldDir = nil
    for _, d in ipairs(DIR_ORDER) do
      if input:isDown(d) then self.heldDir = d break end
    end
  end
  -- A full map load (setMap: map.exited, then map.entered; a same-map warp too, :10134-10354)
  function World:loadMap(mapId, cx, cy, facing, via)
    local from = self.map and self.map.id or nil
    if from then emit("map.exited", { mapId = from, toMapId = mapId }) end
    self:setMap(mapId, cx, cy, facing)
    emit("map.entered", { mapId = mapId, map = self.map, fromMapId = from, via = via or "warp" })
  end
  -- A walked warp (takeWarp, :10473-10547): warp.destination, player.warped, the load
  function World:takeWarp(w)
    local function vanilla(map, x, y) return map, x, y end
    local map, x, y = callHook("warp.destination", vanilla, w.destMap, w.destX or 1, w.destY or 1,
      { warp = w, lastMap = self.map.id, destWarp = w.destWarp })
    if not E.maps[map] then return end
    emit("player.warped", { fromMap = self.map.id, toMap = map, x = x, y = y, warp = w, toWarp = w.destWarp })
    self:loadMap(map, x, y, nil, "warp")
  end
  -- A step under way advances and lands (world.stepped, :11470-11481, with the
  -- cell's COLL byte; a walked warp); a held direction starts the next step in
  -- the same update (:11452, :11541); a tick with no direction held while idle
  -- re-arms the turn (:11530-11535).
  function World:movePlayer(dir)
    local r = self.player:tryMove(dir, self.map, self)
    return r
  end
  function World:step()
    E.worldSteps = E.worldSteps + 1
    if self.waitButton then self:pollWaitButton() end
    local p = self.player
    if p.turnTimer > 0 then p.turnTimer = p.turnTimer - 1 end
    if p.moving then
      p.progress = p.progress + 1
      if p.progress >= p.stepFrames then
        p.cellX, p.cellY, p.moving, p.progress = p.targetX, p.targetY, false, 0
        local tile = self.map:cellCollision(p.cellX, p.cellY)
        emit("world.stepped", { mapId = self.map.id, x = p.cellX, y = p.cellY, tile = tile, tod = "DAY",
                                daytime = "DAY" })
        local w = self.map:warpAt(p.cellX, p.cellY)
        if w and WARP[tile] then
          self:takeWarp(w)
          return
        end
        if self.heldDir then self:movePlayer(self.heldDir) end
      end
      return
    end
    if self:busy() then return end
    if self.heldDir then
      self:movePlayer(self.heldDir)
    else
      p.turnArmed = true
    end
  end
  -- The A press (World:interact, :8649-8676): world.interacted on the faced
  -- cell, before its script; an NPC answers with a text box.
  function World:interact()
    if self.waitButton then return end -- the press is the wait's
    local p = self.player
    local d = DELTA[p.facing]
    local x, y = p.cellX + d[1], p.cellY + d[2]
    local n = self:npcAt(x, y)
    if not n and self.map:isCounterCell(x, y) then
      x, y = x + d[1], y + d[2]
      n = self:npcAt(x, y)
    end
    local kind = n and (n.def.trainer and "trainer" or "npc") or "none"
    emit("world.interacted", { mapId = self.map.id, x = x, y = y, kind = kind, target = n })
    if n then E.game.stack:push(E.TextBox.new({ "TEST TALK" })) end
  end

  -- People: E.npcDefs[map] = list of { def, x, y, facing } (the map's object
  -- defs, RomExtractorGen2.lua:1321-1350: index, sprite, movement, type,
  -- trainer, itemball, eventFlag)
  E.npcDefs = {}
  function E.addNpc(index, o)
    o = o or {}
    local map = o.map or (E.game.world and E.game.world.map.id) or "TEST_TOWN"
    E.npcDefs[map] = E.npcDefs[map] or {}
    local def = { index = index, sprite = o.sprite or "SPRITE_TEST_YOUNGSTER", spriteId = 1,
                  movement = o.movement or 0x05, type = o.type or 0, trainer = o.trainer, itemball = o.itemball }
    local rec = { def = def, x = o.x, y = o.y, facing = o.facing }
    table.insert(E.npcDefs[map], rec)
    local w = E.game.world
    if w and w.map.id == map then
      w.npcs[#w.npcs + 1] = { def = def, id = map .. "_obj_" .. index, mapId = map, cellX = o.x, cellY = o.y,
                              facing = o.facing or "down", moving = false, targetX = o.x, targetY = o.y,
                              progress = 0, hidden = o.hidden }
    end
    return def
  end
  -- An NPC's step: movement.collision with the NPC as mover (Npc.lua:521-535)
  function E.walkNpc(index, dir)
    local w = E.game.world
    for _, n in ipairs(w.npcs) do
      if n.def.index == index then
        local d = DELTA[dir]
        local ctx = { map = w.map, mover = n, dir = dir, fromX = n.cellX, fromY = n.cellY, toX = n.cellX + d[1],
                      toY = n.cellY + d[2], reason = nil }
        local ok = callHook("movement.collision", passthrough, true, ctx)
        if ok then n.cellX, n.cellY = ctx.toX, ctx.toY end
        return ok
      end
    end
    return nil
  end

  function E.setFlag(id, on)
    local w = E.game.world
    if w then
      w.events:set(id, on ~= false)
    else
      local s = E.game.save
      s.events = s.events or { flags = {} }
      local ev = Events.new(s.events.flags)
      ev:set(id, on ~= false)
      s.events.flags = ev.flags
    end
  end

  -- ---- the Game2 object (src/core/Game2.lua)

  local Game2 = {}
  Game2.__index = Game2
  E.Game2 = Game2

  -- the state stack (src/core/StateStack.lua:21-49): screen.pushed/popped after enter/exit
  local function newStack()
    local stack = { states = {} }
    function stack:push(state)
      table.insert(self.states, state)
      emit("screen.pushed", { state = state })
    end
    function stack:pop()
      local state = table.remove(self.states)
      if state then emit("screen.popped", { state = state }) end
      return state
    end
    function stack:top() return self.states[#self.states] end
    function stack:clear() while self:top() do self:pop() end end
    return stack
  end

  E.game = setmetatable({ generation = 2, input = newInput(), stack = newStack(), phase = "boot", world = nil,
                          save = nil, data = { gen2Maps = E.maps } }, Game2)
  E.game.save = E.newSave() -- the boot skeleton (Game2.lua:1254)
  E.data = E.game.data

  -- The title screen while nothing else is up (Gen2TitleState, a Screens record)
  function E.titleScreen()
    E.game.stack:push({ screenId = "Gen2TitleState", update = function() end })
  end
  E.titleScreen()

  -- The field up: a World on the map, the play phase, map.entered (Game2:startWorld,
  -- :262-290; CONTINUE's load, :312-358)
  function E.enterWorld(mapId, x, y, facing, via)
    local g = E.game
    g.stack:clear()
    g.world = World.new(mapId, x, y, facing, g.save)
    g.phase = "play"
    emit("map.entered", { mapId = mapId, map = g.world.map, fromMapId = nil, via = via or "boot" })
    return g.world
  end
  -- back to the title (returnToTitle's result, :374-381)
  function E.leaveWorld()
    local g = E.game
    g.world, g.phase = nil, "boot"
    g.stack:clear()
    E.titleScreen()
  end

  -- Oak's speech (src/ui/gen2/OakSpeech.lua): the step list through
  -- intro.oak_speech.build (:255-270), Crystal's gender first (:247-251); each
  -- step a state on the stack over the speech: the gender screen (:460-493,
  -- the version's default name laid down), the clock (:426-455), Oak's lines
  -- (shared text boxes), the name pick (:495-520, its presets E.namePresets);
  -- a `fn` step run in place (:686-692); the field at the end (startWorld,
  -- Game2.lua:277-290)
  E.speechRuns = {}
  E.namePresets = { "TESTA", "TESTB", "TESTC" }
  local function oakSpeech()
    local g = E.game
    local steps = {
      { id = "init_clock", kind = "initclock" }, { id = "oak_welcome", kind = "say" },
      { id = "ask_player_name", kind = "say" }, { id = "name_player", kind = "name", saveKey = "name" },
      { id = "legend", kind = "say" },
    }
    if E.GameVersion.current == "crystal" then table.insert(steps, 1, { id = "gender_select", kind = "gender" }) end
    local speech = { screenId = "Gen2OakSpeech", game = g, answers = {} }
    function speech.recordAnswer(sp, step, index, label, value) sp.answers[step.saveKey or step.id] = value end
    local list = callHook("intro.oak_speech.build", function(s) return s end, steps, speech)
    speech.steps = type(list) == "table" and list or steps
    E.speechRuns[#E.speechRuns + 1] = speech
    local i = 0
    local function advance()
      i = i + 1
      local step = speech.steps[i]
      if not step then
        g.stack:pop() -- the speech
        local st = E.newGameStart
        g.world = World.new(st.map, st.x, st.y, st.facing, g.save)
        g.phase = "play"
        emit("map.entered", { mapId = st.map, map = g.world.map, fromMapId = nil, via = "boot" })
        return
      end
      local state
      if step.kind == "fn" then
        step.run(speech, advance)
        return
      elseif step.kind == "gender" then
        state = E.GenderSelect.new({ onDone = function(gender)
          g.stack:pop()
          g.save.player.name = gender == "female" and "TESTKRIS" or "TESTCHRIS"
          speech:recordAnswer(step, gender == "female" and 2 or 1, gender, gender)
          advance()
        end })
      elseif step.kind == "initclock" then
        state = InitClock.new({ mode = "clock", onDone = function()
          g.stack:pop()
          advance()
        end })
      elseif step.kind == "name" then
        state = NamePick.new({ presets = E.namePresets, onDone = function(name)
          g.save.player.name = name
          g.stack:pop() -- NamePick
          speech:recordAnswer(step, 1, name, name)
          advance()
        end })
      else
        state = E.TextBox.new({ "TEST SPEECH" }, advance)
      end
      g.stack:push(state)
    end
    function speech.update() end
    g.stack:push(speech)
    advance()
  end

  -- :292-310: a fresh save (save.created), then Oak's speech
  function Game2:newGame()
    E.newGames[#E.newGames + 1] = true
    if E.newGameFails then error("fake newGame failed") end
    self.stack:clear()
    self.save = E.newSave()
    emit("save.created", { save = self.save })
    oakSpeech()
  end
  -- :312-358: nil starts a NEW GAME; else save.loading, a new World,
  -- map.entered{via="continue"}, save.loaded
  function Game2:continueGame(save)
    E.continues[#E.continues + 1] = save ~= nil
    if save == nil then return self:newGame() end
    if E.continueFails then error("fake continueGame failed") end
    emit("save.loading", { raw = save })
    self.stack:clear()
    self.save = save
    local pos = type(save.position) == "table" and save.position or { map = "TEST_HOUSE_2F", x = 3, y = 3, facing = "down" }
    self.world = World.new(pos.map, pos.x, pos.y, pos.facing, save)
    self.phase = "play"
    emit("map.entered", { mapId = pos.map, map = self.world.map, fromMapId = nil, via = "continue" })
    emit("save.loaded", { save = save, meta = save.meta })
  end
  -- :374-381
  function Game2:returnToTitle()
    E.titleCalls[#E.titleCalls + 1] = true
    E.leaveWorld()
  end
  -- :1022-1042: the save.write veto, the snapshot into the save, save.writing, the file
  function Game2:writeSave()
    E.saveCalls[#E.saveCalls + 1] = { phase = self.phase }
    if E.saveResult == "refuse" then return false end
    if E.saveResult == "error" then error("fake writeSave failed") end
    local s = self.save
    local w = self.world
    if w then
      s.position = { map = w.map.id, x = w.player.cellX, y = w.player.cellY, facing = w.player.facing }
      s.events = { flags = deepCopy(w.events.flags) }
    end
    s.meta = { savedAt = 1700000000 + #E.saveCalls, sessionStart = 1700000000, engine = E.Version.engine }
    emit("save.writing", { save = s, meta = s.meta })
    local slot = E.activeSlot or "flat"
    E.slotSaves[slot] = deepCopy(s)
    if E.activeSlot then E.slotFiles[E.activeSlot] = true end
    return true
  end
  -- :1044-1050: the World's own START gate
  function Game2:quickSaveAllowed()
    local w = self.world
    if self.phase ~= "play" or not w then return false end
    if not w:acceptsMenuInput() then return false end
    return E.quickSaveOk ~= false
  end

  -- ---- screens a test opens

  function E.openTextBox(pages, onDone) local s = E.TextBox.new(pages, onDone) E.game.stack:push(s) return s end
  function E.openChoice(cb) local s = E.ChoiceBox.new(cb) E.game.stack:push(s) return s end
  -- a question: its text box, and the yes/no it pushes once its last page is up
  function E.openYesNo(pages, cb) local s = E.TextBox.new(pages, nil, { choice = cb }) E.game.stack:push(s) return s end
  function E.pushScreen(id) local s = { screenId = id, update = function() end } E.game.stack:push(s) return s end

  -- ---- mod.world, the Gen 2 WorldAPI (src/world/gen2/WorldAPI.lua): current
  -- (:114-120), warpTo (:398-411: a known map, a script warp's load, no
  -- player.warped), getFlag/setFlag (:452-475: numbers only), replaceBlock
  -- (:477-484: block units, the active map, world.block_replaced), queueScript
  -- (:636-730: the allowlist, refused before the first row otherwise)

  E.worldApi = {}
  local api = E.worldApi
  function api.overworld(_) return E.game.world end
  function api.current(_)
    local w = E.game.world
    if not w then return nil, "no overworld" end
    return { mapId = w.map.id, x = w.player.cellX, y = w.player.cellY, facing = w.player.facing }
  end
  function api.warpTo(_, map, x, y, facing)
    E.warps[#E.warps + 1] = { map = map, x = x, y = y, facing = facing }
    local w = E.game.world
    if not w then return nil, "no overworld" end
    if E.refuseWarp then return nil, "busy" end
    if type(map) ~= "string" or not E.maps[map] then return nil, "unknown map " .. tostring(map) end
    w:loadMap(map, x, y, facing, "warp")
    return true
  end
  function api.getFlag(_, id)
    if type(id) ~= "number" then return nil, "Gen 2 flags are numbers" end
    local w = E.game.world
    return w and w.events:get(id) or false
  end
  function api.setFlag(_, id, value)
    if type(id) ~= "number" then return nil, "Gen 2 flags are numbers" end
    local w = E.game.world
    if not w then return nil, "no overworld" end
    w.events:set(id, value ~= false)
    return true
  end
  function api.replaceBlock(_, bx, by, block)
    local w = E.game.world
    if not w then return nil, "no overworld" end
    E.replaced[#E.replaced + 1] = { map = w.map.id, bx = bx, by = by, block = block }
    emit("world.block_replaced", { mapId = w.map.id, bx = bx, by = by, block = block })
    return true
  end
  local ALLOWED = { start_battle = true, warp = true, text = true, setflag = true, clearflag = true }
  function api.queueScript(_, rows, extra)
    for _, row in ipairs(rows or {}) do
      if not ALLOWED[row[1]] then return nil, "unsupported script command " .. tostring(row[1]) end
    end
    E.queued[#E.queued + 1] = rows
    for _, row in ipairs(rows) do
      if row[1] == "warp" then
        local ok, err = api.warpTo(api, row[2], row[3], row[4], row[5])
        if not ok then return nil, err end
      end
    end
    if type(extra) == "table" and extra.onDone then extra.onDone(true) end
    return true
  end

  E.modules = {
    ["src.core.Version"] = E.Version,
    ["src.core.GameVersion"] = E.GameVersion,
    ["src.core.SaveData"] = E.SaveData,
    ["src.core.gen2.Save"] = E.Save,
    ["src.core.Music"] = E.Music,
    ["src.render.TextBox"] = E.TextBox,
    ["src.ui.ChoiceBox"] = E.ChoiceBox,
    ["src.world.gen2.World"] = E.World,
    ["src.world.gen2.Player"] = E.Player,
    ["src.world.gen2.Map"] = E.Map,
    ["src.world.gen2.FieldMoves"] = E.FieldMoves,
    ["src.world.gen2.UnionCenter2F"] = E.UnionCenter2F,
    ["src.world.gen2.Permissions"] = E.Permissions,
    ["src.core.Strings"] = E.Strings,
    ["src.core.gen2.Clock"] = E.Clock,
    ["src.ui.gen2.NamingScreen"] = E.NamingScreen,
    ["src.ui.gen2.GenderSelect"] = E.GenderSelect,
    ["src.ui.gen2.CallerBox"] = E.CallerBox,
  }
  -- no ModUI or dataset tables on this fake
  E.ui, E.datasets = nil, nil

  -- ---- one logic step (Game2's stepBody after the input.step hook,
  -- Game2.lua:1289-1348): Input:step; the stack's top state updates and the
  -- step ends there (:1314-1318); else, in the play phase with a World, the
  -- d-pad poll, A -> interact, World:step.
  function E.tick()
    E.ticks = E.ticks + 1
    local g = E.game
    g.input:step()
    local top = g.stack:top()
    if top then
      if type(top.update) == "function" then top.update(top) end
      return
    end
    if g.phase ~= "play" or not g.world then return end
    local w = g.world
    w:pollInput(g.input)
    if g.input:wasPressed("a") then w:interact() end
    if g.stack:top() then return end
    w:step()
  end

  -- Events and hooks go to the mod object main.lua runs on (FakeMod.emit/call).
  function E.bindMod(FakeMod, mod)
    emit = function(name, payload) FakeMod.emit(mod, name, payload) end
    callHook = function(name, vanilla, ...) return FakeMod.call(mod, name, vanilla, ...) end
  end

  return E
end

return FakeEngine2
