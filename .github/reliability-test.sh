#!/usr/bin/env bash
# Run on a fresh, rootable AOSP emulator before the APK is published.
set -euo pipefail
apk=$1
baseline=$2
tmp=$(mktemp -d)
ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=size=320x180:rate=24 \
  -f lavfi -i sine=frequency=440 -t 55 -c:v libx264 -preset ultrafast \
  -pix_fmt yuv420p -c:a aac -f mpegts "$tmp/test.ts"
ffmpeg -hide_banner -loglevel error -i "$tmp/test.ts" -map 0 -c copy -t 24 \
  -hls_time 2 -hls_playlist_type vod -hls_segment_filename "$tmp/segment%02d.ts" "$tmp/index.m3u8"
python3 tools/fake-provider/fake_provider.py --port 8099 --ts "$tmp/test.ts" --hls-dir "$tmp" \
  --channels 20 --movies 20 --series 10 > "$tmp/provider.log" 2>&1 &
provider=$!
trap 'kill "$provider" 2>/dev/null || true' EXIT
for _ in $(seq 15); do
  if curl -fsS http://localhost:8099/player_api.php > /dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS http://localhost:8099/player_api.php > /dev/null

if ! ./gradlew :app:connectedDebugAndroidTest --no-daemon; then
  adb logcat -d -v brief | grep -E 'Relay/(Player|Timeshift|Focus|Epg)' | tail -120 || true
  exit 1
fi
if adb shell pm path dev.relay.tv | grep -q '^package:'; then
  adb uninstall dev.relay.tv
fi
bash .github/smoke-test.sh "$apk"
adb uninstall dev.relay.tv

# Upgrade the published 1.0.126 APK in place. The fixture uses that version's preference keys
# and a synthetic provider. Only the disposable CI emulator has root.
adb install "$baseline"
adb shell am start -W -n dev.relay.tv/.MainActivity
adb shell am force-stop dev.relay.tv
adb root
adb wait-for-device
adb shell am force-stop dev.relay.tv
prefs=/data/user/0/dev.relay.tv/shared_prefs
python3 - "$tmp/relay.xml" <<'PY'
import json
import sys
import xml.etree.ElementTree as ET

data = {
    "account": {"name": "Upgrade test", "hosts": ["http://10.0.2.2:8099"],
                "username": "bench", "password": "bench"},
    "profiles": [{"id": "fixture", "name": "Upgrade profile"}],
    "settings": {"setupDone": True, "subSize": 3, "autoNext": False},
    "fav_fixture": [{"kind": "movie", "id": 100001, "name": "Upgrade favourite"}],
    "hist_fixture": [{"key": "movie:100001", "kind": "movie", "title": "Upgrade movie",
                      "streamId": "100001", "ext": "mp4", "position": 60000,
                      "duration": 600000, "updated": 1795520000000}],
}
xml = ET.Element("map")
for key, value in data.items():
    ET.SubElement(xml, "string", name=key).text = json.dumps(value)
ET.SubElement(xml, "string", name="last_profile").text = "fixture"
ET.ElementTree(xml).write(sys.argv[1], encoding="utf-8", xml_declaration=True)
PY
adb push "$tmp/relay.xml" /data/local/tmp/relay-upgrade-fixture.xml
adb shell "mkdir -p $prefs && owner=\$(stat -c '%u:%g' /data/user/0/dev.relay.tv) &&
  chown \$owner $prefs && chmod 771 $prefs &&
  cp /data/local/tmp/relay-upgrade-fixture.xml $prefs/relay.xml &&
  chown \$owner $prefs/relay.xml && chmod 660 $prefs/relay.xml &&
  restorecon -R $prefs"
bash .github/smoke-test.sh "$apk"
adb pull "$prefs/relay.xml" "$tmp/upgraded.xml"
python3 - "$tmp/relay.xml" "$tmp/upgraded.xml" <<'PY'
import json
import sys
import xml.etree.ElementTree as ET

before, after = (dict((node.attrib["name"], node.text) for node in ET.parse(p).getroot()) for p in sys.argv[1:])
for key in ("profiles", "fav_fixture", "hist_fixture", "settings"):
    assert json.loads(before[key]) == json.loads(after[key]), f"Upgrade changed {key}"
assert before["last_profile"] == after["last_profile"]
print("Signed upgrade preserved the profile, favourite, watch entry, resume position and settings.")
PY
