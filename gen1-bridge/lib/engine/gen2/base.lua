-- gen2 base: what every part of the Gen 2 adapter shares (GEN2_BRIDGE_PLAN.md
-- §3). Engine modules are looked up by name through the sandbox's require (the
-- engine_internals permission; a Gen 2 boot lets a mod require its own
-- generation's modules, src/mods/Loader.lua:129-150) and kept once found -- a
-- module table is a singleton for the process; its contents are not.
--
-- The engine's own lifetimes (research note g2_world_state.md §13): the
-- Game2 instance (`game`) lives for the process; its World (`game.world`) is
-- rebuilt on every CONTINUE and dropped at the title (src/core/Game2.lua:347-348,
-- :374-388); the save (`game.save`) is replaced by NEW GAME and CONTINUE; a map
-- load rebuilds the World's map, NPCs and object masks. So nothing read from
-- the engine is kept across a tick, and the position and flags are read from
-- the live World, never from the save (save.position and save.events are only
-- refreshed when a save is written, Game2.lua:958-1020).
--
-- Map ids (J4): Gen 2 names its maps by the disassembly's constants, and its
-- Kanto's are Gen 1's own names (PALLET_TOWN, ROUTE_1, POKECENTER_2F...), so
-- everything this adapter reports carries the prefix "G2_" and everything it
-- is handed has it taken off (b.ext, b.int); our own maps (KB_*) keep their
-- names.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Base = {}

-- The process-lifetime slots on the Game2 object (gen1.lua's names, so a hot
-- reload of the adapter finds its predecessor's state).
Base.STATE_SLOT = "kantoBridgeState"
Base.CLOSE_SLOT = "kantoBridgeCloseInstance"
Base.WINDOW_SLOT = "kantoBridgeWindow"
-- The prefix on every reported Gen 2 map id (GEN2_BRIDGE_PLAN.md J4).
Base.PREFIX = "G2_"

function Base.new(opts, adapter)
  local b = {
    opts = opts, adapter = adapter, log = opts.log, api = opts.world, input = opts.input, love = opts.love,
    hooks = opts.hooks, checked = opts.checked, tick = 0,
  }
  local requireFn = type(opts.require) == "function" and opts.require or function() return nil end
  local found = {}

  -- An engine module by name, or nil; never throws. A module that isn't
  -- there yet is asked for again next time.
  function b.mod(name)
    local m = found[name]
    if m then return m end
    local ok, r = pcall(requireFn, name)
    if ok and type(r) == "table" then
      found[name] = r
      return r
    end
    return nil
  end
  function b.w2(name) return b.mod("src.world.gen2." .. name) end
  function b.c2(name) return b.mod("src.core.gen2." .. name) end
  function b.ui2(name) return b.mod("src.ui.gen2." .. name) end

  -- Defines one of the adapter's functions (the core's interface name).
  function b.def(name, fn)
    adapter[name] = fn
    local stubbed = rawget(adapter, "_stubbed")
    if type(stubbed) == "table" then stubbed[name] = nil end
  end

  -- A function of a module called safely: (ok, results...) of pcall, or
  -- false when the module or the function isn't there.
  function b.call(m, fname, ...)
    if type(m) ~= "table" or type(m[fname]) ~= "function" then return false end
    return pcall(m[fname], ...)
  end
  -- The first result of a safe call, or the fallback.
  function b.ask(m, fname, fallback, ...)
    local ok, v = b.call(m, fname, ...)
    if ok then return v end
    return fallback
  end
  -- A method of an object called safely: (ok, results...) or false.
  function b.method(obj, fname, ...)
    if type(obj) ~= "table" or type(obj[fname]) ~= "function" then return false end
    return pcall(obj[fname], obj, ...)
  end

  function b.warnAt(code, err)
    if b.log then
      b.log.warn(code, { at = type(err) == "string" and err:match("[%w_/%.%-]+%.lua:%d+") or "unknown" })
    end
  end

  -- An upper-case protocol symbol (defs.schema.json's symbol pattern).
  function b.symbol(v) return type(v) == "string" and #v >= 1 and #v <= 64 and v:find("^[A-Z0-9_]+$") ~= nil end

  -- ---- map ids (J4)

  -- The engine's id as reported: "G2_" + id; our own maps (KB_*) as they are.
  function b.ext(id)
    if type(id) ~= "string" then return nil end
    if id:find("^KB_") then return b.symbol(id) and id or nil end
    local out = Base.PREFIX .. id
    return b.symbol(out) and out or nil
  end
  -- A reported id as the engine knows it: the prefix taken off; an id without
  -- it (our own KB_* maps) as it is.
  function b.int(id)
    if type(id) ~= "string" then return nil end
    if id:sub(1, #Base.PREFIX) == Base.PREFIX then return id:sub(#Base.PREFIX + 1) end
    return id
  end

  -- ---- the game, the World, the save

  -- The World (src/world/gen2/World.lua, Game2's `world`, Game2.lua:267), or nil
  -- at the title and during the boot cinema.
  function b.ow()
    local g = b.game
    local w = type(g) == "table" and g.world or nil
    return type(w) == "table" and w or nil
  end

  -- The object the save events carry (save.created, save.loaded: game.save,
  -- Game2.lua:306, :353-357): the live save.
  function b.saveObject()
    local g = b.game
    return type(g) == "table" and type(g.save) == "table" and g.save or nil
  end

  -- In the field: the play phase (Game2.lua:180, :262-275) with a World that
  -- has a map and a player.
  function b.inField()
    local g = b.game
    if type(g) ~= "table" or g.phase ~= "play" then return false end
    local w = b.ow()
    return w ~= nil and type(w.map) == "table" and type(w.player) == "table"
  end

  -- The running game is Crystal (World:isCrystal, World.lua:1837-1843; the
  -- engine id answers outside the field).
  function b.isCrystal()
    local GameVersion = b.mod("src.core.GameVersion")
    return b.ask(GameVersion, "engine", nil) == "crystal"
  end

  -- ---- story state (numbered event flags, src/world/gen2/Events.lua)

  -- One event flag by number: the live store in the field (World.events,
  -- Events:get, Events.lua:19-25), else the save's copy (save.events, the same
  -- byte -> bits table, written when the game saves).
  function b.flag(id)
    id = tonumber(id)
    if not id or id < 0 then return false end
    local w = b.ow()
    local ev = w and w.events or nil
    if type(ev) == "table" and type(ev.get) == "function" then
      local ok, v = pcall(ev.get, ev, id)
      if ok then return v == true end
    end
    local s = b.saveObject()
    local flags = type(s) == "table" and type(s.events) == "table" and s.events or nil
    if type(flags) == "table" and type(flags.flags) == "table" then flags = flags.flags end
    local row = type(flags) == "table" and tonumber(flags[math.floor(id / 8)]) or nil
    if not row then return false end
    return math.floor(row / (2 ^ (id % 8))) % 2 == 1
  end

  -- Every set event flag's number (World.events.flags: byte -> bits).
  function b.setFlags()
    local out = {}
    local w = b.ow()
    local ev = w and w.events or nil
    local bytes = type(ev) == "table" and type(ev.flags) == "table" and ev.flags or nil
    if not bytes then
      local s = b.saveObject()
      local saved = type(s) == "table" and s.events or nil
      bytes = type(saved) == "table" and (type(saved.flags) == "table" and saved.flags or saved) or nil
    end
    for k, v in pairs(type(bytes) == "table" and bytes or {}) do
      local byte, row = tonumber(k), tonumber(v)
      if byte and row and row > 0 then
        for bit = 0, 7 do
          if math.floor(row / (2 ^ bit)) % 2 == 1 then out[#out + 1] = byte * 8 + bit end
        end
      end
    end
    table.sort(out)
    return out
  end

  -- The process-lifetime state on the Game2 object (gen1.lua's pattern): the
  -- token and serial behind save ids, the queued save, the center save, the
  -- park hold, our persistent tables. nil before game.ready.
  function b.state()
    local g = b.game
    if type(g) ~= "table" then return nil end
    local st = rawget(g, Base.STATE_SLOT)
    if type(st) ~= "table" then
      st = { serial = 0, gifts = {}, heals = 0, giving = {} }
      rawset(g, Base.STATE_SLOT, st)
    end
    return st
  end

  return b
end

return Base
