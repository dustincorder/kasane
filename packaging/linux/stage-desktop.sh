#!/usr/bin/env bash
# Stage a Kasane desktop release tarball layout from a jpackage AppDir.
#
# Usage:
#   packaging/linux/stage-desktop.sh \
#       --appdir android/desktop-compose/build/compose/binaries/main/app/kasane-compose \
#       --package kasane-compose \
#       --version 0.2.1-alpha \
#       --out build/release-stage
#
# Produces <out>/kasane-desktop-<version>/ containing:
#   <package>/       the AppDir (renamed to the package name)
#   <package>-run    portable launcher ("kasane" for stable, "kasane-nightly" for nightly)
#   install.sh       user-scope installer (no sudo, XDG-safe, idempotent)
set -euo pipefail

APPDIR=""
PACKAGE="kasane-compose"
VERSION="dev"
OUT="build/release-stage"

while [ $# -gt 0 ]; do
    case "$1" in
        --appdir)  APPDIR="$2";  shift 2 ;;
        --package) PACKAGE="$2"; shift 2 ;;
        --version) VERSION="$2"; shift 2 ;;
        --out)     OUT="$2";     shift 2 ;;
        *) echo "stage-desktop.sh: unknown argument $1" >&2; exit 2 ;;
    esac
done

[ -n "$APPDIR" ] || { echo "stage-desktop.sh: --appdir is required" >&2; exit 2; }
[ -d "$APPDIR" ] || { echo "stage-desktop.sh: appdir '$APPDIR' does not exist" >&2; exit 1; }
[ -x "$APPDIR/bin/$PACKAGE-java" ] || {
    echo "stage-desktop.sh: expected $APPDIR/bin/$PACKAGE-java" >&2; exit 1
}

# Launcher / desktop-entry naming: stable keeps the short "kasane" names,
# nightly gets fully separated names so both can coexist. Desktop-file IDs must
# match BuildInfo's MPRIS DesktopEntry (`cord.kasane.desktop` / `.nightly`).
if [ "$PACKAGE" = "kasane-compose" ]; then
    RUN_NAME="kasane"
    APP_NAME="Kasane"
    DESKTOP_FILE="cord.kasane.desktop.desktop"
    WMCLASS="kasane-compose"
else
    # Can't be "$PACKAGE": the staged AppDir already takes that directory name.
    RUN_NAME="$PACKAGE-run"
    APP_NAME="Kasane Nightly"
    DESKTOP_FILE="cord.kasane.desktop.nightly.desktop"
    WMCLASS="$PACKAGE"
fi

STAGE="$OUT/kasane-desktop-$VERSION"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -a "$APPDIR" "$STAGE/$PACKAGE"

cat > "$STAGE/$RUN_NAME" <<EOF
#!/bin/sh
# Kasane desktop launcher (portable run from the unpacked directory)
HERE="\$(CDPATH= cd -- "\$(dirname -- "\$0")" && pwd)"
exec "\$HERE/$PACKAGE/bin/$PACKAGE-java" "\$@"
EOF
chmod +x "$STAGE/$RUN_NAME"

cat > "$STAGE/install.sh" <<'INSTALL'
#!/bin/sh
# Kasane desktop installer - idempotent, no sudo, user-scope only.
# Installs the app into ~/.local/opt/, registers a desktop entry and an icon.
# Never touches user data under XDG dirs.
set -eu

PACKAGE="KASANE_PACKAGE_PLACEHOLDER"
APP_NAME="KASANE_APPNAME_PLACEHOLDER"
DESKTOP_FILE="KASANE_DESKTOP_PLACEHOLDER"
WMCLASS="KASANE_WMCLASS_PLACEHOLDER"

HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
SRC="$HERE/$PACKAGE"
DEST="${XDG_OPT_HOME:-$HOME/.local/opt}/$PACKAGE"
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
ICONS="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/256x256/apps"

if [ ! -x "$SRC/bin/$PACKAGE-java" ]; then
    echo "install.sh: app directory '$SRC' is missing bin/$PACKAGE-java" >&2
    exit 1
fi

mkdir -p "$(dirname "$DEST")"
if command -v rsync >/dev/null 2>&1; then
    mkdir -p "$DEST"
    rsync -a --delete "$SRC/" "$DEST/"
else
    TMP="$(dirname "$DEST")/.$PACKAGE.new.$$"
    rm -rf "$TMP"
    cp -a "$SRC" "$TMP"
    if [ -d "$DEST" ]; then mv "$DEST" "$TMP.old.$$"; mv "$TMP" "$DEST"; rm -rf "$TMP.old.$$"; else mv "$TMP" "$DEST"; fi
fi
chmod +x "$DEST/bin/$PACKAGE-java" "$DEST/bin/$PACKAGE" 2>/dev/null || true

mkdir -p "$APPS"
cat > "$APPS/$DESKTOP_FILE" <<DESKTOP
[Desktop Entry]
Type=Application
Name=$APP_NAME
Comment=YouTube Music client
Exec=$DEST/bin/$PACKAGE-java %U
Icon=$PACKAGE
Terminal=false
Categories=AudioVideo;Audio;Player;
StartupNotify=true
StartupWMClass=$WMCLASS
MimeType=x-scheme-handler/kasane;
DESKTOP

mkdir -p "$ICONS"
cp -f "$SRC/lib/$PACKAGE.png" "$ICONS/$PACKAGE.png"

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$APPS" >/dev/null 2>&1 || true
fi
if command -v kbuildsycoca6 >/dev/null 2>&1; then kbuildsycoca6 >/dev/null 2>&1 || true; fi
if command -v kbuildsycoca5 >/dev/null 2>&1; then kbuildsycoca5 >/dev/null 2>&1 || true; fi

echo "$APP_NAME installed to $DEST"
echo "Desktop entry: $APPS/$DESKTOP_FILE"
INSTALL

sed -i \
    -e "s|KASANE_PACKAGE_PLACEHOLDER|$PACKAGE|g" \
    -e "s|KASANE_APPNAME_PLACEHOLDER|$APP_NAME|g" \
    -e "s|KASANE_DESKTOP_PLACEHOLDER|$DESKTOP_FILE|g" \
    -e "s|KASANE_WMCLASS_PLACEHOLDER|$WMCLASS|g" \
    "$STAGE/install.sh"
chmod +x "$STAGE/install.sh"

echo "$STAGE"
