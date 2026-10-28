# ADR-0045: No reviewer renders or validates the Kubernetes manifests; CI does

- **Status:** Proposed
- **Backlog:** #0-182 (#0-183 is the way back)
- **Date:** 2026-10-27
- **Author:** human (owner's decision B in #0-182, drafted in an interactive session)
- **Reversible:** yes — #0-183 describes a render that could return

## Context

`readonly-bash.sh --k8s` let `review-k8s` run `kubectl kustomize` and `kubeconform` once their first words matched,
and `settings.autopilot.json` allowed both by wildcard. On kubectl 1.36.1 (kustomize 5.8.1) a render read what git
does not track and what no reviewer may read: `-o` wrote a file, `--enable-helm --helm-command <program>` ran it,
and with no option at all a kustomization root or base above `k8s/` printed the gitignored `docker/.env`. A guard of
the allowed forms (`kustomize_unsafe`, on `git_grep_unsafe`'s model) was written and reviewed twice; each round found
a form it missed, every one confirmed by a test: the shell's `~+` and `a=~`, YAML's `"../"` escapes and
`!!binary` tag, kustomize's `github.com/o/r//dir?ref=` shorthand, an upper-case `KUSTOMIZATION.YML` on a
case-insensitive APFS, gitignored files under `k8s/`. The devcontainer of the autopilot has no kubectl, so only the
owner's manual review ever rendered.

## Decision

No reviewer runs `kubectl` or `kubeconform`. `readonly-bash.sh` has no `--k8s` mode and refuses a mode it does not
know; `review-k8s` runs the plain hook in its frontmatter and is in the plain session-level twin of both settings
files (ADR-0041); no allow rule names either tool. The k8s reviewer reads manifests, Dockerfiles and POMs as files.
CI renders base and the three overlays and validates them (K8S-03, ADR-0027), and its structural checks read those
renders (K8S-04, K8S-20…K8S-24).

## Alternatives considered

- **An allow-list of `kubectl kustomize`'s forms plus a scan of every kustomization under `k8s/`** (`/research`'s
  A + E2): built, tested with 28 cases and mutations, and bypassed in both review rounds. It predicts what kustomize
  will read; YAML and kustomize have more ways to spell a path than the scan models.
- **Keep patching that guard** (no `!`, `-iname`, a character allow-list for the directory, no ignored files): each
  fix was small, but two rounds showed the next form, and every kustomize release could add one.
- **Render a tracked-only snapshot with no network** (copy the tracked or new, not ignored files of `k8s/` to a
  temporary directory, refuse symlinks, cut the network): sound, as Argo CD and Flux confine their renders, but more
  work than this item; it is #0-183.

## Consequences

- The k8s reviewer has the common reviewer's commands; a manual review and the autopilot judge k8s changes alike.
- An overlay's patches over a base are read by hand; CI's render is what shows the result.
- A frontmatter or settings twin that still passes `--k8s`, or a typo of a mode, refuses every Bash call it guards
  (fail closed, FAC-S04); `check-hook-entries.rb` keeps twins and frontmatter equal.
- A reviewer recognises a violation as a `kubectl`, `kubeconform` or other renderer in a hook mode, an allow rule,
  a skill's `allowed-tools` or an agent's instructions, without #0-183's snapshot.
