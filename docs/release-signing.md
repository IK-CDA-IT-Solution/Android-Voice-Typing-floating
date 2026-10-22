# Release signing: owner configuration and operating procedure

## Safe default

Privileged signing and publishing are **disabled by default**. The `sign-release` and `publish-release` jobs run only for a manually dispatched workflow on `main` when the repository variable `RELEASE_SIGNING_ENABLED` is exactly `true`. Pushing any tag (including `v1.0.3`) or release branch only runs the read-only build/test job; it cannot invoke signing or publishing.

The build/test job checks out with `persist-credentials: false`, has only `contents: read`, receives no signing secrets, runs release-version and security regression tests before Gradle unit tests, and uploads an unsigned APK. Its unsigned Release variant depends on the project Gradle configuration honoring `-PunsignedRelease=true` (see the release workflow). The isolated signing job downloads that artifact, does not check out the repository and does not execute Gradle or repository scripts. It uses pinned Android build-tools 35.0.0 and trusted inline commands. A separate publish job receives `contents: write` and likewise has no repository checkout.

The signer verifies the newest published release APK and compares its SHA-256 signing-certificate fingerprint with the new signed APK. Missing, malformed, unverifiable, or mismatched previous signing identity fails closed. There is no special-case tag or first-release bypass. If an intentional signing-key migration is ever required, do not add a workflow exception: handle it through a separately reviewed owner-controlled migration process.

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
2. Once owner-side protection is configured, use **Run workflow** on the protected `main` ref and provide a stable `vMAJOR.MINOR.PATCH` tag. Version validation, release-policy checks, and Android unit tests run before an unsigned APK is built.
3. GitHub's `release-signing` environment approval gates the isolated signer. It compares the new APK certificate fingerprint to the latest published `voicebubble.apk`; any failure prevents the publish job.
4. The separate publisher creates the release and attaches `voicebubble.apk` plus `voicebubble.apk.sha256` only after successful signing and continuity verification.

If the repository has no latest release with a verifiable `voicebubble.apk`, signing intentionally fails. Do not work around this by adding a tag exception.
