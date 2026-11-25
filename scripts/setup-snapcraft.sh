#!/bin/bash
# Installs snapcraft 9 in a container that has no systemd, so no snapd.
#
# snapd needs systemd, and this image runs without it. Snapcraft is distributed
# only as a snap, so this unpacks the snap and its core24 base and points the
# paths the patched binaries expect back at the unpacked trees.
#
# Needs root for the /snap symlinks and the snapctl stub.
# Run: sudo ./scripts/setup-snapcraft.sh
set -euo pipefail

ROOT=${ROOT:-/opt}
STORE_API=https://api.snapcraft.io

snap_url() {
    curl -s -H "Snap-Device-Series: 16" "$STORE_API/v2/snaps/info/$1" |
        python3 -c "import json,sys; d=json.load(sys.stdin); print(next(c['download']['url'] for c in d['channel-map'] if c['channel']['name']=='stable' and c['channel']['architecture']=='amd64'))"
}

fetch() {
    local name=$1 dest=$2
    if [ ! -d "$dest" ]; then
        echo "fetching $name"
        curl -sL -H "Snap-Device-Series: 16" -o "/tmp/$name.snap" "$(snap_url "$name")"
        unsquashfs -q -d "$dest" "/tmp/$name.snap" >/dev/null
        chmod -R a+rX "$dest"
    fi
}

fetch snapcraft "$ROOT/snapcraft-9"
fetch core24 "$ROOT/core24"

# The snap binaries carry the interpreter path of their base, and their
# libraries are found relative to it.
mkdir -p /snap/core24 /snap/snapcraft
ln -sfn "$ROOT/core24" /snap/core24/current
ln -sfn "$ROOT/snapcraft-9" /snap/snapcraft/current
chmod 755 /snap /snap/core24 /snap/snapcraft

# snapcraft asks snapd for its configuration; without snapd it has to answer
# itself. `get -d` must return JSON or snapcraft aborts on a decode error.
cat >/usr/bin/snapctl <<'EOF'
#!/bin/sh
case "$1" in
  get) echo "{}" ;;
  is-connected) exit 1 ;;
  *) exit 0 ;;
esac
EOF
chmod +x /usr/bin/snapctl

cat >/usr/local/bin/snapcraft <<'EOF'
#!/bin/bash
export SNAP=/snap/snapcraft/current
export SNAP_NAME=snapcraft SNAP_ARCH=amd64 SNAP_INSTANCE_NAME=snapcraft
export SNAP_USER_DATA="$HOME/.local/share/snapcraft"
export SNAP_USER_COMMON="$SNAP_USER_DATA/common"
export SNAP_DATA="$HOME/.snapcraft-data"
export SNAP_COMMON="$HOME/.snapcraft-common"
mkdir -p "$SNAP_USER_DATA" "$SNAP_USER_COMMON" "$SNAP_DATA" "$SNAP_COMMON"
exec "$SNAP/bin/python" "$SNAP/bin/snapcraft" "$@"
EOF
chmod +x /usr/local/bin/snapcraft

echo "installed: $(snapcraft --version)"
echo
echo "login needs a keyring. Start one in this shell before snapcraft login:"
echo "  eval \"\$(dbus-launch --sh-syntax)\""
echo "  export DBUS_SESSION_BUS_ADDRESS"
echo "  echo -n 'keyring-pass' | gnome-keyring-daemon --unlock --components=secrets"
