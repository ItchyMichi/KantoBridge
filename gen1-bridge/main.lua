-- gen1-bridge: the Gen1Recomp half of Kanto Bridge.
-- Skeleton only. The "network" permission in manifest.json unlocks require("socket")
-- for the bridge connection (docs/PROTOCOL.md); nothing uses it yet.
return function(mod)
  mod.log:info("gen1-bridge loaded")
end
