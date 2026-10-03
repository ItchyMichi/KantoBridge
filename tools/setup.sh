#!/usr/bin/env bash
# Creates tools/.venv with the pinned, wheel-only test packages (tools/requirements.txt).
# Usage (Git Bash): tools/setup.sh            Re-run after a requirements change or a repo move.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
venv="$here/.venv"

python_cmd=python
command -v "$python_cmd" >/dev/null 2>&1 || python_cmd=python3

if [ ! -d "$venv" ]; then
	"$python_cmd" -m venv "$venv"
fi
py="$venv/Scripts/python.exe"
[ -x "$py" ] || py="$venv/bin/python"

"$py" -m pip install --disable-pip-version-check --only-binary=:all: -r "$here/requirements.txt"
"$py" -c "import lupa.luajit21, jsonschema, pytest; print('tools/.venv ready')"
