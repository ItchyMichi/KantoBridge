# tools/e2e

End-to-end runs on the **test** Gen1Recomp install, `local/gen1recomp-e2e/`, never the user's own
`local/gen1recomp/`. They need the pre-flight in docs/PLAN.md §0.6 (the user's ROM import) and run
only with `--e2e`.

- `run_gen1recomp.py` launches the test install in driver mode (`POKEPORT_DRIVER`) with stdout piped,
  and **prints only safe lines**: our drivers' `KB_` markers, `[gen1-bridge]` log lines, the engine's
  `loaded mod gen1-bridge` line, and the locations of Lua errors. Everything else is counted and
  hashed. It checks the install by file names only and never opens anything under `local/`.
  `--report` writes a counts-only summary to `local/reports/e2e_<driver>.json`.
- `drivers/*.lua` are our own driver scripts (unsandboxed, run inside the engine's `main.lua`). A
  driver must print only `KB_<NAME> key=value ...` markers with ids, versions and counts, never game
  text, and end (exit 0) or print `KB_FAIL <reason>` and quit nonzero.
- `test_*.py` are the pytest scenarios. `test_s5_ready.py` is spike S5.

```sh
tools/.venv/Scripts/python.exe tools/e2e/run_gen1recomp.py kb_ready.lua --report
```
