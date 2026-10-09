# ADR-0024: Logs are collected by Alloy into Loki, in docker-compose only (backlog #0-94, step 2)

- **Status:** Accepted
- **Backlog:** #0-94, #0-72
- **Source:** written by the owner in `.ai/context/project.md` ("Known Invariants and Limitations")
  on 2026-10-09, while the ADR split was in preparation; carried over verbatim. The text below is the
  original record, not rewritten into the Context / Decision / Consequences form.

## Record

`docker-socket-proxy` → Alloy (`docker/alloy/config.alloy`) → Loki (`docker/loki.yml`), read in Grafana
(provisioned datasource `Loki`, not the default). Things that are not obvious from the files:
- Alloy keeps containers by **name** (`/incident-.*`, every compose service sets `container_name`), not by
  the compose project label, which follows the directory or `-p`. A new compose service is collected only
  with an `incident-` name.
- ECS **nests** its fields: the level is `{"log":{"level":"INFO"}}`, read in `stage.json` as the path
  `log.level`; the quoted flat key `"\"log.level\""` finds nothing.
- Only services are parsed: `stage.match` on `{service=~".+-service"}` wraps the JSON, label and metadata
  stages, so a compose service whose name ends in `-service` is treated as the platform's (the smoke test's
  probe is `log-probe-service` for that reason, and `log-probe-other` checks the opposite).
- Labels are only `service`, `container`, `level`. The MDC ids are structured metadata (Loki 3,
  `allow_structured_metadata`), filtered with `| tenantId="..."`; Loki's answer merges them into the stream
  unless the request sends `X-Loki-Response-Encoding-Flags: categorize-labels`, which the smoke check uses
  to tell metadata from labels. Loki also adds `service_name` and `detected_level` on its own.
- The proxy's `CONTAINERS=1` opens every GET under `/containers`: list, logs, but also inspect (environment,
  so secrets) and archive/export (files). It cannot be narrowed further; it is still far from the socket's root.
  It runs `read_only` with `cap_drop: ALL` and `no-new-privileges`; its entrypoint renders `haproxy.cfg` into
  `/tmp`, so `/tmp` (and `/run`) are small tmpfs mounts, or it exits at once. HAProxy's client/server timeouts
  are 10m, and Alloy's long log streams through it do not get cut.
  `NETWORKS=1` is needed (Alloy's discovery lists networks); `EVENTS` is on by default in the image and
  switched off.
- Alloy's component health stays "healthy" when the proxy is down; only
  `prometheus_sd_refresh_failures_total{mechanism="docker"}` shows it (`LogDiscoveryFailing`).
- Loki's image is distroless (no shell, no wget): no compose healthcheck, and the smoke check queries it from
  a `curlimages/curl` container sharing its network namespace (`--network container:incident-loki`), as no
  port is published. Alloy's image has bash, so its healthcheck uses `/dev/tcp`.
- `.github/scripts/test-log-collection.sh` runs against a running stack (CI's smoke test, or locally). Its
  probe container's line carries a per-run id, so lines from an earlier run never pass the check; its
  per-service check reads the last 30 minutes, so locally, after changing the Alloy config, rely on the probe
  checks (a fresh CI stack has no old lines; a service idle for 30 minutes also fails it locally: restart
  it). One deadline for the whole run (`LOG_COLLECTION_DEADLINE_SECONDS`, default 240), and `--max-time` on
  every request. Its write checks against the proxy target a container that does not exist, because with the
  proxy misconfigured (`POST=1`) a write to a real one goes through: `POST .../incident-loki/stop` stops Loki.
- Networks: Loki and Alloy answer without authentication (Loki: read, push, query; Alloy: its config and
  pipeline), so they are on the internal `logs` network with only Grafana and Prometheus, never on `default`
  where the services and the UI images are; Loki's delete API is off (`deletion_mode: disabled`; retention runs
  in the compactor without it). A container on internal networks only can publish no port, which these need
  none of. Prometheus is on `default` too (the services' metrics, its port). Grafana is NOT: from `default`
  any container could log in to it and query Loki through its datasource proxy, so it is on `logs` (reaching
  both Loki and Prometheus there) and on `grafana-ui`, a non-internal network of its own that exists only to
  publish its port. Alloy is on a second internal network, `docker-api`, shared with `docker-socket-proxy`
  alone: the proxy never shares a network with Loki, Grafana or Prometheus, so only Alloy can ask it for the
  Docker API (whose container inspect still shows every container's environment). Prometheus, on `default`
  and `logs`, is the one container on both sides; it scrapes Loki and Alloy but proxies neither, so it is no
  way to their data. `test-log-collection.sh` pins the exact members of all three networks.
- Local config changes: Prometheus and Alertmanager mount single files (`prometheus.yml`, the rules,
  `alertmanager.yml`). An editor or script that replaces such a file (a new inode) leaves the container
  on the old one, so `/-/reload` fails with "no such file"; restart the container instead.
- Loki refuses a line about an hour older than the newest of its stream ("entry too far behind", HTTP 400,
  not retried), far stricter than `reject_old_samples_max_age` (7 days). Losing Alloy's positions (its
  volume, the crash loop while making it run as 473) makes it re-read old Docker logs: those lines are
  dropped and `LogsDropped` fires once. Expected, not a pipeline fault.
- Users: Loki's image runs as uid 10001, Alloy's as root although it ships an `alloy` user (473) that owns
  `/var/lib/alloy`. With `cap_drop: ALL`, root cannot write that directory (no CAP_DAC_OVERRIDE), so Alloy
  runs as `user: "473:473"`. A fresh volume takes 473's ownership from the image; one an older Alloy filled
  as root needs `chown -R 473:473` (the command is in the compose comment).
- Memory: Loki's WAL is on by default and replayed at start with a 4GB default ceiling; under the 1 GiB
  container limit that ceiling is set to 400MB, or one OOM kill could become a restart loop. Both Loki and
  Alloy run with `GOMEMLIMIT` below their `mem_limit`.
- Grafana 13 downloads app plugins from grafana.com at start unless `GF_PLUGINS_PREINSTALL_DISABLED`; a
  volume created before that keeps the ones already downloaded (`grafana cli plugins uninstall <id>`).
- `validate-monitoring-config` reads the Loki and Alloy image tags from the compose file (awk on the service's
  `image:`); only the Prometheus and Alertmanager tags are still duplicated in the job.
- Level mapping: `stage.template` turns a level outside TRACE..ERROR into `OTHER` before `stage.labels`; a
  nested `stage.match` with `stage.static_labels` on the label did not change it (tried).
- Grafana now reads tenant content, so it left #0-72's pattern: `127.0.0.1:3000`, `GRAFANA_ADMIN_PASSWORD`
  required and empty in `docker/.env.example` (a value in a public template would be a known password; compose's
  `:?` rejects an empty value too), image pinned. `GF_SECURITY_ADMIN_PASSWORD` is read only when `grafana_data` is
  created: an existing volume keeps its password (`admin` for one from before) until
  `grafana cli admin reset-admin-password`.
- Every compose service has `logging: *default-logging` (`x-logging` at the top: json-file, 3 x 10 MB). A new
  service needs the line too. Rotation loses nothing Alloy has shipped, but it bounds an Alloy outage: lines
  rotated away before Alloy is back are lost, and nothing counts them.
- Alloy registers `loki_write_dropped_entries_total` for every `reason` at 0 on start, so `increase()` in
  `LogsDropped` sees the first drop (a counter born at its first non-zero value would not). Alloy's
  `/api/v0/web/components/<id>` lists only the arguments set in the file, not the defaults, so the backoff that
  `LogsDropped`'s delay depends on is written out in `config.alloy`.
