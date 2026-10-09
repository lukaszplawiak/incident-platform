#!/usr/bin/env bash
# ============================================================
# Backlog #0-94 step 2: warns about a Grafana that reads every tenant's logs
# with a weak or default admin password. Run by `make dev-up` (and
# `make grafana-password-check`) from the repository root. Warns only; never
# changes the password and never fails the make target.
#
#   1. GRAFANA_ADMIN_PASSWORD shorter than 16 characters. compose's `:?`
#      refuses only an empty one. Read as compose reads it: the shell's
#      variable first, then docker/.env (`export`, spaces around `=`, quotes
#      and an unquoted ` # comment` handled the way compose does, a UTF-8 BOM
#      some Windows editors write stripped). A line it cannot read is said
#      so, not guessed at; so is a value with `$` outside single quotes,
#      which compose interpolates, so its length here is not Grafana's.
#   2. Grafana still accepting admin/admin: GF_SECURITY_ADMIN_PASSWORD applies
#      only when the grafana_data volume is created, so a volume from before
#      the variable keeps `admin`. This is one admin:admin login per run; with
#      a good password that is one failed attempt, and Grafana locks an
#      account only after 5 in 5 minutes. Another user name cannot tell
#      whether admin's password is still admin. It waits up to 2 minutes for
#      Grafana to answer (a first start migrates its database), then says it
#      did not check rather than failing.
#
# The reset command it suggests puts the password in the shell's history:
# fine on a developer's machine, or start it with a space under
# HISTCONTROL=ignorespace.
# ============================================================
set -uo pipefail

ENV_FILE=${GRAFANA_ENV_FILE:-docker/.env}
GRAFANA_URL=${GRAFANA_URL:-http://localhost:3000}

BOM=$(printf '\357\273\277')

# env_value: the value of GRAFANA_ADMIN_PASSWORD's last line in $ENV_FILE as
# compose reads it, on stdout; exit 2 when the line exists but cannot be read,
# 3 when compose would interpolate it (a `$` outside single quotes), 1 when
# there is none.
env_value() {
    local line value
    [ -f "$ENV_FILE" ] || return 1
    line=$(LC_ALL=C sed "1s/^$BOM//" "$ENV_FILE" \
        | grep -E '^[[:space:]]*(export[[:space:]]+)?GRAFANA_ADMIN_PASSWORD[[:space:]]*=' | tail -1 | tr -d '\r')
    [ -n "$line" ] || return 1
    value=${line#*=}
    value="${value#"${value%%[![:space:]]*}"}"
    case "$value" in
        \'*\'*) value=${value#\'}; value=${value%%\'*}; printf '%s' "$value"; return 0 ;;
        \"*\"*) value=${value#\"}; value=${value%%\"*} ;;
        \"*|\'*) return 2 ;;
        *)
            value=${value%%[[:space:]]#*}
            value="${value%"${value##*[![:space:]]}"}"
            ;;
    esac
    case "$value" in
        *'$'*) return 3 ;;
    esac
    printf '%s' "$value"
}

if [ -n "${GRAFANA_ADMIN_PASSWORD+set}" ]; then
    password=$GRAFANA_ADMIN_PASSWORD
    source_name="GRAFANA_ADMIN_PASSWORD z powłoki"
    status=0
else
    password=$(env_value)
    status=$?
    source_name="GRAFANA_ADMIN_PASSWORD w $ENV_FILE"
fi

if [ "$status" -eq 2 ]; then
    echo "⚠ Nie umiem odczytać GRAFANA_ADMIN_PASSWORD z $ENV_FILE (niezamknięty cudzysłów?): długości hasła nie sprawdzam"
elif [ "$status" -eq 3 ]; then
    echo "⚠ GRAFANA_ADMIN_PASSWORD w $ENV_FILE zawiera \$ poza pojedynczymi cudzysłowami, a compose go podstawia:"
    echo "  długości hasła nie sprawdzam; weź je w '...' albo wygeneruj bez \$ (openssl rand -base64 24)"
elif [ "$status" -eq 0 ] && [ -n "$password" ] && [ "${#password}" -lt 16 ]; then
    echo "⚠ $source_name ma mniej niż 16 znaków, a Grafana czyta logi wszystkich tenantów:"
    echo "  wygeneruj nowe przez openssl rand -base64 24"
fi

if ! command -v curl >/dev/null; then
    echo "⚠ brak curl: nie sprawdzam, czy Grafana przyjmuje admin/admin"
    exit 0
fi

up=
for _ in $(seq 1 60); do
    if curl -sf -o /dev/null --max-time 2 "$GRAFANA_URL/api/health"; then
        up=1
        break
    fi
    sleep 2
done
if [ -z "$up" ]; then
    echo "⚠ Grafana jeszcze nie odpowiada, hasła nie sprawdzono: uruchom później make grafana-password-check"
    exit 0
fi

if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -u admin:admin "$GRAFANA_URL/api/org")" = "200" ]; then
    echo "⚠ Grafana nadal przyjmuje admin/admin (wolumen sprzed GRAFANA_ADMIN_PASSWORD), a czyta logi"
    echo "  wszystkich tenantów. Ustaw hasło z docker/.env:"
    echo "  docker exec incident-grafana grafana cli admin reset-admin-password '<GRAFANA_ADMIN_PASSWORD z docker/.env>'"
fi
exit 0
