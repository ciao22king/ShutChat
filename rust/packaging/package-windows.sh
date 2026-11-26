#!/usr/bin/env bash
#
# Packages the client for Windows x64: one zip, no installer, no runtime.
#
# Usage: rust/packaging/package-windows.sh [version]
#
# The binary is linked against nothing but DLLs that ship with Windows itself,
# so the archive holds a single executable plus the licence and a short readme.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION="${1:-0.1.0}"
TARGET=x86_64-pc-windows-gnu
BIN="$ROOT/rust/target/$TARGET/release/shutchat.exe"
OUT_DIR="$ROOT/rust/target/windows"
ARCHIVE="$OUT_DIR/ShutChat-${VERSION}-windows-x64.zip"

[ -x "$BIN" ] || {
  echo "error: build first: rustup target add $TARGET && cargo build --release -p shutchat --target $TARGET" >&2
  exit 1
}
command -v x86_64-w64-mingw32-gcc >/dev/null || {
  echo "error: mingw-w64 is required to cross-compile (apt install mingw-w64)" >&2
  exit 1
}

mkdir -p "$OUT_DIR"
rm -f "$ARCHIVE"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
PKG="$STAGE/ShutChat"
mkdir -p "$PKG"

install -m755 "$BIN" "$PKG/ShutChat.exe"
install -m644 "$ROOT/LICENSE" "$PKG/LICENSE"
cat > "$PKG/README.txt" <<'README'
ShutChat for Windows
====================

ShutChat is a peer-to-peer messenger: no account, no phone number, no server.
Two devices pair with a 15-byte invite code and everything after that is
encrypted end to end.

Running it
----------
Double-click ShutChat.exe. There is nothing to install and nothing to
configure.

Windows SmartScreen may warn that the publisher is unknown, because the
executable is not code-signed. Choose "More info" then "Run anyway".

Where the data lives
--------------------
Chats and settings are stored in %APPDATA%\shutchat. Deleting that folder
removes every conversation, every key and every invite.

Talking to another device
-------------------------
* Same Wi-Fi: create a code on one device and paste it on the other, or scan
  the QR code. Discovery uses multicast on the local network.
* Any distance: enable the relay in Settings. The broker only forwards
  encrypted frames it cannot read.

Firewall
--------
The first time it starts, Windows asks whether to allow network access. Allow
it for private networks, otherwise other devices cannot reach this one.
README

python3 - "$PKG" "$ARCHIVE" <<'PY'
import os, sys, zipfile
package, archive = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
    for entry in sorted(os.listdir(package)):
        z.write(os.path.join(package, entry), f"ShutChat/{entry}")
        print(f"  + {entry}")
PY

ls -l "$ARCHIVE"
echo "$ARCHIVE"
