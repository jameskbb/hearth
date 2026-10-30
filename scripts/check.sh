#!/usr/bin/env bash
# Repository checks: unit tests, test-app builds, script lint, agent-rule
# parity, and a public-safety scan.
set -u
cd "$(dirname "$0")/.." || exit 1
fail=0

python3 scripts/check-public-safety.py || { echo "FAIL: public-safety checks"; fail=1; }

# Shell scripts: syntax always, shellcheck when installed.
for s in scripts/*.sh scripts/lib/*.sh; do
  bash -n "$s" || { echo "FAIL: syntax error in $s"; fail=1; }
done
if command -v shellcheck >/dev/null 2>&1; then
  shellcheck -x scripts/*.sh scripts/lib/*.sh || { echo "FAIL: shellcheck"; fail=1; }
fi

./gradlew -q testDebugUnitTest || { echo "FAIL: unit tests"; fail=1; }
./gradlew -q :fixtures:assembleDebug :demoapps:assembleDebug || { echo "FAIL: fixture or demo app build"; fail=1; }

[ $fail -eq 0 ] && echo "OK: all checks passed"
exit $fail
