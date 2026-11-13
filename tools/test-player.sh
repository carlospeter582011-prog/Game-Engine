#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Set ANDROID_HOME}}
tools=$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
if [[ -n "${NEXUS_TEST_APKSIG:-}" ]]; then
  cp "$NEXUS_TEST_APKSIG" "$tmp/apksig.jar"
else
  curl --fail --silent --show-error --retry 3 -L https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/8.7.3/apksig-8.7.3.jar -o "$tmp/apksig.jar"
fi
keytool -genkeypair -keystore "$tmp/test.p12" -storepass test-only -alias test -keyalg RSA -keysize 2048 -dname CN=NexusSmoke -validity 1 >/dev/null
template=android/app/src/main/assets/nexus-player.apk
icon_path=$(unzip -Z1 "$template" | awk '/\/game_icon.png$/ {print}')
unzip -p "$template" "$icon_path" > "$tmp/icon.png"
# Append a valid empty PNG ancillary chunk before IEND, proving that the payload changes.
python3 - "$tmp/icon.png" <<'PY'
import pathlib, struct, sys, zlib
p = pathlib.Path(sys.argv[1])
data = p.read_bytes()
kind = b'tEXt'
chunk = b'Test\x00Custom icon'
p.write_bytes(data[:-12] + struct.pack('>I', len(chunk)) + kind + chunk
    + struct.pack('>I', zlib.crc32(kind + chunk)) + data[-12:])
PY
javac --release 8 -cp "$tmp/apksig.jar" -d "$tmp" android/app/src/main/java/com/nexusengine/app/GameApk.java tools/player/GameApkSmoke.java
java -cp "$tmp:$tmp/apksig.jar" GameApkSmoke "$tmp/test.p12" "$template" "$tmp" "$tmp/icon.png"
"$tools/apksigner" verify --verbose --min-sdk-version 24 "$tmp/game.apk"
"$tools/zipalign" -c 4 "$tmp/game.apk"
info=$("$tools/aapt" dump badging "$tmp/game.apk")
grep -F "package: name='com.carlos.nexus.offlinegame' versionCode='42' versionName='2.3.4'" <<<"$info"
grep -F "application-label:'Nexus لعبة 🎮'" <<<"$info"
manifest=$("$tools/aapt" dump xmltree "$tmp/game.apk" AndroidManifest.xml)
grep -E 'screenOrientation.*0x7$' <<<"$manifest"
unzip -p "$tmp/game.apk" assets/game.html > "$tmp/game.html"
unzip -p "$tmp/game.apk" "$icon_path" > "$tmp/packaged-icon.png"
cmp tools/smoke-game.html "$tmp/game.html"
cmp "$tmp/icon.png" "$tmp/packaged-icon.png"
