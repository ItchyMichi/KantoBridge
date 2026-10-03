# gen1-bridge

The Gen1Recomp half of Kanto Bridge. It's a Gen1Recomp mod (Lua, mod API 2) that runs inside the
player's hidden Gen1Recomp and will talk to `fabric-bridge` over a local TCP socket
([`docs/PROTOCOL.md`](../docs/PROTOCOL.md)).

**Status: skeleton.** It only logs `gen1-bridge loaded` when a game boots.

## Files

| File | Purpose |
|---|---|
| `manifest.json` | Read by Gen1Recomp's loader. Declares the `network` permission, which unlocks `require("socket")` (LÖVE's bundled LuaSocket) inside the mod sandbox. Gen1Recomp only shows permissions as a disclosure; it never prompts the player. |
| `main.lua` | Entry point. The loader calls the returned function with the `mod` API object; `mod.log:info` writes to stdout. |
| `mod.card` | Sharing metadata for the mod manager and gallery. The loader never reads it. |

The format is documented in [`docs/REFERENCES.md`](../docs/REFERENCES.md#gen1recomp).
`game_version` covers source checkouts (`0.0.0-dev`) and the 0.3.x release line, from v0.3.49, the
version studied.

## Trying it

Only do this with your own Gen1Recomp in `local/` (see the root [README](../README.md)), never from
`refs/`. Link this folder into the portable Gen1Recomp's `mods/` folder rather than copying it
(run in `cmd`):

```bat
mklink /J "local\gen1recomp\mods\gen1-bridge" "gen1-bridge"
```

Start Gen1Recomp with `lovec.exe` from a terminal (`love.exe` hides the console) and boot a game,
e.g. `--game=red`. Mod entry points run only once a game boots, not in the launcher. The line
`gen1-bridge loaded` then appears on stdout, prefixed with the mod id.
