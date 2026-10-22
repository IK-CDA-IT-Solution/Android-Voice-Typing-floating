package com.nirzor.voicebubble.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechUpdateGuardTest {
    @Test
    fun updaterSeesActiveSpeechAcrossAllEnginesButNotIdleOrFailedDrafts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val idleBubble = SpeechEngine(context)
        val homeEngine = SpeechEngine(context)
        try {
            assertFalse(SpeechEngine.isAnySessionActive)
            assertTrue(homeEngine.stateMachine.startListening())
            assertTrue(SpeechEngine.isAnySessionActive)
            homeEngine.stateMachine.moveToProcessing()
            assertTrue(SpeechEngine.isAnySessionActive)
            homeEngine.stateMachine.moveToError("provider error", 4)
            assertFalse(SpeechEngine.isAnySessionActive)
        } finally {
            idleBubble.destroy()
            homeEngine.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertFalse(SpeechEngine.isAnySessionActive)
    }
}
