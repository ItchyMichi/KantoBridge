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
    Npcs = import("observe/kb_npcs.lua"),
    Ui = import("observe/kb_ui.lua"),
    Mirror = import("observe/kb_mirror.lua"),
    Bag = import("observe/kb_bag.lua"),
    Move = import("act/kb_move.lua"),
    Interact = import("act/kb_interact.lua"),
    UiAct = import("act/kb_uiact.lua"),
    Battle = import("act/kb_battle.lua"),
    Party = import("act/kb_party.lua"),
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
  local events = { verdicts = {}, entered = {}, interacted = {} }
  local mapsEntered = 0
  local screenEvents = 0
  local function takeEvents()
    local taken = events
    events = { verdicts = {}, entered = {}, interacted = {} }
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
  -- species -> national dex through the supported content API (pokemon.dex,
  -- docs/modding/reference/registries.md)
  local function dexOf(species)
    local r = contentRecord("pokemon", species)
    return r and tonumber(r.dex)
  end

  -- M4: NPCs, UIs and their answers, interaction, the mirror.
  local npcObs = kb.Npcs.new({ json = kb.json, log = log })
  local uiObs = kb.Ui.new({ engine = engine, json = kb.json, log = log, dexOf = dexOf })
  local uiAct = kb.UiAct.new({
    engine = engine, ui = uiObs, log = log,
    press = function(btn) return mod.input:press(engine.game(), btn) end,
    release = function(token) return mod.input:release(token) end,
  })
  local interact = kb.Interact.new({ engine = engine, log = log })
  local mirror = kb.Mirror.new({ love = love, clock = clock, log = log, base64 = kb.Images.base64 })

  -- M5: battles fought in Cobblemon, gifts, heals and the shadow party.
  -- party uids persist through mod.save (save.modData, src/core/Game.lua:1314-1324).
  local battles = kb.Battle.new({ engine = engine, log = log, markup = kb.Ui.markup })
  local party = kb.Party.new({
    engine = engine, log = log, markup = kb.Ui.markup, clock = clock,
    store = {
      get = function(key) return mod.save:get(key) end,
      set = function(key, value) mod.save:set(key, value) end,
    },
  })
  -- M6: the bag's battle items mirrored into Minecraft (P17)
  local bagObs = kb.Bag.new({ engine = engine, json = kb.json, log = log })

  -- One injected tap per logic step (PLAN section 2.3), shared by the
  -- interaction and the UI answers: mod.input:tap with the live game
  -- (src/mods/Loader.lua:1393-1431), issued from input.step so the edge lands
  -- in this very step (src/core/Game.lua:320-321).
  local tapped = false
  local tapBudget = {
    can = function() return not tapped end,
    tap = function(btn)
      if tapped then return false end
      tapped = true
      local ok = pcall(mod.input.tap, mod.input, engine.game(), btn)
      if not ok then log.once("tap.failed:" .. btn, "warn", "input.tap_failed", { button = btn }) end
      return ok
    end,
  }

  local function snapshot()
    local facts = engine.observe()
    lastFacts = facts
    player.rebase(facts)
    -- the snapshot's NPC list is shaped like npc.set's (kb_npcs); the
    -- observers then send npc.set and the open UIs again, later in the same
    -- logic step (the snapshot is answered from session.step, before them)
    local raw, npcMap = engine.npcs()
    local npcs = raw and kb.Npcs.list(raw) or nil
    npcObs.rebase()
    uiObs.rebase()
    -- a pending battle, a native special battle, unconfirmed gifts and the
    -- mirrored bag go out again after it
    battles.rebase()
    party.rebase()
    bagObs.rebase()
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
      dexOf = dexOf,
      moveNum = function(moveId)
        local r = contentRecord("moves", moveId)
        return r and tonumber(r.index)
      end,
      uidOf = engine.uidOf,
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
    -- counts per service, prefixed: moveRequests, warpStarted, uiOpened,
    -- uiactPicked, npcMoves, interactOk, mirrorFrames, ...
    local function merge(prefix, stats)
      for k, v in pairs(stats) do
        if type(v) == "number" or type(v) == "boolean" then out[prefix .. k:sub(1, 1):upper() .. k:sub(2)] = v end
      end
    end
    merge("move", mover.stats())
    merge("warp", warps.stats())
    merge("ui", uiObs.stats())
    merge("uiact", uiAct.stats())
    merge("npc", npcObs.stats())
    merge("interact", interact.stats())
    merge("mirror", mirror.stats())
    merge("battle", battles.stats())
    merge("party", party.stats())
    merge("bag", bagObs.stats())
    merge("encounter", engine.encounterCounts())
    merge("item", engine.itemCounts())
    for k, v in pairs(engine.bridgeCounts()) do out[k] = v end
    out.screenEvents = screenEvents
    -- the names typed into the hidden game: lengths and a short hash only
    local p = save and type(save.player) == "table" and save.player or {}
    for key, field in pairs({ playerName = "name", rivalName = "rival" }) do
      local name = p[field]
      if type(name) == "string" then
        out[key .. "Len"] = #name
        out[key .. "Hash"] = tonumber(kb.sha256.short(name):sub(1, 7), 16) or 0
      end
    end
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

  -- M4 requests (docs/PROTOCOL.md "Interaction", "UI"), client and test
  -- roles only. They queue in their modules and are applied in the step,
  -- with fresh facts; answers that need one (act.result, late errors) go
  -- out in the step's message list with their `re`.
  local function streamRole(msg, role)
    if kb.Session.STREAM_ROLES[role] then return true end
    session.sendError("role", msg.seq, "needs role client or test")
    return false
  end
  local function queued(handler)
    return function(data, msg, role)
      if not streamRole(msg, role) then return end
      local ok, code = handler(data, msg.seq, session.generation())
      if not ok then session.sendError(code or "bad_message", msg.seq, code or "bad_message") end
    end
  end
  session.on("act.interact", queued(interact.request))
  session.on("act.button", queued(uiAct.button))
  for _, t in ipairs({ "ui.advance", "ui.pick", "ui.back", "ui.qty", "ui.name" }) do
    session.on(t, queued(function(data, seq, gen) return uiAct.request(t, data, seq, gen) end))
  end
  session.on("mirror.sub", function(data, msg, role)
    if not streamRole(msg, role) then return end
    local fps = data.fps
    if type(fps) ~= "number" or fps ~= math.floor(fps) or fps < 1 or fps > 30 then
      session.sendError("bad_message", msg.seq, "bad mirror.sub")
      return
    end
    mirror.subscribe(fps, session.generation())
  end)
  session.on("mirror.unsub", function(_, msg, role)
    if not streamRole(msg, role) then return end
    mirror.unsubscribe(session.generation())
  end)

  -- M5 (docs/PROTOCOL.md "Battles", "Party, Pokédex, bag, PC"), client and
  -- test roles: a battle's result applies at once (its texts are pushed in
  -- this step); a party.sync waits in kb_party for a safe moment.
  local function answered(handler)
    return function(data, msg, role)
      if not streamRole(msg, role) then return end
      local ok, code = handler(data)
      if not ok then session.sendError(code or "bad_message", msg.seq, code or "bad_message") end
    end
  end
  session.on("battle.result", answered(battles.result))
  session.on("party.sync", answered(party.sync))
  -- M6: Cobblemon's Pokedex, and items it used up
  session.on("dex.sync", answered(party.dex))
  session.on("bag.use", answered(bagObs.use))

  -- The battle and gift seams (lib/kb_engine.lua "battles, gifts, the
  -- shadow party"): script.command for the scripted battle rows, give_pokemon
  -- and heal_party; trainer.before_battle for map trainers. Hooks die with
  -- this instance's loader on a hot reload; what a held battle or a gift
  -- needs lives in kb_engine's process-lifetime slot. Battles are taken over
  -- only while a client or test session is connected.
  local bridge = {
    live = function() return session.established() and kb.Session.STREAM_ROLES[session.role()] == true end,
    battle = function(facts) return battles.begin(facts) end,
  }
  mod.hooks:wrap("script.command", function(nextFn, ctx, name, args)
    return engine.scriptCommand(nextFn, ctx, name, args, bridge)
  end)
  mod.hooks:wrap("trainer.before_battle", function(nextFn, g, context, continueBattle)
    return engine.trainerBeforeBattle(nextFn, g, context, continueBattle, bridge)
  end)
  -- M6 (lib/kb_engine.lua "wild encounters, Pokedex, bag, items"): wild
  -- encounters (grass and indoor rolls suppressed for the habitat pools,
  -- Safari and ghost battles native, surfing and fishing held for
  -- Cobblemon) and items used on a party member (stones and RARE_CANDY
  -- applied by Cobblemon, TM/HM moves watched). Hooks only, so a hot reload
  -- drops them with this instance.
  mod.hooks:wrap("encounter.roll", function(nextFn, encDef, ctx)
    return engine.encounterRoll(nextFn, encDef, ctx, bridge)
  end)
  mod.hooks:wrap("encounter.fishing", function(nextFn, rod, mapId, pool)
    return engine.encounterFishing(nextFn, rod, mapId, pool, bridge)
  end)
  mod.hooks:wrap("item.use", function(nextFn, g, battle, id, target, list, moveIndex, picker)
    return engine.itemUse(nextFn, g, battle, id, target, list, moveIndex, picker, bridge)
  end)

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

  local wake = true -- a screen was pushed or popped since the last step
  local function step(game)
    engine.setGame(game)
    steps = steps + 1
    tapped = false
    session.step() -- received requests queue in their modules
    local exported = exporter.step()
    local facts = engine.observe()
    lastFacts = facts
    local ev = takeEvents()
    local mode = kb.Player.classify(facts)
    local out = player.update(facts) -- player.state, then player.pos
    -- warp start/arrive go after player.state and before player.pos (the
    -- arrival's sync follows its warp message)
    local warpOut = warps.step(facts, ev)
    if #warpOut > 0 then
      local at = (out[1] and out[1][1] == "player.state") and 2 or 1
      for i = #warpOut, 1, -1 do table.insert(out, at, warpOut[i]) end
    end
    local live = session.established() and kb.Session.STREAM_ROLES[session.role()] == true
    local generation = session.generation()
    -- the step driver answers its requests in `out`, then presses or
    -- releases for this logic step
    mover.step(facts, mode, ev, out, { live = live, generation = generation })
    -- then the interaction (act.result), the NPCs (npc.*, emote), the UIs
    -- (ui.close, ui.open, ui.update), their answers (taps; act.result for
    -- act.button), and the newest mirror frame
    local npcRaw, npcMap, npcGen, emote
    if live then
      npcRaw, npcMap, npcGen = engine.npcs()
      emote = engine.emote()
    end
    local ctx = { live = live, generation = generation, budget = tapBudget, moverBusy = mover.busy() }
    interact.step(facts, mode, ev, npcRaw, out, ctx)
    npcObs.step(npcRaw, npcMap, npcGen, emote, out, live)
    uiObs.step(wake, out)
    wake = false
    uiAct.step(facts, mode, out, ctx)
    -- M5: gifts, heals and (M6) items used on a member first (a gift goes
    -- out before a battle that may follow it), then the mirrored bag (its
    -- balls before a battle that may use them), then a native special
    -- battle's battle.native and a held battle's battle.start
    local link = { live = live, generation = generation }
    party.step(out, link)
    bagObs.step(out, link)
    battles.step(out, link)
    mirror.step(out, { live = live, generation = generation, queued = session.queued() })
    for i = 1, #out do
      local m = out[i]
      if m.re ~= nil then
        -- a reply (act.result, a late error) belongs to the connection that asked
        if m.gen == generation and session.established() then session.reply({ seq = m.re }, m[1], m[2]) end
      else
        session.stream(m[1], m[2])
      end
    end
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
    status.mirrorOn = mirror.enabled()
    status.battleHeld = facts.battle == true
    if steps % 30 == 0 then
      local u, n, s = uiObs.stats(), npcObs.stats(), mirror.stats()
      status.uiOpened, status.uiClosed, status.uiOpen = u.opened, u.closed, u.open
      status.npcSets, status.npcMoves = n.sets, n.moves
      status.mirrorCaptures, status.mirrorFrames, status.mirrorAvgUs, status.mirrorMaxUs =
        s.captures, s.frames, s.avgUs, s.maxUs
      status.mirrorEncodeAvgUs, status.mirrorSkipped = s.encodeAvgUs, s.skipped
    end
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
    -- battle and gift ids carry the first instance's token (kept by the slot
    -- through hot reloads); the party's uids come back from mod.save; the
    -- slot points at this instance's bridge from now on (a fishing bite
    -- that was cast before a reload is answered here)
    engine.bridgeToken(sessionId:sub(3, 10))
    engine.attachBridge(bridge)
    party.adopt()
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
  -- a blackout heals the hidden party (OC:5204-5207); Cobblemon's heals too
  mod.events:on("world.blacked_out", function()
    events.blackout = true
    engine.queueHeal()
  end)

  -- M4. Pushes and pops only wake the UI observer, which polls the stack
  -- itself (src/core/StateStack.lua:28-45 fire them after enter/exit);
  -- world.interacted reports what an A press hit (OC:2532-2535).
  mod.events:on("screen.pushed", function(payload)
    wake = true
    screenEvents = screenEvents + 1
    -- M6: the cast box of a bite gets the bite's continuation (kb_engine)
    pcall(engine.fishPushed, type(payload) == "table" and payload.state or nil)
  end)
  mod.events:on("screen.popped", function()
    wake = true
    screenEvents = screenEvents + 1
  end)
  mod.events:on("world.interacted", function(payload)
    local ok, facts = pcall(engine.interactedFacts, payload)
    if ok and facts then capped(events.interacted, facts) end
  end)

  -- The mirror (observe/kb_mirror.lua): the output seam is asked for only
  -- while a client watches, and render.output stays a pure observer -- it
  -- always returns next(ctx), so the engine presents the frame itself
  -- (Renderer.lua:939-962, :1374-1419; docs/modding.md "render.output").
  -- Hooks die with this instance's loader on a hot reload.
  mod.hooks:wrap("render.output_enabled", function(nextFn, ...)
    if mirror.enabled() then return true end
    return nextFn(...)
  end)
  local captureErrorAt = -math.huge
  mod.hooks:wrap("render.output", function(nextFn, ctx)
    if mirror.enabled() then
      local ok, err = pcall(mirror.capture, ctx)
      if not ok then
        mirror.failed()
        if clock() - captureErrorAt >= 5 then
          captureErrorAt = clock()
          log.error("mirror.capture_failed", { at = type(err) == "string" and err:match("[%w_/%.%-]+%.lua:%d+") or "unknown" })
        end
      end
    end
    return nextFn(ctx)
  end)
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
    -- Cobblemon's party (the last party.sync) is the new game's party too
    party.reapply()
  end)

  -- CONTINUE re-applies the stored options before this event
  -- (src/core/Game.lua:1502 vs :1533), so pin and re-apply. The loaded
  -- party gets its uids back, then Cobblemon's party (the last sync) again.
  mod.events:on("save.loaded", function()
    pinOptions(true)
    party.adopt()
    party.reapply()
  end)

  mod.log:info("gen1-bridge loaded")
end
