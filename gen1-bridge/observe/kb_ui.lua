-- kb_ui: the UI observer (docs/PLAN.md section 4.1 "UI observer",
-- docs/PROTOCOL.md "UI" and "Text").
--
--   ui.open{ui, kind, ...}   once per state, when it first becomes the top
--                            of the screen stack (ids unique per connection;
--                            after every snapshot the open UIs are sent
--                            again, bottom first, with their ids)
--   ui.update{ui, ...}       the moving parts: text page/line/waiting/done,
--                            a cursor (index/top), a quantity, a list's rows
--   ui.open{ui, ...} again   (M7) a menu or list whose footer or money
--                            changed while open (the Mart's clerk lines, its
--                            money box): the whole UI again with its id, which
--                            the client replaces in place
--   ui.close{ui}             once the state has left the stack
--
-- The stack is polled every logic step (kb_engine.uiStack: plain entries
-- with a stable key per state); screen.pushed/popped only wake a full
-- re-read of the open lists' rows. Kinds by class (kb_engine.uiClass):
--   TextBox -> text, ChoiceBox -> choice, Menu -> menu, ListMenu -> list,
--   QuantityBox -> quantity, NamingScreen -> naming (its preset Menu on
--   top is part of it), PokedexMenu / PrizeCounter / TownMap's fly list ->
--   list (small adapters), everything else that takes input -> mirror
--   (screen = its id). Busy states (fades, slides, panels) open nothing;
--   the mod manager and anything above it are ignored.
--
-- Every text field is translated to the protocol markup (Ui.markup). Logs
-- show codes, kinds, ids and counts only, never text.

local Ui = {}

Ui.MAX_STRING = 1000     -- ui.open string maxLength
Ui.MAX_PAGE_LINES = 8    -- pages[p] maxItems
Ui.MAX_PAGES = 64        -- pages maxItems
Ui.MAX_ITEMS = 256       -- items maxItems
Ui.MAX_LABELS = 8        -- choice labels, naming presets
Ui.MAX_ROWS = 32         -- rows maximum
Ui.MAX_GRID_PAGES, Ui.MAX_GRID_ROWS, Ui.MAX_GRID_CELLS, Ui.MAX_GLYPH = 4, 12, 16, 8
Ui.ITEMS_EVERY = 30      -- steps between re-reads of the rows of open lists not on top

local byte, find, gsub, sub = string.byte, string.find, string.gsub, string.sub
local concat = table.concat
local floor = math.floor

-- ------------------------------------------------------------ markup

-- Font-only charmap sequences (the engine draws them as single tiles):
-- written out as the plain characters they show. <PK> and <MN> stay tokens.
local FONT_SEQ = {
  ["<COLON>"] = ":", ["<DOT>"] = ".", ["<LV>"] = ":L", ["<ID>"] = "ID", ["<to>"] = "to",
  ["<ED>"] = "ED", ["<PKMN>"] = "<PK><MN>", ["<\226\128\166\226\128\166>"] = "\226\128\166\226\128\166",
}
local POKE = "POK\195\169" -- "POKé"

-- Replaces every malformed UTF-8 sequence with "?" (strict rules: no
-- overlong forms, no surrogates, nothing above U+10FFFF).
local function utf8Clean(s)
  if not find(s, "[\128-\255]") then return s, 0 end
  local out, n, i, len, bad = {}, 0, 1, #s, 0
  while i <= len do
    local c = byte(s, i)
    local need, lo, hi = 0, 128, 191
    if c < 128 then need = 0
    elseif c >= 194 and c <= 223 then need = 1
    elseif c == 224 then need, lo = 2, 160
    elseif c >= 225 and c <= 236 then need = 2
    elseif c == 237 then need, hi = 2, 159
    elseif c >= 238 and c <= 239 then need = 2
    elseif c == 240 then need, lo = 3, 144
    elseif c >= 241 and c <= 243 then need = 3
    elseif c == 244 then need, hi = 3, 143
    else need = -1 end
    local ok = need >= 0
    if ok then
      for k = 1, need do
        local d = byte(s, i + k)
        local l, h = 128, 191
        if k == 1 then l, h = lo, hi end
        if not d or d < l or d > h then ok = false break end
      end
    end
    n = n + 1
    if ok then
      out[n] = sub(s, i, i + need)
      i = i + need + 1
    else
      out[n] = "?"
      bad = bad + 1
      i = i + 1
    end
  end
  return concat(out), bad
end

-- At most `max` code points, cut on a character boundary.
local function capChars(s, max)
  local _, cont = gsub(s, "[\128-\191]", "")
  if #s - cont <= max then return s, false end
  local chars, i = 0, 1
  while i <= #s do
    local c = byte(s, i)
    if c < 128 or c >= 192 then
      chars = chars + 1
      if chars > max then return sub(s, 1, i - 1), true end
    end
    i = i + 1
  end
  return s, false
end

-- One string in the protocol's text markup (docs/PROTOCOL.md "Text").
-- Returns the text and how many things had to be fixed (engine tokens the
-- substitution left behind, font-only sequences, control or malformed bytes,
-- an over-long string); a plain string comes back unchanged with 0.
function Ui.markup(s)
  if type(s) ~= "string" then return "", 1 end
  local fixes, n = 0, 0
  -- engine tokens the engine's substitution kept ({NUM:..., 2 | ...}, P2 3.1)
  s, n = gsub(s, "{[^{}]*}", "")
  fixes = fixes + n
  s, n = gsub(s, "<BOLD_(%u)>", "%1")
  fixes = fixes + n
  s = gsub(s, "<[^<>]*>", function(seq)
    local plain = FONT_SEQ[seq]
    if plain then fixes = fixes + 1 end
    return plain
  end)
  -- "#" is the POKé macro (charmap $54), never a literal
  s = gsub(s, "#", POKE)
  -- no line breaks inside one string; other control bytes go
  s, n = gsub(s, "[\n\r\t\v\f]", " ")
  fixes = fixes + n
  s, n = gsub(s, "[%z\1-\31\127]", "")
  fixes = fixes + n
  s, n = utf8Clean(s)
  fixes = fixes + n
  local cut
  s, cut = capChars(s, Ui.MAX_STRING)
  if cut then fixes = fixes + 1 end
  return s, fixes
end

-- ------------------------------------------------------------ the observer

local KINDS = {
  TextBox = "text", ChoiceBox = "choice", Menu = "menu", ListMenu = "list",
  QuantityBox = "quantity", NamingScreen = "naming", PokedexMenu = "list", PrizeCounter = "list",
}
local MIRRORS = { PartyMenu = true, PicBox = true, BattleState = true, Screen = true }
local LISTLIKE = { menu = true, list = true }

local function clampInt(v, lo, hi)
  v = tonumber(v) or lo
  v = floor(v)
  if v < lo then return lo end
  if v > hi then return hi end
  return v
end

-- opts:
--   engine   kb_engine instance (uiStack, uiOpenFacts, uiCursor, uiItems)
--   json     kb_json
--   log      kb_log logger (optional)
--   dexOf    species id -> national dex number (naming's `mon`), optional
function Ui.new(opts)
  local engine, json, log = opts.engine, opts.json, opts.log
  local dexOf = opts.dexOf or function() return nil end
  local obs = {}
  local open = {}          -- entries, in stack order
  local byKey, byId = {}, {}
  local lastId = 0
  local steps = 0
  local top, presetKey     -- the effective top entry, and the preset Menu above a naming screen
  local counts = { opened = 0, closed = 0, updates = 0, reannounced = 0, reopened = 0, fixes = 0, cut = 0,
                   mirrors = 0 }

  local function text(s)
    local out, fixes = Ui.markup(s)
    if fixes > 0 then counts.fixes = counts.fixes + fixes end
    return out
  end

  local function texts(list, max)
    local out = {}
    for i = 1, #(list or {}) do
      if #out >= max then counts.cut = counts.cut + 1 break end
      out[#out + 1] = text(list[i])
    end
    return out
  end

  local function shapeItems(items)
    local out = {}
    for i = 1, #(items or {}) do
      if #out >= Ui.MAX_ITEMS then counts.cut = counts.cut + 1 break end
      local row = { label = text(items[i].label) }
      if items[i].right ~= nil then row.right = text(items[i].right) end
      out[#out + 1] = row
    end
    return out
  end

  local function sameItems(a, b)
    if a == nil or b == nil or #a ~= #b then return false end
    for i = 1, #a do
      if a[i].label ~= b[i].label or a[i].right ~= b[i].right then return false end
    end
    return true
  end

  -- The kind a stack entry opens as, or nil when it opens nothing.
  local function kindOf(entry, cursor)
    local cls = entry.cls
    if cls == "TownMap" then return (cursor and cursor.fly) and "list" or "mirror" end
    if cls == "ListMenu" and cursor and cursor.script then return "mirror" end
    if KINDS[cls] then return KINDS[cls] end
    if MIRRORS[cls] then return "mirror" end
    return nil
  end

  -- text: the engine's pages split into protocol pages of at most 8 lines;
  -- start[p] is the protocol page (0-based) of engine page p's first line.
  local function textData(e, f)
    local pages, cont, start = {}, {}, {}
    for p = 1, #f.pages do
      start[p] = #pages
      local lines, conts = f.pages[p], f.cont[p]
      local i = 1
      repeat
        if #pages >= Ui.MAX_PAGES then counts.cut = counts.cut + 1 break end
        local pl, pc = {}, {}
        for k = i, math.min(#lines, i + Ui.MAX_PAGE_LINES - 1) do
          pl[#pl + 1] = text(lines[k])
          pc[#pc + 1] = conts[k] == true
        end
        if #pl == 0 then pl[1], pc[1] = "", false end
        pages[#pages + 1], cont[#cont + 1] = pl, pc
        i = i + Ui.MAX_PAGE_LINES
      until i > #lines
    end
    if #pages == 0 then pages[1], cont[1] = { "" }, { false } end
    e.pageStart, e.pageCount = start, #pages
    local data = { pages = pages, cont = cont }
    if f.auto then data.auto = true end
    if f.stay then data.stay = true end
    if f.instant then data.instant = true end
    if f.choice then data.choice = true end -- a YES/NO opens over it at its end
    return data
  end

  -- the protocol page/line of the engine's 1-based pageIndex/lineIndex
  local function textPos(e, page, line)
    local first = e.pageStart and e.pageStart[page]
    if first == nil then return clampInt((e.pageCount or 1) - 1, 0, 255), 0 end
    local pp = first + floor((line - 1) / Ui.MAX_PAGE_LINES)
    local pl = (line - 1) % Ui.MAX_PAGE_LINES
    if pp > (e.pageCount or 1) - 1 then pp = (e.pageCount or 1) - 1 end
    return clampInt(pp, 0, 255), clampInt(pl, 0, 255)
  end

  local function namingData(e, f)
    local data = {
      title = text(f.title), max = clampInt(f.max, 1, 999),
      presets = texts(f.presets, Ui.MAX_LABELS), value = text(f.value),
    }
    local dex = f.species and tonumber(dexOf(f.species)) or nil
    if dex and dex == floor(dex) and dex >= 1 and dex <= 151 then data.mon = dex end
    local grid, set = {}, {}
    for p = 1, #(f.grid or {}) do
      if #grid >= Ui.MAX_GRID_PAGES then break end
      local rows = {}
      for r = 1, #f.grid[p] do
        if #rows >= Ui.MAX_GRID_ROWS then counts.cut = counts.cut + 1 break end
        local cells = {}
        for c = 1, #f.grid[p][r] do
          -- a cell is sent as the engine's own token, which is already the
          -- markup (A, é, <PK>, " "); one that isn't is left out
          local cell = f.grid[p][r][c]
          local _, cont = gsub(cell, "[\128-\191]", "")
          if #cells < Ui.MAX_GRID_CELLS and #cell - cont <= Ui.MAX_GLYPH and Ui.markup(cell) == cell then
            cells[#cells + 1] = cell
            set[cell] = true
          else
            counts.cut = counts.cut + 1
          end
        end
        if #cells > 0 then rows[#rows + 1] = cells end
      end
      grid[#grid + 1] = rows
    end
    if #grid > 0 then data.grid = grid end
    -- the presets as the menu holds them (raw) and as the client sees them
    e.glyphs, e.max, e.presets = set, data.max, {}
    for i, p in ipairs(f.presets or {}) do e.presets[i] = { raw = p, shown = Ui.markup(p) } end
    return data
  end

  -- The ui.open data of an entry, from fresh facts. Also sets the entry's
  -- baseline of what the client now knows.
  local function openData(e)
    local f = engine.uiOpenFacts(e.key)
    if not f then return nil end
    local data
    local kind = e.kind
    if kind == "text" then
      data = textData(e, f)
      e.sent = { page = 0, line = 0, waiting = false, done = false }
    elseif kind == "choice" then
      local c = engine.uiCursor(e.key) or {}
      data = { labels = texts(f.labels, Ui.MAX_LABELS), index = clampInt((c.index or 1) - 1, 0, 255) }
      if f.question and #f.question > 0 then
        -- the rows the box shows, joined; a blank row (Gen 1's fixed two-row box keeps one above a
        -- continued line since v0.3.85, kb_engine visibleLines) adds nothing to the question
        local q = {}
        for i = 1, #f.question do
          local row = text(f.question[i])
          if type(row) == "string" and row ~= "" then q[#q + 1] = row end
        end
        data.question = text(concat(q, " "))
      end
      e.sent = { index = data.index }
    elseif kind == "menu" or kind == "list" then
      local c = engine.uiCursor(e.key) or {}
      local items = shapeItems(f.items)
      -- a menu's box shows min(maxVisible, rows) rows (Menu.lua:48-50); a
      -- list keeps its window size (ListMenu.lua:159)
      local rows = f.rows or #items
      if kind == "menu" and rows > #items then rows = #items end
      data = {
        items = items, index = clampInt((c.index or 1) - 1, 0, 255), top = clampInt(c.scroll, 0, 255),
        rows = clampInt(rows, 1, Ui.MAX_ROWS),
      }
      if f.title ~= nil and f.title ~= "" then data.title = text(f.title) end
      if f.footer ~= nil and f.footer ~= "" then data.footer = text(f.footer) end
      if f.money ~= nil then data.money = clampInt(f.money, 0, 999999) end
      if f.wrap ~= nil then data.wrap = f.wrap == true end
      e.sent = { index = data.index, top = data.top }
      e.items = items
      -- what the client was told, raw, for the re-open check (M7)
      e.rawFooter, e.rawMoney = f.footer, f.money
    elseif kind == "quantity" then
      data = { qty = clampInt(f.qty, 0, 999), max = clampInt(f.max, 1, 999) }
      if f.unit ~= nil then data.unit = clampInt(f.unit, 0, 999999) end
      e.sent = { qty = data.qty }
    elseif kind == "naming" then
      data = namingData(e, f)
      e.sent = {}
    else
      data = {}
      e.sent = {}
    end
    data.ui, data.kind = e.id, kind
    local screen = e.screen or f.screen
    if type(screen) == "string" and screen:find("^[A-Za-z0-9_]+$") and #screen <= 64 then
      data.screen = screen
    end
    return json.object(data)
  end

  local function announce(e, out, again)
    local data = openData(e)
    if not data then return end
    e.announced = true
    out[#out + 1] = { "ui.open", data, "ui" }
    if again then
      counts.reannounced = counts.reannounced + 1
    else
      counts.opened = counts.opened + 1
      if e.kind == "mirror" then counts.mirrors = counts.mirrors + 1 end
      if log then log.debug("ui.opened", { ui = e.id, kind = e.kind, screen = e.screen }) end
    end
  end

  -- The cursor fields that changed since the client was told. A menu or
  -- list whose footer or money changed (the Mart's clerk lines and money
  -- box, M7) is sent again as ui.open with its id: ui.update carries
  -- neither, and the client replaces an open UI sent again in place.
  local function update(e, readItems, out)
    local c = engine.uiCursor(e.key)
    e.cursor = c
    if not c or not e.announced then return end
    if LISTLIKE[e.kind] and engine.uiMoneyFooter then
      local mf = engine.uiMoneyFooter(e.key)
      if mf and (mf.footer ~= e.rawFooter or mf.money ~= e.rawMoney) then
        local data = openData(e)
        if data then
          out[#out + 1] = { "ui.open", data, "ui" }
          counts.reopened = counts.reopened + 1
          if log then log.debug("ui.reopened", { ui = e.id, kind = e.kind, screen = e.screen }) end
          return
        end
      end
    end
    local now = {}
    if e.kind == "text" then
      now.page, now.line = textPos(e, c.page or 1, c.line or 1)
      now.waiting, now.done = c.waiting == true, c.done == true
    elseif e.kind == "choice" then
      now.index = clampInt((c.index or 1) - 1, 0, 255)
    elseif LISTLIKE[e.kind] then
      now.index = clampInt((c.index or 1) - 1, 0, 255)
      now.top = clampInt(c.scroll, 0, 255)
    elseif e.kind == "quantity" then
      now.qty = clampInt(c.qty, 0, 999)
    end
    local changed
    for k, v in pairs(now) do
      if e.sent[k] ~= v then
        changed = changed or {}
        changed[k] = v
        e.sent[k] = v
      end
    end
    if readItems and LISTLIKE[e.kind] then
      local items = shapeItems(engine.uiItems(e.key))
      if not sameItems(items, e.items) then
        e.items = items
        changed = changed or {}
        changed.items = items
      end
    end
    if changed then
      changed.ui = e.id
      counts.updates = counts.updates + 1
      out[#out + 1] = { "ui.update", changed, "ui" }
    end
  end

  -- One logic step. wake: a screen was pushed or popped since the last
  -- step (every open list's rows are read again). Messages go into out as
  -- { type, data, tag }: closes (top first), opens (bottom first), updates.
  function obs.step(wake, out)
    steps = steps + 1
    local stack = engine.uiStack()
    local n = #stack
    for i = 1, #stack do
      if stack[i].cls == "Manager" then n = i - 1 break end
    end
    local pos = {}
    for i = 1, n do pos[stack[i].key] = i end

    for i = #open, 1, -1 do
      local e = open[i]
      if not pos[e.key] then
        table.remove(open, i)
        byKey[e.key], byId[e.id] = nil, nil
        counts.closed = counts.closed + 1
        e.closed = true
        if e.announced then out[#out + 1] = { "ui.close", { ui = e.id }, "ui" } end
      end
    end

    -- the effective top: a naming screen stays the UI while its preset
    -- Menu sits on it (NamingScreen.lua:113-151)
    top, presetKey = nil, nil
    local topIdx, preset = n, nil
    if n >= 2 and stack[n].cls == "Menu" and stack[n - 1].cls == "NamingScreen" then
      local c = engine.uiCursor(stack[n - 1].key)
      if c and c.choosing then topIdx, preset = n - 1, stack[n].key end
    end
    local entry = stack[topIdx]
    if entry then
      local e = byKey[entry.key]
      if not e then
        local cursor = (entry.cls == "TownMap" or entry.cls == "ListMenu") and engine.uiCursor(entry.key) or nil
        local kind = kindOf(entry, cursor)
        if kind then
          lastId = lastId + 1
          e = { key = entry.key, id = lastId, kind = kind, cls = entry.cls, screen = entry.screen,
                since = steps }
          byKey[e.key], byId[e.id] = e, e
          open[#open + 1] = e
        end
      end
      if e then top, presetKey = e, preset end
    end

    table.sort(open, function(a, b) return pos[a.key] < pos[b.key] end)
    for i = 1, #open do
      if not open[i].announced then announce(open[i], out, open[i].reannounce) end
    end
    local everyNow = wake or steps % Ui.ITEMS_EVERY == 0
    for i = 1, #open do
      local e = open[i]
      update(e, everyNow or e == top, out)
    end
  end

  -- After a snapshot (hello or resync): the open UIs are sent again with
  -- their ids, bottom first, by the step that follows it (the snapshot is
  -- answered from the session's pump, before the observers run).
  function obs.rebase()
    for i = 1, #open do
      open[i].announced = false
      open[i].reannounce = true
    end
  end

  -- The effective top entry (or nil) and, when a naming screen's presets
  -- are up, the key of that preset Menu.
  function obs.top() return top, presetKey end
  function obs.entry(id) return byId[id] end
  function obs.openCount() return #open end

  function obs.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    out.open = #open
    return out
  end

  return obs
end

return Ui
