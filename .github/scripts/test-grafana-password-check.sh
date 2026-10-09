#!/usr/bin/env bash
# ============================================================
# Tests docker/grafana-password-check.sh (backlog #0-94 step 2): one docker/.env
# per case, the check run with a PATH that holds only the tools it uses and a
# stub curl (Grafana "up" at once, its admin:admin answer set per case), so no
# case waits for a real Grafana. Each case names a fragment its output must
# contain, or "-" for no warning at all.
#
# Run by the build-and-test job in .github/workflows/ci.yml; locally:
#   .github/scripts/test-grafana-password-check.sh
# ============================================================
set -euo pipefail

CHECK=$(cd "$(dirname "$0")/../.." && pwd)/docker/grafana-password-check.sh
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failures=0
n=0

# The tools the check needs, by their real paths (not shell functions or
# aliases), in two PATHs: with the stub curl and without curl at all.
mkdir -p "$WORK/bin" "$WORK/bin-no-curl"
for tool in sed grep tail tr seq sleep; do
    real=$(type -P "$tool")
    ln -s "$real" "$WORK/bin/$tool"
    ln -s "$real" "$WORK/bin-no-curl/$tool"
done
cat > "$WORK/bin/curl" <<'EOF'
#!/bin/bash
# Stub: /api/health answers at once; a login with -u answers $FAKE_ORG_CODE.
for arg in "$@"; do
    case "$arg" in
        */api/health) exit 0 ;;
        -u) printf '%s' "${FAKE_ORG_CODE:-401}"; exit 0 ;;
    esac
done
exit 7
EOF
chmod +x "$WORK/bin/curl"

LENGTH="ma mniej niż 16 znaków"
DOLLAR="zawiera \$ poza pojedynczymi cudzysłowami"
UNREADABLE="Nie umiem odczytać"
ADMIN="nadal przyjmuje admin/admin"

# case_ <name> <expected fragment|-> <env file content|NONE> [VAR=value ...]
# Extra VAR=value pairs go to the check's environment; FROM_SHELL=<value> sets
# GRAFANA_ADMIN_PASSWORD there, NO_CURL=1 runs it without curl.
case_() {
    local name=$1 expect=$2 content=$3; shift 3
    local env_file="$WORK/env$((n += 1))" path="$WORK/bin" out
    local -a vars=()
    [ "$content" = NONE ] || printf '%b' "$content" > "$env_file"
    for kv in "$@"; do
        case "$kv" in
            FROM_SHELL=*) vars+=("GRAFANA_ADMIN_PASSWORD=${kv#FROM_SHELL=}") ;;
            NO_CURL=1) path="$WORK/bin-no-curl" ;;
            *) vars+=("$kv") ;;
        esac
    done
    # Never inherited from the caller's shell: the case decides.
    out=$(env -u GRAFANA_ADMIN_PASSWORD PATH="$path" GRAFANA_ENV_FILE="$env_file" \
        GRAFANA_URL=http://grafana.invalid "${vars[@]+"${vars[@]}"}" \
        /bin/bash "$CHECK" 2>&1) || { echo "FAIL [$name]: exited non-zero"; failures=$((failures + 1)); return; }
    if [ "$expect" = - ]; then
        if [ -n "$out" ]; then
            echo "FAIL [$name]: expected no warning, got:"; echo "$out"; failures=$((failures + 1))
        else
            echo "ok   [$name]"
        fi
    elif grep -qF -- "$expect" <<<"$out"; then
        echo "ok   [$name]"
    else
        echo "FAIL [$name]: expected '$expect', got:"; echo "${out:-<nothing>}"; failures=$((failures + 1))
    fi
}

LONG=abcdefghijklmnopqrstuvwxyz

case_ "short value" "$LENGTH" "GRAFANA_ADMIN_PASSWORD=short\n"
case_ "long value" - "GRAFANA_ADMIN_PASSWORD=$LONG\n"
case_ "empty value: compose's :? refuses it, not this check" - "GRAFANA_ADMIN_PASSWORD=\n"
case_ "no line" - "DB_PASSWORD=x\n"
case_ "no file" - NONE
case_ "not the first line" "$LENGTH" "DB_PASSWORD=x\nGRAFANA_ADMIN_PASSWORD=short\n"
case_ "last line wins" - "GRAFANA_ADMIN_PASSWORD=short\nGRAFANA_ADMIN_PASSWORD=$LONG\n"
case_ "UTF-8 BOM, short" "$LENGTH" "\357\273\277GRAFANA_ADMIN_PASSWORD=short\n"
case_ "UTF-8 BOM, long" - "\357\273\277GRAFANA_ADMIN_PASSWORD=$LONG\n"
case_ "export, spaces, comment, CRLF" - "export GRAFANA_ADMIN_PASSWORD = $LONG # c\r\n"
case_ "unquoted comment not counted" "$LENGTH" "GRAFANA_ADMIN_PASSWORD=short # a long comment here\n"
case_ "double-quoted short" "$LENGTH" "GRAFANA_ADMIN_PASSWORD=\"short\" # c\n"
case_ "double-quoted # kept" - "GRAFANA_ADMIN_PASSWORD=\"abcdefgh #ijklmnop\"\n"
case_ "unterminated quote" "$UNREADABLE" "GRAFANA_ADMIN_PASSWORD=\"$LONG\n"
case_ "unquoted \$: compose interpolates" "$DOLLAR" "GRAFANA_ADMIN_PASSWORD=ab\$cdefghijklmnopqrs\n"
case_ "double-quoted \${}: compose interpolates" "$DOLLAR" "GRAFANA_ADMIN_PASSWORD=\"ab\${X}cd\"\n"
case_ "single-quoted \$ is literal" "$LENGTH" "GRAFANA_ADMIN_PASSWORD='ab\$c'\n"
case_ "single-quoted long" - "GRAFANA_ADMIN_PASSWORD='$LONG'\n"
case_ "shell variable wins over a long file value" "z powłoki" "GRAFANA_ADMIN_PASSWORD=$LONG\n" FROM_SHELL=short
case_ "shell variable wins over a short file value" - "GRAFANA_ADMIN_PASSWORD=short\n" "FROM_SHELL=$LONG"
case_ "Grafana accepts admin/admin" "$ADMIN" "GRAFANA_ADMIN_PASSWORD=$LONG\n" FAKE_ORG_CODE=200
case_ "Grafana refuses admin/admin" - "GRAFANA_ADMIN_PASSWORD=$LONG\n" FAKE_ORG_CODE=401
case_ "no curl" "brak curl" "GRAFANA_ADMIN_PASSWORD=$LONG\n" NO_CURL=1

echo
if [ "$failures" -ne 0 ]; then
    echo "$failures of $n cases failed"
    exit 1
fi
echo "All $n cases passed"
