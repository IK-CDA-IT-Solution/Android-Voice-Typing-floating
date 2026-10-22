#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VALIDATOR="$ROOT_DIR/scripts/validate-release-version.sh"

assert_valid() {
  local input=$1 expected=$2 actual
  actual=$("$VALIDATOR" "$input")
  [[ $actual == "$expected" ]] || {
    printf 'Expected %q to produce %q, got %q\n' "$input" "$expected" "$actual" >&2
    exit 1
  }
}

assert_invalid() {
  local input=$1
  if "$VALIDATOR" "$input" >/dev/null 2>&1; then
    printf 'Unexpectedly accepted invalid release version: %q\n' "$input" >&2
    exit 1
  fi
}

assert_valid 'v1.2.3' $'TAG_NAME=v1.2.3\nVERSION_NAME=1.2.3'
assert_valid 'v0.0.0' $'TAG_NAME=v0.0.0\nVERSION_NAME=0.0.0'
assert_valid 'v210000.0.0' $'TAG_NAME=v210000.0.0\nVERSION_NAME=210000.0.0'
assert_valid 'v209999.99.99' $'TAG_NAME=v209999.99.99\nVERSION_NAME=209999.99.99'

assert_invalid ''
assert_invalid '1.2.3'
assert_invalid 'v01.2.3'
assert_invalid 'v1.02.3'
assert_invalid 'v1.2.03'
assert_invalid 'v1.2.3-rc.1'
assert_invalid 'v1.2.3+metadata'
assert_invalid 'v210000.0.1'
assert_invalid 'v210001.0.0'
assert_invalid 'v999999999999999999999999999999999999.0.0'
assert_invalid 'v1.100.0'
assert_invalid 'v1.0.100'
assert_invalid 'v1.2.3; touch /tmp/release-version-injection-should-not-exist'
assert_invalid 'v1.2.3$(touch /tmp/release-version-injection-should-not-exist)'
assert_invalid $'v1.2.3\nTAG_NAME=attacker'

[[ ! -e /tmp/release-version-injection-should-not-exist ]] || {
  echo 'An injection-shaped input executed a command.' >&2
  exit 1
}

echo 'Release-version validator regression tests passed.'
