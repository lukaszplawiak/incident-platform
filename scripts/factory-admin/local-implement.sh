#!/usr/bin/env bash
# ============================================================
# EXPERIMENTAL, owner-run (scripts/factory-admin/: autopilot sessions may not run it). Implements one `Complexity: low` item with a local model (Ollama) while the Claude
# limit is exhausted, so the waiting time is not wasted. Claude Code itself is the harness, pointed at
# Ollama's Anthropic-compatible endpoint; nothing here reviews or publishes:
#   - the branch stays local (no push, no PR);
#   - review happens later, by the normal panel: /backlog-autopilot with args
#     {"item":"#0-N","branch":"<branch>"} once the limit has reset.
# Usage: local-implement.sh <item-id> <branch-name>
# Env: FACTORY_LOCAL_MODEL (default qwen3.8:27b), OLLAMA_URL (default http://localhost:11434;
#      from the devcontainer http://host.docker.internal:11434, which its firewall must allow).
# Verify before relying on it: model name in `ollama list`, memory headroom with the test stack up.
# ============================================================
set -uo pipefail
. "$(dirname "$0")/../factory/_common.sh"

item=${1:?item id, e.g. "#0-58"}; branch=${2:?branch, e.g. fix/0-58-short-slug}
model=${FACTORY_LOCAL_MODEL:-qwen3.8:27b}
url=${OLLAMA_URL:-http://localhost:11434}

case "${item#\#}" in 0-[0-9]*) ;; *) echo '{"ok":false,"reason":"item id must look like #0-N"}'; exit 1 ;; esac
grep -A3 -E "^### ${item#\#}\." BACKLOG.md | grep -q 'Complexity:\*\* low' \
    || { echo "{\"ok\":false,\"reason\":\"$item is not marked Complexity: low\"}"; exit 1; }
curl -fsS --max-time 5 "$url/api/tags" >/dev/null || { echo "{\"ok\":false,\"reason\":\"Ollama not reachable at $url\"}"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo '{"ok":false,"reason":"working tree is not clean"}'; exit 1; }

git switch -c "$branch" "$(base_ref)" || exit 1
ANTHROPIC_BASE_URL="$url" ANTHROPIC_AUTH_TOKEN=ollama \
claude -p --settings .claude/settings.autopilot.json --agent implementer --model "$model" \
  "Implement backlog item $item on the current branch, following .ai/rules/implementation.md. \
Create .ai/work/<item>/ from .ai/work/_template/, commit locally, do not push. \
Run ./mvnw -B -ntp verify for the affected modules and stop when it passes or after 3 attempts." \
  > "$RUNS_DIR/local-implement-$(date -u +%Y%m%d-%H%M%S).log" 2>&1
rc=$?
echo "{\"ok\":$([ $rc -eq 0 ] && echo true || echo false),\"branch\":\"$branch\",\"next\":\"review with /backlog-autopilot args {item:'$item', branch:'$branch'}\"}"
