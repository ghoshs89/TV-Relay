#!/usr/bin/env bash
# Installs the release APK on an emulator, starts it, and fails if it crashes. This catches code
# that shrinking removed by mistake before the build is published to TVs.
set -u
APK="$1"
adb wait-for-device
adb install -r "$APK" || { echo "::error::The APK didn't install on the emulator."; exit 1; }
adb logcat -c
adb shell am start -W -n dev.relay.tv/.MainActivity
sleep 25
CRASH=$(adb logcat -d -b crash 2>/dev/null; adb logcat -d | grep -A40 "FATAL EXCEPTION" || true)
if ! adb shell pidof dev.relay.tv > /dev/null; then
  echo "::error::Relay stopped within 25 seconds of starting on the emulator."
  echo "$CRASH" | head -120
  exit 1
fi
if echo "$CRASH" | grep -q "dev.relay.tv"; then
  echo "::error::Relay crashed on the emulator."
  echo "$CRASH" | head -120
  exit 1
fi
echo "Relay started on the emulator and kept running."
