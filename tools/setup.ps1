# Creates tools\.venv with the pinned, wheel-only test packages (tools\requirements.txt).
# Usage (PowerShell): .\tools\setup.ps1      Re-run after a requirements change or a repo move.
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$venv = Join-Path $here '.venv'

if (-not (Test-Path $venv)) {
    python -m venv $venv
    if ($LASTEXITCODE -ne 0) { throw 'python -m venv failed' }
}
$py = Join-Path $venv 'Scripts\python.exe'

& $py -m pip install --disable-pip-version-check --only-binary=:all: -r (Join-Path $here 'requirements.txt')
if ($LASTEXITCODE -ne 0) { throw 'pip install failed' }
& $py -c "import lupa.luajit21, jsonschema, pytest; print('tools/.venv ready')"
exit $LASTEXITCODE
