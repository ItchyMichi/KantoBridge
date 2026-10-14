-- A stand-in for the parts of Gen1Recomp v0.3.85's Gen 3 engine,
-- `game3`, that gen1-bridge's game3 adapter reads (lib/engine/game3.lua and
-- its parts, lib/engine/game3/*.lua; docs/specs/GEN3_BRIDGE_PLAN.md §8.1):
-- the Game3 object (phase, boot, input, session and the methods the adapter
-- calls), the engine modules it requires, and a small field loop so main.lua
-- can be stepped on it. Written for Kanto Bridge; module and field names
-- follow the engine (the file:line of each below). Everything is synthetic:
-- made-up maps (EM_TEST_*), flags, items, sprites and behaviours, with
-- made-up numbers; the names are engine constant names.
--
--   local E = FakeEngine3.new({ game = "emerald" })
--   bridge modules: E.modules (require name -> table); the Game3 object: E.game
--   E.enterField("EM_TEST_TOWN", 5, 6, "down")   CONTINUE's or NEW GAME's result
--   E.leaveField()                               back to the title (Field.stop)
--   E.startStep("up"); E.land()                  a step under way, landed
--   E.block(x, y, why)                           tryMove into (x, y): "blocked", why
--   E.behavior(x, y, "MB_TEST_DOOR")             a cell's metatile behaviour
--   E.openMessage("text"), E.pushLayer("start"), E.openChoice("yesno")
--   E.setFlag("FLAG_TEST_ONE"), E.addObject(1, { x = 3, y = 4, gfx = 1 })
--   E.slotSave("slot1", { map = ... })           a Gen 3 save in a slot
--   E.bindMod(FakeMod, mod)                      events go to that mod object
--   E.tick()                                     the engine's part of a logic
--                                                step (after input.step)
--
-- Test switches: E.refuseUnlock (Field.unlock does nothing, as during a fly
-- landing), E.saveResult (nil: written, "refuse", "error"), E.quickSaveOk
-- (false: no SAVE offered), E.createFails, E.newGameFails; records:
-- E.bootActions, E.saveCalls, E.titleCalls, E.newGameStarts, E.audioCalls,
-- E.slotCalls, E.tryMoves.

local FakeEngine3 = {}

local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
local DIR_ORDER = { "down", "up", "left", "right" } -- dirs_from_input's order (player.lua:166-177)

local function key(x, y) return y * 1024 + x end -- the engine's cell key (field.lua:90-93)

local function deepCopy(v)
  if type(v) ~= "table" then return v end
  local out = {}
  for k, x in pairs(v) do out[k] = deepCopy(x) end
  return out
end

-- ------------------------------------------------------------ input

-- The engine's Input as mod.input presses into it (src/mods/Loader.lua:1467-1505):
-- sources per button; a press sets the held state at once and queues an
-- edge; Input:step turns the queued edges into one step of wasPressed.
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

function FakeEngine3.new(opts)
  opts = opts or {}
  local E = {
    bootActions = {}, saveCalls = {}, titleCalls = {}, newGameStarts = {}, audioCalls = {}, slotCalls = {},
    tryMoves = {}, ticks = 0, walls = {}, behaviors = {},
  }
  local emit = function() end

  -- src/core/Version.lua:6-23: the release, the native-shell contract, the
  -- mod API major and the save format (as fake_engine.lua)
  E.Version = { engine = opts.engine or "0.3.85", shell = 3, modApi = 2, saveFormat = 5 }
  -- src/core/GameVersion.lua: get (:209), generation (:231), engine (:236-238)
  E.GameVersion = { current = opts.game or "emerald" }
  function E.GameVersion.get() return E.GameVersion.current end
  function E.GameVersion.generation() return 3 end
  E.engineId = opts.engineId or "game3"
  function E.GameVersion.engine() return E.engineId end

  -- ---- save slots: SaveData's registry (src/core/SaveData.lua:1642-1900),
  -- keyed by version; E.slotReg = options.lua's saveSlots[version]
  -- ({ list, active, names }), E.activeSlot the process cache (:1771),
  -- E.slotFiles[id] a file on disk (saves/<version>/<id>.lua), E.slotSaves[id
  -- or "flat"] the save table it holds (what CONTINUE loads)
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
  function SaveData.activeSlot(version) -- :1771-1775
    call("activeSlot", version)
    if not known(version) then return nil end
    return E.activeSlot
  end
  function SaveData.setActiveSlot(version, id) -- :1726-1730: registered if new, active
    call("setActiveSlot", version)
    if not known(version) then return nil end
    if not registered(id) then E.slotReg.list[#E.slotReg.list + 1] = id end
    E.slotReg.active, E.activeSlot = id, id
    return id
  end
  function SaveData.createSlot(version) -- :1755-1759: one past the highest number, not active
    call("createSlot", version)
    if not known(version) then return nil end
    if E.createFails then error("fake createSlot failed") end
    local max = 0
    for _, id in ipairs(E.slotReg.list) do
      local n = tonumber(id:match("^slot(%d+)$"))
      if n and n > max then max = n end
    end
    local id = "slot" .. (max + 1)
    E.slotReg.list[#E.slotReg.list + 1] = id
    return id
  end
  function SaveData.renameSlot(version, id, label) -- :1699-1703
    call("renameSlot", version)
    if not known(version) then return false, "unknown version" end
    if not registered(id) then return false, "slot not registered" end
    E.slotReg.names[id] = label
    return true
  end
  function SaveData.deleteSlot(version, id) -- :1896-1900: the active one moves to list[1]
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
  function SaveData.listSlots(version) -- :1642
    call("listSlots", version)
    local out = {}
    for i, v in ipairs(known(version) and E.slotReg.list or {}) do out[i] = v end
    return out
  end
  -- loadOptions (:1025): the options file, the slot registry under saveSlots
  function SaveData.loadOptions()
    local list, names = {}, {}
    for i, v in ipairs(E.slotReg.list) do list[i] = v end
    for k, v in pairs(E.slotReg.names) do names[k] = v end
    return { saveSlots = { [E.GameVersion.current] = { list = list, active = E.slotReg.active, names = names } } }
  end
  -- persistenceFs (:566) with the slot files (saves/<version>/<id>.lua, .tmp, .bak)
  function SaveData.persistenceFs()
    return { getInfo = function(path)
      local version, id = path:match("^saves/([%w_]+)/(slot%d+)%.lua")
      if version == E.GameVersion.current and E.slotFiles[id] then return { type = "file" } end
      return nil
    end }
  end
  E.SaveData = SaveData

  -- A Gen 3 save in a slot (a made-up save table; nil removes it).
  function E.slotSave(id, save)
    if save ~= nil and not registered(id) then E.slotReg.list[#E.slotReg.list + 1] = id end
    E.slotFiles[id] = save ~= nil or nil
    E.slotSaves[id] = save
  end

  -- ---- the field's modules

  -- src/core/game3/runtime.lua:32-38
  E.Runtime = { active = false, session = nil }
  function E.Runtime.isActive() return E.Runtime.active == true end
  function E.Runtime.getSession() return E.Runtime.session end

  -- src/core/game3/player.lua:62-117 (the fields), :730 (tryMove), :1066-1069
  -- (scriptFace). tryMove here never turns (the adapter faces first): it
  -- steps, or answers "blocked", why for a cell in E.walls or off the map.
  E.Player = {
    cellX = 0, cellY = 0, facing = "down", moving = false, targetX = 0, targetY = 0, progress = 0,
    stepFrames = 16, jumping = false, surfing = false, biking = false, running = false, visible = true,
    stepSerial = 0,
  }
  local P = E.Player
  function P.tryMove(dir, game, run)
    E.tryMoves[#E.tryMoves + 1] = { dir = dir, run = run == true, game = game }
    if P.moving or not DELTA[dir] then return nil end
    P.facing = dir
    local d = DELTA[dir]
    local tx, ty = P.cellX + d[1], P.cellY + d[2]
    local why = E.walls[key(tx, ty)]
    if why == nil and E.inMap(tx, ty) == false then why = "bounds" end
    if why then return "blocked", why end
    E.startStep(dir, run and 8 or 16)
    return "step"
  end
  function P.scriptFace(dir)
    if DELTA[dir] then P.facing = dir end
  end

  -- src/core/game3/field.lua:15-45 (the lock tags), :67 (running), :101-147
  -- (Field.start wipes the tags, :109), :149-167 (Field.stop, :155), :302
  -- (callbackPending). E.refuseUnlock: unlock does nothing (a fly landing,
  -- a fall warp, a field callback, :30-37).
  E.Field = { running = false, _locks = {}, _fieldCallback = false }
  local F = E.Field
  function F.isLocked() return next(F._locks) ~= nil end
  function F.lock(tag)
    F._locks = F._locks or {}
    F._locks[tag or "default"] = true
  end
  function F.unlock(tag)
    if E.refuseUnlock then return end
    F._locks = F._locks or {}
    F._locks[tag or "default"] = nil
  end
  function F.callbackPending() return F._fieldCallback == true end

  -- src/core/game3/map.lua:11 (current), :23-25 (currentDef)
  E.Map = { current = nil, _def = nil }
  function E.Map.currentDef() return E.Map._def end

  -- src/core/game3/warp.lua:11-13
  E.Warp = { _busy = false }
  function E.Warp.isBusy() return E.Warp._busy == true end

  -- src/core/game3/objects.lua:38-39: rebuilt on every map load
  E.Objects = { _byId = {}, _order = {} }

  -- Metatile behaviours: made-up numbers, engine-style names (mb.lua:83-85)
  E.MB_IDS = {
    MB_NORMAL = 0, MB_TEST_DOOR = 1, MB_TEST_EXIT = 2, MB_TEST_PAD = 3, MB_TEST_HOLE = 4, MB_TEST_STAIRS = 5,
    MB_TEST_ARROW = 6, MB_COUNTER = 7,
  }
  local names = {}
  for name, id in pairs(E.MB_IDS) do names[id] = name end
  local MB = E.MB_IDS
  E.MB = {}
  function E.MB.nameOf(id) return names[tonumber(id) or -1] end

  -- src/core/game3/collision.lua: behavior (:255), isWalkable (:914) and the
  -- warp predicates (:155, :586, :610, :616, :637, :656)
  E.Collision = {}
  local C = E.Collision
  function C.behavior(x, y) return E.behaviors[key(x, y)] or MB.MB_NORMAL end
  function C.isWalkable(x, y) return E.walls[key(x, y)] == nil and E.inMap(x, y) ~= false end
  function C.isWarpDoor(beh) return beh == MB.MB_TEST_DOOR end
  function C.isWarpPad(beh) return beh == MB.MB_TEST_PAD end
  function C.isFallWarp(beh) return beh == MB.MB_TEST_HOLE end
  function C.isArrowWarpBehavior(beh) return beh == MB.MB_TEST_ARROW end
  function C.isStairWarpBehavior(beh) return beh == MB.MB_TEST_STAIRS end
  function C.isWarpMetatileBehavior(beh)
    return beh == MB.MB_TEST_DOOR or beh == MB.MB_TEST_EXIT or beh == MB.MB_TEST_PAD or beh == MB.MB_TEST_STAIRS
      or beh == MB.MB_TEST_ARROW
  end

  -- ---- story: flags and the constants

  -- Flags.active(session) (scripting/flags.lua:192-217): the running game's
  -- tables. Made-up ids; the "FireRed" set (Flags.IDS/NAMES, :17-48, which
  -- the engine's own string lookups go through) uses other numbers, so a
  -- caller that passes a name instead of a number reads the wrong flag, as
  -- on the real engine.
  local EM_FLAGS = {
    FLAG_TEST_ONE = 0x101, FLAG_TEST_TWO = 0x102, FLAG_TEST_THREE = 0x103,
    FLAG_SYS_POKEMON_GET = 0x210, FLAG_SYS_GAME_CLEAR = 0x214,
  }
  local BADGE_NAMES = { "STONE", "KNUCKLE", "DYNAMO", "HEAT", "BALANCE", "FEATHER", "MIND", "RAIN" }
  local badges = {}
  for i, name in ipairs(BADGE_NAMES) do
    local flag = ("FLAG_BADGE0%d_GET"):format(i)
    EM_FLAGS[flag] = 0x220 + i - 1
    badges[i] = { num = i, name = name, flag = EM_FLAGS[flag] }
  end
  local function tables(ids)
    local byId = {}
    for name, id in pairs(ids) do byId[id] = name end
    return ids, byId
  end
  local emIds, emNames = tables(EM_FLAGS)
  E.flagTables = { game = "emerald", IDS = emIds, NAMES = emNames, BADGES = badges }
  local frIds, frNames = tables({ FLAG_TEST_ONE = 0x301, FLAG_SYS_POKEMON_GET = 0x310, FLAG_SYS_GAME_CLEAR = 0x314,
                                  FLAG_BADGE01_GET = 0x320 })
  E.Flags = { IDS = frIds, NAMES = frNames }
  local Flags = E.Flags
  function Flags.active(session) return E.flagTables end
  -- :377-390, the engine's lookup: numbers and numeric strings, "0x..." keys,
  -- and a name key through the FireRed table
  function Flags.getFlag(store, ctx, id)
    id = tonumber(id) or (type(id) == "string" and Flags.IDS[id]) or 0
    if not store or not store.flags then return false end
    if store.flags[id] == true or store.flags[tostring(id)] == true then return true end
    if type(id) == "number" and id > 0 then
      if store.flags[("0x%X"):format(id)] == true then return true end
      local name = Flags.NAMES[id]
      if name and store.flags[name] == true then return true end
    end
    return false
  end
  function Flags.countBadges(store) -- :234-242
    local n = 0
    for _, b in ipairs(E.flagTables.BADGES) do
      if Flags.getFlag(store, nil, b.flag) then n = n + 1 end
    end
    return n
  end

  -- scripting/space.lua:22-23 (vm, store): the live store, rebuilt on every
  -- map load from the session (E.enterField)
  E.Space = { store = nil, vm = { running = false } }
  function E.Space.vm:isRunning() return self.running == true end -- vm.lua:68

  -- Constants.of(game) (constants.lua:108-116) with Methods:name (:51-66):
  -- made-up tables, keyed by prefix as the generated ones are
  E.constTables = {
    items = { ITEM_ = { [1] = "ITEM_TEST_BALL", [2] = "ITEM_TEST_POTION", [3] = "ITEM_TEST_KEY", [4] = "ITEM_none" } },
    event_objects = { OBJ_EVENT_GFX_ = { [1] = "OBJ_EVENT_GFX_TEST_HIKER", [2] = "OBJ_EVENT_GFX_TEST_BOULDER",
                                         [3] = "OBJ_EVENT_GFX_TEST_ITEM_BALL", [4] = "OBJ_EVENT_GFX_TEST_GIRL" } },
    -- the nurse's special by its engine name, at a made-up id
    specials = { [7] = "HealPlayerParty", [8] = "TestOtherSpecial" },
  }
  local Methods = {}
  function Methods:name(kind, id, prefix)
    local t = E.constTables[kind]
    if t == nil then return nil end
    if kind == "specials" then return t[id] end
    if prefix then return t[prefix] and t[prefix][id] or nil end
    for _, sub in pairs(t) do
      if type(sub) == "table" and sub[id] then return sub[id] end
    end
    return nil
  end
  local GAMES = { firered = "firered", leafgreen = "firered", emerald = "emerald", ruby = "ruby", sapphire = "ruby" }
  local sets = {}
  E.Constants = {}
  function E.Constants.of(game)
    local k = GAMES[game]
    if not k then error("game3 constants: no tables for game " .. tostring(game), 2) end -- gameKey :25-31
    sets[k] = sets[k] or setmetatable({ game = k }, { __index = Methods })
    return sets[k]
  end

  -- scripting/natives.lua:1074-1076
  E.Natives = {}
  function E.Natives.specialName(id) return E.constTables.specials[tonumber(id)] end

  -- bag.lua:662-675 (Coins.get)
  E.Bag = { Coins = {} }
  function E.Bag.Coins.get(session)
    if type(session) ~= "table" then return 0 end
    return math.floor(tonumber(session.coins) or 0)
  end

  -- battle/init.lua:369-371
  E.Battle = { _active = false }
  function E.Battle.isActive() return E.Battle._active == true end

  -- audio.lua:708 (fadeOutBgm), :730 (fadeInBgm: playSong(id), :429-452,
  -- a song id), :1507 (currentSong: the playing record { id, duration, loop,
  -- startedAt }, :447-452, or nil): recorded. E.song: that record (a made-up id).
  E.song = { id = 412, loop = true, startedAt = 0 }
  E.Audio = {}
  function E.Audio.fadeOutBgm(speed) E.audioCalls[#E.audioCalls + 1] = { op = "fadeOut", speed = speed } end
  function E.Audio.fadeInBgm(id, speed) E.audioCalls[#E.audioCalls + 1] = { op = "fadeIn", song = id, speed = speed } end
  function E.Audio.currentSong() return E.song end

  -- forced_movement.lua:441-449
  E.Forced = { forced = false }
  function E.Forced.isForced() return E.Forced.forced == true end

  -- ---- the HUD (src/ui/game3/*)

  -- message.lua:260-275
  E.Message = { open = false, _waiting = false, _pages = nil, _page = 1 }
  function E.Message.isOpen() return E.Message.open == true end
  function E.Message.isWaiting() return E.Message.open == true and E.Message._waiting == true end
  function E.Message.currentPage()
    local m = E.Message
    return m._pages and m._pages[m._page] or ""
  end
  -- choice.lua:10-11
  E.Choice = { active = false, kind = nil }
  -- stack.lua:17-21: layers { id, ... }
  E.Stack = { _layers = {} }
  function E.Stack.top()
    local n = #E.Stack._layers
    if n < 1 then return nil end
    return E.Stack._layers[n]
  end
  -- fade.lua:50 (isActive) and Fade.lockInput (Game3.lua:114)
  E.Fade = { active = false, lockInput = nil }
  function E.Fade.isActive() return E.Fade.active end
  -- hud.lua:51-58
  E.Hud = { _waitButton = nil }
  function E.Hud.busy()
    return E.Battle.isActive() or E.Message.isOpen() or E.Choice.active or E.Stack.top() ~= nil
      or E.Fade.isActive() == true or E.Hud._waitButton ~= nil
  end
  -- boot.lua:23-33 (the phases) and boot_modules.lua:111-117 (startNewGame:
  -- Birch's speech, the boot phase "oak")
  E.BootUi = { PHASE = { INTRO = "intro", TITLE = "title", MENU = "menu", OAK = "oak" } }
  E.BootModules = {}
  function E.BootModules.startNewGame(Boot, state)
    E.newGameStarts[#E.newGameStarts + 1] = { boot = Boot, state = state }
    if E.newGameFails then error("fake startNewGame failed") end
    state.phase = Boot.PHASE.OAK
    state.timer = 0
  end

  E.modules = {
    ["src.core.Version"] = E.Version,
    ["src.core.GameVersion"] = E.GameVersion,
    ["src.core.SaveData"] = E.SaveData,
    ["src.core.game3.runtime"] = E.Runtime,
    ["src.core.game3.player"] = E.Player,
    ["src.core.game3.field"] = E.Field,
    ["src.core.game3.map"] = E.Map,
    ["src.core.game3.warp"] = E.Warp,
    ["src.core.game3.objects"] = E.Objects,
    ["src.core.game3.collision"] = E.Collision,
    ["src.core.game3.scripting.space"] = E.Space,
    ["src.core.game3.scripting.flags"] = E.Flags,
    ["src.core.game3.scripting.natives"] = E.Natives,
    ["src.core.game3.constants"] = E.Constants,
    ["src.core.game3.bag"] = E.Bag,
    ["src.core.game3.battle.init"] = E.Battle,
    ["src.core.game3.audio"] = E.Audio,
    ["src.core.game3.forced_movement"] = E.Forced,
    ["src.core.game3.mb"] = E.MB,
    ["src.ui.game3.message"] = E.Message,
    ["src.ui.game3.choice"] = E.Choice,
    ["src.ui.game3.stack"] = E.Stack,
    ["src.ui.game3.hud"] = E.Hud,
    ["src.ui.game3.fade"] = E.Fade,
    ["src.ui.game3.boot"] = E.BootUi,
    ["src.ui.game3.boot_modules"] = E.BootModules,
  }
  -- what main.lua and kblua hand around besides the modules: no ModUI or
  -- dataset tables on this fake; the live data mods.loaded carries
  E.ui, E.datasets = nil, nil
  E.data = { maps = {} }

  -- ---- sessions and the field

  E.newGameStart = { map = "EM_TEST_TRUCK", x = 2, y = 2, facing = "down" }
  -- A session as Schema.newGame / fromSaveTable builds one (made-up values).
  function E.newSession(map, x, y, facing)
    return {
      version = E.GameVersion.current, map = map, x = x, y = y, facing = facing or "down",
      name = "TESTER", money = 3000, coins = 0, flags = {}, vars = {}, party = {},
      bag = { pockets = { ITEMS = {}, KEY_ITEMS = {}, POKE_BALLS = {}, TM_CASE = {}, BERRY_POUCH = {} } },
      options = { textSpeed = "fast" },
    }
  end

  -- The field up on a session (Runtime.start -> Field.start -> Map.load):
  -- the phase, a new flag store from the session's flags, new object
  -- tables, no lock tags, the player on the cell; map.entered{via} (boot,
  -- the first load after CONTINUE or NEW GAME). opts: session, width,
  -- height (the layout in cells, default 20 x 20), via.
  function E.enterField(map, x, y, facing, o)
    o = o or {}
    local s = o.session or E.game.session or E.newSession(map, x, y, facing)
    s.map, s.x, s.y, s.facing = map, x, y, facing or s.facing or "down"
    E.game.phase, E.game.session = "field", s
    E.Runtime.active, E.Runtime.session = true, s
    F.running, F._locks, F._fieldCallback = true, {}, false
    E.Map.current = map
    E.Map._def = { id = map, midLayout = { width = o.width or 20, height = o.height or 20 } }
    E.Objects._byId, E.Objects._order = {}, {}
    E.Space.store = { flags = deepCopy(s.flags or {}), vars = deepCopy(s.vars or {}) }
    E.Space.vm.running = false
    E.Warp._busy = false
    P.cellX, P.cellY, P.facing = x, y, facing or "down"
    P.targetX, P.targetY, P.moving, P.progress, P.stepFrames = x, y, false, 0, 16
    P.jumping, P.surfing, P.biking, P.running, P.visible, P._scriptedStep = false, false, false, false, true, nil
    emit("map.entered", { mapId = map, via = o.via or "boot" })
    return s
  end
  -- rig.Rig.world()'s name for it
  E.enterWorld = E.enterField

  -- Field.stop and the boot phase (returnToTitle's teardown, Game3.lua:1445-1479)
  function E.leaveField(bootPhase)
    F.running, F._locks = false, {}
    E.Runtime.active, E.Runtime.session = false, nil
    E.Space.store = nil
    E.Stack._layers = {}
    E.Message.open = false
    E.game.phase, E.game.session = "boot", nil
    E.game.boot = { phase = bootPhase or "title" }
  end

  -- Whether (x, y) is on the current map: nil out of the field.
  function E.inMap(x, y)
    local d = E.Map._def
    local l = d and d.midLayout
    if not l then return nil end
    return x >= 0 and y >= 0 and x < l.width and y < l.height
  end

  -- A step as beginStep starts it (player.lua:502-545).
  function E.startStep(dir, frames)
    local d = DELTA[dir]
    P.facing = dir
    P.targetX, P.targetY = P.cellX + d[1], P.cellY + d[2]
    P.moving, P.progress, P.stepFrames = true, 0, frames or 16
    P.stepSerial = P.stepSerial + 1
  end
  -- finishStep (player.lua:1180-1326): the cell, then world.stepped (:1222-1230)
  function E.land()
    P.cellX, P.cellY = P.targetX, P.targetY
    P.moving, P.progress, P.running, P.jumping = false, 0, false, false
    P._scriptedStep = nil
    emit("world.stepped", { mapId = E.Map.current, x = P.cellX, y = P.cellY,
                            tile = C.behavior(P.cellX, P.cellY), facing = P.facing })
  end

  function E.block(x, y, why) E.walls[key(x, y)] = why end
  function E.behavior(x, y, name) E.behaviors[key(x, y)] = name and MB[name] or nil end

  function E.openMessage(text, waiting)
    E.Message.open, E.Message._pages, E.Message._page = true, { text or "" }, 1
    E.Message._waiting = waiting == true
  end
  function E.closeMessage() E.Message.open, E.Message._pages = false, nil end
  function E.openChoice(kind) E.Choice.active, E.Choice.kind = true, kind or "yesno" end
  function E.closeChoice() E.Choice.active, E.Choice.kind = false, nil end
  function E.pushLayer(id) E.Stack._layers[#E.Stack._layers + 1] = { id = id } end
  function E.popLayer() E.Stack._layers[#E.Stack._layers] = nil end

  -- A flag in the live store by its Emerald name or number, as setFlag
  -- writes it (both keys, flags.lua:398-400).
  function E.setFlag(nameOrId, on)
    local id = tonumber(nameOrId) or E.flagTables.IDS[nameOrId]
    assert(id, "no such flag: " .. tostring(nameOrId))
    local store = assert(E.Space.store, "no flag store (enter the field first)")
    store.flags[id] = on ~= false or nil
    store.flags[tostring(id)] = on ~= false or nil
  end

  -- An object event as object_prepare.lua:105-149 builds it. o: x, y, gfx
  -- (graphicsId), facing, sight, item, visible, hidden, invisible, moving,
  -- tx, ty.
  function E.addObject(lid, o)
    o = o or {}
    local eo = {
      localId = lid, cellX = o.x or 0, cellY = o.y or 0, facing = o.facing or "down", graphicsId = o.gfx or 1,
      sight = o.sight or 0, def = { item = o.item }, visible = o.visible ~= false, hidden = o.hidden == true,
      invisible = o.invisible == true, moving = o.moving == true, targetX = o.tx or o.x or 0,
      targetY = o.ty or o.y or 0, progress = 0, stepFrames = 16,
    }
    E.Objects._byId[lid] = eo
    E.Objects._order[#E.Objects._order + 1] = lid
    return eo
  end
  -- Objects.loadMap rebuilds every table (objects.lua:621-675)
  function E.reloadObjects() E.Objects._byId, E.Objects._order = {}, {} end

  -- ---- the Game3 object (src/core/Game3.lua:47-57; methods on its class)

  local Game3 = {}
  Game3.__index = Game3
  E.Game3 = Game3
  E.game = setmetatable({ generation = 3, input = newInput(), session = nil, phase = "boot",
                          boot = { phase = "title" } }, Game3)

  -- :60-66: the active slot holds a Gen 3 save
  function Game3:_hasContinueSave()
    return E.slotSaves[E.activeSlot or "flat"] ~= nil
  end

  -- :509-573: continue (the active slot's save, silent without one: save.loading,
  -- the field, save.loaded), new_game (save.created, the field), exit
  function Game3:_handleBootAction(action)
    E.bootActions[#E.bootActions + 1] = action
    if not action then return end
    if action.action == "continue" then
      local save = E.slotSaves[E.activeSlot or "flat"]
      if not save then return end
      emit("save.loading", { raw = save })
      local s = deepCopy(save) -- Schema.fromSaveTable: new tables
      s.version = s.version or E.GameVersion.current
      self.session = s
      E.enterField(s.map, s.x, s.y, s.facing, { session = s })
      emit("save.loaded", { save = s, meta = s.meta })
    elseif action.action == "new_game" then
      local st = E.newGameStart
      local s = E.newSession(st.map, st.x, st.y, st.facing)
      self.session = s
      emit("save.created", { save = s })
      E.enterField(st.map, st.x, st.y, st.facing, { session = s })
      F.lock("truck")
    end
  end

  -- :1019-1026: the field phase, nothing of the HUD busy, START allowed
  -- (hud.lua:164-178: no Field lock tag -- the park's included --, no
  -- forced movement, no warp, no running script) and SAVE offered here
  -- (E.quickSaveOk false: a pending ON_FRAME, a link room, the Safari Zone)
  function Game3:quickSaveAllowed()
    if self.phase ~= "field" then return false end
    if E.Hud.busy() then return false end
    if F.isLocked() or E.Forced.isForced() or E.Warp.isBusy() or E.Space.vm:isRunning() then return false end
    return E.quickSaveOk ~= false
  end

  -- :1028-1072: no state guards; false when a save.write veto refuses it
  -- (E.saveResult "refuse"); save.writing just before the write; the meta
  -- on the session
  function Game3:saveGame()
    E.saveCalls[#E.saveCalls + 1] = { phase = self.phase }
    if not self.session then return end
    if E.saveResult == "refuse" then return false end
    if E.saveResult == "error" then error("fake saveGame failed") end
    local s = E.Runtime.getSession() or self.session
    self.session = s
    s.meta = { savedAt = 1700000000 + #E.saveCalls, sessionStart = 1700000000, engine = E.Version.engine }
    local save = deepCopy(s)
    emit("save.writing", { save = save, meta = save.meta })
    local slot = E.activeSlot or "flat"
    E.slotSaves[slot] = save
    if E.activeSlot then E.slotFiles[E.activeSlot] = true end
    return true
  end

  -- :1445-1479: the field torn down, the boot phase; the intro unless skipped
  function Game3:returnToTitle(o)
    E.titleCalls[#E.titleCalls + 1] = o or {}
    E.leaveField((o and o.skipIntro) and "title" or "intro")
  end

  -- ---- one logic step (Game3:fixedUpdate after the input.step hook,
  -- Game3.lua:575-633): Input:step, then Player.update's part
  -- (player.lua:1437-1504): the step under way advances and lands
  -- (Player.tick, :1328-1395), then, the player standing and the field idle,
  -- a direction pressed or held goes to tryMove through the module table,
  -- in the same update.

  local function fieldBusy()
    return F.isLocked() or E.Fade.lockInput == true or E.Warp.isBusy() or E.Hud.busy()
      or E.Space.vm:isRunning()
  end

  local function direction(input)
    for _, d in ipairs(DIR_ORDER) do
      if input:wasPressed(d) then return d end
    end
    for _, d in ipairs(DIR_ORDER) do
      if input:isDown(d) then return d end
    end
    return nil
  end

  function E.tick()
    E.ticks = E.ticks + 1
    local g = E.game
    g.input:step()
    if g.phase ~= "field" or not E.Runtime.active or not F.running or E.Battle.isActive() then return end
    if P.moving then
      P.progress = P.progress + 1
      if P.progress >= (P.stepFrames or 16) then E.land() end
    end
    if P.moving or fieldBusy() then return end
    local dir = direction(g.input)
    if dir then E.Player.tryMove(dir, g, false) end
  end

  -- Events go to the mod object main.lua runs on (FakeMod.emit).
  function E.bindMod(FakeMod, mod)
    emit = function(name, payload) FakeMod.emit(mod, name, payload) end
  end

  return E
end

return FakeEngine3
