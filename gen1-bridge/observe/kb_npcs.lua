-- kb_npcs: the NPC observer (docs/PLAN.md section 4.1 "NPCs",
-- docs/PROTOCOL.md "NPCs"; refs/_research/P1_gen1_movement.md section 4).
--
--   npc.set{map, list}        the full list: on map entry (every new ow.npcs
--                             table: warps, seam crossings, same-map warps)
--                             and after every snapshot
--   npc.add{map, ...npc}      one appeared (show_object, a toggle)
--   npc.remove{id}            one went (an item taken, hide_object, a beaten
--                             static, a boulder down a hole)
--   npc.move{id, x, y, facing, frames, kind}
--                             a step starts: the target cell and its length;
--                             kind walk (its own wandering), script (a
--                             cutscene move), push (a Strength boulder), march
--                             (a walk-in-place beat, at its own cell); a jump
--                             with no step (a script placing it, a reset to
--                             spawn) is kind script with frames 0
--   npc.face{id, facing}      it turned without stepping
--   emote{target, bubble, frames}
--                             the global emotion-bubble hold names the player
--                             or an NPC ("!", "?", "happy", null = silent)
--
-- Works on kb_engine.npcs() / kb_engine.emote() plain facts, diffed by id
-- each step; nothing relies on the engine's NPC events (they miss most
-- changes, P1 4.4). Npcs.shape is the one place an NPC becomes a protocol
-- entry, so the snapshot's list (kb_snapshot) is the same as npc.set's. An
-- NPC caught mid-step is listed at the cell it is stepping to.

local Npcs = {}

Npcs.MAX_LIST = 64

local floor = math.floor
local DIRS = { up = true, down = true, left = true, right = true }
local KINDS = { person = true, item = true, boulder = true, static = true, trainer = true }
local BUBBLES = { [1] = "!", [2] = "?", [3] = "happy" }

local function coord(v) return type(v) == "number" and v == floor(v) and v >= -4096 and v <= 4096 end
local function sym(v, pattern, max)
  return type(v) == "string" and #v > 0 and #v <= (max or 64) and v:find(pattern) ~= nil
end

local function frames(v)
  v = tonumber(v) or 0
  v = floor(v + 0.5)
  if v < 0 then return 0 end
  if v > 3600 then return 3600 end
  return v
end

-- One kb_engine.npcs() entry as a protocol npc (defs.schema.json npc), or
-- nil when it can't be sent (a malformed id, sprite or cell).
function Npcs.shape(n)
  if type(n) ~= "table" or not sym(n.id, "^[A-Za-z0-9_]+$", 96) or not sym(n.sprite, "^[A-Z0-9_]+$")
      or not DIRS[n.facing] then
    return nil
  end
  local x, y = n.x, n.y
  if n.moving and not n.marching and coord(n.tx) and coord(n.ty) then x, y = n.tx, n.ty end
  if not (coord(x) and coord(y)) then return nil end
  return {
    id = n.id, sprite = n.sprite, kind = KINDS[n.kind] and n.kind or "person",
    x = x, y = y, facing = n.facing, moving = n.moving == true,
  }
end

-- The shaped list (at most 64) and how many were left out.
function Npcs.list(raw)
  local out, dropped = {}, 0
  for _, n in ipairs(raw or {}) do
    local s = #out < Npcs.MAX_LIST and Npcs.shape(n) or nil
    if s then out[#out + 1] = s else dropped = dropped + 1 end
  end
  return out, dropped
end

-- opts.json: kb_json; opts.log: kb_log logger (optional)
function Npcs.new(opts)
  local json, log = opts.json, opts.log
  local obs = {}
  local known = {}           -- id -> the last facts sent about it
  local lastMap, lastGen     -- the map and npc-table generation of the last npc.set
  local resend = true        -- send npc.set on the next step
  local lastEmote
  local counts = { sets = 0, adds = 0, removes = 0, moves = 0, faces = 0, emotes = 0, dropped = 0 }

  local function remember(n, shaped)
    known[n.id] = {
      x = n.x, y = n.y, tx = n.tx, ty = n.ty, facing = n.facing, moving = n.moving == true,
      marching = n.marching == true, progress = n.progress or 0, sprite = shaped.sprite, kind = shaped.kind,
    }
  end

  local function add(map, shaped, out)
    local data = { map = map }
    for k, v in pairs(shaped) do data[k] = v end
    out[#out + 1] = { "npc.add", data, "npc" }
    counts.adds = counts.adds + 1
  end

  local function move(n, x, y, kind, len, out)
    if not (coord(x) and coord(y)) then return end
    out[#out + 1] = { "npc.move", { id = n.id, x = x, y = y, facing = n.facing, frames = frames(len), kind = kind }, "npc" }
    counts.moves = counts.moves + 1
  end

  -- raw, map, gen: kb_engine.npcs(); emote: kb_engine.emote(). Messages go
  -- into out as { type, data, tag }. Without a live client (live false)
  -- nothing is diffed: the next live step starts with a full npc.set.
  function obs.step(raw, map, gen, emote, out, live)
    if live == false then
      resend = true
      return
    end
    if raw == nil or type(map) ~= "string" or not map:find("^[A-Z0-9_]+$") then
      -- no overworld (title screen, a battle's teardown): start over later
      known, lastMap, lastGen = {}, nil, nil
      return
    end
    if resend or map ~= lastMap or gen ~= lastGen then
      local list, dropped = {}, 0
      known = {}
      for _, n in ipairs(raw) do
        local shaped = #list < Npcs.MAX_LIST and Npcs.shape(n) or nil
        if shaped then
          list[#list + 1] = shaped
          remember(n, shaped)
        else
          dropped = dropped + 1
        end
      end
      if dropped > 0 then
        counts.dropped = counts.dropped + dropped
        if log then log.once("npcs.dropped:" .. map, "warn", "npcs.dropped", { map = map, count = dropped }) end
      end
      out[#out + 1] = { "npc.set", { map = map, list = json.array(list) }, "npc" }
      counts.sets = counts.sets + 1
      lastMap, lastGen, resend = map, gen, false
    else
      local seen = {}
      for _, n in ipairs(raw) do
        local shaped = Npcs.shape(n)
        if shaped then
          seen[n.id] = true
          local k = known[n.id]
          if not k then
            add(map, shaped, out)
            remember(n, shaped)
          elseif k.sprite ~= shaped.sprite or k.kind ~= shaped.kind then
            -- a new appearance (NPC:setAppearance, NPC.lua:68-79): rebuilt
            out[#out + 1] = { "npc.remove", { id = n.id }, "npc" }
            counts.removes = counts.removes + 1
            add(map, shaped, out)
            remember(n, shaped)
          else
            local moving, marching = n.moving == true, n.marching == true
            if moving and marching then
              -- a walk-in-place beat (NPC.lua:116-124): a new cycle restarts its progress
              if not (k.moving and k.marching) or (n.progress or 0) < (k.progress or 0) then
                move(n, n.x, n.y, "march", n.frames, out)
              elseif n.facing ~= k.facing then
                out[#out + 1] = { "npc.face", { id = n.id, facing = n.facing }, "npc" }
                counts.faces = counts.faces + 1
              end
            elseif moving and (not k.moving or k.marching or n.tx ~= k.tx or n.ty ~= k.ty) then
              move(n, n.tx, n.ty, n.via == "push" and "push" or (n.via == "script" and "script") or "walk",
                n.frames, out)
            elseif not moving and (n.x ~= k.x or n.y ~= k.y)
                and not (k.moving and not k.marching and n.x == k.tx and n.y == k.ty) then
              move(n, n.x, n.y, "script", 0, out) -- placed, not stepped
            elseif n.facing ~= k.facing then
              out[#out + 1] = { "npc.face", { id = n.id, facing = n.facing }, "npc" }
              counts.faces = counts.faces + 1
            end
            remember(n, shaped)
          end
        end
      end
      for id in pairs(known) do
        if not seen[id] then
          known[id] = nil
          out[#out + 1] = { "npc.remove", { id = id }, "npc" }
          counts.removes = counts.removes + 1
        end
      end
    end
    if emote and emote.serial ~= lastEmote then
      lastEmote = emote.serial
      if emote.target then
        local bubble = emote.bubble
        if bubble == nil then
          bubble = "!" -- nil draws the first crop, "!" (OC:6073)
        elseif bubble == false then
          bubble = json.null
        else
          bubble = BUBBLES[bubble] or json.null
        end
        out[#out + 1] = { "emote", { target = emote.target, bubble = bubble, frames = frames(emote.frames) }, "npc" }
        counts.emotes = counts.emotes + 1
      end
    end
  end

  -- After a snapshot: the next step sends npc.set again.
  function obs.rebase() resend = true end

  function obs.stats()
    local out = {}
    for k, v in pairs(counts) do out[k] = v end
    local n = 0
    for _ in pairs(known) do n = n + 1 end
    out.known = n
    return out
  end

  return obs
end

return Npcs
