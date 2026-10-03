-- gen1-bridge: the Gen1Recomp half of Kanto Bridge (docs/PLAN.md section 4.1).
--
-- The entry chunk only loads modules and subscribes to hooks and events. It
-- never opens a socket: a throwing entry chunk is rolled back, but raw
-- sockets are not. The listener binds lazily on the first input.step.
--
-- Modules are plain Lua files loaded with mod:read + load, which compiles them
-- into this mod's sandbox. Never require our own files: require would run
-- them with the engine's globals and cache them across hot reloads.
--
-- Everything runs from input.step (src/core/Game.lua:320), about 60 times a
-- second; core.update doesn't fire under test drivers. Hooks and events die
-- with the loader on a hot reload, so nothing here outlives its instance.
return function(mod)
  local loaded = 0

  local function import(rel)
    local source, err = mod:read(rel)
    if type(source) ~= "string" then
      error(("gen1-bridge: cannot read %s (%s)"):format(rel, tostring(err)), 0)
    end
    local chunk, compileErr = load(source, "@" .. rel)
    if not chunk then error(compileErr, 0) end
    loaded = loaded + 1
    return chunk()
  end

  local kb = {
    json = import("lib/kb_json.lua"),
    sha256 = import("lib/kb_sha256.lua"),
    Log = import("lib/kb_log.lua"),
    Net = import("lib/kb_net.lua"),
    Proto = import("lib/kb_proto.lua"),
    Engine = import("lib/kb_engine.lua"),
    catalog = import("lib/kb_catalog.lua"),
    Session = import("lib/kb_session.lua"),
    Player = import("observe/kb_player.lua"),
    Snapshot = import("observe/kb_snapshot.lua"),
  }

  local log = kb.Log.new({
    write = kb.Log.modWriter(mod.log),
    hash = kb.sha256.short,
    debug = mod.developer == true,
  })
  local engine = kb.Engine.new({ require = require, log = log, ui = mod.ui })

  -- The port is a mod option (F10 manager); drivers can move it through
  -- exports.configure before the first bind.
  mod.options:define({
    { key = "port", label = "BRIDGE PORT", type = "number", default = 27151, min = 1024, max = 65535 },
  })
  local port = tonumber(mod.options:get("port")) or 27151
  if port ~= math.floor(port) or port < 1 or port > 65535 then port = 27151 end

  -- love.timer passes the sandbox's love facade (src/mods/Sandbox.lua:100-108).
  local timer = love.timer
  local function clock() return timer.getTime() end

  -- A fresh id per start, so a client can tell a restart from a reconnect.
  local sessionId = "s-" .. kb.sha256.short(table.concat({
    tostring(os.time()), tostring(clock()), tostring({}), tostring(math.random()),
  }, ":"))

  -- Read by our test drivers as game.mods.exports["gen1-bridge"]: versions,
  -- counts and flags only.
  local status = mod.exports
  status.bridge = mod.version
  status.protocol = kb.Proto.PROTOCOL
  status.modules = loaded
  status.ready = false
  status.port = port
  status.connected, status.bound, status.role = false, false, nil
  status.sessions, status.quitRequested = 0, false

  local player = kb.Player.new()
  local lastFacts = { inGame = false, top = "none" }
  local pendingDropped

  local function contentRecord(registry, id)
    local reg = mod.content and mod.content[registry]
    if reg == nil or id == nil then return nil end
    local ok, record = pcall(reg.get, reg, id)
    return ok and type(record) == "table" and record or nil
  end

  local function snapshot()
    local facts = engine.observe()
    lastFacts = facts
    player.rebase(facts)
    local npcs, npcMap = engine.npcs()
    local data, dropped = kb.Snapshot.build({
      json = kb.json,
      Player = kb.Player,
      facts = facts,
      save = engine.save(),
      darkMaps = engine.darkMaps(),
      badgeItems = engine.badgeItems(),
      npcs = npcs,
      npcMap = npcMap,
      -- species and moves through the supported content API: pokemon.dex,
      -- moves.index (docs/modding/reference/registries.md)
      dexOf = function(species)
        local r = contentRecord("pokemon", species)
        return r and tonumber(r.dex)
      end,
      moveNum = function(moveId)
        local r = contentRecord("moves", moveId)
        return r and tonumber(r.index)
      end,
    })
    -- logged at the end of the step: a forwarded `log` must not land between
    -- hello.ok and the snapshot
    if dropped > 0 then pendingDropped = dropped end
    return data
  end

  local session
  local function debugStatus()
    local save = engine.save()
    local flags = 0
    for _, v in pairs(save and type(save.flags) == "table" and save.flags or {}) do
      if v == true then flags = flags + 1 end
    end
    local stats = session.stats()
    return {
      npcs = lastFacts.npcs or 0,
      topState = lastFacts.topName or "None",
      mode = kb.Player.classify(lastFacts),
      flags = flags,
      ingame = lastFacts.inGame == true,
      port = session.port(),
      accepted = stats.accepted,
      sessions = stats.sessions,
      steps = engine.logicStep(),
      optionsPinned = engine.displayPinned(save and save.options),
    }
  end

  session = kb.Session.new({
    json = kb.json,
    Net = kb.Net,
    Proto = kb.Proto,
    catalog = kb.catalog,
    socket = function() return require("socket") end,
    port = port,
    clock = clock,
    log = log,
    bridge = mod.version,
    engine = function() return (engine.version()) end,
    game = engine.gameVersion,
    sessionId = sessionId,
    snapshot = snapshot,
    status = debugStatus,
    onQuit = function(code)
      status.quitRequested = true
      status.quitCode = code
    end,
  })
  log.setForward(session.forwardLog)

  status.session = sessionId
  status.configure = function(opts)
    if type(opts) == "table" and opts.port ~= nil then
      session.setPort(opts.port)
      status.port = opts.port
    end
  end

  local PIN_EVERY = 60 -- steps between checks that the display pin still holds
  local steps = 0
  local function pinOptions(reapply)
    local save = engine.save()
    local changed = engine.pinDisplay(save and save.options, reapply)
    if changed > 0 then log.info("options.pinned", { changed = changed }) end
  end

  local function step(game)
    engine.setGame(game)
    steps = steps + 1
    session.step()
    local facts = engine.observe()
    lastFacts = facts
    local out = player.update(facts)
    for i = 1, #out do session.stream(out[i][1], out[i][2]) end
    if pendingDropped then
      log.once("snapshot.dropped", "warn", "snapshot.dropped", { values = pendingDropped })
      pendingDropped = nil
    end
    if #out > 0 then session.flush() end
    if steps % PIN_EVERY == 0 then pinOptions(true) end
    status.connected = session.connected()
    status.bound = session.listening()
    status.role = session.role()
    status.sessions = session.sessions()
  end

  -- input.step must always call next, and a throwing wrapper would be
  -- skipped by the hook bus on every call (src/mods/Hooks.lua:62-104), so
  -- the step is guarded and its errors are logged at most every 5 seconds.
  local errorAt = -math.huge
  mod.hooks:wrap("input.step", function(nextFn, game, dt)
    local ok, err = pcall(step, game)
    if not ok and clock() - errorAt >= 5 then
      errorAt = clock()
      log.error("bridge.step_failed", { at = type(err) == "string" and err:match("[%w_/%.%-]+%.lua:%d+") or "unknown" })
    end
    return nextFn(game, dt)
  end)

  mod.events:on("game.ready", function(payload)
    engine.setGame(payload and payload.game)
    -- A hot reload or a return to the launcher leaves the previous instance's
    -- sockets open. Close them through the slot it left on the Game object,
    -- before this instance binds, and collect once for anything else it held.
    if engine.takeOver(session.close) then log.info("bridge.replaced_instance", {}) end
    collectgarbage("collect")
    local version, api = engine.version()
    engine.checkPin()
    pinOptions(true)
    status.engine = version
    status.game = engine.gameVersion()
    status.ready = true
    log.info("game.ready", { engine = version, api = api, modules = loaded })
  end)

  -- New Game builds a fresh save and applies its options right after this
  -- event (src/core/Game.lua:179-183), so pinning the table is enough.
  mod.events:on("save.created", function(payload)
    local save = payload and payload.save
    local changed = engine.pinDisplay(type(save) == "table" and save.options or nil, false)
    if changed > 0 then log.info("options.pinned", { changed = changed }) end
  end)

  -- CONTINUE re-applies the stored options before this event
  -- (src/core/Game.lua:1502 vs :1533), so pin and re-apply.
  mod.events:on("save.loaded", function() pinOptions(true) end)

  mod.log:info("gen1-bridge loaded")
end
