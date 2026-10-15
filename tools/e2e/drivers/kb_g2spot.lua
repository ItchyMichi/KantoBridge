-- Kanto Bridge test driver (MG9 G2H: the Johto end of the test pass,
-- docs/specs/HOENN_PASS_PLAN.md K1's rules): finds where a Gen 2 town's
-- clearing can go. Once gen1-bridge is ready (at the title: the map data is
-- the import's, src/core/Game2.lua:1133-1145) it runs gen1-bridge's own Gen 2
-- exit planner over the town, loaded from the mod's file
-- (lib/engine/gen2/routes.lua's pure functions: Routes2.plan with
-- Routes2.kind over the town's tileset collision, src/world/gen2/
-- Permissions.lua; Routes2.occupied: warps, objects, bg and coord events) for
-- every block and the facings asked for. A spot counts only when the town's
-- warps reach its block on foot (a walk over the walkable cells,
-- Permissions.isWalkable) and every cell of it and its clearing stands at
-- least KB_SPOT_CLEAR cells (Manhattan) from every warp, object, bg event and
-- coord event. The best few, nearest the corner asked for first:
--   KB_SPOT rank=<n> x=<cell> y=<cell> facing=<dir> corner=<cells> edge=<bool> clear=<cells>
-- then KB_SPOTS found=<count> w=<cells> h=<cells> depth=<blocks>, and the driver ends (exit 0).
--
-- Env: KB_SPOT_MAP (the engine's id, default NEW_BARK_TOWN), KB_SPOT_DEPTH
-- (2), KB_SPOT_FACINGS ("up,down,left,right"), KB_SPOT_CORNER (se, sw, ne,
-- nw; default se), KB_SPOT_CLEAR (6), KB_SPOT_SHOW (8).
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- (refs/gen1recomp/main.lua:755-759). Data safety: prints only markers,
-- cells, counts and directions -- never a block, a tile or any text.

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27206
local EXTRA_SECONDS = tonumber(os.getenv("KB_DRIVER_EXTRA") or "") or 0
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (300 + EXTRA_SECONDS)
local MAP = os.getenv("KB_SPOT_MAP") or "NEW_BARK_TOWN"
local DEPTH = tonumber(os.getenv("KB_SPOT_DEPTH") or "") or 2
local SHOW = tonumber(os.getenv("KB_SPOT_SHOW") or "") or 8
local FACINGS = {}
for f in (os.getenv("KB_SPOT_FACINGS") or "up,down,left,right"):gmatch("[a-z]+") do FACINGS[#FACINGS + 1] = f end
local CORNER = os.getenv("KB_SPOT_CORNER") or "se"
local CLEAR = tonumber(os.getenv("KB_SPOT_CLEAR") or "") or 6
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

-- gen1-bridge's own Gen 2 planner, from the repository this driver sits in
local function loadPlanner()
  local me = os.getenv("POKEPORT_DRIVER") or ""
  local repo = me:match("^(.*)[/\\]tools[/\\]e2e[/\\]drivers[/\\][^/\\]+$")
  if not repo then return nil end
  local chunk = loadfile(repo .. "/gen1-bridge/lib/engine/gen2/routes.lua")
  return chunk and chunk() or nil
end

local function find(game, Routes2)
  local def = game.data.gen2Maps and game.data.gen2Maps[MAP]
  local ts = def and game.data.gen2Tilesets and game.data.gen2Tilesets[def.tileset]
  if not (def and ts) then fail("no_map map=" .. word(MAP)) end
  local okP, P = pcall(require, "src.world.gen2.Permissions")
  if not okP or type(P) ~= "table" then fail("no_permissions") end
  local function ask(m, fname, fallback, ...)
    if type(m) ~= "table" or type(m[fname]) ~= "function" then return fallback end
    local ok, v = pcall(m[fname], ...)
    if ok then return v end
    return fallback
  end
  local function kindOf(coll) return Routes2.kind(P, ask, coll) end
  local w, h = def.width * 2, def.height * 2
  local function coll(x, y)
    if x < 0 or y < 0 or x >= w or y >= h then return 0xff end
    local block = def.blocks[math.floor(y / 2) * def.width + math.floor(x / 2) + 1] or 0
    if block == 0 then return 0xff end
    local quad = ts.collision[block + 1]
    return quad and quad[(y % 2) * 2 + x % 2 + 1] or 0xff
  end
  local function walkable(x, y) return ask(P, "isWalkable", false, coll(x, y)) == true end
  -- the cells the town's warps reach on foot
  local seen, queue = {}, {}
  local function push(x, y)
    local k = y * w + x
    if x >= 0 and y >= 0 and x < w and y < h and not seen[k] and walkable(x, y) then
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
  local events = {}
  for _, list in ipairs({ def.warps, def.objects, def.bgEvents, def.coordEvents, def.signs }) do
    for _, o in ipairs(type(list) == "table" and list or {}) do
      if type(o) == "table" and type(o.x) == "number" and type(o.y) == "number" then events[#events + 1] = o end
    end
  end
  local occupied = Routes2.occupied(def)
  local function clearance(bx, by, p)
    local blocks = { { bx, by } }
    for _, c in ipairs(p.cleared) do blocks[#blocks + 1] = c end
    local best = math.huge
    for _, blk in ipairs(blocks) do
      for dy = 0, 1 do
        for dx = 0, 1 do
          local cx, cy = 2 * blk[1] + dx, 2 * blk[2] + dy
          for _, o in ipairs(events) do
            local d = math.abs(o.x - cx) + math.abs(o.y - cy)
            if d < best then best = d end
          end
        end
      end
    end
    return best == math.huge and 999 or best
  end
  local function cornerDistance(x, y)
    local cx = CORNER:find("w") and 0 or (w - 1)
    local cy = CORNER:find("n") and 0 or (h - 1)
    return math.abs(cx - x) + math.abs(cy - y)
  end
  local found = {}
  for by = 0, def.height - 1 do
    for bx = 0, def.width - 1 do
      local reachable = false
      for _, c in ipairs({ { 2 * bx, 2 * by }, { 2 * bx + 1, 2 * by }, { 2 * bx, 2 * by + 1 }, { 2 * bx + 1, 2 * by + 1 } }) do
        if seen[c[2] * w + c[1]] then reachable = true end
      end
      if reachable then
        for fi, facing in ipairs(FACINGS) do
          local f = { x = 2 * bx, y = 2 * by, facing = facing, depth = DEPTH }
          local p = Routes2.plan(f, def, ts, kindOf, occupied)
          local clear = p and clearance(bx, by, p) or -1
          if p and clear >= CLEAR then
            local last = p.cleared[#p.cleared]
            found[#found + 1] = {
              x = f.x, y = f.y, facing = facing, order = fi, clear = clear, corner = cornerDistance(f.x, f.y),
              edge = last[1] == 0 or last[2] == 0 or last[1] == def.width - 1 or last[2] == def.height - 1,
            }
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
  for n = 1, math.min(SHOW, #found) do
    local s = found[n]
    print(("KB_SPOT rank=%d x=%d y=%d facing=%s corner=%d edge=%s clear=%d"):format(n, s.x, s.y, s.facing, s.corner,
      tostring(s.edge), s.clear))
  end
  print(("KB_SPOTS found=%d w=%d h=%d depth=%d"):format(#found, w, h, DEPTH))
end

return function(game)
  local started = love.timer.getTime()
  local Routes2 = loadPlanner()
  if not (Routes2 and Routes2.plan and Routes2.kind and Routes2.occupied) then fail("no_planner") end
  while true do
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if status and status.ready and game.data and game.data.gen2Maps then
      status.configure({ port = PORT })
      find(game, Routes2)
      love.event.quit(0)
      while true do coroutine.yield() end
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      fail("timeout")
    end
    game = coroutine.yield() or game
  end
end
