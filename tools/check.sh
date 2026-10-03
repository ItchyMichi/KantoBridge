#!/usr/bin/env bash
# One entry point for every automated test (docs/PLAN.md section 5):
#   1. pytest: protocol schemas, kb_protocol, gen1-bridge Lua under lupa, stand-ins;
#   2. Gradle build in fabric-bridge: JUnit and server GameTests.
#
# Usage (Git Bash): tools/check.sh [--e2e] [--no-gradle] [extra pytest args]
#   --e2e        also run end-to-end tests on local/gen1recomp-e2e (a Gen1Recomp window opens)
#   --no-gradle  skip the Gradle build
# Gradle runs on JDK 21 from KB_JAVA_HOME (default: the Temurin 21 path in CLAUDE.md); the
# machine's default Java is left alone.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"

py="$here/.venv/Scripts/python.exe"
[ -x "$py" ] || py="$here/.venv/bin/python"
if [ ! -x "$py" ]; then
	echo "check: tools/.venv is missing; run tools/setup.sh first" >&2
	exit 2
fi

pytest_args=()
gradle=1
for arg in "$@"; do
	case "$arg" in
		--e2e) pytest_args+=("--e2e") ;;
		--no-gradle) gradle=0 ;;
		*) pytest_args+=("$arg") ;;
	esac
done

export PYTHONUTF8=1
echo "== pytest"
(cd "$here" && "$py" -m pytest -c pytest.ini --rootdir . ${pytest_args[@]+"${pytest_args[@]}"})

if [ "$gradle" = 1 ]; then
	echo "== gradle build (fabric-bridge)"
	java_home="${KB_JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot}"
	(cd "$root/fabric-bridge" && JAVA_HOME="$java_home" ./gradlew build)
fi
echo "check: OK"
