# tools/

Test tooling for Kanto Bridge. Everything runs from a project-local Python venv; nothing is
installed system-wide.

## Setup and the one test command

| Command | What it does |
|---|---|
| `.\tools\setup.ps1` / `tools/setup.sh` | Creates `tools/.venv` from the pinned, wheel-only `requirements.txt` |
| `.\tools\check.ps1` / `tools/check.sh` | Every automated test: pytest (below), then `gradlew build` in `fabric-bridge` (JUnit and GameTests) on JDK 21 |
| `... -E2E` / `... --e2e` | Also the end-to-end tests on the test Gen1Recomp in `local/gen1recomp-e2e` (a window opens) |
| `... -NoGradle` / `... --no-gradle` | Skips Gradle |

Extra arguments go to pytest, e.g. `tools/check.sh --no-gradle -k redact`. Gradle uses
`KB_JAVA_HOME` if set, otherwise the Temurin 21 path from CLAUDE.md.

## Layout

| Path | Contents |
|---|---|
| `kb_protocol/` | Shared protocol helpers: strict NDJSON framing, the schema registry and message checker, envelope builders, redaction, and the generator of `protocol/catalog.json` and `protocol/redaction-vectors.json` |
| `tests/` | pytest suites for the protocol and `kb_protocol` |
| `lua/` | The Lua test harness: gen1-bridge modules run under lupa's LuaJIT 2.1 in a sandbox-parity environment, with a fake LuaSocket. Its pytest files live beside it |
| `e2e/` | End-to-end runner for the test Gen1Recomp instance, our own driver scripts, and e2e tests (marker `e2e`, run only with `--e2e`) |
| `pytest.ini`, `conftest.py` | pytest configuration (`--e2e` option, markers) |
| `requirements.txt` | Exact pins (see `docs/VERSIONS.md`) |

Coming in M1: **`fake_gen1`** (listens like gen1-bridge, with a synthetic town) and
**`fake_minecraft`** (connects like the fabric-bridge client and checks every message).

## Protocol helpers

```sh
cd tools
.venv/Scripts/python.exe -m kb_protocol.catalog --check    # are the derived files current?
.venv/Scripts/python.exe -m kb_protocol.catalog --write    # regenerate after a schema change
```

## Data safety

The stand-ins and tests never contain ROM data: they use made-up maps, text and flags. Anything that
talks to the real game prints only message types, engine symbols, counts, lengths and short hashes;
ROM-derived fields are redacted through the `x-rom-derived` marks in the schemas
(docs/PLAN.md section 0.3). Reports go to `local/reports/`, which is git-ignored.
