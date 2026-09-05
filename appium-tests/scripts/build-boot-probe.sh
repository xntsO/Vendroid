#!/usr/bin/env bash
# CI dependencies: grub-pc-bin grub-efi-amd64-bin grub-common xorriso mtools.
# Build on the Ubuntu CI runner; no distribution ISO or kernel is downloaded.
set -euo pipefail

if [[ $# -ne 1 || -z "$1" ]]; then
    echo "Usage: $0 OUTPUT.iso" >&2
    exit 2
fi

for tool in grub-mkrescue xorriso mformat; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Missing dependency: $tool" >&2
        exit 1
    fi
done
# Without both platforms grub-mkrescue can silently produce a single-mode ISO.
for platform in i386-pc x86_64-efi; do
    if [[ ! -f "/usr/lib/grub/$platform/modinfo.sh" ]]; then
        echo "Missing GRUB platform: $platform" >&2
        exit 1
    fi
done

mkdir -p -- "$(dirname -- "$1")"
output_dir="$(cd -- "$(dirname -- "$1")" && pwd)"
output_iso="$output_dir/$(basename -- "$1")"
# Stage beside the destination so publication is an atomic rename.
work_dir="$(mktemp -d "$output_dir/.vendroid-boot-probe.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -p "$work_dir/iso/boot/grub"
cat > "$work_dir/iso/boot/grub/grub.cfg" <<'GRUB'
# This config exists only inside the rescue ISO, never in Ventoy's menu.
serial --unit=0 --speed=115200 --word=8 --parity=no --stop=1
terminal_input serial
terminal_output serial
echo
echo "VENDROID_CI_BOOT_FIRMWARE=$grub_platform"
echo "VENDROID_CI_BOOT_OK"
halt
GRUB

# Embed serial and halt support for both platforms before reading grub.cfg.
# Let mkrescue discover both platform directories; -d would restrict it to one.
# Copy only the rescue modules and their dependencies so the ISO fits the small
# CI data partition. No kernels, fonts, themes, or full GRUB module collection.
grub-mkrescue --modules="serial halt" \
    --fonts="" --themes="" --locales="" \
    --install-modules="normal serial halt echo iso9660 fat part_gpt part_msdos search search_fs_file search_fs_uuid" \
    --output="$work_dir/probe.iso" "$work_dir/iso"
test -s "$work_dir/probe.iso"
mv -f -- "$work_dir/probe.iso" "$output_iso"
printf 'Boot probe ISO: %s\n' "$output_iso"
