#!/usr/bin/env bash
# ============================================================
# Owner-run (scripts/factory-admin/). Deletes the build VM and everything in it (backlog #0-238, ADR-0056, S1):
# its disk, its clone and the logins entered in it, which the owner enters again after create.sh. The image Lima
# downloaded stays in Lima's cache (~/Library/Caches/lima), keyed by its digest.
#
#   delete.sh            stops and deletes instance `factory-build`; one that does not exist is not an error
# ============================================================
set -euo pipefail

NAME=factory-build

command -v limactl >/dev/null || { echo "delete.sh: limactl not found (brew install lima)" >&2; exit 1; }
# The list into a variable first: `limactl list | grep -q` under pipefail can fail on SIGPIPE, which would read as
# "no instance" and delete nothing (review round 1 of #0-238); a failing `limactl list` stops the script.
instances=$(limactl list --quiet)
if ! grep -qx "$NAME" <<<"$instances"; then
  echo "delete.sh: no instance $NAME"
  exit 0
fi
limactl delete --force "$NAME"
echo "delete.sh: deleted $NAME"
