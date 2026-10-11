-- Fake mod.world for the Wild Routes tests (WR2): Gen1Recomp's
-- src/world/WorldAPI.lua as gen1-bridge uses it. queueScript runs the rows
-- on the fake engine's script runner when it is free and refuses while a
-- script runs (WorldAPI.lua:626-632); warpTo starts the fake world's warp
-- (:358-370). Written for Kanto Bridge.
--
--   FakeWorldApi.new(E)   api.queued: every row list that ran; api.refused: refusals

local FakeWorldApi = {}

function FakeWorldApi.new(E)
  local api = { queued = {}, refused = 0, warps = {} }
  function api.queueScript(_, rows, extra)
    local ow = E.ow
    if not (ow and ow.runner) then return nil, "no overworld" end
    if ow.runner:isRunning() then
      api.refused = api.refused + 1
      return nil, "a script is already running"
    end
    api.queued[#api.queued + 1] = rows
    ow.runner:run(rows, extra)
    return true
  end
  function api.warpTo(_, mapId, x, y, facing)
    api.warps[#api.warps + 1] = { mapId, x, y, facing }
    E.startWarpTo(mapId, x, y, facing or "down")
    return true
  end
  return api
end

return FakeWorldApi
