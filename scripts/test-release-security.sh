#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOW="$ROOT_DIR/.github/workflows/release.yml"

fail() {
  printf 'Release security regression failed: %s\n' "$*" >&2
  exit 1
}

contains() {
  grep -Fq -- "$1" "$WORKFLOW" || fail "workflow is missing required guard: $1"
}

# Ensure every action is pinned to a full immutable commit SHA.
if grep -E '^[[:space:]]*uses:[[:space:]]*[^[:space:]#]+@' "$WORKFLOW" |
  grep -Ev '@[0-9a-f]{40}([[:space:]]|$)' >/dev/null; then
  fail 'an action reference is not pinned to a full 40-character commit SHA'
fi

contains 'persist-credentials: false'
contains "github.event_name == 'workflow_dispatch'"
contains "github.ref == 'refs/heads/main'"
contains "vars.RELEASE_SIGNING_ENABLED == 'true'"
contains 'name: release-signing'
contains 'if [[ "$new_cert" != "$PREVIOUS_CERT" ]]; then'
contains 'releases/latest'
contains 'voicebubble.apk'
contains 'mktemp -d "$RUNNER_TEMP/release-keystore.XXXXXX"'
contains 'trap cleanup_key EXIT'
contains "trap 'exit 129' HUP"
contains "trap 'exit 130' INT"
contains "trap 'exit 143' TERM"
contains 'umask 077'
contains '-PunsignedRelease=true'

build_job="$(awk '
  /^  build-and-test:/ { capture = 1 }
  /^  sign-release:/ { capture = 0 }
  capture { print }
' "$WORKFLOW")"
if grep -Fq 'secrets.' <<<"$build_job"; then
  fail 'the read-only build job references signing or other secrets'
fi
if grep -Fq 'GITHUB_ENV' "$WORKFLOW"; then
  fail 'workflow writes values to GITHUB_ENV (passwords must never be persisted there)'
fi
if [[ "$(grep -c 'contents: write' "$WORKFLOW")" -ne 1 ]] ||
  ! awk '/^  publish-release:/ { capture = 1 } capture && /contents: write/ { found = 1 } END { exit !found }' "$WORKFLOW"; then
  fail 'contents: write must be scoped only to the separate publisher job'
fi

if grep -Fq 'v1.0.3' "$WORKFLOW"; then
  fail 'workflow contains a tag-specific exception for v1.0.3'
fi

# Guard the privileged jobs against repository checkout, scripts, and Gradle.
privileged_jobs="$(awk '
  /^  sign-release:/ { capture = 1 }
  /^  publish-release:/ { capture = 0 }
  capture { print }
' "$WORKFLOW")"
[[ -n "$privileged_jobs" ]] || fail 'sign-release job could not be found'
if grep -E 'actions/checkout@|scripts/|gradlew|gradle ' <<<"$privileged_jobs" >/dev/null; then
  fail 'a privileged job checks out or executes repository code/Gradle'
fi
publish_job="$(awk '/^  publish-release:/ { capture = 1 } capture { print }' "$WORKFLOW")"
if grep -E 'actions/checkout@|scripts/|gradlew|gradle ' <<<"$publish_job" >/dev/null; then
  fail 'publish-release checks out or executes repository code/Gradle'
fi

# Model the workflow gate: tag pushes never receive signing privileges, even
# when the variable is accidentally enabled. Signing is manual from main only.
may_sign() {
  local event=$1 ref=$2 enabled=$3
  [[ "$event" == workflow_dispatch && "$ref" == refs/heads/main && "$enabled" == true ]]
}
for tag in v1.0.3 v1.2.3 v210000.99.99; do
  if may_sign push "refs/tags/$tag" true; then
    fail "arbitrary tag $tag bypassed the manual-main signing gate"
  fi
done
may_sign workflow_dispatch refs/heads/main true || fail 'configured manual main release was unexpectedly disabled'
if may_sign workflow_dispatch refs/heads/main false; then
  fail 'signing was enabled without RELEASE_SIGNING_ENABLED=true'
fi
if may_sign workflow_dispatch refs/heads/release/v1 true; then
  fail 'a non-main branch bypassed the signing gate'
fi

# Exercise the fail-closed fingerprint comparison for arbitrary valid tags,
# including the historical v1.0.3 tag. This mirrors the inline comparison in
# the isolated signing step; static checks above ensure it remains unconditional.
valid_stable_tag() {
  [[ "$1" =~ ^v(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]]
}
compare_certificates() {
  local tag=$1 candidate=$2 previous=$3
  valid_stable_tag "$tag" || return 1
  [[ "$candidate" =~ ^[[:xdigit:]]{64}$ && "$previous" =~ ^[[:xdigit:]]{64}$ ]] || return 1
  [[ "${candidate,,}" == "${previous,,}" ]]
}
matching_cert='0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
other_cert='abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789'
for tag in v1.0.3 v1.2.3 v210000.99.99; do
  compare_certificates "$tag" "$matching_cert" "$matching_cert" ||
    fail "matching signing fingerprint was rejected for $tag"
  if compare_certificates "$tag" "$matching_cert" "$other_cert"; then
    fail "mismatched signing fingerprint was accepted for $tag"
  fi
done
if compare_certificates v1.0.3 "$matching_cert" invalid; then
  fail 'invalid previous fingerprint was not rejected'
fi

printf 'Release security regression tests passed.\n'
