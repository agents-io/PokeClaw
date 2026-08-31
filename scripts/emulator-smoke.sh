#!/usr/bin/env bash
# Emulator smoke test runner invoked by .github/workflows/emulator-matrix.yml
# Each line of the workflow's `script:` block is interpreted as an
# independent `sh -c` call, which breaks multi-line shell (variable
# assignments, if/then/fi). So we put the actual logic in this file
# and let the workflow call it as one line.
#
# Usage: ./scripts/emulator-smoke.sh <api-level>

set -e

API_LEVEL="${1:-unknown}"

echo "::group::APK info"
echo "Searching for APK under apk/ ..."
find apk -type f -name '*.apk' || echo "no .apk found anywhere under apk/"
APK="$(find apk -type f -name '*.apk' | head -1)"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "::error::No APK found under apk/. download-artifact may have placed it elsewhere or upload failed."
  ls -laR apk/ || true
  exit 1
fi
echo "Using APK: $APK ($(stat -c%s "$APK") bytes)"
echo "::endgroup::"

echo "::group::Device info"
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.model
echo "::endgroup::"

echo "::group::Install APK"
adb install -r -t "$APK"
echo "::endgroup::"

echo "::group::Verify package installed"
adb shell pm list packages | grep io.agents.pokeclaw || { echo "Package not installed!"; exit 1; }
echo "::endgroup::"

echo "::group::Launch app"
adb logcat -c
# Correct component is io.agents.pokeclaw/.ui.splash.SplashActivity
# (the .ui.splash. path is the actual class location; earlier rev had a stale
# com.apk.claw.android.ui.splash.SplashActivity name from a pre-rename build).
adb shell am start -W -n io.agents.pokeclaw/.ui.splash.SplashActivity \
  || adb shell monkey -p io.agents.pokeclaw -c android.intent.category.LAUNCHER 1
echo "::endgroup::"

echo "::group::Wait for app to settle"
sleep 8
echo "::endgroup::"

echo "::group::Verify process running"
PID="$(adb shell pidof io.agents.pokeclaw || echo "")"
if [ -z "$PID" ]; then
  echo "::error::App crashed on launch! No process found for io.agents.pokeclaw"
  adb logcat -d -t 200 > "logcat-crash.txt"
  exit 1
fi
echo "App is running with PID: $PID"
echo "::endgroup::"

echo "::group::Check for crashes in logcat"
adb logcat -d -b crash | head -50 > "logcat-crash-buffer.txt"
if grep -q "FATAL EXCEPTION" "logcat-crash-buffer.txt"; then
  echo "::error::FATAL EXCEPTION found in crash buffer"
  cat "logcat-crash-buffer.txt"
  exit 1
fi
echo "No fatal exceptions in crash buffer."
echo "::endgroup::"

echo "::group::Capture logcat artifact"
adb logcat -d > "logcat-full-api${API_LEVEL}.txt"
adb shell screencap -p "/sdcard/screen-api${API_LEVEL}.png"
adb pull "/sdcard/screen-api${API_LEVEL}.png" .
echo "::endgroup::"

echo "::group::Smoke test summary"
echo "API Level: ${API_LEVEL}"
echo "PASS: app installs, launches, no crash, process alive after 8s"
echo "::endgroup::"
