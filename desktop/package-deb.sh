#!/usr/bin/env bash
#
# Builds the Debian package and adds the pieces jpackage leaves out.
#
# `jpackage` on Linux only writes /opt/shutchat unless it is asked for a
# shortcut, and Compose's Gradle DSL has no switch for that on this version. A
# package without a .desktop file installs fine but never appears in the
# application menu, so this script injects the entry and the icon, then repacks.
#
# Usage: desktop/package-deb.sh [version]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-1.4.0}"
DEB="$ROOT/desktop/build/compose/binaries/main/deb/shutchat_${VERSION}_amd64.deb"
ICON="$ROOT/desktop/icons/shutchat.png"
WORK="$ROOT/desktop/build/compose/binaries/main/deb/work"

if [ ! -f "$DEB" ]; then
  echo "error: run ./gradlew :desktop:packageDeb first" >&2
  exit 1
fi

rm -rf "$WORK"
mkdir -p "$WORK"
dpkg-deb -R "$DEB" "$WORK"

install -Dm644 "$ICON" "$WORK/usr/share/icons/hicolor/512x512/apps/shutchat.png"
mkdir -p "$WORK/usr/share/applications"
cat > "$WORK/usr/share/applications/shutchat.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=ShutChat
GenericName=Peer-to-peer messenger
Comment=Chat and call without an account or a server
Exec=/opt/shutchat/bin/shutchat
Icon=shutchat
Terminal=false
Categories=Network;InstantMessaging;Chat;
Keywords=chat;messenger;call;p2p;
StartupWMClass=dev.shutchat.desktop.MainKt
DESKTOP

# jpackage's maintainer scripts call xdg-desktop-menu and abort the whole
# install when xdg-utils is missing. The menu entry ships in
# /usr/share/applications already, so the call only matters on desktops that
# keep their own copy: make it optional instead of fatal.
for script in postinst prerm; do
  [ -f "$WORK/DEBIAN/$script" ] || continue
  sed -i -e 's|^xdg-desktop-menu |if command -v xdg-desktop-menu >/dev/null 2>/dev/null; then xdg-desktop-menu |' \
         -e 's,^if command -v xdg-desktop-menu .*$,& || true; fi,' "$WORK/DEBIAN/$script"
done

dpkg-deb --build --root-owner-group "$WORK" "$DEB" >/dev/null
echo "wrote $DEB"
