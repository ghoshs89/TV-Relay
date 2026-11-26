#!/usr/bin/env bash
# Run on a disposable emulator. Captures use generated media and the demo account.
set -euo pipefail
layout=$1
case "$layout" in
  tv)
    adb shell wm size 1920x1080
    adb shell wm density 320
    ;;
  phone)
    adb shell wm size 1080x2340
    adb shell wm density 420
    ;;
  *) exit 2 ;;
esac
fixture=$(mktemp -d)
ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=size=640x360:rate=24 \
  -f lavfi -i sine=frequency=440 -t 20 -c:v libx264 -preset ultrafast \
  -pix_fmt yuv420p -c:a aac -f mpegts "$fixture/demo.ts"
python3 tools/fake-provider/fake_provider.py --port 8099 --ts "$fixture/demo.ts" \
  --channels 200 --movies 100 --series 100 > "$fixture/provider.log" 2>&1 &
provider=$!
trap 'kill "$provider" 2>/dev/null || true' EXIT
curl --retry 10 --retry-connrefused --retry-delay 1 -fsS http://localhost:8099/player_api.php > /dev/null
./gradlew :app:connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.relay.tv.DocumentationScreenshotsTest \
  -Pandroid.testInstrumentationRunnerArguments.gallery="$layout"
mkdir -p "gallery-$layout"
adb pull "/sdcard/Android/data/dev.relay.tv/files/gallery-$layout/." "gallery-$layout"
