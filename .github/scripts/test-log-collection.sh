#!/usr/bin/env bash
# ============================================================
# Backlog #0-94, step 2: the platform's container logs reach Loki, parsed, and
# the Docker API proxy in front of Alloy stays read-only.
#
# Runs against a compose stack already up with docker-socket-proxy, loki, alloy
# and the seven services (CI: the docker-compose smoke test, after the health
# checks; locally: after `docker compose -f docker/docker-compose.yml up -d`).
# Run it from the repository root, as CI does: it reads the compose file by
# that relative path.
# Checks:
#   1. the proxy, asked from Alloy's network as Alloy asks it, answers the
#      reads Alloy needs and refuses writes (POST stop/exec/create) and other
#      reads (images, info); every network the proxy, Loki and Alloy are on is
#      internal; neither Loki nor Alloy (no authentication on either), nor
#      Grafana (which reads Loki) if it is running, can be reached from the
#      services' network, and in the compose file none of the four is on
#      default, exactly who is on logs, docker-api and grafana-ui (the first
#      two internal), only Grafana publishes a port (on 127.0.0.1), the proxy,
#      Loki and Alloy are read-only with no capabilities and no privilege
#      gain, Alloy is not root, and the pipeline has memory limits, with
#      Loki's and Alloy's GOMEMLIMIT below them;
#      Loki and Alloy answer on the logs network by the names Prometheus and
#      Grafana use;
#   2. every service has lines in Loki under its compose service name, with a
#      level label read from its ECS JSON (so discovery, relabelling and
#      stage.json all work on the services' real output; the ECS shape itself
#      is pinned by StructuredLoggingDefaultsTest in shared). It reads the last
#      30 minutes, so on a long-lived local stack lines from before a change
#      can pass it: there, trust the probe checks, which carry a per-run id;
#   3. a probe container named like a service (log-probe-service) has its
#      known line arrive with level WARN as a label and tenantId, requestId,
#      userId and kafkaMessageId as structured metadata, not labels (the
#      services log no tenant on an idle stack, so the probe stands in for
#      one), a level outside TRACE..ERROR arrives as OTHER, and a JSON line
#      with no level gets no level label;
#   4. a line that is not JSON arrives unparsed and with no level; the same
#      JSON from a container that is not a service gets no level and no
#      identifiers (only services are parsed); none of the unbounded
#      identifiers ever became a label.
# One deadline for the whole run (review: a deadline per check added up past
# the CI job's timeout when the pipeline was broken), and the first check that
# misses it fails the run. Loki and the proxy publish no port: every request
# runs in a curl container sharing the target's network namespace. Needs
# docker, python3, and the variables docker/docker-compose.yml requires (CI's
# job env, or docker/.env).
# ============================================================
set -euo pipefail

# Pinned by digest: both run inside the pipeline's network namespaces.
CURL_IMAGE=curlimages/curl:8.16.0@sha256:463eaf6072688fe96ac64fa623fe73e1dbe25d8ad6c34404a669ad3ce1f104b6
PROBE_IMAGE=alpine:3.22@sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8
PROBE=incident-log-probe
OTHER_PROBE=incident-log-probe-other
# Unique per run, so a line left in Loki by an earlier run never passes.
RUN_ID="run-$(date +%s)-$$"
# The two helper images are pulled first, so a slow registry does not eat
# into the deadline that is meant for the pipeline.
for image in "$CURL_IMAGE" "$PROBE_IMAGE"; do
    docker pull -q "$image" >/dev/null \
        || { echo "::error::Log collection: could not pull $image (backlog #0-94)"; exit 1; }
done
DEADLINE=$(( $(date +%s) + ${LOG_COLLECTION_DEADLINE_SECONDS:-240} ))
SERVICES="auth-service ingestion-service incident-service notification-service escalation-service postmortem-service oncall-service"

fail() {
    echo "::error::Log collection: $1 (backlog #0-94)"
    exit 1
}

# loki <path> [curl args...]: GET on Loki's HTTP API, body on stdout.
loki() {
    local path=$1
    shift
    # --max-time: a Loki that accepts the connection and never answers must
    # not hold the run past its deadline.
    docker run --rm --network container:incident-loki "$CURL_IMAGE" -sfG --max-time 10 "http://localhost:3100$path" "$@"
}

# within <description> <command...>: retries the command every 5 s until it
# succeeds; fails the run once the shared deadline has passed.
within() {
    local description=$1
    shift
    until "$@"; do
        if [ "$(date +%s)" -ge "$DEADLINE" ]; then
            fail "$description (deadline of the run passed)"
        fi
        sleep 5
    done
    echo "OK: $description"
}

# --- 0. Probe containers --------------------------------------------------

cleanup() {
    docker rm -f "$PROBE" "$OTHER_PROBE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Started first (review: started after the other checks, on a slow runner
# the probes got only the deadline's last seconds), so they are discovered
# and shipped while the checks below run. Named incident-* so Alloy keeps
# them; running until removed, as discovery lists running containers only.
# The same lines from a "service" and from a container that is not one,
# repeated, so a line written before Alloy found the container is not the
# only chance.
cleanup
for probe in "$PROBE:log-probe-service" "$OTHER_PROBE:log-probe-other"; do
    docker run -d --name "${probe%%:*}" --label com.docker.compose.service="${probe#*:}" -e RUN_ID="$RUN_ID" "$PROBE_IMAGE" sh -c '
while true; do
  echo "{\"log\":{\"level\":\"WARN\"},\"tenantId\":\"smoke-tenant\",\"requestId\":\"smoke-request\",\"userId\":\"smoke-user\",\"kafkaMessageId\":\"smoke-message\",\"message\":\"log-probe-json-$RUN_ID\"}"
  echo "{\"log\":{\"level\":\"NOT-A-LEVEL\"},\"message\":\"log-probe-badlevel-$RUN_ID\"}"
  echo "{\"message\":\"log-probe-nolevel-$RUN_ID\"}"
  echo "log-probe-plain-$RUN_ID"
  sleep 5
done' >/dev/null
done

# --- 1. The Docker API proxy ---------------------------------------------

# proxy_status <method> <path>: HTTP status of a request to the proxy, sent
# from Alloy's network namespace by the name Alloy uses.
proxy_status() {
    docker run --rm --network container:incident-alloy "$CURL_IMAGE" \
        -s -o /dev/null -w '%{http_code}' --max-time 5 -X "$1" "http://docker-socket-proxy:2375$2"
}

expect_proxy() {
    local expected=$1 method=$2 path=$3 status
    status=$(proxy_status "$method" "$path") \
        || fail "docker-socket-proxy did not answer $method $path (status ${status:-none}): is it running?"

    [ "$status" = "$expected" ] \
        || fail "docker-socket-proxy answered $status to $method $path, expected $expected"
}

expect_proxy 200 GET /containers/json
expect_proxy 200 GET /networks
# Writes aimed at a container that does not exist: refused, they are 403; let
# through, 404 (a mistake in the proxy's configuration must not make this
# check stop a real container, as it did in review).
expect_proxy 403 POST /containers/incident-no-such-container/stop
expect_proxy 403 POST /containers/incident-no-such-container/exec
expect_proxy 403 POST "/containers/create?name=incident-no-such-container"
expect_proxy 403 GET /images/json
expect_proxy 403 GET /info
echo "OK: docker-socket-proxy allows the reads Alloy needs and refuses writes and other reads"

# networks_internal <container>: every network the container is on is internal.
networks_internal() {
    local container=$1 networks network
    networks=$(docker inspect -f '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}} {{end}}' "$container")
    [ -n "$networks" ] || fail "$container is on no network"
    for network in $networks; do
        [ "$(docker network inspect -f '{{.Internal}}' "$network")" = "true" ] \
            || fail "$container is on network $network, which is not internal"
    done
    echo "OK: $container is on internal networks only ($networks)"
}

networks_internal incident-docker-socket-proxy
networks_internal incident-loki
networks_internal incident-alloy

# From the network the services are on, Loki and Alloy must not answer: both
# serve their API without authentication.
services_network=$(docker inspect -f '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}} {{end}}' incident-auth-service | awk '{print $1}')
[ -n "$services_network" ] || fail "incident-auth-service is on no network"
unreachable_targets="http://loki:3100/ready http://alloy:12345/-/ready"
# Grafana is not part of the smoke test's stack; checked when it runs.
if [ "$(docker inspect -f '{{.State.Running}}' incident-grafana 2>/dev/null)" = "true" ]; then
    unreachable_targets="$unreachable_targets http://grafana:3000/api/health"
fi
for target in $unreachable_targets; do
    # Only curl's "could not resolve" (6), "could not connect" (7) or timeout
    # (28) mean unreachable; any other failure (an image that did not pull, a
    # docker error) must not pass for isolation.
    set +e
    docker run --rm --network "$services_network" "$CURL_IMAGE" -s -o /dev/null --max-time 5 "$target"
    rc=$?
    set -e
    case "$rc" in
        6|7|28) ;;
        0) fail "$target answers from the services' network $services_network" ;;
        *) fail "could not check $target from the services' network $services_network (exit $rc)" ;;
    esac
done
echo "OK: cannot be reached from the services' network ($services_network): $unreachable_targets"

# The same rule in the compose file itself, for Grafana too when it is not
# running, and the hardening nothing else would notice losing: who is on the
# logs, docker-api and grafana-ui networks, exactly, and that the first two
# are internal; none of the four containers that see the logs is on default;
# none but Grafana publishes a port, and Grafana only on 127.0.0.1; the
# proxy, Loki and Alloy are read-only, with no capabilities and no privilege
# gain, and Alloy is not root; the three have a memory limit, and Loki's and
# Alloy's GOMEMLIMIT is below theirs.
docker compose -f docker/docker-compose.yml config --format json | python3 -c '
import json, sys
config = json.load(sys.stdin)
services = config["services"]
problems = []
# Exactly who is on each network that reaches the logs or the Docker API: a
# service, kafka-ui or pgAdmin added to one of them could read every tenant
# (Loki and Alloy answer without authentication) or ask the Docker API.
expected = {
    "logs": {"loki", "alloy", "grafana", "prometheus"},
    "docker-api": {"docker-socket-proxy", "alloy"},
    "grafana-ui": {"grafana"},
}
for network, members in expected.items():
    actual = {name for name, svc in services.items() if network in (svc.get("networks") or {})}
    if actual != members:
        problems.append(network + " has " + ", ".join(sorted(actual)) + "; expected " + ", ".join(sorted(members)))
for network in ("logs", "docker-api"):
    if (config.get("networks") or {}).get(network, {}).get("internal") is not True:
        problems.append("network " + network + " is not internal")
for name in ("docker-socket-proxy", "loki", "alloy", "grafana"):
    if "default" in (services[name].get("networks") or {"default": None}):
        problems.append(name + " is on the default network")
for name in ("docker-socket-proxy", "loki", "alloy"):
    if services[name].get("ports"):
        problems.append(name + " publishes a port")
    if not services[name].get("mem_limit"):
        problems.append(name + " has no mem_limit")
for port in services["grafana"].get("ports") or []:
    if port.get("host_ip") != "127.0.0.1":
        problems.append("grafana publishes " + str(port.get("published")) + " beyond 127.0.0.1")
for name in ("docker-socket-proxy", "loki", "alloy"):
    svc = services[name]
    if svc.get("read_only") is not True:
        problems.append(name + " is not read_only")
    if "ALL" not in (svc.get("cap_drop") or []):
        problems.append(name + " does not drop ALL capabilities")
    if not any(o.replace(" ", "") in ("no-new-privileges:true", "no-new-privileges") for o in svc.get("security_opt") or []):
        problems.append(name + " lacks no-new-privileges")
if str(services["alloy"].get("user", "")).split(":")[0] in ("", "0", "root"):
    problems.append("alloy runs as root")
# GOMEMLIMIT must sit below mem_limit, or the GC lets the heap reach the
# cgroup limit and the container is OOM-killed instead of collecting.
units = {"KiB": 1024, "MiB": 1024**2, "GiB": 1024**3}
for name in ("loki", "alloy"):
    svc = services[name]
    go_limit = (svc.get("environment") or {}).get("GOMEMLIMIT") or ""
    unit = next((u for u in units if go_limit.endswith(u)), None)
    if not unit or not svc.get("mem_limit"):
        problems.append(name + " needs GOMEMLIMIT (in KiB/MiB/GiB) and mem_limit")
    elif float(go_limit[:-3]) * units[unit] >= int(svc["mem_limit"]):
        problems.append(name + " GOMEMLIMIT " + go_limit + " is not below its mem_limit")
if problems:
    print("::error::Log collection: docker-compose.yml: " + "; ".join(problems) + " (backlog #0-94)")
    sys.exit(1)' || exit 1
echo "OK: docker-compose.yml keeps the log pipeline off default, its ports closed and the proxy, Loki and Alloy hardened"

# By the names and ports Prometheus scrapes and Grafana's datasource uses.
# Retried to the run's deadline: Loki has no compose healthcheck (distroless),
# so `up --wait` returns before its /ready does (503 for its first ~15 s).
logs_network=$(docker inspect -f '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}} {{end}}' incident-loki | awk '{print $1}')
answers_on_logs_network() {
    docker run --rm --network "$logs_network" "$CURL_IMAGE" -sf -o /dev/null --max-time 5 "$1"
}
for target in http://loki:3100/ready http://alloy:12345/-/ready; do
    within "$target answers on the logs network ($logs_network)" answers_on_logs_network "$target"
done

# --- 2. Every service's lines, parsed ------------------------------------

loki_ready() {
    [ "$(loki /ready 2>/dev/null)" = "ready" ]
}

# One query for all services: prints the services with no line carrying a
# level label in the last 30 minutes (empty when every one has some).
services_without_parsed_lines() {
    loki /loki/api/v1/query \
        --data-urlencode 'query=sum by (service) (count_over_time({service=~".+-service", level=~".+"}[30m]))' \
        | SERVICES="$SERVICES" python3 -c '
import json, os, sys
found = {r["metric"].get("service") for r in json.load(sys.stdin)["data"]["result"]}
print(" ".join(sorted(set(os.environ["SERVICES"].split()) - found)))'
}

within "Loki is ready" loki_ready
until missing=$(services_without_parsed_lines) && [ -z "$missing" ]; do
    if [ "$(date +%s)" -ge "$DEADLINE" ]; then
        fail "no line with a level in Loki for: ${missing:-every service (query failed)} (deadline of the run passed)"
    fi
    sleep 5
done
echo "OK: all seven services' lines are in Loki with a level"

# --- 3 and 4. Probe lines ------------------------------------------------

# The probe's JSON line: level a label, tenantId and requestId structured
# metadata (categorize-labels splits the two in the answer).
probe_json_line_parsed() {
    loki /loki/api/v1/query_range \
        -H 'X-Loki-Response-Encoding-Flags: categorize-labels' \
        --data-urlencode "query={service=\"log-probe-service\"} |= \"log-probe-json-$RUN_ID\"" \
        --data-urlencode 'since=30m' \
        | python3 -c '
import json, sys
streams = json.load(sys.stdin)["data"]["result"]
for stream in streams:
    labels = stream["stream"]
    for value in stream["values"]:
        metadata = value[2].get("structuredMetadata", {}) if len(value) > 2 else {}
        if (labels.get("level") == "WARN"
                and not {"tenantId", "requestId", "userId", "kafkaMessageId"} & set(labels)
                and metadata.get("tenantId") == "smoke-tenant"
                and metadata.get("requestId") == "smoke-request"
                and metadata.get("userId") == "smoke-user"
                and metadata.get("kafkaMessageId") == "smoke-message"):
            sys.exit(0)
sys.exit(1)'
}

probe_plain_line_unparsed() {
    loki /loki/api/v1/query_range \
        --data-urlencode "query={service=\"log-probe-service\"} |= \"log-probe-plain-$RUN_ID\"" \
        --data-urlencode 'since=30m' \
        | python3 -c '
import json, sys
streams = json.load(sys.stdin)["data"]["result"]
sys.exit(0 if streams and all("level" not in s["stream"] for s in streams) else 1)'
}

# A level outside TRACE..ERROR from a service arrives as OTHER.
probe_bad_level_is_other() {
    loki /loki/api/v1/query_range \
        --data-urlencode "query={service=\"log-probe-service\", level=\"OTHER\"} |= \"log-probe-badlevel-$RUN_ID\"" \
        --data-urlencode 'since=30m' \
        | python3 -c '
import json, sys
sys.exit(0 if json.load(sys.stdin)["data"]["result"] else 1)'
}

# JSON from a service with no log.level gets no level label (not "" nor OTHER).
probe_no_level_json_unlabelled() {
    loki /loki/api/v1/query_range \
        --data-urlencode "query={service=\"log-probe-service\"} |= \"log-probe-nolevel-$RUN_ID\"" \
        --data-urlencode 'since=30m' \
        | python3 -c '
import json, sys
streams = json.load(sys.stdin)["data"]["result"]
sys.exit(0 if streams and all("level" not in s["stream"] for s in streams) else 1)'
}

# The same JSON from a container that is not a service: arrives, unparsed.
other_json_line_unparsed() {
    loki /loki/api/v1/query_range \
        -H 'X-Loki-Response-Encoding-Flags: categorize-labels' \
        --data-urlencode "query={service=\"log-probe-other\"} |= \"log-probe-json-$RUN_ID\"" \
        --data-urlencode 'since=30m' \
        | python3 -c '
import json, sys
streams = json.load(sys.stdin)["data"]["result"]
if not streams:
    sys.exit(1)
for stream in streams:
    if "level" in stream["stream"]:
        sys.exit(1)
    for value in stream["values"]:
        metadata = value[2].get("structuredMetadata", {}) if len(value) > 2 else {}
        if {"tenantId", "requestId", "userId", "kafkaMessageId"} & set(metadata):
            sys.exit(1)
sys.exit(0)'
}

# Loki's label list holds index labels only, not structured metadata: this
# catches an identifier turned into a label (a stream per value), which is
# its purpose; that each one is metadata is probe_json_line_parsed's check.
no_identifier_label() {
    loki /loki/api/v1/labels | python3 -c '
import json, sys
labels = json.load(sys.stdin)["data"]
sys.exit(1 if set(labels) & {"tenantId", "requestId", "userId", "kafkaMessageId"} else 0)'
}

within "a JSON line has its level as a label, tenantId, requestId, userId and kafkaMessageId as structured metadata" probe_json_line_parsed
within "a level outside TRACE..ERROR arrives as OTHER" probe_bad_level_is_other
within "a service's JSON line with no level gets no level label" probe_no_level_json_unlabelled
within "a line that is not JSON arrives unparsed" probe_plain_line_unparsed
within "JSON from a container that is not a service is not parsed" other_json_line_unparsed
no_identifier_label || fail "tenantId, requestId, userId or kafkaMessageId became a Loki label"
echo "OK: no unbounded identifier is a label"
