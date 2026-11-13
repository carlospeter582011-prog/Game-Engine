#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

flutter_version=3.50.0
if ! command -v flutter >/dev/null 2>&1; then
  flutter_install=$(mktemp -d)
  trap 'rm -rf "$flutter_install"' EXIT
  git clone --depth 1 --branch "$flutter_version" https://github.com/flutter/flutter.git "$flutter_install/sdk"
  export PATH="$flutter_install/sdk/bin:$PATH"
fi

export CI=true
bash tools/fetch-python.sh
flutter pub get
node tools/test-runtime.cjs
node tools/test-web-apk.cjs
flutter build web --release --no-web-resources-cdn
test -s build/web/index.html
test -s build/web/assets/assets/web/editor.html
test -s build/web/assets/assets/web/vendor/skulpt.min.js
