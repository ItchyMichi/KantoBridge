-- kb_pc: Bill's PC is Cobblemon's PC (docs/PLAN.md section 4.1 "Party and
-- Pokedex"; docs/PROTOCOL.md "Party, Pokedex, bag, PC").
--
--   pc.open{pc}     G->M   the Pokemon Center PC's box row was chosen
--   pc.closed{pc}   M->G   Cobblemon's PC screen closed (pc: the open's id)
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
-- M10: every pc.open carries a fresh id ("pc-<token>-<n>", unique per
-- process: the bridge token and a counter in the process-lifetime slot),
-- kept in the hold's record; only the pc.closed that names it ends
-- the hold. A pc.closed with another id (a stale one, from a hold already
-- released) or with no hold at all is counted and ignored; one without a
-- valid id is bad_message.
--
-- A dropped link (no client or test session) releases the hold at once:
-- the hidden game never waits for a PC nobody can close. A hold whose world
-- went away (the title, New Game, CONTINUE) is dropped. Logs show codes and
-- counts only.

local Pc = {}

local ID = "^[A-Za-z0-9_.:-]+$"

local function validId(v)
  return type(v) == "string" and #v >= 1 and #v <= 64 and v:find(ID) ~= nil
end

-- opts:
--   engine   kb_engine instance (pcPending, pcSent, pcClose, pcHeld, pcCounts,
--            bridgeToken, persistent)
--   log      kb_log logger (optional)
function Pc.new(opts)
  local engine, log = opts.engine, opts.log
  local pc = {}
  local counts = { opened = 0, closed = 0, released = 0, unexpected = 0, stale = 0, dropped = 0 }

  -- One logic step, after the save messages: out gets
  -- { "pc.open", { pc = id }, "pc" }. link = { live, generation }.
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
      -- the counter lives in the process-lifetime slot, so ids never repeat
      -- after a hot reload
      local ok, keep = pcall(engine.persistent, "pc")
      keep = ok and type(keep) == "table" and keep or counts
      keep.ids = (keep.ids or 0) + 1
      local id = ("pc-%s-%d"):format(engine.bridgeToken(), keep.ids)
      out[#out + 1] = { "pc.open", { pc = id }, "pc" }
      engine.pcSent(link.generation, id)
      counts.opened = counts.opened + 1
      if log then log.info("pc.open", { pc = id, count = counts.opened }) end
    end
  end

  -- pc.closed from the session (role already checked). Returns true, or
  -- false plus bad_message.
  function pc.closed(data)
    if type(data) ~= "table" or not validId(data.pc) then return false, "bad_message" end
    local closed, why = engine.pcClose("closed", data.pc)
    if closed then
      counts.closed = counts.closed + 1
      if log then log.info("pc.closed", { pc = data.pc, count = counts.closed }) end
    elseif why == "stale" then
      counts.stale = counts.stale + 1
      if log then log.info("pc.closed_stale", { count = counts.stale }) end
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
