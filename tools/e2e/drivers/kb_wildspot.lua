-- Kanto Bridge test driver (Wild Routes, WR2's spot): finds where Viridian
-- City's clearing can go near the map's bottom-right corner. After New Game
-- without the intro (src/core/Game.lua:171-198) it runs gen1-bridge's own exit
-- planner over the town, loaded from the mod's file (lib/kb_routes.lua has no
-- requires): Routes.plan for every block and the facings right and down, with
-- the cells the clearing must not cover (Routes.occupiedCells: warps, signs,
-- objects, hidden items), then Routes.check on the patched record. A spot
-- counts only when the town's doors reach its block on foot (a walk over the
-- engine's own walkable cells, Map.new: src/world/Map.lua:112). The best few,
-- nearest the bottom-right corner first:
--   KB_SPOT rank=<n> x=<cell> y=<cell> facing=<dir> corner=<cells> edge=<bool>
-- then KB_SPOTS found=<count> w=<cells> h=<cells>, and the driver ends (exit 0).
-- `edge`: the clearing's last block touches the map's border.
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- (refs/gen1recomp/main.lua:641-645, :993-1011). Data safety: prints only
-- markers, cells, counts and directions -- never a block, a tile or any text.

io.stdout:setvbuf("no") -- LÖVE block-buffers stdout; this also unbuffers mod.log

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27205
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or 300
local MAP = os.getenv("KB_SPOT_MAP") or "VIRIDIAN_CITY"
local DEPTH = tonumber(os.getenv("KB_SPOT_DEPTH") or "") or 2
local SHOW = 8
local FACINGS = { "right", "down" }
local NEIGHBOURS = { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } }

local function word(v)
  local s = tostring(v)
  return (s:gsub("[^%w_.:+%-]", "?"))
end

local function fail(reason)
  print("KB_FAIL " .. reason)
  love.event.quit(1)
  while true do coroutine.yield() end
end

local function overworld(game)
  local states = game.stack and game.stack.states or {}
  for i = #states, 1, -1 do
    local s = states[i]
    if type(s) == "table" and s.isOverworld then return s end
  end
  return nil
end

-- gen1-bridge's own planner, from the repository this driver sits in
local function loadRoutes()
  local me = os.getenv("POKEPORT_DRIVER") or ""
  local repo = me:match("^(.*)[/\\]tools[/\\]e2e[/\\]drivers[/\\][^/\\]+$")
  if not repo then return nil end
  local chunk = loadfile(repo .. "/gen1-bridge/lib/kb_routes.lua")
  return chunk and chunk() or nil
end

local function query(Map, def, ts)
  local ok, m = pcall(Map.new, def, ts)
  if not ok or type(m) ~= "table" then return nil end
  return {
    walkable = function(x, y) return m:isWalkableCell(x, y) and true or false end,
    water = function(x, y) return m:isWaterCell(x, y) and true or false end,
  }
end

-- The cells the town's doors reach on foot.
local function reached(def, q, w, h)
  local seen, queue = {}, {}
  local function push(x, y)
    local k = y * w + x
    if x >= 0 and y >= 0 and x < w and y < h and not seen[k] and q.walkable(x, y) then
      seen[k] = true
      queue[#queue + 1] = { x, y }
    end
  end
  for _, wp in ipairs(def.warps or {}) do
    if type(wp.x) == "number" and type(wp.y) == "number" then
      push(wp.x, wp.y)
      for _, d in ipairs(NEIGHBOURS) do push(wp.x + d[1], wp.y + d[2]) end
    end
  end
  local i = 1
  while i <= #queue do
    local c = queue[i]
    for _, d in ipairs(NEIGHBOURS) do push(c[1] + d[1], c[2] + d[2]) end
    i = i + 1
  end
  return seen
end

local function find(game, Routes)
  local def = game.data.maps[MAP]
  local ts = def and game.data.tilesets[def.tileset]
  if not (def and ts) then fail("no_map map=" .. word(MAP)) end
  local Map = require("src.world.Map")
  local q = query(Map, def, ts)
  if not q then fail("no_query") end
  local w, h = def.width * 2, def.height * 2
  local field = game.data.field
  local hidden = field and type(field.hiddenItems) == "table" and field.hiddenItems[MAP] or nil
  local occupied = Routes.occupiedCells(def, hidden)
  local seen = reached(def, q, w, h)
  local found = {}
  for by = 0, def.height - 1 do
    for bx = 0, def.width - 1 do
      local reachable = false
      for _, c in ipairs({ { 2 * bx, 2 * by }, { 2 * bx + 1, 2 * by }, { 2 * bx, 2 * by + 1 }, { 2 * bx + 1, 2 * by + 1 } }) do
        if seen[c[2] * w + c[1]] then reachable = true end
      end
      if reachable then
        for fi, facing in ipairs(FACINGS) do
          local e = { from = { map = MAP, x = 2 * bx + 1, y = 2 * by + 1, facing = facing, depth = DEPTH } }
          local p = Routes.plan(e, def, q, occupied)
          if p then
            local patched = {}
            for k, v in pairs(def) do patched[k] = v end
            patched.blocks = p.blocks
            local q2 = query(Map, patched, ts)
            if q2 and Routes.check(p, q2) then
              local last = p.cleared[#p.cleared]
              found[#found + 1] = {
                x = e.from.x, y = e.from.y, facing = facing, order = fi,
                corner = (w - 1 - e.from.x) + (h - 1 - e.from.y),
                edge = last[1] == 0 or last[2] == 0 or last[1] == def.width - 1 or last[2] == def.height - 1,
              }
            end
          end
        end
      end
    end
  end
  table.sort(found, function(a, b)
    if a.corner ~= b.corner then return a.corner < b.corner end
    if a.order ~= b.order then return a.order < b.order end
    if a.y ~= b.y then return a.y > b.y end
    return a.x > b.x
  end)
  for i = 1, math.min(SHOW, #found) do
    local s = found[i]
    print(("KB_SPOT rank=%d x=%d y=%d facing=%s corner=%d edge=%s"):format(i, s.x, s.y, s.facing, s.corner,
      tostring(s.edge)))
  end
  print(("KB_SPOTS found=%d w=%d h=%d depth=%d"):format(#found, w, h, DEPTH))
end

return function(game)
  local started = love.timer.getTime()
  local phase = "ready"
  local Routes = loadRoutes()
  if not (Routes and Routes.plan and Routes.check and Routes.occupiedCells) then fail("no_planner") end
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      if status and status.ready then
        status.configure({ port = PORT })
        game:startNewGame({ intro = false })
        phase = "world"
      end
    elseif phase == "world" then
      local ow = overworld(game)
      if ow and game.stack:top() == ow and ow.map then
        find(game, Routes)
        return
      end
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase)
    end
    game = coroutine.yield() or game
  end
end
