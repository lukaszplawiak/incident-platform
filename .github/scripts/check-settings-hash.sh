#!/usr/bin/env bash
# ============================================================
# Factory guards (backlog #0-194, ADR-0052): .claude/settings.autopilot.json carries the hash of the autopilot
# session's settings. Its env.FACTORY_SETTINGS_SHA256 must equal scripts/factory/settings-hash.sh of that file and
# .claude/settings.json: the session's env holds the value of the files it loaded, and the preflight compares it with
# main's files after its fast-forward. A change to either file that leaves the field as it was would make every later
# session refuse (its value would match no files), so it fails here, in the pull request that makes it, and the
# message gives the value to put in.
#
#   check-settings-hash.sh [autopilot settings] [project settings]
#       default .claude/settings.autopilot.json and .claude/settings.json
#
# Run: .github/scripts/check-settings-hash.sh   (also run by .github/workflows/factory-guards.yml; tested by
# test-preflight.sh)
# ============================================================
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1

file=${1:-.claude/settings.autopilot.json}
project=${2:-.claude/settings.json}
want=$(scripts/factory/settings-hash.sh "$file" "$project") || { echo "::error file=$file::cannot hash $file and $project (settings-hash.sh failed)"; exit 1; }
have=$(jq -r '.env.FACTORY_SETTINGS_SHA256 // ""' "$file")
if [ "$have" != "$want" ]; then
    echo "::error file=$file::env.FACTORY_SETTINGS_SHA256 is '$have', but the hash of $file and $project is $want: set the field to that value (scripts/factory/settings-hash.sh; ADR-0052)"
    exit 1
fi
echo "$file carries the hash of the session's settings ($want)."
