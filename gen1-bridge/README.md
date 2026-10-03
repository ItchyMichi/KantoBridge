# gen1-bridge

The Gen1Recomp half of Kanto Bridge: a Gen1Recomp mod (Lua, mod API 2) that runs inside the
player's hidden Gen1Recomp. It will listen on `127.0.0.1:27151` for `fabric-bridge`
([`docs/PROTOCOL.md`](../docs/PROTOCOL.md#transport)).

**Status: skeleton.** It only logs `gen1-bridge loaded` when a game boots.

## Files

| File | Purpose |
|---|---|
| `manifest.json` | Read by Gen1Recomp's loader. Declares two permissions: `network` unlocks `require("socket")` (LÖVE's bundled LuaSocket) inside the mod sandbox, and `engine_internals` discloses that the mod requires and patches engine modules (`src.*`). Gen1Recomp only displays permissions; it never prompts. |
| `main.lua` | Entry point. The loader calls the returned function with the `mod` API object; `mod.log:info` writes to stdout. |
| `mod.card` | Sharing metadata for the mod manager and gallery. The loader never reads it. |

The format is documented in [`docs/REFERENCES.md`](../docs/REFERENCES.md#gen1recomp). `game_version`
covers source checkouts (`0.0.0-dev`) and the 0.3.x line from the pinned v0.3.49.

## Trying it

The pinned Gen1Recomp release lives in `local/gen1recomp/` (see [`CLAUDE.md`](../CLAUDE.md)).
`portable.txt` sits beside the exe, so all its data stays in that folder. This mod folder is linked
into it as `local\gen1recomp\mods\gen1-bridge` (a junction), so edits here take effect on the next
boot without copying.

1. Start `local\gen1recomp\gen1recomp.exe`. On the very first run, import your ROM from the launcher.
2. Boot a game. Later runs can boot directly with `gen1recomp.exe --game=red`. Mod entry points run
   only once a game boots, not in the launcher.
3. To see the `gen1-bridge loaded` line, add `--console`. That is LÖVE's standard Windows option
   for a log console; it hasn't yet been tried on this build. `POKEPORT_DEV=1` enables the in-game
   developer console (backtick) with a `mods` command and an event tracer.

Keep Gen1Recomp's window open behind Minecraft and never minimise it. A minimised window drops to
about a third of normal speed (from reading the code, not measured).

To remove the link, use `rmdir local\gen1recomp\mods\gen1-bridge` in `cmd`. Never use `rm -rf`
through it, which can delete this folder's contents.
