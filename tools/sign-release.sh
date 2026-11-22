#!/usr/bin/env bash
# Sign a privately distributed DriveMate Android release with the owner's keystore.
set -euo pipefail
if [[ "$#" -ne 3 ]]; then
  echo "Usage: $0 app-release-unsigned.apk DriveMate-signing-keystore.p12 DriveMate-vX.Y.apk" >&2
  exit 2
fi
: "${DRIVEMATE_SIGNING_PASSWORD:?Set DRIVEMATE_SIGNING_PASSWORD in your environment (never in git).}"
: "${ANDROID_HOME:?Set ANDROID_HOME to your installed Android SDK directory.}"
INPUT="$1"; KEYSTORE="$2"; OUTPUT="$3"
TOOLS="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
if [[ ! -f "$INPUT" || ! -f "$KEYSTORE" || ! -x "$TOOLS/apksigner" ]]; then
  echo "Missing unsigned APK, keystore or Android Build Tools apksigner" >&2
  exit 3
fi
"$TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-type PKCS12 \
  --ks-key-alias drivemate-release \
  --ks-pass env:DRIVEMATE_SIGNING_PASSWORD \
  --key-pass env:DRIVEMATE_SIGNING_PASSWORD \
  --out "$OUTPUT" "$INPUT"
"$TOOLS/apksigner" verify --verbose --print-certs --min-sdk-version 26 "$OUTPUT"
echo "Signed and verified private release: $OUTPUT"
