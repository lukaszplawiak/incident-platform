#!/bin/bash
# ============================================================
# The build VM's contents (backlog #0-238, ADR-0056): what the VM holds beyond Ubuntu's cloud image, in one file.
#
# One script, two readers, nothing in it that knows which one runs it (criterion S2):
#   - Lima runs it as a `mode: system` provision (factory-build.yaml), as root, on every boot;
#   - a cloud VM (stage C of ADR-0055) takes the same file as its cloud-init user-data: a user-data that starts with
#     `#!` is run as a script, once, as root.
# So it is idempotent (Lima's every-boot run must change nothing the second time), every step guarded by what it
# installs, and uses nothing of either tool: no Lima variable, no cloud metadata, no file of the repository (a cloud
# VM runs it before any clone exists, so the pins are here, not read from elsewhere).
#
# What it installs: Docker Engine in the VM (not Docker Desktop: the Docker the test gate's Testcontainers reach is
# this VM's, which holds no file of the owner's, #0-116), git, Node and the devcontainer CLI that starts today's
# devcontainer (.devcontainer/). And one user, `factory`, in the docker group, who owns the clone (create.sh): the
# same name on a laptop VM and a cloud VM, where the default user differs (Lima's is the Mac's login, a cloud
# image's `ubuntu`). Membership of the docker group is root on this VM; the VM is the boundary, not the user.
#
# Where each comes from, and what checks it (review round 1 of #0-238):
#   - Ubuntu's packages: its archive, signed, checked by apt.
#   - Node: nodejs.org's tarball, checked against the sha256 pinned below. Not Ubuntu's `nodejs`: noble ships 18,
#     and the devcontainer CLI needs 20 or later (its `engines`; npm only warns). The version is .node-version's,
#     the one CI runs (ADR-0043); create.sh refuses a mismatch.
#   - The devcontainer CLI: npm's tarball, checked against the sha512 pinned below (npm's `dist.integrity` of that
#     version, in hex) and installed from the file, so npm fetches nothing: the package has no dependencies.
# The pins are checked against their publishers by verify-pins.sh (Node's signed SHASUMS256, npm's integrity), run
# with every change of a pin. A change here takes effect on a new VM: delete.sh, then create.sh.
# ============================================================
set -euxo pipefail
export DEBIAN_FRONTEND=noninteractive

APT_PACKAGES=(docker.io git ca-certificates curl xz-utils)
NODE_VERSION=24.22.1
NODE_SHA256_ARM64=dbabf7a1cbf947e6059ffad2c27b80041db97dea1e553b62afdee7f13fca336e
NODE_SHA256_X64=6c172a9ab6718f7b801fb5a7bd486af2ee5e32758086a04b84fcce2aa77095e6
DEVCONTAINER_CLI_VERSION=0.89.0
DEVCONTAINER_CLI_SHA512=2f36a8386290fd9aa5e8fb226788552185596a0cd6903eb9686ff5a2ee7f2a5cb96353ed8cb835ca7eeabbe2d90b3568025e83659fe96f3f2a38ee112cd96a32
FACTORY_USER=factory

# Every package checked, not one stand-in for all: a package added to the list reaches a VM that already has the rest.
if ! dpkg -s "${APT_PACKAGES[@]}" >/dev/null 2>&1; then
  apt-get update
  apt-get install -y --no-install-recommends "${APT_PACKAGES[@]}"
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

if [ "$(/usr/local/bin/node --version 2>/dev/null)" != "v$NODE_VERSION" ]; then
  case "$(dpkg --print-architecture)" in
    arm64) node_arch=arm64 node_sha=$NODE_SHA256_ARM64 ;;
    amd64) node_arch=x64 node_sha=$NODE_SHA256_X64 ;;
    *) echo "provision.sh: no Node pin for $(dpkg --print-architecture)" >&2; exit 1 ;;
  esac
  node_tar=node-v${NODE_VERSION}-linux-${node_arch}.tar.xz
  curl -fsSL -o "$work/$node_tar" "https://nodejs.org/dist/v${NODE_VERSION}/${node_tar}"
  echo "$node_sha  $work/$node_tar" | sha256sum -c -
  tar -xJf "$work/$node_tar" -C /usr/local --strip-components=1 --no-same-owner \
    --exclude=CHANGELOG.md --exclude=LICENSE --exclude=README.md
fi

if [ "$(devcontainer --version 2>/dev/null)" != "$DEVCONTAINER_CLI_VERSION" ]; then
  cli_tgz=$work/devcontainers-cli-${DEVCONTAINER_CLI_VERSION}.tgz
  curl -fsSL -o "$cli_tgz" "https://registry.npmjs.org/@devcontainers/cli/-/cli-${DEVCONTAINER_CLI_VERSION}.tgz"
  echo "$DEVCONTAINER_CLI_SHA512  $cli_tgz" | sha512sum -c -
  # --ignore-scripts: no lifecycle script runs as root (0.89.0 has none; a later version cannot add one unseen).
  /usr/local/bin/npm install -g --no-fund --no-audit --ignore-scripts "$cli_tgz"
fi

if ! id -u "$FACTORY_USER" >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "$FACTORY_USER"
fi
usermod -aG docker "$FACTORY_USER"

systemctl enable --now docker
echo "factory-build provisioned: $(docker --version); node $(/usr/local/bin/node --version); devcontainer $(devcontainer --version)" \
  > /etc/factory-build-provisioned
