-- game3 region acts (GEN3_BRIDGE_PLAN.md §4.8; MULTI_GAME_PLAN §4.3's park,
-- resume and start on Gen 3).
--
-- The park is a private Field lock, "kb_park" (src/core/game3/field.lua:23-45):
-- it stops input-driven movement, NPC wandering, A-press interaction, START,
-- coord events, trainer sight, step encounters and RTC events (world note
-- §8), and scripts' release/releaseall clear only "default" (ops_a.lua:552,
-- :571), so it survives them. Field.start and Field.stop wipe every tag
-- (field.lua:109, :155): awayHeld, which the core asks every step, puts it
-- back. Field.unlock does nothing during a fly landing, a fall warp, a field
-- callback or the forest preview (field.lua:30-37): the release retries
-- until the tag is gone. The map's song fades out (Audio.fadeOutBgm,
-- audio.lua:708) and comes back on release (fadeInBgm, :730, which plays a
-- song id): the id is the playing record's (Audio.currentSong, :1507, is
-- { id, duration, loop, startedAt }, :447-452).
--
-- A start from the title: CONTINUE when the active slot holds a Gen 3 save
-- (Game3:_hasContinueSave, Game3.lua:60-66) through the boot action the main
-- menu itself returns (Game3:_handleBootAction{action="continue"},
-- :509-541); else NEW GAME as the main menu's row starts it
-- (BootModules.startNewGame: Birch's speech, boot_modules.lua:111-117), its
-- intro included (MULTI_GAME_PLAN §4.13).
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Region = {}

Region.TAG = "kb_park"
Region.FADE_SPEED = 4 -- pret's map fade (16 * speed frames)

function Region.install(b)
  local def = b.def

  local function record()
    local st = b.state()
    local rec = type(st) == "table" and st.region or nil
    return type(rec) == "table" and rec or nil, st
  end
  local function locked()
    local F = b.g3("field")
    return type(F) == "table" and type(F._locks) == "table" and F._locks[Region.TAG] ~= nil
  end

  -- True when held (again), or false plus not_ingame | failed.
  def("holdAway", function()
    if not b.inField() then return false, "not_ingame" end
    local st = b.state()
    if not st then return false, "failed" end
    if type(st.region) == "table" and st.region.state == "held" then return true end
    local F = b.g3("field")
    local ok = b.call(F, "lock", Region.TAG)
    if not ok or not locked() then return false, "failed" end
    local Audio = b.g3("audio")
    local playing = b.ask(Audio, "currentSong", nil)
    local song = type(playing) == "table" and playing.id or nil
    st.region = { state = "held", song = song }
    b.call(Audio, "fadeOutBgm", Region.FADE_SPEED)
    return true
  end)

  -- Ends the hold; true when one was held. The lock goes at once, or on a
  -- later step if the engine refuses now.
  def("releaseAway", function()
    local rec, st = record()
    if not rec then return false end
    rec.state = "released"
    b.call(b.g3("field"), "unlock", Region.TAG)
    if locked() then
      st.region = rec -- still to unlock: awayHeld retries
    else
      st.region = nil
    end
    if rec.song ~= nil then b.call(b.g3("audio"), "fadeInBgm", rec.song, Region.FADE_SPEED) end
    return true
  end)

  -- Every step: true while the park holds; keeps the lock in place (after a
  -- Field.start/stop) and finishes a release the engine refused.
  def("awayHeld", function()
    local rec, st = record()
    if not rec then return false end
    local F = b.g3("field")
    if rec.state == "held" then
      if b.inField() and not locked() then b.call(F, "lock", Region.TAG) end
      return true
    end
    b.call(F, "unlock", Region.TAG)
    if not locked() then st.region = nil end
    return false
  end)

  -- "new" | "continue", or nil plus not_title | menu (try again) | failed.
  def("startFromTitle", function()
    if b.inField() then return nil, "not_title" end
    local g = b.game
    if type(g) ~= "table" or g.phase ~= "boot" or type(g.boot) ~= "table" then return nil, "failed" end
    if g.boot.phase == "oak" then return nil, "menu" end -- a NEW GAME already under way
    local okSave, hasSave = false, false
    if type(g._hasContinueSave) == "function" then okSave, hasSave = pcall(g._hasContinueSave, g) end
    if okSave and hasSave then
      if type(g._handleBootAction) ~= "function" then return nil, "failed" end
      local ok, err = pcall(g._handleBootAction, g, { action = "continue" })
      if not ok then
        b.warnAt("engine.continue_failed", err)
        return nil, "failed"
      end
      if not b.inField() then return nil, "failed" end
      return "continue"
    end
    local Boot, BootModules = b.ui3("boot"), b.ui3("boot_modules")
    local ok, err = b.call(BootModules, "startNewGame", Boot, g.boot)
    if not ok then
      b.warnAt("engine.new_game_failed", err)
      return nil, "failed"
    end
    return "new"
  end)

  -- The catalogue's story markers (protocol/games.json: a marker -> its
  -- Emerald flag name), read from the flag store by Emerald's own names.
  def("progressMarkers", function(names)
    local out = {}
    for name, flag in pairs(type(names) == "table" and names or {}) do
      if type(name) == "string" and type(flag) == "string" then out[name] = b.flag(flag) == true end
    end
    return out
  end)
end

return Region
