#!/usr/bin/env bash
# ============================================================
# Prints the hash an autopilot session's settings carry (backlog #0-194, ADR-0052): the sha256 of the two settings
# files the session runs on, each as `jq -cS` (keys sorted, compact) on its own line:
#   1. the --settings file, .claude/settings.autopilot.json, without its own `env.FACTORY_SETTINGS_SHA256`, which
#      holds this hash (a file cannot hold a hash of itself, so the field is left out of what is hashed);
#   2. the project settings, .claude/settings.json, which every session loads under it: the session-level twins of
#      the agents' guards and the owner's denies (review round 1 of #0-194: hashing the first file alone left a
#      change to this one undetected).
# Formatting and key order do not change the value; any change of a rule, a hook, a mode or an `env` entry in either
# file does. The value depends on jq's printing: jq 1.6 and 1.7 print some number literals differently, so a number
# in either file could hash differently on two jq versions (neither file has one today; the devcontainer has jq 1.7,
# a newer one gives the same value for these files). A difference fails closed: the preflight refuses.
#
#   settings-hash.sh [autopilot settings] [project settings]
#       default .claude/settings.autopilot.json and .claude/settings.json, relative to the repository root
#
# Used by preflight.sh (the session's env value against main's files, after its fast-forward), by Factory guards'
# check-settings-hash.sh (the field against the files), and by the owner after changing either file: put the value
# it prints into `env.FACTORY_SETTINGS_SHA256` of settings.autopilot.json by hand (Factory guards names it when the
# two differ). Exit 1, printing nothing, when a file is missing or is not JSON.
# ============================================================
set -uo pipefail

autopilot=${1:-.claude/settings.autopilot.json}
project=${2:-.claude/settings.json}
for f in "$autopilot" "$project"; do
    [ -f "$f" ] || { echo "settings-hash.sh: no file $f" >&2; exit 1; }
done

# The two tools of the platforms the factory runs on: sha256sum (Linux, the devcontainer and CI), shasum (macOS).
sha256() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum | cut -d' ' -f1
    else shasum -a 256 | cut -d' ' -f1; fi
}

a=$(jq -cS 'del(.env.FACTORY_SETTINGS_SHA256)' "$autopilot" 2>/dev/null) || { echo "settings-hash.sh: $autopilot is not JSON" >&2; exit 1; }
p=$(jq -cS . "$project" 2>/dev/null) || { echo "settings-hash.sh: $project is not JSON" >&2; exit 1; }
printf '%s\n%s\n' "$a" "$p" | sha256
