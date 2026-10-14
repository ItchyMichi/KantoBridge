-- game3 routes (GEN3_BRIDGE_PLAN.md §4.10, phase G3F): the blackout the
-- server asks for after a lost habitat battle (route.blackout, any region:
-- J/server/WildBlackouts.java), the engine's own whiteout as its battle
-- writeback runs it on a loss (battle_bridge.lua:257-279): half the money
-- (BattleBridge.applyWhiteoutMoneyLoss, :122-132) and the respawn at the heal
-- point (Field.respawnAtHeal, field.lua:2054-2090), which halts a running
-- script and emits world.blacked_out (main.lua then queues the heal for
-- Cobblemon).
--
-- Wild Routes zones with an Emerald end (the hidden player parked on the
-- zone's exit cell, §10 E6) come with Hoenn's routes data (MG8): no
-- data/routes.json end names Emerald yet.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Routes3 = {}

function Routes3.install(b)
  local def = b.def

  -- true, or false plus not_ingame | busy | failed (gen1.lua's contract).
  def("blackout", function()
    if not b.inField() then return false, "not_ingame" end
    if b.ask(b.g3("battle"), "isActive", false) == true then return false, "busy" end
    local s = b.session()
    local g = b.game
    b.call(b.g3("battle_bridge"), "applyWhiteoutMoneyLoss", s, type(g) == "table" and g.save or nil)
    local ok = b.call(b.g3("field"), "respawnAtHeal")
    b.dropView()
    if not ok then return false, "failed" end
    return true
  end)
end

return Routes3
