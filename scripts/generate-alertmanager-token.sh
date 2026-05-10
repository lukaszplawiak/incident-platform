#!/usr/bin/env bash
# =============================================================================
# generate-alertmanager-token.sh
#
# Generates a JWT service token for Alertmanager to authenticate against
# ingestion-service (/api/v1/alerts endpoints with ROLE_INGESTOR).
#
# The token is identical to what AlertManagerTokenRefresher generates at
# ingestion-service startup — HS512, signed with JWT_SECRET, payload:
#   sub=alertmanager, serviceName=alertmanager, roles=[ROLE_SERVICE],
#   tenantId=system
#
# Usage:
#   export JWT_SECRET="your-64-char-minimum-secret"
#   ./scripts/generate-alertmanager-token.sh
#
#   # Or inline:
#   JWT_SECRET="..." ./scripts/generate-alertmanager-token.sh
#
#   # Custom expiry (default: 30 days):
#   JWT_SECRET="..." TOKEN_EXPIRY_DAYS=90 ./scripts/generate-alertmanager-token.sh
#
# Output:
#   Writes token to docker/secrets/alertmanager-token.txt (chmod 600)
#   Prints export command to copy into .env
#
# Run this once when setting up the environment, and again when the token
# is about to expire. Add the generated .env line to your CI/CD secrets
# manager (GitHub Actions secrets, Vault, AWS SSM) — never commit it to git.
#
# Prerequisites: Python 3 (standard library only — no pip install needed)
# =============================================================================

set -euo pipefail

# ─── Configuration ────────────────────────────────────────────────────────────

OUTPUT_FILE="${TOKEN_FILE:-docker/secrets/alertmanager-token.txt}"
EXPIRY_DAYS="${TOKEN_EXPIRY_DAYS:-30}"

# ─── Validate JWT_SECRET ──────────────────────────────────────────────────────

if [[ -z "${JWT_SECRET:-}" ]]; then
  echo ""
  echo "ERROR: JWT_SECRET is not set."
  echo ""
  echo "The token must be signed with the same secret used by ingestion-service."
  echo "Set JWT_SECRET to the value in your application-local.yml or k8s secret:"
  echo ""
  echo "  export JWT_SECRET=\"your-64-char-minimum-secret\""
  echo "  ./scripts/generate-alertmanager-token.sh"
  echo ""
  exit 1
fi

if [[ ${#JWT_SECRET} -lt 64 ]]; then
  echo ""
  echo "ERROR: JWT_SECRET must be at least 64 characters (got ${#JWT_SECRET})."
  echo "Spring Security rejects shorter secrets for HS512."
  echo ""
  exit 1
fi

# ─── Generate token via Python (no external dependencies) ─────────────────────

TOKEN=$(python3 - <<PYTHON
import base64
import hashlib
import hmac
import json
import time
import sys

secret = "${JWT_SECRET}"
expiry_days = ${EXPIRY_DAYS}

# Validate minimum secret length (matches JwtUtils constructor check)
if len(secret.encode("utf-8")) < 64:
    print("ERROR: secret too short", file=sys.stderr)
    sys.exit(1)

now = int(time.time())
expiration = now + (expiry_days * 24 * 60 * 60)

# JWT Header — HS512 matches Keys.hmacShaKeyFor() default in JwtUtils
header = {
    "alg": "HS512",
    "typ": "JWT"
}

# JWT Payload — matches generateServiceToken() in JwtUtils:
#   .subject(serviceName)
#   .claim(CLAIM_SERVICE_NAME, serviceName)
#   .claim(CLAIM_ROLES, List.of(ROLE_SERVICE))
#   .claim(CLAIM_TENANT_ID, "system")
payload = {
    "sub": "alertmanager",
    "serviceName": "alertmanager",
    "roles": ["ROLE_SERVICE"],
    "tenantId": "system",
    "iat": now,
    "exp": expiration
}

def b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("utf-8")

header_b64  = b64url_encode(json.dumps(header,  separators=(",", ":")).encode())
payload_b64 = b64url_encode(json.dumps(payload, separators=(",", ":")).encode())

signing_input = f"{header_b64}.{payload_b64}".encode("utf-8")

# HS512 signature — matches .signWith(secretKey) where secretKey is
# Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))
signature = hmac.new(
    secret.encode("utf-8"),
    signing_input,
    hashlib.sha512
).digest()

signature_b64 = b64url_encode(signature)

print(f"{header_b64}.{payload_b64}.{signature_b64}")
PYTHON
)

# ─── Write token to file ──────────────────────────────────────────────────────

mkdir -p "$(dirname "$OUTPUT_FILE")"
printf "%s" "$TOKEN" > "$OUTPUT_FILE"
chmod 600 "$OUTPUT_FILE"

# ─── Output ───────────────────────────────────────────────────────────────────

EXPIRY_DATE=$(python3 -c "
import datetime, time
exp = time.time() + ${EXPIRY_DAYS} * 86400
print(datetime.datetime.utcfromtimestamp(exp).strftime('%Y-%m-%d %H:%M UTC'))
")

echo ""
echo "✓ Alertmanager token generated successfully"
echo ""
echo "  File    : $OUTPUT_FILE"
echo "  Expires : $EXPIRY_DATE (${EXPIRY_DAYS} days)"
echo "  Subject : alertmanager"
echo "  Roles   : ROLE_SERVICE"
echo "  TenantId: system"
echo ""
echo "─────────────────────────────────────────────────────────"
echo "Add this line to your .env file (never commit to git):"
echo ""
echo "  ALERTMANAGER_INGESTOR_TOKEN=$(cat "$OUTPUT_FILE")"
echo ""
echo "─────────────────────────────────────────────────────────"
echo "Or set it directly in your shell before docker compose up:"
echo ""
echo "  export ALERTMANAGER_INGESTOR_TOKEN=\$(cat $OUTPUT_FILE)"
echo "  docker compose -f docker/docker-compose.yml up -d"
echo ""
echo "Remember to regenerate before expiry: ${EXPIRY_DATE}"
echo ""