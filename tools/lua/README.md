# tools/lua

Unit tests for `gen1-bridge`'s Lua, run by pytest under **lupa 2.8 on LuaJIT 2.1** (the engine's
runtime; never a bare `import lupa`, which picks Lua 5.5).

| File | Contents |
|---|---|
| `kblua.py` | `Bridge`: a fresh LuaJIT state with the sandbox, a fake `mod` and a fake socket. `module(rel)` loads a gen1-bridge file the way `main.lua` does; `run_main()` runs the entry chunk the way the loader does. Strict by default: reading an unknown global or creating one fails. |
| `sandbox.lua` | A copy of the environment Gen1Recomp gives mods (`src/mods/Sandbox.lua`, v0.3.49), minus the legacy compat shims. Re-check it on every Gen1Recomp pin change. |
| `fake_mod.lua` | The parts of the loader's `mod` object gen1-bridge uses, plus `emit` (engine events) and `call` (engine hooks). |
| `fake_socket.lua` | LuaSocket 3.0-rc1's TCP semantics (blocking accepted clients, partial receives, send tails, busy ports) with test controls. |
| `test_lua_*.py` | The tests, marked `lua`. `test_lua_s4_spike.py` is spike S4. |

Run them with `tools/.venv/Scripts/python.exe -m pytest -c tools/pytest.ini tools/lua`.

`kblua.py` turns off Python's faulthandler: LuaJIT on Windows x64 raises an SEH exception for every
Lua `error()`, and faulthandler would print each one as a "Windows fatal exception".
