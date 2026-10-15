-- The saves announced to Minecraft, shared by the game3 and gen2 adapters
-- (MG9 moved them from lib/engine/game3/saves.lua; gen1.lua's queue): a
-- hidden-game save gets an id ("v-<token>-<serial>") and waits here until the
-- core sends it. The adapter's base supplies b.state and b.saveObject (the
-- object the save events carry: Gen 3's session, Gen 2's game.save).

local SaveQueue = {}

function SaveQueue.install(b)
  local def = b.def

  -- ---- saves announced to Minecraft (gen1.lua's queue)

  def("savingReason", function()
    local st = b.state()
    return type(st) == "table" and st.saving or nil
  end)
  def("queueSave", function(reason, pair)
    local st = b.state()
    if not st then return nil, false end
    st.serial = (st.serial or 0) + 1
    local id = ("v-%s-%d"):format(st.token or "x", st.serial)
    local replaced = st.saveQueued ~= nil
    pair = type(pair) == "table" and pair or {}
    local s = b.saveObject()
    st.saveQueued = { save = id, reason = reason, serial = st.serial, pair = pair.pair, at = pair.at, from = s }
    -- the playthrough is a saved one from now on (playthrough)
    if s then
      st.savedSessions = type(st.savedSessions) == "table" and st.savedSessions or setmetatable({}, { __mode = "k" })
      st.savedSessions[s] = true
    end
    return id, replaced
  end)
  def("takeSave", function()
    local st = b.state()
    local q = type(st) == "table" and st.saveQueued or nil
    if type(q) ~= "table" then return nil end
    st.saveQueued = nil
    return { save = q.save, reason = q.reason, pair = q.pair, at = q.at,
             current = q.from ~= nil and q.from == b.saveObject() }
  end)
  def("queuedSaveSerial", function()
    local st = b.state()
    local q = type(st) == "table" and st.saveQueued or nil
    return type(q) == "table" and q.serial or nil
  end)
end

return SaveQueue
