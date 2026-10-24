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
--   E.content                                 G2H: mod.content's maps and tilesets (writes land in the data)
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
    -- :301-333, made up for TILESET_TEST: { after, animation } (0 a tree, 1 the grass swirl)
    CUT_BLOCKS = { TILESET_TEST = { [0x40] = { 0x41, 0 }, [0x50] = { 0x51, 1 } } },
    WHIRLPOOL_BLOCKS = { TILESET_TEST = { [0x60] = { 0x61, 0 } } },
  }
  -- src/core/gen2/Decorations.lua: the actions (:42-63), made-up attribute rows (:113-170: the
  -- sprite byte is a block for the four maptile kinds), state (:247-256) and tiles (:504-527)
  E.Decorations = {
    ACTIONS = {
      SET_UP_BED = { slot = "bed" }, PUT_AWAY_BED = { slot = "bed", put = true },
      SET_UP_CARPET = { slot = "carpet" }, SET_UP_PLANT = { slot = "plant" },
      SET_UP_POSTER = { slot = "poster" }, SET_UP_DOLL = { ornament = true },
    },
    ATTRIBUTES = {
      [0] = { action = nil, sprite = 0 },
      [1] = { action = "PUT_AWAY_BED", sprite = 0 },
      [2] = { action = "SET_UP_BED", sprite = 0x1b },
      [3] = { action = "SET_UP_BED", sprite = 0x1c },
      [4] = { action = "SET_UP_CARPET", sprite = 0x08 },
      [5] = { action = "SET_UP_PLANT", sprite = 0x20 },
      [6] = { action = "SET_UP_POSTER", sprite = 0x1f },
      [7] = { action = "SET_UP_DOLL", sprite = 0x8e },
    },
  }
  function E.Decorations.state(save)
    if type(save) ~= "table" then return {} end
    if not save.decorations then save.decorations = { bed = 2, poster = 6 } end
    return save.decorations
  end
  function E.Decorations.tiles(state)
    local out = {}
    local function put(slot, bx, by)
      local attr = E.Decorations.ATTRIBUTES[state and state[slot]]
      if attr and attr.sprite and attr.sprite ~= 0 then
        out[#out + 1] = { x = bx, y = by, block = attr.sprite }
        return attr.sprite
      end
    end
    put("bed", 0, 2)
    put("plant", 3, 2)
    put("poster", 3, 0)
    local carpet = put("carpet", 0, 0)
    if carpet then
      out[#out + 1] = { x = 0, y = 1, block = carpet + 1 }
      out[#out + 1] = { x = 1, y = 1, block = carpet + 2 }
      out[#out + 1] = { x = 2, y = 1, block = carpet + 1 }
    end
    return out
  end
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
  -- data.gen2Scripts (RomExtractorGen2.lua:3911-4128): key -> decoded rows, made up by the tests
  E.scripts = {}
  function E.newMap(id, w, h, o)
    o = o or {}
    E.maps[id] = { id = id, width = w, height = h, blocks = {}, borderBlock = 1, tileset = "TILESET_TEST",
                   environment = o.environment or "TOWN", palette = o.palette or "PALETTE_AUTO", warps = {},
                   objects = {}, connections = {}, blockColl = o.blockColl, group = o.group, map = o.map }
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
    local set = (E.colls[self.id] or {})[cellKey(cx, cy)]
    if set then return set end
    -- G2H: a map made with { blockColl = true } reads its blocks' collision in its tileset, as the engine does
    if self.def.blockColl then
      local id = self:blockId(math.floor(cx / 2), math.floor(cy / 2))
      local ts = E.data and E.data.gen2Tilesets and E.data.gen2Tilesets[self.def.tileset]
      local quad = id ~= 0 and ts and ts.collision and ts.collision[id + 1] or nil
      return quad and quad[(cy % 2) * 2 + cx % 2 + 1] or 0xff
    end
    return 0x00
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
  -- Events:objectVisible (:75-79): an object with no flag (nil or 0xFFFF) always stands
  function Events:objectVisible(eventFlag)
    if eventFlag == nil or eventFlag == 0xFFFF then return true end
    return not self:get(eventFlag)
  end
  E.Events = Events

  -- ---- the World (src/world/gen2/World.lua): the overworld of one map, its
  -- player and people, rebuilt on every CONTINUE (Game2.lua:347-348)

  local World = {}
  World.__index = World
  E.World = World
  function World.new(mapId, cx, cy, facing, save)
    local w = setmetatable({ game = E.game, npcs = {}, blockEdits = {}, objectMasks = {}, maskScripted = {},
                             mapSetup = nil, pendingWarp = nil,
                             battleActive = nil, moveState = nil, waitButton = nil, playerState = "normal",
                             flashUsed = false, pendingSceneScript = false, vmRunning = false, clockHour = 12,
                             heldDir = nil }, World)
    w.events = Events.new(type(save) == "table" and type(save.events) == "table" and save.events.flags or nil)
    w.player = Player.new(cx, cy, facing)
    w:setMap(mapId, cx, cy, facing)
    return w
  end
  -- setMap (:10134-10354): the last visit's block edits put back (restoreBlocks, :10159), a new Map
  -- object (:10236), the object masks (loadObjectMasks), the unmasked people, then the map's
  -- MAPCALLBACK_TILES (E.tileCallbacks[map]: functions of the World, :10292)
  function World:setMap(mapId, cx, cy, facing)
    self:restoreBlocks()
    self.map = Map.new(E.maps[mapId])
    self.player.cellX, self.player.cellY, self.player.targetX, self.player.targetY = cx, cy, cx, cy
    self.player.moving, self.player.progress = false, 0
    if facing then self.player.facing = facing end
    self:loadObjectMasks()
    self:rebuildPeople()
    for _, cb in ipairs(E.tileCallbacks[mapId] or {}) do cb(self) end
  end
  -- the unmasked people of the map (rebuildPeople's result)
  function World:rebuildPeople()
    local mapId = self.map.id
    self.npcs = {}
    for _, n in ipairs(E.npcDefs[mapId] or {}) do
      if self.objectMasks[self:objectMaskKey(n.def, n.def.index)] ~= true then
        self.npcs[#self.npcs + 1] = { def = n.def, id = mapId .. "_obj_" .. n.def.index, mapId = mapId, cellX = n.x,
                                     cellY = n.y, facing = n.facing or "down", moving = false, targetX = n.x,
                                     targetY = n.y, progress = 0, hidden = n.hidden }
      end
    end
  end
  -- who stands where (:8146-8215): the clock's time of day, an object's hour window, the masks
  local MORN_MASK, DAY_MASK, NITE_MASK = 1, 2, 4
  function World:clockTimeMask()
    local hour = self:hour()
    if hour >= 4 and hour < 10 then return MORN_MASK end
    if hour >= 10 and hour < 18 then return DAY_MASK end
    return NITE_MASK
  end
  function World:objectTimeVisible(obj)
    local hours = obj and obj.hours
    if not hours then return true end
    local h1 = hours[1] or -1
    local h2 = hours[2] or -1
    if h1 == -1 then
      if h2 == -1 then return true end
      local mask = self:clockTimeMask()
      return h2 % (mask * 2) >= mask
    end
    if h1 == h2 then return true end
    local hour = self:hour()
    if h1 < h2 then return hour >= h1 and hour <= h2 end
    return hour >= h1 or hour <= h2
  end
  function World:objectMaskKey(obj, index)
    return string.format("%s:%d", self.map.id, (obj and obj.index) or index or 0)
  end
  function World:setObjectMask(obj, index, masked)
    local key = self:objectMaskKey(obj, index)
    self.objectMasks[key] = masked and true or false
    self.maskScripted[key] = true
  end
  function World:loadObjectMasks(opts)
    opts = opts or {}
    local scripted = (opts.keepScripted and self.maskScripted) or {}
    local masks = opts.keepScripted and (self.objectMasks or {}) or {}
    for index, obj in ipairs(self.map.def.objects or {}) do
      local key = self:objectMaskKey(obj, index)
      if not scripted[key] then
        masks[key] = not (self.events:objectVisible(obj.eventFlag) and self:objectTimeVisible(obj))
      end
    end
    self.objectMasks, self.maskScripted = masks, scripted
  end
  -- appear / disappear (:2003-2035, :4058-4090): objectId is the object's index + 1
  function World:appearObject(objectId)
    local obj = self.map.def.objects[objectId - 1]
    if not obj then return end
    if obj.eventFlag and obj.eventFlag ~= 0xFFFF then self.events:set(obj.eventFlag, false) end
    self:setObjectMask(obj, objectId - 1, false)
    self:rebuildPeople()
  end
  function World:disappearObject(objectId)
    local obj = self.map.def.objects[objectId - 1]
    if not obj then return end
    self:setObjectMask(obj, objectId - 1, true)
    if obj.eventFlag and obj.eventFlag ~= 0xFFFF then self.events:set(obj.eventFlag, true) end
    self:rebuildPeople()
  end
  -- the block edits (:2259-2335, :5844-5875, :5943-5958): the original of each edited block kept
  -- until the next load; world.block_replaced after each replaceBlock (changemapblocks raises none)
  function World:replaceBlock(index, blockId)
    local map = self.map
    local blocks = map.def.blocks
    if not blocks[index] then return false end
    local edits = self.blockEdits[map.id]
    if not edits then
      edits = {}
      self.blockEdits[map.id] = edits
    end
    if edits[index] == nil then edits[index] = blocks[index] end
    blocks[index] = blockId
    local zero = index - 1
    emit("world.block_replaced", { mapId = map.id, bx = zero % map.width, by = math.floor(zero / map.width),
                                   block = blockId, index = index })
    return true
  end
  function World:changeBlock(bx, by, blockId)
    local map = self.map
    if bx < 0 or by < 0 or bx >= map.width or by >= map.height then return false end
    return self:replaceBlock(by * map.width + bx + 1, blockId)
  end
  function World:changeMapBlocks(bank, address)
    local map = self.map
    local blocks = map.def.blocks
    local src, offset
    for _, d in pairs(E.maps) do
      local bd = d.blockdata
      if bd and bd.bank == bank and address >= bd.address and address < bd.address + #d.blocks then
        src, offset = d.blocks, address - bd.address
      end
    end
    local count = map.width * map.height
    if not src or offset + count > #src then return false end
    local run = {}
    for i = 1, count do run[i] = src[offset + i] end
    local edits = self.blockEdits[map.id] or {}
    self.blockEdits[map.id] = edits
    for i = 1, count do
      if edits[i] == nil then edits[i] = blocks[i] end
      blocks[i] = run[i]
    end
    return true
  end
  function World:restoreBlocks()
    for mapId, edits in pairs(self.blockEdits) do
      local blocks = E.maps[mapId] and E.maps[mapId].blocks
      if blocks then
        for index, original in pairs(edits) do blocks[index] = original end
      end
      self.blockEdits[mapId] = nil
    end
  end
  -- the bedroom's TILES callback (:7621-7631)
  function World:toggleMaptileDecorations()
    for _, t in ipairs(E.Decorations.tiles(E.Decorations.state(self.game.save))) do
      self:changeBlock(t.x, t.y, t.block)
    end
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
  E.tileCallbacks = {}
  function E.addNpc(index, o)
    o = o or {}
    local map = o.map or (E.game.world and E.game.world.map.id) or "TEST_TOWN"
    E.npcDefs[map] = E.npcDefs[map] or {}
    local def = { index = index, sprite = o.sprite or "SPRITE_TEST_YOUNGSTER", spriteId = 1,
                  movement = o.movement or 0x05, type = o.type or 0, trainer = o.trainer, itemball = o.itemball,
                  eventFlag = o.eventFlag, hours = o.hours, scriptKey = o.scriptKey, x = o.x, y = o.y }
    local rec = { def = def, x = o.x, y = o.y, facing = o.facing, hidden = o.hidden }
    table.insert(E.npcDefs[map], rec)
    if E.maps[map] then E.maps[map].objects[index] = def end
    local w = E.game.world
    if w and w.map.id == map then
      local key = w:objectMaskKey(def, index)
      w.objectMasks[key] = not (w.events:objectVisible(def.eventFlag) and w:objectTimeVisible(def))
      if not w.objectMasks[key] then
        w.npcs[#w.npcs + 1] = { def = def, id = map .. "_obj_" .. index, mapId = map, cellX = o.x, cellY = o.y,
                                facing = o.facing or "down", moving = false, targetX = o.x, targetY = o.y,
                                progress = 0, hidden = o.hidden }
      end
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
                          save = nil, data = { gen2Maps = E.maps, gen2Scripts = E.scripts } }, Game2)
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

  -- ---- G2F: Pokemon, moves, items and trainers (made up; the shapes of RomExtractorGen2.lua's records), and the
  -- modules the battle, party and bag parts use. Records: E.battleStarts (startBattle's opts), E.battleEnds (the
  -- outcome the engine's closure got: "nil" for an abort), E.blackouts, E.partyItemUses, E.fieldItemUses,
  -- E.fieldMoveCalls, E.pcScreens.
  E.battleStarts, E.battleEnds, E.blackouts, E.partyItemUses, E.fieldItemUses, E.fieldMoveCalls, E.pcScreens =
    {}, {}, 0, {}, {}, {}, {}
  local function stats(hp, atk, df, spd, spa, spdf)
    return { hp = hp, attack = atk, defense = df, speed = spd, specialAttack = spa, specialDefense = spdf }
  end
  E.data.pokemon = {
    TEST_CHIKORITA = { dex = 152, index = 152, name = "TEST CHIKORITA", baseStats = stats(45, 49, 65, 45, 49, 65),
                       growthRate = "MEDIUM_SLOW", genderRatio = 0x1F, types = { "GRASS" } },
    TEST_RATTATA = { dex = 19, index = 19, name = "TEST RATTATA", baseStats = stats(30, 56, 35, 72, 25, 35),
                     growthRate = "MEDIUM_FAST", genderRatio = 0x7F, types = { "NORMAL" } },
    TEST_MAGIKARP = { dex = 129, index = 129, name = "TEST MAGIKARP", baseStats = stats(20, 10, 55, 80, 15, 20),
                      growthRate = "SLOW", genderRatio = 0x7F, types = { "WATER" } },
    TEST_TOGEPI = { dex = 175, index = 175, name = "TEST TOGEPI", baseStats = stats(35, 20, 65, 20, 40, 65),
                    growthRate = "FAST", genderRatio = 0x1F, types = { "NORMAL" } },
    UNOWN = { dex = 201, index = 201, name = "TEST UNOWN", baseStats = stats(48, 72, 48, 48, 72, 48),
              growthRate = "MEDIUM_FAST", genderRatio = 0xFF, types = { "PSYCHIC" } },
  }
  E.data.moves = {
    TEST_TACKLE = { index = 33, pp = 35 }, TEST_GROWL = { index = 45, pp = 40 }, TEST_SPLASH = { index = 150, pp = 40 },
    TEST_CUT = { index = 15, pp = 30 }, TEST_SURF = { index = 57, pp = 15 }, TEST_HIDDEN = { index = 237, pp = 15 },
  }
  E.data.items = {
    POTION = { index = 18, pocket = "ITEM" }, POKE_BALL = { index = 5, pocket = "BALL" },
    BERRY = { index = 173, pocket = "ITEM" }, RARE_CANDY = { index = 32, pocket = "ITEM" },
    MOON_STONE = { index = 8, pocket = "ITEM" }, ESCAPE_ROPE = { index = 19, pocket = "ITEM" },
    BICYCLE = { index = 7, pocket = "KEY_ITEM" }, OLD_ROD = { index = 58, pocket = "KEY_ITEM" },
    TM_DYNAMICPUNCH = { index = 191, pocket = "TM_HM", teaches = "TEST_TACKLE" },
    HM_CUT = { index = 243, pocket = "TM_HM", teaches = "TEST_CUT" },
    ETHER = { index = 60, pocket = "ITEM" }, ELIXER = { index = 62, pocket = "ITEM" }, HP_UP = { index = 26, pocket = "ITEM" },
  }
  E.data.trainers = { classes = {
    YOUNGSTER = { index = 3, id = "YOUNGSTER", name = "TEST YOUNGSTER", baseMoney = 4, trainers = {
      { id = "JOEY1", index = 1, name = "TEST JOEY", party = { { level = 4, species = "TEST_RATTATA" } } },
      { id = "MIKEY", index = 2, name = "TEST MIKEY", party = { { level = 2, species = "TEST_RATTATA" },
                                                                { level = 4, species = "TEST_CHIKORITA", item = "BERRY" } } } } },
    RIVAL1 = { index = 9, id = "RIVAL1", name = "TEST RIVAL", baseMoney = 20, trainers = {
      { id = "RIVAL1_1_CHIKORITA", index = 1, name = "?", party = { { level = 5, species = "TEST_CHIKORITA" } } } } },
  } }

  -- src/battle/gen2/Mon.lua: DVs (:150-178), stats (:180-200; a plain made-up formula), experience (level^3),
  -- Gen 2's shininess pattern (:616-624), Mon.new (:513-570), stampOT (:572-585)
  local Mon = {}
  E.Mon = Mon
  E.nextDVs = nil -- a test may set the DVs the next Mon.new rolls
  function Mon.randomDVs()
    local d = E.nextDVs or { attack = 7, defense = 7, speed = 7, special = 7 }
    E.nextDVs = nil
    return { attack = d.attack, defense = d.defense, speed = d.speed, special = d.special }
  end
  function Mon.hpDV(d)
    return (d.attack % 2) * 8 + (d.defense % 2) * 4 + (d.speed % 2) * 2 + d.special % 2
  end
  function Mon.stats(base, dvs, level, _statExp)
    local function v(b0, dv) return math.floor(((b0 or 1) * 2 + (dv or 0) * 2) * level / 100) + 5 end
    return { hp = math.floor(((base.hp or 1) * 2 + Mon.hpDV(dvs) * 2) * level / 100) + level + 10,
             attack = v(base.attack, dvs.attack), defense = v(base.defense, dvs.defense), speed = v(base.speed, dvs.speed),
             specialAttack = v(base.specialAttack, dvs.special), specialDefense = v(base.specialDefense, dvs.special) }
  end
  function Mon.growthFor(_data, curve) return curve or "MEDIUM_FAST" end
  function Mon.experienceForLevel(_growth, level) return level * level * level end
  function Mon.vanillaShiny(dvs)
    if not dvs or dvs.speed ~= 10 or dvs.defense ~= 10 or dvs.special ~= 10 then return false end
    return (dvs.attack or 0) % 4 == 2 or (dvs.attack or 0) % 4 == 3
  end
  function Mon.new(data, species, level, opts)
    opts = opts or {}
    local def = data and data.pokemon and data.pokemon[species]
    if not def then return nil end
    local dvs = opts.dvs or Mon.randomDVs()
    dvs.hp = Mon.hpDV(dvs)
    local st = Mon.stats(def.baseStats, dvs, level)
    local moves = opts.moves
    if not moves then
      moves = { { id = "TEST_TACKLE", pp = 35, maxPp = 35 } }
    end
    return { species = species, name = def.name, nickname = opts.nickname, level = level,
             experience = Mon.experienceForLevel(nil, level), dvs = dvs, statExp = { hp = 0, attack = 0, defense = 0,
             speed = 0, special = 0 }, stats = st, hp = opts.hp or st.hp, maxHp = st.hp, types = def.types, moves = moves,
             item = opts.item, happiness = opts.happiness or 70, shiny = opts.shiny or Mon.vanillaShiny(dvs),
             gender = E.Gender.of(def.genderRatio, dvs), unownLetter = species == "UNOWN" and E.Unown.letterFromDVs(dvs) or nil }
  end
  -- Mon.syncIdentity (:219-241: name and types from the species) and Mon.refreshStats (:347-363: the stats rebuilt
  -- from the species' base stats, then syncIdentity), as the engine runs them on every party menu and summary
  function Mon.syncIdentity(mon, data)
    local def = data and data.pokemon and data.pokemon[mon.species]
    if not def then return mon end
    mon.name = def.name or mon.species
    if def.types then mon.types = def.types end
    mon.gender = E.Gender.of(def.genderRatio, mon.dvs)
    return mon
  end
  function Mon.refreshStats(mon, data)
    local def = data and data.pokemon and data.pokemon[mon.species]
    if not (def and def.baseStats) then return mon end
    Mon.syncIdentity(mon, data)
    local st = Mon.stats(def.baseStats, mon.dvs or {}, mon.level or 1)
    mon.stats, mon.maxHp = st, st.hp
    if mon.hp == nil or mon.hp > st.hp then mon.hp = st.hp end
    return mon
  end
  function Mon.stampOT(save, mon)
    local player = save and save.player
    if not (mon and player) then return mon end
    mon.ot = mon.ot or player.name
    mon.otName = mon.otName or mon.ot
    if not mon.traded then mon.otId = mon.otId or player.id end
    return mon
  end
  -- src/core/gen2/Gender.lua:5-12; src/core/gen2/Unown.lua:66-81
  E.Gender = {}
  function E.Gender.of(ratio, dvs)
    if not ratio or ratio == 0xff then return "unknown" end
    if ratio == 0 then return "male" end
    if ratio == 0xfe then return "female" end
    local value = (dvs and dvs.attack or 0) * 16 + (dvs and dvs.speed or 0)
    return value <= ratio and "female" or "male"
  end
  E.Unown = { SPECIES = "UNOWN" }
  local function middle(dv) return math.floor((dv or 0) / 2) % 4 end
  function E.Unown.letterFromDVs(dvs)
    if not dvs then return nil end
    local packed = middle(dvs.attack) * 64 + middle(dvs.defense) * 16 + middle(dvs.speed) * 4 + middle(dvs.special)
    return math.floor(packed / 10) + 1
  end

  -- src/battle/gen2/Prize.lua:184-228 (made up: the base times the last row's level, all to the wallet)
  E.Prize = {}
  function E.Prize.award(save, opts)
    local total = (opts.baseMoney or 0) * (opts.level or 1)
    save.player.money = (save.player.money or 0) + total
    return { total = total, mode = 0, toMom = 0 }
  end
  function E.Prize.message(award, name) return ("TEST %s GOT %d"):format(name or "PLAYER", award and award.total or 0) end

  -- the battle model (src/battle/gen2/Battle.lua:300-399 setup, :457-472 endBattle, :3792-3810 the prize)
  function E.newBattleModel(opts)
    local m = { wild = opts.wild ~= nil, enemy = opts.wild, trainer = opts.trainer, battleType = opts.battleType,
                roaming = opts.roaming, save = E.game.save }
    m.enemyParty = opts.wild and { opts.wild } or (opts.trainer and opts.trainer.party) or {}
    function m.endBattle(self, outcome)
      self.over, self.outcome = true, outcome
      if self.endedEmitted then return end
      self.endedEmitted = true
      emit("battle.ended", { battle = self, result = outcome })
    end
    function m.awardPrizeMoney(self)
      local last = self.enemyParty[#self.enemyParty]
      self.prize = E.Prize.award(self.save, { baseMoney = self.trainer and self.trainer.baseMoney,
                                              level = last and last.level or 1 })
      return self.prize
    end
    return m
  end

  -- src/ui/Screens.lua:191-195 (a builtin by its module's `new`, the screenId stamped), the native battle screen
  -- (src/ui/gen2/BattleState.lua: a test ends it with E.finishNative)
  E.Screens = {}
  E.BattleState = {}
  function E.BattleState.new(_g, opts)
    return { native = true, battle = opts.battle, onDone = opts.onDone, contest = opts.contest,
             tutorial = opts.tutorial, update = function() end }
  end
  function E.Screens.push(g, id, opts)
    local inst
    if id == "Gen2BattleState" then
      inst = E.BattleState.new(g, opts)
    else
      inst = { update = function() end, opts = opts }
    end
    inst.screenId = inst.screenId or id
    g.stack:push(inst)
    return inst
  end
  function E.finishNative(outcome)
    local top = E.game.stack:top()
    if type(top) == "table" and top.native and top.onDone then top.onDone(outcome, top.battle) end
  end

  -- World:pushBattleTransition (:6931-6943: the wipe takes the screen, then onDone) and World:startBattle
  -- (:6957-7142: the model, the battle screen through Screens, the after-battle closure: pop, the whiteout on a
  -- loss but CANLOSE's, then the caller's onDone)
  function World:pushBattleTransition(_battle, _opts, onDone)
    local g = E.game
    if not (g and g.stack and self.map) then return false end
    g.stack:push({ screenId = "Gen2BattleTransition", update = function()
      g.stack:pop()
      onDone()
    end })
    return true
  end
  function World:startBattle(opts, onDone)
    local g = E.game
    E.battleStarts[#E.battleStarts + 1] = opts
    local battle = E.newBattleModel(opts)
    local function pushBattle()
      self.battleActive = true
      E.Screens.push(g, "Gen2BattleState", { battle = battle, save = g.save, contest = opts.contest,
                                             tutorial = opts.tutorial, onDone = function(outcome)
        self.battleActive = nil
        g.stack:pop()
        E.battleEnds[#E.battleEnds + 1] = outcome == nil and "nil" or outcome
        if outcome == "lose" and opts.battleType ~= 1 then
          g.save.player.money = math.floor((g.save.player.money or 0) / 2)
          E.blackouts = E.blackouts + 1
          emit("world.blacked_out", { save = g.save })
        end
        if onDone then onDone(outcome) end
      end })
    end
    if not self:pushBattleTransition(battle, opts, pushBattle) then pushBattle() end
    return true
  end
  -- startScriptedBattle (:7165-7262): a trainer record by class and member, or a scripted wild. The engine's
  -- form takes the record Trainers.lookup made (G2H: the zone battles; wBattleType from scriptVars[3]); the
  -- tests' older form names the class and member.
  function World:startScriptedBattle(classId, member, onDone, battleType)
    if type(classId) == "table" then
      local rec, done = classId, onDone
      local party = E.Trainers.party(E.data, rec)
      if #party == 0 then
        if done then done(nil) end
        return false
      end
      local bt = self.scriptVars and self.scriptVars[3] or nil
      if self.scriptVars then self.scriptVars[3] = nil end
      return self:startBattle({ trainer = { class = rec.class, classId = rec.classId, memberId = rec.id,
                                            name = rec.className .. " " .. tostring(rec.name), party = party,
                                            baseMoney = rec.baseMoney, winText = "TEST WIN TEXT",
                                            lossText = "TEST LOSS TEXT" }, battleType = bt }, done)
    end
    local cls = E.data.trainers.classes[classId]
    local row = cls and cls.trainers[member]
    if not row then
      if onDone then onDone(nil) end
      return false
    end
    local party = {}
    for _, r in ipairs(row.party) do
      party[#party + 1] = Mon.new(E.data, r.species, r.level, { item = r.item,
                                                                dvs = { attack = 9, defense = 8, speed = 8, special = 8 } })
    end
    local name = cls.name .. " " .. row.name
    if classId == "RIVAL1" then name = (E.game.save.rival or {}).name or "???" end
    return self:startBattle({ trainer = { class = cls.index, classId = classId, memberId = row.id, name = name,
                                          party = party, baseMoney = cls.baseMoney, winText = "TEST WIN TEXT",
                                          lossText = "TEST LOSS TEXT" }, battleType = battleType }, onDone)
  end
  -- a step encounter (tryWildEncounter, :4498-4588): the encounter.roll hook, then the battle
  function E.wildEncounter(species, level, terrain, opts)
    opts = opts or {}
    local w = E.game.world
    local function vanilla(_tables, _ctx) return { species = species, level = level } end
    local roll = callHook("encounter.roll", vanilla, {}, { terrain = terrain or "grass", kind = opts.kind or "wild",
                                                           mapId = w.map.id, daytime = "DAY" })
    if not roll then return false end
    local mon = Mon.new(E.data, roll.species, roll.level, { dvs = opts.dvs })
    w:startBattle({ wild = mon, battleType = opts.battleType, contest = opts.contest, tutorial = opts.tutorial,
                    party = opts.party })
    return true
  end
  function World:fieldContext() return { save = E.game.save, data = E.data } end
  -- the party menu's field move (:6315-6331): E.fieldMoveResult or a success
  function World:useFieldMove(moveId, mon)
    E.fieldMoveCalls[#E.fieldMoveCalls + 1] = { move = moveId, mon = mon }
    return E.fieldMoveResult or { ok = true, action = moveId }
  end
  -- the PACK's field items (:4908-4925): E.fieldItemResults[id]
  function World:useFieldItem(itemId)
    E.fieldItemUses[#E.fieldItemUses + 1] = itemId
    return (E.fieldItemResults or {})[itemId]
  end
  E.FieldMoves.isSurfing = function(state) return state == "surf" end
  E.FieldMoves.partyMoveUser = function(party, moveId)
    for _, mon in ipairs(party or {}) do
      for _, mv in ipairs(mon.moves or {}) do
        if mv.id == moveId then return mon end
      end
    end
    return nil
  end

  -- givepoke through the script.command hook (Vm.lua:616-637 inside Vm.lua:1829-1851): the op appends a new
  -- member (Party.add, the OT stamped)
  function E.givePoke(species, level, opts)
    opts = opts or {}
    local function vanilla(_ctx, _name, _args, _cmd)
      local save = E.game.save
      local mon = Mon.new(E.data, species, level, { item = opts.item, dvs = opts.dvs })
      Mon.stampOT(save, mon)
      if #save.party < 6 then save.party[#save.party + 1] = mon end
      return nil
    end
    return callHook("script.command", vanilla, { vm = {} }, "givepoke", {}, { op = "givepoke" })
  end
  -- giveegg (World:giveEgg, :2883-2905)
  function E.giveEgg(species, level)
    local save = E.game.save
    local mon = Mon.new(E.data, species, level or 5)
    mon.isEgg, mon.nickname, mon.hp = true, "EGG", 0
    save.party[#save.party + 1] = mon
    return mon
  end

  -- src/core/gen2/NpcTrade.lua:140-174: the given member out, the received one on the end
  E.NpcTrade = {}
  function E.NpcTrade.perform(data, save, row, index)
    local party = save and save.party
    local given = party and party[index]
    if not (data and given and row) then return nil end
    local received = Mon.new(data, row.get, given.level, { nickname = row.nickname })
    received.ot, received.otName, received.otId = "TEST OT", "TEST OT", 7
    table.remove(party, index)
    party[#party + 1] = received
    return given, received
  end

  -- src/core/gen2/Breeding.lua: dayCare (:686-696), side (:698-702), deposit (:783-794), withdraw (:864-918:
  -- the record rebuilt at the new level, the price paid), collectEgg (:1048-1064), hatch (:1087-1148)
  E.Breeding = {}
  function E.Breeding.dayCare(save)
    save.dayCare = save.dayCare or {}
    save.dayCare.man = save.dayCare.man or {}
    save.dayCare.lady = save.dayCare.lady or {}
    return save.dayCare
  end
  function E.Breeding.side(save, which) return E.Breeding.dayCare(save)[which == "lady" and "lady" or "man"] end
  function E.Breeding.deposit(_data, save, which, partyIndex)
    local slot = E.Breeding.side(save, which)
    local mon = table.remove(save.party, partyIndex)
    slot.mon = mon
    return true, mon
  end
  function E.Breeding.withdraw(data, save, which)
    local slot = E.Breeding.side(save, which)
    local mon = slot.mon
    if not mon then return false, "no_mon" end
    local level = mon.level + 1
    local rebuilt = Mon.new(data, mon.species, level, { dvs = mon.dvs, moves = mon.moves, nickname = mon.nickname })
    rebuilt.ot, rebuilt.otId = mon.ot, mon.otId
    save.player.money = math.max(0, save.player.money - 100)
    save.party[#save.party + 1] = rebuilt
    slot.mon = nil
    return true, rebuilt, 100
  end
  function E.Breeding.hatch(data, save, index, nickname)
    local egg = save.party[index]
    if not (egg and egg.isEgg) then return nil end
    local hatched = Mon.new(data, egg.species, egg.level, { dvs = egg.dvs, nickname = nickname, happiness = 120 })
    hatched.ot, hatched.otId = save.player.name, save.player.id
    save.party[index] = hatched
    return hatched, { species = egg.species }
  end
  -- src/core/gen2/Boxes.lua:49-56
  E.Boxes = { MONS_PER_BOX = 20 }
  function E.Boxes.box(save, index)
    index = index or save.currentBox or 1
    save.boxes = save.boxes or {}
    save.boxes[index] = save.boxes[index] or {}
    return save.boxes[index]
  end

  -- src/inventory/Bag.lua (Gen 1's, shared): add (all or nothing: 99 a stack, 20 ITEM slots), remove, capacity,
  -- slots (the ITEM pocket's stacks)
  E.Bag = {}
  local function pocketOf(id)
    local rec = E.data.items[id]
    return rec and rec.pocket or "ITEM"
  end
  function E.Bag.capacity(_data) return 20 end
  function E.Bag.slots(save)
    local n = 0
    for id, qty in pairs(save.inventory or {}) do
      if pocketOf(id) == "ITEM" and qty > 0 then n = n + math.ceil(qty / 99) end
    end
    return n
  end
  function E.Bag.add(save, id, qty)
    save.inventory = save.inventory or {}
    local have = save.inventory[id] or 0
    if pocketOf(id) == "ITEM" and have == 0 and E.Bag.slots(save) >= 20 then return false end
    save.inventory[id] = have + qty
    return true
  end
  function E.Bag.remove(save, id, qty)
    save.inventory = save.inventory or {}
    local left = (save.inventory[id] or 0) - qty
    save.inventory[id] = left > 0 and left or nil
    return true
  end

  -- Game2's say (:940-943) and the item uses the bag part guards (:686-905)
  function Game2:say(text, onDone)
    self.stack:push(E.TextBox.new({ type(text) == "table" and (text[1] or "TEST") or text }, onDone))
  end
  -- FP4: src/core/gen2/ItemEffects.lua's two entry points (:410, :441) for the items the tests use: POTION
  -- (heal: 20 HP on a hurt member), HP_UP (vitamin), ETHER (pp: 10 on the slot), ELIXER (pp: every move),
  -- RARE_CANDY (candy: one level), MOON_STONE (stone: TEST_TOGEPI evolves); { used, text } as the
  -- engine returns them
  E.ItemEffects = {}
  local ACTIONS = { POTION = "heal", HP_UP = "vitamin", ETHER = "pp", ELIXER = "pp", RARE_CANDY = "candy",
                    MOON_STONE = "stone" }
  function E.ItemEffects.partyAction(itemId) return ACTIONS[itemId] end
  function E.ItemEffects.useOnMon(itemId, mon, _data)
    local action = ACTIONS[itemId]
    if not mon or mon.isEgg or action == nil or action == "pp" then return { used = false, text = "NO EFFECT" } end
    if action == "heal" then
      if (mon.hp or 0) <= 0 or mon.hp >= (mon.maxHp or mon.hp) then return { used = false, text = "NO EFFECT" } end
      mon.hp = math.min(mon.maxHp, mon.hp + 20)
      return { used = true, text = "HEALED" }
    elseif action == "vitamin" then
      return { used = true, text = "STAT UP" }
    elseif action == "candy" then
      if (mon.level or 1) >= 100 then return { used = false, text = "NO EFFECT" } end
      mon.level = mon.level + 1
      return { used = true, text = "LEVEL UP", learned = {} }
    elseif action == "stone" then
      if mon.species ~= "TEST_TOGEPI" then return { used = false, text = "NO EFFECT" } end
      return { used = true, evolution = { species = "TEST_CHIKORITA" } }
    end
    return { used = false, text = "NO EFFECT" }
  end
  function E.ItemEffects.usePpItem(itemId, mon, slot, _data)
    if not mon or ACTIONS[itemId] ~= "pp" then return { used = false, text = "NO EFFECT" } end
    if itemId == "ELIXER" then return { used = true, text = "PP RESTORED" } end
    local mv = mon.moves and mon.moves[slot]
    if not mv then return { used = false, text = "NO EFFECT" } end
    mv.pp = (mv.pp or 0) + 10
    return { used = true, text = "PP RESTORED" }
  end
  E.forgetSlot = 1
  -- Game2:usePartyItem (:802-905) on the member E.itemSlot (default 1), its move E.itemMoveSlot for the ETHER
  -- family: the effect, the item spent on a success (consumeItem, :730-734), the text said; the evolution scene
  -- of a used stone and the level-up's moves are recorded, not drawn (E.partyItemUses: { item, used, scene })
  function Game2:consumeItem(itemId)
    if not (self.save and self.save.inventory) then return end
    local left = (self.save.inventory[itemId] or 1) - 1
    self.save.inventory[itemId] = left > 0 and left or nil
  end
  function Game2:usePartyItem(itemId)
    local IE = E.modules["src.core.gen2.ItemEffects"]
    local action = IE.partyAction(itemId, self.data)
    local mon = self.save.party[E.itemSlot or 1]
    local r
    if action == "pp" then
      local slot = E.itemMoveSlot or 1
      if itemId == "ELIXER" then slot = nil end
      r = IE.usePpItem(itemId, mon, slot, self.data)
    else
      r = IE.useOnMon(itemId, mon, self.data)
    end
    local use = { item = itemId, used = r.used == true }
    if r.used then
      if action == "stone" then
        use.scene = "evolution"
        mon.species = r.evolution.species
      end
      self:consumeItem(itemId)
    end
    E.partyItemUses[#E.partyItemUses + 1] = use
    self:say(r.text or "")
  end
  -- Game2:useFieldItem (:686-725): a TM/HM's party menu; the pick (a test calls the pushed screen's
  -- opts.onChoose) checks the species' tmhm and the known moves, then learnMoveOn
  function Game2:useFieldItem(itemId)
    local def = self.data.items and self.data.items[itemId]
    local moveId = def and def.teaches
    if not moveId then return self:usePartyItem(itemId) end
    E.fieldItemUses[#E.fieldItemUses + 1] = itemId
    E.modules["src.ui.Screens"].push(self, "Gen2PartyMenu", {
      prompt = "choose",
      onChoose = function(_index, mon)
        self.stack:pop()
        local species = self.data.pokemon[mon.species]
        local allowed = false
        for _, id in ipairs(species and species.tmhm or {}) do if id == moveId then allowed = true end end
        if not allowed then return self:say("CAN'T LEARN") end
        for _, mv in ipairs(mon.moves or {}) do if mv.id == moveId then return self:say("KNOWS IT") end end
        self:learnMoveOn(mon, moveId, function(learned)
          if not learned then return end
          if tostring(itemId):sub(1, 3) ~= "HM_" then self:consumeItem(itemId) end
        end)
      end,
    })
  end
  -- Game2:learnMoveOn: a free slot takes it; with four, the move in E.forgetSlot goes (E.declineLearn: no)
  function Game2:learnMoveOn(mon, moveId, onDone)
    if E.declineLearn then return onDone(false) end
    local row = { id = moveId, pp = 10, maxPp = 10 }
    if #mon.moves < 4 then mon.moves[#mon.moves + 1] = row else mon.moves[E.forgetSlot] = row end
    onDone(true)
  end

  -- src/ui/gen2/CenterPcMenu.lua:217-232: BILL's row says its line, then pushes the box screens
  local CenterPcMenu = {}
  CenterPcMenu.__index = CenterPcMenu
  E.CenterPcMenu = CenterPcMenu
  function CenterPcMenu:say(pages, onDone) E.game.stack:push(E.TextBox.new(pages, onDone)) end
  function CenterPcMenu:choose()
    local entry = self.entries[self.index]
    if entry and entry.id == "bills" then
      self:say({ "TEST BILLS PC" }, function()
        E.pcScreens[#E.pcScreens + 1] = "Gen2PcMenu"
        E.Screens.push(E.game, "Gen2PcMenu", {})
      end)
    end
  end
  function CenterPcMenu:update()
    if E.game.input:wasPressed("a") then self:choose() end
  end
  function E.openCenterPc()
    local s = setmetatable({ screenId = "Gen2CenterPcMenu", index = 1,
                             entries = { { id = "bills" }, { id = "players" }, { id = "oaks" }, { id = "turnoff" } } },
                           CenterPcMenu)
    E.game.stack:push(s)
    return s
  end

  -- ---- G2H: a Wild Routes end (lib/engine/gen2/routes.lua) and the blackout. mod.content's maps and tilesets
  -- over data.gen2Maps and data.gen2Tilesets (src/mods/Schemas.lua:1928-1977: the registry writes into the live
  -- tables; register refuses an id that exists, a patch replaces lists wholesale), the trainer record
  -- (src/world/gen2/Trainers.lua:40-91), the whiteout's pieces (World.lua:7084-7105: healParty, HalveMoney,
  -- GetWhiteoutSpawn, world.blacked_out, the warp to the spawn point as a MAPSETUP_WARP load).
  -- Switches: E.refuseFullMaps (a map record carrying callbacks is refused: the slim one goes in), E.rejectMaps
  -- (every maps write throws), E.rejectPatch, E.bugContest, E.spawn ({ map, x, y }). Records: E.contentWrites,
  -- E.calledAsm, E.mapSetups, E.heals.
  E.contentWrites, E.calledAsm, E.mapSetups, E.heals = {}, {}, {}, 0
  local function registryOver(name, tableOf)
    local r = {}
    function r.get(_, id)
      local t = tableOf()
      return t and t[id] ~= nil and deepCopy(t[id]) or nil
    end
    function r.register(_, id, rec)
      if name == "maps" and E.rejectMaps then error("maps: schema violation", 0) end
      if name == "maps" and E.refuseFullMaps and type(rec) == "table" and rec.callbacks ~= nil then
        error("maps: callbacks is not a field", 0)
      end
      local t = tableOf()
      if t[id] ~= nil then error(name .. ": " .. tostring(id) .. " is taken", 0) end
      t[id] = deepCopy(rec)
      if name == "maps" then E.colls[id] = E.colls[id] or {} end
      E.contentWrites[#E.contentWrites + 1] = { registry = name, op = "register", id = id }
      return true
    end
    function r.patch(_, id, fields)
      if name == "maps" and (E.rejectMaps or E.rejectPatch) then error("maps: schema violation", 0) end
      local t = tableOf()
      if t[id] == nil then error(name .. ": no " .. tostring(id), 0) end
      for k, v in pairs(fields) do t[id][k] = deepCopy(v) end
      E.contentWrites[#E.contentWrites + 1] = { registry = name, op = "patch", id = id }
      return true
    end
    function r.remove(_, id)
      local t = tableOf()
      t[id] = nil
      E.contentWrites[#E.contentWrites + 1] = { registry = name, op = "remove", id = id }
      return true
    end
    return r
  end
  E.content = {
    maps = registryOver("maps", function() return E.maps end),
    tilesets = registryOver("tilesets", function() return E.data.gen2Tilesets end),
  }

  E.Trainers = {}
  function E.Trainers.lookup(trainerData, class, member)
    for id, cls in pairs(trainerData and trainerData.classes or {}) do
      if cls.index == class then
        local row = cls.trainers and cls.trainers[member]
        if not row then return nil end
        return { class = class, classId = id, className = cls.name, member = member, id = row.id, name = row.name,
                 roster = row.party or {}, baseMoney = cls.baseMoney }
      end
    end
    return nil
  end
  function E.Trainers.party(data, entry)
    local party = {}
    for _, r in ipairs(entry and entry.roster or {}) do
      local moves
      if r.moves and #r.moves > 0 then
        moves = {}
        for _, id in ipairs(r.moves) do
          local def = data.moves and data.moves[id]
          moves[#moves + 1] = { id = id, pp = def and def.pp or 0, maxPp = def and def.pp or 0 }
        end
      end
      local mon = E.Mon.new(data, r.species, r.level, { moves = moves, item = r.item,
                                                        dvs = { attack = 9, defense = 8, speed = 8, special = 8 } })
      if mon then party[#party + 1] = mon end
    end
    return party
  end

  E.BugContest = {}
  function E.BugContest.isActive(_save) return E.bugContest == true end
  E.CallAsm = {}
  function E.CallAsm.run(world, name)
    E.calledAsm[#E.calledAsm + 1] = name
    if name == "HalveMoney" then
      local p = E.game.save.player
      p.money = math.floor((p.money or 0) / 2)
    end
  end
  E.Runtime = {}
  function E.Runtime.emit(name, payload) emit(name, payload) end
  function World:healParty()
    E.heals = E.heals + 1
    for _, mon in ipairs(E.game.save.party or {}) do mon.hp = mon.maxHp end
  end
  function World:healPoint()
    local s = E.spawn or { map = "TEST_CENTER", x = 2, y = 3 }
    return { map = s.map, x = s.x, y = s.y }
  end
  function World:warpToSpawn()
    local t = self:healPoint()
    self:loadMap(t.map, t.x, t.y, "down", "warp")
  end
  function World:runMapSetup(method, load)
    E.mapSetups[#E.mapSetups + 1] = method
    if load then load() end
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
    ["src.world.gen2.Events"] = E.Events,
    ["src.core.gen2.Decorations"] = E.Decorations,
    ["src.world.gen2.UnionCenter2F"] = E.UnionCenter2F,
    ["src.world.gen2.Permissions"] = E.Permissions,
    ["src.core.Strings"] = E.Strings,
    ["src.core.gen2.Clock"] = E.Clock,
    ["src.ui.gen2.NamingScreen"] = E.NamingScreen,
    ["src.ui.gen2.GenderSelect"] = E.GenderSelect,
    ["src.ui.gen2.CallerBox"] = E.CallerBox,
    -- G2F
    ["src.ui.gen2.BattleState"] = E.BattleState,
    ["src.ui.Screens"] = E.Screens,
    ["src.core.gen2.ItemEffects"] = E.ItemEffects,
    ["src.battle.gen2.Mon"] = E.Mon,
    ["src.battle.gen2.Prize"] = E.Prize,
    ["src.core.gen2.Gender"] = E.Gender,
    ["src.core.gen2.Unown"] = E.Unown,
    ["src.core.gen2.NpcTrade"] = E.NpcTrade,
    ["src.core.gen2.Breeding"] = E.Breeding,
    ["src.core.gen2.Boxes"] = E.Boxes,
    ["src.inventory.Bag"] = E.Bag,
    ["src.core.Game2"] = E.Game2,
    ["src.ui.gen2.CenterPcMenu"] = E.CenterPcMenu,
    -- G2H
    ["src.world.gen2.Trainers"] = E.Trainers,
    ["src.core.gen2.BugContest"] = E.BugContest,
    ["src.script.gen2.CallAsm"] = E.CallAsm,
    ["src.mods.Runtime"] = E.Runtime,
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
