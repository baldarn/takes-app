#!/usr/bin/env bash
# Installs the debug build and its test build on the device adb talks to (ANDROID_SERIAL picks
# one when several are attached), runs the pixel tests of the shaders and fails unless the report
# ends in OK. `am instrument` exits with 0 even when tests fail or the process crashes, so the
# result is read from its output. Arguments go to `am instrument`, for example `-e only Wipe`.
set -euo pipefail

cd "$(dirname "$0")/.."
# On the CI only, a copy left on the emulator by another build (a snapshot from another runner,
# whose debug key differs) would refuse the install as an incompatible update: take it off first.
# Never on a phone of one's own, where it would delete the development build's projects.
if [ "${CI:-}" = "true" ]; then
  adb uninstall app.takes.dev.test > /dev/null 2>&1 || true
  adb uninstall app.takes.dev > /dev/null 2>&1 || true
fi
./gradlew :app:installDebug :app:installDebugAndroidTest

report="$(mktemp)"
trap 'rm -f "$report"' EXIT
adb shell am instrument -w "$@" app.takes.dev.test/app.takes.pixels.TransitionPixelRunner | tr -d '\r' | tee "$report"

if grep -q 'FAILURES!!!' "$report"; then
  echo "Pixel tests failed." >&2
  exit 1
fi
if grep -qE 'INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=|Process crashed' "$report"; then
  echo "The instrumentation did not finish: it crashed or could not start." >&2
  exit 1
fi
if ! grep -qE '^OK \([1-9][0-9]* tests\)' "$report"; then
  echo "The report does not end in OK with at least one test: the run did not complete, or no case was chosen." >&2
  exit 1
fi
