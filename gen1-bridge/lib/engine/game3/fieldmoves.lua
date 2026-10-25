-- game3 field moves (FOREIGN_POKEMON_PLAN.md §4.2, FP7; the story group's
-- fieldAction, act/kb_field.lua): a move or a field item from Minecraft, done
-- as the hidden game does it from its own menus.
--
-- * A move goes through the party menu's path: the context the menu builds
--   (src/ui/game3/party_menu.lua:1871-1913: the cell in front, its object,
--   water, a waterfall, the 3x3 grass, the map's type and header flags, the
--   escape warp), FieldMoves.fromMenu (src/core/game3/field_moves.lua:804-813:
--   the badge and the move's own checks), then Field.executeFieldMove
--   (src/core/game3/field.lua:1169), its "<mon> used CUT!" and all. The member
--   is the first that knows it (FieldMoves.partyMoveUser, field_moves.lua:313).
--   Emerald adds Dive; Fly opens the game's own region map (kind fly_list).
-- * Getting off the water is a step onto the land in front (Player.tryMove,
--   src/core/game3/player.lua:730), as walking there does.
-- * The bicycle, a rod and the Escape Rope go through the bag's field paths:
--   ItemUse.useBike / useEscapeRope (src/core/game3/item_use.lua:503, :440)
--   and Field.tryFishingEncounter (the rod's encounter, as battle.lua's
--   fishing wrapper sees it).
--
-- Answers (ok, kind) or (false, reason): kb_field's kinds and refusals.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Field3 = {}

-- act.field's moves -> Emerald's move names (FieldMoves.MOVES' keys)
Field3.MOVES = {
  cut = "CUT", surf = "SURF", strength = "STRENGTH", flash = "FLASH", dig = "DIG", teleport = "TELEPORT",
  fly = "FLY", rock_smash = "ROCK_SMASH", waterfall = "WATERFALL", sweet_scent = "SWEET_SCENT", dive = "DIVE",
}
-- moves on the cell in front: a refusal there is "no target", elsewhere "not here"
Field3.TARGETED = { cut = true, surf = true, rock_smash = true, waterfall = true, dive = true }
-- the rods, best first, and the items, as Emerald's symbols
Field3.RODS = { "SUPER_ROD", "GOOD_ROD", "OLD_ROD" }
Field3.ROPE = "ESCAPE_ROPE"
Field3.BIKES = { "MACH_BIKE", "ACRO_BIKE" }
local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }

function Field3.install(b)
  local def = b.def

  local function itemNum(sym)
    local C = b.constants()
    return C and tonumber(b.ask(C, "id", nil, C, "items", "ITEM_" .. sym)) or nil
  end
  local function has(sym)
    local s = b.session()
    local id = itemNum(sym)
    return id ~= nil and type(s) == "table" and type(s.bag) == "table"
      and (tonumber(b.ask(b.g3("bag"), "get", 0, s.bag, id)) or 0) >= 1
  end
  local function headerFlag(mapDef, key)
    return type(mapDef) == "table" and (tonumber(mapDef[key]) or 0) ~= 0
  end

  -- The party menu's context for a field move (party_menu.lua:1871-1913).
  local function context(s, mon)
    local P, Collision, Objects, Map = b.g3("player"), b.g3("collision"), b.g3("objects"), b.g3("map")
    local Space = b.g3("scripting.space")
    local FM = b.g3("field_moves")
    local d = DELTA[type(P) == "table" and P.facing or "down"] or DELTA.down
    local px, py = tonumber(P and P.cellX) or 0, tonumber(P and P.cellY) or 0
    local fx, fy = px + d[1], py + d[2]
    local function grass3x3()
      if not (type(Collision) == "table" and type(Collision.isGrass) == "function") then return false end
      local elev = P.elevation
      for y = py - 1, py + 1 do
        for x = px - 1, px + 1 do
          local e = type(Collision.elevationAt) == "function" and Collision.elevationAt(x, y) or nil
          if (elev == nil or e == elev) and Collision.isGrass(x, y) then return true end
        end
      end
      return false
    end
    local mapDef = b.ask(Map, "currentDef", nil)
    local beh = type(Collision) == "table" and type(Collision.behavior) == "function" and Collision.behavior(fx, fy) or nil
    return {
      party = s.party, mon = mon, store = type(Space) == "table" and Space.store or nil, session = s,
      facingObject = b.ask(Objects, "at", nil, fx, fy),
      isFacingWater = type(Collision) == "table" and type(Collision.isWater) == "function" and Collision.isWater(fx, fy) == true,
      isFacingWaterfall = b.ask(FM, "isWaterfallBehavior", false, beh) == true,
      facing = P.facing, isSurfing = P.surfing == true, hasCuttableGrass = grass3x3(),
      mapType = type(mapDef) == "table" and mapDef.mapType or nil,
      isCave = headerFlag(mapDef, "cave"), canEscapeRope = headerFlag(mapDef, "allowEscaping"),
      escapeWarp = s.escapeWarp,
    }, fx, fy
  end

  -- The warp a field move from Minecraft sets off is named by it (FA8 item 11, K11): warpDeparture takes the mark
  -- (game3/move.lua); observe() forgets one no warp took (observe.lua)
  local MARKS = { dig = "dig", teleport = "teleport", fly = "fly", rope = "rope" }
  local function mark(kind)
    local st = b.state()
    if st and MARKS[kind] then st.departure = { kind = MARKS[kind], steps = 0 } end
  end

  def("fieldAction", function(action, opts)
    opts = type(opts) == "table" and opts or {}
    local s = b.session()
    if not (b.inField() and type(s) == "table") then return false, "not_ingame" end
    if b.ask(b.g3("battle"), "isActive", false) == true then return false, "not_idle" end
    local P = b.g3("player")
    if type(P) ~= "table" then return false, "not_ingame" end
    local ItemUse = b.g3("item_use")
    if action == "bicycle" then
      for _, sym in ipairs(Field3.BIKES) do
        if has(sym) then
          -- (Player.biking, player.lua; useBike refuses the Cycling Road's dismount and the wrong maps)
          local was = P.biking == true
          local okCall, used = b.call(ItemUse, "useBike", s, itemNum(sym))
          if not (okCall and used) then return false, "not_here" end
          if (P.biking == true) == was then return false, "not_here" end
          return true, P.biking == true and "bike_on" or "bike_off"
        end
      end
      return false, "no_item"
    end
    if action == "fish" then
      local rod = opts.item
      if rod == nil then
        for _, sym in ipairs(Field3.RODS) do
          if has(sym) then rod = sym break end
        end
      end
      if rod == nil or not has(rod) then return false, "no_item" end
      local rods = { OLD_ROD = 0, GOOD_ROD = 1, SUPER_ROD = 2 }
      local okCall = b.call(b.g3("field"), "tryFishingEncounter", rods[rod] or 0)
      if not okCall then return false, "failed" end
      return true, "fish"
    end
    if action == "dig" and opts.item == Field3.ROPE then
      if not has(Field3.ROPE) then return false, "no_item" end
      local okCall, used = b.call(ItemUse, "useEscapeRope", s, s.bag, itemNum(Field3.ROPE))
      if okCall and used then
        mark("rope")
        return true, "rope"
      end
      return false, "not_here"
    end
    local move = Field3.MOVES[action]
    if not move then return false, "failed" end
    -- Fly picks its destination on the game's own region map: a named one isn't taken
    if action == "fly" and opts.dest ~= nil then return false, "no_target" end
    local FM = b.g3("field_moves")
    if type(FM) ~= "table" or type(FM.fromMenu) ~= "function" then return false, "failed" end
    -- off the water: a step onto the land in front (the player already faces it, kb_field turned them)
    if action == "surf" and P.surfing == true then
      -- (tryMove answers "step" when the hop ashore starts, player.lua:772-785; surfing ends as it lands)
      local okCall, step = b.call(P, "tryMove", P.facing or "down", b.game)
      if okCall and (step == "step" or P.surfing ~= true) then return true, "dismount" end
      return false, "no_target"
    end
    local okUser, mon = b.call(FM, "partyMoveUser", s.party, move)
    if not (okUser and type(mon) == "table") then return false, "no_move" end
    local ctx = context(s, mon)
    local okRes, res = b.call(FM, "fromMenu", move, ctx)
    if not (okRes and type(res) == "table") then return false, "failed" end
    if not res.ok then
      if res.badge ~= nil then return false, "no_badge" end
      return false, Field3.TARGETED[action] and "no_target" or "not_here"
    end
    local okRun = b.call(b.g3("field"), "executeFieldMove", res)
    if not okRun then return false, "failed" end
    mark(action)
    if action == "fly" then return true, "fly_list" end
    return true, action
  end)
end

return Field3
