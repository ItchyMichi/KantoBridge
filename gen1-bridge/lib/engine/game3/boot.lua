-- game3 boot and identity (GEN3_BRIDGE_PLAN.md §4.1): who this adapter is,
-- the pin and the engine members it relies on, the Game3 object, the hot
-- reload takeover, and the save: save() is the live session (the object the
-- save events carry, Game3.lua:540, :557), saveView() the Gen 1-shaped facts
-- the core's readers want (§3, §4.7).
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Boot = {}

-- Every engine module and member the adapter reads (checked at game.ready:
-- checkRequired -> engine.missing). Modules whose tables are only read field
-- by field at run time list no members.
Boot.REQUIRES = {
  ["src.core.Version"] = { "engine", "modApi" },
  ["src.core.GameVersion"] = { "get", "engine", "generation" },
  ["src.core.SaveData"] = { "activeSlot", "setActiveSlot", "createSlot", "deleteSlot", "renameSlot",
                            "persistenceFs", "loadOptions" },
  ["src.core.game3.runtime"] = { "getSession", "isActive" },
  ["src.core.game3.player"] = { "tryMove" },
  ["src.core.game3.field"] = { "isLocked", "lock", "unlock", "callbackPending", "respawnAtHeal", "tryFishingEncounter" },
  ["src.core.game3.map"] = { "currentDef" },
  ["src.core.game3.warp"] = { "isBusy" },
  ["src.core.game3.objects"] = {},
  ["src.core.game3.collision"] = { "behavior", "isWalkable" },
  ["src.core.game3.scripting.space"] = {},
  ["src.core.game3.scripting.flags"] = { "active", "getFlag", "countBadges" },
  ["src.core.game3.constants"] = { "of" },
  -- the battle module under the name the engine loads it by (battle_bridge.lua's require)
  ["src.core.game3.battle"] = { "isActive", "getState", "abort" },
  -- G3F: battles, party, items (battle.lua, party.lua, items.lua, routes.lua)
  ["src.core.game3.battle_bridge"] = { "start", "applyWhiteoutMoneyLoss", "twoOpponentFoe" },
  ["src.core.game3.battle.prize"] = { "rewardRse", "apply", "moneyMessage" },
  ["src.core.game3.scripting.trainers"] = { "foeFromId", "info", "dialogs" },
  ["src.core.game3.pokemon"] = { "national", "speciesFromNational", "movesAtLevel", "movePp", "applyStats",
                                 "moveFromTmItem" },
  ["src.core.game3.party"] = { "giveMon" },
  ["src.core.game3.summary_data"] = { "expForLevel" },
  ["src.core.game3.dex"] = { "setSeen", "setCaught" },
  ["src.core.game3.bag"] = { "get", "add", "remove" },
  ["src.core.game3.items_data"] = { "pocketOf", "isHm", "isTm" },
  ["src.core.game3.safari"] = { "isActive" },
  ["src.core.game3.audio"] = { "fadeOutBgm", "fadeInBgm", "currentSong" },
  ["src.ui.game3.message"] = { "isOpen", "isWaiting", "currentPage", "show" },
  ["src.ui.game3.stack"] = { "top" },
  ["src.ui.game3.hud"] = { "busy" },
  ["src.ui.game3.fade"] = { "isActive" },
  ["src.ui.game3.boot"] = {},
  ["src.ui.game3.boot_modules"] = { "startNewGame" },
}
-- The Game3 object's methods (src/core/Game3.lua:60, :509, :1019, :1028, :1445).
Boot.GAME_METHODS = { "_hasContinueSave", "_handleBootAction", "quickSaveAllowed", "saveGame", "returnToTitle" }

-- Emerald's badges in order (pokeemerald party_menu.c:120, mirrored in
-- scripting/flags.lua EM_BADGES), the fallback when the flag tables can't be
-- read. The save view names them "<NAME>BADGE", Gen 1's item spelling.
Boot.BADGE_NAMES = { "STONE", "KNUCKLE", "DYNAMO", "HEAT", "BALANCE", "FEATHER", "MIND", "RAIN" }

function Boot.install(b)
  local def = b.def

  def("hasAdapter", function() return true end)
  def("engineId", function() return "game3" end)
  def("family", function() return "gen3" end)

  -- The Game3 object, from game.ready's payload and every input.step
  -- (Game3.lua:255, :577-578). Each call is one logic step: tick counts them.
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
        if type(g[method]) ~= "function" then missing[#missing + 1] = "Game3:" .. method end
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

  -- Gen 3's Union Plaza doesn't patch the maps the export reads (the Gen 1
  -- Union Room's Pokémon Center rewrite has no Gen 3 counterpart).
  def("unionRoomPatched", function() return false end)
  def("unionRoomOff", function() return false end)

  def("save", function() return b.session() end)

  -- ---- the save view (GEN3_BRIDGE_PLAN.md §4.7)

  local function badgeList()
    local t = b.flagTables()
    local out = {}
    if t and type(t.BADGES) == "table" and #t.BADGES == 8 then
      for i, badge in ipairs(t.BADGES) do
        local name = type(badge) == "table" and type(badge.name) == "string" and badge.name or Boot.BADGE_NAMES[i]
        out[i] = { item = name .. "BADGE", flag = type(badge) == "table" and tonumber(badge.flag) or nil }
      end
    else
      for i, name in ipairs(Boot.BADGE_NAMES) do out[i] = { item = name .. "BADGE", flag = nil } end
    end
    return out
  end

  def("badgeItems", function()
    local out = {}
    for i, badge in ipairs(badgeList()) do out[i] = badge.item end
    return out
  end)
  def("darkMaps", function() return {} end)

  -- Every set flag by its Emerald name: the store's keys are numbers, numeric
  -- strings, "0x..." strings or names (scripting/flags.lua:377-414).
  local function flagNames(store, t)
    local out = {}
    if type(store) ~= "table" or type(t) ~= "table" then return out end
    local names, ids = t.NAMES or {}, t.IDS or {}
    for k, v in pairs(store.flags or {}) do
      if v == true then
        local id = tonumber(k)
        if not id and type(k) == "string" then id = ids[k] end
        local name = id and names[id] or nil
        if b.symbol(name) then out[name] = true end
      end
    end
    return out
  end

  -- The bag as { SYMBOL = qty } (session.bag.pockets = { POCKET = {{id, qty}} },
  -- src/core/game3/bag.lua:9-27), symbols without the ITEM_ prefix (Gen 1's
  -- item spelling); the set badges as { <NAME>BADGE = 1 }.
  local function inventory(s, store)
    local out = {}
    local C = b.constants()
    local pockets = type(s.bag) == "table" and type(s.bag.pockets) == "table" and s.bag.pockets or {}
    for _, list in pairs(pockets) do
      for _, row in ipairs(type(list) == "table" and list or {}) do
        local id, qty = type(row) == "table" and tonumber(row.id) or nil, type(row) == "table" and tonumber(row.qty) or 0
        local name = id and C and b.ask(C, "name", nil, C, "items", id) or nil
        if type(name) == "string" then
          local sym = name:gsub("^ITEM_", "")
          if b.symbol(sym) and qty and qty > 0 then out[sym] = (out[sym] or 0) + math.floor(qty) end
        end
      end
    end
    local Flags = b.g3("scripting.flags")
    for _, badge in ipairs(badgeList()) do
      local on = false
      if badge.flag and store then
        local ok, v = b.call(Flags, "getFlag", store, nil, badge.flag)
        on = ok and v == true
      end
      if on then out[badge.item] = 1 end
    end
    return out
  end

  -- The party's records as they are (snapshot reads species, level, moves,
  -- hp, maxHp, status; uidOf knows them by identity), and the Pokedex by
  -- species (session.dex.seen/owned, dex.lua:184-204).
  local function partyOf(s)
    local out = {}
    for _, mon in ipairs(type(s.party) == "table" and s.party or {}) do
      if type(mon) == "table" and #out < 6 then out[#out + 1] = mon end
    end
    return out
  end
  local function dexOf(s)
    local dex = type(s.dex) == "table" and s.dex or {}
    local seen, owned = {}, {}
    for species, v in pairs(type(dex.seen) == "table" and dex.seen or {}) do
      if v then seen[species] = true end
    end
    for species, v in pairs(type(dex.owned) == "table" and dex.owned or {}) do
      if v then owned[species] = true end
    end
    return { seen = seen, owned = owned }
  end

  -- Built at most once per logic step (setGame counts the steps); G3F's
  -- writes (the bag, money, the party) drop it (b.dropView).
  local cachedTick, cachedSession, cachedView = -1, nil, nil
  function b.dropView() cachedView = nil end
  def("saveView", function()
    local s = b.session()
    if type(s) ~= "table" then return nil end
    if cachedTick == b.tick and cachedSession == s and cachedView then return cachedView end
    local store, t = b.flagStore(), b.flagTables()
    local coins = b.ask(type(b.g3("bag")) == "table" and b.g3("bag").Coins or nil, "get", 0, s)
    local view = {
      player = { map = s.map, x = tonumber(s.x), y = tonumber(s.y), facing = s.facing,
                 name = type(s.name) == "string" and s.name or nil },
      flags = flagNames(store, t),
      inventory = inventory(s, store),
      money = tonumber(s.money) or 0,
      coins = tonumber(coins) or 0,
      party = partyOf(s),               -- the shadow of Cobblemon's party (G3F)
      pokedex = dexOf(s),
      options = type(s.options) == "table" and s.options or nil,
      meta = type(s.meta) == "table" and s.meta or nil,
      version = type(s.version) == "string" and s.version or nil,
      flashLit = false,
    }
    cachedTick, cachedSession, cachedView = b.tick, s, view
    return view
  end)
end

return Boot
