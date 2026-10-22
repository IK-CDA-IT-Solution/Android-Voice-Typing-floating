# Release signing: owner configuration and operating procedure

## Safe default

Privileged signing and publishing are **disabled by default**. The `sign-release` and `publish-release` jobs run only for a manually dispatched workflow on `main` when the repository variable `RELEASE_SIGNING_ENABLED` is exactly `true`. Pushing any tag (including `v1.0.3`) or release branch only runs the read-only build/test job; it cannot invoke signing or publishing.

The build/test job checks out with `persist-credentials: false`, has only `contents: read`, receives no signing secrets, verifies `HEAD == github.sha`, runs release-version and security regression tests before Gradle unit tests, and uploads an unsigned APK. Before upload, pinned Android `aapt` from build-tools 35.0.0 verifies the APK manifest's `versionCode` and `versionName` exactly match the requested tag. Its unsigned Release variant depends on the project Gradle configuration honoring `-PunsignedRelease=true` (see the release workflow). The isolated signing job downloads that artifact, does not check out the repository and does not execute Gradle or repository scripts. It uses pinned Android build-tools 35.0.0 and trusted inline commands. A separate publish job receives `contents: write` and likewise has no repository checkout.

Before exposing signing secrets, the signer requires the requested tag to be absent (GitHub API HTTP 404 only; existing tag or any other response fails closed), then validates that the requested stable tag's Android `versionCode` is **strictly greater** than the latest published stable release tag's `versionCode`. Lower, equal, malformed, missing, or out-of-range versions fail closed. Equal-version rebuilds are deliberately rejected: publish only a higher version with a fresh, unused tag; do not replace a prior tag/release or reuse a version. The publisher repeats the absent-tag check and also requires that no release already exists for the tag. Release workflow runs share a non-canceling concurrency group to serialize manual releases and avoid racing the latest-published baseline.

The signer verifies the newest published release APK and compares its SHA-256 signing-certificate fingerprint with the new signed APK. Missing, malformed, or unverifiable signing identity fails closed. A mismatch also fails closed by default. For an intentional signing-key migration, the manual `workflow_dispatch` form exposes a default-false boolean input named `confirm_signing_key_migration`. Setting it to `true` explicitly approves a certificate change for that single workflow run only; the signer logs the new certificate fingerprint, and the published APK then becomes the baseline for subsequent continuity checks. Do not enable this input on normal releases. This is an owner-operated escape hatch, not a permanently enabled mode; future runs default back to strict same-signer-only validation.

A key migration changes Android package signing identity. Existing installations signed by the previous key generally cannot install a differently signed APK as an in-place update. The owner must plan a manual uninstall/reinstall or a separately designed Android signing-key rotation lineage before publishing the migration release. Uninstalling can erase local app data and Android Keystore-protected secrets, so verify backup and recovery first.

## Required owner configuration before enabling

Do not enable signing until all of these settings have been independently reviewed in GitHub repository settings:

1. Create the GitHub Actions environment named **`release-signing`**.
2. Configure required reviewers/approval for that environment; prevent self-review where the repository plan/settings permit it.
3. Restrict the environment's permitted deployment refs to the protected default branch **`main`**. Do not allow tags or arbitrary branches.
4. Add `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` as **environment-scoped secrets on `release-signing` only**. Never copy them into repository- or organization-wide secrets for this workflow. Remove any repository-wide copies of these production signing secrets before enabling this pipeline.
5. Protect `main` with the repository's required review and status-check rules so only reviewed workflow changes can reach it.
6. Only after steps 1–5, set the repository Actions variable `RELEASE_SIGNING_ENABLED` to the exact string `true`. Leave it unset or set to any other value to keep privileged jobs disabled.

The workflow YAML is not, by itself, the trust boundary. Repository/environment rules, protected refs, reviewer approval, and the scope/removal of secrets are owner-side controls that must be configured and audited separately. This repository change does not inspect or modify any GitHub settings, secrets, variables, or production keys; their current state is unverified.

## Release operation

1. Pushes of `v*` tags or `release/v*` branches run only the read-only build/test workflow. They do not sign or publish.
2. Once owner-side protection is configured, use **Run workflow** on the protected `main` ref and provide a fresh stable `vMAJOR.MINOR.PATCH` tag. Version validation, release-policy checks, and Android unit tests run before an unsigned APK is built.
3. For ordinary releases, leave `confirm_signing_key_migration` unchecked. GitHub's `release-signing` environment approval gates the isolated signer, which requires the new APK certificate to match the latest published `voicebubble.apk`.
4. For the deliberate initial migration to a new Environment Keystore, verify the new key/alias are intended and the data/reinstall implications are understood, then set `confirm_signing_key_migration=true` for that run only. The next workflow dispatch defaults back to strict continuity. Do not use the flag for routine releases.
5. The release must use a new tag whose version code is higher than the latest published release; a same-version rebuild is rejected. The separate publisher verifies the read-only build commit equals `github.sha`, requires the requested tag and release not to exist, then creates the release targeting `github.sha` and attaches `voicebubble.apk` plus `voicebubble.apk.sha256` only after successful signing and continuity checks.

If the repository has no latest release with a verifiable `voicebubble.apk`, signing intentionally fails. Do not work around this by adding a tag exception.
