#!/usr/bin/env bash
# Guard every signing path using the current repo branch tips and the last
# known distributed release floor. Never downgrade installed RC13 (200012).
set -euo pipefail
code() { sed -nE 's/^[[:space:]]*versionCode[[:space:]]+([0-9]+).*/\1/p' | head -n 1; }
this="$(code < app/build.gradle)"
[[ "$this" =~ ^[0-9]+$ ]]
floor=200012
git fetch --no-tags origin '+refs/heads/*:refs/remotes/origin/*'
while IFS= read -r ref; do
  v="$(git show "$ref:app/build.gradle" 2>/dev/null | code || true)"
  if [[ "$v" =~ ^[0-9]+$ ]] && (( v > floor )); then floor="$v"; fi
done < <(git for-each-ref --format='%(refname)' refs/remotes/origin)
echo "Candidate versionCode $this; highest known $floor"
if (( this < floor || this <= 200012 )); then
  echo "::error::Refusing obsolete signing version $this; require at least $floor and above 200012"
  exit 1
fi
