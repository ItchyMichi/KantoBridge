-- game3 base: what every part of the Gen 3 adapter shares (GEN3_BRIDGE_PLAN.md
-- §2). Engine modules are looked up by name through the sandbox's require
-- (the engine_internals permission) and kept once found -- a module table is
-- a singleton for the process; its contents are not, so nothing read from
-- one is ever kept across a tick. The engine's own lifetimes (world note
-- §10.12): the flag store, the object tables and the metatile overrides are
-- rebuilt on every map load, and Field's lock tags are wiped by Field.start
-- and Field.stop (refs/gen1recomp src/core/game3/field.lua:109, :155).
--
-- Paths below are refs/gen1recomp's (Gen1Recomp v0.3.85, the pin).

local Base = {}

-- The process-lifetime slots on the Game3 object (gen1.lua's names, so a
-- hot reload of either adapter finds its predecessor's state).
Base.STATE_SLOT = "kantoBridgeState"
Base.CLOSE_SLOT = "kantoBridgeCloseInstance"
Base.WINDOW_SLOT = "kantoBridgeWindow"

function Base.new(opts, adapter)
  local b = {
    opts = opts, adapter = adapter, log = opts.log, world = opts.world, input = opts.input, love = opts.love,
    checked = opts.checked, tick = 0,
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
  function b.g3(name) return b.mod("src.core.game3." .. name) end
  function b.ui3(name) return b.mod("src.ui.game3." .. name) end

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

  function b.warnAt(code, err)
    if b.log then
      b.log.warn(code, { at = type(err) == "string" and err:match("[%w_/%.%-]+%.lua:%d+") or "unknown" })
    end
  end

  -- An upper-case protocol symbol (defs.schema.json's symbol pattern).
  function b.symbol(v) return type(v) == "string" and #v >= 1 and #v <= 64 and v:find("^[A-Z0-9_]+$") ~= nil end

  -- The live session: the runtime's (src/core/game3/runtime.lua:36,
  -- getSession), else the Game3 object's (the boot phase keeps none).
  function b.session()
    local s = b.ask(b.g3("runtime"), "getSession", nil)
    if type(s) == "table" then return s end
    local g = b.game
    return type(g) == "table" and type(g.session) == "table" and g.session or nil
  end

  -- In the field: the Game3 object's phase is "field" (Game3.lua:101; boot,
  -- quest_log and arena otherwise), Field runs (field.lua:67, :108) and a
  -- session exists.
  function b.inField()
    local g = b.game
    if type(g) ~= "table" or g.phase ~= "field" then return false end
    local F = b.g3("field")
    return type(F) == "table" and F.running == true and b.session() ~= nil
  end

  -- The Emerald flag tables for the running game (scripting/flags.lua:192-217,
  -- Flags.active): IDS (name -> id), NAMES (id -> name), BADGES. The engine's
  -- own string lookups go through FireRed's tables (flags.lua:17-48), so the
  -- adapter always resolves names here and hands the engine numbers.
  function b.flagTables()
    local Flags = b.g3("scripting.flags")
    local ok, t = b.call(Flags, "active", b.session())
    if ok and type(t) == "table" then return t end
    return nil
  end

  -- The live flag store (scripting/space.lua:23, Space.store = { flags, vars }),
  -- rebuilt on every map load; outside the field the session's copy (stale
  -- between saves, space.lua:111-113).
  function b.flagStore()
    local Space = b.g3("scripting.space")
    local store = type(Space) == "table" and Space.store or nil
    if type(store) == "table" and type(store.flags) == "table" then return store end
    local s = b.session()
    if type(s) == "table" and type(s.flags) == "table" then return { flags = s.flags, vars = s.vars or {} } end
    return nil
  end

  -- One flag by Emerald name or number: true or false.
  function b.flag(nameOrId)
    local id = tonumber(nameOrId)
    if not id then
      local t = b.flagTables()
      id = t and t.IDS and t.IDS[nameOrId] or nil
    end
    local store = b.flagStore()
    if not (id and store) then return false end
    local Flags = b.g3("scripting.flags")
    local ok, v = b.call(Flags, "getFlag", store, nil, id)
    if ok then return v == true end
    return store.flags[id] == true or store.flags[tostring(id)] == true
  end

  -- The game constants (src/core/game3/constants.lua:108, Constants.of):
  -- C:name("items", 13) -> "ITEM_POTION".
  function b.constants()
    local Constants = b.g3("constants")
    local GameVersion = b.mod("src.core.GameVersion")
    local v = GameVersion and b.ask(GameVersion, "get", nil) or nil
    local ok, C = b.call(Constants, "of", v)
    if ok and type(C) == "table" then return C end
    return nil
  end

  -- The process-lifetime state on the Game3 object (gen1.lua's pattern):
  -- the token and serial behind save ids, the queued save, the center save,
  -- the park hold, our persistent tables. nil before game.ready.
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
