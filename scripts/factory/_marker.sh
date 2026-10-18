#!/usr/bin/env bash
# ============================================================
# The escape marker of the backlog, defined once. Sourced, not run; no side effects. Used by audit-data.sh (the
# audits count these markers) and by .github/scripts/check-escape-markers.sh (a malformed one fails CI).
#
#   MARKER           an ERE for a real marker: `**Fixes:** #0-<digits> · **Escaped from:** <stage>` on one line,
#                    the fixing item first. Prose that only describes the convention (`#0-N`) is not one.
#   MARKER_ATTEMPT   an ERE, matched case-insensitively (grep -i), for a line that tries to be one: `Escaped from`
#                    with a colon, bold or not, followed by a stage (a letter). The convention's own prose has
#                    `<stage>`, a backtick or the end of the line there instead; "escaped from review" in a
#                    sentence has no colon.
#   marker_span      the marker itself, out of a MARKER line: from `**Fixes:**` to the end of the stage.
#   marker_item      its item (`0-8`), and marker_stage its stage (`plan`; `Review-General` is returned as written,
#                    the audit then reports an `escaped:unparsed` case).
# A line that matches MARKER_ATTEMPT but not MARKER would vanish from the audits; the CI check refuses it.
#
# Fixed (backlog #0-128): a marker without its `**Fixes:**` part was silently dropped; the CI check that now
# refuses it reads this one definition rather than a copy (as _protected.sh does for the protected paths).
# Review round 1 of #0-128/#0-129: the audit traced and dated a marker by its whole line, and the marker sits on
# the item's `**Type:** … · **Status:** …` line, so changing the status moved the marker's origin to whoever
# changed it (the owner's edit vouched for the bot's marker, the bot's close unvouched the owner's). It now keys on
# marker_span, which the rest of the line does not touch; and the item and stage are read here, not by a second
# pattern in audit-data.sh's jq. The attempt pattern was case-sensitive and bold-only, so `**Escaped From:**` or
# `Escaped from:` vanished from the audits without CI noticing.
# ============================================================

# Read by the scripts that source this file, not here (backlog #0-149).
# shellcheck disable=SC2034
MARKER='\*\*Fixes:\*\* #0-[0-9]+.*\*\*Escaped from:\*\*'
MARKER_ATTEMPT='escaped[[:space:]]+from[[:space:]]*(\*\*)?[[:space:]]*:[[:space:]]*(\*\*)?[[:space:]]*[a-z]'

marker_span()  { printf '%s\n' "$1" | grep -oE "${MARKER}[[:space:]]*[^[:space:]]*" | head -n 1; }
marker_item()  { marker_span "$1" | grep -oE '#0-[0-9]+' | head -n 1 | sed 's/^#//'; }
marker_stage() {
    local s
    s=$(marker_span "$1"); s=${s##*\*\*Escaped from:\*\*}
    printf '%s\n' "${s#"${s%%[![:space:]]*}"}"
}
