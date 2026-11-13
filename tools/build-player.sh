#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Set ANDROID_HOME}}
platform="$sdk/platforms/android-36/android.jar"
tools=$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/res/drawable-nodpi" "$tmp/classes" android/app/src/main/assets
python3 - "$tmp/res/drawable-nodpi/game_icon.png" <<'PY'
import struct, sys, zlib
def chunk(name, data):
    return struct.pack(">I", len(data)) + name + data + struct.pack(">I", zlib.crc32(name + data))
size = 128
pixels = bytearray()
for y in range(size):
    pixels.append(0)
    for x in range(size):
        letter = 30 <= y < 98 and (31 <= x < 46 or 82 <= x < 97 or abs(x - y) < 10 and 40 <= x < 89)
        pixels.extend((99, 198, 255, 255) if letter else (17, 28, 46, 255))
with open(sys.argv[1], 'wb') as output:
    output.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b''))
PY
"$tools/aapt2" compile --no-crunch --dir "$tmp/res" -o "$tmp/resources.zip"
"$tools/aapt2" link -I "$platform" --manifest tools/player/AndroidManifest.xml -o "$tmp/player.apk" "$tmp/resources.zip"
javac --release 8 -classpath "$platform" -d "$tmp/classes" tools/player/GameActivity.java
jar cf "$tmp/classes.jar" -C "$tmp/classes" .
"$tools/d8" --min-api 24 --lib "$platform" --output "$tmp" "$tmp/classes.jar"
(cd "$tmp" && zip -q player.apk classes.dex)
cp "$tmp/player.apk" android/app/src/main/assets/nexus-player.apk
