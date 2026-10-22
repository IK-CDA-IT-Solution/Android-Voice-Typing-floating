package com.nirzor.voicebubble.speech

/** Formats only non-transcript recognizer metadata for troubleshooting logs. */
internal object SpeechDiagnostics {
    private val safeLanguageCode = Regex("[A-Za-z0-9_-]{1,35}")

    fun failure(code: Int, languageCode: String, onDevice: Boolean, retry: Boolean): String {
        val safeLanguage = languageCode.takeIf(safeLanguageCode::matches) ?: "unknown"
        return "recognizer_error code=$code language=$safeLanguage onDevice=$onDevice retry=$retry"
    }
}
