-- gen2 boot and identity (GEN2_BRIDGE_PLAN.md §5.1): who this adapter is, the
-- pin and the engine members it relies on, the Game2 object, the hot reload
-- takeover, the Union Room guard, the map ids as reported (J4) and the world
-- facade the core's acts warp through, the new game's clock (J5), and the
-- save: save() is game.save (the table save.created and save.loaded carry,
-- src/core/Game2.lua:306, :353-357), saveView() the Gen 1-shaped facts the
-- core's readers want.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Boot = {}

-- Every engine module and member the adapter reads (checked at game.ready:
-- checkRequired -> engine.missing).
Boot.REQUIRES = {
  ["src.core.Version"] = { "engine", "modApi" },
  ["src.core.GameVersion"] = { "get", "engine", "generation" },
  ["src.core.SaveData"] = { "activeSlot", "setActiveSlot", "createSlot", "deleteSlot", "renameSlot",
                            "persistenceFs", "loadOptions" },
  ["src.core.gen2.Save"] = { "exists", "load" },
  ["src.core.Music"] = { "fadeOut" },
  ["src.world.gen2.World"] = { "step", "acceptsMenuInput", "restoreMapMusic", "playerCollision",
                               -- G2C: who stands where (story.lua)
                               "hour", "clockTimeMask", "objectTimeVisible", "objectMaskKey",
                               -- G2D: the export's areas and animated tiles (export.lua)
                               "region", "landmarkIndex", "animLayers", "animRow", "flowerFrameFor",
                               -- G2F: the held battle (battle.lua), the field moves (fieldmoves.lua)
                               "startBattle", "pushBattleTransition", "scriptRunning", "useFieldMove",
                               "useFieldItem", "fieldContext", "busy" },
  ["src.world.gen2.Permissions"] = { "isIce", "currentDirection", "doorForcedDirection",
                                     -- G2D: what a COLL byte means (export.lua)
                                     "of", "isGrass", "isSuperTallGrass", "isEncounterCollision", "isWhirlpool",
                                     "isCutTree", "isCuttable", "isHeadbuttTree", "isWaterfall", "isCounter",
                                     "ledgeFacings", "sideBlocks", "neighborBlocks", "isWarpCollision",
                                     "carpetDirection", "warpFacesDown" },
  ["src.world.gen2.Player"] = { "scriptFace" },
  ["src.world.gen2.Map"] = { "cellCollision", "inBounds", "warpAt", "isCounterCell" },
  ["src.world.gen2.FieldMoves"] = { "JOHTO_BADGES", "KANTO_BADGES", "CUT_BLOCKS", "WHIRLPOOL_BLOCKS",
                                    -- G2F: surf for a wild's kind (battle.lua), a move's user (fieldmoves.lua)
                                    "isSurfing", "partyMoveUser" },
  ["src.world.gen2.Events"] = { "objectVisible" },
  ["src.core.gen2.Decorations"] = { "tiles", "ATTRIBUTES", "ACTIONS" },
  -- G2D: the export (export.lua)
  ["src.world.gen2.Palettes"] = { "daytimeFor", "bgSet", "spritePalette", "objectPaletteId" },
  ["src.world.gen2.TileAttrs"] = { "forTile", "sheetTileId" },
  ["src.world.gen2.Npc"] = { "bigFacing" },
  ["src.render.Assets"] = { "resolve" },
  ["src.battle.gen2.Encounter"] = { "GRASS_SLOT_CHANCES", "WATER_SLOT_CHANCES" },
  ["src.world.gen2.UnionCenter2F"] = { "isApplied" },
  ["src.render.TextBox"] = {},
  ["src.ui.ChoiceBox"] = {},
  -- G2B: the UI part (ui.lua)
  ["src.core.Strings"] = {},
  ["src.core.gen2.Clock"] = { "hostMinutes", "hostWeekday" },
  ["src.ui.gen2.NamingScreen"] = { "TYPES", "rows", "bottomRow" },
  ["src.ui.gen2.GenderSelect"] = { "OPTIONS", "menuOpen" },
  ["src.ui.gen2.CallerBox"] = {},
  -- G2F: battles, the party and the bag (battle.lua, party.lua, bag.lua)
  ["src.ui.gen2.BattleState"] = { "new" },
  ["src.battle.gen2.Mon"] = { "new", "stampOT", "stats", "growthFor", "experienceForLevel", "randomDVs",
                              "vanillaShiny" },
  ["src.battle.gen2.Prize"] = { "message" },
  ["src.core.gen2.Gender"] = { "of" },
  ["src.core.gen2.Unown"] = { "letterFromDVs" },
  ["src.core.gen2.NpcTrade"] = { "perform" },
  ["src.core.gen2.Breeding"] = { "hatch", "withdraw" },
  ["src.core.gen2.Boxes"] = { "box" },
  ["src.inventory.Bag"] = { "add", "remove", "capacity", "slots" },
  ["src.core.Game2"] = { "usePartyItem", "useFieldItem", "say" },
  ["src.ui.gen2.CenterPcMenu"] = { "choose" },
}
-- The Game2 object's methods (src/core/Game2.lua:292, :312, :374, :1022, :1044).
Boot.GAME_METHODS = { "newGame", "continueGame", "returnToTitle", "writeSave", "quickSaveAllowed" }

-- The badges in the engine's order (FieldMoves.JOHTO_BADGES / KANTO_BADGES,
-- src/world/gen2/FieldMoves.lua:136-150), the fallback when the module can't be
-- read. The save view names them "<NAME>BADGE", Gen 1's item spelling.
Boot.JOHTO_BADGES = { "ZEPHYR", "HIVE", "PLAIN", "FOG", "MINERAL", "STORM", "GLACIER", "RISING" }
Boot.KANTO_BADGES = { "BOULDER", "CASCADE", "THUNDER", "RAINBOW", "SOUL", "MARSH", "VOLCANO", "EARTH" }

-- The synthetic flag the champion marker reads (protocol/games.json): the Hall
-- of Fame holds a team (save.hallOfFame.count, src/core/gen2/Save.lua:246).
Boot.HALL_OF_FAME = "KB_HALL_OF_FAME"

function Boot.install(b)
  local def = b.def

  def("hasAdapter", function() return true end)
  -- engineId stays the stub's: the live id, "gs" or "crystal" (one adapter for both)
  def("family", function() return "gen2" end)

  -- The Game2 object, from game.ready's payload (Game2.lua:1262) and every
  -- input.step (:1289). Each call is one logic step: tick counts them.
  def("setGame", function(g)
    if g ~= nil then b.game = g end
    b.tick = b.tick + 1
  end)
  def("game", function() return b.game end)
  def("logicStep", function() return b.tick end)

  -- gen1.lua's hot-reload takeover: the previous instance's close function
  -- lives on the Game object; ours replaces it and the old one runs.
  def("takeOver", function(closeMine)
    local g = b.game
    if type(g) ~= "table" then return false end
    local previous = rawget(g, "kantoBridgeCloseInstance")
    rawset(g, "kantoBridgeCloseInstance", closeMine)
    if type(previous) == "function" and previous ~= closeMine then
      local ok = pcall(previous)
      if not ok and b.log then b.log.warn("engine.takeover_failed", {}) end
      return true
    end
    return false
  end)

  -- src/core/Version.lua: engine (the release) and modApi.
  def("version", function()
    local Version = b.mod("src.core.Version")
    if not Version then return nil end
    return tostring(Version.engine), Version.modApi
  end)
  def("checkPin", function()
    local v = b.adapter.version()
    if v == nil then return false end
    local ok = v == b.checked or v:find("^0%.0%.0%-dev") ~= nil
    if not ok and b.log then
      b.log.once("engine.pin", "warn", "engine.unchecked_version", { engine = v, checked = b.checked })
    end
    return ok
  end)
  def("checkRequired", function()
    local missing = {}
    for name, members in pairs(Boot.REQUIRES) do
      local m = b.mod(name)
      if m == nil then
        missing[#missing + 1] = name
      else
        for i = 1, #members do
          if m[members[i]] == nil then missing[#missing + 1] = name .. "." .. members[i] end
        end
      end
    end
    local g = b.game
    if type(g) == "table" then
      for _, method in ipairs(Boot.GAME_METHODS) do
        if type(g[method]) ~= "function" then missing[#missing + 1] = "Game2:" .. method end
      end
    end
    table.sort(missing)
    return missing
  end)

  -- The catalogue id of the running game (src/core/GameVersion.lua:209).
  def("gameVersion", function()
    local GameVersion = b.mod("src.core.GameVersion")
    local v = GameVersion and b.ask(GameVersion, "get", nil) or nil
    return type(v) == "string" and v or nil
  end)

  -- The Union Room on Gen 2 widens POKECENTER_2F and adds UNION_ROOM before
  -- mods load, only when its option is on (src/world/gen2/UnionCenter2F.lua:
  -- 222-260; Game2.lua:1145); the export would then disagree with the hidden
  -- game, so it is refused, as on Gen 1. The option is the one Gen 1 reads
  -- (src/online/union/Setting.lua:5-24): kb_union_off.lua switches both off.
  def("unionRoomPatched", function()
    local g = b.game
    local maps = type(g) == "table" and type(g.data) == "table" and g.data.gen2Maps or nil
    if type(maps) ~= "table" then return false end
    if maps.UNION_ROOM ~= nil then return true end
    return b.ask(b.w2("UnionCenter2F"), "isApplied", false, maps) == true
  end)
  def("unionRoomOff", function() return false end)

  def("save", function() return b.saveObject() end)
  def("top", function()
    local g = b.game
    local stack = type(g) == "table" and g.stack or nil
    local ok, top = b.method(stack, "top")
    return ok and top or nil
  end)
  -- The World while the field is up (main.lua reads its map for the routes' closed row).
  def("overworld", function() return b.inField() and b.ow() or nil end)

  -- ---- map ids as reported, and the world facade (J4)

  def("mapOf", function(id) return b.ext(id) end)

  -- mod.world for the core's acts (region resumes, Wild Routes, cave climbs):
  -- the map ids they hand over are the reported ones, so warps take the prefix
  -- off; everything else is mod.world's own (src/world/gen2/WorldAPI.lua).
  def("worldFacade", function(api)
    if type(api) ~= "table" then return api end
    local facade = {}
    function facade.warpTo(_, map, x, y, facing, opts2)
      return api:warpTo(b.int(map), x, y, facing, opts2)
    end
    function facade.queueScript(_, rows, extra)
      local out = {}
      for i, row in ipairs(type(rows) == "table" and rows or {}) do
        if type(row) == "table" and row[1] == "warp" then
          local copy = {}
          for k, v in pairs(row) do copy[k] = v end
          copy[2] = b.int(row[2])
          out[i] = copy
        else
          out[i] = row
        end
      end
      return api:queueScript(out, extra)
    end
    function facade.current(_)
      local c = api:current()
      if type(c) ~= "table" then return c end
      local copy = {}
      for k, v in pairs(c) do copy[k] = v end
      copy.mapId = b.ext(c.mapId)
      return copy
    end
    return setmetatable(facade, { __index = function(_, name)
      local f = api[name]
      if type(f) ~= "function" then return f end
      return function(_, ...) return f(api, ...) end
    end })
  end)

  -- ---- the new game's clock (J5): the host's own time, as the cart's clock

  -- Oak's speech opens with the clock screen (OakSpeech.defaultSteps,
  -- src/ui/gen2/OakSpeech.lua:219-254: `init_clock`); answered here with the
  -- host's own time: a zero offset (Clock.setTime stores the wanted time minus
  -- the host's, src/core/gen2/Clock.lua:126-134). The intro.oak_speech.build
  -- hook takes the step list (:255-270) and a `fn` step runs (:686-692).
  if type(b.hooks) == "table" and type(b.hooks.wrap) == "function" then
    local okWrap = pcall(b.hooks.wrap, b.hooks, "intro.oak_speech.build", function(nextFn, steps, speech)
      local list = nextFn(steps, speech)
      if type(list) ~= "table" then return list end
      local out = {}
      for i, step in ipairs(list) do
        if type(step) == "table" and step.id == "init_clock" then
          out[i] = { id = "init_clock", kind = "fn", run = function(sp, done)
            local save = type(sp) == "table" and type(sp.game) == "table" and sp.game.save or nil
            if type(save) == "table" then
              save.rtc = type(save.rtc) == "table" and save.rtc or {}
              save.rtc.startMinute = 0
            end
            done()
          end }
        else
          out[i] = step
        end
      end
      return out
    end)
    if not okWrap then b.warnAt("engine.hook_failed", "intro.oak_speech.build") end
  end

  -- ---- the save view (GEN2_BRIDGE_PLAN.md §5.1)

  local function badgeNames()
    local FM = b.w2("FieldMoves")
    local johto = type(FM) == "table" and type(FM.JOHTO_BADGES) == "table" and FM.JOHTO_BADGES or Boot.JOHTO_BADGES
    local kanto = type(FM) == "table" and type(FM.KANTO_BADGES) == "table" and FM.KANTO_BADGES or Boot.KANTO_BADGES
    return johto, kanto
  end
  def("badgeItems", function()
    local out = {}
    local johto, kanto = badgeNames()
    for _, name in ipairs(johto) do out[#out + 1] = name .. "BADGE" end
    for _, name in ipairs(kanto) do out[#out + 1] = name .. "BADGE" end
    return out
  end)

  -- A badge set as the save keeps it (save.player.badges / kantoBadges): names
  -- set true, or a list of names.
  local function owned(set, name)
    if type(set) ~= "table" then return false end
    if set[name] == true then return true end
    for _, v in ipairs(set) do if v == name then return true end end
    return false
  end

  -- The dark maps: PALETTE_DARK maps, black until Flash (src/world/gen2/
  -- Palettes.lua:52-58, :84-94), worked out once per data table.
  local darkFor, darkSet, darkList = nil, {}, {}
  function b.darkMapSet()
    local g = b.game
    local maps = type(g) == "table" and type(g.data) == "table" and g.data.gen2Maps or nil
    if maps ~= darkFor then
      darkFor, darkSet, darkList = maps, {}, {}
      for id, mdef in pairs(type(maps) == "table" and maps or {}) do
        local ext = b.ext(id)
        if ext and type(mdef) == "table" and mdef.palette == "PALETTE_DARK" then
          darkSet[ext] = true
          darkList[#darkList + 1] = ext
        end
      end
      table.sort(darkList)
    end
    return darkSet, darkList
  end
  -- Flash used on this map (World.flashUsed, World.lua:611-618, :6085-6091).
  function b.flashLit()
    local w = b.ow()
    return w ~= nil and w.flashUsed == true
  end
  def("darkMaps", function()
    local _, list = b.darkMapSet()
    local out = {}
    for i, id in ipairs(list) do out[i] = id end
    return out
  end)

  -- The marker flags (protocol/games.json): "E<number>" is an event flag;
  -- KB_HALL_OF_FAME the Hall of Fame holding a team.
  function b.hallOfFame()
    local s = b.saveObject()
    local hof = type(s) == "table" and s.hallOfFame or nil
    return type(hof) == "table" and (tonumber(hof.count) or 0) > 0
  end
  function b.namedFlag(name)
    if name == Boot.HALL_OF_FAME then return b.hallOfFame() end
    local n = type(name) == "string" and tonumber(name:match("^E(%d+)$")) or nil
    if n then return b.flag(n) end
    return false
  end

  -- Built at most once per logic step (setGame counts the steps).
  local cachedTick, cachedSave, cachedView = -1, nil, nil
  function b.dropView() cachedView = nil end
  def("saveView", function()
    local s = b.saveObject()
    if type(s) ~= "table" then return nil end
    if cachedTick == b.tick and cachedSave == s and cachedView then return cachedView end
    local sp = type(s.player) == "table" and s.player or {}
    local player = { name = type(sp.name) == "string" and sp.name or nil }
    local w = b.ow()
    if b.inField() then
      player.map, player.x, player.y, player.facing = b.ext(w.map.id), w.player.cellX, w.player.cellY, w.player.facing
    elseif type(s.position) == "table" then
      player.map, player.x, player.y, player.facing = b.ext(s.position.map), tonumber(s.position.x),
        tonumber(s.position.y), s.position.facing
    end
    local flags = {}
    for _, id in ipairs(b.setFlags()) do flags["E" .. id] = true end
    if b.hallOfFame() then flags[Boot.HALL_OF_FAME] = true end
    -- the bag as { SYMBOL = qty } (save.inventory: item id -> count, Save.lua:255) and the badges
    local inventory = {}
    for id, qty in pairs(type(s.inventory) == "table" and s.inventory or {}) do
      local n = tonumber(qty)
      if b.symbol(id) and n and n > 0 then inventory[id] = math.floor(n) end
    end
    local johto, kanto = badgeNames()
    for _, name in ipairs(johto) do if owned(sp.badges, name) then inventory[name .. "BADGE"] = 1 end end
    for _, name in ipairs(kanto) do if owned(sp.kantoBadges, name) then inventory[name .. "BADGE"] = 1 end end
    local view = {
      player = player,
      flags = flags,
      inventory = inventory,
      money = tonumber(sp.money) or 0,
      coins = tonumber(sp.coins) or 0,
      party = {},                       -- Cobblemon's party; the shadow party comes with G2F
      pokedex = { seen = {}, owned = {} },
      options = type(s.options) == "table" and s.options or nil,
      meta = type(s.meta) == "table" and s.meta or nil,
      version = type(s.version) == "string" and s.version or nil,
      flashLit = b.flashLit(),
    }
    cachedTick, cachedSave, cachedView = b.tick, s, view
    return view
  end)
end

return Boot
