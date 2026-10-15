-- kb_route: the Wild Routes acts (docs/PROTOCOL.md "Wild Routes (WR2)",
-- docs/specs/WILD_ROUTES_PLAN.md §4.2) and the zones' progress.
--
--   route.give{route, id, item, keep?} -> route.result{op:"give", route, id, ok, reason?}
--                                     (keep false, WR7: a dungeon's item, given
--                                     but not recorded as taken)
--   route.battle{route, id, class, party, lines?}
--                                     -> route.result{op:"battle", route, id, ok, outcome?, reason?}
--   route.blackout{}                  -> route.result{op:"blackout", ok, reason?}
--   route.progress{routes}            whenever a zone's progress changes, after
--                                     a save loads, and in the snapshot
--
-- The hidden game does the work with its own commands, through a queued
-- script (mod.world:queueScript, src/world/WorldAPI.lua:626-632) whose
-- onDone the script runner calls when the rows are done
-- (src/script/ScriptRunner.lua:120-141):
--   give      the engine's give_item (src/script/Commands.lua:250-292): the
--             bag, the jingle and the "got ITEM!" box, or the bag-full box
--             and a halt. The bag's count before and after says which.
--   battle    the zone's KB_<CLASS> record (lib/kb_routes.lua) gets the
--             party, then an optional line of the trainer's (show_text with
--             literal text, Commands.lua:81-91) and start_battle trainer,
--             which the bridge's script-battle path fights in Cobblemon
--             (lib/kb_engine.lua "battles"): prize money, music, the loss
--             line and the blackout as for any scripted trainer. The answer
--             waits for the battle's end and carries its outcome.
--   blackout  the engine's own loss handling (kb_engine.blackout): for wild
--             battles lost in Cobblemon, in zones and in Kanto.
-- give and battle need the hidden player on the zone's route map. All three
-- wait (up to WAIT steps) for an idle world, one at a time.
--
-- Progress (balls taken, trainers' wins) lives in the hidden save's mod data
-- (mod.save "routes"), so an older save brings older progress back with its
-- bag and money. "Beaten on this visit" is the server's to keep.
-- Logs show route ids, ops, codes and counts only.

local Route = {}

Route.WAIT = 120    -- steps a request waits for an idle world
Route.QUEUE = 8     -- requests waiting at most
Route.STALE = 30    -- steps a running request waits once the runner stopped without onDone (a script error)
Route.STORE_KEY = "routes"
Route.MAX_PARTY = 6
Route.MAX_LINE = 160

local floor = math.floor

local function sym(v, pattern)
  return type(v) == "string" and #v > 0 and #v <= 64 and v:find(pattern) ~= nil
end
local ROUTE_ID, OBJECT_ID, UPPER = "^[a-z][a-z0-9_]*$", "^[a-z0-9][a-z0-9_.%-]*$", "^[A-Z0-9_]+$"

-- opts:
--   engine    kb_engine instance (inventoryCount, writeTrainerParty,
--             scriptBattleResult, blackout)
--   routes    the kb_routes runtime (zones; nil when none installed)
--   world     mod.world (queueScript)
--   json      kb_json
--   store     { get(key), set(key, value) }: mod.save
--   has       function(registry, id) -> true when the content id exists
--   log       kb_log logger (optional)
function Route.new(opts)
  local engine, routes, world, json, store, has, log =
    opts.engine, opts.routes, opts.world, opts.json, opts.store, opts.has, opts.log
  local act = {}
  local queue = {}        -- requests waiting, oldest first
  local current           -- the request at the head: waiting, or its script running
  local generation
  local steps = 0
  local progress = {}     -- route id -> { taken = { [id] = true }, wins = { [id] = n } }
  local dirty = {}        -- route ids whose progress changed since it last went out
  local counts = { requests = 0, ok = 0, failed = 0, busy = 0, given = 0, full = 0, battles = 0, wins = 0,
                   losses = 0, blackouts = 0, progressSent = 0, stale = 0 }

  -- A route's zone in this game: its `from` end (a free-roam zone's only one) is keyed by the route's id, a
  -- connecting route's `to` end by "<id>:to" (lib/kb_routes.lua); the server names the route only, so the end is
  -- the one whose route map the hidden player stands on when this game holds both (a test world).
  local function zoneOf(id, map)
    local zones = routes and routes.zones
    if type(zones) ~= "table" or type(id) ~= "string" then return nil end
    local near, far = zones[id], zones[id .. ":to"]
    if near and far and map ~= nil and far.mapId == map then return far end
    return near or far
  end

  -- ------------------------------------------------------------ progress

  local function load()
    progress, dirty = {}, {}
    local ok, stored = pcall(store.get, Route.STORE_KEY)
    if not ok or type(stored) ~= "table" then return end
    for rid, p in pairs(stored) do
      if sym(rid, ROUTE_ID) and type(p) == "table" then
        local entry = { taken = {}, wins = {} }
        for id, v in pairs(type(p.taken) == "table" and p.taken or {}) do
          if v == true and sym(id, OBJECT_ID) then entry.taken[id] = true end
        end
        for id, n in pairs(type(p.wins) == "table" and p.wins or {}) do
          if sym(id, OBJECT_ID) and type(n) == "number" and n >= 1 and n == floor(n) then entry.wins[id] = n end
        end
        progress[rid] = entry
        dirty[rid] = true
      end
    end
  end

  local function save()
    local out = {}
    for rid, p in pairs(progress) do
      local taken, wins = {}, {}
      for id in pairs(p.taken) do taken[id] = true end
      for id, n in pairs(p.wins) do wins[id] = n end
      out[rid] = { taken = taken, wins = wins }
    end
    pcall(store.set, Route.STORE_KEY, out)
  end

  local function entryOf(rid)
    progress[rid] = progress[rid] or { taken = {}, wins = {} }
    return progress[rid]
  end

  -- One route's progress as the protocol shapes it.
  local function shape(p)
    local taken = {}
    for id in pairs(p.taken) do taken[#taken + 1] = id end
    table.sort(taken)
    local wins = json.object()
    for id, n in pairs(p.wins) do wins[id] = n end
    return json.object({ taken = json.array(taken), wins = wins })
  end

  -- Every route's progress, for the snapshot.
  function act.progress()
    local out = json.object()
    for rid, p in pairs(progress) do out[rid] = shape(p) end
    return out
  end

  -- A save was loaded or created (the hidden save's mod data changed): the
  -- progress is read again and goes out whole.
  function act.reload()
    load()
  end

  -- After a snapshot (which carried everything): nothing is owed.
  function act.rebase() dirty = {} end

  -- ------------------------------------------------------------ requests

  local function validParty(party)
    if type(party) ~= "table" or #party < 1 or #party > Route.MAX_PARTY then return nil end
    local out = {}
    for i, m in ipairs(party) do
      if type(m) ~= "table" or not sym(m.species, UPPER) or type(m.level) ~= "number" or m.level ~= floor(m.level)
          or m.level < 1 or m.level > 100 then
        return nil
      end
      local slot = { species = m.species, level = m.level }
      if m.moves ~= nil then
        if type(m.moves) ~= "table" or #m.moves < 1 or #m.moves > 4 then return nil end
        slot.moves = {}
        for k, mv in ipairs(m.moves) do
          if not sym(mv, UPPER) then return nil end
          slot.moves[k] = mv
        end
      end
      out[i] = slot
    end
    return out
  end

  -- route.give / route.battle / route.blackout data from the session (role
  -- already checked). Returns true; false plus "bad_message" when malformed.
  function act.request(op, data, seq, gen)
    local r = { op = op, seq = seq, gen = gen, since = steps }
    if op == "give" then
      if not (sym(data.route, ROUTE_ID) and sym(data.id, OBJECT_ID) and #data.id <= 32 and sym(data.item, UPPER)) then
        return false, "bad_message"
      end
      if data.keep ~= nil and type(data.keep) ~= "boolean" then return false, "bad_message" end
      r.route, r.id, r.item, r.keep = data.route, data.id, data.item, data.keep ~= false
    elseif op == "battle" then
      local party = validParty(data.party)
      if not (sym(data.route, ROUTE_ID) and sym(data.id, OBJECT_ID) and #data.id <= 32 and sym(data.class, UPPER)
              and party) then
        return false, "bad_message"
      end
      local line
      if data.lines ~= nil then
        if type(data.lines) ~= "table" then return false, "bad_message" end
        line = data.lines.before
        if line ~= nil and (type(line) ~= "string" or #line < 1 or #line > Route.MAX_LINE) then
          return false, "bad_message"
        end
      end
      r.route, r.id, r.class, r.party, r.line = data.route, data.id, data.class, party, line
    elseif op == "blackout" then
      -- nothing to check
    else
      return false, "bad_message"
    end
    counts.requests = counts.requests + 1
    if #queue >= Route.QUEUE then
      r.refused = true
      counts.busy = counts.busy + 1
    end
    queue[#queue + 1] = r
    return true
  end

  local function answer(out, r, ok, reason, outcome)
    local data = { op = r.op, ok = ok }
    if r.route then data.route = r.route end
    if r.id then data.id = r.id end
    if reason then data.reason = reason end
    if outcome then data.outcome = outcome end
    out[#out + 1] = { "route.result", data, "answer", re = r.seq, gen = r.gen }
    if ok then counts.ok = counts.ok + 1 else counts.failed = counts.failed + 1 end
    if log then log.debug("route.result", { op = r.op, route = r.route, ok = ok, reason = reason, outcome = outcome }) end
  end

  local function finish(out, ok, reason, outcome)
    local r = current
    current = nil
    answer(out, r, ok, reason, outcome)
  end

  -- Queues the request's rows; true once the script runs.
  local function queueRows(r, rows)
    if not (world and type(world.queueScript) == "function") then return false end
    r.done = false
    local ok, queued = pcall(world.queueScript, world, rows, { onDone = function() r.done = true end })
    return ok and queued == true
  end

  -- The world is free for a script: in the game, standing, nothing scripted,
  -- no warp, no battle.
  local function idle(f, mode)
    return f.inGame and mode == "idle" and not f.moving and not f.scripted and not f.transitioning
      and not f.battle and not f.forcedPhase
  end

  local function start(f, mode, out)
    local r = current
    if r.op ~= "blackout" then
      local zone = zoneOf(r.route, f.map)
      if not (zone and zone.active) then return finish(out, false, "unknown_route") end
      if not f.inGame then return finish(out, false, "not_ingame") end
      if f.map ~= zone.mapId then return finish(out, false, "not_in_zone") end
      -- (MG8: an adapter's own zone checks items and species itself, by its game's names)
      r.own = zone.own == true and type(rawget(engine, "routeRun")) == "function"
      if r.op == "give" then
        if r.keep and entryOf(r.route).taken[r.id] then return finish(out, false, "taken") end
        if has and not r.own and not has("items", r.item) then return finish(out, false, "unknown_item") end
      else
        r.kbClass = zone.classes[r.class]
        if not r.kbClass then return finish(out, false, "unknown_class") end
        for _, m in ipairs(r.party) do
          if has and not r.own and not has("pokemon", m.species) then return finish(out, false, "bad_party") end
        end
      end
    elseif not f.inGame then
      return finish(out, false, "not_ingame")
    end
    if not idle(f, mode) then
      if steps - r.since > Route.WAIT then return finish(out, false, "busy") end
      return -- wait for an idle world
    end
    if r.op == "blackout" then
      local ok, why = engine.blackout()
      if ok then
        counts.blackouts = counts.blackouts + 1
        return finish(out, true)
      end
      return finish(out, false, why or "failed")
    end
    if r.own then
      -- MG8 (docs/specs/HOENN_PASS_PLAN.md K4): the adapter gives the item or starts the battle itself and
      -- calls back once it is over (a battle with its result word); false plus a reason refuses
      r.done = false
      if r.op == "give" then r.before = engine.inventoryCount(r.item) end
      local req = { op = r.op, item = r.item, class = r.class, party = r.party, line = r.line,
                    onDone = function(result)
                      if result ~= nil then r.result = result end
                      r.done = true
                    end }
      local okRun, ran, why = pcall(rawget(engine, "routeRun"), r.op, req)
      if not okRun then return finish(out, false, "failed") end
      if ran ~= true then
        if why == "busy" and steps - r.since <= Route.WAIT then return end
        return finish(out, false, type(why) == "string" and why or "failed")
      end
      if r.op == "battle" then counts.battles = counts.battles + 1 end
      r.running = true
      return
    end
    if r.op == "give" then
      r.before = engine.inventoryCount(r.item)
      if not queueRows(r, { { "give_item", r.item, 1 } }) then
        if steps - r.since > Route.WAIT then return finish(out, false, "busy") end
        return
      end
    else
      if not engine.writeTrainerParty(r.kbClass, r.party) then return finish(out, false, "failed") end
      local rows = {}
      if r.line then rows[#rows + 1] = { "show_text", r.line } end
      rows[#rows + 1] = { "start_battle", "trainer", r.kbClass, 1 }
      if not queueRows(r, rows) then
        if steps - r.since > Route.WAIT then return finish(out, false, "busy") end
        return
      end
      counts.battles = counts.battles + 1
    end
    r.running = true
  end

  local function settle(out)
    local r = current
    if not r.done then return end
    if r.op == "give" then
      local after = engine.inventoryCount(r.item)
      if after > (r.before or 0) then
        if r.keep then
          entryOf(r.route).taken[r.id] = true
          dirty[r.route] = true
          save()
        end
        counts.given = counts.given + 1
        return finish(out, true)
      end
      if r.stale and r.stale >= Route.STALE then return finish(out, false, "failed") end
      counts.full = counts.full + 1
      return finish(out, false, "bag_full")
    end
    local result = r.result or engine.scriptBattleResult()
    local outcome = (result == "win" or result == "lose") and result or "abort"
    if outcome == "win" then
      local p = entryOf(r.route)
      p.wins[r.id] = (p.wins[r.id] or 0) + 1
      dirty[r.route] = true
      save()
      counts.wins = counts.wins + 1
    elseif outcome == "lose" then
      counts.losses = counts.losses + 1
    end
    return finish(out, true, nil, outcome)
  end

  -- f:    kb_engine.observe() for this step
  -- mode: kb_player.classify(f)
  -- out:  this step's messages (route.result with re and gen, route.progress)
  -- ctx:  { live, generation }
  function act.step(f, mode, out, ctx)
    steps = steps + 1
    if not (ctx and ctx.live) then
      queue, current, generation = {}, nil, nil
      return
    end
    if generation ~= ctx.generation then
      -- requests of an old connection are dropped; a running script finishes
      -- on its own (its answer has nowhere to go)
      local kept = {}
      for _, r in ipairs(queue) do
        if r.gen == ctx.generation then kept[#kept + 1] = r end
      end
      queue = kept
      if current and current.gen ~= ctx.generation and not current.running then current = nil end
      generation = ctx.generation
    end
    -- refusals first, then the head of the line
    local kept = {}
    for _, r in ipairs(queue) do
      if r.refused then answer(out, r, false, "busy") else kept[#kept + 1] = r end
    end
    queue = kept
    if current and current.running then
      if current.op == "battle" and not current.result then
        -- the script's own context keeps the outcome until onDone
        local res = engine.scriptBattleResult()
        if res then current.result = res end
      end
      -- a script that died on an error never calls onDone: once the runner
      -- has stood still for STALE steps, the request settles as it stands
      -- (MG9: an adapter's own run calls back itself, after its text boxes
      -- and its battle: its steps count only while the world stands free, so
      -- a line waiting for its press never ends it)
      if not current.done and not f.runner and (not current.own or idle(f, mode)) then
        current.stale = (current.stale or 0) + 1
        if current.stale >= Route.STALE then
          current.done = true
          counts.stale = counts.stale + 1
        end
      end
      settle(out)
    end
    if not current and #queue > 0 then
      current = table.remove(queue, 1)
    end
    if current and not current.running then start(f, mode, out) end
    -- progress that changed, in route id order
    local ids = {}
    for rid in pairs(dirty) do ids[#ids + 1] = rid end
    if #ids > 0 then
      table.sort(ids)
      local data = json.object()
      for _, rid in ipairs(ids) do data[rid] = shape(entryOf(rid)) end
      out[#out + 1] = { "route.progress", { routes = data }, "stream" }
      counts.progressSent = counts.progressSent + 1
      dirty = {}
    end
  end

  function act.busy() return current ~= nil end

  function act.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    local n = 0
    for _ in pairs(progress) do n = n + 1 end
    out.zones = n -- zones with progress in this save
    return out
  end

  return act
end

return Route
