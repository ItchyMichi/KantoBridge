-- Kanto Bridge test driver (MG9 G2A, docs/specs/GEN2_BRIDGE_PLAN.md §10): the
-- Gen 2 adapter on the real Crystal engine, driven over TCP by
-- tools/e2e/test_crystal.py. Every scene first registers a fresh empty save
-- slot labelled KB_CRYSTAL_TEST and makes it active (the engine's slot API,
-- src/core/SaveData.lua:1642-1900, shared by every engine; Gen 2's saves go to
-- the active slot, src/core/gen2/Save.lua:109-152), so nothing touches the
-- install's own saves; at the end the test slots are deleted and the slot
-- active at boot is active again. KB_SCENE picks the part:
--
--   field           driver mode's own start: Gen 2 skips the boot cinema
--                   under a driver and starts the world on the boot skeleton
--                   in PLAYERS_HOUSE_2F (src/core/Game2.lua:1263-1274); serves.
--                   KB_SERVING gives the start cell and two walks the driver
--                   found on the live map (breadth-first over walkable cells
--                   with nobody on them): `wall`, the steps to the nearest cell
--                   beside something solid and `bump`, the step into it; and
--                   `stairs`, the steps to the cell beside the warp to
--                   PLAYERS_HOUSE_1F and `into`, the step onto it (letters
--                   U D L R; "-" for none). Once a client is connected and the
--                   player stands off the start cell, the game saves itself
--                   once (Game2:writeSave), so the test sees a save.begin.
--   title_continue  the same start saved into the test slot, then back to the
--                   title (Game2:returnToTitle); serves: region.start CONTINUEs.
--   title_new       the empty test slot active at the title (run it with
--                   POKEPORT_BOOT_CINEMA=1: the real boot chain, copyright ->
--                   splash -> intro -> title; without it, driver mode's start
--                   goes back to the title first); serves: region.start starts
--                   NEW GAME, and the driver answers Oak's speech's screens the
--                   bridge can't answer before G2B (the gender choice: BOY; the
--                   name: the first preset; text boxes and yes/no: A) until the
--                   new game is in the field. The clock screen must never come
--                   up (the adapter answers it, J5): KB_INITCLOCK then fails.
--   title_ui        (G2B) as title_new, but the driver answers nothing: the
--                   test answers Oak's speech through the bridge's UI as a
--                   player would. The clock screen must still never come up in
--                   the speech (KB_INITCLOCK fails the run). Once the new game
--                   stands in the field, KB_FIELD also gives the walk to the
--                   stairs; downstairs, Mom's scene is answered by the test and
--                   her weekday wheel (Gen2InitClock in the play phase) by the
--                   adapter: the driver counts the frames it stays up (stuck
--                   for 10 s fails the run) and, once the field has stood idle
--                   for half a second in PLAYERS_HOUSE_1F, KB_NPC gives the walk
--                   to a cell beside the nearest person and the step that faces
--                   them, with that person's cell (for act.interact).
--   story           (G2C) title_ui's game and walk, then on: in the bedroom
--                   KB_BEDROOM gives how many blocks its decoration callback
--                   changed (the World's own record); after Mom's scene KB_EXIT
--                   the walk to the door to NEW_BARK_TOWN and the presses that
--                   take it (a mat: one step onto it and one in its direction);
--                   in NEW_BARK_TOWN, once the field stands idle, KB_LAB the
--                   walk into ELMS_LAB; there, once Elm's scene is over and the
--                   field stands idle, KB_BALL the walk to a cell beside a Poke
--                   Ball on the table, the step that faces it, its cell and
--                   index; KB_STARTER once flag 26 (EVENT_GOT_A_POKEMON_FROM_ELM)
--                   is set, with whether that ball's object is masked now.
--   export          (G2D) driver mode's start, as field; once the world
--                   stands the driver bakes a sample of maps with the
--                   engine's own World:bakeMapImage (one map per tileset,
--                   environment, palette and roof; the export's daytime) and
--                   prints, per map, a hash of each distinct block's 32x32
--                   pixels (KB_BAKE map= daytime= n= skipped= ids=<block>:<md5
--                   8 hex>+...; blocks with an animated tile left out, the
--                   engine draws their frames over the bake), then
--                   KB_EXPORT_READY and serves: the test fetches the export as
--                   a builder and compares its baked tiles with these.
--   cleanup         only the clean-up (after a failed run), then quits.
--
-- Markers: KB_BOOTED scene= slot= home= precleaned= phase=, KB_SERVING map= x= y= facing= wall= bump=
-- stairs= into=, KB_SAVED slot= ok=, KB_TITLE slot= hasSave=, KB_SPEECH texts= choices= gender= names=
-- screens=, KB_FIELD map= x= y= clock= stairs= into=, KB_INITCLOCK, KB_DAYWHEEL seen= frames=, KB_NPC map= path=
-- face= x= y= index=, KB_BEDROOM edits= differ=, KB_EXIT path= into=, KB_LAB path= into=, KB_BALL path= face= x=
-- y= index=, KB_STARTER flag= masked=, KB_BAKE map= daytime= n= skipped= ids=, KB_EXPORT_READY maps= blocks= skipped=
-- mode=, KB_CLEANED deleted= active=, KB_QUIT sessions= code=;
-- KB_FAIL <reason> on a timeout. Slot numbers, map symbols, cell numbers, directions, booleans and counts only
-- (the speech's text and the names are never printed).
--
-- Runs unsandboxed as a POKEPORT_DRIVER coroutine, resumed once per frame
-- with the Game (refs/gen1recomp/main.lua:755-759, :1131-1150).

io.stdout:setvbuf("no")

local PORT = tonumber(os.getenv("KB_TEST_PORT") or "") or 27222
local SCENE = os.getenv("KB_SCENE") or "field"
local EXTRA_SECONDS = tonumber(os.getenv("KB_DRIVER_EXTRA") or "") or 0
local SECONDS_LIMIT = tonumber(os.getenv("KB_DRIVER_SECONDS") or "") or (300 + EXTRA_SECONDS)
-- KB_DATASET_MEMO=<seconds> (as every driver whose test may fetch an export): the engine re-checks a dataset
-- view's readiness on every record read, which on the pod's network disk stretches an export to minutes;
-- memoising the check for that many seconds brings it back to seconds. Test-only: patches
-- DatasetViews._ready (src/mods/DatasetViews.lua) in place.
local DATASET_MEMO = tonumber(os.getenv("KB_DATASET_MEMO") or "") or 0
local LABEL = "KB_CRYSTAL_TEST"
local HOUSE, DOWNSTAIRS = "PLAYERS_HOUSE_2F", "PLAYERS_HOUSE_1F"
-- the story scene (G2C): the town, the lab, the starters' balls, the flag the starter's script sets (the same
-- number on Gold and Crystal, PROGRESS.md G2-S4)
local TOWN, LAB, BALL_SPRITE, STARTER_FLAG = "NEW_BARK_TOWN", "ELMS_LAB", "SPRITE_POKE_BALL", 26

local SaveData = require("src.core.SaveData")
local GameVersion = require("src.core.GameVersion")
local okSave, Save = pcall(require, "src.core.gen2.Save")
local okTB, TextBox = pcall(require, "src.render.TextBox")
local okCB, ChoiceBox = pcall(require, "src.ui.ChoiceBox")
local okPerm, Permissions = pcall(require, "src.world.gen2.Permissions")

local function word(v) return (tostring(v):gsub("[^%w_.:+%-]", "?")) end
local function num(id) return tonumber(tostring(id):match("^slot(%d+)$")) or 0 end
local function version() return GameVersion.get() end
local function active() return SaveData.activeSlot(version()) end

local function slots()
  local ok, list = pcall(SaveData.listSlots, version())
  return ok and type(list) == "table" and list or {}
end
local function hasSave()
  if okSave and type(Save) == "table" and type(Save.exists) == "function" then
    local ok, yes = pcall(Save.exists, version())
    return ok and yes == true
  end
  return false
end

-- Deletes every test slot (never another), making `home` active first.
local function cleanup(home)
  local deleted, v = 0, version()
  for _, s in ipairs(slots()) do
    if type(s) == "table" and s.label == LABEL then
      if active() == s.id then
        local back = home
        if not back then
          for _, o in ipairs(slots()) do
            if type(o) == "table" and o.label ~= LABEL then back = o.id break end
          end
        end
        if back and back ~= s.id then SaveData.setActiveSlot(v, back) end
      end
      if SaveData.deleteSlot(v, s.id) then deleted = deleted + 1 end
    end
  end
  if home and active() ~= home then SaveData.setActiveSlot(v, home) end
  return deleted, active()
end

local homeRef
local function fail(reason)
  local ok, deleted = pcall(cleanup, homeRef)
  print(("KB_FAIL %s cleaned=%s"):format(reason, word(ok and deleted or "error")))
  love.event.quit(1)
  while true do coroutine.yield() end
end

local function memoDatasetChecks(seconds)
  local ok, DatasetViews = pcall(require, "src.mods.DatasetViews")
  if not ok or type(DatasetViews) ~= "table" or type(DatasetViews._ready) ~= "function" then return false end
  local ready = DatasetViews._ready
  DatasetViews._ready = function(self, view)
    local now = love.timer.getTime()
    if type(view) == "table" and view.kbReadyAt and now - view.kbReadyAt < seconds and not view.invalid
        and not view.unavailable then
      return true
    end
    local passed = ready(self, view)
    if passed and type(view) == "table" then view.kbReadyAt = now end
    return passed
  end
  return true
end

-- What is on top of the state stack: a screen's id (Screens stamps "Gen2<Name>", src/ui/Screens.lua:174),
-- the shared text box or yes/no, or nil.
local function topState(game)
  local stack = game and game.stack
  return stack and stack.top and stack:top() or nil
end
local function topName(game)
  local t = topState(game)
  if t == nil then return nil end
  local mt = getmetatable(t)
  if okTB and mt == TextBox then return "TextBox" end
  if okCB and mt == ChoiceBox then return "ChoiceBox" end
  if t.isTextBox then return "TextBox" end
  return type(t.screenId) == "string" and t.screenId or "?"
end

-- A press of one button for one step (a source pressed and released before Input:step: wasPressed true,
-- src/core/Input.lua), the driver's own source names.
local tapSeq = 0
local function tap(game, btn)
  tapSeq = tapSeq + 1
  local s = "kb-crystal-driver:" .. tapSeq
  game.input:sourcePress(btn, s)
  game.input:sourceRelease(btn, s)
end

-- The field is up and settled: the play phase with a map and a player, nothing on the stack, the World's own
-- START gate open (World:acceptsMenuInput, src/world/gen2/World.lua:1567-1578), no step, turn or setup.
local function fieldReady(game)
  local w = game and game.world
  if not (game and game.phase == "play" and w and w.map and w.player) then return false end
  if topState(game) ~= nil then return false end
  local ok, accepts = pcall(w.acceptsMenuInput, w)
  if not (ok and accepts) then return false end
  local p = w.player
  if p.moving or (tonumber(p.turnTimer) or 0) > 0 or p.jumping then return false end
  return not (w.mapSetup or w.pendingSceneScript or w.pendingWarp or w.waitButton)
end

-- ---------------------------------------------------------------- walks on the live map

local DIRS = { { "U", 0, -1 }, { "D", 0, 1 }, { "L", -1, 0 }, { "R", 1, 0 } }
local function key(x, y) return x .. "," .. y end

local function mapFacts(w)
  local map = w.map
  local npcs = {}
  for _, n in ipairs(w.npcs or {}) do
    if type(n) == "table" and n.cellX and n.cellY then npcs[key(n.cellX, n.cellY)] = true end
  end
  local function inside(x, y)
    local ok, v = pcall(map.inBounds, map, x, y)
    return ok and v == true
  end
  local function walkable(x, y)
    if not inside(x, y) then return false end
    local ok, v = pcall(map.isWalkable, map, x, y)
    return ok and v == true
  end
  local function warpAt(x, y)
    local ok, v = pcall(map.warpAt, map, x, y)
    return ok and v ~= nil
  end
  local function free(x, y) return walkable(x, y) and not npcs[key(x, y)] and not warpAt(x, y) end
  return { inside = inside, walkable = walkable, warpAt = warpAt, free = free, npcs = npcs }
end

-- Breadth-first from (x, y) over free cells to the first cell `goal(cx, cy)` accepts: the path as letters
-- and the cell, or nil.
local function walkTo(m, x, y, goal)
  local seen, queue, head = { [key(x, y)] = "" }, { { x, y } }, 1
  while head <= #queue do
    local cx, cy = queue[head][1], queue[head][2]
    head = head + 1
    local path = seen[key(cx, cy)]
    if goal(cx, cy) then return path, cx, cy end
    if #path < 64 then
      for _, d in ipairs(DIRS) do
        local nx, ny = cx + d[2], cy + d[3]
        if seen[key(nx, ny)] == nil and m.free(nx, ny) then
          seen[key(nx, ny)] = path .. d[1]
          queue[#queue + 1] = { nx, ny }
        end
      end
    end
  end
  return nil
end

-- The nearest cell beside something solid on the map (an inside cell no one may walk onto, no NPC there) and
-- the step into it; and the walk to the warp leading to PLAYERS_HOUSE_1F and the step onto it.
local function walks(w)
  local m = mapFacts(w)
  local x, y = w.player.cellX, w.player.cellY
  local bump
  local wall = walkTo(m, x, y, function(cx, cy)
    for _, d in ipairs(DIRS) do
      local nx, ny = cx + d[2], cy + d[3]
      if m.inside(nx, ny) and not m.walkable(nx, ny) and not m.npcs[key(nx, ny)] then
        bump = d[1]
        return true
      end
    end
    return false
  end)
  local stairs, into
  for _, wp in ipairs(type(w.map.def) == "table" and w.map.def.warps or {}) do
    if not stairs and wp.destMap == DOWNSTAIRS and tonumber(wp.x) and tonumber(wp.y) then
      local tx, ty = tonumber(wp.x), tonumber(wp.y)
      local step
      local path = walkTo(m, x, y, function(cx, cy)
        for _, d in ipairs(DIRS) do
          if cx + d[2] == tx and cy + d[3] == ty then
            step = d[1]
            return true
          end
        end
        return false
      end)
      if path then stairs, into = path, step end
    end
  end
  return wall, bump, stairs, into
end

-- The walk to the nearest warp leading to `dest` and the presses that take it from the cell beside it: one step
-- onto a door, stairs or a pad; onto a mat (COLL_WARP_CARPET_*) a step, then a press in the mat's direction
-- (World:checkCarpetWhileStanding, src/world/gen2/World.lua:10842-10854; Permissions.carpetDirection,
-- src/world/gen2/Permissions.lua:309-318).
local LETTER = { up = "U", down = "D", left = "L", right = "R" }
local function warpWalk(w, dest)
  local m = mapFacts(w)
  local x, y = w.player.cellX, w.player.cellY
  local best
  for _, wp in ipairs(type(w.map.def) == "table" and w.map.def.warps or {}) do
    local tx, ty = tonumber(wp.x), tonumber(wp.y)
    if wp.destMap == dest and tx and ty then
      local step
      local path = walkTo(m, x, y, function(cx, cy)
        for _, d in ipairs(DIRS) do
          if cx + d[2] == tx and cy + d[3] == ty then
            step = d[1]
            return true
          end
        end
        return false
      end)
      if path and (best == nil or #path < #best.path) then
        local okC, coll = pcall(w.map.cellCollision, w.map, tx, ty)
        local mat = okC and okPerm and type(Permissions.carpetDirection) == "function"
          and Permissions.carpetDirection(coll) or nil
        best = { path = path, into = step .. (mat and LETTER[mat] or "") }
      end
    end
  end
  if best == nil then return nil end
  return best.path, best.into
end

-- ---------------------------------------------------------------- Oak's speech (title_new)

-- NEW GAME's intro is under way: the boot phase with Oak's speech on the stack (Game2:showOakSpeech,
-- src/core/Game2.lua:277-290); the title and the main menu are left alone (an A there would start a game
-- the test didn't ask for).
local function introRunning(game)
  if not (game and game.phase == "boot") then return false end
  local states = game.stack and game.stack.states
  for _, state in ipairs(type(states) == "table" and states or {}) do
    if type(state) == "table" and state.screenId == "Gen2OakSpeech" then return true end
  end
  return false
end

-- One step of answering the intro's screens as a player would, before the bridge can (G2B): counts only.
local speech = { texts = 0, choices = 0, gender = 0, names = 0, screens = 0 }
local answered = setmetatable({}, { __mode = "k" })
local function answerSpeech(game, frame)
  local t = topState(game)
  if t == nil then return end
  local name = topName(game)
  if name == "Gen2InitClock" then
    print("KB_INITCLOCK")
    fail("init_clock_shown")
  end
  if not answered[t] then
    answered[t] = true
    if name == "TextBox" then speech.texts = speech.texts + 1
    elseif name == "ChoiceBox" then speech.choices = speech.choices + 1
    elseif name == "Gen2GenderSelect" then speech.gender = speech.gender + 1
    elseif name == "Gen2NamePick" or name == "Gen2NamingScreen" then speech.names = speech.names + 1
    else speech.screens = speech.screens + 1 end
  end
  if frame % 6 ~= 0 then return end
  if name == "Gen2NamePick" then
    -- the first preset name (NamePick's items: NEW NAME, then the presets; src/ui/gen2/NamePick.lua:25-30)
    if type(t.items) == "table" and #t.items >= 2 then t.cursor = 2 end
    tap(game, "a")
  elseif name == "Gen2NamingScreen" then
    -- an empty name takes the pick's first preset (NamePick.lua:69-76): START puts the cursor on END, A takes it
    tap(game, (frame % 12 == 0) and "start" or "a")
  elseif name == "ChoiceBox" then
    t.index = 1 -- YES
    tap(game, "a")
  else
    tap(game, "a") -- text boxes, the gender choice (its cursor opens on BOY), Oak's own beats
  end
end

-- ---------------------------------------------------------------- the UI path (title_ui, G2B)

-- KB_FIELD: where the new game stands, the clock's stored offset (the adapter's answer: 0), the walk to the
-- stairs and the step onto them.
local function printField(w, rtc)
  local _, _, stairs, into = walks(w)
  print(("KB_FIELD map=%s x=%d y=%d clock=%s stairs=%s into=%s"):format(word(w.map.id), w.player.cellX,
    w.player.cellY, word(type(rtc) == "table" and rtc.startMinute or "none"),
    word(stairs == "" and "-" or (stairs or "none")), word(into or "none")))
end

-- The walk to a free cell beside the nearest visible person of the map, the step that faces them, and their
-- cell and object index (World.npcs, src/world/gen2/World.lua; the follower left out).
local function spriteOf(n)
  if type(n.spriteDef) == "table" and type(n.spriteDef.id) == "string" then return n.spriteDef.id end
  return n.def.sprite
end
local function npcWalk(w, sprite)
  local m = mapFacts(w)
  local people = {}
  for _, n in ipairs(w.npcs or {}) do
    if type(n) == "table" and n.cellX and n.cellY and not n.follower and not n.hidden and n.visible ~= false
        and type(n.def) == "table" and tonumber(n.def.index) and (sprite == nil or spriteOf(n) == sprite) then
      people[key(n.cellX, n.cellY)] = n
    end
  end
  local face, target
  local path = walkTo(m, w.player.cellX, w.player.cellY, function(cx, cy)
    for _, d in ipairs(DIRS) do
      local n = people[key(cx + d[2], cy + d[3])]
      if n then
        face, target = d[1], n
        return true
      end
    end
    return false
  end)
  return path, face, target
end

local ui = { field = false, idle = 0, downstairs = false, npcPrinted = false, wheelSeen = 0, wheelFrames = 0,
             wheelRun = 0, wheelKey = nil, downTexts = 0, downKey = nil }
-- the story scene: idle frames per map, the lab's text boxes, what was printed, the ball taken
local story = { idle = 0, idleMap = nil, labTexts = 0, labKey = nil, lab = false, ball = nil, starter = false }

-- The bedroom's decoration callback, from the World's own record of the visit's edits (blockEdits: index -> the
-- block before, World:replaceBlock, src/world/gen2/World.lua:5844-5875): edited blocks, and how many differ now.
local function printBedroom(w)
  local edits = type(w.blockEdits) == "table" and w.blockEdits[HOUSE] or {}
  local n, differ = 0, 0
  for i, original in pairs(edits) do
    n = n + 1
    if w.map.def.blocks[i] ~= original then differ = differ + 1 end
  end
  print(("KB_BEDROOM edits=%d differ=%d"):format(n, differ))
end

-- The story scene past Mom: the town, the lab, the ball, the flag (counts, cells and map symbols only).
local function watchStory(game)
  local w = game.world
  if not (w and w.map) then return end
  if w.map.id == LAB and topName(game) == "TextBox" then
    local t = topState(game)
    if story.labKey ~= t then story.labKey, story.labTexts = t, story.labTexts + 1 end
  end
  if story.ball and not story.starter and w.events and w.events:get(STARTER_FLAG) then
    story.starter = true
    local masked = type(w.objectMasks) == "table" and w.objectMasks[("%s:%d"):format(LAB, story.ball)] == true
    print(("KB_STARTER flag=%d masked=%s"):format(STARTER_FLAG, tostring(masked)))
  end
  if not fieldReady(game) then
    story.idle = 0
    return
  end
  if story.idleMap ~= w.map.id then story.idleMap, story.idle = w.map.id, 0 end
  story.idle = story.idle + 1
  if story.idle < 30 then return end
  if w.map.id == TOWN and not story.lab then
    story.lab = true
    local path, into = warpWalk(w, LAB)
    print(("KB_LAB path=%s into=%s"):format(word(path == "" and "-" or (path or "none")), word(into or "none")))
  elseif w.map.id == LAB and story.labTexts > 0 and not story.ball then
    local path, face, n = npcWalk(w, BALL_SPRITE)
    story.ball = n and tonumber(n.def.index) or -1
    print(("KB_BALL path=%s face=%s x=%s y=%s index=%s"):format(word(path == "" and "-" or (path or "none")),
      word(face or "none"), word(n and n.cellX or "none"), word(n and n.cellY or "none"),
      word(n and n.def.index or "none")))
  end
end
local function watchUi(game)
  local name = topName(game)
  -- Mom's scene: a coord event in front of the stairs (the test walks onto it); its text boxes are counted so the
  -- idle field after it (not the one on arrival) gives KB_NPC
  local wd = game.world
  if wd and wd.map and wd.map.id == DOWNSTAIRS and name == "TextBox" then
    local t = topState(game)
    if ui.downKey ~= t then ui.downKey, ui.downTexts = t, ui.downTexts + 1 end
  end
  -- Oak's speech: the clock step is the adapter's (J5), its screen must never come up
  if introRunning(game) and name == "Gen2InitClock" then
    print("KB_INITCLOCK")
    fail("init_clock_shown")
  end
  -- Mom's weekday wheel (the SetDayOfWeek special pushes Gen2InitClock in the play phase): the adapter answers
  -- it; counted, and stuck for 10 s fails the run
  if game.phase == "play" and name == "Gen2InitClock" then
    local t = topState(game)
    if ui.wheelKey ~= t then
      ui.wheelKey, ui.wheelRun = t, 0
      ui.wheelSeen = ui.wheelSeen + 1
    end
    ui.wheelFrames, ui.wheelRun = ui.wheelFrames + 1, ui.wheelRun + 1
    if ui.wheelRun > 600 then fail("daywheel_stuck") end
  end
  if not fieldReady(game) then
    ui.idle = 0
    return
  end
  local w = game.world
  if not ui.field and w.map.id == HOUSE then
    ui.field = true
    printField(w, game.save and game.save.rtc)
    if SCENE == "story" then printBedroom(w) end
  end
  if w.map.id == DOWNSTAIRS then
    ui.idle = ui.idle + 1
    if ui.idle >= 30 and ui.downTexts > 0 and not ui.npcPrinted then
      ui.npcPrinted = true
      print(("KB_DAYWHEEL seen=%d frames=%d texts=%d"):format(ui.wheelSeen, ui.wheelFrames, ui.downTexts))
      local path, face, n = npcWalk(w)
      print(("KB_NPC map=%s path=%s face=%s x=%s y=%s index=%s"):format(DOWNSTAIRS,
        word(path == "" and "-" or (path or "none")), word(face or "none"), word(n and n.cellX or "none"),
        word(n and n.cellY or "none"), word(n and n.def.index or "none")))
      if SCENE == "story" then
        local exit, into = warpWalk(w, TOWN)
        print(("KB_EXIT path=%s into=%s"):format(word(exit == "" and "-" or (exit or "none")), word(into or "none")))
      end
    end
  else
    ui.idle = 0
  end
end

-- ---------------------------------------------------------------- the engine's own bake (export, G2D)

-- One map per tileset, environment, palette and roof group (sorted by id, at most 48), each baked whole by the
-- engine (World:bakeMapImage, src/world/gen2/World.lua:8911-9020; daytime as the export bakes it: Palettes.daytimeFor
-- with the clock at noon and Flash used) in the cart's colours (GbcPalette.mode "gbc"), then hashed per distinct
-- block id at its first position. Prints KB_BAKE per map and returns the totals.
local function bakeSample(game)
  local w = game.world
  local maps = game.data and game.data.gen2Maps or {}
  local Map2 = require("src.world.gen2.Map")
  local Palettes = require("src.world.gen2.Palettes")
  local BorderFill = require("src.world.gen2.BorderFill")
  local GbcPalette = require("src.render.GbcPalette")
  local ids = {}
  for id in pairs(maps) do ids[#ids + 1] = id end
  table.sort(ids)
  local picked, seenKey = {}, {}
  for _, id in ipairs(ids) do
    local def = maps[id]
    local roofGroup = (def.tileset == "TILESET_JOHTO" or def.tileset == "TILESET_JOHTO_MODERN") and def.group or "-"
    local k = table.concat({ tostring(def.tileset), tostring(def.environment), tostring(def.palette), tostring(roofGroup) }, "|")
    if not seenKey[k] and #picked < 48 then
      seenKey[k] = true
      picked[#picked + 1] = id
    end
  end
  local mode = GbcPalette.mode
  GbcPalette.mode = "gbc"
  local total, skippedAll = 0, 0
  for _, id in ipairs(picked) do
    local def = maps[id]
    local ts = w.tilesets and w.tilesets[def.tileset]
    local daytime = Palettes.daytimeFor(def, 12, true)
    local okM, map = pcall(Map2.new, def, ts)
    local okB, canvas = false, nil
    if okM and map then okB, canvas = pcall(w.bakeMapImage, w, map, daytime) end
    if okB and canvas then
      local img = canvas:newImageData()
      local raw, pw = img:getString(), map.width * 32
      local okL, layers = pcall(w.animLayers, w, ts)
      layers = okL and layers or {}
      local seen, parts, skipped = {}, {}, 0
      for by = 0, map.height - 1 do
        for bx = 0, map.width - 1 do
          local blockId = BorderFill.blockFor(def.blocks[by * map.width + bx + 1], def.borderBlock)
          if not seen[blockId] then
            seen[blockId] = true
            local block = ts.blocks and ts.blocks[blockId + 1] or {}
            local animated = false
            for i = 1, 16 do
              if layers[block[i] or 0] then animated = true end
            end
            if animated then
              skipped = skipped + 1
            else
              local rows = {}
              for r = 0, 31 do
                local at = ((by * 32 + r) * pw + bx * 32) * 4 + 1
                rows[r + 1] = raw:sub(at, at + 127)
              end
              local h = love.data.encode("string", "hex", love.data.hash("md5", table.concat(rows)))
              parts[#parts + 1] = blockId .. ":" .. h:sub(1, 8)
            end
          end
        end
      end
      if img.release then img:release() end
      if canvas.release then canvas:release() end
      total, skippedAll = total + #parts, skippedAll + skipped
      print(("KB_BAKE map=%s daytime=%s n=%d skipped=%d ids=%s"):format(word(id), word(daytime), #parts, skipped,
        #parts > 0 and table.concat(parts, "+") or "-"))
    else
      print(("KB_BAKE map=%s daytime=%s n=0 skipped=0 ids=failed"):format(word(id), word(daytime)))
    end
  end
  GbcPalette.mode = mode
  return #picked, total, skippedAll, mode
end

-- ---------------------------------------------------------------- the scene

return function(game)
  local started = love.timer.getTime()
  local frame = 0
  local phase = "ready"
  local slot, start
  local saved = false
  local waitUntil = 0
  while true do
    frame = frame + 1
    local status = game and game.mods and game.mods.exports and game.mods.exports["gen1-bridge"]
    if phase == "ready" then
      -- the bridge is up (game.ready ran, Game2.lua:1262) and the game has started: driver mode's world, or the
      -- boot cinema (POKEPORT_BOOT_CINEMA=1)
      local w = game and game.world
      if status and status.ready and (game.phase == "boot" or (game.phase == "play" and w and w.map)) then
        status.configure({ port = PORT })
        if DATASET_MEMO > 0 then
          print(("KB_DATASET_MEMO seconds=%d ok=%s"):format(DATASET_MEMO, tostring(memoDatasetChecks(DATASET_MEMO))))
        end
        homeRef = active()
        if SCENE == "cleanup" then
          local deleted, now = cleanup(homeRef)
          print(("KB_CLEANED deleted=%d active=%d"):format(deleted, num(now)))
          print("KB_QUIT sessions=0 code=0")
          return
        end
        local precleaned = cleanup(homeRef)
        local v = version()
        slot = SaveData.createSlot(v)
        if not slot then fail("no_slot") end
        SaveData.renameSlot(v, slot, LABEL)
        if SaveData.setActiveSlot(v, slot) ~= slot then fail("activate_failed") end
        print(("KB_BOOTED scene=%s slot=%d home=%d precleaned=%d phase=%s"):format(word(SCENE), num(slot),
          num(homeRef), precleaned, word(game.phase)))
        if SCENE == "field" or SCENE == "title_continue" or SCENE == "export" then
          phase = "field"
        elseif SCENE == "title_new" or SCENE == "title_ui" or SCENE == "story" then
          if game.phase == "play" then pcall(game.returnToTitle, game) end
          waitUntil = love.timer.getTime() + 1
          phase = "title"
        else
          fail("unknown_scene")
        end
      end
    elseif phase == "field" then
      if fieldReady(game) then
        local w = game.world
        if w.map.id ~= HOUSE then fail("not_in_house") end
        if SCENE == "export" then
          local n, blocks, skipped, mode = bakeSample(game)
          print(("KB_EXPORT_READY maps=%d blocks=%d skipped=%d mode=%s"):format(n, blocks, skipped, word(mode)))
          phase = "serve"
        elseif SCENE == "field" then
          start = { x = w.player.cellX, y = w.player.cellY }
          local wall, bump, stairs, into = walks(w)
          print(("KB_SERVING map=%s x=%d y=%d facing=%s wall=%s bump=%s stairs=%s into=%s"):format(HOUSE, start.x,
            start.y, word(w.player.facing), word(wall == "" and "-" or (wall or "none")), word(bump or "none"),
            word(stairs == "" and "-" or (stairs or "none")), word(into or "none")))
          phase = "serve"
        else
          local ok, err = pcall(game.writeSave, game)
          print(("KB_SAVED slot=%d ok=%s"):format(num(active()), word(ok and hasSave())))
          if not (ok and hasSave()) then fail("save_failed " .. word(err):sub(1, 40)) end
          pcall(game.returnToTitle, game)
          waitUntil = love.timer.getTime() + 1
          phase = "title"
        end
      end
    elseif phase == "title" then
      local name = topName(game)
      if game.phase == "boot" and love.timer.getTime() >= waitUntil
          and (name == "Gen2TitleState" or name == "Gen2MainMenu") then
        print(("KB_TITLE slot=%d hasSave=%s"):format(num(active()), word(hasSave())))
        phase = "serve"
      end
    elseif phase == "serve" then
      -- the field scene's one save, once a client watches and the player stands off the start cell
      if SCENE == "field" and not saved and (tonumber(status.sessions) or 0) >= 1 and fieldReady(game) then
        local p = game.world.player
        if p.cellX ~= start.x or p.cellY ~= start.y then
          saved = true
          local ok = pcall(game.writeSave, game)
          print(("KB_SAVED slot=%d ok=%s"):format(num(active()), word(ok and hasSave())))
        end
      end
      -- title_ui (G2B): nothing answered here; the clock never in the speech, the walks once the field is up;
      -- story (G2C): the same, then the town, the lab and the starter
      if SCENE == "title_ui" or SCENE == "story" then watchUi(game) end
      if SCENE == "story" and ui.npcPrinted then watchStory(game) end
      -- title_new: Oak's speech after region.start, answered until the new game stands in the field
      if SCENE == "title_new" and introRunning(game) then
        answerSpeech(game, frame)
      elseif SCENE == "title_new" and not saved and fieldReady(game) and speech.texts > 0 then
        saved = true -- (reused: the field reached once)
        local w = game.world
        local rtc = game.save and game.save.rtc
        print(("KB_SPEECH texts=%d choices=%d gender=%d names=%d screens=%d"):format(speech.texts, speech.choices,
          speech.gender, speech.names, speech.screens))
        printField(w, rtc)
      end
      if status.quitRequested then
        local deleted, now = cleanup(homeRef)
        print(("KB_CLEANED deleted=%d active=%d"):format(deleted, num(now)))
        print(("KB_QUIT sessions=%s code=%s"):format(word(status.sessions), word(status.quitCode)))
        return
      end
    end
    if love.timer.getTime() - started > SECONDS_LIMIT then
      fail("timeout phase=" .. phase)
    end
    game = coroutine.yield() or game
  end
end
