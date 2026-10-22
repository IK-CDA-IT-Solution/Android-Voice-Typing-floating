package com.nirzor.voicebubble.updater

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateValidationTest {
    @Test
    fun cancellationFailsClosedIfAbandonOrEitherLookupFailsOrSessionRemains() {
        assertEquals(PendingInstallCancellation.UNCONFIRMED,
            tryCancelConfirmedPendingSession({ throw SecurityException("lookup unavailable") }, { error("must not abandon") }))
        assertEquals(PendingInstallCancellation.UNCONFIRMED,
            tryCancelConfirmedPendingSession({ true }, { throw SecurityException("abandon rejected") }))
        var lookups = 0
        assertEquals(PendingInstallCancellation.UNCONFIRMED,
            tryCancelConfirmedPendingSession({ if (++lookups == 1) true else throw IllegalStateException("lookup unavailable") }, {}))
        assertEquals(PendingInstallCancellation.UNCONFIRMED,
            tryCancelConfirmedPendingSession({ true }, {}))
    }

    @Test
    fun cancellationOnlyConfirmsAfterSuccessfulAbandonAndConfirmedAbsence() {
        var abandoned = false
        assertEquals(PendingInstallCancellation.ALREADY_MISSING,
            tryCancelConfirmedPendingSession({ false }, { abandoned = true }))
        assertFalse(abandoned)
        assertEquals(PendingInstallCancellation.CONFIRMED_CANCELLED,
            tryCancelConfirmedPendingSession({ !abandoned }, { abandoned = true }))
        assertTrue(abandoned)
    }

    @Test
    fun recoveryPreservesUnknownAndPotentiallyLiveSessions() {
        assertEquals(
            InstallerRecoveryDecision.PRESERVE_SESSION,
            installerRecoveryDecision(sessionFound = true, isSealed = true, isCommitted = true)
        )
        assertEquals(
            InstallerRecoveryDecision.PRESERVE_SESSION,
            installerRecoveryDecision(sessionFound = true, isSealed = true, isCommitted = false)
        )
        assertEquals(
            InstallerRecoveryDecision.PRESERVE_SESSION,
            installerRecoveryDecision(sessionFound = true, isSealed = null, isCommitted = null)
        )
        assertEquals(
            InstallerRecoveryDecision.PRESERVE_SESSION,
            installerRecoveryDecision(sessionFound = null, isSealed = false, isCommitted = false)
        )
        assertEquals(
            InstallerRecoveryDecision.ABANDON_INCOMPLETE,
            installerRecoveryDecision(sessionFound = true, isSealed = false, isCommitted = false)
        )
        assertEquals(
            InstallerRecoveryDecision.ABANDON_INCOMPLETE,
            installerRecoveryDecision(sessionFound = true, isSealed = false, isCommitted = null)
        )
        assertEquals(
            InstallerRecoveryDecision.PRESERVE_SESSION,
            installerRecoveryDecision(sessionFound = true, isSealed = false, isCommitted = true)
        )
        assertEquals(
            InstallerRecoveryDecision.CLEAR_MISSING,
            installerRecoveryDecision(sessionFound = false, isSealed = true, isCommitted = true)
        )
    }

    @Test
    fun checksumParserFailsClosedAndAcceptsSha256sumOutput() {
        val digest = "a".repeat(64)
        assertEquals(digest, parseSha256Checksum("$digest  voicebubble.apk\n"))
        assertEquals(digest, parseSha256Checksum(digest.uppercase()))
        assertNull(parseSha256Checksum(null))
        assertNull(parseSha256Checksum(""))
        assertNull(parseSha256Checksum("$digest trailing extra"))
        assertNull(parseSha256Checksum("${digest.dropLast(1)}g"))
    }

    @Test
    fun releaseVersionCodeRejectsMalformedOrOverflowingVersions() {
        assertEquals(10203, parseReleaseVersionCode("v1.2.3"))
        assertEquals(2_100_000_000, parseReleaseVersionCode("v210000.0.0"))
        assertNull(parseReleaseVersionCode("latest"))
        assertNull(parseReleaseVersionCode("1.2"))
        assertNull(parseReleaseVersionCode("1.2.3-rc1"))
        assertNull(parseReleaseVersionCode("v210000.0.1"))
        assertNull(parseReleaseVersionCode("v01.2.3"))
        assertNull(parseReleaseVersionCode("v1.02.3"))
        assertNull(parseReleaseVersionCode("999999999.0.0"))
    }

    @Test
    fun releaseAssetsMustComeFromCanonicalHttpsReleasePath() {
        assertTrue(isTrustedReleaseAssetUrl("https://github.com/engrnirzorme02/NIRZOR-Voice-Typing-floating/releases/download/v1.2.3/voicebubble.apk"))
        assertFalse(isTrustedReleaseAssetUrl("http://github.com/engrnirzorme02/NIRZOR-Voice-Typing-floating/releases/download/v1/a.apk"))
        assertFalse(isTrustedReleaseAssetUrl("https://example.com/a.apk"))
        assertFalse(isTrustedReleaseAssetUrl("https://github.com.evil.example/engrnirzorme02/NIRZOR-Voice-Typing-floating/releases/download/v1/a.apk"))
    }

    @Test
    fun installerFailureOutcomesRemainActionableAndDoNotEchoSystemMessages() {
        val aborted = installerFailureMessage(PackageInstaller.STATUS_FAILURE_ABORTED)
        val blocked = installerFailureMessage(PackageInstaller.STATUS_FAILURE_BLOCKED)
        val conflict = installerFailureMessage(PackageInstaller.STATUS_FAILURE_CONFLICT)
        assertTrue(aborted.contains("আবার চেষ্টা"))
        assertTrue(aborted.contains("সুনির্দিষ্ট কারণ জানা যায়নি"))
        assertFalse(aborted.contains("বাতিল"))
        assertTrue(blocked.contains("ব্লক করেছে"))
        assertTrue(blocked.contains("যাচাই করুন"))
        assertFalse(blocked.contains("নীতিমালার কারণে"))
        assertTrue(conflict.contains("স্বাক্ষরের"))
        assertEquals("ইনস্টলেশন ব্যর্থ হয়েছে। আবার চেষ্টা করুন।", installerFailureMessage(-999))
    }

    @Test
    fun apkMetadataPreflightRequiresExactPackageVersionAndCurrentSignerSet() {
        val expected = mapOf(
            "archivePackageName" to "com.nirzor.voicebubble",
            "archiveVersionName" to "1.2.3",
            "archiveVersionCode" to 10203,
            "expectedPackageName" to "com.nirzor.voicebubble",
            "expectedVersionName" to "1.2.3",
            "expectedVersionCode" to 10203,
            "archiveSigners" to setOf("current-cert"),
            "installedSigners" to setOf("current-cert")
        )
        fun trusted(
            archivePackage: String? = expected["archivePackageName"] as String,
            archiveVersion: String? = expected["archiveVersionName"] as String,
            archiveCode: Int = expected["archiveVersionCode"] as Int,
            archiveSignerSet: Set<String> = expected["archiveSigners"] as Set<String>,
            installedSignerSet: Set<String> = expected["installedSigners"] as Set<String>
        ) = isApkMetadataTrusted(
            archivePackage,
            archiveVersion,
            archiveCode,
            expected["expectedPackageName"] as String,
            expected["expectedVersionName"] as String,
            expected["expectedVersionCode"] as Int,
            archiveSignerSet,
            installedSignerSet
        )

        assertTrue(trusted())
        assertFalse(trusted(archivePackage = "com.example.other"))
        assertFalse(trusted(archivePackage = null))
        assertFalse(trusted(archiveVersion = "1.2.4"))
        assertFalse(trusted(archiveVersion = null))
        assertFalse(trusted(archiveCode = 10204))
        assertFalse(trusted(archiveSignerSet = setOf("stale-history-cert")))
        assertFalse(trusted(archiveSignerSet = emptySet()))
        assertFalse(trusted(installedSignerSet = emptySet()))
        assertFalse(trusted(archiveSignerSet = setOf("current-cert", "extra-cert")))
    }
}
