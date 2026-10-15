# ADR-0027: The k8s job's validation inputs are pinned: kubeconform by checksum, its schemas by commit, one Kubernetes version (backlog #0-76)

- **Status:** Accepted
- **Backlog:** #0-76 (open follow-ups #0-134, #0-135)
- **Date:** 2026-10-15
- **Author:** human (owner's choice of option and version; written up by Claude Code in the owner's session)
- **Reversible:** yes

## Context

`validate-k8s-manifests` (`.github/workflows/ci.yml`) renders `k8s/base` and the three overlays with
`kubectl kustomize` and validates them with kubeconform; later steps read the same `/tmp/*-rendered.yml`
(#0-63's "no Spring profile in staging/prod", #0-42's `check-k8s-mail.rb`). kubeconform came from
`releases/latest`, piped into `tar` unchecked, and ran with no `-kubernetes-version` and no
`-schema-location`, so it fetched the schemas of the newest Kubernetes from the `master` branch of
`yannh/kubernetes-json-schema` on every run: the result could change with no change here, and a replaced
binary could rewrite the renders and make the checks after it pass. kubectl was pinned to 1.31.0 (end of
upstream support 2025-11-11) while the schemas were `master`. No cluster exists to name a target version.
ADR-0021 already pins the Snyk CLI by version and SHA-256.

## Decision

- kubeconform follows ADR-0021's pattern: `KUBECONFORM_VERSION` and `KUBECONFORM_SHA256` in the job's
  `env`, downloaded to a file, `sha256sum --check --strict`, then unpacked into `$RUNNER_TEMP` and put on
  `$GITHUB_PATH`, no `sudo`. The checksum is committed only when the release's `CHECKSUMS` file and the
  asset's `digest` in GitHub's API agree; kubeconform publishes no signature or attestation.
- `KUBECONFORM_SCHEMA_LOCATION` names a commit of `yannh/kubernetes-json-schema`, never `master`.
- One `KUBERNETES_VERSION` (1.36.5) for both the kubectl that renders (`azure/setup-kubectl`'s `version`)
  and the schemas (`-kubernetes-version`): a minor supported upstream (until 2027-06-28), as no cluster
  sets one. A bump goes only to a version whose `v<version>-standalone-strict` directory the pinned commit
  has, and compares the renders before and after (a newer kubectl bundles a newer kustomize).
- All four values are bumped by hand; Renovate tracks none of them. `ci.yml`'s job `env` is their only
  copy: CLAUDE.md's local command reads them from it, and README and `.ai/context/infrastructure.md` name
  it instead of a version. 1.36.5 in this record is the version chosen on 2026-10-15, not the current one.

## Alternatives considered

- **Pin the binary only** (the item's first sketch): the schemas stay on `master`, so the result still
  changes with no change here, which was the item's own problem statement.
- **The image `ghcr.io/yannh/kubeconform` by digest, through `uses: docker://`**: Renovate could bump the
  digest, but it is a second way of fetching CI tools next to ADR-0021's, the README asks for a
  `docker login` to ghcr, and how the repository's `sha_pinning_required` treats `docker://` was not checked.
- **Schemas vendored into the repository**: offline, but a regeneration script and new files for each
  resource kind, for what a commit-addressed URL already makes immutable.
- **Validate against 1.31, kubectl's version then**: consistent, but a version past its upstream support.
- **`kubectl apply --dry-run=server` on a kind cluster**: a real API server, but minutes and new CI
  infrastructure for a Low item.

## Consequences

- The job's result depends only on the repository; a checksum mismatch stops it before kubeconform runs
  (the check working: compare both channels, never copy the new hash alone).
- A manifest must be valid for the pinned Kubernetes version; using a newer API needs a deliberate bump.
- The two channels are both GitHub's: a swapped file is caught, a malicious release is not, until someone
  bumps to it.
- Knowingly open: kubectl is pinned by version only, `azure/setup-kubectl` checks nothing it downloads and
  kubectl writes the renders (#0-135, Sigstore signature); `diff-cover` is pinned by version without hashes
  (#0-134).
- A reviewer recognises a violation as: a CI tool fetched from `latest` or without a checked checksum, a
  kubeconform call without the pinned `-kubernetes-version`/`-schema-location`, or kubectl and the schemas
  at different versions.
