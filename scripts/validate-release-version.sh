#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 vMAJOR.MINOR.PATCH" >&2
  exit 2
fi

tag=$1
if [[ ! $tag =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]]; then
  echo "Invalid release version: expected stable vMAJOR.MINOR.PATCH with no leading zeroes." >&2
  exit 1
fi

major=${BASH_REMATCH[1]}
minor=${BASH_REMATCH[2]}
patch=${BASH_REMATCH[3]}

# Bound the major's decimal length before arithmetic, so oversized untrusted
# input can never overflow the shell's integer representation.
if (( ${#major} > 6 )) || { (( ${#major} == 6 )) && [[ $major > 210000 ]]; }; then
  echo "Invalid release version: major must be at most 210000." >&2
  exit 1
fi

major_num=$((10#$major))
minor_num=$((10#$minor))
patch_num=$((10#$patch))
version_code=$((major_num * 10000 + minor_num * 100 + patch_num))
if (( minor_num > 99 || patch_num > 99 || version_code > 2100000000 )); then
  echo "Invalid release version: components exceed Android version-code limits." >&2
  exit 1
fi

printf 'TAG_NAME=%s\nVERSION_NAME=%s\n' "$tag" "${tag#v}"
