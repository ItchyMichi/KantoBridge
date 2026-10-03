-- kb_pc: Bill's PC is Cobblemon's PC (docs/PLAN.md section 4.1 "Party and
-- Pokedex"; docs/PROTOCOL.md "Party, Pokedex, bag, PC").
--
--   pc.open{}     G->M   the Pokemon Center PC's box row was chosen
--   pc.closed{}   M->G   Cobblemon's PC screen closed
--
-- kb_engine wraps the PC menu's box row ("BILL'S PC" / "SOMEONE'S PC")
-- through the ui.pc.items hook: while a client or test session is
-- connected, the row's own access text runs as usual, and then, instead of
-- the Gen 1 box screens, a hold state sits on top of the still-open PC menu
-- (mode busy, no UI). This module sends pc.open once for that hold and ends
-- it on pc.closed; the hold then pops itself and the PC menu takes input
-- again, so the player picks another row or LOG OFF natively. The player's
-- item PC and the other rows are never touched.
--
-- A dropped link (no client or test session) releases the hold at once:
-- the hidden game never waits for a PC nobody can close. A hold whose world
-- went away (the title, New Game, CONTINUE) is dropped. pc.closed without a
-- hold is counted and ignored (the protocol has no id to tell PCs apart).
-- Logs show codes and counts only.

local Pc = {}

-- opts:
--   engine   kb_engine instance (pcPending, pcSent, pcClose, pcHeld, pcCounts)
--   log      kb_log logger (optional)
function Pc.new(opts)
  local engine, log = opts.engine, opts.log
  local pc = {}
  local counts = { opened = 0, closed = 0, released = 0, unexpected = 0, dropped = 0 }

  -- One logic step, after the save messages: out gets { "pc.open", {}, "pc" }.
  -- link = { live, generation }.
  function pc.step(out, link)
    local rec, why = engine.pcPending()
    if why == "dropped" then
      counts.dropped = counts.dropped + 1
      if log then log.info("pc.dropped", { count = counts.dropped }) end
    end
    if not rec then return end
    if not (link and link.live) then
      if engine.pcClose("released") then
        counts.released = counts.released + 1
        if log then log.info("pc.released", { count = counts.released }) end
      end
      return
    end
    if rec.sentGen == nil then
      out[#out + 1] = { "pc.open", {}, "pc" }
      engine.pcSent(link.generation)
      counts.opened = counts.opened + 1
      if log then log.info("pc.open", { count = counts.opened }) end
    end
  end

  -- pc.closed from the session (role already checked). Always true.
  function pc.closed()
    if engine.pcClose("closed") then
      counts.closed = counts.closed + 1
      if log then log.info("pc.closed", { count = counts.closed }) end
    else
      counts.unexpected = counts.unexpected + 1
      if log then log.info("pc.closed_unexpected", { count = counts.unexpected }) end
    end
    return true
  end

  function pc.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    out.held = engine.pcHeld()
    local c = engine.pcCounts()
    out.taken, out.native = c.taken, c.native
    return out
  end

  return pc
end

return Pc
