-- gen2 region acts (GEN2_BRIDGE_PLAN.md §5.8; MULTI_GAME_PLAN §4.3's park,
-- resume and start on Gen 2).
--
-- The park is Gen 1's: a hold state pushed on the shared state stack. Each
-- logic step updates only the stack's top state when there is one and skips
-- the whole field step (src/core/Game2.lua:1314-1318), so the VM, the NPCs,
-- trainer sight, phone calls and the time events stop with it (G2-S7: no
-- World.step and no NPC motion while it is up; input.step still runs). The
-- map's song fades out (Music.fadeOut, src/core/Music.lua:363) and comes back
-- on release (World:restoreMapMusic, src/world/gen2/World.lua:2760).
--
-- A start from the title: CONTINUE when the active slot holds a Gen 2 save
-- (Save.load, src/core/gen2/Save.lua:904; Game2:continueGame(save),
-- Game2.lua:312-358 -- never with nil, which starts a NEW GAME); else NEW GAME
-- (Game2:newGame, :292-310): Oak's speech, its intro included
-- (MULTI_GAME_PLAN §4.13), with the clock answered by the boot part (J5).
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Region = {}

function Region.install(b)
  local def = b.def

  local function record()
    local st = b.state()
    local rec = type(st) == "table" and st.region or nil
    return type(rec) == "table" and rec or nil, st
  end

  -- True when held (again), or false plus not_ingame | failed.
  def("holdAway", function()
    if not b.inField() then return false, "not_ingame" end
    local st, g = b.state(), b.game
    if not (st and type(g) == "table" and type(g.stack) == "table") then return false, "failed" end
    if type(st.region) == "table" then return true end
    local rec = { state = "held" }
    local stack = g.stack
    local hold = { kantoBridgeRegionHold = true }
    function hold.update()
      if rec.state ~= "held" and stack:top() == hold then stack:pop() end
    end
    function hold.draw() end
    rec.hold = hold
    st.region = rec
    local ok, err = pcall(stack.push, stack, hold)
    if not ok then
      st.region = nil
      b.warnAt("engine.hold_failed", err)
      return false, "failed"
    end
    local Music = b.mod("src.core.Music")
    if Music and type(Music.fadeOut) == "function" then pcall(Music.fadeOut, tonumber(Music.MAP_FADE) or 10) end
    return true
  end)

  -- Ends the hold: popped at once when it is on top (else on its next update);
  -- the map's song comes back. True when one was held.
  def("releaseAway", function()
    local rec, st = record()
    if not rec then return false end
    rec.state = "released"
    st.region = nil
    local g = b.game
    local stack = type(g) == "table" and g.stack or nil
    if type(stack) == "table" and type(stack.top) == "function" and stack:top() == rec.hold then
      pcall(stack.pop, stack)
    end
    local w = b.ow()
    if w then b.method(w, "restoreMapMusic") end
    return true
  end)

  def("awayHeld", function() return record() ~= nil end)

  -- "new" | "continue", or nil plus not_title | menu (try again) | failed.
  def("startFromTitle", function()
    if b.inField() then return nil, "not_title" end
    local g = b.game
    if type(g) ~= "table" then return nil, "failed" end
    if g.phase == "error" then return nil, "failed" end
    if g.phase ~= "boot" then return nil, "menu" end -- a world being set up: ask again next step
    if type(b.introRunning) == "function" and b.introRunning() then return nil, "menu" end -- a NEW GAME under way
    local Save = b.c2("Save")
    local v = b.adapter.gameVersion()
    local hasSave = Save and v and b.ask(Save, "exists", false, v) == true
    if hasSave then
      local loaded = b.ask(Save, "load", nil, v)
      if type(loaded) == "table" and type(g.continueGame) == "function" then
        local ok, err = pcall(g.continueGame, g, loaded)
        if not ok then
          b.warnAt("engine.continue_failed", err)
          return nil, "failed"
        end
        return "continue"
      end
    end
    if type(g.newGame) ~= "function" then return nil, "failed" end
    local ok, err = pcall(g.newGame, g)
    if not ok then
      b.warnAt("engine.new_game_failed", err)
      return nil, "failed"
    end
    return "new"
  end)

  -- The catalogue's story markers (protocol/games.json: a marker -> "E<number>",
  -- an event flag, or KB_HALL_OF_FAME; boot.lua's b.namedFlag).
  def("progressMarkers", function(names)
    local out = {}
    for name, flag in pairs(type(names) == "table" and names or {}) do
      if type(name) == "string" and type(flag) == "string" then out[name] = b.namedFlag(flag) == true end
    end
    return out
  end)
end

return Region
