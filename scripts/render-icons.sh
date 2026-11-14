#!/usr/bin/env bash
# Renders the PNG icons from assets/logo/fireside.svg:
#   icon.png                                        48x48, Plugin Hub icon (max 48x72)
#   src/main/resources/com/fireside/panel-icon.png  16x16, side panel icon
#
# Usage: ./scripts/render-icons.sh
#
# Requires: rsvg-convert (Arch: pacman -S librsvg).
set -euo pipefail

if ! command -v rsvg-convert > /dev/null; then
	echo "error: 'rsvg-convert' is required (Arch: pacman -S librsvg)" >&2
	exit 1
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
svg="$root/assets/logo/fireside.svg"

render() {
	local size="$1" out="$2"
	rsvg-convert --width "$size" --height "$size" --format png --output "$root/$out" "$svg"
	echo "Rendered $out (${size}x${size})"
}

render 48 icon.png
render 16 src/main/resources/com/fireside/panel-icon.png
