#!/usr/bin/env bash
#
# Packages the Compose desktop distribution as an AppImage.
#
# Compose's own `packageAppImage` task stops at the jpackage app image (a plain
# directory). This script wraps that directory into a single self-contained
# executable, which is what people mean by "app image": one file, no install, no
# root, works on any glibc distribution.
#
# Usage: desktop/package-appimage.sh [version]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-1.3.5}"
APP_SRC="$ROOT/desktop/build/compose/binaries/main/app/shutchat"
OUT_DIR="$ROOT/desktop/build/compose/binaries/main/appimage"
ICON="$ROOT/desktop/icons/shutchat.png"
APPDIR="$OUT_DIR/ShutChat.AppDir"

if [ ! -x "$APP_SRC/bin/shutchat" ]; then
  echo "error: run ./gradlew :desktop:createDistributable first" >&2
  exit 1
fi

rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/bin" "$APPDIR/usr/lib" "$APPDIR/usr/share/icons/hicolor/512x512/apps"

cp -a "$APP_SRC/bin/." "$APPDIR/usr/bin/"
cp -a "$APP_SRC/lib/." "$APPDIR/usr/lib/"
cp "$ICON" "$APPDIR/shutchat.png"
cp "$ICON" "$APPDIR/usr/share/icons/hicolor/512x512/apps/shutchat.png"

cat > "$APPDIR/AppRun" <<'RUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
export PATH="$HERE/usr/bin:$PATH"
exec "$HERE/usr/bin/shutchat" "$@"
RUN
chmod +x "$APPDIR/AppRun"

cat > "$APPDIR/shutchat.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=ShutChat
Comment=Peer-to-peer messenger with calls
Exec=shutchat
Icon=shutchat
Terminal=false
Categories=Network;InstantMessaging;
StartupWMClass=dev.shutchat.desktop.MainKt
DESKTOP

TOOL="${APPIMAGETOOL:-$OUT_DIR/appimagetool-x86_64.AppImage}"
if [ ! -x "$TOOL" ]; then
  echo "downloading appimagetool..."
  curl -fsSL -o "$TOOL" \
    https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
  chmod +x "$TOOL"
fi

cd "$OUT_DIR"
# The tool is itself an AppImage; extract-and-run avoids needing FUSE, which is
# not available in most containers.
# Build next to the target and rename at the end: mksquashfs fails if the
# destination is a file that is currently running.
ARCH=x86_64 "$TOOL" --appimage-extract-and-run "$APPDIR" "ShutChat-$VERSION.new"
mv -f "ShutChat-$VERSION.new" "ShutChat-$VERSION-x86_64.AppImage"

echo "wrote $OUT_DIR/ShutChat-$VERSION-x86_64.AppImage"
