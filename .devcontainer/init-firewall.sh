#!/usr/bin/env bash
# ============================================================
# Outbound firewall of the factory's devcontainer: default deny, then an allow-list. Modelled on the
# reference devcontainer of Claude Code (github.com/anthropics/claude-code, .devcontainer/), adapted to
# this project. Run as root at container start (postStartCommand). Needs NET_ADMIN and NET_RAW.
#
# Allowed: DNS to the container's own resolver only (no direct queries to outside resolvers); the
# Anthropic API and login; GitHub (its published IP ranges); Maven Central; the Docker host (Testcontainers'
# mapped ports, and Ollama on the host for the optional local model); extra hosts given as arguments (the
# ntfy server, when notifications are on). Everything else from this container is rejected.
#
# Not covered, by design of this setup: the host's Docker daemon is reachable (Testcontainers), and a
# container it starts has the host's network, not this firewall. Data can also leave through DNS queries
# the resolver forwards, and through the allowed hosts themselves (a push to GitHub, a PR body). The
# firewall narrows exfiltration; it does not make it impossible (docs/ai-factory.md, "What isolates what").
# Domains are resolved once, at start: a CDN that moves IPs needs a container restart.
#
# Usage: init-firewall.sh [extra-host ...]
# ============================================================
set -euo pipefail

ALLOWED_DOMAINS=(
    api.anthropic.com claude.ai platform.claude.com console.anthropic.com statsig.anthropic.com
    repo.maven.apache.org repo1.maven.org
)
for extra in "$@"; do
    case "$extra" in
        *[!A-Za-z0-9.-]*|'') echo "ignored extra host: $extra" >&2 ;;
        *) ALLOWED_DOMAINS+=("$extra") ;;
    esac
done

# Only the filter table is reset. The nat table is left alone: Docker's embedded DNS (127.0.0.11) lives
# there, and flushing it would break name resolution in the container.
iptables -F; iptables -X
ipset destroy allowed 2>/dev/null || true

# DNS to the configured resolver(s) only, and loopback, so the resolution below works.
resolvers=$(awk '/^nameserver/ {print $2}' /etc/resolv.conf | grep -E '^[0-9.]+$' || true)
[ -n "$resolvers" ] || { echo "no IPv4 nameserver in /etc/resolv.conf" >&2; exit 1; }
for r in $resolvers; do
    iptables -A OUTPUT -p udp -d "$r" --dport 53 -j ACCEPT
    iptables -A OUTPUT -p tcp -d "$r" --dport 53 -j ACCEPT
    iptables -A INPUT  -p udp -s "$r" --sport 53 -j ACCEPT
done
iptables -A INPUT  -i lo -j ACCEPT
iptables -A OUTPUT -o lo -j ACCEPT

ipset create allowed hash:net

# GitHub publishes its ranges; take web, api and git (IPv4).
gh_meta=$(curl -fsS --max-time 15 https://api.github.com/meta) || { echo "could not fetch GitHub meta" >&2; exit 1; }
printf '%s' "$gh_meta" | jq -r '(.web + .api + .git)[] | select(test(":") | not)' | sort -u | while read -r cidr; do
    ipset add allowed "$cidr" -exist
done

for domain in "${ALLOWED_DOMAINS[@]}"; do
    ips=$(dig +short A "$domain" | grep -E '^[0-9.]+$' || true)
    [ -n "$ips" ] || { echo "could not resolve $domain" >&2; exit 1; }
    for ip in $ips; do ipset add allowed "$ip" -exist; done
done

# The Docker host: Testcontainers' mapped ports and Ollama.
host_ip=$(getent ahostsv4 host.docker.internal | awk 'NR==1{print $1}' || true)
[ -n "$host_ip" ] && ipset add allowed "$host_ip" -exist
gw=$(ip route | awk '/default/ {print $3; exit}')
[ -n "$gw" ] && ipset add allowed "$gw" -exist

iptables -P INPUT DROP
iptables -P FORWARD DROP
iptables -P OUTPUT DROP
iptables -A INPUT  -m state --state ESTABLISHED,RELATED -j ACCEPT
iptables -A OUTPUT -m state --state ESTABLISHED,RELATED -j ACCEPT
iptables -A OUTPUT -m set --match-set allowed dst -j ACCEPT
iptables -A OUTPUT -j REJECT --reject-with icmp-admin-prohibited

# Self-test: one allowed, one denied.
curl -fsS --max-time 5 https://api.github.com/zen >/dev/null || { echo "firewall self-test: GitHub not reachable" >&2; exit 1; }
if curl -fsS --max-time 5 https://example.com >/dev/null 2>&1; then
    echo "firewall self-test: example.com is reachable, the allow-list is not applied" >&2; exit 1
fi
echo "Firewall up: $(ipset list allowed | grep -c '^[0-9]') allowed networks."
