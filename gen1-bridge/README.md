# gen1-bridge

The Gen1Recomp half of Kanto Bridge: a Gen1Recomp mod (Lua, mod API 2) that runs inside the
player's hidden Gen1Recomp. It listens on `127.0.0.1:27151` for `fabric-bridge`
([`docs/PROTOCOL.md`](../docs/PROTOCOL.md#transport)).

**Status: M1, bridge connection.** The listener, the handshake, the heartbeat, `snapshot` and the
live `player.pos`/`player.state` stream work against the real engine (end-to-end tests in
[`tools/e2e/`](../tools/e2e)). Steps, UI, NPCs and the story arrive in later milestones.

## What it does each logic step

Everything runs from the `input.step` hook (about 60 times a second; `core.update` doesn't fire under
test drivers), guarded so the hook always calls `next`:

1. **Network:** the listener binds lazily on the first step (no `SO_REUSEADDR`; a busy port is
   retried every second and logged once), takes one client at a time (a newer one replaces it), and
   moves bytes without ever blocking.
2. **Session:** the first message must be `hello` (protocol 1; roles `client`, `builder`, `test`),
   answered by `hello.ok` and a `snapshot`, or `hello.reject` and a close. `ping` gets `pong`;
   `resync` gets a `snapshot`; 10 s of silence drops the client. `debug.status` and `debug.quit` are
   for the `test` role only. Unknown types get `error unknown_type`, types that never travel this way
   `error wrong_leg`; protocol types handled in later milestones are accepted quietly.
3. **Player:** the classifier turns the engine's state into a mode (`screen`, `busy`, `forced`,
   `cutscene`, `idle`), and changes go out as `player.state`. Each step start goes out as
   `player.pos` with the **target** cell and its length in frames; turns, warps and teleports go out
   as `player.pos` kind `sync`.
4. **Options pin (PLAN P21):** the hidden game's display options stay classic (no tilt, zoom, render
   pipelines, wide or fill battles, or dynamic UI) and text speed stays FAST. They are pinned in
   memory at boot, on New Game and on CONTINUE, and re-checked every second. Nothing is written to
   disk; Gen1Recomp's own OPTIONS screen persists them if the player saves options there.

Warnings and errors from `kb_log` are also sent to the client as `log` messages (at most 5 a
second). Logs only ever show codes, engine symbols, counts, lengths and short hashes.

**The port** is the mod option `port` (default 27151, in Gen1Recomp's F10 mod manager). Test drivers
move it with `game.mods.exports["gen1-bridge"].configure({ port = N })`.

**Hot reload (F5) and returning to the launcher:** each instance leaves a "close my sockets" function
on the Game object; the next instance calls it on `game.ready`, before it binds, so the port and the
client are released at once.

## Files

| File | Purpose |
|---|---|
| `manifest.json` | Read by Gen1Recomp's loader. `network` unlocks `require("socket")` (LÖVE's bundled LuaSocket) inside the mod sandbox; `engine_internals` discloses that the mod requires engine modules (`src.*`). Gen1Recomp only displays permissions; it never prompts. |
| `main.lua` | Entry point. Loads the modules, defines the `port` option, wires the `input.step` hook and the `game.ready`, `save.created` and `save.loaded` events, and publishes a status table in `mod.exports` (versions, counts, flags) for test drivers. |
| `lib/kb_json.lua` | Our own strict, deterministic JSON codec: sorted keys, `{}`/`[]`/null kept apart, strict parsing, errors that never quote the input. |
| `lib/kb_sha256.lua` | SHA-256, for the short hashes logs may show and for export hashes. |
| `lib/kb_log.lua` | Redacted logging: a code plus fields; any string that isn't id-shaped prints as `<str:LEN:SHA8>`. |
| `lib/kb_net.lua` | The non-blocking TCP listener: one client at a time, newline framing up to 4 MiB, a send queue that keeps unsent tails, `finish` (send what is queued, then close), port changes. |
| `lib/kb_proto.lua` | The message envelope (`type`, `seq`, `re`, `data`), dispatch, replies and `error` answers, with a gate and catalog-aware handling of unhandled types. |
| `lib/kb_session.lua` | One connection's session: hello first, handshake, heartbeat timeout, resync, debug messages, live streams, log forwarding. |
| `lib/kb_catalog.lua` | **Generated** from `protocol/catalog.json` by `tools/lua/gen_catalog.py`: the message types and their legs. A test fails when it is stale. |
| `lib/kb_engine.lua` | **The only file that touches engine internals.** Each function cites the engine `file:line` it relies on (v0.3.49): the overworld and its player, the top state's class, NPCs, dark maps, badges, the options pin and the instance takeover slot. Re-check them all when the Gen1Recomp pin changes. |
| `observe/kb_player.lua` | The mode classifier and the `player.state`/`player.pos` observer, working on plain facts from `kb_engine`. |
| `observe/kb_snapshot.lua` | Builds `snapshot` from the facts and the save: position, state, flags, toggles, bag, money, coins, badges, the hidden game's party, the Pokédex, the current map's NPCs. |
| `mod.card` | Sharing metadata for the mod manager and gallery. The loader never reads it. |

Rules for this folder:

- Modules are loaded with `load(mod:read(rel), "@" .. rel)()`, which compiles them into the mod's
  sandbox. Never `require` our own files.
- File names start with `kb_`, and no file may mention the engine's generated-data folders by path
  (modkit's MK301 and MK305 checks).
- Only `lib/kb_engine.lua` reaches into the engine; everything else gets plain values from it.
- Nothing may log game text. Log codes and fields through `kb_log`.
- Tests live outside this folder, in [`tools/lua/`](../tools/lua) (lupa, LuaJIT 2.1, with a copy of
  the engine's mod sandbox, a fake engine and a fake LuaSocket).

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
   `[gen1-bridge] game.ready ...`, and `net.listening ... port=27151` once the title screen runs.
   `POKEPORT_DEV=1` enables the in-game developer console (backtick).
4. Connect a stand-in client: from `tools/`, `.venv\Scripts\python.exe -m fake_minecraft --seconds 10`
   prints what gen1-bridge sends (types and sizes; `--verbose` shows them with game text redacted).

Automated runs use the separate test install `local/gen1recomp-e2e/` through
`tools/e2e/run_gen1recomp.py`, never the user's install.

Keep Gen1Recomp's window open behind Minecraft and never minimise it. A minimised window drops to
about a third of normal speed (from reading the code, not measured).

To remove the link, use `rmdir local\gen1recomp\mods\gen1-bridge` in `cmd`. Never use `rm -rf`
through it, which can delete this folder's contents.
