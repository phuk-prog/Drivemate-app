#!/usr/bin/env bash
# Run with an authenticated GitHub CLI account with Actions write + contents write.
# Default is a dry run. --delete explicitly removes matching historical binaries.
set -euo pipefail
repo="${GH_REPO:-phuk-prog/Drivemate-app}"
[[ "$repo" == "phuk-prog/Drivemate-app" ]] || { echo 'Unexpected repository' >&2; exit 1; }
mode="${1:---dry-run}"
[[ "$mode" == --dry-run || "$mode" == --delete ]] || exit 2
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
gh api --paginate "repos/$repo/actions/artifacts?per_page=100" --jq '.artifacts[] | select(.expired == false) | [.id,.name] | @tsv' > "$tmp/artifacts"
gh api --paginate "repos/$repo/releases?per_page=100" --jq '.[].assets[] | [.id,.name] | @tsv' > "$tmp/assets"
while IFS=$'\t' read -r id name; do
  [[ "$id" =~ ^[0-9]+$ ]] || continue
  lower="${name,,}"
  [[ "$lower" == *password-protected* || "$lower" == *encrypted* || "$lower" == *apksigner* ]] && continue
  if [[ "$lower" =~ (^|[-_.[:space:]])(apk|apks|xapk|apkm|aab)($|[-_.[:space:]]) || ( "$lower" == drivemate* && "$lower" =~ (^|[-_.])(debug|unsigned)($|[-_.]) ) ]]; then
    printf '%s Actions artifact %s: %s\n' "$mode" "$id" "$name"
    if [[ "$mode" == --delete ]]; then gh api --method DELETE "repos/$repo/actions/artifacts/$id" --silent; fi
  fi
done < "$tmp/artifacts"
while IFS=$'\t' read -r id name; do
  [[ "$id" =~ ^[0-9]+$ ]] || continue
  case "${name,,}" in
    *.apk|*.apks|*.xapk|*.apkm|*.aab)
      printf '%s release asset %s: %s\n' "$mode" "$id" "$name"
      if [[ "$mode" == --delete ]]; then gh api --method DELETE "repos/$repo/releases/assets/$id" --silent; fi
      ;;
  esac
done < "$tmp/assets"
echo 'Review any differently named historical archives manually. Previously downloaded copies cannot be recalled.'
