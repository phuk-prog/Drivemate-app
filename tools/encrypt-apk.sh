#!/usr/bin/env bash
# Produce a standard ZIP with WinZip AES-256 encryption, not legacy ZipCrypto.
set -euo pipefail
set +x
umask 077
if [[ $# -ne 2 || ! -f "$1" || "$1" != *.apk || "$2" != *.zip ]]; then
  echo 'Usage: encrypt-apk.sh INPUT.apk OUTPUT.zip' >&2; exit 2
fi
if [[ -z "${DRIVEMATE_ZIP_PASSWORD:-}" || ${#DRIVEMATE_ZIP_PASSWORD} -lt 16 ]]; then
  echo 'Set DRIVEMATE_ZIP_PASSWORD (at least 16 characters).' >&2; exit 1
fi
command -v 7z >/dev/null
apk="$(realpath "$1")"
out="$(realpath -m "$2")"
[[ ! -e "$out" ]] || { echo 'Refusing to overwrite an archive' >&2; exit 1; }
mkdir -p "$(dirname "$out")"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
(
  cd "$(dirname "$apk")"
  7z a -tzip -mem=AES256 -mx=0 "-p$DRIVEMATE_ZIP_PASSWORD" "$stage/protected.zip" "$(basename "$apk")" >/dev/null
)
7z t "-p$DRIVEMATE_ZIP_PASSWORD" "$stage/protected.zip" >/dev/null
7z l -slt "$stage/protected.zip" | grep -q 'Method = AES-256'
mv "$stage/protected.zip" "$out"
echo 'Created and verified AES-256 encrypted APK ZIP.'
