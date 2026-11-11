#!/usr/bin/env bash
# Runs the benchmark APK on an emulator against the made-up provider and prints what the app logged:
# frame times per screen, list build times, and time to first picture. Used by the "bench" job in
# .github/workflows/build.yml; the numbers show regressions between builds, not how fast a TV is
# (an emulator on a build server is nothing like a TV processor).
#
#   bash tools/bench/bench.sh <benchmark.apk> <test.ts> [output dir]
set -u
APK="$1"; TS="$2"; OUT="${3:-bench-out}"
mkdir -p "$OUT"

python3 tools/fake-provider/fake_provider.py --port 8099 --ts "$TS" > "$OUT/provider.log" 2>&1 &
PROVIDER=$!
trap 'kill $PROVIDER 2>/dev/null' EXIT
for _ in $(seq 30); do
  curl -fs "localhost:8099/player_api.php?username=bench&password=bench" > /dev/null && break
  sleep 1
done

UP=19; DOWN=20; LEFT=21; RIGHT=22; OK=23; BACK=4

# The screen on top is printed too, so a run that wandered off (out of the app, say) shows up in the log.
mark() {
  local top
  top=$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E "topResumedActivity|mResumedActivity" | sed 's/^ *//' | cut -c1-110)
  echo "== $1   ($top)"
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb shell cat /sdcard/ui.xml 2>/dev/null | python3 tools/bench/uidump.py || true
  adb shell log -t RelayBench "stretch:$1"
}
# One key press every $1 seconds: keys 0.3 $DOWN $DOWN $RIGHT
keys() { local gap=$1; shift; for k in "$@"; do adb shell input keyevent "$k"; sleep "$gap"; done; }
# $2 presses of key $1 sent as fast as the app takes them, like holding the button down.
burst() { adb shell "input keyevent $(yes "$1" | head -n "$2" | tr '\n' ' ')"; }
# Opens a screen by tapping its label (found in a UI dump), so the run does not depend on where focus starts.
tap() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  local xy
  xy=$(adb shell cat /sdcard/ui.xml 2>/dev/null | python3 tools/bench/uitap.py "$1")
  if [ -n "$xy" ]; then adb shell input tap $xy; else echo "   (no '$1' on screen)"; fi
}
top_is() { adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E "topResumedActivity|mResumedActivity" | grep -q "$1"; }
# Leaves the player (two Back presses close to each other) but only when it is the player on screen, so a
# run that never got there does not Back out of the app.
back_out() {
  if top_is PlayerActivity; then
    keys 0.6 $BACK $BACK
  else
    echo "   (the player is not on screen)"
  fi
}
# OK on the focused channel; when that does not open the player the log says so and a tap on a channel name does.
play_live() {
  keys 0.5 $OK; sleep 4
  if ! top_is PlayerActivity; then
    echo "   (OK on the focused row did not open the player; tapping a channel instead)"
    tap "re:^[A-Z]{2} ?[:|-] "; sleep 4
  fi
  sleep 10
}
repeat() { local n=$1; shift; for _ in $(seq "$n"); do echo "$@"; done | tr '\n' ' '; }

adb wait-for-device
adb install -r "$APK" || { echo "::error::The benchmark APK didn't install."; exit 1; }
adb logcat -c
adb shell am start -W -n dev.relay.tv/.MainActivity

# The library (12,000 channels, 40,000 movies, 8,000 series) has to be loaded first.
for _ in $(seq 90); do
  adb logcat -d | grep -q "channels with several versions" && break
  sleep 2
done
sleep 8

mark home-idle;       sleep 8
mark home-browse;     keys 0.3 $DOWN $DOWN $DOWN $RIGHT $RIGHT $RIGHT $RIGHT $RIGHT $RIGHT $LEFT $LEFT $LEFT $UP $UP $UP
mark open-live-tab;   tap "Live TV"; sleep 6; keys 0.5 $DOWN $RIGHT $DOWN
mark live-steady;     keys 0.25 $(repeat 25 $DOWN)
mark live-hold-down;  burst $DOWN 60; sleep 3
mark live-play;       play_live
mark live-zap;        keys 2 $DOWN $DOWN $DOWN $UP; sleep 4
mark live-leave;      back_out; sleep 4
mark live-hold-up;    burst $UP 60; sleep 3
mark open-movies;     tap "Movies"; sleep 6; keys 0.5 $DOWN $RIGHT $DOWN
mark movies-hold-down; burst $DOWN 60; sleep 3
mark movies-hold-right; burst $RIGHT 30; sleep 3
mark movies-hold-up;  burst $UP 60; sleep 3
mark open-series;     tap "Series"; sleep 6; keys 0.5 $DOWN $RIGHT $DOWN
mark series-hold-down; burst $DOWN 60; sleep 3
mark done;            sleep 5

adb logcat -d -v threadtime > "$OUT/logcat.txt"
adb shell dumpsys gfxinfo dev.relay.tv > "$OUT/gfxinfo.txt" 2>&1 || true
adb shell dumpsys meminfo dev.relay.tv > "$OUT/meminfo.txt" 2>&1 || true
python3 tools/bench/report.py "$OUT/logcat.txt" | tee "$OUT/report.txt"
grep -E "Total PSS|Java Heap|Native Heap|Graphics" "$OUT/meminfo.txt" | head -6 || true
grep -E "Janky frames|50th|90th|95th|99th" "$OUT/gfxinfo.txt" | head -6 || true
if adb logcat -d -b crash | grep -q "dev.relay.tv"; then
  echo "::warning::The benchmark build crashed during the run."
  adb logcat -d -b crash | head -60
fi
