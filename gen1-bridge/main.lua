-- gen1-bridge: the Gen1Recomp half of Kanto Bridge (docs/PLAN.md section 4.1).
--
-- The entry chunk only loads modules and subscribes to events. It must never
-- open a socket: a throwing entry chunk is rolled back, but raw sockets are
-- not, so the listener starts lazily from input.step (wired in M1).
--
-- Modules are plain Lua files loaded with mod:read + load, which compiles them
-- into this mod's sandbox. Never require our own files: require would run
-- them with the engine's globals and cache them across hot reloads.
return function(mod)
  local loaded = 0

  local function import(rel)
    local source, err = mod:read(rel)
    if type(source) ~= "string" then
      error(("gen1-bridge: cannot read %s (%s)"):format(rel, tostring(err)), 0)
    end
    local chunk, compileErr = load(source, "@" .. rel)
    if not chunk then error(compileErr, 0) end
    loaded = loaded + 1
    return chunk()
  end

  local kb = {
    json = import("lib/kb_json.lua"),
    sha256 = import("lib/kb_sha256.lua"),
    Log = import("lib/kb_log.lua"),
    Net = import("lib/kb_net.lua"),
    Proto = import("lib/kb_proto.lua"),
    Engine = import("lib/kb_engine.lua"),
  }

  local log = kb.Log.new({
    write = kb.Log.modWriter(mod.log),
    hash = kb.sha256.short,
    debug = mod.developer == true,
  })
  local engine = kb.Engine.new({ require = require, log = log })

  -- Read by our test drivers as game.mods.exports["gen1-bridge"]. Versions,
  -- counts and flags only.
  local status = mod.exports
  status.bridge = mod.version
  status.protocol = kb.Proto.PROTOCOL
  status.modules = loaded
  status.ready = false

  mod.events:on("game.ready", function(payload)
    -- A hot reload leaves the previous instance's sockets to the garbage
    -- collector; collect once so they are closed before this instance binds.
    collectgarbage("collect")
    engine.setGame(payload and payload.game)
    local version, api = engine.version()
    engine.checkPin()
    status.engine = version
    status.ready = true
    log.info("game.ready", { engine = version, api = api, modules = loaded })
  end)

  mod.log:info("gen1-bridge loaded")
end
