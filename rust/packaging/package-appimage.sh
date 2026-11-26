#!/usr/bin/env bash
#
# Packages the release binary as an AppImage: one file, no install, no root.
#
# Usage: rust/packaging/package-appimage.sh [version]
#
# System libraries that talk to the GPU or the display server stay on the host
# (bundling them breaks drivers); everything else the binary needs is copied
# into the image so it runs on distributions with older versions of it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION="${1:-0.1.0}"
BIN="$ROOT/rust/target/release/shutchat"
ICON="$ROOT/desktop/icons/shutchat.png"
OUT_DIR="$ROOT/rust/target/appimage"
APPDIR="$OUT_DIR/ShutChat.AppDir"
OUT="$OUT_DIR/ShutChat-${VERSION}-x86_64.AppImage"

[ -x "$BIN" ] || { echo "error: run 'cargo build --release -p shutchat' first" >&2; exit 1; }
[ -f "$ICON" ] || { echo "error: missing $ICON" >&2; exit 1; }

rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/bin" "$APPDIR/usr/lib" \
         "$APPDIR/usr/share/icons/hicolor/512x512/apps" \
         "$APPDIR/usr/share/applications"

install -m755 "$BIN" "$APPDIR/usr/bin/shutchat"
install -m644 "$ICON" "$APPDIR/shutchat.png"
install -m644 "$ICON" "$APPDIR/usr/share/icons/hicolor/512x512/apps/shutchat.png"

# Libraries provided by the host: graphics stack, display server and glibc.
KEEP_SYSTEM='^(ld-linux|libc|libm|libdl|libpthread|librt|libresolv|libgcc_s|libstdc\+\+|libGL|libGLX|libGLdispatch|libEGL|libOpenGL|libgbm|libdrm|libX11|libxcb|libwayland|libxkbcommon|libdbus|libglib|libgobject|libgio|libselinux|libsystemd|libudev)'
while IFS= read -r lib; do
  name="$(basename "$lib")"
  if [[ "$name" =~ $KEEP_SYSTEM ]]; then
    continue
  fi
  cp -nL "$lib" "$APPDIR/usr/lib/" 2>/dev/null || true
done < <(ldd "$BIN" | awk '/=> \//{print $3} /^\t\//{print $1}')

# The dynamic loader has to come from the image or LD_LIBRARY_PATH is ignored.
cp -nL "$(ldd "$BIN" | awk '/ld-linux/{print $1; exit}')" "$APPDIR/usr/lib/" 2>/dev/null || true

cat > "$APPDIR/AppRun" <<'RUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
export LD_LIBRARY_PATH="$HERE/usr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export SHUTCHAT_APPIMAGE="$HERE"
exec "$HERE/usr/bin/shutchat" "$@"
RUN
chmod +x "$APPDIR/AppRun"

cat > "$APPDIR/shutchat.desktop" <<'DESKTOP'
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
X-AppImage-Name=ShutChat
DESKTOP
cp "$APPDIR/shutchat.desktop" "$APPDIR/usr/share/applications/shutchat.desktop"

TOOL="${APPIMAGETOOL:-$OUT_DIR/appimagetool-x86_64.AppImage}"
if [ ! -x "$TOOL" ]; then
  echo "downloading appimagetool..." >&2
  curl -fsSL -o "$TOOL" \
    https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
  chmod +x "$TOOL"
fi

command -v file >/dev/null || { echo "error: appimagetool needs 'file' (apt install file)" >&2; exit 1; }

rm -f "$OUT"
# --appimage-extract-and-run covers containers and hosts without FUSE.
ARCH=x86_64 NO_STRIP=1 "$TOOL" --appimage-extract-and-run "$APPDIR" "$OUT" >/dev/null \
  || ARCH=x86_64 NO_STRIP=1 "$TOOL" "$APPDIR" "$OUT" >/dev/null
echo "$OUT"
