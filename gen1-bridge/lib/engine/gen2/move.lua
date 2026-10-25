-- gen2 movement, warps and interaction (GEN2_BRIDGE_PLAN.md §5.3, §5.4).
--
-- Gen 2 raises movement.collision with Gen 1's contract (Player:tryMove,
-- src/world/gen2/Player.lua:97-136: ctx { map, mover, dir, fromX, fromY, toX,
-- toY, reason }, reasons bounds | tile | entity), so main.lua's wrapper hands
-- every verdict to playerVerdict, as on Gen 1; NPCs raise it too (Npc.lua:
-- 521-535), so only the player's mover counts. A ledge first arrives as a
-- refused `tile` and then hops (World.lua:10763-10765; G2-S2).
--
-- The mover turns the player first (setFacing, with no 4-frame turn) and then
-- holds the direction (mod.input:press): the field reads directions with
-- isDown only, so a tap never moves (World.lua:10368-10381; G2-S2).
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Move = {}

local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }

-- The warp a COLL byte under the player (or in front) is, as the protocol's
-- warp kinds (src/world/gen2/Permissions.lua:300-345; collision vocabulary in
-- research note g2_export_data_model.md §4): doors and cave mouths are doors,
-- staircases and ladders stairs, warp panels pads, pits holes, the exit mats
-- (carpets, entered by a press in their direction) edges.
Move.WARP_KINDS = {
  [0x71] = "door", [0x7b] = "door",
  [0x7a] = "stairs", [0x72] = "ladder", -- COLL_LADDER: ladder (FA8 item 16, D2)
  [0x7c] = "pad",
  [0x60] = "hole", [0x68] = "hole",
  [0x70] = "edge", [0x76] = "edge", [0x78] = "edge", [0x7e] = "edge",
}
Move.COUNTER = 0x90 -- COLL_COUNTER (the A press reaches across)

function Move.install(b)
  local def = b.def

  local function player()
    if not b.inField() then return nil end
    return b.ow().player
  end

  -- One movement.collision verdict as plain values when its mover is the
  -- player, else nil.
  def("playerVerdict", function(ctx, allowed)
    local p = player()
    if type(ctx) ~= "table" or p == nil or ctx.mover ~= p then return nil end
    local reason = ctx.reason
    return {
      allowed = allowed and true or false,
      dir = DELTA[ctx.dir] and ctx.dir or nil,
      reason = type(reason) == "string" and reason:find("^[a-z_]+$") and reason or nil,
    }
  end)

  -- Turns the standing player as the engine's own scripts do (Player:scriptFace,
  -- Player.lua:143-145); a held direction then steps at once, since tryMove turns
  -- only when the facing differs (:97-108). Refused mid-step. True when the
  -- player now faces dir.
  def("setFacing", function(dir)
    local p = player()
    if not p or p.moving or DELTA[dir] == nil then return false end
    if p.facing ~= dir then
      local ok = b.method(p, "scriptFace", dir)
      if not ok then p.facing = dir end
      p.bumpFrames = nil
    end
    return p.facing == dir
  end)

  -- True while any source holds the button (Input:isDown on game.input, the
  -- shared src/core/Input.lua Game2 reads, Game2.lua:23; mod.input presses into
  -- it, src/mods/Loader.lua:1466-1503).
  def("buttonDown", function(btn)
    local g = b.game
    local input = type(g) == "table" and g.input or nil
    if type(input) ~= "table" or type(input.isDown) ~= "function" then return false end
    local ok, down = pcall(input.isDown, input, btn)
    return ok and down == true
  end)

  -- Where a warp the player is taking leaves from: player.warped fires for
  -- walked warps before the fade and the load (World.lua:10514-10516), the
  -- player on the warp cell (or pressing into it from in front); the kind from
  -- the COLL byte there.
  def("warpDeparture", function()
    local p = player()
    if not p then return nil end
    local w = b.ow()
    local x, y, facing = p.cellX, p.cellY, p.facing
    local out = { map = b.ext(w.map.id), x = x, y = y, facing = facing }
    local function kindAt(cx, cy)
      local ok, c = b.method(w.map, "cellCollision", cx, cy)
      return ok and Move.WARP_KINDS[c] or nil
    end
    local kind = (type(x) == "number" and type(y) == "number") and kindAt(x, y) or nil
    local d = DELTA[facing]
    if not kind and d and type(x) == "number" then kind = kindAt(x + d[1], y + d[2]) end
    out.kind = kind or "door"
    return out
  end)

  -- Gen 2 pins no display options (gen1's classic pin is Gen 1's renderer).
  def("displayPinned", function() return true end)
  def("pinDisplay", function() return 0 end)

  -- ---- interaction (world.interacted: { mapId, x, y, kind, target },
  -- World.lua:8634-8642; kinds npc, trainer, boulder, itemball, sign, hidden,
  -- std, fieldmove, none)

  def("interactedFacts", function(payload)
    if type(payload) ~= "table" then return nil end
    local kind = type(payload.kind) == "string" and payload.kind:find("^[a-z_]+$") and #payload.kind <= 32
      and payload.kind or "none"
    local out = { map = b.ext(payload.mapId), x = tonumber(payload.x), y = tonumber(payload.y), kind = kind }
    local target = payload.target
    if type(target) == "table" and type(target.def) == "table" and tonumber(target.def.index) then
      out.npcId = ("O%d"):format(tonumber(target.def.index))
      out.npcMoving = target.moving == true
    end
    return out
  end)

  -- Whether (x, y) is a cell of the current map (Map:inBounds, Map.lua:41-44).
  def("cellInMap", function(x, y)
    if not b.inField() or type(x) ~= "number" or type(y) ~= "number" then return nil end
    local ok, inside = b.method(b.ow().map, "inBounds", x, y)
    if not ok then return nil end
    return inside == true
  end)

  -- True when (x, y) is a counter cell: the A press then reaches one cell
  -- further (Map:isCounterCell, Map.lua:123; World.lua:8664-8676).
  def("counterCell", function(x, y)
    if not b.inField() then return false end
    local m = b.ow().map
    local ok, yes = b.method(m, "isCounterCell", x, y)
    if ok then return yes == true end
    local okC, c = b.method(m, "cellCollision", x, y)
    return okC and c == Move.COUNTER
  end)
end

return Move
