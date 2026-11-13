#!/usr/bin/env bash
# Build a Linux release archive that reports a newer Ventoy version than any real release.
#
# No official Ventoy release is newer than the bundled one, yet CI must prove that the
# app refuses to replace a newer installed payload. This copies the boot files of a
# caller-verified official archive unchanged and edits only the version line in the
# VTOYEFI image's grub/grub.cfg, which Vendroid reads to detect the installed version.
# The result is a test fixture only; it is never booted, published or packaged.
#
# CI dependencies: mtools xz-utils.
# Usage: make-relabelled-payload.sh OFFICIAL.tar.gz FROM_VERSION TO_VERSION OUTPUT.tar.gz
set -euo pipefail

if [[ $# -ne 4 ]]; then
    echo "Usage: $0 OFFICIAL.tar.gz FROM_VERSION TO_VERSION OUTPUT.tar.gz" >&2
    exit 2
fi
archive="$1" from="$2" to="$3" output="$4"
version='^[0-9]+\.[0-9]+\.[0-9]+$'
[[ "$from" =~ $version && "$to" =~ $version && "$from" != "$to" ]] || { echo "Invalid versions" >&2; exit 2; }
# Same length, so the edited file and the FAT image keep their sizes.
[[ ${#from} == "${#to}" ]] || { echo "Versions must have the same length" >&2; exit 2; }
for tool in mcopy mtype xz tar; do
    command -v "$tool" >/dev/null 2>&1 || { echo "Missing dependency: $tool" >&2; exit 1; }
done
# VTOYEFI is a bare FAT partition image without drive geometry mtools expects.
export MTOOLS_SKIP_CHECK=1

work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
tar -xzf "$archive" -C "$work"
[[ -d "$work/ventoy-$from" ]] || { echo "Archive has no ventoy-$from directory" >&2; exit 1; }
target="$work/out/ventoy-$to"
members=(boot/boot.img boot/core.img.xz ventoy/ventoy.disk.img.xz)
for member in "${members[@]}"; do
    install -D -m 0644 -- "$work/ventoy-$from/$member" "$target/$member"
done
disk="$target/ventoy/ventoy.disk.img"
xz --decompress -- "$disk.xz"

mtype -i "$disk" ::/grub/grub.cfg > "$work/grub.cfg"
# Match the line exactly, tolerating CRLF. Dots are literal; grep -E has no \r escape.
cr=$'\r'
literal() { printf '%s' "$1" | sed 's/\./\\./g'; }
line="^set VENTOY_VERSION=\"$(literal "$from")\"(${cr}?)\$"
to_line="^set VENTOY_VERSION=\"$(literal "$to")\"${cr}?\$"
count="$(grep -cE "$line" "$work/grub.cfg" || true)"
if [[ "$count" != 1 ]]; then
    echo "Expected exactly one 'set VENTOY_VERSION=\"$from\"' line in grub/grub.cfg, found $count" >&2
    exit 1
fi
sed -E -i "s/$line/set VENTOY_VERSION=\"$to\"\1/" "$work/grub.cfg"
grep -qE "$to_line" "$work/grub.cfg"
mcopy -o -i "$disk" "$work/grub.cfg" ::/grub/grub.cfg
mtype -i "$disk" ::/grub/grub.cfg | grep -qE "$to_line"

xz --compress -T0 -6 -- "$disk"
mkdir -p -- "$(dirname -- "$output")"
tar -czf "$output" -C "$work/out" "${members[@]/#/ventoy-$to/}"
echo "Built $output: Ventoy $from boot files reporting version $to"
