#!/usr/bin/env bash
# Install the Kasane .desktop entry and hicolor icons for a given binary path.
# Usage: scripts/install-desktop-entry.sh [path-to-kasane-binary]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BINARY="${1:-$REPO_ROOT/target/release/kasane}"
BINARY="$(readlink -f "$BINARY")"

APPS_DIR="$HOME/.local/share/applications"
ICON_ROOT="$HOME/.local/share/icons/hicolor"

mkdir -p "$APPS_DIR"
for size in 32 64 128 256 512; do
    src="$REPO_ROOT/src-tauri/icons/${size}x${size}.png"
    dest_dir="$ICON_ROOT/${size}x${size}/apps"
    mkdir -p "$dest_dir"
    cp "$src" "$dest_dir/cord.kasane.desktop.png"
done

sed "s|^Exec=.*|Exec=$BINARY %U|" \
    "$REPO_ROOT/packaging/linux/cord.kasane.desktop.desktop" \
    > "$APPS_DIR/cord.kasane.desktop.desktop"

command -v update-desktop-database >/dev/null && update-desktop-database "$APPS_DIR" || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -f -t "$ICON_ROOT" || true

echo "Installed launcher for $BINARY"
