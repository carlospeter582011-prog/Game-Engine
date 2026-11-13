#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${KEYSTORE_BASE64:-}" ]]; then
  : "${NEXUS_STORE_PASSWORD:?Missing signing store password}"
  : "${NEXUS_KEY_ALIAS:?Missing signing key alias}"
  : "${NEXUS_KEY_PASSWORD:?Missing signing key password}"
  umask 077
  signing_file="$RUNNER_TEMP/nexus-release.jks"
  printf '%s' "$KEYSTORE_BASE64" | base64 --decode > "$signing_file"
  printf 'NEXUS_KEYSTORE=%s\n' "$signing_file" >> "$GITHUB_ENV"
fi
