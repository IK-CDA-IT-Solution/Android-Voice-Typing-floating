package com.nirzor.voicebubble.updater

import android.content.pm.PackageInstaller
import java.net.URI

internal enum class InstallerRecoveryDecision {
    RESTORE_AWAITING_RESULT,
    ABANDON_OR_CLEAR
}

/** Never keep the updater busy unless the installer confirms a sealed, committed session. */
internal fun installerRecoveryDecision(
    sessionFound: Boolean,
    isSealed: Boolean?,
    isCommitted: Boolean?
): InstallerRecoveryDecision = if (sessionFound && isSealed == true && isCommitted == true) {
    InstallerRecoveryDecision.RESTORE_AWAITING_RESULT
} else {
    InstallerRecoveryDecision.ABANDON_OR_CLEAR
}

/** Parse a sha256sum-style response, accepting either a bare digest or one filename. */
internal fun parseSha256Checksum(payload: String?): String? {
    val value = payload?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val match = Regex("^([0-9a-fA-F]{64})(?:\\s+\\*?[^\\s]+)?$").matchEntire(value) ?: return null
    return match.groupValues[1].lowercase()
}

/** Release tags must match the app's three-component version-code scheme exactly. */
internal fun parseReleaseVersionCode(versionName: String): Int? {
    val clean = versionName.removePrefix("v")
    if (!Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]?)\\.(0|[1-9][0-9]?)$").matches(clean)) return null
    val parts = clean.split('.').map { it.toIntOrNull() ?: return null }
    if (parts[0] > 210_000 || parts[1] > 99 || parts[2] > 99) return null
    val code = parts[0].toLong() * 10_000L + parts[1] * 100L + parts[2]
    return code.takeIf { it <= 2_100_000_000L }?.toInt()
}

/** Only accept release assets from this application's canonical GitHub release path. */
internal fun isTrustedReleaseAssetUrl(url: String): Boolean = try {
    val uri = URI(url)
    uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) &&
        uri.userInfo == null && uri.port == -1 &&
        uri.path.startsWith("/engrnirzorme02/NIRZOR-Voice-Typing-floating/releases/download/")
} catch (_: Exception) {
    false
}


/** APK identity must match the release metadata and the currently installed signer set exactly. */
internal fun isApkMetadataTrusted(
    archivePackageName: String?,
    archiveVersionName: String?,
    archiveVersionCode: Int,
    expectedPackageName: String,
    expectedVersionName: String,
    expectedVersionCode: Int,
    archiveSigners: Set<String>,
    installedSigners: Set<String>
): Boolean = archivePackageName == expectedPackageName &&
    archiveVersionName == expectedVersionName &&
    archiveVersionCode == expectedVersionCode &&
    archiveSigners.isNotEmpty() && installedSigners.isNotEmpty() &&
    archiveSigners == installedSigners

/** Safe, localized outcomes for installer failures; never expose system-provided text. */
internal fun installerFailureMessage(status: Int): String = when (status) {
    PackageInstaller.STATUS_FAILURE_ABORTED -> "ইনস্টলেশন বাতিল করা হয়েছে। আবার চেষ্টা করুন।"
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "অ্যান্ড্রয়েড ইনস্টলেশনটি ব্লক করেছে। অনুমতি, সিকিউরিটি ও ডিভাইস নীতিমালা যাচাই করুন।"
    PackageInstaller.STATUS_FAILURE_CONFLICT -> "বিদ্যমান প্যাকেজ বা স্বাক্ষরের সাথে দ্বন্দ্ব রয়েছে।"
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "এই সংস্করণটি আপনার ডিভাইসের জন্য উপযুক্ত নয়।"
    PackageInstaller.STATUS_FAILURE_INVALID -> "ইনস্টলার প্যাকেজটি অবৈধ বা ত্রুটিপূর্ণ।"
    PackageInstaller.STATUS_FAILURE_STORAGE -> "ডিভাইসে পর্যাপ্ত মেমোরি খালি নেই।"
    else -> "ইনস্টলেশন ব্যর্থ হয়েছে। আবার চেষ্টা করুন।"
}
