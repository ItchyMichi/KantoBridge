# tools/e2e

End-to-end runs on the **test** Gen1Recomp install, `local/gen1recomp-e2e/`, never the user's own
`local/gen1recomp/`. They need the pre-flight in docs/PLAN.md §0.6 (the user's ROM import) and run
only with `--e2e`; without the pre-flight they skip.

- `run_gen1recomp.py` launches the test install in driver mode (`POKEPORT_DRIVER`) with stdout piped,
  and **prints only safe lines**: our drivers' `KB_` markers, gen1-bridge's own `[gen1-bridge]` log
  lines, the engine's `loaded mod gen1-bridge` line, and only the `file.lua:NN` locations of Lua
  errors and of other engine lines that mention the mod (`KB_ENGINE_NOTE`). Everything else is
  counted and hashed. It checks the install by file names only and never opens anything under
  `local/`. `--report` writes a counts-only summary to `local/reports/e2e_<driver>.json`.
  - `run(driver, ...)` waits for the game to exit; `launch(driver, ...)` returns at once, so a test
    can wait for a marker (`wait_marker`), talk to gen1-bridge over TCP, then `wait()` for the exit.
    Leaving `with launch(...)` kills the game if it is still running.
- `drivers/*.lua` are our own driver scripts (unsandboxed, run inside the engine's `main.lua`). A
  driver prints only `KB_<NAME> key=value ...` markers with ids, versions and counts, never game
  text, and ends (exit 0) or prints `KB_FAIL <reason>` and quits nonzero.

| Driver | Test | What it checks |
|---|---|---|
| `kb_ready.lua` | `test_s5_ready.py` | Spike S5: the mod loads in the real loader and reports ready after `game.ready` |
| `kb_handshake.lua` | `test_handshake.py` | M1: on a test port (27199), after New Game without the intro, `fake_minecraft` gets `hello.ok` (protocol 1, engine 0.3.49, the imported game), a valid `snapshot`, pongs, reconnects to the same session, reads `debug.status` (display options pinned) and quits the game with `debug.quit` |
| `kb_reload.lua` | `test_hot_reload.py` | M1: an F5-style hot reload closes the old instance's connection at once, the new instance binds the same port (27198) cleanly, and a reconnect gets a new session |
| `kb_export.lua` | `test_export.py` | M2: at the title screen, on port 27197, a `builder` connection gets `export.index` and every resource; all messages pass their schemas (strict), every resource passes its export schema, every hash and size matches Python's canonical JSON + SHA-256, the counts match Kanto (222 maps, 36 surface maps whose 78 directed connection edges all agree with the layout, 24 tilesets, 151 species, ...). The driver measures the longest frame while the export builds. Writes the counts-only `local/reports/export_report.json` (counts, ids, sizes, times and the index hash; no data) |
| `kb_walk.lua` | `test_walk.py` | M3: on port 27196, after New Game without the intro, the test fetches three maps' cell flags as a `builder` (kept in memory, never printed), then as `test` walks with `move.step` (each step sent as soon as the one before starts): onto the upstairs stairs (`warp` kind `stairs`), onto the exit mat downstairs (kind `edge`), into a wall as a push (`player.blocked` `tile`), back through the house door (kind `door`) and a push against the mat (kind `edge`, the push answered by a `sync`). Every `move.step` is answered exactly once with its `n`, steps sent while walking chained on the landing poll (`moveChained`), `map.entered` fired for each warp (the driver's `KB_MAP_ENTERED map=<symbol> via=<word>` markers), every message passed its schema. Prints only types, kinds, counts, map symbols and step counts |

Drivers move gen1-bridge off 27151 through `game.mods.exports["gen1-bridge"].configure({ port = N })`,
so a user instance running on 27151 can't interfere.

```sh
tools/.venv/Scripts/python.exe tools/e2e/run_gen1recomp.py kb_ready.lua --report
```
