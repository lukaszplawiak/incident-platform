#!/usr/bin/env bash
# ============================================================
# Owner-run (scripts/factory-admin/). Creates and starts the build VM from nothing (backlog #0-238, ADR-0056,
# criterion S1): Lima instance `factory-build` from factory-build.yaml, its contents from provision.sh, then the
# repository cloned inside it from GitHub (REPO_URL), owned by the VM's `factory` user (#0-202: a clone of its own, no mount of
# the owner's checkout, no gitignored file of the owner's).
#
#   create.sh            the instance must not exist (delete.sh first): an existing one is refused, not reused, so
#                        "created from nothing" stays true of every VM this script reports
#
# It checks what the ADR and README state as properties rather than trusting the template to give them (review
# round 1 of #0-238): Lima merges ~/.lima/_config/default.yaml and override.yaml into every instance, so a mount or
# SSH agent forwarding set there would reach this VM too. After the start it refuses, and deletes the VM, if Lima
# reports a mount, agent or X11 forwarding, a port forward, an extra network or proxy propagation, or the guest shows
# a host file system or an agent socket. Any failure from `limactl create` until the clone is done deletes the VM
# (review round 3): a half-made VM was never checked, and would only block the next run.
#
# What it does not do: the logins. The bot's `gh` token and Claude Code's login are entered by the owner once, inside
# the devcontainer in the VM (docs/ai-factory.md "Build VM"). The clone is anonymous, which holds while the
# repository is public (ADR-0055 stage 9 makes it private: then the clone needs a credential, ADR-0056).
# Needs limactl (Lima 2.x, `brew install lima`) on the Mac; the image is downloaded by Lima and checked against the
# digest in factory-build.yaml.
# ============================================================
set -euo pipefail

NAME=factory-build
# This product's repository, named here: the factory is to build other products too (ADR-0055), so in stage 4, when
# these scripts move to the `factory` repository, the URL becomes the product adapter's setting.
REPO_URL=https://github.com/profitbuild/incident-platform.git
CLONE=/home/factory/incident-platform
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(cd "$here/../../.." && pwd)

fail() { echo "create.sh: $*" >&2; exit 1; }
command -v limactl >/dev/null || fail "limactl not found (brew install lima)"

# The list into a variable first: `limactl list | grep -q` under pipefail can fail when grep exits before limactl
# has written everything (SIGPIPE), and a failure here would read as "no such instance" (review round 1).
instances=$(limactl list --quiet)
if grep -qx "$NAME" <<<"$instances"; then
  fail "instance $NAME exists; run delete.sh first (this script only creates from nothing)"
fi

# provision.sh cannot read .node-version (a cloud VM runs it before any clone), so its copy is checked here.
node_pin=$(sed -n 's/^NODE_VERSION=//p' "$here/provision.sh")
[ "$node_pin" = "$(tr -d '[:space:]' < "$root/.node-version")" ] \
  || fail "provision.sh pins Node $node_pin, .node-version says $(cat "$root/.node-version"): change both (and verify-pins.sh)"

# The provision file's path is the Mac's, read by limactl at create time, so it is written into a copy of the template
# (a `--set` applies too late: "locator is empty"). A path holding a character special to sed or YAML is refused
# rather than escaped; the repository's own path holds none.
case "$here" in *[\"\\\|\&]*) fail "unsupported path: $here" ;; esac
template=$(mktemp -d)/factory-build.yaml
created=false ready=false
cleanup() {
  local rc=$?
  rm -rf "${template%/*}"
  if $created && ! $ready; then
    echo "create.sh: the VM is not ready (exit $rc); deleting $NAME" >&2
    limactl delete --force "$NAME" >&2 || echo "create.sh: could not delete $NAME: run delete.sh" >&2
  fi
  exit "$rc"
}
trap cleanup EXIT
sed "s|@PROVISION_SH@|$here/provision.sh|" "$here/factory-build.yaml" > "$template"
if grep -q '@PROVISION_SH@' "$template"; then fail "the template's placeholder was not filled"; fi
created=true
limactl create --tty=false --name="$NAME" "$template"
limactl start --tty=false "$NAME"

# The EXIT trap deletes the VM: every failure below leaves `ready` false.
isolation_fail() { echo "create.sh: $*" >&2; exit 1; }
# Each check fails closed: a command that cannot answer deletes the VM like a check that finds something (review
# round 2 of #0-238: a failed `limactl list` or `limactl shell` used to stop the script and leave the VM running).
mounts=$(limactl list "$NAME" --format '{{json .Config.Mounts}}') || isolation_fail "cannot read the VM's mounts"
[ "$mounts" = "[]" ] || [ "$mounts" = "null" ] \
  || isolation_fail "Lima mounts host directories into the VM ($mounts): check ~/.lima/_config/default.yaml and override.yaml"
agent=$(limactl list "$NAME" --format '{{.Config.SSH.ForwardAgent}}') || isolation_fail "cannot read the VM's agent forwarding"
[ "$agent" = "false" ] || isolation_fail "Lima forwards the SSH agent (forwardAgent=$agent)"
# Every port rule must be an `ignore` one: a rule merged in ahead of the template's (override.yaml) would forward.
forwarding=$(limactl list "$NAME" --format '{{range .Config.PortForwards}}{{if not .Ignore}}{{.GuestPortRange}} {{end}}{{end}}') \
  || isolation_fail "cannot read the VM's port forwards"
[ -z "$forwarding" ] || isolation_fail "Lima forwards VM ports to the Mac (guest ports $forwarding): check ~/.lima/_config/override.yaml"
# The other keys ~/.lima/_config/ could merge in that widen what the VM reaches or receives (review round 3):
# a network (vzNAT, bridged: the VM on the LAN), X11 forwarding, the Mac's proxy variables.
extra=$(limactl list "$NAME" --format 'networks={{json .Config.Networks}} x11={{.Config.SSH.ForwardX11}} proxy={{.Config.PropagateProxyEnv}}') \
  || isolation_fail "cannot read the VM's networks and forwarding"
case "$extra" in
  "networks=[] x11=false proxy=false" | "networks=null x11=false proxy=false") ;;
  *) isolation_fail "Lima adds a network, X11 forwarding or proxy variables ($extra): check ~/.lima/_config/" ;;
esac
# And what could copy files out of the VM onto the Mac (copyToHost), attach a disk, or run another script or probe
# than provision.sh (review round 4).
more=$(limactl list "$NAME" --format 'copy={{len .Config.CopyToHost}} disks={{len .Config.AdditionalDisks}} provision={{len .Config.Provision}} probes={{len .Config.Probes}}') \
  || isolation_fail "cannot read the VM's copies, disks, provisions and probes"
[ "$more" = "copy=0 disks=0 provision=1 probes=0" ] \
  || isolation_fail "Lima adds a copy to the Mac, a disk, a provision or a probe ($more): check ~/.lima/_config/"
# findmnt exits 1 when it finds nothing; any other status (the shell failed) is no answer.
rc=0
host_fs=$(limactl shell "$NAME" findmnt -rn -o TARGET,FSTYPE -t virtiofs,9p,fuse.sshfs,sshfs) || rc=$?
case $rc in
  0) isolation_fail "the guest has host file systems mounted: $host_fs" ;;
  1) ;;
  *) isolation_fail "cannot list the guest's file systems (exit $rc)" ;;
esac
# shellcheck disable=SC2016 # expanded in the guest, not here
sock=$(limactl shell "$NAME" sh -c 'echo "${SSH_AUTH_SOCK:-}"') || isolation_fail "cannot read the guest's environment"
[ -z "$sock" ] || isolation_fail "the guest has an SSH agent socket ($sock)"

# Lima reports the VM started once its provisions ran; check that this one did, rather than trusting the order.
limactl shell "$NAME" cat /etc/factory-build-provisioned

limactl shell "$NAME" sudo -u factory -H git clone --quiet "$REPO_URL" "$CLONE"
cloned=$(limactl shell "$NAME" sudo -u factory -H git -C "$CLONE" rev-parse HEAD)
echo "factory-build: cloned $cloned"
ready=true
# The VM's contents come from this checkout's provision.sh, its code from the default branch of REPO_URL: the two match
# only when this runs from an up-to-date main (review round 4). Not a failure: the VM is fine, the owner decides.
local_head=$(git -C "$root" rev-parse HEAD)
if [ -n "$(git -C "$root" status --porcelain -- scripts/factory-admin/build-vm .node-version)" ]; then
  echo "create.sh: WARNING: provisioned from uncommitted build-vm files of this checkout, cloned $cloned:" \
       "recreate from an up-to-date main once they are merged" >&2
elif [ "$local_head" != "$cloned" ]; then
  echo "create.sh: WARNING: provisioned from this checkout at $local_head, cloned $cloned:" \
       "recreate from an up-to-date main if provision.sh differs between them" >&2
fi
echo "factory-build: ready; next, the manual steps in docs/ai-factory.md \"Build VM\""
