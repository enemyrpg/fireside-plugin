#!/usr/bin/env bash
# Downloads a published contract package from the fireside repo, verifies
# its SHA-256 checksum and pins it in src/test/resources/contract/.
#
# Usage: ./scripts/update-contract.sh <version>    (e.g. 1.13.0)
#
# Requires: gh (logged in), sha256sum, unzip.
set -euo pipefail

REPO="enemyrpg/fireside"

if [[ $# -ne 1 ]]; then
	echo "Usage: $0 <version>   (e.g. 1.13.0)" >&2
	exit 2
fi

version="${1#v}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
	echo "error: '$1' is not a version like 1.13.0" >&2
	exit 2
fi

for tool in gh sha256sum unzip; do
	if ! command -v "$tool" > /dev/null; then
		echo "error: '$tool' is required" >&2
		exit 1
	fi
done

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="$root/src/test/resources/contract"
tag="contract-v$version"
zip="$tag.zip"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "Downloading $zip from $REPO ..."
gh release download "$tag" --repo "$REPO" --dir "$work/download" \
	--pattern "$zip" --pattern "$zip.sha256"

echo "Verifying checksum ..."
# The .sha256 file names the zip without a path, so check from its directory.
(cd "$work/download" && sha256sum --check --strict "$zip.sha256")

unzip -q "$work/download/$zip" -d "$work/unpacked"

# The zip may contain the files at the top level or inside one folder.
src="$work/unpacked"
if [[ ! -f "$src/manifest.json" ]]; then
	dirs=("$src"/*/)
	if [[ ${#dirs[@]} -eq 1 && -f "${dirs[0]}manifest.json" ]]; then
		src="${dirs[0]%/}"
	else
		echo "error: manifest.json not found in $zip" >&2
		exit 1
	fi
fi

manifest_version="$(sed -n 's/^[[:space:]]*"contractVersion":[[:space:]]*"\([^"]*\)".*/\1/p' "$src/manifest.json" | head -n 1)"
if [[ "$manifest_version" != "$version" ]]; then
	echo "error: manifest.json says contractVersion '$manifest_version', expected '$version'" >&2
	exit 1
fi

rm -rf "$target"
mkdir -p "$(dirname "$target")"
cp -R "$src" "$target"

echo "Pinned contract $version in ${target#"$root"/}"
echo "Remember to update Contract.PINNED_VERSION (and REQUIRED_VERSION or ICONS_VERSION or NAMED_ICONS_VERSION if the plugin now needs this version)."
