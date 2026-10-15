-- gen2 field moves (GEN2_BRIDGE_PLAN.md §5.10, phase G2F; the story group's
-- fieldAction, act/kb_field.lua): a move or a field item from Minecraft, done
-- as the hidden game does it from its own menus.
--
-- * A move goes through the party menu's path, World:useFieldMove(moveId, mon)
--   (src/world/gen2/World.lua:6315-6331): the badge (CheckBadge), the move's own
--   checks (FieldMoves.fromMenu, src/world/gen2/FieldMoves.lua:847-855), and a
--   success queued to run once the field owns the frame (runQueuedFieldMove,
--   :6334-6339), its "<mon> used CUT!" line and all. The member is the first
--   that knows it (FieldMoves.partyMoveUser, :250-261). Gen 2 adds Headbutt,
--   Rock Smash, Whirlpool, Waterfall and Sweet Scent to Gen 1's set.
-- * The bicycle, a rod and the Escape Rope go through the PACK's field path,
--   World:useFieldItem(itemId) (:4908-4925): useBike (:5265-5292), useRod
--   (:4888-4894), useEscapeRope (:4974-4990).
--
-- Answers (ok, kind) or (false, reason): kb_field's kinds and refusals.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Field2 = {}

-- act.field's moves -> Gen 2's move ids (FieldMoves.FROM_MENU's keys)
Field2.MOVES = {
  cut = "CUT", surf = "SURF", strength = "STRENGTH", flash = "FLASH", dig = "DIG", teleport = "TELEPORT",
  fly = "FLY", headbutt = "HEADBUTT", rock_smash = "ROCK_SMASH", whirlpool = "WHIRLPOOL", waterfall = "WATERFALL",
  sweet_scent = "SWEET_SCENT",
}
-- moves on the cell in front: a refusal there is "no target", elsewhere "not here"
Field2.TARGETED = { cut = true, surf = true, headbutt = true, rock_smash = true, whirlpool = true, waterfall = true }
-- the rods, best first
Field2.RODS = { "SUPER_ROD", "GOOD_ROD", "OLD_ROD" }
Field2.ROPE = "ESCAPE_ROPE"
Field2.BICYCLE = "BICYCLE"

function Field2.install(b)
  local def = b.def

  local function inventory()
    local s = b.saveObject()
    return type(s) == "table" and type(s.inventory) == "table" and s.inventory or {}
  end

  local function useItem(w, item)
    local ok, result = b.method(w, "useFieldItem", item)
    if not ok then return nil end
    return result
  end

  def("fieldAction", function(action, opts)
    opts = type(opts) == "table" and opts or {}
    local w = b.ow()
    if not (b.inField() and w) then return false, "not_ingame" end
    if w.battleActive then return false, "not_idle" end
    local okBusy, busy = b.method(w, "busy")
    if okBusy and busy then return false, "busy" end
    if action == "bicycle" then
      if (tonumber(inventory()[Field2.BICYCLE]) or 0) < 1 then return false, "no_item" end
      local r = useItem(w, Field2.BICYCLE)
      if r == "bike_on" or r == "bike_off" then return true, r end
      return false, "not_here"
    end
    if action == "fish" then
      local rod = opts.item
      if rod == nil then
        for _, id in ipairs(Field2.RODS) do
          if (tonumber(inventory()[id]) or 0) >= 1 then rod = id break end
        end
      end
      if rod == nil or (tonumber(inventory()[rod]) or 0) < 1 then return false, "no_item" end
      local r = useItem(w, rod)
      if r == nil then return false, "no_item" end
      if r == "nowhere" then return false, "no_target" end
      return true, "fish"
    end
    if action == "dig" and opts.item == Field2.ROPE then
      if (tonumber(inventory()[Field2.ROPE]) or 0) < 1 then return false, "no_item" end
      local r = useItem(w, Field2.ROPE)
      if r == "escape_rope" then return true, "rope" end
      return false, "not_here"
    end
    local moveId = Field2.MOVES[action]
    if not moveId then return false, "failed" end
    -- Gen 2's Fly picks its destination on its own map (FieldMoves.flyFromMenu, :660-672): a named one isn't taken
    if action == "fly" and opts.dest ~= nil then return false, "no_target" end
    local s = b.saveObject()
    local party = type(s) == "table" and type(s.party) == "table" and s.party or {}
    local okCtx, ctx = b.method(w, "fieldContext")
    local okUser, mon = b.call(b.w2("FieldMoves"), "partyMoveUser", party, moveId, okCtx and ctx or nil)
    if not (okUser and type(mon) == "table") then return false, "no_move" end
    local ok, result = b.method(w, "useFieldMove", moveId, mon)
    if not ok or type(result) ~= "table" then return false, "failed" end
    if result.ok then
      if action == "fly" and result.inMenu then return true, "fly_list" end
      return true, action
    end
    if result.badge ~= nil then return false, "no_badge" end
    return false, Field2.TARGETED[action] and "no_target" or "not_here"
  end)
end

return Field2
