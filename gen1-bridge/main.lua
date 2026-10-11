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
    Routes = import("lib/kb_routes.lua"),
    catalog = import("lib/kb_catalog.lua"),
    Session = import("lib/kb_session.lua"),
    Perf = import("lib/kb_perf.lua"),
    Player = import("observe/kb_player.lua"),
    Warp = import("observe/kb_warp.lua"),
    Snapshot = import("observe/kb_snapshot.lua"),
    Npcs = import("observe/kb_npcs.lua"),
    Story = import("observe/kb_story.lua"),
    Ui = import("observe/kb_ui.lua"),
    Mirror = import("observe/kb_mirror.lua"),
    Bag = import("observe/kb_bag.lua"),
    Move = import("act/kb_move.lua"),
    Interact = import("act/kb_interact.lua"),
    Field = import("act/kb_field.lua"),
    UiAct = import("act/kb_uiact.lua"),
    Battle = import("act/kb_battle.lua"),
    Party = import("act/kb_party.lua"),
    Save = import("act/kb_save.lua"),
    Pairing = import("act/kb_pairing.lua"),
    Pc = import("act/kb_pc.lua"),
    Route = import("act/kb_route.lua"),
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
  -- M10: the love facade too (love.window and love.timer for running hidden)
  local engine = kb.Engine.new({ require = require, log = log, ui = mod.ui, datasets = mod.datasets, love = love })

  -- Wild Routes (WR2: lib/kb_routes.lua, docs/specs/WILD_ROUTES_PLAN.md
  -- §4.2): the zones' exits, route maps, step scripts and trainer classes
  -- are registered here, in the entry chunk, before the engine merges the
  -- registries (src/mods/Loader.lua:2040-2060). A zone that doesn't check out
  -- is switched off; nothing here can fail the entry chunk.
  local routesText = mod:read("data/routes.json")
  local okRoutes, routes = pcall(kb.Routes.install, {
    json = kb.json, text = type(routesText) == "string" and routesText or nil,
    content = mod.content, world = mod.world, game = engine.gameVersion(),
    mapQuery = engine.mapQuery, log = log,
  })
  if not okRoutes then
    routes = nil
    log.error("routes.install_failed", {})
  end

  -- The port is a mod option (F10 manager, or the launcher's mod settings);
  -- drivers can move it through exports.configure before the first bind. A
  -- change in the F10 manager applies at once (mod.options_changed, below).
  -- M9: when another process keeps the port, the listener takes the next free
  -- one of the PORT_SPAN after it (kb_net), so a second instance on the same
  -- PC still listens; exports.port and debug.status say which.
  local PORT_SPAN = 8
  local function validPort(v)
    v = tonumber(v)
    if v and v == math.floor(v) and v >= 1024 and v <= 65535 then return v end
    return nil
  end
  mod.options:define({
    { key = "port", label = "BRIDGE PORT", type = "number", default = 27151, min = 1024, max = 65535, step = 1 },
  })
  local port = validPort(mod.options:get("port")) or 27151

  -- love.timer passes the sandbox's love facade (src/mods/Sandbox.lua:100-108).
  local timer = love.timer
  local function clock() return timer.getTime() end

  -- M10: gen1-bridge's own cost per logic step and per drawn frame
  -- (lib/kb_perf.lua): every hook and event handler below is timed into it.
  local perf = kb.Perf.new({ clock = clock })

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
  -- Wild Routes (WR2): zones installed at boot
  status.routesInstalled = 0
  status.port, status.portConfigured, status.portFallback = port, port, false
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
  local saves -- act/kb_save.lua, below (M10: it counts the Pokemon operations party sends)
  local party = kb.Party.new({
    engine = engine, log = log, markup = kb.Ui.markup, clock = clock,
    store = {
      get = function(key) return mod.save:get(key) end,
      set = function(key, value) mod.save:set(key, value) end,
    },
    onOp = function() saves.opSent() end,
  })
  -- M6: the bag's battle items mirrored into Minecraft (P17)
  local bagObs = kb.Bag.new({ engine = engine, json = kb.json, log = log })
  -- M7: saving (save.begin/done, the Pokemon Center's heal and save),
  -- session pairing, and Bill's PC as Cobblemon's PC. The bound world is
  -- kept in mod.save too (save.modData, src/core/Game.lua:1314-1324). M10:
  -- every save's pair is written into its own mod data (save.begin.pair,
  -- save.loaded).
  saves = kb.Save.new({
    engine = engine, log = log, hex = kb.sha256.hex, seed = sessionId, clock = clock,
    store = {
      get = function(key) return mod.save:get(key) end,
      set = function(key, value) mod.save:set(key, value) end,
    },
  })
  local pcs = kb.Pc.new({ engine = engine, log = log })
  -- Wild Routes (WR2): the zones' acts and progress (act/kb_route.lua); the
  -- progress is kept in mod.save "routes" (save.modData, so it loads and
  -- saves with the hidden game)
  local routeActs = kb.Route.new({
    engine = engine, routes = routes, world = mod.world, json = kb.json, log = log,
    store = {
      get = function(key) return mod.save:get(key) end,
      set = function(key, value) mod.save:set(key, value) end,
    },
    has = function(registry, id) return contentRecord(registry, id) ~= nil end,
  })
  -- M9: online save pairing (act/kb_pairing.lua): session.bind's world gets
  -- its own save slot, switched to at runtime; the world -> slot map lives in
  -- mod.cache (installation-wide, src/mods/Loader.lua:1567-1569), never in
  -- a slot's own save, as session/pairings.json.
  local cache = mod.cache
  local pairing = kb.Pairing.new({
    engine = engine, json = kb.json, log = log, clock = clock, hash = kb.sha256.short,
    store = type(cache) == "table" and {
      read = function(rel) return cache:read(rel) end,
      write = function(rel, bytes) return cache:write(rel, bytes) end,
      remove = function(rel) return cache:delete(rel) end,
    } or nil,
    onBound = function(world, multiplayer, slot) saves.bound(world, multiplayer, slot) end,
    -- what Cobblemon sent before a switching bind is the old world's
    onPending = function() party.forget() end,
    -- a New Game moved to the paired slot as it is: its gifts, operations
    -- and bag go to the new world again
    onSwitch = function(kind)
      if kind == "fresh" then
        party.rebase()
        bagObs.rebase()
      end
    end,
  })
  -- M8: the story diffs (story.flags, story.toggles, story.blocks,
  -- story.lit) and field moves from Minecraft (act.field)
  local story = kb.Story.new({ engine = engine, SwapSites = kb.SwapSites, json = kb.json, log = log })
  local field = kb.Field.new({ engine = engine, log = log })

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
    -- M8: the story's full state (effective toggles, the current map's
    -- differing blocks, lit maps); its diffs start from here
    local storyParts = story.rebase()
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
      story = storyParts,
      -- Wild Routes (WR2): every zone's progress; its diffs start from here
      routes = routeActs.progress(),
    })
    routeActs.rebase()
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
      -- M9: the port asked for, and whether the listener fell back from it
      portConfigured = session.configuredPort(),
      portFallback = session.portFallback(),
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
    merge("save", saves.stats())
    -- M9: pairingBinds, pairingCreated, pairingSwitches, pairingPending, ...
    merge("pairing", pairing.stats())
    merge("pc", pcs.stats())
    merge("story", story.stats())
    merge("field", field.stats())
    -- Wild Routes (WR2): the acts (routeRequests, routeGiven, routeWins, ...)
    -- and the boot's zones (routesListed, routesInstalled, routesSkipped, ...)
    merge("route", routeActs.stats())
    merge("routes", routes and routes.stats or {})
    -- M10: our own cost per logic step and per drawn frame (perfSteps,
    -- perfAvgUs, perfMaxUs, perfOver1ms, perfTotalUs, perfFrames,
    -- perfDrawAvgUs, ...), running hidden (hiddenActive, hiddenFaked,
    -- hiddenVisible, hiddenMinimized, ...), the session's hello counts
    merge("perf", perf.stats())
    merge("hidden", engine.hiddenStats())
    local hello = {}
    for _, k in ipairs({ "rejected", "rejectedProtocol", "rejectedRole", "rejectedMalformed", "helloTimeouts",
                         "timeouts", "untested", "badFirstLines" }) do
      hello[k] = stats[k]
    end
    merge("session", hello)
    for k, v in pairs(engine.bridgeCounts()) do out[k] = v end
    -- M8 (wave 2): direct battles, native catches, trades, the daycare,
    -- battle music
    for k, v in pairs(engine.contentCounts()) do out[k] = v end
    out.screenEvents = screenEvents
    -- M7: money, the bag's size and the beaten trainers, as counts
    local s = type(save) == "table" and save or {}
    out.money = math.floor(tonumber(s.money) or 0)
    local badges = {}
    for _, item in ipairs(engine.badgeItems()) do badges[item] = true end
    local bagTotal, bagKinds = 0, 0
    for item, qty in pairs(type(s.inventory) == "table" and s.inventory or {}) do
      if type(qty) == "number" and not badges[item] then
        bagTotal, bagKinds = bagTotal + math.floor(qty), bagKinds + 1
      end
    end
    out.bagTotal, out.bagKinds = bagTotal, bagKinds
    local defeated = 0
    for _, v in pairs(type(s.defeatedTrainers) == "table" and s.defeatedTrainers or {}) do
      if v == true then defeated = defeated + 1 end
    end
    out.defeated = defeated
    -- M8: the badges held and the coins, as counts
    local badgeCount = 0
    for _, item in ipairs(engine.badgeItems()) do
      if type(s.inventory) == "table" and s.inventory[item] then badgeCount = badgeCount + 1 end
    end
    out.badges = badgeCount
    out.coins = math.floor(tonumber(s.coins) or 0)
    -- the names typed into the hidden game: lengths and a short hash only
    local p = save and type(save.player) == "table" and save.player or {}
    for key, slot in pairs({ playerName = "name", rivalName = "rival" }) do
      local name = p[slot]
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
    portSpan = PORT_SPAN,
    clock = clock,
    log = log,
    bridge = mod.version,
    engine = function() return (engine.version()) end,
    -- M10: hello.ok.tested, and engine.untested when the running one differs
    tested = kb.Engine.TESTED,
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
    bridge = mod.version, Routes = kb.Routes, routes = routes,
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
  -- M8: field moves (act/kb_field.lua), answered by act.result
  session.on("act.field", queued(field.request))
  session.on("act.button", queued(uiAct.button))
  -- Wild Routes (WR2): item balls, trainer battles and blackouts the server
  -- decides, answered by route.result (act/kb_route.lua)
  for _, op in ipairs({ "give", "battle", "blackout" }) do
    session.on("route." .. op, queued(function(data, seq, gen) return routeActs.request(op, data, seq, gen) end))
  end
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
  -- (M9: not while a save-slot switch waits: the bag isn't the new world's yet)
  session.on("bag.use", answered(function(data)
    if pairing.blocked() then return false, "session.pending" end
    return bagObs.use(data)
  end))
  -- M7: Minecraft saved (log only), Cobblemon's PC closed
  session.on("save.done", answered(saves.done))
  session.on("pc.closed", answered(pcs.closed))
  -- M9: session pairing (act/kb_pairing.lua). The target slot is decided at
  -- once (so what arrives after the bind is the new world's); the switch and
  -- session.bound{slot} (re set) come in the step, once the hidden game is on
  -- that slot.
  session.on("session.bind", function(data, msg, role)
    if not streamRole(msg, role) then return end
    local ok, code = pairing.bind(data, msg.seq, session.generation())
    if not ok then session.sendError(code or "bad_message", msg.seq, code or "bad_message") end
  end)

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
  -- (M10: every hook and event handler is timed into kb_perf; perf.hook
  -- leaves the engine's own work in nextFn out)
  mod.hooks:wrap("script.command", perf.hook(function(nextFn, ctx, name, args)
    return engine.scriptCommand(nextFn, ctx, name, args, bridge)
  end))
  mod.hooks:wrap("trainer.before_battle", perf.hook(function(nextFn, g, context, continueBattle)
    return engine.trainerBeforeBattle(nextFn, g, context, continueBattle, bridge)
  end))
  -- M6 (lib/kb_engine.lua "wild encounters, Pokedex, bag, items"): wild
  -- encounters (grass and indoor rolls suppressed for the habitat pools,
  -- Safari and ghost battles native, surfing and fishing held for
  -- Cobblemon) and items used on a party member (stones and RARE_CANDY
  -- applied by Cobblemon, TM/HM moves watched). Hooks only, so a hot reload
  -- drops them with this instance.
  mod.hooks:wrap("encounter.roll", perf.hook(function(nextFn, encDef, ctx)
    return engine.encounterRoll(nextFn, encDef, ctx, bridge)
  end))
  mod.hooks:wrap("encounter.fishing", perf.hook(function(nextFn, rod, mapId, pool)
    return engine.encounterFishing(nextFn, rod, mapId, pool, bridge)
  end))
  mod.hooks:wrap("item.use", perf.hook(function(nextFn, g, battle, id, target, list, moveIndex, picker)
    return engine.itemUse(nextFn, g, battle, id, target, list, moveIndex, picker, bridge)
  end))
  -- M7 (lib/kb_engine.lua "the Pokemon Center, saving, the PC, sessions"):
  -- the Pokemon Center PC's rows (OC:3664): the box row opens Cobblemon's
  -- PC while a client or test session is connected. A hook, so a hot
  -- reload drops it; a menu already built keeps asking the current
  -- instance through kb_engine's slot.
  mod.hooks:wrap("ui.pc.items", perf.hook(function(nextFn, g, items)
    return engine.pcItems(nextFn, g, items)
  end))
  -- M8 (lib/kb_engine.lua "direct battles, native catches, the daycare,
  -- renames"): the A press on an object (world.talk, OC:2561-2566) and a
  -- completed step (world.stepped, OC:4675-4678) arm the text boxes they
  -- push, so a battle their continuation pushes directly (a static
  -- Pokemon object, the ghost Marowak with the SILPH_SCOPE, Giovanni in
  -- the Rocket Hideout) is fought in Cobblemon; pokemon.caught (a native
  -- catch: the Safari Zone) crosses over as a gift. Hooks and events only.
  mod.hooks:wrap("world.talk", perf.hook(function(nextFn, ow, target)
    return engine.worldTalk(nextFn, ow, target)
  end))
  mod.events:on("world.stepped", perf.timed(function() pcall(engine.directStepped) end))
  mod.events:on("pokemon.caught", perf.timed(function(payload) pcall(engine.nativeCaught, payload) end))
  -- Every hidden-game save (Game:writeSave emits save.writing before the
  -- file is written, src/core/Game.lua:1342-1345): Minecraft saves too
  -- (except the one before an M9 slot switch).
  mod.events:on("save.writing", perf.timed(function() saves.writing() end))

  status.session = sessionId
  status.configure = function(opts)
    if type(opts) == "table" and opts.port ~= nil then
      session.setPort(opts.port)
      status.port, status.portConfigured = session.port(), session.configuredPort()
    end
  end
  -- M9, for test drivers: the slot id paired with a world, the home slot id,
  -- and forgetting a world's pairing (its slot stays; the driver deletes it)
  status.pairedSlot = function(world) return pairing.slotOf(world) end
  status.homeSlot = function() return pairing.home() end
  status.forgetWorld = function(world) return pairing.forget(world) end
  -- M10, for test drivers: start the per-step cost counts over (a scenario's
  -- own numbers, without the boot, the export or the handshake)
  status.perfReset = function() perf.reset() end

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
    local live = session.established() and kb.Session.STREAM_ROLES[session.role()] == true
    local generation = session.generation()
    -- M9: a bind's save-slot switch runs before anything looks at the game
    -- (it may save the old game and return to the title); its
    -- session.bound (or error) goes out first in this step
    local pairOut = {}
    pairing.step(pairOut, { live = live, generation = generation })
    local blocked = pairing.blocked()
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
      for i = #warpOut, 1, -1 do
        table.insert(out, at, warpOut[i])
        -- M7: a blackout's party.heal follows its warp's arrival (this
        -- step's party.* go out after the warp messages)
        local w = warpOut[i][2]
        if w.phase == "arrive" and w.kind == "blackout" then engine.releaseHeals() end
      end
    end
    for i = #pairOut, 1, -1 do table.insert(out, 1, pairOut[i]) end
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
    field.step(facts, mode, out, { live = live, generation = generation, moverBusy = ctx.moverBusy,
                                   interactBusy = interact.busy() })
    -- Wild Routes (WR2): route.result and route.progress. Guarded like the
    -- story: a fault there must not stop what follows in this step.
    local okRoute, routeErr = pcall(routeActs.step, facts, mode, out, { live = live, generation = generation })
    if not okRoute then
      log.once("route.step_failed", "error", "route.step_failed",
        { at = type(routeErr) == "string" and routeErr:match("[%w_/%.%-]+%.lua:%d+") or "unknown" })
    end
    npcObs.step(npcRaw, npcMap, npcGen, emote, out, live)
    -- M8: story.blocks / story.lit every step, story.flags / story.toggles
    -- every few steps, when a script ends and on a map load. Guarded: the
    -- story reads a lot of save data, and a fault there must not stop the
    -- UIs, battles and saves that follow in this step.
    local okStory, storyErr = pcall(story.step, facts, out, { live = live, generation = generation })
    if not okStory then
      log.once("story.step_failed", "error", "story.step_failed",
        { at = type(storyErr) == "string" and storyErr:match("[%w_/%.%-]+%.lua:%d+") or "unknown" })
    end
    uiObs.step(wake, out)
    wake = false
    uiAct.step(facts, mode, out, ctx)
    -- M5: gifts, heals and (M6) items used on a member first (a gift goes
    -- out before a battle that may follow it), then the mirrored bag (its
    -- balls before a battle that may use them), (M7) a save's save.begin
    -- and Bill's PC's pc.open, then a native special battle's
    -- battle.native and a held battle's battle.start. A nurse heal is
    -- noticed first, so its party.heal goes out this very step. (M9: while
    -- a save-slot switch waits, syncs and save announcements wait too.)
    local link = { live = live, generation = generation, blocked = blocked }
    saves.watch(link)
    party.step(out, link)
    bagObs.step(out, link)
    saves.step(out, link)
    -- M10: which save the hidden game runs from, after a bind and on
    -- entering the game, behind this step's party.* and save.begin
    saves.loadedStep(out, link)
    pcs.step(out, link)
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
    -- M10 (DESIGN section 9, running hidden): while a client or test session
    -- is connected, a window that isn't shown keeps the normal frame cap
    -- (lib/kb_engine.lua "reliability (M10)"); released when the link drops
    engine.runHidden(live, clock())
    status.connected = session.connected()
    status.bound = session.listening()
    -- M9: the port listened on (after a fallback, not the configured one)
    status.port, status.portConfigured = session.port(), session.configuredPort()
    status.portFallback = session.portFallback()
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
    -- M7, for test drivers: saves announced, sessions bound, the PC hold
    local sv = saves.stats()
    status.saves, status.centerSaves, status.sessionBinds = sv.begun, sv.centerWritten, sv.binds
    status.pcHeld = engine.pcHeld()
    -- M9: the active save slot (0: the flat legacy save) and a waiting switch
    status.slot, status.pairingPending = engine.saveSlot(), pairing.blocked()
    if steps % 30 == 0 then
      local u, n, s = uiObs.stats(), npcObs.stats(), mirror.stats()
      status.uiOpened, status.uiClosed, status.uiOpen = u.opened, u.closed, u.open
      status.npcSets, status.npcMoves = n.sets, n.moves
      status.mirrorCaptures, status.mirrorFrames, status.mirrorAvgUs, status.mirrorMaxUs =
        s.captures, s.frames, s.avgUs, s.maxUs
      status.mirrorEncodeAvgUs, status.mirrorSkipped = s.encodeAvgUs, s.skipped
      status.mirrorPeakFps, status.mirrorCapped = s.peakFps, s.capped
      -- M10: our own cost per logic step
      local p = perf.stats()
      status.perfSteps, status.perfAvgUs, status.perfMaxUs, status.perfOver1ms =
        p.steps, p.avgUs, p.maxUs, p.over1ms
      status.perfTotalUs, status.perfFrames = p.totalUs, p.frames
    end
  end

  -- input.step must always call next, and a throwing wrapper would be
  -- skipped by the hook bus on every call (src/mods/Hooks.lua:62-104), so
  -- the step is guarded and its errors are logged at most every 5 seconds.
  local errorAt = -math.huge
  mod.hooks:wrap("input.step", function(nextFn, game, dt)
    -- M10: a new cost cycle starts here (the one before is complete)
    perf.beginStep()
    local t0 = clock()
    local ok, err = pcall(step, game)
    perf.add(clock() - t0)
    if not ok and clock() - errorAt >= 5 then
      errorAt = clock()
      log.error("bridge.step_failed", { at = type(err) == "string" and err:match("[%w_/%.%-]+%.lua:%d+") or "unknown" })
    end
    return nextFn(game, dt)
  end)

  mod.events:on("game.ready", perf.timed(function(payload)
    engine.setGame(payload and payload.game)
    -- A hot reload or a return to the launcher leaves the previous instance's
    -- sockets open. Close them through the slot it left on the Game object,
    -- before this instance binds, and collect once for anything else it held.
    -- (M10: its window wrapper for running hidden goes too.)
    local function closeMine()
      session.close()
      pcall(engine.runHidden, false)
    end
    if engine.takeOver(closeMine) then log.info("bridge.replaced_instance", {}) end
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
    -- Wild Routes (WR2): the progress of the save that is up (a hot reload)
    routeActs.reload()
    status.engine = version
    status.game = engine.gameVersion()
    status.routesInstalled = routes and routes.stats.installed or 0
    status.ready = true
    log.info("game.ready", { engine = version, api = api, modules = loaded })
  end))

  -- The mod merge is done and nothing is on the stack yet
  -- (src/mods/Loader.lua:2163, src/core/Game.lua:52-119): the export checks
  -- that its dataset view opens, and copies the live Data now if it doesn't.
  mod.events:on("mods.loaded", perf.timed(function(payload)
    -- M8: the map data the story diffs compare with, copied before any map
    -- loads (lib/kb_engine.lua "the story, departures, field moves")
    local ok, maps = pcall(engine.capturePristine, type(payload) == "table" and payload.data or nil)
    if ok then log.info("story.pristine", { maps = maps }) else log.warn("story.pristine_failed", {}) end
    exporter.onModsLoaded(payload)
  end))
  -- From the first map load on, the live Data may differ from the import
  -- (map.entered, src/world/OverworldController.lua:686).
  mod.events:on("map.entered", perf.timed(function() exporter.onMapEntered() end))

  -- Warps and steps (M3). Events and hooks only: they die with this
  -- instance's loader on a hot reload. player.warped comes from takeWarp
  -- before anything moves (OverworldController.lua:5306-5309), so the
  -- departure is read right there; map.entered carries `via` (:686-691);
  -- world.blacked_out precedes the heal-point warp (:5212-5214).
  mod.events:on("player.warped", perf.timed(function()
    events.warped = engine.warpDeparture() or {}
  end))
  mod.events:on("map.entered", perf.timed(function(payload)
    mapsEntered = mapsEntered + 1
    payload = type(payload) == "table" and payload or {}
    capped(events.entered, { map = payload.mapId, via = payload.via })
  end))
  -- a blackout heals the hidden party (OC:5204-5207); Cobblemon's heals too,
  -- once the blackout's warp has arrived (M7)
  mod.events:on("world.blacked_out", perf.timed(function()
    events.blackout = true
    engine.queueHeal("blackout")
  end))

  -- M4. Pushes and pops only wake the UI observer, which polls the stack
  -- itself (src/core/StateStack.lua:28-45 fire them after enter/exit);
  -- world.interacted reports what an A press hit (OC:2532-2535).
  mod.events:on("screen.pushed", perf.timed(function(payload)
    wake = true
    screenEvents = screenEvents + 1
    local state = type(payload) == "table" and payload.state or nil
    -- M6: the cast box of a bite gets the bite's continuation (kb_engine)
    pcall(engine.fishPushed, state)
    -- M8: a text box a talk or a step pushed gets the direct-battle seam
    pcall(engine.directPushed, state)
  end))
  mod.events:on("screen.popped", perf.timed(function()
    wake = true
    screenEvents = screenEvents + 1
  end))
  mod.events:on("world.interacted", perf.timed(function(payload)
    local ok, facts = pcall(engine.interactedFacts, payload)
    if ok and facts then capped(events.interacted, facts) end
  end))

  -- The mirror (observe/kb_mirror.lua): the output seam is asked for only
  -- while a client watches, and render.output stays a pure observer -- it
  -- always returns next(ctx), so the engine presents the frame itself
  -- (Renderer.lua:939-962, :1374-1419; docs/modding.md "render.output").
  -- Hooks die with this instance's loader on a hot reload.
  -- (M10: render.output_enabled is asked once per drawn frame while
  -- render.output has a wrapper, Renderer.lua:942-943; it counts the frames,
  -- and the capture's time is the frame's draw-phase cost in kb_perf)
  mod.hooks:wrap("render.output_enabled", function(nextFn, ...)
    perf.frame()
    if mirror.enabled() then return true end
    return nextFn(...)
  end)
  local captureErrorAt = -math.huge
  mod.hooks:wrap("render.output", function(nextFn, ctx)
    if mirror.enabled() then
      local t0 = clock()
      local ok, err = pcall(mirror.capture, ctx)
      perf.addDraw(clock() - t0)
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
  -- the player's are recorded for the step driver. The verdict is changed
  -- only on a Wild Routes route map, whose lower row is closed
  -- (kb_routes.closedMove): refused as a tile, like a wall.
  local function currentMapId()
    local ow = engine.overworld()
    return type(ow) == "table" and type(ow.map) == "table" and ow.map.id or nil
  end
  mod.hooks:wrap("movement.collision", function(nextFn, allowed, ctx)
    local result = nextFn(allowed, ctx)
    local t0 = clock()
    if result and routes ~= nil then
      local okClosed, closed = pcall(kb.Routes.closedMove, routes, ctx, currentMapId)
      if okClosed and closed then
        ctx.reason = "tile"
        result = false
        routes.stats.closed = routes.stats.closed + 1
      end
    end
    local ok, verdict = pcall(engine.playerVerdict, ctx, result)
    if ok and verdict then capped(events.verdicts, verdict) end
    perf.add(clock() - t0)
    return result
  end)

  -- New Game builds a fresh save and applies its options right after this
  -- event (src/core/Game.lua:179-183), so pinning the table is enough.
  mod.events:on("save.created", perf.timed(function(payload)
    local save = payload and payload.save
    local changed = engine.pinDisplay(type(save) == "table" and save.options or nil, false)
    if changed > 0 then log.info("options.pinned", { changed = changed }) end
    -- Cobblemon's party (the last party.sync) is the new game's party too
    party.reapply()
    -- the bound world goes into the new save's mod data (M7)
    saves.restore()
    -- M10: a fresh playthrough (save.loaded{fresh:true} once in the world)
    saves.created(save)
    -- Wild Routes (WR2): a new game has no progress
    routeActs.reload()
  end))

  -- CONTINUE re-applies the stored options before this event
  -- (src/core/Game.lua:1502 vs :1533), so pin and re-apply. The loaded
  -- party gets its uids back, then Cobblemon's party (the last sync) again.
  mod.events:on("save.loaded", perf.timed(function(payload)
    -- M9: a loaded game belongs to its slot (never "a New Game never saved")
    pairing.loaded(type(payload) == "table" and payload.save or nil)
    pinOptions(true)
    party.adopt()
    party.reapply()
    saves.restore()
    -- M10: the loaded save's own pair (save.loaded{fresh:false, pair})
    saves.loadedSave(type(payload) == "table" and payload.save or nil)
    -- Wild Routes (WR2): the loaded save's progress goes out whole
    routeActs.reload()
  end))

  -- M9: BRIDGE PORT changed in the F10 mod manager (ManagerState:setOption,
  -- src/mods/ManagerState.lua:1090-1112): the listener moves at once.
  mod.events:on("mod.options_changed", perf.timed(function(payload)
    if type(payload) ~= "table" or payload.mod ~= mod.id or payload.key ~= "port" then return end
    local p = validPort(payload.value)
    if p then
      session.setPort(p)
      log.info("net.port_option", { port = p })
    else
      log.warn("net.port_option_invalid", {})
    end
  end))

  mod.log:info("gen1-bridge loaded")
end
