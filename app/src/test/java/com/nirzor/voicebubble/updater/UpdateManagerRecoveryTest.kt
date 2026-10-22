package com.nirzor.voicebubble.updater

import android.app.Application
import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.nirzor.voicebubble.BuildConfig
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [24, 25, 26, 27, 28, 29, 34])
class UpdateManagerRecoveryTest {
    private lateinit var context: Context
    private lateinit var installer: PackageInstaller
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var cachedApk: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        installer = context.packageManager.packageInstaller
        prefs = context.getSharedPreferences("updater_state", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        cachedApk = File(context.cacheDir, "voicebubble_update.apk")
        cachedApk.delete()
    }

    @After
    fun tearDown() {
        cachedApk.delete()
        prefs.edit().clear().commit()
    }

    @Test
    fun preservedRecoveryRetryKeepsPendingStateFileAndExistingSession() {
        val sessionId = createSession(
            sealed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
            committed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        )
        seedPending(sessionId)
        cachedApk.writeText("cached-but-untrusted-test-fixture")

        val manager = UpdateManager(context)
        val expected = UpdateStatus.RecoveringInstall(sessionId)
        assertEquals(expected, manager.status.value)
        assertTrue(manager.isBusy.value)
        assertPending(sessionId)
        assertTrue(cachedApk.exists())
        assertTrue(installer.mySessions.any { it.sessionId == sessionId })
        val sessionCount = installer.mySessions.size
        assertTrue(shadowOf(installer).allSessionCallbacks.isNotEmpty())

        manager.retry()

        assertEquals(expected, manager.status.value)
        assertTrue(manager.isBusy.value)
        assertPending(sessionId)
        assertTrue(cachedApk.exists())
        assertEquals(sessionCount, installer.mySessions.size)
    }

    @Test
    fun missingSessionClearsPendingStateAndCachedApk() {
        val missingId = 909090
        seedPending(missingId)
        cachedApk.writeText("stale")

        val manager = UpdateManager(context)

        assertTrue(manager.status.value is UpdateStatus.Error)
        assertFalse(manager.isBusy.value)
        assertFalse(prefs.contains("pending_version_code"))
        assertFalse(prefs.contains("pending_session_id"))
        assertFalse(cachedApk.exists())
    }

    @Test
    @Config(sdk = [26, 27, 28, 29, 34])
    fun unsealedUncommittedSessionIsAbandonedWhenSealedFlagIsSupported() {
        val sessionId = createSession(sealed = false, committed = false)
        seedPending(sessionId)
        cachedApk.writeText("incomplete")

        val manager = UpdateManager(context)

        assertTrue(manager.status.value is UpdateStatus.Error)
        assertFalse(manager.isBusy.value)
        assertFalse(prefs.contains("pending_version_code"))
        assertFalse(prefs.contains("pending_session_id"))
        assertFalse(cachedApk.exists())
        assertFalse(installer.mySessions.any { it.sessionId == sessionId })
    }

    @Test
    @Config(sdk = [29, 34])
    fun committedSessionIsPreservedEvenWhenUnsealed() {
        val sessionId = createSession(sealed = false, committed = true)
        seedPending(sessionId)

        val manager = UpdateManager(context)

        assertEquals(UpdateStatus.RecoveringInstall(sessionId), manager.status.value)
        assertTrue(manager.isBusy.value)
        assertPending(sessionId)
    }

    @Test
    @Config(sdk = [26, 27, 28, 29, 34])
    fun sealedSessionIsPreservedEvenBeforeCommitFlagIsConfirmed() {
        val sessionId = createSession(sealed = true, committed = false)
        seedPending(sessionId)
        val manager = UpdateManager(context)
        assertEquals(UpdateStatus.RecoveringInstall(sessionId), manager.status.value)
        manager.retry()
        assertEquals(UpdateStatus.RecoveringInstall(sessionId), manager.status.value)
        assertTrue(manager.isBusy.value)
        assertPending(sessionId)
    }

    @Test
    fun callbackFailureResolvesOnlyTheMatchingPendingSession() {
        val targetSession = createSession(
            sealed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
            committed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        )
        val unrelatedSession = createSession()
        seedPending(targetSession)
        cachedApk.writeText("pending")
        val manager = UpdateManager(context)
        assertEquals(UpdateStatus.RecoveringInstall(targetSession), manager.status.value)
        assertTrue(shadowOf(installer).allSessionCallbacks.isNotEmpty())

        shadowOf(installer).setSessionFails(unrelatedSession)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(UpdateStatus.RecoveringInstall(targetSession), manager.status.value)
        assertTrue(manager.isBusy.value)
        assertPending(targetSession)

        shadowOf(installer).setSessionFails(targetSession)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(manager.status.value is UpdateStatus.Error)
        assertFalse(manager.isBusy.value)
        assertFalse(prefs.contains("pending_version_code"))
        assertFalse(prefs.contains("pending_session_id"))
    }

    @Test
    @Config(sdk = [24, 25])
    fun legacyUncommittedSessionCanBeExplicitlyCancelledAndClearedForRetry() {
        val sessionId = createSession()
        val unrelatedSession = createSession()
        seedPending(sessionId)
        cachedApk.writeText("pending-uncommitted")
        val manager = UpdateManager(context)
        manager.retry()
        assertEquals(UpdateStatus.RecoveringInstall(sessionId), manager.status.value)
        assertTrue(manager.isBusy.value)
        assertTrue(cachedApk.exists())

        assertTrue(manager.cancelPendingInstallForRetry(sessionId))
        assertFalse(installer.mySessions.any { it.sessionId == sessionId })
        assertTrue(installer.mySessions.any { it.sessionId == unrelatedSession })
        assertFalse(manager.isBusy.value)
        assertTrue((manager.status.value as UpdateStatus.Error).canRetry)
        assertFalse(prefs.contains("pending_session_id"))
        assertFalse(prefs.contains("pending_version_code"))
        assertFalse(cachedApk.exists())
        manager.reset()
        assertEquals(UpdateStatus.Idle, manager.status.value)
        assertFalse(manager.cancelPendingInstallForRetry(sessionId))
    }

    @Test
    fun cancellationRejectsAnUnrelatedOrStaleConfirmedSessionId() {
        val sessionId = createSession(sealed = true, committed = true)
        val unrelatedSession = createSession()
        seedPending(sessionId)
        cachedApk.writeText("preserve")
        val manager = UpdateManager(context)
        assertFalse(manager.cancelPendingInstallForRetry(unrelatedSession))
        assertPending(sessionId)
        assertTrue(cachedApk.exists())
        assertTrue(manager.isBusy.value)
        assertTrue(installer.mySessions.any { it.sessionId == sessionId })
        assertTrue(installer.mySessions.any { it.sessionId == unrelatedSession })
    }

    @Test
    @Config(sdk = [24, 25])
    @Suppress("DEPRECATION")
    fun legacyPendingCommitIsPreservedUntilMatchingSuccessfulInstallerCallback() {
        val sessionId = createSession()
        seedPending(sessionId)
        val manager = UpdateManager(context)
        manager.retry()
        assertEquals(UpdateStatus.RecoveringInstall(sessionId), manager.status.value)
        assertTrue(manager.isBusy.value)
        assertPending(sessionId)
        val callbacks = shadowOf(installer).allSessionCallbacks.toList()
        callbacks.forEach { it.onFinished(sessionId + 1000, true) }
        assertEquals(UpdateStatus.RecoveringInstall(sessionId), manager.status.value)

        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        installed.versionCode = BuildConfig.VERSION_CODE + 1
        installed.versionName = "confirmed-test-update"
        shadowOf(context.packageManager).installPackage(installed)
        callbacks.forEach { it.onFinished(sessionId, true) }
        assertTrue(manager.status.value is UpdateStatus.Installed)
        assertFalse(manager.isBusy.value)
        assertFalse(prefs.contains("pending_session_id"))
        assertFalse(prefs.contains("pending_version_code"))
    }

    private fun seedPending(sessionId: Int) {
        prefs.edit()
            .putInt("pending_version_code", BuildConfig.VERSION_CODE + 1)
            .putInt("pending_session_id", sessionId)
            .commit()
    }

    private fun assertPending(sessionId: Int) {
        assertEquals(BuildConfig.VERSION_CODE + 1, prefs.getInt("pending_version_code", 0))
        assertEquals(sessionId, prefs.getInt("pending_session_id", -1))
    }

    private fun createSession(sealed: Boolean? = null, committed: Boolean? = null): Int {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        // The shadow's completion helper requires a materialized Session object.
        installer.openSession(sessionId).close()
        val info = installer.mySessions.first { it.sessionId == sessionId }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && sealed != null) {
            ReflectionHelpers.setField(info, "sealed", sealed)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && committed != null) {
            ReflectionHelpers.setField(info, "isCommitted", committed)
        }
        assertNotNull(installer.mySessions.firstOrNull { it.sessionId == sessionId })
        return sessionId
    }
}
