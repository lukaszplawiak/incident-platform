#!/usr/bin/env bash
# ============================================================
# Tests for .github/scripts/check-protected-paths.sh: it passes on the repository's own files and fails when
# the settings miss a deny rule, when the hook lets a write through, when CODEOWNERS misses an owner-only
# path, or when the list cannot be read.
# Run: .github/scripts/test-protected-paths.sh   (also run by .github/workflows/factory-guards.yml)
# ============================================================
set -uo pipefail

SRC=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
CHECK="$SRC/.github/scripts/check-protected-paths.sh"

# expect <0|1> <name> <args...>   (run from the repository root, like CI)
expect() {
    local want=$1 name=$2; shift 2
    local rc
    (cd "$SRC" && "$CHECK" "$@" >/dev/null 2>&1); rc=$?
    if [ "$rc" -eq "$want" ]; then echo "  ok: $name"; else echo "::error::$name: expected exit $want, got $rc"; failures=$((failures+1)); fi
}

echo "check-protected-paths"
expect 0 "the repository's own copies cover the list"

jq '.permissions.deny |= map(select(. != "Write(./.github/**)"))' "$SRC/.claude/settings.autopilot.json" > "$WORK/no-write.json"
expect 1 "a missing Write deny fails" "$WORK/no-write.json"

jq '.permissions.deny |= map(select(. != "Edit(./mvnw)"))' "$SRC/.claude/settings.autopilot.json" > "$WORK/no-edit.json"
expect 1 "a missing deny for a build-config file fails" "$WORK/no-edit.json"

printf '#!/usr/bin/env bash\ncat >/dev/null\nexit 0\n' > "$WORK/open-hook.sh"
expect 1 "a hook that lets writes through fails" "$SRC/.claude/settings.autopilot.json" "$WORK/open-hook.sh"

grep -v '^/.ai/audit/decisions.md' "$SRC/.github/CODEOWNERS" > "$WORK/CODEOWNERS-missing"
expect 1 "a protected path without a CODEOWNERS rule fails" "$SRC/.claude/settings.autopilot.json" "$SRC/.claude/hooks/guard-protected-bash.sh" "$WORK/CODEOWNERS-missing"

printf '/.ai/   @owner\n/.claude/ @owner\n/.github/ @owner\n/architecture-tests/ @owner\n/AGENTS.md @owner\n/scripts/ @owner\n/.devcontainer/ @owner\n' > "$WORK/CODEOWNERS-parents"
expect 0 "a rule for a directory above covers it" "$SRC/.claude/settings.autopilot.json" "$SRC/.claude/hooks/guard-protected-bash.sh" "$WORK/CODEOWNERS-parents"

{ cat "$SRC/.github/CODEOWNERS"; echo '/.github/workflows/'; } > "$WORK/CODEOWNERS-unowned"
expect 1 "a later narrower line without an owner fails (last match wins)" "$SRC/.claude/settings.autopilot.json" "$SRC/.claude/hooks/guard-protected-bash.sh" "$WORK/CODEOWNERS-unowned"

{ echo '/.github/workflows/'; cat "$SRC/.github/CODEOWNERS"; } > "$WORK/CODEOWNERS-unowned-first"
expect 0 "an unowned line before the owned one is overridden" "$SRC/.claude/settings.autopilot.json" "$SRC/.claude/hooks/guard-protected-bash.sh" "$WORK/CODEOWNERS-unowned-first"

printf '* @owner\n' > "$WORK/CODEOWNERS-star"
expect 0 "a global * rule with an owner covers everything" "$SRC/.claude/settings.autopilot.json" "$SRC/.claude/hooks/guard-protected-bash.sh" "$WORK/CODEOWNERS-star"

echo '{"permissions":{"deny":[]}}' > "$WORK/empty.json"
expect 1 "settings without deny rules fail" "$WORK/empty.json"

# The list itself unreadable: a copy of the repository without the file.
git clone -q "$SRC" "$WORK/clone" 2>/dev/null || cp -R "$SRC" "$WORK/clone"
cp "$SRC/scripts/factory/_protected.sh" "$WORK/clone/scripts/factory/"
cp "$CHECK" "$WORK/clone/.github/scripts/"
rm -f "$WORK/clone/.ai/rules/protected-paths.md"
rc=0; (cd "$WORK/clone" && .github/scripts/check-protected-paths.sh >/dev/null 2>&1) || rc=$?
if [ "$rc" -eq 1 ]; then echo "  ok: no list: fail closed"; else echo "::error::no list: expected exit 1, got $rc"; failures=$((failures+1)); fi

if [ "$failures" -gt 0 ]; then echo "$failures protected-path test(s) failed"; exit 1; fi
echo "All protected-path tests passed."
