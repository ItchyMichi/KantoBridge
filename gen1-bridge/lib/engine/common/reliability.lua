-- Reliability, shared by the game3 and gen2 adapters (GEN3_BRIDGE_PLAN.md §4.9;
-- MG9 moved it from lib/engine/game3/, GEN2_BRIDGE_PLAN.md §3, J1): save meta,
-- our state that outlives a hot reload, the per-process token behind save ids,
-- and running hidden. The hidden-window pacing is gen1.lua's (M10), unchanged:
-- it wraps love.window.isVisible, which the engine's shared main loop asks once
-- a frame for every engine (main.lua:1907-1924), so nothing in it belongs to one
-- engine. The adapter's base supplies b.saveObject (the object the save events
-- carry: Gen 3's session, Gen 2's game.save) and b.state.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Reliability = {}

Reliability.HIDDEN_KEEP_SECONDS = 1
Reliability.HIDDEN_FRAME = 1 / 60

function Reliability.install(b)
  local def = b.def

  -- A per-process token for ids (the first instance's survives hot reloads)
  -- and the counter behind them (gen1.lua's).
  def("bridgeToken", function(token)
    local st = b.state()
    if not st then return token or "x" end
    if st.token == nil and type(token) == "string" then st.token = token end
    return st.token or token or "x"
  end)
  def("nextSerial", function()
    local st = b.state()
    if not st then return 0 end
    st.serial = (st.serial or 0) + 1
    return st.serial
  end)

  -- A save's meta as plain values: savedAt and sessionStart (Unix seconds)
  -- and the engine version that wrote it. Gen 3's session carries the meta
  -- CONTINUE loaded (Game3.lua:540); a save without one answers {}.
  def("metaOf", function(save)
    if save == nil then save = b.saveObject() end
    local meta = type(save) == "table" and type(save.meta) == "table" and save.meta or nil
    if not meta then return {} end
    local function int(v)
      v = tonumber(v)
      if v and v == v and v >= 0 and v < 9007199254740992 then return math.floor(v) end
      return nil
    end
    return { savedAt = int(meta.savedAt), sessionStart = int(meta.sessionStart),
             engine = type(meta.engine) == "string" and #meta.engine <= 32 and meta.engine or nil }
  end)

  def("persistent", function(name)
    local st = b.state()
    if not st then return nil end
    st.keep = type(st.keep) == "table" and st.keep or {}
    local t = st.keep[name]
    if type(t) ~= "table" then
      t = {}
      st.keep[name] = t
    end
    return t
  end)

  -- ---- running hidden (gen1.lua's runHidden and hiddenStats)

  local windowCounts = { installs = 0, removals = 0 }
  local function index(t, k) return t[k] end
  local loveModules = {}
  local function loveModule(name)
    local m = loveModules[name]
    if m then return m end
    local lv = b.love
    if lv == nil then return nil end
    local ok, value = pcall(index, lv, name)
    if ok and type(value) == "table" then
      loveModules[name] = value
      return value
    end
    return nil
  end

  local function paceFrame(slot, timer, now)
    local deadline = slot.nextFrame
    if deadline == nil or now - deadline > Reliability.HIDDEN_FRAME then deadline = now end
    if deadline > now and type(timer.sleep) == "function" then
      timer.sleep(math.min(deadline - now, Reliability.HIDDEN_FRAME))
      slot.paced = (slot.paced or 0) + 1
    end
    slot.nextFrame = deadline + Reliability.HIDDEN_FRAME
  end

  local function hiddenWrapper(slot)
    return function(...)
      local original = slot.original
      if type(original) ~= "function" then return true end
      local real = original(...)
      local ok, answer = pcall(function()
        local timer = slot.timer
        local now = timer and timer.getTime and timer.getTime() or nil
        if not (slot.active and now and now <= (slot.keepUntil or -math.huge)) then return real end
        if real then
          local isMinimized = slot.isMinimized
          if type(isMinimized) ~= "function" or isMinimized() ~= true then return real end
          paceFrame(slot, timer, now)
          slot.minimizedFrames = (slot.minimizedFrames or 0) + 1
          return real
        end
        paceFrame(slot, timer, now)
        slot.faked = (slot.faked or 0) + 1
        return true
      end)
      if ok then return answer end
      return real
    end
  end

  def("runHidden", function(want, now)
    local g = b.game
    if type(g) ~= "table" then return false end
    local win = loveModule("window")
    local slot = rawget(g, "kantoBridgeWindow")
    if not want then
      if type(slot) == "table" and slot.wrapper ~= nil then
        slot.active = false
        if win and win.isVisible == slot.wrapper then
          win.isVisible = slot.original
          slot.wrapper = nil
          windowCounts.removals = windowCounts.removals + 1
        end
      end
      return false
    end
    if not (win and type(win.isVisible) == "function") then return false end
    if type(slot) ~= "table" then
      slot = { faked = 0, paced = 0 }
      rawset(g, "kantoBridgeWindow", slot)
    end
    slot.timer = loveModule("timer")
    slot.isMinimized = type(win.isMinimized) == "function" and win.isMinimized or nil
    if slot.wrapper == nil then
      slot.original = win.isVisible
      slot.wrapper = hiddenWrapper(slot)
      win.isVisible = slot.wrapper
      windowCounts.installs = windowCounts.installs + 1
    end
    slot.active = true
    slot.keepUntil = (tonumber(now) or 0) + Reliability.HIDDEN_KEEP_SECONDS
    return win.isVisible == slot.wrapper
  end)

  def("hiddenStats", function()
    local g = b.game
    local slot = type(g) == "table" and rawget(g, "kantoBridgeWindow") or nil
    local win = loveModule("window")
    local out = { installs = windowCounts.installs, removals = windowCounts.removals, faked = 0, paced = 0,
                  minimizedFrames = 0, active = false, visible = true, minimized = false }
    if type(slot) == "table" then
      out.faked, out.paced, out.minimizedFrames = slot.faked or 0, slot.paced or 0, slot.minimizedFrames or 0
      out.active = slot.active == true and slot.wrapper ~= nil and win ~= nil and win.isVisible == slot.wrapper
    end
    if win then
      local isVisible = (type(slot) == "table" and slot.wrapper ~= nil and win.isVisible == slot.wrapper)
        and slot.original or win.isVisible
      if type(isVisible) == "function" then
        local ok, v = pcall(isVisible)
        if ok then out.visible = v == true end
      end
      if type(win.isMinimized) == "function" then
        local ok, v = pcall(win.isMinimized)
        if ok then out.minimized = v == true end
      end
    end
    return out
  end)
end

return Reliability
