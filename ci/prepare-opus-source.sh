#!/usr/bin/env bash
set -euo pipefail

version="1.6.1"
expected_sha256="6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1"
filename="opus-${version}.tar.gz"
primary_url="https://downloads.xiph.org/releases/opus/${filename}"
fallback_url="https://ftp.osuosl.org/pub/xiph/releases/opus/${filename}"
cache_root="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/voica-deps"
archive="${cache_root}/${filename}"
temporary="${archive}.part"

mkdir -p "${cache_root}"

verify_archive() {
    local path="$1"
    [[ -f "${path}" ]] || return 1
    printf '%s  %s\n' "${expected_sha256}" "${path}" | sha256sum -c - >/dev/null 2>&1
}

if ! verify_archive "${archive}"; then
    rm -f "${archive}" "${temporary}"
    downloaded=false
    for url in "${primary_url}" "${fallback_url}"; do
        echo "Downloading ${filename} from ${url}"
        if curl \
            --fail \
            --location \
            --retry 5 \
            --retry-delay 2 \
            --retry-all-errors \
            --connect-timeout 20 \
            --max-time 240 \
            --output "${temporary}" \
            "${url}"; then
            if verify_archive "${temporary}"; then
                mv "${temporary}" "${archive}"
                downloaded=true
                break
            fi
            echo "Checksum mismatch from ${url}; rejecting archive." >&2
        fi
        rm -f "${temporary}"
    done

    if [[ "${downloaded}" != true ]]; then
        echo "Unable to download a verified ${filename} from any pinned source." >&2
        exit 1
    fi
fi

verify_archive "${archive}"
echo "Verified Opus source: ${archive}"

if [[ -n "${GITHUB_ENV:-}" ]]; then
    printf 'VOICA_OPUS_ARCHIVE=%s\n' "${archive}" >> "${GITHUB_ENV}"
fi

printf '%s\n' "${archive}"
