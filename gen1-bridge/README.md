# gen1-bridge

The Gen1Recomp half of Kanto Bridge: a Gen1Recomp mod (Lua, mod API 2) that runs inside the
player's hidden Gen1Recomp. It will listen on `127.0.0.1:27151` for `fabric-bridge`
([`docs/PROTOCOL.md`](../docs/PROTOCOL.md#transport)).

**Status: M0 foundations.** The modules below are built and unit-tested, and the mod loads in the
real engine and reports ready. It opens no socket yet: the listener is wired in M1.

## Files

| File | Purpose |
|---|---|
| `manifest.json` | Read by Gen1Recomp's loader. `network` unlocks `require("socket")` (LÖVE's bundled LuaSocket) inside the mod sandbox; `engine_internals` discloses that the mod requires engine modules (`src.*`). Gen1Recomp only displays permissions; it never prompts. |
| `main.lua` | Entry point. Loads the modules, subscribes to `game.ready`, and publishes a small status table in `mod.exports` (versions, counts, a ready flag) for test drivers. |
| `lib/kb_json.lua` | Our own strict, deterministic JSON codec: sorted keys, `{}`/`[]`/null kept apart, strict parsing, errors that never quote the input. |
| `lib/kb_sha256.lua` | SHA-256, for the short hashes logs may show and for export hashes. |
| `lib/kb_log.lua` | Redacted logging: a code plus fields; any string that isn't id-shaped prints as `<str:LEN:SHA8>`. |
| `lib/kb_net.lua` | The non-blocking TCP listener: one client at a time, newline framing up to 4 MiB, a send queue that keeps unsent tails. |
| `lib/kb_proto.lua` | The message envelope (`type`, `seq`, `re`, `data`), dispatch, replies and `error` answers. |
| `lib/kb_engine.lua` | **The only file that touches engine internals.** Each function cites the engine `file:line` it relies on (v0.3.49). Re-check them all when the Gen1Recomp pin changes. |
| `mod.card` | Sharing metadata for the mod manager and gallery. The loader never reads it. |

Rules for this folder:

- Modules are loaded with `load(mod:read(rel), "@" .. rel)()`, which compiles them into the mod's
  sandbox. Never `require` our own files.
- File names start with `kb_`, and no file may mention the engine's generated-data folders by path
  (modkit's MK301 and MK305 checks).
- Nothing may log game text. Log codes and fields through `kb_log`.
- Tests live outside this folder, in [`tools/lua/`](../tools/lua) (lupa, LuaJIT 2.1, with a copy of
  the engine's mod sandbox and a fake LuaSocket).

## Trying it

The pinned Gen1Recomp release lives in `local/gen1recomp/` (see [`CLAUDE.md`](../CLAUDE.md)).
`portable.txt` sits beside the exe, so all its data stays in that folder. This mod folder is linked
into it as `local\gen1recomp\mods\gen1-bridge` (a junction), so edits here take effect on the next
boot without copying.

1. Start `local\gen1recomp\gen1recomp.exe`. On the very first run, import your ROM from the launcher.
2. Boot a game. Later runs can boot directly with `--game=blue` (or `--game=red`). Mod entry
   points run only once a game boots, not in the launcher.
3. To see the log, add `--console`. In PowerShell, from inside the install folder:
   `.\gen1recomp.exe --console --game=blue`. Look for `[gen1-bridge] gen1-bridge loaded`, then
   `[gen1-bridge] game.ready ...`. `POKEPORT_DEV=1` enables the in-game developer console (backtick).

Automated runs use the separate test install `local/gen1recomp-e2e/` through
`tools/e2e/run_gen1recomp.py`, never the user's install.

Keep Gen1Recomp's window open behind Minecraft and never minimise it. A minimised window drops to
about a third of normal speed (from reading the code, not measured).

To remove the link, use `rmdir local\gen1recomp\mods\gen1-bridge` in `cmd`. Never use `rm -rf`
through it, which can delete this folder's contents.
