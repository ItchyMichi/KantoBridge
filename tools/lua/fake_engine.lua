-- A tiny stand-in for the parts of Gen1Recomp v0.3.49 that gen1-bridge's
-- kb_engine reads: the Game object (stack, save, data), the overworld with
-- its player, and the engine modules it requires. Written for Kanto Bridge;
-- field names follow the engine (see lib/kb_engine.lua for the file:line of
-- each). Everything is synthetic: made-up maps, flags and items.
--
--   local E = FakeEngine.new()
--   bridge modules: E.modules (require name -> table), mod.ui: E.ui
--   E.enterWorld("TEST_TOWN", 5, 6, "down"); E.startStep("up"); E.land()
--   E.push(state) / E.pop(); E.text() makes a TextBox-like state

local FakeEngine = {}

local DIRS = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }

function FakeEngine.new(opts)
  opts = opts or {}
  local E = { applied = {} }

  E.Version = { engine = opts.engine or "0.3.49", modApi = 2 }
  E.GameVersion = { current = opts.game or "blue" }
  function E.GameVersion.get() return E.GameVersion.current end
  E.GameModule = { logicStep = 0 }
  E.Badges = {
    list = function() return { { id = "BOULDERBADGE" }, { id = "CASCADEBADGE" }, { id = "TESTBADGE", item = "TEST_BADGE_ITEM" } } end,
    itemFor = function(entry) return entry.item or entry.id end,
  }
  local function recorder(name)
    return { applyOptions = function(o)
      E.applied[#E.applied + 1] = name
      E.lastApplied = o
    end }
  end
  E.modules = {
    ["src.core.Version"] = E.Version,
    ["src.core.GameVersion"] = E.GameVersion,
    ["src.core.Game"] = E.GameModule,
    ["src.inventory.Badges"] = E.Badges,
    ["src.render.Tilt"] = recorder("Tilt"),
    ["src.render.Pipelines"] = recorder("Pipelines"),
    ["src.render.Zoom"] = recorder("Zoom"),
  }

  -- mod.ui module tables; instances use them as metatables
  E.ui = { TextBox = {}, Menu = {}, ListMenu = {}, ChoiceBox = {}, QuantityBox = {},
           NamingScreen = {}, PicBox = {} }

  local stack = { states = {} }
  function stack:top() return self.states[#self.states] end
  E.stack = stack

  E.save = {
    player = { map = "TEST_HOUSE", x = 3, y = 6, facing = "down", name = "TESTER" },
    flags = { EVENT_TEST_ONE = true, EVENT_TEST_OFF = false },
    objectToggles = {},
    inventory = { POTION = 2, BOULDERBADGE = true },
    money = 3000,
    coins = 0,
    party = {},
    pokedex = { seen = {}, owned = {} },
    options = {
      tilt = 2, zoom = -1, battleLayout = "wide", battleFit = "fill",
      uiLayout = "dynamic", textSpeed = 3, colors = "gbc",
      pipelines = { test_voxel = 2, test_off = 0 },
    },
  }
  E.data = { field = { darkMaps = { maps = { "TEST_CAVE" } } } }
  E.game = { stack = stack, save = E.save, data = E.data }

  -- the overworld module table: isOverworld, plus the fields kb_engine reads
  local runner = { running = false }
  function runner:isRunning() return self.running end
  E.ow = { isOverworld = true, scriptMoves = {}, npcs = {}, runner = runner, hopLand = 0 }

  function E.enterWorld(map, x, y, facing)
    E.ow.map = { id = map }
    E.ow.player = { cellX = x, cellY = y, facing = facing or "down", moving = false }
    stack.states = { E.ow }
  end

  function E.leaveWorld()
    stack.states = {}
  end

  function E.push(state) stack.states[#stack.states + 1] = state end
  function E.pop() stack.states[#stack.states] = nil end

  function E.text() return setmetatable({ isTextBox = true }, E.ui.TextBox) end
  function E.menu(screenId) return setmetatable({ screenId = screenId }, E.ui.Menu) end
  function E.transition() return {} end -- no marker: transient

  -- A step as Player:tryMove or updateScriptMoves starts it (targets, frames).
  function E.startStep(dir, frames)
    local p = E.ow.player
    local d = DIRS[dir]
    p.facing = dir
    p.targetX, p.targetY = p.cellX + d[1], p.cellY + d[2]
    p.stepFramesCur = frames or 16
    p.moving = true
  end

  -- Landing (Player.lua:246-248): snap to the target, clear it.
  function E.land()
    local p = E.ow.player
    p.cellX, p.cellY = p.targetX, p.targetY
    p.targetX, p.targetY = nil, nil
    p.moving = false
  end

  -- Land and start the next step within the same logic step, as a held
  -- direction does on the landing poll.
  function E.landAndContinue(dir, frames)
    E.land()
    E.startStep(dir, frames)
  end

  function E.npc(id, sprite, x, y, def)
    local npc = { id = id, def = def or { sprite = sprite }, cellX = x, cellY = y, facing = "down", moving = false }
    E.ow.npcs[#E.ow.npcs + 1] = npc
    return npc
  end

  return E
end

return FakeEngine
