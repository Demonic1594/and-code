#!/usr/bin/env bash
# Runs on a booted emulator (see .github/workflows/emulator-tests.yml): the app's device tests,
# then a smoke pass. Everything worth reading lands in emulator-artifacts/. Exits non-zero when
# a device test failed or the app crashed, but always finishes the remaining steps first.
set -uo pipefail

out=emulator-artifacts
mkdir -p "$out/screens"
status=0

adb logcat -c || true
adb logcat -v threadtime >"$out/logcat.txt" 2>&1 &
logcat_pid=$!

echo "::group::Device tests"
./gradlew :app:connectedGithubDebugAndroidTest --continue || status=1
echo "::endgroup::"

apk=app/build/outputs/apk/github/debug/app-github-debug.apk
pkg=$(jq -r '.applicationId' app/build/outputs/apk/github/debug/output-metadata.json)
shot() { adb exec-out screencap -p >"$out/screens/$1.png" || true; }

echo "::group::Smoke pass ($pkg)"
adb install -r -g "$apk"
adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 10
shot 01-launch
adb shell uiautomator dump /sdcard/launch.xml >/dev/null 2>&1 && adb pull /sdcard/launch.xml "$out/launch-ui.xml" >/dev/null || true
if ! adb shell pidof "$pkg" >/dev/null; then
  echo "::error::$pkg is not running 10 s after launch"
  status=1
fi

# Rotation, then back to portrait: catches state that is lost on recreation.
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 4
shot 02-landscape
adb shell settings put system user_rotation 0
sleep 4
shot 03-portrait
echo "::endgroup::"

events=${MONKEY_EVENTS:-1500}
case "$events" in '' | *[!0-9]*) echo "::warning::MONKEY_EVENTS is not a number; skipping the monkey pass"; events=0 ;; esac
if [ "$events" -gt 0 ]; then
  echo "::group::Monkey ($events events)"
  # Fixed seed so a crash can be replayed with the same events.
  adb shell monkey -p "$pkg" -s 4242 --throttle 250 --pct-syskeys 0 \
    --ignore-security-exceptions -v "$events" >"$out/monkey.txt" 2>&1 || true
  shot 04-after-monkey
  if grep -qE "// CRASH|// NOT RESPONDING|Monkey aborted" "$out/monkey.txt"; then
    echo "::error::The monkey pass hit a crash or ANR; see monkey.txt and logcat.txt"
    status=1
  fi
  echo "::endgroup::"
fi

kill "$logcat_pid" 2>/dev/null || true
grep -nE "FATAL EXCEPTION|AndroidRuntime: Process: $pkg" "$out/logcat.txt" >"$out/crashes.txt" || true
if [ -s "$out/crashes.txt" ]; then
  echo "::error::Fatal exceptions in logcat; see crashes.txt"
  status=1
fi

exit "$status"
