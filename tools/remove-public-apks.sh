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
gh api --paginate "repos/$repo/actions/artifacts?per_page=100" --jq '.artifacts[] | select(.expired == false) | [.id,.name] | @tsv' | sort -u > "$tmp/artifacts"
gh api --paginate "repos/$repo/releases?per_page=100" --jq '.[].assets[] | [.id,.name] | @tsv' | sort -u > "$tmp/assets"
delete_if_present() {
  local endpoint="$1" result
  if result="$(gh api --method DELETE "$endpoint" --silent 2>&1)"; then
    return 0
  fi
  if [[ "$result" == *"HTTP 404"* ]]; then
    echo "Already absent: $endpoint"; return 0
  fi
  printf '%s\n' "$result" >&2
  return 1
}
while IFS=$'\t' read -r id name; do
  [[ "$id" =~ ^[0-9]+$ ]] || continue
  lower="${name,,}"
  [[ "$lower" == *password-protected* || "$lower" == *encrypted* || "$lower" == *apksigner* ]] && continue
  if [[ "$lower" =~ (^|[-_.[:space:]])(apk|apks|xapk|apkm|aab)($|[-_.[:space:]]) || ( "$lower" == drivemate* && "$lower" =~ (^|[-_.])(debug|unsigned)($|[-_.]) ) ]]; then
    printf '%s Actions artifact %s: %s\n' "$mode" "$id" "$name"
    if [[ "$mode" == --delete ]]; then delete_if_present "repos/$repo/actions/artifacts/$id"; fi
  fi
done < "$tmp/artifacts"
while IFS=$'\t' read -r id name; do
  [[ "$id" =~ ^[0-9]+$ ]] || continue
  case "${name,,}" in
    *.apk|*.apks|*.xapk|*.apkm|*.aab)
      printf '%s release asset %s: %s\n' "$mode" "$id" "$name"
      if [[ "$mode" == --delete ]]; then delete_if_present "repos/$repo/releases/assets/$id"; fi
      ;;
  esac
done < "$tmp/assets"
echo 'Review any differently named historical archives manually. Previously downloaded copies cannot be recalled.'
