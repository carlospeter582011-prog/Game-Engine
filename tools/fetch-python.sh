#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
target=assets/web/vendor
mkdir -p "$target"
verify() {
  (cd "$1"; sha256sum --status -c <<'HASHES'
1a319d8eedf314dba5af2444313e3cf2ac072a335df0f5100277e8f49b64eae9  skulpt.min.js
e3ecccbc17c6164d19ed3c5561aaaeb752c38c8efa2d88b62b5fb7a7e1b086a7  skulpt-stdlib.js
HASHES
  )
}
if [[ -f "$target/skulpt.min.js" && -f "$target/skulpt-stdlib.js" ]] && verify "$target"; then exit 0; fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl --fail --silent --show-error --retry 3 -L https://registry.npmjs.org/skulpt/-/skulpt-1.2.0.tgz -o "$tmp/skulpt.tgz"
tar -xzf "$tmp/skulpt.tgz" -C "$tmp" --strip-components=2 package/dist/skulpt.min.js package/dist/skulpt-stdlib.js
verify "$tmp"
cp "$tmp/skulpt.min.js" "$tmp/skulpt-stdlib.js" "$target/"
