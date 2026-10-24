-- kb_progress: one hidden game's story progress, game.status (docs/PROTOCOL.md
-- "Regions (MG2)", docs/specs/MULTI_GAME_PLAN.md §4.7).
--
--   game.status{game, playthrough, ingame, started, badges, markers, name?, slot?}
--
-- Built from the adapter: the running game's id, the playthrough as the
-- pairing judges it ("title": no live game; "fresh": a New Game never
-- saved; "saved": loaded by CONTINUE or saved since), whether the overworld
-- is up, the badges held (the badge items in the bag), the game profile's
-- markers (protocol/games.json `markers`: starter, champion, ... -> a flag
-- each adapter resolves in its own namespace, kb_engine.progressMarkers),
-- the save's player name while the game is started (the player's own text,
-- marked ROM-derived like all text) and the active save slot's number (0:
-- the flat legacy save). At the title the live save is the boot skeleton,
-- so badges, markers and the name say nothing (0, false, absent). `region` is never set here: the client tags each
-- link's messages with its region.
--
-- Sent after every snapshot, after session.bound, on save.created and
-- save.loaded (main.lua calls changed()), and whenever the status changes
-- (compared every EVERY steps), to client and test sessions only.
-- Logs show counts only.

local Progress = {}

Progress.EVERY = 10
Progress.MAX_NAME = 16
Progress.MAX_BADGES = 16

local MARKER = "^[a-z][a-z0-9_]*$"
local GAME_ID = "^[a-z][a-z0-9_]*$"

-- opts:
--   engine        the engine adapter (gameVersion, inGame, save, badgeItems,
--                 progressMarkers, saveSlot, playthrough)
--   games         lib/kb_games.lua (the catalogue: games[id].markers)
--   playthrough() the pairing's view (kb_pairing.playthrough), or nil for the
--                 adapter's
--   visitors()    FP2: how many party members are visitors (optional)
--   json          kb_json
--   log           kb_log logger (optional)
function Progress.new(opts)
  local engine, games, json, log = opts.engine, opts.games, opts.json, opts.log
  local playthrough = opts.playthrough or function() return engine.playthrough() end
  local obs = {}
  local steps = 0
  local dirty = true
  local lastKey
  local counts = { sent = 0, changes = 0 }

  -- The status now, or nil when the game names no catalogue id.
  function obs.status()
    local game = engine.gameVersion()
    if type(game) ~= "string" or #game > 16 or not game:find(GAME_ID) then return nil end
    local pt = playthrough()
    if pt ~= "title" and pt ~= "fresh" and pt ~= "saved" then pt = "title" end
    -- at the title the live save is the boot skeleton: nothing of it counts
    local started = pt ~= "title"
    local save = started and engine.saveView() or nil
    local inventory = type(save) == "table" and type(save.inventory) == "table" and save.inventory or {}
    local badges = 0
    for _, item in ipairs(engine.badgeItems() or {}) do
      if inventory[item] then badges = badges + 1 end
    end
    if badges > Progress.MAX_BADGES then badges = Progress.MAX_BADGES end
    local entry = type(games) == "table" and type(games.games) == "table" and games.games[game] or nil
    local names = type(entry) == "table" and type(entry.markers) == "table" and entry.markers or {}
    local markers = json.object()
    local resolved = started and engine.progressMarkers(names) or {}
    for name in pairs(names) do
      if type(name) == "string" and #name <= 16 and name:find(MARKER) then markers[name] = resolved[name] == true end
    end
    local data = {
      game = game,
      playthrough = pt,
      ingame = engine.inGame() == true,
      started = started,
      badges = badges,
      markers = markers,
    }
    if started then
      local p = type(save) == "table" and type(save.player) == "table" and save.player or nil
      local name = p and p.name
      if type(name) == "string" and #name >= 1 and #name <= Progress.MAX_NAME then data.name = name end
    end
    -- FP2 (FOREIGN_POKEMON_PLAN §4.1): the visitors in the party
    if started and type(opts.visitors) == "function" then
      local n = opts.visitors()
      if type(n) == "number" and n >= 0 and n <= 6 and n == math.floor(n) then data.visitors = n end
    end
    local slot = engine.saveSlot()
    if type(slot) == "number" and slot >= 0 and slot <= 255 and slot == math.floor(slot) then data.slot = slot end
    return data
  end

  -- A snapshot went out: the status follows it.
  function obs.rebase() dirty = true end

  -- The save changed hands (New Game, CONTINUE, a bind answered): say so.
  function obs.changed() dirty = true end

  -- out:  this step's messages
  -- link: { live }
  function obs.step(out, link)
    steps = steps + 1
    if not (link and link.live) then return end
    if not dirty and steps % Progress.EVERY ~= 0 then return end
    local data = obs.status()
    if data == nil then return end
    local key = json.encode(data)
    if dirty or key ~= lastKey then
      if lastKey ~= nil and key ~= lastKey then counts.changes = counts.changes + 1 end
      out[#out + 1] = { "game.status", data, "stream" }
      counts.sent = counts.sent + 1
      lastKey, dirty = key, false
    end
  end

  function obs.stats()
    return { sent = counts.sent, changes = counts.changes }
  end

  return obs
end

return Progress
