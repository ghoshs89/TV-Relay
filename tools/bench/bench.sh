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

# BENCH_PROVIDER_ARGS sizes the made-up library, e.g. "--channels 56000 --movies 166000 --series 38000" for one the size of a real TV's.
python3 tools/fake-provider/fake_provider.py --port 8099 --ts "$TS" ${BENCH_PROVIDER_ARGS:-} > "$OUT/provider.log" 2>&1 &
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

# ---- Added after the stretches above so their numbers stay comparable with earlier builds. ----
# The guide grid and Search with the remote, then the same screens with a finger: flings take a different
# path through the lists from held keys (no focus changes, many more rows a second), and it is how a
# phone is used. Touch comes last because the first touch takes the app out of remote mode.
SIZE=$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | tail -1)
A=${SIZE%x*}; B=${SIZE#*x}
if [ -n "$A" ] && [ -n "$B" ]; then
  if [ "$A" -ge "$B" ]; then W=$A; H=$B; else W=$B; H=$A; fi   # the app is always landscape
else
  W=1920; H=1080
fi
# $1 flings up the list under the point at 60% of the width (fingers move up, the list moves on).
fling() { for _ in $(seq "$1"); do adb shell input swipe $((W*6/10)) $((H*8/10)) $((W*6/10)) $((H*3/10)) 120; sleep 0.8; done; }
fling_back() { for _ in $(seq "$1"); do adb shell input swipe $((W*6/10)) $((H*3/10)) $((W*6/10)) $((H*8/10)) 120; sleep 0.8; done; }

mark open-live-again; tap "Live TV"; sleep 6
mark guide-open;      tap "Guide grid"; sleep 10; keys 0.5 $DOWN $RIGHT $DOWN
mark guide-hold-down; burst $DOWN 40; sleep 3
mark guide-hold-right; burst $RIGHT 20; sleep 3
mark guide-fling;     fling 6; fling_back 3; sleep 2
mark guide-close;     tap "List view"; sleep 5
mark live-fling;      fling 8; fling_back 4; sleep 2
mark movies-fling;    tap "Movies"; sleep 6; fling 8; fling_back 4; sleep 2
mark home-fling;      tap "Home"; sleep 5; fling 4; fling_back 4; sleep 2
mark search-type;     tap "Search"; sleep 4; adb shell input text "news"; sleep 6
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

# ---- Cold start: the saved copy of the library against the JSON path --------------------------------
# Same emulator, same library, two restarts: one that finds the saved copy (library.snap) and one that
# does not. The benchmark build is not debuggable, so run-as cannot reach the app's files; the emulator
# image is a userdebug one, so adb root can. Both restarts start from a dropped file cache, so neither
# gets the other's leftovers. A build without a saved copy (an older commit, for comparing) takes the
# JSON path twice and the report says so.
SNAP=/data/data/dev.relay.tv/files/catalog/library.snap
adb root > /dev/null 2>&1; adb wait-for-device; sleep 2
cold_start() {
  adb shell am force-stop dev.relay.tv
  sleep 2
  adb shell "sync; echo 3 > /proc/sys/vm/drop_caches" > /dev/null 2>&1 || true
  adb logcat -c
  adb shell am start -W -n dev.relay.tv/.MainActivity > "$OUT/cold-$1-launch.txt" 2>&1
  for _ in $(seq 150); do
    adb logcat -d | grep -q "channels with several versions" && break
    sleep 1
  done
  sleep 3
  adb logcat -d -v threadtime > "$OUT/cold-$1.txt"
}
LISTED=$(adb shell "ls -l $SNAP" 2>&1)
if echo "$LISTED" | grep -q "No such file"; then
  echo "::warning::No saved copy of the library was written during the run (expected on a build from before the saved copy existed)."
else
  echo "A saved copy of the library is on the device: $LISTED"
fi
cold_start saved
adb shell "rm -f $SNAP"
if adb shell "ls $SNAP" 2>&1 | grep -q "No such file"; then
  echo "Deleted the saved copy for the second restart."
else
  echo "::warning::Could not delete the saved copy; the second restart may not take the JSON path."
fi
cold_start json
python3 tools/bench/report.py --cold "$OUT/cold-saved.txt" "$OUT/cold-json.txt" | tee -a "$OUT/report.txt"
