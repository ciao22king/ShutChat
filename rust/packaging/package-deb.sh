#!/usr/bin/env bash
#
# Builds the Debian/Ubuntu package around the release binary.
#
# Usage: rust/packaging/package-deb.sh [version]
#
# The binary is statically linked against Rust code but still uses the system
# libc, X11/Wayland and fontconfig. The first three are in the ELF's NEEDED
# list and are computed by dpkg-shlibdeps; the GUI ones are dlopened and are
# listed by hand.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION="${1:-0.1.0}"
BIN="$ROOT/rust/target/release/shutchat"
ICON="$ROOT/desktop/icons/shutchat.png"
ARCH="$(dpkg --print-architecture)"
OUT="$ROOT/rust/target/debian"
# dpkg-deb refuses a control directory whose mode is not 0755-0775, and some
# volumes (and every setgid parent) hand out 2755 no matter what chmod says, so
# the tree is staged under /tmp and only the finished archive lands in the repo.
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
PKG="$STAGE/shutchat_${VERSION}_${ARCH}"
DEB="$OUT/shutchat_${VERSION}_${ARCH}.deb"

command -v dpkg-deb >/dev/null || { echo "error: dpkg-deb not found" >&2; exit 1; }
[ -x "$BIN" ] || { echo "error: run 'cargo build --release -p shutchat' first" >&2; exit 1; }
[ -f "$ICON" ] || { echo "error: missing $ICON" >&2; exit 1; }

mkdir -p "$OUT"
rm -f "$DEB"
mkdir -p "$PKG/DEBIAN" \
         "$PKG/usr/bin" \
         "$PKG/usr/share/applications" \
         "$PKG/usr/share/icons/hicolor/512x512/apps" \
         "$PKG/usr/share/doc/shutchat"

install -m755 "$BIN" "$PKG/usr/bin/shutchat"
install -m644 "$ICON" "$PKG/usr/share/icons/hicolor/512x512/apps/shutchat.png"

cat > "$PKG/usr/share/applications/shutchat.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=ShutChat
GenericName=Peer-to-peer messenger
Comment=Chat without an account or a server
Exec=shutchat
Icon=shutchat
Terminal=false
Categories=Network;InstantMessaging;Chat;
Keywords=chat;messenger;p2p;offline;shutchat;
StartupWMClass=shutchat
DESKTOP

install -m644 "$ROOT/LICENSE" "$PKG/usr/share/doc/shutchat/copyright"

# The X11/Wayland and GL libraries are opened at run time with dlopen, so they
# never show up in the ELF's NEEDED list: list them by hand.
GUI_DEPENDS="libxkbcommon0, libxkbcommon-x11-0, libwayland-client0, libwayland-cursor0, libwayland-egl1, libx11-6, libxcursor1, libxrandr2, libxi6, libxext6, libgl1, libegl1"
DEPENDS="$GUI_DEPENDS, libfontconfig1, libfreetype6, libc6"
if command -v dpkg-shlibdeps >/dev/null 2>&1; then
  # dpkg-shlibdeps wants a debian/control next to the tree it inspects.
  mkdir -p "$PKG/debian"
  printf 'Source: shutchat\n\nPackage: shutchat\nArchitecture: any\n' > "$PKG/debian/control"
  SHLIBS="$(cd "$PKG" && dpkg-shlibdeps -O --ignore-missing-info usr/bin/shutchat 2>/dev/null | sed -n 's/^shlibs:Depends=//p')"
  rm -rf "$PKG/debian"
  if [ -n "$SHLIBS" ]; then
    DEPENDS="$SHLIBS, $GUI_DEPENDS"
  fi
fi

INSTALLED_KB="$(du -sk --apparent-size "$PKG" | cut -f1)"

cat > "$PKG/DEBIAN/control" <<CONTROL
Package: shutchat
Version: ${VERSION}
Section: net
Priority: optional
Architecture: ${ARCH}
Maintainer: ShutChat <ciao22king@users.noreply.github.com>
Installed-Size: ${INSTALLED_KB}
Depends: ${DEPENDS}
Homepage: https://github.com/ciao22king/ShutChat
Description: Peer-to-peer messenger with no server and no accounts
 ShutChat pairs two devices with a 15-byte invite code, derives a room key
 from it and exchanges end-to-end encrypted messages over the local network.
 There is no account, no phone number and no server that can read the
 conversation.
CONTROL

dpkg-deb --root-owner-group --build "$PKG" "$DEB" >/dev/null
echo "$DEB"
