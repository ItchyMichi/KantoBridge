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
    Warp = import("observe/kb_warp.lua"),
    Snapshot = import("observe/kb_snapshot.lua"),
    Move = import("act/kb_move.lua"),
    Cells = import("export/kb_cells.lua"),
    Images = import("export/kb_images.lua"),
    SwapSites = import("export/kb_swapsites.lua"),
    Export = import("export/kb_export.lua"),
  }

  local log = kb.Log.new({
    write = kb.Log.modWriter(mod.log),
    hash = kb.sha256.short,
    debug = mod.developer == true,
  })
  local engine = kb.Engine.new({ require = require, log = log, ui = mod.ui, datasets = mod.datasets })

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
  local warps = kb.Warp.new({ log = log })
  local lastFacts = { inGame = false, top = "none" }
  local pendingDropped

  -- The step driver (act/kb_move.lua) presses through the supported
  -- mod.input (src/mods/Loader.lua:1393-1431) with the live game.
  local mover = kb.Move.new({
    engine = engine,
    Player = kb.Player,
    log = log,
    forceSync = player.forceSync,
    press = function(btn) return mod.input:press(engine.game(), btn) end,
    release = function(token) return mod.input:release(token) end,
  })

  -- Engine events and the player's collision verdicts between two of our
  -- steps; each step takes them (they all happen during the stack update
  -- that follows input.step, src/core/Game.lua:320 vs :356).
  local EVENT_CAP = 16
  local events = { verdicts = {}, entered = {} }
  local mapsEntered = 0
  local function takeEvents()
    local taken = events
    events = { verdicts = {}, entered = {} }
    return taken
  end
  local function capped(list, item)
    if #list >= EVENT_CAP then table.remove(list, 1) end
    list[#list + 1] = item
  end

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

  local session, exporter
  local function debugStatus()
    local save = engine.save()
    local flags = 0
    for _, v in pairs(save and type(save.flags) == "table" and save.flags or {}) do
      if v == true then flags = flags + 1 end
    end
    local stats = session.stats()
    local out = {
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
      mapsEntered = mapsEntered,
    }
    for k, v in pairs(exporter.status()) do out[k] = v end
    -- step driver and warp counts (moveRequests, moveAnswered, moveDropped, ...)
    for k, v in pairs(mover.stats()) do out["move" .. k:sub(1, 1):upper() .. k:sub(2)] = v end
    for k, v in pairs(warps.stats()) do out["warp" .. k:sub(1, 1):upper() .. k:sub(2)] = v end
    return out
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

  -- The world export (builder and test roles): lazy, budgeted, cached for
  -- this instance's lifetime.
  exporter = kb.Export.new({
    json = kb.json, sha256 = kb.sha256, log = log, clock = clock, engine = engine,
    Cells = kb.Cells, Images = kb.Images, SwapSites = kb.SwapSites, catalog = kb.catalog,
    bridge = mod.version,
  })
  exporter.attach(session)

  -- Movement requests (docs/PROTOCOL.md "Movement"), client and test roles
  -- only. They queue in the step driver, which answers move.step in its own
  -- step with fresh facts.
  local function movement(handler, what)
    return function(data, msg, role)
      if not kb.Session.STREAM_ROLES[role] then
        session.sendError("role", msg.seq, "movement needs role client or test")
        return
      end
      if not handler(data, session.generation()) then
        session.sendError("bad_message", msg.seq, "bad " .. what)
      end
    end
  end
  session.on("move.step", movement(mover.request, "move.step"))
  session.on("move.face", movement(mover.face, "move.face"))
  session.on("move.hold", movement(mover.hold, "move.hold"))

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
    session.step() -- received move.* requests queue in the driver
    local exported = exporter.step()
    local facts = engine.observe()
    lastFacts = facts
    local ev = takeEvents()
    local out = player.update(facts) -- player.state, then player.pos
    -- warp start/arrive go after player.state and before player.pos (the
    -- arrival's sync follows its warp message)
    local warpOut = warps.step(facts, ev)
    if #warpOut > 0 then
      local at = (out[1] and out[1][1] == "player.state") and 2 or 1
      for i = #warpOut, 1, -1 do table.insert(out, at, warpOut[i]) end
    end
    -- the step driver answers its requests in `out`, then presses or
    -- releases for this logic step
    mover.step(facts, kb.Player.classify(facts), ev, out, {
      live = session.established() and kb.Session.STREAM_ROLES[session.role()] == true,
      generation = session.generation(),
    })
    for i = 1, #out do session.stream(out[i][1], out[i][2]) end
    if pendingDropped then
      log.once("snapshot.dropped", "warn", "snapshot.dropped", { values = pendingDropped })
      pendingDropped = nil
    end
    if #out > 0 or exported then session.flush() end
    if steps % PIN_EVERY == 0 then pinOptions(true) end
    status.connected = session.connected()
    status.bound = session.listening()
    status.role = session.role()
    status.sessions = session.sessions()
    status.exportState = exporter.state()
    local moves, warped = mover.stats(), warps.stats()
    status.moveRequests, status.moveAnswered = moves.requests, moves.answered
    status.moveDropped, status.moveRepressed = moves.dropped, moves.repressed
    status.warpsStarted, status.warpsArrived = warped.started, warped.arrived
    status.mapsEntered = mapsEntered
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

  -- The mod merge is done and nothing is on the stack yet
  -- (src/mods/Loader.lua:2163, src/core/Game.lua:52-119): the export checks
  -- that its dataset view opens, and copies the live Data now if it doesn't.
  mod.events:on("mods.loaded", function(payload) exporter.onModsLoaded(payload) end)
  -- From the first map load on, the live Data may differ from the import
  -- (map.entered, src/world/OverworldController.lua:686).
  mod.events:on("map.entered", function() exporter.onMapEntered() end)

  -- Warps and steps (M3). Events and hooks only: they die with this
  -- instance's loader on a hot reload. player.warped comes from takeWarp
  -- before anything moves (OverworldController.lua:5306-5309), so the
  -- departure is read right there; map.entered carries `via` (:686-691);
  -- world.blacked_out precedes the heal-point warp (:5212-5214).
  mod.events:on("player.warped", function()
    events.warped = engine.warpDeparture() or {}
  end)
  mod.events:on("map.entered", function(payload)
    mapsEntered = mapsEntered + 1
    payload = type(payload) == "table" and payload or {}
    capped(events.entered, { map = payload.mapId, via = payload.via })
  end)
  mod.events:on("world.blacked_out", function() events.blackout = true end)
  -- Every collision verdict passes this hook (src/world/Collision.lua:84-96);
  -- the player's are recorded for the step driver, and the verdict itself is
  -- never changed.
  mod.hooks:wrap("movement.collision", function(nextFn, allowed, ctx)
    local result = nextFn(allowed, ctx)
    local ok, verdict = pcall(engine.playerVerdict, ctx, result)
    if ok and verdict then capped(events.verdicts, verdict) end
    return result
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
