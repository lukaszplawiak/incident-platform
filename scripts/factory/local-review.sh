#!/usr/bin/env bash
# ============================================================
# EXPERIMENTAL, advisory only. A second opinion on the `general` dimension from a local model of another
# family (Ollama), to catch what Claude's own blind spots would. Its findings never block and never
# approve: the autopilot adds them to the PR as non-blocking notes, and the audit compares them with the
# panel over time.
# Usage: local-review.sh <item-id> [base-ref]     Prints JSON (the model's verdict, or {"ran":false}).
# ============================================================
set -uo pipefail
. "$(dirname "$0")/_common.sh"
item=${1:?item id}; base=${2:-$(base_ref)}
model=${FACTORY_LOCAL_MODEL:-qwen3.8:27b}
url=${OLLAMA_URL:-http://localhost:11434}
curl -fsS --max-time 5 "$url/api/tags" >/dev/null 2>&1 || { echo '{"ran":false,"reason":"Ollama not reachable"}'; exit 0; }

head_before=$(git rev-parse HEAD)
# The model gets the rules and the diff on stdin and no tool that can change anything: it only reads.
out=$( { cat .ai/rules/review/_common.md .ai/rules/review/general.md; echo; echo "=== DIFF for backlog item $item ==="; \
         git diff "$(git merge-base "$base" HEAD)"...HEAD; } | \
  ANTHROPIC_BASE_URL="$url" ANTHROPIC_AUTH_TOKEN=ollama \
  claude -p --settings .claude/settings.autopilot.json --model "$model" --output-format json \
     --disallowedTools "Edit,Write,MultiEdit,NotebookEdit,Bash,WebFetch,WebSearch,Agent" \
  "You are a code reviewer, dimension general. The rules and the diff are on stdin. Answer only with the verdict JSON defined in the rules." 2>/dev/null)
if [ "$(git rev-parse HEAD)" != "$head_before" ] || [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo '{"ran":true,"error":"the working tree changed during the local review: discard the result"}'; exit 0
fi
printf '%s' "$out" | jq -c '{ran:true, model:"'"$model"'", result:(.result // .)}' 2>/dev/null || echo '{"ran":false,"reason":"no parseable output"}'
