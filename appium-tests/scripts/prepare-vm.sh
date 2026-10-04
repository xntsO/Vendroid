#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

BLISSOS_FILE="${BLISSOS_FILE:-Bliss-v16.9.7-x86_64-OFFICIAL-foss-20241011.iso}"
BLISSOS_URL="${BLISSOS_URL:-https://deac-riga.dl.sourceforge.net/project/blissos-x86/Official/BlissOS16/FOSS/Generic/}"
BLISSOS_FALLBACK_URL="${BLISSOS_FALLBACK_URL:-https://downloads.sourceforge.net/project/blissos-x86/Official/BlissOS16/FOSS/Generic/}"
BLISSOS_CONNECT_TIMEOUT="${BLISSOS_CONNECT_TIMEOUT:-15}"
BLISSOS_CHECKSUM_TIMEOUT="${BLISSOS_CHECKSUM_TIMEOUT:-30}"
BLISSOS_DOWNLOAD_TIMEOUT="${BLISSOS_DOWNLOAD_TIMEOUT:-300}"
BLISSOS_LOW_SPEED_TIME="${BLISSOS_LOW_SPEED_TIME:-30}"
VM_DIR="${VM_DIR:-$PROJECT_ROOT/.appium-vm}"

# Check for required tools
EXIT=0
for tool in curl 7z qemu-img sha256sum; do
    if ! command -v "$tool" &> /dev/null; then
        echo "Error: $tool is not installed."
        EXIT=1
    fi
done
[[ "$EXIT" == 1 ]] && exit 1

mkdir -p "$VM_DIR"

ISO_PATH="$VM_DIR/$BLISSOS_FILE"
SHA_PATH="$ISO_PATH.sha256"
EXTRACT_DIR=""
DOWNLOAD_BASES=("${BLISSOS_URL%/}")
if [[ "${BLISSOS_FALLBACK_URL%/}" != "${BLISSOS_URL%/}" ]]; then
    DOWNLOAD_BASES+=("${BLISSOS_FALLBACK_URL%/}")
fi

cleanup() {
    rm -f "$ISO_PATH.part" "$SHA_PATH.part"
    if [[ -n "$EXTRACT_DIR" && -d "$EXTRACT_DIR" ]]; then
        rm -f "$EXTRACT_DIR/kernel" "$EXTRACT_DIR/initrd.img" "$EXTRACT_DIR/system.efs"
        rmdir "$EXTRACT_DIR" || true
    fi
}
trap cleanup EXIT

download() {
    # Each mirror gets one bounded attempt, including redirects and slow reads.
    # Ignore curlrc settings so they cannot add retries beyond these bounds.
    curl --disable --fail --location --silent --show-error \
        --connect-timeout "$BLISSOS_CONNECT_TIMEOUT" --max-time "$2" \
        --speed-limit 1024 --speed-time "$BLISSOS_LOW_SPEED_TIME" \
        --output "$1" \
        --write-out 'HTTP %{http_code}, %{size_download} bytes in %{time_total} seconds\n' \
        "$3"
}

checksum_digest() {
    local -a lines
    local line digest filename
    mapfile -t lines < "$1"
    [[ "${#lines[@]}" == 1 ]] || return 1
    line="${lines[0]%$'\r'}"
    digest="${line:0:64}"
    filename="${line:64}"
    [[ "$digest" =~ ^[[:xdigit:]]{64}$ ]] || return 1
    [[ "$filename" == "  $BLISSOS_FILE" || "$filename" == " *$BLISSOS_FILE" ]] || return 1
    printf '%s' "${digest,,}"
}

verify_iso() {
    local actual
    actual="$(sha256sum "$1")"
    [[ "${actual%% *}" == "$EXPECTED_SHA" ]]
}

if [[ -s "$VM_DIR/kernel" && -s "$VM_DIR/initrd.img" && -s "$VM_DIR/system.efs" ]]; then
    echo "Kernel, initrd, and system.efs already extracted to '$VM_DIR'. Skipping download and extraction."
else
    EXPECTED_SHA=""
    if [[ -f "$SHA_PATH" ]] && EXPECTED_SHA="$(checksum_digest "$SHA_PATH")"; then
        echo "BlissOS checksum is already present and valid."
    else
        rm -f "$SHA_PATH"
        for base in "${DOWNLOAD_BASES[@]}"; do
            echo "Downloading SHA256 checksum from $base..."
            if download "$SHA_PATH.part" "$BLISSOS_CHECKSUM_TIMEOUT" "$base/$BLISSOS_FILE.sha256" && \
                    EXPECTED_SHA="$(checksum_digest "$SHA_PATH.part")"; then
                mv "$SHA_PATH.part" "$SHA_PATH"
                break
            fi
            echo "Checksum download failed or had invalid contents; trying the next mirror."
        done
        if [[ ! -f "$SHA_PATH" ]]; then
            echo "Error: Unable to download a valid BlissOS checksum." >&2
            exit 1
        fi
    fi

    if [[ -f "$ISO_PATH" ]] && verify_iso "$ISO_PATH"; then
        echo "BlissOS ISO is already present and valid."
    else
        rm -f "$ISO_PATH"
        for base in "${DOWNLOAD_BASES[@]}"; do
            echo "Downloading BlissOS ISO from $base..."
            if download "$ISO_PATH.part" "$BLISSOS_DOWNLOAD_TIMEOUT" "$base/$BLISSOS_FILE" && \
                    verify_iso "$ISO_PATH.part"; then
                mv "$ISO_PATH.part" "$ISO_PATH"
                break
            fi
            echo "ISO download failed or did not match SHA256; trying the next mirror."
        done
        if [[ ! -f "$ISO_PATH" ]]; then
            echo "Error: Unable to download a verified BlissOS ISO." >&2
            exit 1
        fi
    fi

    # Publish boot files only after a complete extraction. An interrupted or
    # failed extraction must not become a successful cache hit on the next run.
    echo "Extracting files from ISO..."
    EXTRACT_DIR="$(mktemp -d "$VM_DIR/.extract.XXXXXX")"
    7z e -y -o"$EXTRACT_DIR" "$ISO_PATH" kernel initrd.img system.efs
    for file in kernel initrd.img system.efs; do
        if [[ ! -s "$EXTRACT_DIR/$file" ]]; then
            echo "Error: BlissOS extraction did not produce a nonempty $file." >&2
            exit 1
        fi
    done
    mv "$EXTRACT_DIR/kernel" "$EXTRACT_DIR/initrd.img" "$EXTRACT_DIR/system.efs" "$VM_DIR/"
fi

# Create virtual USB drive image
USB_IMAGE="$VM_DIR/usb-storage.qcow2"
if [[ ! -s "$USB_IMAGE" ]]; then
    echo "Creating virtual USB drive image..."
    qemu-img create -f qcow2 "$USB_IMAGE" 2G
else
    echo "Virtual USB drive image already exists."
fi

echo "VM preparation complete. Files are in $VM_DIR"
# For CI to know where files are
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    echo "vm_dir=$VM_DIR" >> "$GITHUB_OUTPUT"
fi
