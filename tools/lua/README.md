# tools/lua

Unit tests for `gen1-bridge`'s Lua, run by pytest under **lupa 2.8 on LuaJIT 2.1** (the engine's
runtime; never a bare `import lupa`, which picks Lua 5.5).

| File | Contents |
|---|---|
| `kblua.py` | `Bridge`: a fresh LuaJIT state with the sandbox, a fake `mod`, a fake socket, a fake `love.timer` whose clock tests control (`now`), and optionally the fake engine (`engine=True`). `module(rel)` loads a gen1-bridge file the way `main.lua` does; `run_main()` runs the entry chunk the way the loader does; `ready()` emits `game.ready`; `step(n)` runs logic steps through the `input.step` hooks; `reload()` simulates F5. Strict by default: reading an unknown global or creating one fails. |
| `rig.py` | `Rig`: `main.lua` on the fake engine with `Peer`s, fake Minecraft connections on the fake socket. Every message a peer receives is checked strictly against `protocol/` with `kb_protocol` (leg `g2m`). |
| `sandbox.lua` | A copy of the environment Gen1Recomp gives mods (`src/mods/Sandbox.lua`, v0.3.49), minus the legacy compat shims. Re-check it on every Gen1Recomp pin change. |
| `fake_mod.lua` | The parts of the loader's `mod` object gen1-bridge uses (`read`, `log`, `events`, `hooks`, `input`, `options`, `ui`, `content`, `exports`), plus `emit` (engine events) and `call` (engine hooks). |
| `fake_engine.lua` | The engine pieces `lib/kb_engine.lua` reads: a Game with a state stack, save and data, the overworld and its player (steps, landings, script moves, holds), NPCs, and the engine modules it requires (Version, GameVersion, Badges, and the Tilt/Pipelines/Zoom option appliers, which record their calls). All synthetic. |
| `fake_socket.lua` | LuaSocket 3.0-rc1's TCP semantics (blocking accepted clients, partial receives, send tails, busy ports) with test controls. |
| `gen_catalog.py` | Writes `gen1-bridge/lib/kb_catalog.lua` from `protocol/catalog.json` (`--check` reports staleness). Run it after a protocol change. |
| `test_lua_*.py` | The tests, marked `lua`. `test_lua_s4_spike.py` is spike S4; `test_lua_session.py` the connection rules; `test_lua_player.py` the classifier, the observer and `kb_engine.observe()`; `test_lua_options_snapshot.py` the options pin, the snapshot and the generated catalog. |

Run them with `tools/.venv/Scripts/python.exe -m pytest -c pytest.ini --rootdir . lua` from `tools/`.

`kblua.py` turns off Python's faulthandler: LuaJIT on Windows x64 raises an SEH exception for every
Lua `error()`, and faulthandler would print each one as a "Windows fatal exception".
