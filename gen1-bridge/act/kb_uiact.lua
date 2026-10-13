-- kb_uiact: answers to the hidden game's UIs and the raw buttons
-- (docs/PLAN.md section 4.1 "UI observer", docs/PROTOCOL.md "UI" and
-- "Interaction"; refs/_research/P2_gen1_ui.md sections 3-6 and 8).
--
--   ui.advance{ui}        text: A, queued until the box is ready (P2 3.3);
--                         a box that never waits for A (auto, stay, a
--                         choice box's text) just lets its advances die with it
--   ui.pick{ui, index}    choice: 0 = YES (the cursor on YES, then A), 1 = NO
--                         (B, which always answers NO, P2 4); menu and list:
--                         the cursor on the row, then A
--   ui.back{ui}           choice, menu, list, quantity: B
--   ui.qty{ui, qty}       quantity: the number (1..max), then A
--   ui.name{ui, glyphs}   naming: checked against the grid and the length
--                         first; with the presets up, NEW NAME (or the preset
--                         itself when the name is one) is picked; then, with
--                         the grid on top, the glyphs are set and START is
--                         tapped, which runs the screen's own confirm
--                         (NamingScreen.lua:201-203, :153-174)
--   act.button{button, how}  answered by act.result: START tapped while idle
--                         (the start menu then opens as a normal menu) or on
--                         the start menu (which it closes); on a mirror
--                         screen any button, as a tap or a hold (down/up),
--                         never the A+B+START+SELECT soft-reset chord
--
-- Answers wait for their UI to be the top state (only the top state takes
-- input); one tap per logic step, through the step's shared tap budget. A
-- UI that closes takes its waiting answers with it (error ui.stale), an
-- answer that can't be applied for ANSWER_TIMEOUT steps is dropped (error
-- ui.timeout). Mirror-kind UIs are never answered by ui.* messages; the
-- client sends act.button for them. Logs show codes, kinds and counts only.

local UiAct = {}

UiAct.ANSWER_TIMEOUT = 180  -- steps a pick/back/qty/name waits for its UI to be on top
UiAct.MAX_ADVANCES = 16     -- ui.advance queued per text box
UiAct.MAX_BUTTONS = 8       -- act.button requests waiting
UiAct.BUTTON_WAIT = 30      -- steps a START waits for the player to stand

local floor = math.floor
local concat = table.concat

local BUTTONS = { a = true, b = true, start = true, select = true, up = true, down = true, left = true, right = true }
local HOWS = { tap = true, down = true, up = true }
local CHORD = { "a", "b", "start", "select" }
local LISTLIKE = { menu = true, list = true }
-- which answers each kind takes
local TAKES = {
  ["ui.advance"] = { text = true },
  ["ui.pick"] = { choice = true, menu = true, list = true },
  ["ui.back"] = { choice = true, menu = true, list = true, quantity = true },
  ["ui.qty"] = { quantity = true },
  ["ui.name"] = { naming = true },
}

local function whole(v, lo, hi)
  return type(v) == "number" and v == floor(v) and v >= lo and v <= hi
end

-- opts:
--   engine        kb_engine instance (uiSetIndex, uiSetQty, uiSetGlyphs, buttonDown)
--   ui            the kb_ui observer (top, entry)
--   press(btn)    mod.input:press with the live game; returns a token
--   release(tok)  mod.input:release
--   log           kb_log logger (optional)
function UiAct.new(opts)
  local engine, ui, log = opts.engine, opts.ui, opts.log
  local act = {}
  local pending = {}       -- ui id -> { advances = n, list = { answers } }
  local buttons = {}       -- act.button requests, oldest first
  local overflow = {}      -- act.button requests beyond MAX_BUTTONS (answered busy)
  local holds = {}         -- button -> token (mirror holds)
  local heldFor            -- the mirror UI the holds belong to
  local generation
  local steps = 0
  local counts = {
    answers = 0, advanced = 0, picked = 0, backs = 0, quantities = 0, names = 0, presets = 0,
    stale = 0, timeouts = 0, rejected = 0, buttons = 0, buttonTaps = 0, holds = 0, repressed = 0,
    refused = 0,
  }

  local function press(btn)
    local ok, token = pcall(opts.press, btn)
    if ok and token ~= nil then return token end
    if log then log.once("uiact.press_failed:" .. btn, "warn", "uiact.press_failed", { button = btn }) end
    return nil
  end

  local function release(token)
    if token ~= nil then pcall(opts.release, token) end
  end

  local function releaseHolds()
    for btn, token in pairs(holds) do
      holds[btn] = nil
      release(token)
    end
    heldFor = nil
  end

  local function errorOut(out, code, a, msg)
    out[#out + 1] = { "error", { code = code, msg = msg or code }, "answer", re = a.seq, gen = a.gen }
  end

  local function result(out, b, ok, reason, kind)
    local data = { ok = ok }
    if reason then data.reason = reason end
    if kind then data.kind = kind end
    out[#out + 1] = { "act.result", data, "answer", re = b.seq, gen = b.gen }
  end

  -- ------------------------------------------------------------ requests

  -- A ui.* answer from the session (role already checked). Returns true,
  -- or false plus an error code: ui.stale (no such open UI), ui.wrong_kind,
  -- bad_message.
  function act.request(msgType, data, seq, gen)
    local id = data.ui
    if not whole(id, 1, 2147483647) then return false, "bad_message" end
    local e = ui.entry(id)
    if not e or not e.announced then
      counts.stale = counts.stale + 1
      return false, "ui.stale"
    end
    if not (TAKES[msgType] and TAKES[msgType][e.kind]) then
      counts.rejected = counts.rejected + 1
      return false, "ui.wrong_kind"
    end
    local a = { type = msgType, seq = seq, gen = gen, since = steps }
    if msgType == "ui.pick" then
      if not whole(data.index, 0, 255) then return false, "bad_message" end
      if e.kind == "choice" and data.index > 1 then return false, "bad_message" end
      a.index = data.index
    elseif msgType == "ui.qty" then
      if not whole(data.qty, 0, 999) then return false, "bad_message" end
      a.qty = data.qty
    elseif msgType == "ui.name" then
      local glyphs = data.glyphs
      if type(glyphs) ~= "table" or #glyphs < 1 or #glyphs > 10 then return false, "bad_message" end
      local copy = {}
      for i = 1, #glyphs do
        if type(glyphs[i]) ~= "string" then return false, "bad_message" end
        copy[i] = glyphs[i]
      end
      -- one of the screen's own presets is always fine; anything else must
      -- be typeable on its grid, within its length (NamingScreen.lua:98, :242)
      local name, preset = concat(copy), false
      for _, p in ipairs(e.presets or {}) do
        if p.raw == name or p.shown == name then preset = true end
      end
      if not preset then
        if #copy > (e.max or 10) then
          counts.rejected = counts.rejected + 1
          return false, "bad_message"
        end
        for i = 1, #copy do
          if not (e.glyphs and e.glyphs[copy[i]]) then
            counts.rejected = counts.rejected + 1
            return false, "bad_message"
          end
        end
      end
      a.glyphs = copy
    end
    local p = pending[id]
    if not p then
      p = { advances = 0, list = {} }
      pending[id] = p
    end
    counts.answers = counts.answers + 1
    if msgType == "ui.advance" then
      if p.advances < UiAct.MAX_ADVANCES then p.advances = p.advances + 1 end
    else
      p.list[#p.list + 1] = a
    end
    return true
  end

  -- An act.button from the session. Returns true, or false plus
  -- bad_message. Beyond MAX_BUTTONS waiting, a request is answered at once
  -- with reason busy (on the next step).
  function act.button(data, seq, gen)
    if not BUTTONS[data.button] or not HOWS[data.how] then return false, "bad_message" end
    local b = { button = data.button, how = data.how, seq = seq, gen = gen, since = steps }
    counts.buttons = counts.buttons + 1
    if #buttons >= UiAct.MAX_BUTTONS then
      overflow[#overflow + 1] = b
    else
      buttons[#buttons + 1] = b
    end
    return true
  end

  -- ------------------------------------------------------------ each step

  local function chordWith(btn)
    for i = 1, #CHORD do
      local b = CHORD[i]
      if b ~= btn and holds[b] == nil then return false end
    end
    return BUTTONS[btn] ~= nil and (btn == "a" or btn == "b" or btn == "start" or btn == "select")
  end

  local function handleButton(f, mode, top, out, ctx)
    local b = buttons[1]
    if not b then return end
    local budget = ctx.budget
    local ok, reason
    if top and top.kind == "mirror" then
      if b.how == "tap" then
        if not budget.can() then return end
        budget.tap(b.button)
        counts.buttonTaps = counts.buttonTaps + 1
        ok = true
      elseif b.how == "down" then
        if holds[b.button] then
          ok = true
        elseif chordWith(b.button) then
          ok, reason = false, "chord"
        else
          holds[b.button] = press(b.button)
          heldFor = top.id
          counts.holds = counts.holds + 1
          ok = holds[b.button] ~= nil
          if not ok then reason = "press_failed" end
        end
      else
        local token = holds[b.button]
        holds[b.button] = nil
        release(token)
        if next(holds) == nil then heldFor = nil end
        ok = true
      end
    elseif b.button == "start" and b.how == "tap" and top and top.kind == "menu" and top.screen == "StartMenu" then
      -- the start menu's mask has START: it closes it (Menu.lua:106-115, StartMenu.lua:192)
      if not budget.can() then return end
      budget.tap("start")
      counts.buttonTaps = counts.buttonTaps + 1
      ok = true
    elseif b.button == "start" and b.how == "tap" and top == nil and mode == "idle" and f.inGame then
      -- overworld START is an edge read on a standing poll (OC:1815-1834): wait
      -- for the player to stand and the step driver to finish
      if f.moving or ctx.moverBusy or not budget.can() then
        if steps - b.since <= UiAct.BUTTON_WAIT then return end
        ok, reason = false, "not_idle"
      else
        budget.tap("start")
        counts.buttonTaps = counts.buttonTaps + 1
        ok = true
      end
    elseif top == nil and mode ~= "idle" then
      ok, reason = false, "not_idle"
    else
      ok, reason = false, "not_mirror"
    end
    table.remove(buttons, 1)
    if not ok then counts.refused = counts.refused + 1 end
    result(out, b, ok, reason)
  end

  -- Applies the oldest waiting answer to the top UI. Returns true when the
  -- answer is finished (applied or failed), false to keep it waiting.
  local function apply(top, presetKey, a, out, budget)
    local kind = top.kind
    if a.type == "ui.back" then
      budget.tap("b")
      counts.backs = counts.backs + 1
      return true
    elseif a.type == "ui.pick" then
      if kind == "choice" then
        if a.index == 1 then
          budget.tap("b") -- B always answers NO (ChoiceBox.lua:62-70)
        else
          if not engine.uiSetIndex(top.key, 1) then errorOut(out, "bad_message", a) return true end
          budget.tap("a")
        end
      else
        if not engine.uiSetIndex(top.key, a.index + 1) then
          errorOut(out, "bad_message", a, "index out of range")
          return true
        end
        budget.tap("a")
      end
      counts.picked = counts.picked + 1
      return true
    elseif a.type == "ui.qty" then
      if not engine.uiSetQty(top.key, a.qty) then
        errorOut(out, "bad_message", a, "qty out of range")
        return true
      end
      budget.tap("a")
      counts.quantities = counts.quantities + 1
      return true
    elseif a.type == "ui.name" then
      if presetKey then
        if a.phase == "presets" then return false end -- NEW NAME is on its way
        local name, pick = concat(a.glyphs), 1
        for k, preset in ipairs(top.presets or {}) do
          if preset.raw == name or preset.shown == name then pick = k + 1 break end
        end
        if not engine.uiSetIndex(presetKey, pick) then errorOut(out, "bad_message", a) return true end
        budget.tap("a")
        if pick > 1 then
          counts.presets = counts.presets + 1
          counts.names = counts.names + 1
          return true
        end
        a.phase = "presets"
        return false
      end
      if not engine.uiSetGlyphs(top.key, a.glyphs) then errorOut(out, "bad_message", a) return true end
      budget.tap("start")
      counts.names = counts.names + 1
      return true
    end
    return true
  end

  local function applyTop(top, presetKey, out, budget)
    local p = pending[top.id]
    if not p then return end
    local c = top.cursor or {}
    if top.kind == "text" then
      if p.advances > 0 and c.ready and budget.can() then
        budget.tap("a")
        p.advances = p.advances - 1
        counts.advanced = counts.advanced + 1
      end
      return
    end
    local a = p.list[1]
    if not a or c.busy or not budget.can() then return end
    -- a NEW NAME pick lets the preset menu go first: wait for the grid
    if a.phase == "presets" and presetKey then return end
    if apply(top, presetKey, a, out, budget) then
      table.remove(p.list, 1)
    elseif a.phase == "presets" then
      a.since = steps
    end
  end

  -- f:     kb_engine.observe() for this step
  -- mode:  kb_player.classify(f)
  -- out:   this step's messages; answers are added (act.result, error),
  --        with re and gen fields
  -- ctx:   { live, generation, budget = { can(), tap(btn) }, moverBusy }
  function act.step(f, mode, out, ctx)
    steps = steps + 1
    if not (ctx and ctx.live) then
      if next(pending) or #buttons > 0 or #overflow > 0 or next(holds) then
        pending, buttons, overflow = {}, {}, {}
        releaseHolds()
      end
      generation = nil
      return
    end
    if generation ~= ctx.generation then
      -- a new connection: what the old one asked for dies unanswered
      pending, buttons, overflow = {}, {}, {}
      releaseHolds()
      generation = ctx.generation
    end
    for _, b in ipairs(overflow) do
      counts.refused = counts.refused + 1
      result(out, b, false, "busy")
    end
    overflow = {}
    local top, presetKey = ui.top()

    -- UIs that closed take their answers with them
    for id, p in pairs(pending) do
      if not ui.entry(id) then
        for _, a in ipairs(p.list) do
          counts.stale = counts.stale + 1
          errorOut(out, "ui.stale", a)
        end
        pending[id] = nil
      end
    end

    -- mirror holds end with their screen; a focus change's Input:reset
    -- drops them in the engine, so they are pressed again
    if heldFor and not (top and top.id == heldFor) then releaseHolds() end
    for btn, token in pairs(holds) do
      if not engine.buttonDown(btn) then
        release(token)
        holds[btn] = press(btn)
        counts.repressed = counts.repressed + 1
      end
    end

    handleButton(f, mode, top, out, ctx)
    if top and top.kind ~= "mirror" then applyTop(top, presetKey, out, ctx.budget) end

    -- answers waiting on a UI that isn't on top give up after a while
    for id, p in pairs(pending) do
      if not (top and top.id == id) then
        for i = #p.list, 1, -1 do
          local a = p.list[i]
          if steps - a.since > UiAct.ANSWER_TIMEOUT then
            table.remove(p.list, i)
            counts.timeouts = counts.timeouts + 1
            if log then log.warn("uiact.timeout", { type = a.type, count = counts.timeouts }) end
            errorOut(out, "ui.timeout", a)
          end
        end
      end
    end
  end

  function act.releaseAll() releaseHolds() end

  function act.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    local held = 0
    for _ in pairs(holds) do held = held + 1 end
    out.held = held
    out.waitingButtons = #buttons
    return out
  end

  return act
end

return UiAct
