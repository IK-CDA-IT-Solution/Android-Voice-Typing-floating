package com.nirzor.voicebubble.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechDiagnosticsTest {

    @Test
    fun failureDiagnosticContainsOnlySanitizedMetadata() {
        val diagnostic = SpeechDiagnostics.failure(
            code = 7,
            languageCode = "bn-BD",
            onDevice = false,
            retry = true
        )

        assertTrue(diagnostic.contains("code=7"))
        assertTrue(diagnostic.contains("language=bn-BD"))
        assertTrue(diagnostic.contains("onDevice=false"))
        assertTrue(diagnostic.contains("retry=true"))
    }

    @Test
    fun invalidLanguageTextIsNotIncludedInDiagnostic() {
        val transcriptLikeValue = "secret transcript আমার কথা"
        val diagnostic = SpeechDiagnostics.failure(
            code = 7,
            languageCode = transcriptLikeValue,
            onDevice = true,
            retry = false
        )

        assertTrue(diagnostic.contains("language=unknown"))
        assertFalse(diagnostic.contains(transcriptLikeValue))
        assertFalse(diagnostic.contains("আমার কথা"))
    }
}
